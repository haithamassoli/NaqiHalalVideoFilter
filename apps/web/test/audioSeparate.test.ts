import { AudioSample } from 'mediabunny';
import { describe, expect, it } from 'vitest';
import { RATE, STRIDE_SAMPLES } from '../src/audio/grid';
import { separate, toStereo, type VocalsChunk } from '../src/audio/separate';

/** Decoder-like stream: 1024-frame planar f32 samples of a sine at `rate`, starting at `t0`. */
async function* tone(rate: number, channels: number, seconds: number, t0: number, freq: number) {
  const total = Math.round(seconds * rate);
  for (let at = 0; at < total; at += 1024) {
    const frames = Math.min(1024, total - at);
    const data = new Float32Array(frames * channels);
    for (let c = 0; c < channels; c++) {
      for (let i = 0; i < frames; i++) data[c * frames + i] = 0.5 * Math.sin((2 * Math.PI * freq * (at + i)) / rate + c);
    }
    yield new AudioSample({ data, format: 'f32-planar', numberOfChannels: channels, sampleRate: rate, timestamp: t0 + at / rate });
  }
}

const ones = async (magnitude: Float32Array) => new Float32Array(magnitude.length).fill(1);

async function collect(chunks: AsyncIterable<VocalsChunk>) {
  const all: VocalsChunk[] = [];
  for await (const chunk of chunks) all.push({ ...chunk, left: chunk.left.slice(), right: chunk.right.slice() });
  return all;
}

describe('audio separate', () => {
  it('streams contiguous windows that cover exactly the input, and an all-pass mask gives it back', async () => {
    const seconds = 25; // three windows: two seams
    const chunks = await collect(separate(tone(48000, 2, seconds, 1.5, 440), ones));
    expect(chunks.length).toBe(Math.ceil((seconds * RATE) / STRIDE_SAMPLES));
    let at = 0;
    for (const chunk of chunks) {
      expect(chunk.timestamp).toBeCloseTo(1.5 + at / RATE, 9);
      at += chunk.left.length;
    }
    expect(at).toBe(seconds * RATE); // trimmed to the exact length

    const left = new Float32Array(at);
    const right = new Float32Array(at);
    let o = 0;
    for (const chunk of chunks) {
      left.set(chunk.left, o);
      right.set(chunk.right, o);
      o += chunk.left.length;
    }
    let worst = 0;
    for (let n = 64; n < at - 64; n++) {
      const t = n / RATE;
      worst = Math.max(
        worst,
        Math.abs(left[n] - 0.5 * Math.sin(2 * Math.PI * 440 * t)),
        Math.abs(right[n] - 0.5 * Math.sin(2 * Math.PI * 440 * t + 1)),
      );
    }
    expect(worst).toBeLessThan(5e-3);
  });

  it('duplicates mono and yields nothing for no audio', async () => {
    const chunks = await collect(separate(tone(44100, 1, 2, 0, 300), ones));
    expect(chunks.length).toBe(1);
    expect(chunks[0].left.length).toBe(2 * RATE);
    let worst = 0;
    chunks[0].left.forEach((v, i) => (worst = Math.max(worst, Math.abs(v - chunks[0].right[i]))));
    expect(worst).toBeLessThan(1e-6);
    expect(await collect(separate(tone(44100, 2, 0, 0, 300), ones))).toEqual([]);
  });
});

describe('toStereo', () => {
  const planes = (n: number, values: Record<number, number>) =>
    Array.from({ length: n }, (_, c) => new Float32Array(4).fill(values[c] ?? 0));
  const k = Math.SQRT1_2;

  it('keeps the centre (dialogue) of 3.0, 5.0, 5.1, 6.1 and 7.1 in both sides', () => {
    for (const n of [3, 5, 6, 7, 8]) {
      const [l, r] = toStereo(planes(n, { 2: 1 }));
      expect(l[0], `${n} channels`).toBeCloseTo(k, 6);
      expect(r[0], `${n} channels`).toBeCloseTo(k, 6);
    }
  });
  it('folds 7.1 side and back surrounds into their own side and drops the LFE', () => {
    const [l, r] = toStereo(planes(8, { 0: 0.1, 1: 0.2, 3: 1, 4: 1, 6: 1, 5: 0.5, 7: 0.5 }));
    expect(l[0]).toBeCloseTo(0.1 + 2 * k, 6);
    expect(r[0]).toBeCloseTo(0.2 + k, 6);
  });
  it('duplicates mono, averages quad and keeps the first two of an unknown layout', () => {
    expect(toStereo(planes(1, { 0: 0.3 })).map((p) => p[0])).toEqual([0.3, 0.3].map(Math.fround));
    expect(toStereo(planes(4, { 0: 1, 2: 0.5, 3: 1 })).map((p) => p[0])).toEqual([0.75, 0.5]);
    expect(toStereo(planes(2, { 0: 0.25, 1: 0.5 })).map((p) => p[0])).toEqual([0.25, 0.5]);
  });
});
