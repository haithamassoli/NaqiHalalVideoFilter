// Port of core/src/test/kotlin/com/haithamassoli/naqi/edl/EdlTest.kt, case by case.
import { describe, expect, it } from 'vitest';
import { nrect as r, type NRect } from '../src/core/contracts';
import {
  BRIDGE_MS,
  MIN_FULL_MS,
  edlFromJson,
  edlToJson,
  faceTrackEdl,
  fullFrameAt,
  maskAt,
  masksAt,
  mergeRanges,
  promoteFacesToFullFrame,
  regionsAt,
  type BodyMask,
  type Edl,
  type Span,
} from '../src/core/edl';

const EPS = 5; // toBeCloseTo digits: |diff| < 5e-6, as tight as Kotlin's 1e-5

// Interpolated rects can't be compared exactly (float rounding), so compare per field.
function expectRegions(expected: NRect[], actual: NRect[]): void {
  expect(actual.length).toBe(expected.length);
  expected.forEach((e, i) => {
    expect(actual[i].left).toBeCloseTo(e.left, EPS);
    expect(actual[i].top).toBeCloseTo(e.top, EPS);
    expect(actual[i].right).toBeCloseTo(e.right, EPS);
    expect(actual[i].bottom).toBeCloseTo(e.bottom, EPS);
  });
}

const edlOf = (censorIntervalsMs: Span[], faceTracks: Edl['faceTracks']): Edl => ({ censorIntervalsMs, faceTracks });

describe('Edl', () => {
  it('full frame inside censor interval, inclusive', () => {
    const edl = edlOf([[1000, 2000]], []);
    expect(fullFrameAt(edl, 1000)).toBe(true);
    expect(fullFrameAt(edl, 1500)).toBe(true);
    expect(fullFrameAt(edl, 2000)).toBe(true);
    expect(fullFrameAt(edl, 999)).toBe(false);
    expect(fullFrameAt(edl, 2001)).toBe(false);
  });

  it('precedence: full frame suppresses regions', () => {
    const track = faceTrackEdl(0, 5000, [[0, r(0, 0, 0.2, 0.2)], [5000, r(0, 0, 0.2, 0.2)]]);
    const edl = edlOf([[1000, 2000]], [track]);
    expectRegions([], regionsAt(edl, 1500)); // inside censor → empty (precedence)
    expectRegions([r(0, 0, 0.2, 0.2)], regionsAt(edl, 3000)); // outside censor → region
  });

  it('keyframe interpolation midpoint', () => {
    const track = faceTrackEdl(0, 1000, [[0, r(0, 0, 0.2, 0.2)], [1000, r(0.4, 0.4, 0.6, 0.6)]]);
    expectRegions([r(0.2, 0.2, 0.4, 0.4)], regionsAt(edlOf([], [track]), 500));
  });

  it('keyframe edge clamping', () => {
    const track = faceTrackEdl(0, 2000, [[500, r(0.1, 0.1, 0.2, 0.2)], [1500, r(0.5, 0.5, 0.6, 0.6)]]);
    const edl = edlOf([], [track]);
    expectRegions([r(0.1, 0.1, 0.2, 0.2)], regionsAt(edl, 100)); // before first kf → first
    expectRegions([r(0.5, 0.5, 0.6, 0.6)], regionsAt(edl, 1900)); // after last kf → last
    expectRegions([r(0.3, 0.3, 0.4, 0.4)], regionsAt(edl, 1000)); // midpoint
  });

  it('body track covers both neighbours between samples', () => {
    const a = r(0.1, 0.2, 0.3, 0.6);
    const b = r(0.2, 0.1, 0.5, 0.5);
    const track = faceTrackEdl(0, 400, [[100, a], [200, b]], true);
    const edl = edlOf([], [track]);
    expectRegions([r(0.1, 0.1, 0.5, 0.6)], regionsAt(edl, 150));
    expectRegions([a], regionsAt(edl, 100)); // an exact sample stays exact
    expectRegions([b], regionsAt(edl, 200));
    expectRegions([a], regionsAt(edl, 20)); // lead-in holds the first box
    expect(edlFromJson(edlToJson(edl))).toEqual(edl);
    expect(edlFromJson(edlToJson(edlOf([], [{ ...track, union: false }]))).faceTracks[0].union).toBe(false);
  });

  it('precise tracks draw observed masks and fall back to the box', () => {
    const box = r(0.1, 0.1, 0.4, 0.9);
    const m: BodyMask = { rect: r(0.15, 0.2, 0.35, 0.8), width: 2, height: 2, bits: new Uint8Array([0b1001]) };
    const n: BodyMask = { rect: r(0.16, 0.2, 0.36, 0.8), width: 2, height: 2, bits: new Uint8Array([0b0110]) };
    const track = faceTrackEdl(
      0,
      1500,
      [[100, box], [200, box], [300, box], [900, box], [1000, box]],
      true,
      [m, n, null, m, m],
    );
    const edl = edlOf([], [track]);
    expect(masksAt(edl, 100)).toEqual([m]);
    expectRegions([], regionsAt(edl, 100));
    expect(masksAt(edl, 150)).toEqual([m, n]); // consecutive samples: both silhouettes
    expect(masksAt(edl, 250)).toEqual([]); // a sample without a mask: its box
    expectRegions([box], regionsAt(edl, 250));
    expect(masksAt(edl, 600)).toEqual([]); // missed samples in between: the box
    expectRegions([box], regionsAt(edl, 600));
    expect(masksAt(edl, 950)).toEqual([m, m]);
    expect(masksAt(edl, 50).length === 0 && masksAt(edl, 1200).length === 0).toBe(true); // lead-in and hold: the box
    expectRegions([box], regionsAt(edl, 1200));
    expect(masksAt(edlOf([[0, 2000]], [track]), 100)).toEqual([]); // whole frame wins
    expect(maskAt(m, 0, 0) && !maskAt(m, 1, 0) && !maskAt(m, 0, 1) && maskAt(m, 1, 1) && !maskAt(m, 2, 0)).toBe(true);
    const back = edlFromJson(edlToJson(edl));
    expect(back.faceTracks[0].masks).toEqual(edl.faceTracks[0].masks);
    expect(masksAt(back, 150)).toEqual(masksAt(edl, 150));
  });

  it('regions empty outside track span', () => {
    const track = faceTrackEdl(1000, 2000, [[1000, r(0, 0, 0.2, 0.2)], [2000, r(0, 0, 0.2, 0.2)]]);
    const edl = edlOf([], [track]);
    expectRegions([], regionsAt(edl, 500));
    expectRegions([], regionsAt(edl, 2500));
  });

  it('multiple active tracks yield multiple regions', () => {
    const a = faceTrackEdl(0, 1000, [[0, r(0, 0, 0.1, 0.1)], [1000, r(0, 0, 0.1, 0.1)]]);
    const b = faceTrackEdl(0, 1000, [[0, r(0.5, 0.5, 0.6, 0.6)], [1000, r(0.5, 0.5, 0.6, 0.6)]]);
    expectRegions([r(0, 0, 0.1, 0.1), r(0.5, 0.5, 0.6, 0.6)], regionsAt(edlOf([], [a, b]), 500));
  });

  it('JSON round trip', () => {
    const edl = edlOf(
      [[0, 1700], [2500, 4000]],
      [
        faceTrackEdl(0, 1000, [[0, r(0.1, 0.2, 0.3, 0.4)], [1000, r(0.15, 0.25, 0.35, 0.45)]]),
        faceTrackEdl(2000, 3000, [[2000, r(0.5, 0.5, 0.7, 0.7)]]),
      ],
    );
    expect(edlFromJson(edlToJson(edl))).toEqual(edl);
  });

  it('JSON round trip, empty', () => {
    const edl = edlOf([], []);
    expect(edlFromJson(edlToJson(edl))).toEqual(edl);
  });

  // Web-only: the wire shape is Kotlin's Edl.toJson, so an EDL from the Kotlin apps reads here unchanged.
  it('reads the Kotlin JSON shape', () => {
    const kotlin =
      '{"censorIntervalsMs":[[0,1700]],"faceTracks":[{"startMs":0,"endMs":1000,"keyframes":[[0,0.1,0.2,0.3,0.4]],' +
      '"union":true,"masks":[[0.15,0.2,0.35,0.8,2,2,"CQ=="]]}]}';
    const edl = edlFromJson(kotlin);
    expect(edl.censorIntervalsMs).toEqual([[0, 1700]]);
    expect(edl.faceTracks[0].union).toBe(true);
    expect(Array.from(edl.faceTracks[0].masks![0]!.bits)).toEqual([0b1001]);
    expect(edlToJson(edl)).toBe(kotlin);
  });

  // Web-only fail-safe: a damaged EDL throws (stopping the export) instead of rendering with less censoring.
  it('a damaged EDL throws', () => {
    expect(() => edlFromJson('{"faceTracks":[]}')).toThrow();
    expect(() => edlFromJson('{"censorIntervalsMs":[["a",1]],"faceTracks":[]}')).toThrow();
    expect(() =>
      edlFromJson('{"censorIntervalsMs":[],"faceTracks":[{"startMs":0,"endMs":1,"keyframes":[[0,0,0,1,1]],"masks":[]}]}'),
    ).toThrow();
    expect(() =>
      edlFromJson(
        '{"censorIntervalsMs":[],"faceTracks":[{"startMs":0,"endMs":1,"keyframes":[[0,0,0,1,1]],"masks":[[0,0,1,1,2,2,""]]}]}',
      ),
    ).toThrow();
  });

  // --- whole-frame mode ---

  const track = (s: number, e: number) => faceTrackEdl(s, e, [[s, r(0.1, 0.1, 0.2, 0.2)]]);

  it('mergeRanges passes through trivial input', () => {
    expect(mergeRanges([])).toEqual([]);
    expect(mergeRanges([[5, 9]])).toEqual([[5, 9]]);
  });

  it('mergeRanges sorts and merges overlapping', () => {
    expect(mergeRanges([[9000, 9500], [1000, 2500], [0, 1500]], 0)).toEqual([[0, 2500], [9000, 9500]]);
  });

  it('mergeRanges bridges gaps up to BRIDGE_MS', () => {
    expect(mergeRanges([[0, 1000], [1000 + BRIDGE_MS + 1, 2000]])).toEqual([[0, 1000], [1000 + BRIDGE_MS + 1, 2000]]);
    expect(mergeRanges([[0, 1000], [1000 + BRIDGE_MS, 2000]])).toEqual([[0, 2000]]);
  });

  it('mergeRanges keeps outer end when next is contained', () => {
    expect(mergeRanges([[0, 5000], [1000, 2000]], 0)).toEqual([[0, 5000]]);
  });

  it('promoted track blanks the whole frame and suppresses regions', () => {
    const tracks = [track(1000, 2000)];
    const edl = edlOf(promoteFacesToFullFrame([], tracks), tracks);
    expect(fullFrameAt(edl, 1500)).toBe(true);
    expect(regionsAt(edl, 1500)).toEqual([]); // precedence: no rects under full frame
    expect(fullFrameAt(edl, 2500)).toBe(false);
  });

  it('without promotion the rect still censors', () => {
    const edl = edlOf([], [track(1000, 2000)]);
    expect(fullFrameAt(edl, 1500)).toBe(false);
    expect(regionsAt(edl, 1500).length).toBe(1);
  });

  it('promotion merges gate intervals with face spans', () => {
    expect(promoteFacesToFullFrame([[0, 1200]], [track(1100, 2000), track(2100, 3000)])).toEqual([[0, 3000]]);
  });

  it('isolated blip is not promoted and keeps its rect', () => {
    const tracks = [track(5000, 5100)];
    const edl = edlOf(promoteFacesToFullFrame([], tracks), tracks);
    expect(fullFrameAt(edl, 5050)).toBe(false);
    expect(regionsAt(edl, 5050).length).toBe(1); // still censored — by its own rect
  });

  it('blips that merge past the floor are kept', () => {
    expect(promoteFacesToFullFrame([], [track(5000, 5100), track(5400, 5500)])).toEqual([[5000, 5500]]);
  });

  it('measured S23 spans lose only the blips', () => {
    const measured: Span[] = [
      [308558, 308658], [311161, 311761], [485969, 486069],
      [581564, 581664], [585368, 585668], [587170, 587871], [619319, 638903],
    ];
    const kept = mergeRanges(measured).filter(([a, b]) => b - a >= MIN_FULL_MS);
    expect(kept).toEqual([[311161, 311761], [587170, 587871], [619319, 638903]]);
  });
});
