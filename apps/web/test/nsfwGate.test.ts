// Port of core/src/test/kotlin/com/haithamassoli/naqi/analysis/NsfwGateTest.kt, case by case.
import { describe, expect, it } from 'vitest';
import { NSFW_CLASSES, fires, intervals, thr } from '../src/core/nsfwGate';

// NSFW_CLASSES index order: drawings=0, hentai=1, neutral=2, porn=3, sexy=4.
const probs = (drawings: number, hentai: number, neutral: number, porn: number, sexy: number) =>
  new Float32Array([drawings, hentai, neutral, porn, sexy]);
const EPS = 5; // toBeCloseTo digits: |diff| < 5e-6, as tight as Kotlin's 1e-5

describe('NsfwGate', () => {
  it('keeps the GantMan class order', () => {
    expect(NSFW_CLASSES).toEqual(['drawings', 'hentai', 'neutral', 'porn', 'sexy']);
  });

  it('threshold endpoints and midpoint per class', () => {
    // s=0 (t0 column)
    expect(thr(0, 0)).toBeCloseTo(0.5, EPS);
    expect(thr(1, 0)).toBeCloseTo(1.0, EPS);
    expect(thr(2, 0)).toBeCloseTo(0.3, EPS);
    expect(thr(3, 0)).toBeCloseTo(0.75, EPS);
    expect(thr(4, 0)).toBeCloseTo(0.9, EPS);
    // s=100 (t100 column)
    expect(thr(0, 100)).toBeCloseTo(0.5, EPS);
    expect(thr(1, 100)).toBeCloseTo(0.5, EPS);
    expect(thr(2, 100)).toBeCloseTo(1.0, EPS);
    expect(thr(3, 100)).toBeCloseTo(0.1, EPS);
    expect(thr(4, 100)).toBeCloseTo(0.1, EPS);
    // s=50 (linear midpoint)
    expect(thr(0, 50)).toBeCloseTo(0.5, EPS);
    expect(thr(1, 50)).toBeCloseTo(0.75, EPS);
    expect(thr(2, 50)).toBeCloseTo(0.65, EPS);
    expect(thr(3, 50)).toBeCloseTo(0.425, EPS);
    expect(thr(4, 50)).toBeCloseTo(0.5, EPS);
  });

  it('strictness clamps out of range', () => {
    expect(thr(3, -20)).toBeCloseTo(thr(3, 0), EPS);
    expect(thr(3, 200)).toBeCloseTo(thr(3, 100), EPS);
  });

  // Web-only: a NaN strictness reads as the strictest end, never as a disabled gate.
  it('NaN strictness reads as 100', () => {
    expect(thr(3, NaN)).toBeCloseTo(thr(3, 100), EPS);
  });

  it('fires on clear porn', () => {
    expect(fires(probs(0.1, 0.1, 0.1, 0.6, 0.1), 50)).toBe(true);
  });

  it('fires on sexy class', () => {
    expect(fires(probs(0, 0, 0.1, 0, 0.85), 50)).toBe(true);
  });

  it('neutral veto at low strictness, disabled at 100', () => {
    const p = probs(0, 0, 0.55, 0.8, 0);
    expect(fires(p, 0)).toBe(false); // sfwMax 0.25 > nsfwMax 0.05 → vetoed
    expect(fires(p, 100)).toBe(true); // neutral thr → 1.0 kills the veto → fires
  });

  it('neutral content does not fire', () => {
    expect(fires(probs(0.05, 0, 0.9, 0.05, 0), 50)).toBe(false);
  });

  it('hentai only fires at high strictness', () => {
    const p = probs(0, 0.9, 0.05, 0, 0);
    expect(fires(p, 0)).toBe(false);
    expect(fires(p, 100)).toBe(true);
  });

  it('intervals empty when no firings', () => {
    expect(intervals([], 10_000)).toEqual([]);
  });

  it('intervals merge overlapping', () => {
    expect(intervals([1000, 1500], 10_000)).toEqual([[500, 3000]]);
  });

  it('intervals merge gap-free adjacent', () => {
    expect(intervals([1000, 3001], 10_000)).toEqual([[500, 4501]]);
  });

  it('intervals keep disjoint', () => {
    expect(intervals([1000, 3002], 10_000)).toEqual([[500, 2500], [2502, 4502]]);
  });

  it('intervals sort unsorted input', () => {
    expect(intervals([1500, 1000], 10_000)).toEqual([[500, 3000]]);
  });

  it('intervals clamp to zero and duration', () => {
    expect(intervals([200], 10_000)).toEqual([[0, 1700]]);
    expect(intervals([9800], 10_000)).toEqual([[9300, 10_000]]);
  });
});
