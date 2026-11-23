// Port of core/src/test/kotlin/com/haithamassoli/naqi/analysis/FaceTrackerLogicTest.kt, case by case.
import { describe, expect, it } from 'vitest';
import { FaceTrack, nrect } from '../src/core/contracts';
import { edlFor, isStale, padRect, shouldCensor } from '../src/core/faceTrackerLogic';
import { EVERYONE, MEN, NONE, WOMEN } from '../src/core/filterOps';

function track(...ptsMs: number[]): FaceTrack {
  const t = new FaceTrack(1);
  for (const p of ptsMs) t.samples.push({ ptsMs: p, rect: nrect(0.4, 0.4, 0.6, 0.6), mask: null });
  return t;
}

describe('FaceTrackerLogic', () => {
  it('a track is censored and its span is padded by half a sample gap', () => {
    const edl = edlFor(track(1_000, 1_100, 1_200))!;
    expect(edl.startMs).toBe(950);
    expect(edl.endMs).toBe(1_250);
    expect(edl.keyframes.length).toBe(3);
  });

  it('a single-sample track still produces a span', () => {
    const edl = edlFor(track(1_000))!;
    expect(edl.startMs).toBe(950);
    expect(edl.endMs).toBe(1_050);
    expect(edl.keyframes.length).toBe(1);
  });

  it('sampleless track emits nothing rather than throwing', () => {
    expect(edlFor(track())).toBeNull();
  });

  it('span start clamps at zero for a track that begins in the first frames', () => {
    expect(edlFor(track(0, 100))!.startMs).toBe(0);
  });

  it('keyframe rects are padded and clamped to the frame', () => {
    const padded = padRect(nrect(0.4, 0.4, 0.6, 0.6));
    expect(padded.left).toBeCloseTo(0.35, 6);
    expect(padded.right).toBeCloseTo(0.65, 6);
    const clamped = padRect(nrect(0, 0, 1, 1));
    expect(clamped.left).toBeCloseTo(0, 6);
    expect(clamped.bottom).toBeCloseTo(1, 6);
  });

  it('a track goes stale only after the eviction gap of source time', () => {
    expect(isStale(10_000, 10_000)).toBe(false);
    expect(isStale(10_000, 11_900)).toBe(false);
    expect(isStale(10_000, 12_000)).toBe(true);
    expect(isStale(10_000, 30_000)).toBe(true);
  });

  // Load-bearing fail-safe: a track with no votes cast is CENSORED.
  it('zero votes censors in every selective mode', () => {
    expect(shouldCensor(0, 0, WOMEN)).toBe(true);
    expect(shouldCensor(0, 0, MEN)).toBe(true);
  });

  it('a tied vote censors in every selective mode', () => {
    expect(shouldCensor(2, 2, WOMEN)).toBe(true);
    expect(shouldCensor(2, 2, MEN)).toBe(true);
  });

  it('everyone censors whatever the counters say', () => {
    expect(shouldCensor(0, 0, EVERYONE)).toBe(true);
    expect(shouldCensor(5, 0, EVERYONE)).toBe(true);
    expect(shouldCensor(0, 5, EVERYONE)).toBe(true);
  });

  it('none censors nothing whatever the counters say', () => {
    expect(shouldCensor(0, 0, NONE)).toBe(false);
    expect(shouldCensor(5, 0, NONE)).toBe(false);
  });

  it('women mode censors the female majority and spares the male one', () => {
    expect(shouldCensor(4, 1, WOMEN)).toBe(true);
    expect(shouldCensor(1, 4, WOMEN)).toBe(false);
  });

  it('men mode censors the male majority and spares the female one', () => {
    expect(shouldCensor(1, 4, MEN)).toBe(true);
    expect(shouldCensor(4, 1, MEN)).toBe(false);
  });

  it('edlFor emits a span for a censored track and nothing for a spared one', () => {
    const male = track(1_000, 1_100);
    male.maleVotes = 3;
    expect(edlFor(male, WOMEN)).toBeNull();
    expect(edlFor(male, MEN)).not.toBeNull();
    expect(edlFor(male, EVERYONE)).not.toBeNull();
    expect(edlFor(male, MEN)!.startMs).toBe(950);
  });

  // Web-only: an unknown `who` (no Kotlin String constraint here) must censor, like EVERYONE.
  it('an unknown who value censors', () => {
    expect(shouldCensor(0, 5, 'bogus')).toBe(true);
  });
});
