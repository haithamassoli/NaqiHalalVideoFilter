// Ported to TypeScript from Musicless extension/grid.js (https://github.com/ieasybooks/musicless,
// commit 957acc1), constants and math unchanged.
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

// The numbers the Spleeter model and its signal processing are built around, and the fixed grid of
// separation windows. Spleeter reads 512 STFT frames (about 11.9 s at 44.1 kHz) at a time and is not
// shift-invariant, so windows sit on a fixed grid from the start of the audio.
//
// Neighbouring windows overlap by two hops. Each chunk fades in over its first OVERLAP_SAMPLES and out
// over its last, so consecutive chunks crossfade instead of meeting in a click (independently
// separated windows disagree at the seam).

export const RATE = 44100;
export const N_FFT = 4096;
export const HOP = 1024;
export const FRAMES = 512;
export const BINS = 1024; // the model sees 0 to ~11 kHz; higher bins are dropped
/** The level every window is shown to the model at, with the gain kept within ±30 dB. */
export const INPUT_RMS = 10 ** (-24 / 20);
export const MAX_INPUT_GAIN = 10 ** (30 / 20);
export const WINDOW_SAMPLES = FRAMES * HOP; // 524288 samples the model reads per window
export const OVERLAP_SAMPLES = 2 * HOP; // 2048 samples (46 ms) of crossfade
export const STRIDE_SAMPLES = WINDOW_SAMPLES - OVERLAP_SAMPLES;
export const CHUNK_SECONDS = STRIDE_SAMPLES / RATE; // ~11.84 s between chunk starts
export const WINDOW_SECONDS = WINDOW_SAMPLES / RATE;

/** Index of the chunk whose own span [start, next start) contains time t. */
export const chunkIndex = (time: number): number => Math.max(0, Math.floor(time / CHUNK_SECONDS));
export const chunkStart = (k: number): number => (k * STRIDE_SAMPLES) / RATE;

/** Linear crossfade weights: a fade-out and the next chunk's fade-in sum to 1. */
export function applyFades(channels: Float32Array[], k: number): void {
  for (const samples of channels) {
    const length = samples.length;
    for (let i = 0; i < OVERLAP_SAMPLES; i++) {
      const rise = (i + 0.5) / OVERLAP_SAMPLES;
      if (k > 0) samples[i] *= rise;
      samples[length - OVERLAP_SAMPLES + i] *= 1 - rise;
    }
  }
}
