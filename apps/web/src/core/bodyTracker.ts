// Port of core/src/main/kotlin/com/haithamassoli/naqi/analysis/BodyTracker.kt. Person-level votes
// survive a disappearing face; uncertain associations start an unknown (censored) track.

import { FaceTrack, rectHeight, rectWidth, type NRect } from './contracts';
import { faceTrackEdl, type BodyMask, type FaceTrackEdl, type Span } from './edl';
import { MIN_FACE_PX, VOTE_CAP, padRect, shouldCensor } from './faceTrackerLogic';
import { MEN, WOMEN } from './filterOps';

/** A missed person keeps their last box this long (nine missed samples at 10 fps). */
const HOLD_MS = 900;
const LEAD_MS = 300;
const SETTLE_MS = 1_000;

const area = (r: NRect) => Math.max(0, rectWidth(r)) * Math.max(0, rectHeight(r));
const intersection = (a: NRect, b: NRect) =>
  Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) *
  Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));

/** IoU (the Kotlin name is `overlap`; Windows' face association uses it too). */
export function overlap(a: NRect, b: NRect): number {
  const common = intersection(a, b);
  return common / Math.max(1e-6, area(a) + area(b) - common);
}

const faceInside = (face: NRect, body: NRect): boolean =>
  area(face) > 0 &&
  intersection(face, body) / area(face) >= 0.8 &&
  (face.top + face.bottom) / 2 < body.top + rectHeight(body) * 0.65;

const last = <T>(xs: T[]): T => xs[xs.length - 1];

export interface BodyFrameOptions {
  sceneCut?: boolean;
  /** +1 male, -1 female, 0 abstain; omit when no vote is wanted. */
  vote?: ((face: NRect) => number | Promise<number>) | null;
  /** Parallels `people` in precise body mode. */
  masks?: (BodyMask | null)[];
}

export class BodyTracker {
  private live: FaceTrack[] = [];
  private emitted: FaceTrackEdl[] = [];
  private nextId = 0;
  private sparedCount = 0;
  /** The previous sample. At 10 fps anything that first shows up now may have been on screen since then. */
  private lastPtsMs: number | null = null;
  private shotStartMs = 0;
  readonly fallbackIntervals: Span[] = [];
  frameCount = 0;
  detectionCount = 0;
  cutCount = 0;

  constructor(private readonly who: string) {}

  /**
   * Async only because the web's ONNX Runtime is: callers await each frame before the next, and a vote
   * that throws rejects the whole frame (the caller stops the export).
   */
  async onFrame(
    people: NRect[],
    faces: NRect[],
    width: number,
    height: number,
    ptsMs: number,
    opts: BodyFrameOptions = {},
  ): Promise<void> {
    const { sceneCut = false, vote = null, masks = [] } = opts;
    this.frameCount++;
    this.detectionCount += people.length;
    // A cut happened somewhere after the previous sample, so the new shot starts just after it and the
    // old shot's tracks keep their last box up to this sample.
    let since: number;
    if (this.lastPtsMs !== null) since = this.lastPtsMs + 1;
    else {
      since = ptsMs;
      this.shotStartMs = ptsMs;
    }
    this.lastPtsMs = ptsMs;
    if (sceneCut) {
      for (const t of this.live) this.emit(t, ptsMs - 1);
      this.live = [];
      this.cutCount++;
      this.shotStartMs = since;
    }
    this.live = this.live.filter((tr) => {
      if (ptsMs - last(tr.samples).ptsMs > HOLD_MS) {
        this.emit(tr);
        return false;
      }
      return true;
    });
    const old = [...this.live];
    const used = new Set<FaceTrack>();
    const scores = people.map((box) => old.map((t) => overlap(box, last(t.samples).rect)));
    for (const [index, box] of people.entries()) {
      // Stable sort, like Kotlin's sortedByDescending.
      const ranked = old.map((_, j) => j).sort((x, y) => scores[index][y] - scores[index][x]);
      const best = ranked.length > 0 ? ranked[0] : null;
      const score = best !== null ? scores[index][best] : 0;
      const runnerUp = ranked.length > 1 ? scores[index][ranked[1]] : 0;
      let competing = 0;
      if (best !== null) {
        let m = -Infinity;
        for (let i = 0; i < scores.length; i++) if (i !== index) m = Math.max(m, scores[i][best]);
        competing = m === -Infinity ? 0 : m;
      }
      // IoU with reciprocal ambiguity guards, no re-identification. Crossings restart evidence and cover unknown people.
      let matched: FaceTrack | null = null;
      if (best !== null && score >= 0.25 && score - runnerUp >= 0.1 && score - competing >= 0.1 && !used.has(old[best])) {
        matched = old[best];
      }
      // A new span reaches back LEAD_MS within its shot, or to the cut early in a shot. A box replacing a
      // nearby track only needs the frames since the previous sample.
      const fresh = old.every((t) => !(overlap(box, last(t.samples).rect) > 0.1));
      let track = matched;
      if (!track) {
        track = new FaceTrack(this.nextId++);
        track.startMs = fresh ? this.leadIn(ptsMs, Math.max(this.shotStartMs, ptsMs - LEAD_MS)) : since;
        this.live.push(track);
      }
      used.add(track);
      track.samples.push({ ptsMs, rect: box, mask: masks[index] ?? null });
      if (!vote || track.votesTried >= VOTE_CAP) continue;
      const associated = faces.filter(
        (face) => faceInside(face, box) && people.filter((p) => faceInside(face, p)).length === 1,
      );
      if (associated.length !== 1) continue;
      const face = associated[0];
      const px = Math.trunc(Math.max(rectWidth(face) * width, rectHeight(face) * height));
      if (px < MIN_FACE_PX || (track.votesTried > 0 && ptsMs - track.lastVoteMs < 100)) continue;
      track.classifiedPx = Math.max(track.classifiedPx, px);
      track.lastVoteMs = ptsMs;
      track.votesTried++;
      const v = await vote(face);
      if (v === 1) track.maleVotes++;
      else if (v === -1) track.femaleVotes++;
    }
    // A replacement/ambiguous box already covers this location; retaining the old box would manufacture
    // competing candidates on the next frame.
    for (const track of old) {
      if (!used.has(track) && people.some((p) => overlap(p, last(track.samples).rect) > 0.1)) {
        this.emit(track, ptsMs - 1);
        this.live.splice(this.live.indexOf(track), 1);
      }
    }
    // A detected face with no reliable body must never disappear from a body-mode result.
    if (faces.some((face) => people.every((p) => !faceInside(face, p)))) {
      this.fallbackIntervals.push([Math.max(0, this.leadIn(ptsMs, since)), ptsMs + HOLD_MS]);
    }
  }

  /** Someone first found within SETTLE_MS of a cut was most likely there from the cut. */
  private leadIn(ptsMs: number, otherwise: number): number {
    return ptsMs - this.shotStartMs <= SETTLE_MS ? Math.min(this.shotStartMs, otherwise) : otherwise;
  }

  finish(): FaceTrackEdl[] {
    for (const t of this.live) this.emit(t);
    this.live = [];
    return [...this.emitted].sort((a, b) => a.startMs - b.startMs);
  }

  retention(): string {
    return `bodyFrames=${this.frameCount} people=${this.detectionCount} tracks=${this.nextId} live=${this.live.length} cuts=${this.cutCount} fallback=${this.fallbackIntervals.length} spared=${this.sparedCount}`;
  }

  private emit(track: FaceTrack, endMs: number = last(track.samples).ptsMs + HOLD_MS): void {
    const spareVotes = this.who === WOMEN ? track.maleVotes : this.who === MEN ? track.femaleVotes : 2;
    // A single misread face must not expose an entire body for the rest of its track.
    if (!shouldCensor(track.femaleVotes, track.maleVotes, this.who) && spareVotes >= 2) {
      this.sparedCount++;
      return;
    }
    this.emitted.push(
      faceTrackEdl(
        Math.max(0, track.startMs),
        Math.max(endMs, last(track.samples).ptsMs),
        track.samples.map((s) => [s.ptsMs, padRect(s.rect)] as [number, NRect]),
        true,
        track.samples.some((s) => s.mask !== null) ? track.samples.map((s) => s.mask) : null,
      ),
    );
  }
}
