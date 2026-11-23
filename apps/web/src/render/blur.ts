// The pass-2 censor effect: one decoded frame, drawn upright onto an OffscreenCanvas, covered where the
// EDL says. Same rules and constants as Windows' vision/Censor.kt (Android's CensorEffect): an opaque solid
// fill wins over everything; otherwise a Gaussian blur, sigma 40 px at amount 100 on a 1080-px short side,
// run at the smallest downscale in {1, 2, 4, 8} that keeps the low-res sigma <= 4; BT.709 grayscale on top
// when asked; the one style that would change nothing (blur 0, no gray, no fill) becomes a light blur; and
// a feather that ramps OUTWARD from each rect, so softening never uncovers a pixel the hard rect covered.
//
// The blur itself is the canvas `filter: blur()` (its radius is the Gaussian's standard deviation), on a
// low-res copy whose edges are replicated outward first, so the frame edge blurs like GL_CLAMP_TO_EDGE
// instead of fading toward transparent (which would let the original show through at the border).
//
// Frames are drawn upright (mediabunny's VideoSample.draw applies rotation and flip), the space the EDL's
// rects are in, so the output is encoded upright and needs no rotation metadata.

import type { VideoSample } from 'mediabunny';
import type { NRect } from '../core/contracts';
import { fullFrameAt, masksAt, regionsAt, type Edl } from '../core/edl';
import { BLUR, type FilterOps } from '../core/filterOps';

/** Android's FilterWorker.MIN_EFFECTIVE_BLUR: 10 px of sigma at 1080p. */
export const MIN_EFFECTIVE_BLUR = 25;
/** CensorEffect's feather: 15% of the region, never under 0.002 of the frame. */
const FEATHER = 0.15;
const MIN_FEATHER = 0.002;

export interface CensorStyle {
  /** CSS colour of the opaque fill, or null to blur. */
  solid: string | null;
  /** Blur amount 0..100 after the no-op rule. */
  amount: number;
  grayscale: boolean;
}

/** FilterOps' style fields -> what is drawn. */
export function censorStyle(ops: Pick<FilterOps, 'blurAmount' | 'grayscale' | 'solidColor'>): CensorStyle {
  const solid = ops.solidColor !== BLUR;
  const amount = ops.blurAmount > 0 || ops.grayscale || solid ? Math.min(100, Math.max(0, ops.blurAmount)) : MIN_EFFECTIVE_BLUR;
  const c = ops.solidColor;
  return {
    solid: solid ? `rgb(${(c >> 16) & 255} ${(c >> 8) & 255} ${c & 255})` : null,
    amount,
    grayscale: ops.grayscale && !solid,
  };
}

/** CensorEffect.configure: the downscale `d` and the sigma in low-res pixels. */
export function blurPlan(amount: number, width: number, height: number): { scale: number; sigma: number } {
  const sigmaPx = Math.max(0.1, (amount / 100) * 40 * (Math.min(width, height) / 1080));
  const scale = [1, 2, 4, 8].find((d) => sigmaPx / d <= 4) ?? 8;
  return { scale, sigma: sigmaPx / scale };
}

/**
 * Every rect to cover at `tMs` (upright, normalized), or 'full' for the whole frame. Body silhouettes are
 * covered by their whole box until the silhouette renderer lands: the box holds the silhouette, so this
 * covers more, never less.
 */
export function coverAt(edl: Edl, tMs: number): NRect[] | 'full' {
  if (fullFrameAt(edl, tMs)) return 'full';
  return [...regionsAt(edl, tMs), ...masksAt(edl, tMs).map((m) => m.rect)];
}

/** A rect with its feather band, in normalized units: [left, top, right, bottom, featherX, featherY]. */
type Feathered = [number, number, number, number, number, number];

const feathered = (regions: readonly NRect[]): Feathered[] =>
  regions
    .filter((r) => r.right > r.left && r.bottom > r.top)
    .map((r) => [
      r.left,
      r.top,
      r.right,
      r.bottom,
      Math.max((r.right - r.left) * FEATHER, MIN_FEATHER),
      Math.max((r.bottom - r.top) * FEATHER, MIN_FEATHER),
    ]);

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

function smoothstep(e0: number, e1: number, x: number): number {
  const t = clamp((x - e0) / (e1 - e0), 0, 1);
  return t * t * (3 - 2 * t);
}

/** The pixel window [x0, x1) x [y0, y1) a cover reaches, feather included; null when it reaches nothing. */
export interface CoverMask {
  x0: number;
  y0: number;
  width: number;
  height: number;
  /** RGBA, `width` x `height`; only alpha is set: 255 inside the hard rects, ramping to 0 across the feather. */
  rgba: Uint8ClampedArray<ArrayBuffer>;
}

/**
 * The cover's alpha over a `w` x `h` frame: the largest of each rect's feathered mask (Censor.kt's maths).
 * Alpha rounds UP, so a pixel the feather touches at all is touched, and 1.0 is exactly 255.
 */
export function coverMask(regions: readonly NRect[], w: number, h: number): CoverMask | null {
  const rects = feathered(regions);
  if (rects.length === 0) return null;
  const x0 = clamp(Math.floor(Math.min(...rects.map((r) => r[0] - r[4])) * w), 0, w);
  const y0 = clamp(Math.floor(Math.min(...rects.map((r) => r[1] - r[5])) * h), 0, h);
  const x1 = clamp(Math.ceil(Math.max(...rects.map((r) => r[2] + r[4])) * w), 0, w);
  const y1 = clamp(Math.ceil(Math.max(...rects.map((r) => r[3] + r[5])) * h), 0, h);
  if (x0 >= x1 || y0 >= y1) return null;
  const width = x1 - x0;
  const height = y1 - y0;
  const rgba = new Uint8ClampedArray(4 * width * height);
  for (let y = y0; y < y1; y++) {
    const v = (y + 0.5) / h;
    for (let x = x0; x < x1; x++) {
      const u = (x + 0.5) / w;
      let mask = 0;
      for (const [l, t, r, b, fx, fy] of rects) {
        const m =
          smoothstep(l - fx, l, u) * (1 - smoothstep(r, r + fx, u)) * smoothstep(t - fy, t, v) * (1 - smoothstep(b, b + fy, v));
        if (m > mask) mask = m;
        if (mask >= 1) break;
      }
      rgba[4 * ((y - y0) * width + (x - x0)) + 3] = Math.ceil(mask * 255);
    }
  }
  return { x0, y0, width, height, rgba };
}

function context(canvas: OffscreenCanvas, alpha: boolean): OffscreenCanvasRenderingContext2D {
  const ctx = canvas.getContext('2d', { alpha });
  if (!ctx) throw new Error('This browser has no 2D OffscreenCanvas, which the censor effect needs.');
  return ctx;
}

/** Draws frames with the censor applied. One per render; the canvas it returns is reused frame to frame. */
export class Censor {
  readonly canvas: OffscreenCanvas;
  private readonly ctx: OffscreenCanvasRenderingContext2D;
  private readonly style: CensorStyle;
  private readonly plan: { scale: number; sigma: number };
  /** Low-res copy with replicated edges, its blurred twin, and the masked window. */
  private readonly pad: OffscreenCanvas;
  private readonly padCtx: OffscreenCanvasRenderingContext2D;
  private readonly blurred: OffscreenCanvas;
  private readonly blurredCtx: OffscreenCanvasRenderingContext2D;
  private readonly win = new OffscreenCanvas(1, 1);
  private readonly winCtx = context(this.win, true);
  private readonly lowW: number;
  private readonly lowH: number;
  private readonly margin: number;

  constructor(
    readonly width: number,
    readonly height: number,
    ops: Pick<FilterOps, 'blurAmount' | 'grayscale' | 'solidColor'>,
  ) {
    this.canvas = new OffscreenCanvas(width, height);
    this.ctx = context(this.canvas, false);
    // Without canvas filters the "blur" would draw the frame unchanged: stop rather than censor nothing.
    if (!('filter' in this.ctx)) throw new Error('This browser cannot blur on a canvas (no 2D filter support).');
    this.style = censorStyle(ops);
    this.plan = blurPlan(this.style.amount, width, height);
    this.lowW = Math.max(1, Math.floor(width / this.plan.scale));
    this.lowH = Math.max(1, Math.floor(height / this.plan.scale));
    this.margin = Math.ceil(3 * this.plan.sigma) + 2;
    this.pad = new OffscreenCanvas(this.lowW + 2 * this.margin, this.lowH + 2 * this.margin);
    this.padCtx = context(this.pad, false);
    this.blurred = new OffscreenCanvas(this.pad.width, this.pad.height);
    this.blurredCtx = context(this.blurred, true);
  }

  /** Draws `sample` upright and covers what the EDL holds at its timestamp. Returns the canvas. */
  draw(sample: VideoSample, edl: Edl): OffscreenCanvas {
    const { ctx, width: w, height: h } = this;
    sample.draw(ctx, 0, 0, w, h);
    const cover = coverAt(edl, Math.round(sample.timestamp * 1000));
    if (cover === 'full') this.coverAll();
    else this.coverRegions(cover);
    return this.canvas;
  }

  private coverAll(): void {
    const { ctx, width: w, height: h } = this;
    if (this.style.solid) {
      ctx.fillStyle = this.style.solid;
      ctx.fillRect(0, 0, w, h);
      return;
    }
    this.blur();
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(this.blurred, this.margin, this.margin, this.lowW, this.lowH, 0, 0, w, h);
  }

  private coverRegions(regions: readonly NRect[]): void {
    const { width: w, height: h } = this;
    const mask = coverMask(regions, w, h);
    if (!mask) return;
    const win = this.win;
    if (win.width !== mask.width || win.height !== mask.height) {
      win.width = mask.width;
      win.height = mask.height;
    }
    const wctx = this.winCtx;
    wctx.globalCompositeOperation = 'copy';
    wctx.putImageData(new ImageData(mask.rgba, mask.width, mask.height), 0, 0);
    wctx.globalCompositeOperation = 'source-in'; // keep the effect only where the mask is
    if (this.style.solid) {
      wctx.fillStyle = this.style.solid;
      wctx.fillRect(0, 0, mask.width, mask.height);
    } else {
      this.blur();
      const sx = this.lowW / w;
      const sy = this.lowH / h;
      wctx.imageSmoothingEnabled = true;
      wctx.drawImage(
        this.blurred,
        this.margin + mask.x0 * sx,
        this.margin + mask.y0 * sy,
        mask.width * sx,
        mask.height * sy,
        0,
        0,
        mask.width,
        mask.height,
      );
    }
    wctx.globalCompositeOperation = 'source-over';
    this.ctx.drawImage(win, mask.x0, mask.y0);
  }

  /** The current frame, downscaled, edge-replicated, blurred (and desaturated) into `blurred`. */
  private blur(): void {
    const { lowW: lw, lowH: lh, margin: p } = this;
    const pctx = this.padCtx;
    pctx.imageSmoothingEnabled = true;
    pctx.imageSmoothingQuality = 'high';
    pctx.drawImage(this.canvas, 0, 0, this.width, this.height, p, p, lw, lh);
    // Edges: each border row/column stretched outward, then the corners.
    pctx.imageSmoothingEnabled = false;
    const pad = this.pad;
    pctx.drawImage(pad, p, p, 1, lh, 0, p, p, lh);
    pctx.drawImage(pad, p + lw - 1, p, 1, lh, p + lw, p, p, lh);
    pctx.drawImage(pad, 0, p, lw + 2 * p, 1, 0, 0, lw + 2 * p, p);
    pctx.drawImage(pad, 0, p + lh - 1, lw + 2 * p, 1, 0, p + lh, lw + 2 * p, p);

    const bctx = this.blurredCtx;
    bctx.globalCompositeOperation = 'copy';
    bctx.filter = `blur(${this.plan.sigma}px)${this.style.grayscale ? ' grayscale(1)' : ''}`;
    bctx.drawImage(pad, 0, 0);
    bctx.filter = 'none';
  }
}
