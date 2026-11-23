// Offline whole-file music removal with Spleeter. The window pipeline (decode, resample, silence
// past the end, analyze, mask, synthesize, fade) and the model session options are ported from
// Musicless extension/tab-worker.js and model.js (https://github.com/ieasybooks/musicless, commit
// 957acc1); the streaming over a whole file is Naqi's.
//
// MIT License
//
// Copyright (c) 2026 الكتب المُيسّرة
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

// The audio is walked once, in order: windows sit on the fixed grid from the first decoded sample,
// each is separated as soon as the input covers it, and its vocals go out before the next window is
// read. Only about one window of input PCM (plus the resampler's reach) and one window of output are
// held at a time, never the whole file.

import type { AudioSample } from 'mediabunny';
import { analyze, resample, RESAMPLE_TAPS, synthesize } from './dsp';
import { applyFades, BINS, FRAMES, OVERLAP_SAMPLES, RATE, STRIDE_SAMPLES, WINDOW_SAMPLES } from './grid';

/** The network: model input |X| (2, 1, FRAMES, BINS) to the vocal mask of the same shape. */
export type Masker = (magnitude: Float32Array) => Promise<Float32Array>;

/** Separated vocals at 44.1 kHz stereo, starting at `timestamp` seconds (the source's own timeline). */
export interface VocalsChunk {
  timestamp: number;
  left: Float32Array;
  right: Float32Array;
}

/** Loads Spleeter on ONNX Runtime Web (WebAssembly, one thread), with Musicless' session options. */
export async function loadSpleeter(url: string): Promise<{ mask: Masker; release: () => Promise<void> }> {
  const { ort } = await import('../ort'); // only when music removal is on
  const response = await fetch(url);
  // A dev server answers a missing file with the HTML app page.
  if (!response.ok || response.headers.get('content-type')?.includes('text/html')) {
    throw new Error('The music model (spleeter-2stems.onnx) is missing: run scripts/build-spleeter.sh.');
  }
  const session = await ort.InferenceSession.create(new Uint8Array(await response.arrayBuffer()), {
    executionProviders: ['wasm'],
    graphOptimizationLevel: 'all',
    freeDimensionOverrides: { num_splits: 1 },
    // Still one thread; plans buffers differently: less WebAssembly memory, the same masks.
    executionMode: 'parallel',
  });
  const shape = [2, 1, FRAMES, BINS];
  return {
    async mask(magnitude) {
      const output = await session.run({ x: new ort.Tensor('float32', magnitude, shape) });
      const mask = output.mask;
      if (!mask || mask.size !== magnitude.length) throw new Error('The music model returned no usable mask.');
      return new Float32Array(mask.data as Float32Array); // a copy out of the runtime's memory
    },
    release: () => session.release(),
  };
}

/**
 * Separates the vocals of a decoded audio stream (mediabunny AudioSampleSink.samples(), in order).
 * Yields contiguous chunks that together cover exactly the decoded audio's span, from its first
 * sample's timestamp. Each input sample is closed once copied.
 */
export async function* separate(input: AsyncIterable<AudioSample>, mask: Masker): AsyncGenerator<VocalsChunk> {
  const source = input[Symbol.asyncIterator]();
  const pcm = new InputPcm();
  let ended = false;
  let tail: Float32Array[] | null = null; // the previous window's faded-out last OVERLAP_SAMPLES
  try {
    for (let k = 0; ; k++) {
      const first = k * STRIDE_SAMPLES; // the window's first sample, counted from the grid origin
      // Read until the input covers the window and the resampler's reach past it, or ends.
      while (!ended && (pcm.origin === null || pcm.end() < pcm.origin + (first + WINDOW_SAMPLES) / RATE + pcm.reach())) {
        const next = await source.next();
        if (next.done) ended = true;
        else {
          try {
            pcm.add(next.value);
          } finally {
            next.value.close();
          }
        }
      }
      if (pcm.origin === null) return; // no audio at all
      const total = ended ? Math.round((pcm.end() - pcm.origin) * RATE) : Infinity;
      if (first >= total) return;

      const start = pcm.origin + first / RATE;
      const [left, right] = resample(pcm.channels, pcm.rate, pcm.start, start, WINDOW_SAMPLES, pcm.length);
      // Past the end the window is silence (the resampler's reach would otherwise smear into it).
      const cut = total - first;
      if (cut < WINDOW_SAMPLES) {
        left.fill(0, cut);
        right.fill(0, cut);
      }
      const { spec, magnitude } = analyze(left, right);
      const vocals = synthesize(spec, await mask(magnitude));
      const out = [vocals.left, vocals.right];
      applyFades(out, k);
      if (tail) for (let c = 0; c < 2; c++) for (let i = 0; i < OVERLAP_SAMPLES; i++) out[c][i] += tail[c][i];
      tail = out.map((ch) => ch.slice(STRIDE_SAMPLES));
      const count = Math.min(STRIDE_SAMPLES, cut);
      yield { timestamp: start, left: out[0].subarray(0, count), right: out[1].subarray(0, count) };
      pcm.dropBefore(pcm.origin + (first + STRIDE_SAMPLES) / RATE);
    }
  } finally {
    await source.return?.();
  }
}

/**
 * Decoded input at its own rate, downmixed to stereo, placed by timestamp: gaps read as silence.
 * Holds only what the next window still needs.
 */
class InputPcm {
  /** Timestamp of the first decoded sample: the grid's origin. */
  origin: number | null = null;
  rate = 0;
  /** Timestamp of channels[*][0]. */
  start = 0;
  length = 0;
  channels = [new Float32Array(0), new Float32Array(0)];

  end(): number {
    return this.start + this.length / this.rate;
  }

  /** Seconds of input the resampler reads on either side of an instant. */
  reach(): number {
    return (RESAMPLE_TAPS + 1) / this.rate;
  }

  add(sample: AudioSample): void {
    const frames = sample.numberOfFrames;
    if (frames === 0) return;
    if (this.origin === null) {
      this.origin = this.start = sample.timestamp;
      this.rate = sample.sampleRate;
    } else if (sample.sampleRate !== this.rate) {
      throw new Error(`The audio changes sample rate midway (${this.rate} to ${sample.sampleRate} Hz); this is not supported.`);
    }
    let at = Math.round((sample.timestamp - this.start) * this.rate);
    if (Math.abs(at - this.length) <= 1) at = this.length; // rounding jitter: contiguous
    const skip = Math.max(0, -at); // before what is still held (overlapping, already used)
    if (skip >= frames) return;
    this.reserve(at + frames);
    if (at > this.length) for (const ch of this.channels) ch.fill(0, this.length, at); // a gap is silence
    const [l, r] = stereo(sample);
    this.channels[0].set(l.subarray(skip), at + skip);
    this.channels[1].set(r.subarray(skip), at + skip);
    this.length = Math.max(this.length, at + frames);
  }

  /** Forgets input before `time`, keeping the resampler's reach. */
  dropBefore(time: number): void {
    const drop = Math.min(this.length, Math.max(0, Math.floor((time - this.start) * this.rate) - RESAMPLE_TAPS - 1));
    if (drop === 0) return;
    for (const ch of this.channels) ch.copyWithin(0, drop, this.length);
    this.length -= drop;
    this.start += drop / this.rate;
  }

  private reserve(size: number): void {
    if (size <= this.channels[0].length) return;
    const capacity = Math.max(size, Math.ceil(this.channels[0].length * 1.5));
    this.channels = this.channels.map((ch) => {
      const grown = new Float32Array(capacity);
      grown.set(ch.subarray(0, this.length));
      return grown;
    });
  }
}

/**
 * Extra channels folded into each side at -3 dB, by channel count, in the decoded (WAV/WebCodecs) order:
 * [both sides (centre, back centre), left only, right only]. The LFE is dropped. Quad is the Web Audio
 * API's average instead; any other count keeps its first two channels.
 */
const FOLDS: Record<number, [number[], number[], number[]]> = {
  3: [[2], [], []], // L R C
  5: [[2], [3], [4]], // 5.0: L R C SL SR
  6: [[2], [4], [5]], // 5.1: L R C LFE SL SR
  7: [[2, 4], [5], [6]], // 6.1: L R C LFE BC SL SR
  8: [[2], [4, 6], [5, 7]], // 7.1: L R C LFE SL SR BL BR
};

/** Stereo from planar channels: mono duplicated, quad averaged, centre and surrounds folded in (FOLDS). */
export function toStereo(planes: readonly Float32Array[]): [Float32Array, Float32Array] {
  if (planes.length === 1) return [planes[0], planes[0]];
  const left = planes[0].slice();
  const right = planes[1].slice();
  if (planes.length === 4) {
    for (let i = 0; i < left.length; i++) {
      left[i] = 0.5 * (left[i] + planes[2][i]);
      right[i] = 0.5 * (right[i] + planes[3][i]);
    }
    return [left, right];
  }
  const [both, onlyLeft, onlyRight] = FOLDS[planes.length] ?? [[], [], []];
  const add = (into: Float32Array, indices: number[]) => {
    for (const c of indices) for (let i = 0; i < into.length; i++) into[i] += Math.SQRT1_2 * planes[c][i];
  };
  add(left, [...both, ...onlyLeft]);
  add(right, [...both, ...onlyRight]);
  return [left, right];
}

function stereo(sample: AudioSample): [Float32Array, Float32Array] {
  const n = sample.numberOfChannels;
  const planes = Array.from({ length: n === 4 || FOLDS[n] ? n : Math.min(n, 2) }, (_, planeIndex) => {
    const plane = new Float32Array(sample.numberOfFrames);
    sample.copyTo(plane, { planeIndex, format: 'f32-planar' });
    return plane;
  });
  return toStereo(planes);
}
