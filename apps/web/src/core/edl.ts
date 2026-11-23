// Port of core/src/main/kotlin/com/haithamassoli/naqi/edl/Edl.kt: the per-frame censor plan from the
// analysis pass, read by the render pass. The JSON shape is Kotlin's `Edl.toJson`, byte-compatible in
// structure. All times in milliseconds; spans are inclusive [first, last] like Kotlin's LongRange.

import { nrect, type NRect } from './contracts';

/** Inclusive [first, last] in ms (Kotlin LongRange). */
export type Span = [number, number];

/**
 * A person's silhouette over their unpadded body `rect` (upright, normalized): `width`x`height` cells,
 * row-major from the top, one bit per cell, LSB first.
 */
export interface BodyMask {
  rect: NRect;
  width: number;
  height: number;
  bits: Uint8Array;
}

export function maskAt(m: BodyMask, x: number, y: number): boolean {
  if (x < 0 || x >= m.width || y < 0 || y >= m.height) return false;
  const i = y * m.width + x;
  return ((m.bits[i >> 3] >> (i % 8)) & 1) === 1;
}

/**
 * One tracked face's censor span with normalized keyframe rects (already padded), sorted by time.
 * `union` marks a body track: between two samples it covers both boxes instead of interpolating.
 * `masks`, precise body mode only, parallels `keyframes`.
 */
export interface FaceTrackEdl {
  startMs: number;
  endMs: number;
  keyframes: [number, NRect][];
  union: boolean;
  masks: (BodyMask | null)[] | null;
}

export const faceTrackEdl = (
  startMs: number,
  endMs: number,
  keyframes: [number, NRect][],
  union = false,
  masks: (BodyMask | null)[] | null = null,
): FaceTrackEdl => ({ startMs, endMs, keyframes, union, masks });

/** Precedence: an active censor interval blanks the whole frame and suppresses face regions and masks. */
export interface Edl {
  censorIntervalsMs: Span[];
  faceTracks: FaceTrackEdl[];
}

/** True when `tMs` falls inside any whole-frame censor interval. */
export function fullFrameAt(edl: Edl, tMs: number): boolean {
  for (const [a, b] of edl.censorIntervalsMs) if (tMs >= a && tMs <= b) return true;
  return false;
}

/** Face rects to censor at `tMs`: empty under full-frame precedence, else one per active track without masks. */
export function regionsAt(edl: Edl, tMs: number): NRect[] {
  if (fullFrameAt(edl, tMs)) return [];
  const out: NRect[] = [];
  for (const tr of edl.faceTracks) {
    if (tMs < tr.startMs || tMs > tr.endMs) continue;
    if (trackMasksAt(tr, tMs) !== null) continue;
    const rect = rectAt(tr, tMs);
    if (rect) out.push(rect);
  }
  return out;
}

/** Silhouettes to censor at `tMs` (precise body mode), from the tracks `regionsAt` leaves out. */
export function masksAt(edl: Edl, tMs: number): BodyMask[] {
  if (fullFrameAt(edl, tMs)) return [];
  const out: BodyMask[] = [];
  for (const tr of edl.faceTracks) {
    if (tMs < tr.startMs || tMs > tr.endMs) continue;
    const masks = trackMasksAt(tr, tMs);
    if (masks) out.push(...masks);
  }
  return out;
}

const toB64 = (bits: Uint8Array): string => btoa(String.fromCharCode(...bits));
const fromB64 = (s: string): Uint8Array => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export function edlToJson(edl: Edl): string {
  return JSON.stringify({
    censorIntervalsMs: edl.censorIntervalsMs.map(([a, b]) => [a, b]),
    faceTracks: edl.faceTracks.map((tr) => {
      const o: Record<string, unknown> = {
        startMs: tr.startMs,
        endMs: tr.endMs,
        keyframes: tr.keyframes.map(([t, r]) => [t, r.left, r.top, r.right, r.bottom]),
      };
      if (tr.union) o.union = true;
      if (tr.masks) {
        o.masks = tr.masks.map((m) =>
          m ? [m.rect.left, m.rect.top, m.rect.right, m.rect.bottom, m.width, m.height, toB64(m.bits)] : null,
        );
      }
      return o;
    }),
  });
}

// Kotlin's org.json getters throw on a missing or mistyped field; so do these, so a damaged EDL stops the
// export instead of rendering with less censoring.
function num(v: unknown): number {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new Error(`EDL: expected a number, got ${JSON.stringify(v)}`);
  return v;
}
function arr(v: unknown): unknown[] {
  if (!Array.isArray(v)) throw new Error(`EDL: expected an array, got ${JSON.stringify(v)}`);
  return v;
}
const long = (v: unknown): number => Math.trunc(num(v));

function maskFromJson(a: unknown[]): BodyMask {
  if (typeof a[6] !== 'string') throw new Error('EDL: mask bits must be base64');
  const m: BodyMask = {
    rect: nrect(num(a[0]), num(a[1]), num(a[2]), num(a[3])),
    width: long(a[4]),
    height: long(a[5]),
    bits: fromB64(a[6]),
  };
  if (!(m.width >= 1 && m.width <= 64 && m.height >= 1 && m.height <= 64 && m.bits.length === ((m.width * m.height + 7) >> 3))) {
    throw new Error('EDL: malformed mask');
  }
  return m;
}

export function edlFromJson(s: string): Edl {
  const root = JSON.parse(s) as Record<string, unknown>;
  const censorIntervalsMs = arr(root.censorIntervalsMs).map((p): Span => {
    const a = arr(p);
    return [long(a[0]), long(a[1])];
  });
  const faceTracks = arr(root.faceTracks).map((raw) => {
    const t = raw as Record<string, unknown>;
    const keyframes = arr(t.keyframes).map((k): [number, NRect] => {
      const a = arr(k);
      return [long(a[0]), nrect(num(a[1]), num(a[2]), num(a[3]), num(a[4]))];
    });
    let masks: (BodyMask | null)[] | null = null;
    if (Array.isArray(t.masks)) {
      if (t.masks.length !== keyframes.length) throw new Error('masks must parallel keyframes');
      masks = t.masks.map((m) => (Array.isArray(m) ? maskFromJson(m) : null));
    }
    return faceTrackEdl(long(t.startMs), long(t.endMs), keyframes, t.union === true, masks);
  });
  return { censorIntervalsMs, faceTracks };
}

/** Gap under which two whole-frame spans merge rather than strobe. */
export const BRIDGE_MS = 400;

/** Merge overlapping or near-adjacent ranges. Input need not be sorted; output is, and is disjoint. */
export function mergeRanges(ranges: readonly Span[], bridgeMs: number = BRIDGE_MS): Span[] {
  if (ranges.length <= 1) return ranges.map(([a, b]) => [a, b]);
  const sorted = [...ranges].sort((x, y) => x[0] - y[0]);
  const out: Span[] = [];
  let [start, end] = sorted[0];
  for (let i = 1; i < sorted.length; i++) {
    const [a, b] = sorted[i];
    if (a <= end + bridgeMs) {
      if (b > end) end = b;
    } else {
      out.push([start, end]);
      start = a;
      end = b;
    }
  }
  out.push([start, end]);
  return out;
}

/**
 * Shortest whole-frame span worth keeping. Dropping a shorter one costs no coverage: `regionsAt` then
 * returns that track's own rect, so the face stays censored; only the whole-frame flash goes away.
 */
export const MIN_FULL_MS = 500;

/** Whole-frame mode: every censored face span becomes a whole-frame span, merged into `intervals`. */
export function promoteFacesToFullFrame(intervals: readonly Span[], tracks: readonly FaceTrackEdl[]): Span[] {
  return mergeRanges([...intervals, ...tracks.map((t): Span => [t.startMs, t.endMs])]).filter(
    ([a, b]) => b - a >= MIN_FULL_MS,
  );
}

/** Largest keyframe index whose time is <= tMs (callers guarantee kf[0] <= tMs). */
function segmentAt(kf: [number, NRect][], tMs: number): number {
  let lo = 0;
  let hi = kf.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >>> 1;
    if (kf[mid][0] <= tMs) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

/**
 * Linear-interpolate the track's rect at `tMs` (a union track covers both neighbours instead), clamped
 * to the first/last keyframe at the span edges.
 */
function rectAt(tr: FaceTrackEdl, tMs: number): NRect | null {
  const kf = tr.keyframes;
  if (kf.length === 0) return null;
  if (tMs <= kf[0][0]) return kf[0][1];
  if (tMs >= kf[kf.length - 1][0]) return kf[kf.length - 1][1];
  const lo = segmentAt(kf, tMs);
  const [t0, r0] = kf[lo];
  const [t1, r1] = kf[lo + 1];
  if (t1 <= t0 || tMs === t0) return r0;
  if (tr.union) {
    return nrect(Math.min(r0.left, r1.left), Math.min(r0.top, r1.top), Math.max(r0.right, r1.right), Math.max(r0.bottom, r1.bottom));
  }
  const f = (tMs - t0) / (t1 - t0);
  return nrect(
    r0.left + (r1.left - r0.left) * f,
    r0.top + (r1.top - r0.top) * f,
    r0.right + (r1.right - r0.right) * f,
    r0.bottom + (r1.bottom - r0.bottom) * f,
  );
}

/** Two observations further apart than this have held (unseen) samples between them. */
const MASK_GAP_MS = 150;

/**
 * The silhouettes to draw at `tMs`, or null when the box must be drawn instead: a held or unmasked sample
 * never narrows coverage below its rectangle (lead-in, hold, and any gap with missed samples).
 */
function trackMasksAt(tr: FaceTrackEdl, tMs: number): BodyMask[] | null {
  const ms = tr.masks;
  if (!ms) return null;
  const kf = tr.keyframes;
  if (kf.length === 0 || tMs < kf[0][0] || tMs > kf[kf.length - 1][0]) return null;
  const lo = segmentAt(kf, tMs);
  const a = ms[lo];
  if (!a) return null;
  if (kf[lo][0] === tMs) return [a];
  const b = ms[lo + 1];
  if (!b) return null;
  return kf[lo + 1][0] - kf[lo][0] <= MASK_GAP_MS ? [a, b] : null;
}
