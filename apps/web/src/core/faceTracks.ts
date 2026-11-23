// Port of apps/windows/app/src/main/kotlin/com/haithamassoli/naqi/desktop/vision/FaceTracks.kt
// (FaceTracks + SceneCuts). YuNet has no tracking id, so faces are associated by IoU, as on Windows.
//
// Everything after "which track is this face" is Android's, rule for rule: the raw upright rect is the
// sample, the vote guards run cheapest-first (cap, size floor, bigger than anything already classified),
// eviction is core's isStale, and each verdict is read once, at eviction, through edlFor.
// Two Windows rules, because IoU is weaker evidence of identity than a tracking id and a wrong merge fails
// OPEN: a shot cut ends every live track (bridging the cut so no frame goes bare), and a spared track needs
// each face matched to it while spared to read and agree, or the whole track is covered.

import { FaceTrack, rectHeight, rectWidth, type NRect } from './contracts';
import { overlap } from './bodyTracker';
import type { FaceTrackEdl } from './edl';
import { MIN_FACE_PX, SPAN_PAD_MS, VOTE_CAP, edlFor, isStale } from './faceTrackerLogic';
import { EVERYONE, MEN, WOMEN } from './filterOps';

/** The usual tracking-by-detection bar; at 10 fps a moving face still overlaps itself well past it. */
export const MATCH_IOU = 0.3;
/** Bridges up to four missed samples at 10 fps (a blink, a detector miss), not a cut-away. */
export const MATCH_GAP_MS = 500;
/** Largest change of either side between matched boxes. */
export const MAX_SIDE_RATIO = 1.5;
/** Core's SPAN_PAD_MS: half the 10 fps sample gap, the lead-in every span gets. */
export const CUT_LEAD_IN_MS = SPAN_PAD_MS;

const ratio = (x: number, y: number): number => (x <= 0 || y <= 0 ? Infinity : Math.max(x / y, y / x));
const similarSize = (a: NRect, b: NRect): boolean =>
  ratio(rectWidth(a), rectWidth(b)) <= MAX_SIDE_RATIO && ratio(rectHeight(a), rectHeight(b)) <= MAX_SIDE_RATIO;
const last = <T>(xs: T[]): T => xs[xs.length - 1];

export class FaceTracks {
  private live: FaceTrack[] = [];
  private emitted: FaceTrackEdl[] = [];
  private nextId = 0;
  /** Spared tracks a matched face did not vote for (against, abstained, too small): censored whatever the majority says. */
  private contested = new Set<FaceTrack>();
  /** The last cut, and the tracks the shot after it started within MATCH_GAP_MS, keyed to it. */
  private lastCutMs = -Infinity;
  private afterCut = new Map<FaceTrack, number>();
  /** The vote that contradicts a spare: female in Women mode, male in Men mode. */
  private readonly against: number;

  constructor(private readonly who: string) {
    this.against = who === WOMEN ? -1 : who === MEN ? 1 : 0;
  }

  /**
   * `faces` are raw boxes normalized to the `uprightW` x `uprightH` analysis frame. `vote` classifies one
   * of them (+1 male, -1 female, 0 abstain), or is null when no vote is wanted.
   */
  onFrame(faces: NRect[], uprightW: number, uprightH: number, ptsMs: number, vote: ((face: NRect) => number) | null): void {
    const assigned = this.associate(faces, ptsMs);
    faces.forEach((rect, i) => {
      let track = assigned[i];
      if (!track) {
        track = new FaceTrack(this.nextId++);
        this.live.push(track);
        if (ptsMs - this.lastCutMs <= MATCH_GAP_MS) this.afterCut.set(track, this.lastCutMs);
      }
      track.samples.push({ ptsMs, rect, mask: null });
      if (!vote || this.contested.has(track)) return; // contested is already censored: nothing to learn
      // CHECK: a spared track keeps its spare only for faces whose own readable vote agrees with it.
      const spared = this.spared(track);
      const px = Math.trunc(Math.max(rectWidth(rect) * uprightW, rectHeight(rect) * uprightH)); // unpadded, upright px
      if (px < MIN_FACE_PX) {
        // too small to read: no vote, which already means censor
        if (spared) this.contested.add(track);
        return;
      }
      // Android's guards: at most VOTE_CAP votes, spent on the biggest crops.
      const counted = track.votesTried < VOTE_CAP && px > track.classifiedPx;
      if (!counted && !spared) return;
      const v = vote(rect);
      if (counted) {
        track.votesTried++;
        track.classifiedPx = px;
        if (v === 1) track.maleVotes++;
        else if (v === -1) track.femaleVotes++;
      }
      if (spared && v !== -this.against) this.contested.add(track);
    });
    this.live = this.live.filter((track) => {
      if (isStale(last(track.samples).ptsMs, ptsMs)) {
        this.emit(track);
        return false;
      }
      return true;
    });
  }

  /**
   * A shot cut at `atMs` (the first frame of the new shot): end every live track now. A track last seen
   * within MATCH_GAP_MS before the cut is covered up to it, and one the new shot starts within
   * MATCH_GAP_MS after it is covered from the cut with the CUT_LEAD_IN_MS lead-in.
   */
  cut(atMs: number): void {
    for (const track of this.live) {
      this.emit(track, atMs - last(track.samples).ptsMs <= MATCH_GAP_MS ? atMs : null);
    }
    this.live = [];
    this.lastCutMs = atMs;
  }

  /** Emit whatever is still live, then every censored span of the pass, by start. */
  finish(): FaceTrackEdl[] {
    for (const t of this.live) this.emit(t);
    this.live = [];
    return [...this.emitted].sort((a, b) => a.startMs - b.startMs);
  }

  private emit(track: FaceTrack, endAtMs: number | null = null): void {
    const from = this.afterCut.get(track);
    this.afterCut.delete(track);
    let edl = edlFor(track, this.contested.has(track) ? EVERYONE : this.who);
    this.contested.delete(track);
    if (!edl) return;
    if (from !== undefined) edl = { ...edl, startMs: Math.min(edl.startMs, Math.max(0, from - CUT_LEAD_IN_MS)) };
    if (endAtMs !== null) edl = { ...edl, endMs: Math.max(edl.endMs, endAtMs) };
    this.emitted.push(edl);
  }

  /** What shouldCensor would leave uncovered right now. */
  private spared(t: FaceTrack): boolean {
    if (this.who === WOMEN) return t.maleVotes > t.femaleVotes;
    if (this.who === MEN) return t.femaleVotes > t.maleVotes;
    return false;
  }

  /**
   * One-to-one greedy matching, highest IoU first, of `faces` onto live tracks seen within MATCH_GAP_MS.
   * Unmatched faces start new tracks. Erring toward a split is deliberate: a split costs nothing, a wrong
   * merge can spare someone who should be covered.
   */
  private associate(faces: NRect[], ptsMs: number): (FaceTrack | null)[] {
    const pairs: [number, FaceTrack, number][] = [];
    for (const track of this.live) {
      const prev = last(track.samples);
      if (ptsMs - prev.ptsMs > MATCH_GAP_MS) continue;
      faces.forEach((rect, i) => {
        const iou = overlap(prev.rect, rect);
        if (iou >= MATCH_IOU && similarSize(prev.rect, rect)) pairs.push([iou, track, i]);
      });
    }
    pairs.sort((a, b) => b[0] - a[0]); // stable, like Kotlin's sortByDescending
    const assigned: (FaceTrack | null)[] = faces.map(() => null);
    const taken = new Set<FaceTrack>();
    for (const [, track, i] of pairs) {
      if (assigned[i] === null && !taken.has(track)) {
        assigned[i] = track;
        taken.add(track);
      }
    }
    return assigned;
  }
}

/**
 * Android's coarse shot-cut test: a 16x16 grid of mean-of-RGB samples, /255, of each upright analysis
 * frame, and a cut when the mean absolute change from the previous frame's grid is over THRESHOLD.
 * Gradual transitions can slip past it, which is why FaceTracks also re-checks spares.
 * `channels` is 3 for packed RGB (Windows' RgbFrame) or 4 for canvas RGBA; alpha is ignored.
 */
export class SceneCuts {
  static readonly GRID = 16;
  static readonly THRESHOLD = 0.2;
  private previous: Float32Array | null = null;

  isCut(pixels: ArrayLike<number>, width: number, height: number, channels = 3): boolean {
    const G = SceneCuts.GRID;
    const signature = new Float32Array(G * G);
    for (let y = 0; y < G; y++) {
      for (let x = 0; x < G; x++) {
        const s = channels * (Math.trunc((y * height) / G) * width + Math.trunc((x * width) / G));
        signature[y * G + x] = (pixels[s] + pixels[s + 1] + pixels[s + 2]) / 3 / 255;
      }
    }
    const old = this.previous;
    let cut = false;
    if (old) {
      let sum = 0;
      for (let i = 0; i < signature.length; i++) sum += Math.abs(signature[i] - old[i]);
      cut = sum / signature.length > SceneCuts.THRESHOLD;
    }
    this.previous = signature;
    return cut;
  }
}
