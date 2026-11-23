// Ported from Musicless tests/unit/dsp.test.mjs (MIT, Copyright (c) 2026 الكتب المُيسّرة).
import { describe, expect, it } from 'vitest';
import { analyze, resample, synthesize } from '../src/audio/dsp';
import { BINS, FRAMES, N_FFT, RATE, WINDOW_SAMPLES } from '../src/audio/grid';

const sine = (length: number, freq: number, rate: number, phase = 0) =>
  new Float32Array(length).map((_, i) => Math.sin((2 * Math.PI * freq * i) / rate + phase));

describe('audio dsp', () => {
  it('STFT then iSTFT with a mask of ones gives the input back away from the edges', () => {
    const left = sine(WINDOW_SAMPLES, 440, RATE);
    const right = sine(WINDOW_SAMPLES, 1250, RATE, 1);
    const { spec, magnitude } = analyze(left, right);
    expect(magnitude.length).toBe(2 * FRAMES * BINS);
    const out = synthesize(spec, new Float32Array(2 * FRAMES * BINS).fill(1));
    let worst = 0;
    for (let i = 8192; i < WINDOW_SAMPLES - 8192; i++) {
      worst = Math.max(worst, Math.abs(out.left[i] - left[i]), Math.abs(out.right[i] - right[i]));
    }
    expect(worst).toBeLessThan(1e-4);
  });

  it('a mask of zeros gives silence', () => {
    const x = sine(WINDOW_SAMPLES, 300, RATE);
    const out = synthesize(analyze(x, x).spec, new Float32Array(2 * FRAMES * BINS));
    expect(out.left.every((v) => v === 0) && out.right.every((v) => v === 0)).toBe(true);
  });

  it('the magnitude the model reads peaks at the sinusoid bin', () => {
    const x = sine(WINDOW_SAMPLES, 100 * (RATE / N_FFT), RATE); // exactly bin 100
    const { magnitude } = analyze(x, x);
    const frame = 200 * BINS;
    let best = 0;
    for (let k = 1; k < BINS; k++) if (magnitude[frame + k] > magnitude[frame + best]) best = k;
    expect(best).toBe(100);
  });

  it('the model reads every window at the same level', () => {
    const scaled = (gain: number) => sine(WINDOW_SAMPLES, 440, RATE).map((v) => v * gain);
    const quiet = analyze(scaled(0.05), scaled(0.05)).magnitude;
    const loud = analyze(scaled(0.8), scaled(0.8)).magnitude;
    const at = 200 * BINS + 41; // the 440 Hz bin
    expect(Math.abs(quiet[at] / loud[at] - 1)).toBeLessThan(1e-4);
  });

  it('resampling 48 kHz to 44.1 kHz keeps a sine and its frequency', () => {
    const input = sine(48000, 1000, 48000);
    const [out] = resample([input], 48000, 0, 0.25, 4410);
    let worst = 0;
    for (let n = 0; n < out.length; n++) {
      worst = Math.max(worst, Math.abs(out[n] - Math.sin(2 * Math.PI * 1000 * (0.25 + n / RATE))));
    }
    expect(worst).toBeLessThan(2e-3);
    // Count upward zero crossings over 0.1 s: 100 periods of 1 kHz.
    let crossings = 0;
    for (let n = 1; n < out.length; n++) if (out[n - 1] < 0 && out[n] >= 0) crossings++;
    expect(Math.abs(crossings - 100)).toBeLessThanOrEqual(1);
  });

  it('resampling 22.05 kHz up keeps the frequency too', () => {
    const input = sine(22050, 440, 22050);
    const [out] = resample([input], 22050, 0, 0.1, RATE / 2);
    let worst = 0;
    for (let n = 0; n < out.length; n++) {
      worst = Math.max(worst, Math.abs(out[n] - Math.sin(2 * Math.PI * 440 * (0.1 + n / RATE))));
    }
    expect(worst).toBeLessThan(2e-3);
  });

  it('reading outside the decoded audio gives silence', () => {
    const [out] = resample([sine(4800, 500, 48000)], 48000, 10, 0, 1000); // input starts at 10 s
    expect(out.every((v) => v === 0)).toBe(true);
  });
});
