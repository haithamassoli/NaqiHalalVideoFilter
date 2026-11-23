// Port of core/src/main/kotlin/com/haithamassoli/naqi/analysis/Contracts.kt.
//
// Rects are plain objects (not classes) so they survive postMessage/structuredClone unchanged.
// All rectangles are normalized to [0,1] in UPRIGHT (display-oriented) frame space.

import type { BodyMask } from './edl';

export interface NRect {
  readonly left: number;
  readonly top: number;
  readonly right: number;
  readonly bottom: number;
}

export const nrect = (left: number, top: number, right: number, bottom: number): NRect => ({ left, top, right, bottom });
export const rectWidth = (r: NRect): number => r.right - r.left;
export const rectHeight = (r: NRect): number => r.bottom - r.top;

/**
 * Map an upright-space rect into STORED-frame space for a video whose stored frames must be rotated
 * `rotationDegrees` clockwise for display. Identity for unrotated video.
 */
export function toStoredSpace(r: NRect, rotationDegrees: number): NRect {
  switch ((((rotationDegrees % 360) + 360) % 360)) {
    case 90: return nrect(r.top, 1 - r.right, r.bottom, 1 - r.left);
    case 180: return nrect(1 - r.right, 1 - r.bottom, 1 - r.left, 1 - r.top);
    case 270: return nrect(1 - r.bottom, r.left, 1 - r.top, r.right);
    default: return r;
  }
}

/** Source video properties (probe result). `width`/`height` are as stored, pre-rotation. */
export interface VideoMeta {
  width: number;
  height: number;
  rotationDegrees: number;
  durationMs: number;
  fps: number;
}

/** One face observation on one sampled frame; body mode adds the person's silhouette in precise mode. */
export interface FaceSample {
  ptsMs: number;
  rect: NRect;
  mask: BodyMask | null;
}

/**
 * A face (or person) tracked across sampled frames. The verdict is four ints, never crops: each crop is
 * classified and dropped; only the tallies survive.
 */
export class FaceTrack {
  readonly samples: FaceSample[] = [];
  femaleVotes = 0;
  maleVotes = 0;
  /** Largest crop (max side, upright px) already classified in this track; 0 = none yet. */
  classifiedPx = 0;
  /** Classifications RUN, abstentions included: the VOTE_CAP counter. */
  votesTried = 0;
  /** Body mode spaces its bounded face observations in source time. */
  lastVoteMs = Number.MIN_SAFE_INTEGER;
  /** Body mode: first covered ms, which reaches back before the first sample. */
  startMs = 0;

  constructor(readonly id: number) {}
}
