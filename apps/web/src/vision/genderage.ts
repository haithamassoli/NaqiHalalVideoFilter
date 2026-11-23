// InsightFace genderage on one face: +1 male, -1 female, 0 abstain. Ported from Windows' GenderVoter
// (apps/windows/.../vision/Vision.kt), which is Android's FaceGenderVoter contract with FilterWorker's
// body: crop, sigmoid of the logit gap, CONF_FLOOR. Used only when censorWho is women or men; the size
// floor (MIN_FACE_PX) and VOTE_CAP are core's, applied by FaceTracks before a vote is asked for.

import type { NRect } from '../core/contracts';
import { openModel, runModel, type ort } from '../ort';
import type { Rgba } from './preprocess';

export const SIDE = 96;

/** Android's FilterWorker.CONF_FLOOR: below this the crop votes for nobody (censor). */
export const CONF_FLOOR = 0.6;

/**
 * Android's `FrameSampler.cropToTensor` on an upright frame: a SQUARE of side `max(boxW, boxH) * 1.5`
 * centred on the box (InsightFace's training crop), nearest-neighbour, edge-clamped, NCHW RGB in 0..255
 * unscaled (genderage has mean 0, std 1, unlike the NSFW gate).
 */
export function cropToTensor(frame: Rgba, rect: NRect, out: Float32Array): void {
  const { width: w, height: h, data } = frame;
  const half = (Math.max((rect.right - rect.left) * w, (rect.bottom - rect.top) * h) * 1.5) / 2;
  const x0 = ((rect.left + rect.right) / 2) * w - half;
  const y0 = ((rect.top + rect.bottom) / 2) * h - half;
  const step = (half * 2) / SIDE;
  const at = (v: number, max: number) => Math.min(max - 1, Math.max(0, Math.trunc(v)));
  const xs = Array.from({ length: SIDE }, (_, i) => at(x0 + i * step, w));
  const ys = Array.from({ length: SIDE }, (_, i) => at(y0 + i * step, h));
  const plane = SIDE * SIDE;
  for (let oy = 0; oy < SIDE; oy++) {
    const row = ys[oy] * w;
    for (let ox = 0; ox < SIDE; ox++) {
      const s = 4 * (row + xs[ox]);
      const i = oy * SIDE + ox;
      out[i] = data[s];
      out[plane + i] = data[s + 1];
      out[2 * plane + i] = data[s + 2];
    }
  }
}

/**
 * Raw logits [female, male, age] -> vote. Softmax over the first two is a sigmoid of their difference.
 * Non-finite logits throw: read as a vote they would land on one side (Kotlin's NaN compares do), which
 * spares a face in one of the two modes.
 */
export function voteFromLogits(out: ArrayLike<number>): number {
  if (out.length < 2 || !Number.isFinite(out[0]) || !Number.isFinite(out[1])) {
    throw new Error('The gender model returned unusable output.');
  }
  const p = 1 / (1 + Math.exp(-(out[1] - out[0])));
  if (Math.max(p, 1 - p) < CONF_FLOOR) return 0;
  return p >= 0.5 ? 1 : -1;
}

export class GenderAge {
  private readonly input = new Float32Array(3 * SIDE * SIDE);

  private constructor(private readonly session: ort.InferenceSession) {}

  static async load(): Promise<GenderAge> {
    return new GenderAge(await openModel('genderage.onnx', 'gender'));
  }

  /**
   * One vote on `rect` (normalized to `frame`). Unlike Windows, which abstains on an inference error, a
   * failure here throws and stops the export: the web app reports every model failure.
   */
  async vote(frame: Rgba, rect: NRect): Promise<number> {
    cropToTensor(frame, rect, this.input);
    const out = await runModel(this.session, this.input, [1, 3, SIDE, SIDE]);
    return voteFromLogits(Object.values(out)[0]);
  }

  release(): Promise<void> {
    return this.session.release();
  }
}
