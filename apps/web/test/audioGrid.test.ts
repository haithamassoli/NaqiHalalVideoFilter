// Ported from Musicless tests/unit/grid.test.mjs (MIT, Copyright (c) 2026 الكتب المُيسّرة).
import { describe, expect, it } from 'vitest';
import {
  applyFades,
  BINS,
  CHUNK_SECONDS,
  chunkIndex,
  chunkStart,
  FRAMES,
  HOP,
  INPUT_RMS,
  MAX_INPUT_GAIN,
  N_FFT,
  OVERLAP_SAMPLES,
  RATE,
  STRIDE_SAMPLES,
  WINDOW_SAMPLES,
} from '../src/audio/grid';

describe('audio grid', () => {
  it('keeps Musicless constants', () => {
    expect([RATE, N_FFT, HOP, FRAMES, BINS]).toEqual([44100, 4096, 1024, 512, 1024]);
    expect(WINDOW_SAMPLES).toBe(524288);
    expect(OVERLAP_SAMPLES).toBe(2048);
    expect(STRIDE_SAMPLES).toBe(522240);
    expect(STRIDE_SAMPLES % HOP).toBe(0); // every window starts on the STFT hop grid
    expect(20 * Math.log10(INPUT_RMS)).toBeCloseTo(-24, 12);
    expect(20 * Math.log10(MAX_INPUT_GAIN)).toBeCloseTo(30, 12);
  });

  it('puts windows on a fixed grid from time 0', () => {
    expect(chunkStart(0)).toBe(0);
    expect(chunkStart(3)).toBeCloseTo((3 * STRIDE_SAMPLES) / RATE, 12);
    for (const k of [0, 1, 7, 120]) {
      expect(chunkIndex(chunkStart(k))).toBe(k);
      expect(chunkIndex(chunkStart(k) + CHUNK_SECONDS / 2)).toBe(k);
      expect(chunkIndex(chunkStart(k + 1) - 1e-6)).toBe(k);
    }
    expect(chunkIndex(-1)).toBe(0);
  });

  it('crossfade weights of neighbouring chunks sum to 1', () => {
    const a = new Float32Array(WINDOW_SAMPLES).fill(1);
    const b = new Float32Array(WINDOW_SAMPLES).fill(1);
    applyFades([a], 4);
    applyFades([b], 5);
    for (let i = 0; i < OVERLAP_SAMPLES; i++) {
      expect(Math.abs(a[STRIDE_SAMPLES + i] + b[i] - 1)).toBeLessThan(1e-6);
    }
    expect(b[0]).toBeCloseTo(0.5 / OVERLAP_SAMPLES, 9); // weights (i + 0.5) / 2048
    expect(a[OVERLAP_SAMPLES + 10]).toBe(1); // the middle is untouched
  });

  it('does not fade the first chunk in', () => {
    const first = new Float32Array(WINDOW_SAMPLES).fill(1);
    applyFades([first], 0);
    expect(first[0]).toBe(1);
    expect(first[WINDOW_SAMPLES - 1]).toBeLessThan(0.001);
  });
});
