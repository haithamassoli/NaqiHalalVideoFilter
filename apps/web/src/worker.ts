// The pipeline worker: pass 1 (analysis -> EDL) when anything in the picture is censored, then one pass that
// writes the output:
//   - nothing selected, or nothing to change (empty EDL, music kept) -> refused, nothing is written;
//   - empty EDL (or no censoring) with music removal -> video packets copied, audio replaced by Spleeter's vocals;
//   - non-empty EDL -> every frame decoded, covered per the EDL (render/blur.ts), re-encoded (H.264 where the
//     browser can), with the vocals or the original audio.
// Audio drives the pass and video follows it in lockstep, so the muxer never queues more than about one
// audio step (one Spleeter window at most) of the other track. Output streams to disk through StreamTarget.
import {
  ALL_FORMATS,
  AudioSample,
  AudioSampleSink,
  AudioSampleSource,
  BlobSource,
  BufferTarget,
  canEncodeAudio,
  canEncodeVideo,
  EncodedAudioPacketSource,
  type EncodedPacket,
  EncodedPacketSink,
  EncodedVideoPacketSource,
  Input,
  type InputAudioTrack,
  type InputVideoTrack,
  Mp4OutputFormat,
  Output,
  QUALITY_HIGH,
  StreamTarget,
  VideoSample,
  VideoSampleSink,
  VideoSampleSource,
  WebMOutputFormat,
  type AudioCodec,
  type StreamTargetChunk,
  type VideoCodec,
} from 'mediabunny';
import { RATE } from './audio/grid';
import { loadSpleeter, separate, type Masker } from './audio/separate';
import type { Edl } from './core/edl';
import { censorFaces, type FilterOps } from './core/filterOps';
import { pickFormat } from './outputFormat';
import type { FromWorker, Phase, ToWorker } from './protocol';
import { Censor } from './render/blur';

let canceled = false;
let cancelCurrent: (() => Promise<void>) | null = null;

function post(msg: FromWorker, transfer: Transferable[] = []): void {
  self.postMessage(msg, { transfer });
}

const notice = (message: string) => post({ type: 'notice', message });

let shownPhase: Phase | null = null;
let phaseStart = 0;
let lastPost = 0;

/** Progress at most four times a second per phase, with an ETA once the phase has run a few seconds. */
function progress(phase: Phase, pct: number): void {
  const now = performance.now();
  if (phase !== shownPhase) {
    shownPhase = phase;
    phaseStart = now;
  } else if (now - lastPost < 250 && pct < 1) {
    return;
  }
  lastPost = now;
  const elapsed = (now - phaseStart) / 1000;
  const eta = pct > 0.01 && pct < 1 && elapsed > 3 ? (elapsed * (1 - pct)) / pct : undefined;
  post({ type: 'progress', phase, pct, eta });
}

function stopIfCanceled(): void {
  if (canceled) throw new Error('Canceled.');
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

/** Whether anything in the picture is censored (faces, NSFW scenes, bodies). */
const censorsPicture = (ops: FilterOps) => censorFaces(ops) || ops.censorNsfw || ops.bodyBlur;

async function run({ file, ops, target }: Extract<ToWorker, { type: 'start' }>): Promise<void> {
  canceled = false;
  shownPhase = null;
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  let disk: FileSystemWritableFileStream | null = null;
  try {
    if (!ops.removeMusic && !censorsPicture(ops)) {
      throw new Error('Nothing to do: choose Remove music, faces to censor or NSFW scenes.');
    }
    progress('open', 0);
    const video = await input.getPrimaryVideoTrack();
    if (video && !video.codec) throw new Error('The video uses a codec this app does not know.');
    let encode: VideoEncode | null = null;
    let edl: Edl | null = null;
    if (censorsPicture(ops)) {
      if (!video) {
        if (!ops.removeMusic) throw new Error('This file has no video to censor.');
      } else {
        encode = await planVideoEncode(video); // before the analysis: no point analyzing what cannot be written
        edl = await analyzePicture(video, ops);
      }
    }
    const reencode = edl && (edl.censorIntervalsMs.length > 0 || edl.faceTracks.length > 0) ? { ...encode!, edl } : null;
    if (!reencode && !ops.removeMusic) {
      throw new Error('The analysis found nothing to cover and music removal is off, so there is nothing to change.');
    }

    const audio = await planAudio(input, ops.removeMusic);
    const format = pickFormat({
      videoCodec: reencode?.codec ?? video?.codec ?? null,
      copiedMatrix: video && !reencode ? await video.getTransformationMatrix() : null,
      opusVocals: audio?.mode === 'music' && audio.codec === 'opus',
      inMemory: target.kind === 'buffer',
    });
    if (audio?.mode === 'music' && audio.codec === 'opus') {
      notice(
        `This browser cannot encode AAC audio, so the audio is Opus${format instanceof WebMOutputFormat ? ' and the file is WebM (.webm)' : ''}.`,
      );
    }
    const buffer = target.kind === 'buffer' ? new BufferTarget() : null;
    if (target.kind === 'file') disk = await target.handle.createWritable();
    const output = new Output({
      format,
      target: buffer ?? new StreamTarget(commitOnlyOnSuccess(disk!), { chunked: true }),
    });

    await write(input, output, video, reencode, audio, ops);

    progress('finalize', 1);
    if (disk) await disk.close(); // the only place the file on disk is committed
    if (buffer) post({ type: 'done', buffer: buffer.buffer!, mimeType: format.mimeType }, [buffer.buffer!]);
    else post({ type: 'done', mimeType: format.mimeType });
  } catch (err) {
    await disk?.abort().catch(() => {}); // closes the writable and discards the partial output
    const message = canceled ? 'Canceled.' : err instanceof Error ? err.message : String(err);
    post({ type: 'error', message, canceled });
  } finally {
    cancelCurrent = null;
    input.dispose();
  }
}

/** Pass 1. Anything that cannot be analyzed throws (vision/analyze.ts); an empty EDL means nothing to cover. */
async function analyzePicture(video: InputVideoTrack, ops: FilterOps): Promise<Edl> {
  progress('analyze', 0);
  const { analyze } = await import('./vision/analyze');
  return analyze(video, ops, (pct) => progress('analyze', pct), stopIfCanceled);
}

interface VideoEncode {
  codec: VideoCodec;
  /** Upright output size, even on both sides (4:2:0). */
  width: number;
  height: number;
  hardwareAcceleration: 'prefer-hardware' | 'no-preference';
}

/** H.264 first, then VP9 and AV1; hardware first. */
const ENCODE_CODECS: VideoCodec[] = ['avc', 'vp9', 'av1'];

/** Size limits (warn above 1080p, refuse above 4K) and the encoder, checked before any work. */
async function planVideoEncode(video: InputVideoTrack): Promise<VideoEncode> {
  const w = await video.getDisplayWidth();
  const h = await video.getDisplayHeight();
  if (Math.max(w, h) > 4096 || Math.min(w, h) > 2160) {
    throw new Error(`The video is ${w}x${h}; censoring handles up to 4K (3840x2160).`);
  }
  if (Math.min(w, h) > 1080) notice(`The video is ${w}x${h}, above 1080p: censoring it is slow and needs much memory.`);
  if (!(await video.canDecode())) {
    throw new Error(`This browser cannot decode the video (${video.codec ?? 'unknown codec'}), so it cannot be censored.`);
  }
  const width = Math.max(2, w & ~1);
  const height = Math.max(2, h & ~1);
  for (const codec of ENCODE_CODECS) {
    for (const hardwareAcceleration of ['prefer-hardware', 'no-preference'] as const) {
      if (await canEncodeVideo(codec, { width, height, quality: QUALITY_HIGH, hardwareAcceleration })) {
        return { codec, width, height, hardwareAcceleration };
      }
    }
  }
  throw new Error(`This browser cannot encode ${width}x${height} video (H.264, VP9 or AV1), so it cannot write censored video.`);
}

interface AudioPlan {
  track: InputAudioTrack;
  /** Spleeter's vocals, the original packets as they are, or the original decoded and re-encoded. */
  mode: 'music' | 'copy' | 'transcode';
  /** The output codec. */
  codec: AudioCodec;
}

/** AAC where the browser can encode it, else Opus. */
async function encodableAudio(numberOfChannels: number, sampleRate: number): Promise<AudioCodec | null> {
  const options = { numberOfChannels, sampleRate, quality: QUALITY_HIGH };
  if (await canEncodeAudio('aac', options)) return 'aac';
  if (await canEncodeAudio('opus', options)) return 'opus';
  return null;
}

async function planAudio(input: Input, removeMusic: boolean): Promise<AudioPlan | null> {
  const track = await input.getPrimaryAudioTrack();
  if (!track) {
    if (removeMusic) throw new Error('This video has no audio, so there is no music to remove.');
    return null;
  }
  if (removeMusic) {
    if (!(await track.canDecode())) {
      throw new Error(`This browser cannot decode the video's audio (${track.codec ?? 'unknown codec'}).`);
    }
    const codec = await encodableAudio(2, RATE);
    if (!codec) throw new Error('This browser can encode neither AAC nor Opus audio.');
    return { track, mode: 'music', codec };
  }
  // Censoring only: the original audio, copied when MP4 can carry it.
  if (track.codec && new Mp4OutputFormat().getSupportedAudioCodecs().includes(track.codec)) {
    return { track, mode: 'copy', codec: track.codec };
  }
  const codec = (await track.canDecode()) ? await encodableAudio(track.numberOfChannels, track.sampleRate) : null;
  if (!codec) throw new Error(`The video's audio (${track.codec ?? 'unknown codec'}) can be neither copied nor re-encoded here.`);
  return { track, mode: 'transcode', codec };
}

/** The output pass. `reencode` set: frames are covered per its EDL and re-encoded; else video packets are copied. */
async function write(
  input: Input,
  output: Output,
  video: InputVideoTrack | null,
  reencode: (VideoEncode & { edl: Edl }) | null,
  audio: AudioPlan | null,
  ops: FilterOps,
): Promise<void> {
  const duration = await input.computeDuration();
  const phase: Phase = reencode ? 'render' : 'music';
  const report = (t: number) => progress(phase, duration > 0 ? Math.min(1, Math.max(0, t / duration)) : 0);

  let videoTrack: VideoTrack | null = null;
  if (video && reencode) {
    const source = new VideoSampleSource({
      codec: reencode.codec,
      quality: QUALITY_HIGH,
      hardwareAcceleration: reencode.hardwareAcceleration,
    });
    output.addVideoTrack(source); // frames are drawn upright: no rotation metadata
    videoTrack = encodedVideo(video, source, new Censor(reencode.width, reencode.height, ops), reencode.edl, report);
  } else if (video) {
    if (!output.format.getSupportedVideoCodecs().includes(video.codec!)) {
      throw new Error(`The video (${video.codec}) cannot be copied into ${output.format.fileExtension}.`);
    }
    const source = new EncodedVideoPacketSource(video.codec!);
    output.addVideoTrack(source, { transformationMatrix: await video.getTransformationMatrix() });
    videoTrack = copiedVideo(video, source);
  }

  // Every other audio track is dropped: with music removal it would still carry music.
  const audioSource = audio
    ? audio.mode === 'copy'
      ? new EncodedAudioPacketSource(audio.codec)
      : new AudioSampleSource({ codec: audio.codec, quality: QUALITY_HIGH })
    : null;
  if (audioSource) output.addAudioTrack(audioSource);
  cancelCurrent = () => output.cancel();

  let spleeter: { mask: Masker; release: () => Promise<void> } | null = null;
  try {
    if (audio?.mode === 'music') {
      progress('model', 0);
      spleeter = await loadSpleeter(new URL(`${import.meta.env.BASE_URL}models/spleeter-2stems.onnx`, self.location.href).href);
    }
    stopIfCanceled();
    await output.start();
    progress(phase, 0);
    const steps = !audio
      ? null
      : audio.mode === 'music'
        ? vocals(audio.track, audioSource as AudioSampleSource, spleeter!.mask)
        : audio.mode === 'copy'
          ? copiedAudio(audio.track, audioSource as EncodedAudioPacketSource)
          : transcodedAudio(audio.track, audioSource as AudioSampleSource);
    if (steps) {
      for await (const end of steps) {
        stopIfCanceled();
        await videoTrack?.until(end);
        report(end);
      }
    }
    stopIfCanceled();
    await videoTrack?.until(Infinity);
    stopIfCanceled();
    await output.finalize();
  } catch (err) {
    await output.cancel().catch(() => {});
    throw err;
  } finally {
    await videoTrack?.close().catch(() => {});
    await spleeter?.release().catch(() => {});
  }
}

/** Video written up to a time, following the audio. */
interface VideoTrack {
  /** Writes every frame presented before `time` (seconds). */
  until(time: number): Promise<void>;
  /** Stops reading (on error or cancel), closing what is held. */
  close(): Promise<void>;
}

/** Packets copied in decode order until one is presented at or after `time`. */
function copiedVideo(video: InputVideoTrack, source: EncodedVideoPacketSource): VideoTrack {
  const packets = new EncodedPacketSink(video).packets();
  let config: VideoDecoderConfig | null | undefined;
  let first = true;
  let pending: IteratorResult<EncodedPacket> | null = null;
  return {
    async until(time) {
      if (config === undefined) config = await video.getDecoderConfig();
      pending ??= await packets.next();
      while (!pending.done && pending.value.timestamp < time) {
        await source.add(pending.value, first ? { decoderConfig: config ?? undefined } : undefined);
        first = false;
        pending = await packets.next();
      }
    },
    async close() {
      await packets.return();
    },
  };
}

/** Frames decoded in presentation order, covered, and encoded with their own timestamps and durations. */
function encodedVideo(
  video: InputVideoTrack,
  source: VideoSampleSource,
  censor: Censor,
  edl: Edl,
  report: (t: number) => void,
): VideoTrack {
  const samples = new VideoSampleSink(video).samples();
  let pending: IteratorResult<VideoSample> | null = null;
  let frames = 0;
  return {
    async until(time) {
      pending ??= await samples.next();
      while (!pending.done && pending.value.timestamp < time) {
        const sample = pending.value;
        pending = null;
        const { timestamp, duration } = sample;
        try {
          stopIfCanceled();
          const frame = new VideoSample(censor.draw(sample, edl), { timestamp, duration });
          try {
            await source.add(frame);
          } finally {
            frame.close();
          }
        } finally {
          sample.close();
        }
        frames++;
        report(timestamp);
        pending = await samples.next();
      }
      if (pending.done && time === Infinity && frames === 0) {
        throw new Error('No frame of the video could be decoded, so it cannot be censored.');
      }
    },
    async close() {
      if (pending && !pending.done) pending.value.close();
      await samples.return();
    },
  };
}

/** Spleeter's vocals, window by window; yields where each chunk ends (seconds). */
async function* vocals(track: InputAudioTrack, source: AudioSampleSource, mask: Masker): AsyncGenerator<number> {
  for await (const chunk of separate(new AudioSampleSink(track).samples(), mask)) {
    stopIfCanceled();
    const data = new Float32Array(2 * chunk.left.length);
    data.set(chunk.left);
    data.set(chunk.right, chunk.left.length);
    const sample = new AudioSample({ data, format: 'f32-planar', numberOfChannels: 2, sampleRate: RATE, timestamp: chunk.timestamp });
    try {
      await source.add(sample);
    } finally {
      sample.close();
    }
    yield chunk.timestamp + chunk.left.length / RATE;
  }
}

/** The original audio packets as they are. */
async function* copiedAudio(track: InputAudioTrack, source: EncodedAudioPacketSource): AsyncGenerator<number> {
  const decoderConfig = await track.getDecoderConfig();
  if (!decoderConfig) throw new Error(`The video's audio (${track.codec}) cannot be read.`);
  let first = true;
  for await (const packet of new EncodedPacketSink(track).packets()) {
    await source.add(packet, first ? { decoderConfig } : undefined);
    first = false;
    yield packet.timestamp + packet.duration;
  }
}

/** The original audio decoded and re-encoded (a codec MP4 cannot carry). */
async function* transcodedAudio(track: InputAudioTrack, source: AudioSampleSource): AsyncGenerator<number> {
  for await (const sample of new AudioSampleSink(track).samples()) {
    const end = sample.timestamp + sample.duration;
    try {
      await source.add(sample);
    } finally {
      sample.close();
    }
    yield end;
  }
}

/**
 * Mediabunny closes its target even on cancel or error, and closing a FileSystemWritableFileStream
 * commits it. Forward the writes but leave close/abort to `run`, so a failed export never lands on disk.
 */
function commitOnlyOnSuccess(disk: FileSystemWritableFileStream): WritableStream<StreamTargetChunk> {
  return new WritableStream({ write: (chunk) => disk.write(chunk) });
}
