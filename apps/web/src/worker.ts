// The pipeline worker. Without "remove music" it remuxes the file to MP4 (packets copied where the
// codec fits). With it, the primary video track is copied and the audio is replaced by Spleeter's
// vocals, separated window by window and encoded as it goes (plan §3, render case 2).
import {
  ALL_FORMATS,
  AudioSample,
  AudioSampleSink,
  AudioSampleSource,
  BlobSource,
  BufferTarget,
  canEncodeAudio,
  Conversion,
  EncodedPacketSink,
  EncodedVideoPacketSource,
  Input,
  type InputAudioTrack,
  type InputVideoTrack,
  Mp4OutputFormat,
  Output,
  QUALITY_HIGH,
  StreamTarget,
  WebMOutputFormat,
  type AudioCodec,
  type StreamTargetChunk,
} from 'mediabunny';
import { RATE } from './audio/grid';
import { loadSpleeter, separate } from './audio/separate';
import { censorFaces, type FilterOps } from './core/filterOps';
import type { FromWorker, Phase, ToWorker } from './protocol';

let canceled = false;
let cancelCurrent: (() => Promise<void>) | null = null;

function post(msg: FromWorker, transfer: Transferable[] = []): void {
  self.postMessage(msg, { transfer });
}

function progress(phase: Phase, pct: number): void {
  post({ type: 'progress', phase, pct });
}

self.onmessage = (e: MessageEvent<ToWorker>) => {
  const msg = e.data;
  if (msg.type === 'cancel') {
    canceled = true;
    void cancelCurrent?.();
  } else if (msg.type === 'start') {
    void run(msg);
  }
};

async function run({ file, ops, target }: Extract<ToWorker, { type: 'start' }>): Promise<void> {
  canceled = false;
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  let disk: FileSystemWritableFileStream | null = null;
  try {
    progress('open', 0);
    await analyzePicture(input, ops);
    const mp4 = new Mp4OutputFormat({ fastStart: target.kind === 'buffer' ? 'in-memory' : false });
    const music = ops.removeMusic ? await planMusicRemoval(input) : null;
    // AAC in MP4 where the browser can encode it. Otherwise Opus, in WebM when the video fits WebM.
    const format = music?.codec === 'opus' && music.webm ? new WebMOutputFormat() : mp4;
    if (music?.codec === 'opus') {
      post({
        type: 'notice',
        message: `This browser cannot encode AAC audio, so the audio is Opus${format === mp4 ? '' : ' and the file is WebM (.webm)'}.`,
      });
    }
    const buffer = target.kind === 'buffer' ? new BufferTarget() : null;
    if (target.kind === 'file') disk = await target.handle.createWritable();
    const output = new Output({
      format,
      target: buffer ?? new StreamTarget(commitOnlyOnSuccess(disk!), { chunked: true }),
    });

    if (music) await removeMusic(input, output, music);
    else await remux(input, output);

    progress('finalize', 1);
    if (disk) await disk.close(); // the only place the file on disk is committed
    if (buffer) post({ type: 'done', buffer: buffer.buffer!, mimeType: format.mimeType }, [buffer.buffer!]);
    else post({ type: 'done', mimeType: format.mimeType });
  } catch (err) {
    await disk?.abort().catch(() => {}); // discard the partial output
    const message = canceled ? 'Canceled.' : err instanceof Error ? err.message : String(err);
    post({ type: 'error', message, canceled });
  } finally {
    cancelCurrent = null;
    input.dispose();
  }
}

/**
 * Pass 1 when anything in the picture is censored. Until the render pass exists, an EDL with anything in
 * it stops the export: the video is never written with what should have been covered left bare. An empty
 * EDL (analyzed, nothing to cover) goes on to the copy paths below (plan §3, render case 2).
 */
async function analyzePicture(input: Input, ops: FilterOps): Promise<void> {
  if (!censorFaces(ops) && !ops.censorNsfw) return;
  const video = await input.getPrimaryVideoTrack();
  if (!video) return; // no picture, nothing to censor in it
  progress('analyze', 0);
  const { analyze } = await import('./vision/analyze');
  const edl = await analyze(
    video,
    ops,
    (pct) => progress('analyze', pct),
    () => {
      if (canceled) throw new Error('Canceled.');
    },
  );
  if (edl.censorIntervalsMs.length > 0 || edl.faceTracks.length > 0) {
    throw new Error(
      `The analysis found ${edl.faceTracks.length} face span(s) and ${edl.censorIntervalsMs.length} whole-frame span(s) to cover, and this build cannot render censored video yet.`,
    );
  }
}

/** Milestone 1 path: copy every track it can into the new container. */
async function remux(input: Input, output: Output): Promise<void> {
  const conversion = await Conversion.init({ input, output, showWarnings: false });
  cancelCurrent = () => conversion.cancel();
  const dropped = conversion.discardedTracks.filter((d) => d.track.type !== 'subtitle');
  if (!conversion.isValid || dropped.length > 0) {
    const why = dropped.map((d) => `${d.track.type} track: ${d.reason}`).join('; ');
    throw new Error(`This file cannot be converted${why ? ` (${why})` : ''}.`);
  }
  conversion.onProgress = (p) => progress('remux', p);
  if (canceled) await conversion.cancel();
  await conversion.execute();
}

interface MusicPlan {
  video: InputVideoTrack | null;
  audio: InputAudioTrack;
  codec: AudioCodec;
  /** Whether WebM can hold the video packets as they are. */
  webm: boolean;
}

async function planMusicRemoval(input: Input): Promise<MusicPlan> {
  const audio = await input.getPrimaryAudioTrack();
  if (!audio) throw new Error('This video has no audio, so there is no music to remove.');
  if (!(await audio.canDecode())) {
    throw new Error(`This browser cannot decode the video's audio (${audio.codec ?? 'unknown codec'}).`);
  }
  const video = await input.getPrimaryVideoTrack();
  if (video && !video.codec) throw new Error('The video uses a codec this app does not know.');
  const options = { numberOfChannels: 2, sampleRate: RATE, quality: QUALITY_HIGH };
  const codec: AudioCodec | null = (await canEncodeAudio('aac', options))
    ? 'aac'
    : (await canEncodeAudio('opus', options))
      ? 'opus'
      : null;
  if (!codec) throw new Error('This browser can encode neither AAC nor Opus audio.');
  const webm = !video || new WebMOutputFormat().getSupportedVideoCodecs().includes(video.codec!);
  return { video, audio, codec, webm };
}

/**
 * The primary video track's packets are copied as they are; the audio is replaced by Spleeter's
 * vocals. Both keep their source timestamps (the muxer writes the start offsets), so they stay in
 * sync. Video is copied in lockstep with the audio, so the muxer never queues more than about one
 * window of it. Every other audio track is dropped: it would still carry music.
 */
async function removeMusic(input: Input, output: Output, { video, audio, codec }: MusicPlan): Promise<void> {
  if (video && !output.format.getSupportedVideoCodecs().includes(video.codec!)) {
    throw new Error(`The video (${video.codec}) cannot be copied into ${output.format.fileExtension} with ${codec} audio.`);
  }
  const videoSource = video ? new EncodedVideoPacketSource(video.codec!) : null;
  if (video && videoSource) {
    output.addVideoTrack(videoSource, { transformationMatrix: await video.getTransformationMatrix() });
  }
  const audioSource = new AudioSampleSource({ codec, quality: QUALITY_HIGH });
  output.addAudioTrack(audioSource);
  cancelCurrent = () => output.cancel();
  const stopIfCanceled = () => {
    if (canceled) throw new Error('Canceled.');
  };

  progress('model', 0);
  const spleeter = await loadSpleeter(new URL(`${import.meta.env.BASE_URL}models/spleeter-2stems.onnx`, self.location.href).href);
  try {
    stopIfCanceled();
    await output.start();
    const packets = video ? new EncodedPacketSink(video).packets() : null;
    const decoderConfig = (await video?.getDecoderConfig()) ?? undefined;
    let first = true;
    let pending = await packets?.next();
    /** Copies video packets (decode order) until one is presented at or after `until`. */
    const copyVideo = async (until: number) => {
      while (videoSource && pending && !pending.done && pending.value.timestamp < until) {
        await videoSource.add(pending.value, first ? { decoderConfig } : undefined);
        first = false;
        pending = await packets!.next();
      }
    };

    const duration = await input.computeDuration();
    progress('music', 0);
    for await (const chunk of separate(new AudioSampleSink(audio).samples(), spleeter.mask)) {
      stopIfCanceled();
      const data = new Float32Array(2 * chunk.left.length);
      data.set(chunk.left);
      data.set(chunk.right, chunk.left.length);
      const sample = new AudioSample({ data, format: 'f32-planar', numberOfChannels: 2, sampleRate: RATE, timestamp: chunk.timestamp });
      try {
        await audioSource.add(sample);
      } finally {
        sample.close();
      }
      const end = chunk.timestamp + chunk.left.length / RATE;
      await copyVideo(end);
      progress('music', duration > 0 ? Math.min(1, end / duration) : 0);
    }
    stopIfCanceled();
    await copyVideo(Infinity);
    await output.finalize();
  } catch (err) {
    await output.cancel().catch(() => {});
    throw err;
  } finally {
    await spleeter.release().catch(() => {});
  }
}

/**
 * Mediabunny closes its target even on cancel or error, and closing a FileSystemWritableFileStream
 * commits it. Forward the writes but leave close/abort to `run`, so a failed export never lands on disk.
 */
function commitOnlyOnSuccess(disk: FileSystemWritableFileStream): WritableStream<StreamTargetChunk> {
  return new WritableStream({ write: (chunk) => disk.write(chunk) });
}
