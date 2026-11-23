// Port of core/src/main/kotlin/com/haithamassoli/naqi/analysis/FaceTrackerLogic.kt: the vote policy
// and span building shared by the face tracker and BodyTracker. Constants are Android's, unchanged.

import { nrect, rectHeight, rectWidth, type FaceTrack, type NRect } from './contracts';
import { faceTrackEdl, type FaceTrackEdl } from './edl';
import { EVERYONE, MEN, NONE, WOMEN } from './filterOps';

/** Classifications per track, then stop: cost is per track, not per frame. */
export const VOTE_CAP = 5;

/**
 * Below this max side in upright pixels a crop is not classified at all. Measured on device: the 40–80 px
 * band is ~20 points less accurate than every band above it. Not classified ⇒ no vote ⇒ censored.
 */
export const MIN_FACE_PX = 80;

/** Source-time gap after which an unseen track is over (20 samples at 10 fps). */
export const EVICT_AFTER_MS = 2_000;

/** Half the 100 ms (10 fps) sample gap, so between-sample frames stay covered at a span's edges. */
export const SPAN_PAD_MS = 50;

/** 25% per side ⇒ 1.5x each dimension. */
const KEYFRAME_PAD = 0.25;

const clamp01 = (v: number): number => Math.min(1, Math.max(0, v));

/** The span for one finished track, or null if it is not censored under `who` (or has no samples). */
export function edlFor(track: FaceTrack, who: string = EVERYONE): FaceTrackEdl | null {
  const s = track.samples;
  if (s.length === 0) return null;
  if (!shouldCensor(track.femaleVotes, track.maleVotes, who)) return null;
  return faceTrackEdl(
    Math.max(0, s[0].ptsMs - SPAN_PAD_MS),
    s[s.length - 1].ptsMs + SPAN_PAD_MS,
    s.map((x) => [x.ptsMs, padRect(x.rect)] as [number, NRect]),
  );
}

/**
 * The censor verdict for one finished track: "censor unless the OTHER gender strictly won", so 0/0
 * censors (no vote cast) and a tie censors. Unknown `who` values censor.
 */
export function shouldCensor(femaleVotes: number, maleVotes: number, who: string): boolean {
  switch (who) {
    case NONE: return false;
    case WOMEN: return !(maleVotes > femaleVotes);
    case MEN: return !(femaleVotes > maleVotes);
    default: return true; // EVERYONE, and any unknown value: cover it
  }
}

/** True once `nowMs` is EVICT_AFTER_MS past the last frame a track appeared in. */
export const isStale = (lastSeenMs: number, nowMs: number): boolean => nowMs - lastSeenMs >= EVICT_AFTER_MS;

/** Pad a sample rect to 1.5x each dimension (25% per side) and clamp to [0,1]. */
export function padRect(r: NRect): NRect {
  const dx = rectWidth(r) * KEYFRAME_PAD;
  const dy = rectHeight(r) * KEYFRAME_PAD;
  return nrect(clamp01(r.left - dx), clamp01(r.top - dy), clamp01(r.right + dx), clamp01(r.bottom + dy));
}
