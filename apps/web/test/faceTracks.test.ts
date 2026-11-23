// Port of apps/windows/app/src/test/kotlin/com/haithamassoli/naqi/desktop/vision/FaceTracksTest.kt, case by
// case. Fixed boxes and a scripted voter in, exact tracks, votes and censor decisions out. No models.
import { describe, expect, it } from 'vitest';
import { nrect, type NRect } from '../src/core/contracts';
import { FaceTracks, SceneCuts } from '../src/core/faceTracks';
import { EVERYONE, MEN, WOMEN } from '../src/core/filterOps';

// A 640x360 upright analysis frame: a box 0.25 wide is 160 px, well over MIN_FACE_PX.
const w = 640;
const h = 360;
const box = (left: number, top: number, size = 0.25) => nrect(left, top, left + size, top + size);
const padded = (r: NRect) => {
  const dw = (r.right - r.left) / 4;
  const dh = (r.bottom - r.top) / 4;
  return nrect(r.left - dw, r.top - dh, r.right + dw, r.bottom + dh);
};
function expectRect(expected: NRect, actual: NRect): void {
  expect(actual.left).toBeCloseTo(expected.left, 5);
  expect(actual.top).toBeCloseTo(expected.top, 5);
  expect(actual.right).toBeCloseTo(expected.right, 5);
  expect(actual.bottom).toBeCloseTo(expected.bottom, 5);
}
const spans = (t: FaceTracks) => t.finish().map((e) => [e.startMs, e.endMs]);

describe('FaceTracks (Windows IoU association)', () => {
  it('everyone censors every track with padded spans and never votes', async () => {
    const t = new FaceTracks(EVERYONE);
    for (let i = 0; i <= 2; i++) {
      const dx = i * 0.01; // a little drift, as between 10 fps samples
      await t.onFrame([box(0.1 + dx, 0.1), box(0.6 + dx, 0.2)], w, h, i * 100, null);
    }
    const edls = t.finish();
    expect(edls.length).toBe(2);
    for (const e of edls) {
      expect(e.startMs).toBe(0); // 0 - 50, clamped
      expect(e.endMs).toBe(250); // 200 + 50
      expect(e.keyframes.map((k) => k[0])).toEqual([0, 100, 200]);
    }
    const first = edls.find((e) => e.keyframes[0][1].left < 0.5)!.keyframes[0][1];
    expectRect(nrect(0.0375, 0.0375, 0.4125, 0.4125), first);
  });

  it('women and men spare only a strict majority of the other gender', async () => {
    // Four people, one per column. A votes female, B male, C one each (tie), D always abstains.
    async function run(who: string): Promise<number[]> {
      const t = new FaceTracks(who);
      let cCalls = 0;
      const vote = (r: NRect) =>
        r.left < 0.2 ? -1 : r.left < 0.45 ? 1 : r.left < 0.7 ? (cCalls++ % 2 === 0 ? 1 : -1) : 0;
      for (let i = 0; i <= 1; i++) {
        // Each face grows, so the second frame is "bigger than anything classified" and votes too.
        const s = 0.2 + i * 0.02;
        await t.onFrame([box(0, 0.1, s), box(0.25, 0.1, s), box(0.5, 0.1, s), box(0.75, 0.1, s)], w, h, i * 100, vote);
      }
      return t
        .finish()
        .map((e) => e.keyframes[0][1].left)
        .sort((a, b) => a - b);
    }
    // Padded lefts: 0.0 -> 0 (clamped), 0.25 -> 0.20, 0.5 -> 0.45, 0.75 -> 0.70.
    expect((await run(WOMEN)).map((x) => Math.round(x * 100))).toEqual([0, 45, 70]); // B (male) spared
    expect((await run(MEN)).map((x) => Math.round(x * 100))).toEqual([20, 45, 70]); // A (female) spared
  });

  it('votes are capped and spent only on bigger crops over the size floor', async () => {
    const t = new FaceTracks(WOMEN);
    const votedPx: number[] = [];
    // Max side in px: 64, 72 (under MIN_FACE_PX 80), then 80, 80 (not bigger), 96, 112, 104 (smaller),
    // 128, 144 (the fifth vote: VOTE_CAP), then 160 and 176 unclassified.
    const sides = [64, 72, 80, 80, 96, 112, 104, 128, 144, 160, 176];
    for (const [i, px] of sides.entries()) {
      await t.onFrame([nrect(0, 0, px / 1024, px / 2048)], 1024, 576, i * 100, () => {
        votedPx.push(px);
        return 0;
      });
    }
    expect(votedPx).toEqual([80, 96, 112, 128, 144]);
    expect(t.finish().length).toBe(1); // five abstentions: censored
  });

  it('a face that jumps or returns after the gap starts a new track', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.1, 0.1)], w, h, 0, null);
    await t.onFrame([box(0.1, 0.1)], w, h, 100, null);
    // IoU of 0.25-wide boxes 0.2 apart is 0.11 < MATCH_IOU: a different face.
    await t.onFrame([box(0.3, 0.1)], w, h, 200, null);
    // Same place as the first, but 700 ms after it was last seen: over MATCH_GAP_MS.
    await t.onFrame([box(0.1, 0.1)], w, h, 800, null);
    expect(spans(t)).toEqual([[0, 150], [150, 250], [750, 850]]);
  });

  it('matching is one to one', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.1, 0.1)], w, h, 0, null);
    // Two boxes both overlap the one track: the closer one continues it, the other starts its own.
    await t.onFrame([box(0.14, 0.1), box(0.11, 0.1)], w, h, 100, null);
    const edls = t.finish();
    expect(edls.length).toBe(2);
    const continued = edls.filter((e) => e.keyframes.length === 2);
    expect(continued.length).toBe(1);
    expectRect(padded(box(0.11, 0.1)), continued[0].keyframes[1][1]);
  });

  it('a different person in the same place later is not pooled into the first track', async () => {
    const t = new FaceTracks(WOMEN);
    await t.onFrame([box(0.4, 0.2)], w, h, 0, () => -1);
    await t.onFrame([box(0.4, 0.2, 0.26)], w, h, 1_000, () => 1);
    await t.onFrame([box(0.4, 0.2, 0.27)], w, h, 1_100, () => 1);
    expect(spans(t)).toEqual([[0, 50]]); // the woman; the man is spared
  });

  it('a smaller overlapping face that votes the other way is not spared on the first track\'s vote', async () => {
    async function run(who: string, first: number): Promise<number> {
      const t = new FaceTracks(who);
      await t.onFrame([box(0.4, 0.2, 0.25)], w, h, 0, () => first);
      await t.onFrame([box(0.41, 0.21, 0.24)], w, h, 100, () => -first);
      return t.finish().length;
    }
    expect(await run(WOMEN, 1)).toBe(1);
    expect(await run(MEN, -1)).toBe(1);
  });

  it('a spare stands while the matched faces keep agreeing', async () => {
    const t = new FaceTracks(WOMEN);
    for (let i = 0; i <= 9; i++) {
      await t.onFrame([box(0.4 + (i % 2) * 0.01, 0.2, 0.25 - (i % 2) * 0.01)], w, h, i * 100, () => 1);
    }
    expect(t.finish().length).toBe(0);
  });

  it('a spare is not inherited by a matched face that abstains or is too small to read', async () => {
    async function run(who: string, first: number, second: NRect, secondVote: number): Promise<number> {
      const t = new FaceTracks(who);
      await t.onFrame([box(0.4, 0.2, 0.25)], w, h, 0, () => first);
      await t.onFrame([second], w, h, 100, () => secondVote);
      return t.finish().length;
    }
    const readable = box(0.41, 0.21, 0.24);
    expect(await run(WOMEN, 1, readable, 0)).toBe(1);
    expect(await run(MEN, -1, readable, 0)).toBe(1);
    async function runSmall(who: string, first: number): Promise<number> {
      const t = new FaceTracks(who);
      await t.onFrame([box(0.4, 0.2, 0.25)], w, h, 0, () => first);
      await t.onFrame([readable], 256, 144, 100, () => first); // never asked: too small to read
      return t.finish().length;
    }
    expect(await runSmall(WOMEN, 1)).toBe(1);
    expect(await runSmall(MEN, -1)).toBe(1);
  });

  it('a cut between two detections leaves no frame uncovered', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.4, 0.2)], w, h, 0, null);
    t.cut(100);
    await t.onFrame([], w, h, 100, null);
    await t.onFrame([box(0.4, 0.2)], w, h, 200, null);
    const s = spans(t);
    expect(s).toEqual([[0, 100], [50, 250]]);
    for (let ms = 0; ms <= 250; ms++) expect(s.some(([a, b]) => ms >= a && ms <= b)).toBe(true);
  });

  it('a cut does not stretch a face long gone or pull in one that comes much later', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.4, 0.2)], w, h, 0, null);
    t.cut(1_000);
    await t.onFrame([box(0.4, 0.2)], w, h, 2_000, null);
    expect(spans(t)).toEqual([[0, 50], [1_950, 2_050]]);
  });

  it('a face that changes size too much starts a new track', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.3, 0.3, 0.2)], w, h, 0, null);
    // Concentric and 1.6x the side: IoU 0.39 clears MATCH_IOU, but no face grows that much in 100 ms.
    await t.onFrame([box(0.24, 0.24, 0.32)], w, h, 100, null);
    expect(t.finish().length).toBe(2);
  });

  it('a scene cut ends every track so the next shot is judged on its own', async () => {
    const t = new FaceTracks(WOMEN);
    await t.onFrame([box(0.4, 0.2)], w, h, 0, () => 1);
    await t.onFrame([box(0.4, 0.2)], w, h, 100, () => 1);
    t.cut(200);
    await t.onFrame([box(0.4, 0.2)], w, h, 200, () => -1);
    expect(spans(t)).toEqual([[150, 250]]); // her span only; he is spared
  });

  it('scene cuts fire on a change of picture and not on a still one', () => {
    const solid = (r: number, g: number, b: number) =>
      Uint8Array.from({ length: 64 * 36 * 3 }, (_, i) => [r, g, b][i % 3]);
    const cuts = new SceneCuts();
    expect(cuts.isCut(solid(40, 40, 40), 64, 36)).toBe(false); // the first frame is never a cut
    expect(cuts.isCut(solid(50, 45, 40), 64, 36)).toBe(false); // a small exposure change: mean diff 0.02
    expect(cuts.isCut(solid(200, 190, 180), 64, 36)).toBe(true); // a new picture: mean diff ~0.56
    expect(cuts.isCut(solid(200, 190, 180), 64, 36)).toBe(false);
  });

  // Web-only: canvas frames are RGBA; alpha is skipped.
  it('scene cuts read RGBA frames', () => {
    const rgba = (v: number) => Uint8Array.from({ length: 64 * 36 * 4 }, (_, i) => (i % 4 === 3 ? 255 : v));
    const cuts = new SceneCuts();
    expect(cuts.isCut(rgba(40), 64, 36, 4)).toBe(false);
    expect(cuts.isCut(rgba(45), 64, 36, 4)).toBe(false);
    expect(cuts.isCut(rgba(200), 64, 36, 4)).toBe(true);
  });

  it('stale tracks are emitted and the result is sorted by start', async () => {
    const t = new FaceTracks(EVERYONE);
    await t.onFrame([box(0.6, 0.1)], w, h, 0, null);
    await t.onFrame([box(0.1, 0.1)], w, h, 2_000, null); // evicts the first (2 s unseen)
    await t.onFrame([box(0.1, 0.1)], w, h, 2_100, null);
    expect(t.finish().map((e) => e.startMs)).toEqual([0, 1_950]);
  });
});
