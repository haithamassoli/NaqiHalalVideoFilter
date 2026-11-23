// The pipeline worker. Milestone 1 is the media spike: open the file with Mediabunny and remux it to
// MP4 (packets copied where the codec fits MP4), streamed to disk or into a buffer.
import {
  ALL_FORMATS,
  BlobSource,
  BufferTarget,
  Conversion,
  Input,
  Mp4OutputFormat,
  Output,
  StreamTarget,
  type StreamTargetChunk,
} from 'mediabunny';
import type { FromWorker, Phase, ToWorker } from './protocol';

let conversion: Conversion | null = null;
let canceled = false;

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
    void conversion?.cancel();
  } else if (msg.type === 'start') {
    void run(msg);
  }
};

async function run({ file, target }: Extract<ToWorker, { type: 'start' }>): Promise<void> {
  canceled = false;
  const input = new Input({ source: new BlobSource(file), formats: ALL_FORMATS });
  let disk: FileSystemWritableFileStream | null = null;
  try {
    progress('open', 0);
    const buffer = target.kind === 'buffer' ? new BufferTarget() : null;
    if (target.kind === 'file') disk = await target.handle.createWritable();
    const output = new Output({
      format: new Mp4OutputFormat({ fastStart: buffer ? 'in-memory' : false }),
      target: buffer ?? new StreamTarget(commitOnlyOnSuccess(disk!), { chunked: true }),
    });

    conversion = await Conversion.init({ input, output, showWarnings: false });
    const dropped = conversion.discardedTracks.filter((d) => d.track.type !== 'subtitle');
    if (!conversion.isValid || dropped.length > 0) {
      const why = dropped.map((d) => `${d.track.type} track: ${d.reason}`).join('; ');
      throw new Error(`This file cannot be converted${why ? ` (${why})` : ''}.`);
    }
    conversion.onProgress = (p) => progress('remux', p);
    if (canceled) await conversion.cancel();
    await conversion.execute();

    progress('finalize', 1);
    if (disk) await disk.close(); // the only place the file on disk is committed
    if (buffer) post({ type: 'done', buffer: buffer.buffer! }, [buffer.buffer!]);
    else post({ type: 'done' });
  } catch (err) {
    await disk?.abort().catch(() => {}); // discard the partial output
    const message = canceled ? 'Canceled.' : err instanceof Error ? err.message : String(err);
    post({ type: 'error', message, canceled });
  } finally {
    conversion = null;
    input.dispose();
  }
}

/**
 * Mediabunny closes its target even on cancel or error, and closing a FileSystemWritableFileStream
 * commits it. Forward the writes but leave close/abort to `run`, so a failed export never lands on disk.
 */
function commitOnlyOnSuccess(disk: FileSystemWritableFileStream): WritableStream<StreamTargetChunk> {
  return new WritableStream({ write: (chunk) => disk.write(chunk) });
}
