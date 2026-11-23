// Port of core/src/test/kotlin/com/haithamassoli/naqi/analysis/BodyTrackerTest.kt, case by case.
import { describe, expect, it } from 'vitest';
import { BodyTracker } from '../src/core/bodyTracker';
import { nrect } from '../src/core/contracts';
import { mergeRanges, type BodyMask } from '../src/core/edl';
import { EVERYONE, WOMEN } from '../src/core/filterOps';

const body = nrect(0.1, 0.1, 0.9, 1);
const face = nrect(0.3, 0.15, 0.6, 0.35);

describe('BodyTracker', () => {
  it('face disappears but person decision survives', () => {
    const tracker = new BodyTracker(WOMEN);
    tracker.onFrame([body], [face], 640, 640, 0, { vote: () => -1 });
    tracker.onFrame([body], [], 640, 640, 30);
    const output = tracker.finish();
    expect(output.length).toBe(1);
    expect(output[0].endMs).toBeGreaterThanOrEqual(30);
    const kfs = output[output.length - 1].keyframes;
    expect(kfs[kfs.length - 1][1].bottom).toBe(1);
  });

  it('cut and ambiguity never transfer a spare decision', () => {
    const tracker = new BodyTracker(WOMEN);
    tracker.onFrame([body], [face], 640, 640, 0, { vote: () => 1 });
    tracker.onFrame([body], [face], 640, 640, 100, { vote: () => 1 });
    tracker.onFrame([body], [], 640, 640, 130);
    expect(tracker.finish()).toEqual([]); // classified male remains spared without a face

    const cut = new BodyTracker(WOMEN);
    cut.onFrame([body], [face], 640, 640, 0, { vote: () => 1 });
    cut.onFrame([body], [face], 640, 640, 100, { vote: () => 1 });
    cut.onFrame([body], [], 640, 640, 130, { sceneCut: true });
    expect(cut.finish().length).toBe(1); // same coordinates, new unknown person, covered

    const crossing = new BodyTracker(WOMEN);
    crossing.onFrame([body], [face], 640, 640, 0, { vote: () => 1 });
    crossing.onFrame([body], [face], 640, 640, 100, { vote: () => 1 });
    crossing.onFrame([body, body], [], 640, 640, 130);
    expect(crossing.finish().length).toBe(2); // ambiguous continuation does not inherit "spare"
  });

  it('missing body falls back and unknown people are covered', () => {
    const tracker = new BodyTracker(WOMEN);
    tracker.onFrame([], [face], 640, 640, 100);
    expect(tracker.fallbackIntervals).toEqual([[100, 1000]]);
    tracker.onFrame([body], [], 640, 640, 200);
    expect(tracker.finish().length).toBe(1);
  });

  it('orphan faces stay contiguous at 10 fps', () => {
    const tracker = new BodyTracker(WOMEN);
    tracker.onFrame([], [], 640, 640, 0);
    for (const pts of [100, 200, 301]) tracker.onFrame([], [face], 640, 640, pts);
    for (let pts = 401; pts <= 2301; pts += 100) tracker.onFrame([], [], 640, 640, pts);
    tracker.onFrame([], [face], 640, 640, 2401);
    const merged = mergeRanges(tracker.fallbackIntervals, 0);
    // 900 ms past the last sighting, from the shot start this early in a shot, else from just after the
    // previous sample.
    expect(merged).toEqual([[0, 1201], [2302, 3301]]);
  });

  it('tracks after a cut reach back to the previous sample', () => {
    const tracker = new BodyTracker(EVERYONE);
    const other = nrect(0.5, 0.2, 0.9, 0.9);
    tracker.onFrame([body], [], 640, 640, 0);
    tracker.onFrame([body], [], 640, 640, 100);
    tracker.onFrame([other], [], 640, 640, 200, { sceneCut: true });
    const [old, fresh] = tracker.finish();
    expect(old.startMs).toBe(0);
    expect(old.endMs).toBe(199);
    expect(fresh.startMs).toBe(101);
    expect(Math.abs(fresh.keyframes[0][1].right - other.right)).toBeLessThanOrEqual(1);
    expect(fresh.keyframes.length).toBe(1);
    expect(old.union && fresh.union).toBe(true);
  });

  it('people found early in a shot are covered from the cut', () => {
    const tracker = new BodyTracker(EVERYONE);
    const small = nrect(0.45, 0.58, 0.58, 0.7);
    for (let pts = 0; pts <= 1500; pts += 100) {
      const people = pts === 900 || pts === 1000 ? [small] : pts === 1400 || pts === 1500 ? [body] : [];
      tracker.onFrame(people, [], 640, 640, pts, { sceneCut: pts === 200 });
    }
    const [early, late] = tracker.finish();
    expect(early.startMs).toBe(101); // first seen 799 ms after the cut
    expect(late.startMs).toBe(1100); // first seen 1299 ms after it: the usual 300 ms lead-in
  });

  it('precise masks follow their samples into the EDL', () => {
    const mask: BodyMask = { rect: body, width: 1, height: 1, bits: new Uint8Array([1]) };
    const tracker = new BodyTracker(EVERYONE);
    tracker.onFrame([body], [], 640, 640, 0, { masks: [mask] });
    tracker.onFrame([body], [], 640, 640, 100, { masks: [null] });
    const out = tracker.finish();
    expect(out.length).toBe(1);
    expect(out[0].masks).toEqual([mask, null]);
    const boxes = new BodyTracker(EVERYONE);
    boxes.onFrame([body], [], 640, 640, 0);
    const b = boxes.finish();
    expect(b.length).toBe(1);
    expect(b[0].masks).toBeNull();
  });

  it('ambiguous boxes do not accumulate ghosts across frames', () => {
    const tracker = new BodyTracker(EVERYONE);
    for (let i = 0; i <= 20; i++) tracker.onFrame([body, body], [], 640, 640, i * 30);
    const spans = tracker.finish();
    for (let i = 0; i <= 20; i++) {
      expect(spans.filter((s) => i * 30 >= s.startMs && i * 30 <= s.endMs).length).toBeLessThanOrEqual(2);
    }
  });

  it('one wrong face vote cannot expose an entire body', () => {
    const tracker = new BodyTracker(WOMEN);
    tracker.onFrame([body], [face], 640, 640, 0, { vote: () => 1 });
    tracker.onFrame([body], [], 640, 640, 100);
    expect(tracker.finish().length).toBe(1);
  });

  it('person rect includes a margin for hands outside the detected box', () => {
    const tracker = new BodyTracker(EVERYONE);
    const narrow = nrect(0.2, 0.1, 0.7, 0.9);
    tracker.onFrame([narrow], [], 640, 640, 0);
    const out = tracker.finish();
    expect(out.length).toBe(1);
    expect(out[0].keyframes.length).toBe(1);
    const padded = out[0].keyframes[0][1];
    expect(padded.right).toBeGreaterThanOrEqual(0.82);
    expect(padded.top).toBe(0);
    expect(padded.bottom).toBe(1);
  });
});
