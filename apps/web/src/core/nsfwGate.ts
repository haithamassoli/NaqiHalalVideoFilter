// Port of core/src/main/kotlin/com/haithamassoli/naqi/analysis/NsfwGate.kt and ml/NsfwClasses.kt:
// the strictness→threshold table, the per-sample delta firing rule, and the hysteresis that expands
// firings into merged whole-frame censor intervals. All times in milliseconds.

import type { Span } from './edl';

/** GantMan class order (alphabetical, index-locked). sfw = drawings+neutral; nsfw = the rest. */
export const NSFW_CLASSES = ['drawings', 'hentai', 'neutral', 'porn', 'sexy'] as const;

/** Each firing at t censors [t - PRE_MS, t + POST_MS]. */
export const PRE_MS = 500;
export const POST_MS = 1500;

// Strictness table keyed by class NAME: [thr at s=0, thr at s=100], linear in between.
const TABLE: Record<(typeof NSFW_CLASSES)[number], readonly [number, number]> = {
  porn: [0.75, 0.1],
  sexy: [0.9, 0.1],
  hentai: [1.0, 0.5],
  neutral: [0.3, 1.0],
  drawings: [0.5, 0.5],
};

const t0 = NSFW_CLASSES.map((c) => TABLE[c][0]);
const t100 = NSFW_CLASSES.map((c) => TABLE[c][1]);
const nsfwIdx = (['porn', 'sexy', 'hentai'] as const).map((c) => NSFW_CLASSES.indexOf(c));
const sfwIdx = (['neutral', 'drawings'] as const).map((c) => NSFW_CLASSES.indexOf(c));

/**
 * Interpolated threshold for class `classIndex` at `strictness` (clamped to 0..100). Kotlin's Int cannot
 * be NaN; here a NaN strictness reads as 100, the strictest end, so a bad value never disables the gate.
 */
export function thr(classIndex: number, strictness: number): number {
  const s = Number.isNaN(strictness) ? 100 : Math.min(100, Math.max(0, Math.trunc(strictness)));
  return t0[classIndex] + ((t100[classIndex] - t0[classIndex]) * s) / 100;
}

/**
 * Delta rule per sample: nsfwMax = max over {porn,sexy,hentai} of (p − thr), sfwMax = max over
 * {neutral,drawings} of (p − thr); fire iff nsfwMax ≥ 0 AND nsfwMax > sfwMax.
 */
export function fires(probs: ArrayLike<number>, strictness: number): boolean {
  let nsfwMax = -Infinity;
  for (const c of nsfwIdx) nsfwMax = Math.max(nsfwMax, probs[c] - thr(c, strictness));
  let sfwMax = -Infinity;
  for (const c of sfwIdx) sfwMax = Math.max(sfwMax, probs[c] - thr(c, strictness));
  return nsfwMax >= 0 && nsfwMax > sfwMax;
}

/**
 * Hysteresis: expand each firing to [t − PRE_MS, t + POST_MS], merge overlapping or gap-free adjacent
 * spans, clamp to [0, durationMs]. Input need not be sorted; the result is time-ordered.
 */
export function intervals(firingsMs: readonly number[], durationMs: number): Span[] {
  if (firingsMs.length === 0) return [];
  const clamp = (v: number) => Math.min(durationMs, Math.max(0, v));
  const sorted = [...firingsMs].sort((a, b) => a - b);
  const out: Span[] = [];
  let start = clamp(sorted[0] - PRE_MS);
  let end = clamp(sorted[0] + POST_MS);
  for (let i = 1; i < sorted.length; i++) {
    const s = clamp(sorted[i] - PRE_MS);
    const e = clamp(sorted[i] + POST_MS);
    if (s <= end + 1) {
      if (e > end) end = e;
    } else {
      out.push([start, end]);
      start = s;
      end = e;
    }
  }
  out.push([start, end]);
  return out;
}
