// Decoded frame -> pixels for the vision models, and the analysis sampling rule. Ported from
// apps/windows/.../media/Decode.kt (Slots, analysisSize), which follows Android's FrameSampler.
//
// Frames are drawn UPRIGHT: mediabunny's VideoSample.draw applies the track's rotation (and flip)
// metadata, so every pixel buffer here, and every normalized rect the models return, is in display space,
// the space core's NRect and the EDL use.

import type { VideoSample } from 'mediabunny';

/** Longer side of the face analysis frame (Windows' and Android's maxDim). */
export const ANALYSIS_MAX = 640;

/** Packed RGBA, row-major, as canvas `getImageData` returns it. */
export interface Rgba {
  data: Uint8ClampedArray;
  width: number;
  height: number;
}

/** Android's analysis size: longer side down to `maxDim` (never up), each side even and at least 2. */
export function analysisSize(w: number, h: number, maxDim = ANALYSIS_MAX): [number, number] {
  const scale = Math.max(w, h) > maxDim ? maxDim / Math.max(w, h) : 1;
  return [Math.max(2, Math.round(w * scale)) & ~1, Math.max(2, Math.round(h * scale)) & ~1];
}

/**
 * Android's `FrameSampler` slot rule: slots `1000 / fps` ms apart (kept in µs), anchored to the first
 * frame offered. A frame is taken when its pts is at or past the next slot; the slot then advances one
 * interval, or to `pts + interval` when that would leave it at or behind the frame (a gap).
 */
export class Slots {
  private readonly intervalUs: number;
  private nextUs: number | null = null;

  constructor(fps: number) {
    this.intervalUs = Math.max(1, Math.trunc(1_000_000 / fps));
  }

  take(ptsUs: number): boolean {
    if (this.nextUs === null) this.nextUs = ptsUs;
    if (ptsUs < this.nextUs) return false;
    this.nextUs += this.intervalUs;
    if (this.nextUs <= ptsUs) this.nextUs = ptsUs + this.intervalUs;
    return true;
  }
}

/** One reusable OffscreenCanvas that turns decoded samples into upright RGBA pixels. Worker/browser only. */
export class FramePainter {
  private readonly canvas = new OffscreenCanvas(2, 2);
  private readonly ctx: OffscreenCanvasRenderingContext2D;

  constructor() {
    const ctx = this.canvas.getContext('2d', { willReadFrequently: true, alpha: false });
    if (!ctx) throw new Error('This browser has no 2D OffscreenCanvas, which the analysis needs.');
    this.ctx = ctx;
  }

  /**
   * `sample` upright, scaled to `width` x `height`. `smooth` false samples nearest neighbour (source pixels
   * as they are, the NSFW gate's input); true averages (the face analysis downscale, like Windows' SWS_AREA).
   */
  draw(sample: VideoSample, width: number, height: number, smooth: boolean): Rgba {
    if (this.canvas.width !== width || this.canvas.height !== height) {
      this.canvas.width = width;
      this.canvas.height = height;
    }
    const ctx = this.ctx;
    ctx.imageSmoothingEnabled = smooth;
    if (smooth) ctx.imageSmoothingQuality = 'high';
    sample.draw(ctx, 0, 0, width, height);
    return { data: ctx.getImageData(0, 0, width, height).data, width, height };
  }
}
