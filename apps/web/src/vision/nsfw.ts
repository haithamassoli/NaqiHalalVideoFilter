// The GantMan NSFW model (nsfw_mnv2_140_int8.onnx, input [1,3,224,224], output [1,5] softmax in-graph, in
// core's NSFW_CLASSES order), feeding core's NsfwGate. Preprocessing ported from Windows' NsfwClassifier
// (apps/windows/.../vision/Vision.kt), which is Android's FrameSampler.convertToTensor.

import { NSFW_CLASSES } from '../core/nsfwGate';
import { openModel, runModel, type ort } from '../ort';
import type { Rgba } from './preprocess';

export const SIDE = 224;

/**
 * Android's gate input is ONE nearest-neighbour STRETCH of the source pixels to 224², upright, RGB / 255, no
 * mean/std. FramePainter.draw(sample, 224, 224, false) makes that picture (the canvas samples the source
 * frame nearest-neighbour, rotation applied); this packs it NCHW. Never feed it the downscaled analysis
 * frame: Android measured that variant as under-censoring, and the strictness table was tuned on these.
 */
export function fillInput(frame: Rgba, out: Float32Array): void {
  if (frame.width !== SIDE || frame.height !== SIDE) throw new Error(`The NSFW input must be ${SIDE}x${SIDE}.`);
  const plane = SIDE * SIDE;
  const px = frame.data;
  for (let i = 0; i < plane; i++) {
    out[i] = px[4 * i] / 255;
    out[plane + i] = px[4 * i + 1] / 255;
    out[2 * plane + i] = px[4 * i + 2] / 255;
  }
}

/**
 * The model's output as probabilities for NsfwGate.fires. Anything but five finite values in [0, 1] throws:
 * NaN would never fire the gate, which reads as "clean".
 */
export function probabilities(out: ArrayLike<number>): Float32Array {
  if (out.length !== NSFW_CLASSES.length) throw new Error(`The NSFW model returned ${out.length} classes, not ${NSFW_CLASSES.length}.`);
  const p = Float32Array.from(out);
  // The output is dequantized from int8, so allow a rounding hair past 1.
  if (!p.every((v) => v >= 0 && v <= 1.001)) throw new Error('The NSFW model returned values that are not probabilities.');
  return p;
}

export class NsfwClassifier {
  private readonly input = new Float32Array(3 * SIDE * SIDE);

  private constructor(private readonly session: ort.InferenceSession) {}

  static async load(): Promise<NsfwClassifier> {
    return new NsfwClassifier(await openModel('nsfw_mnv2_140_int8.onnx', 'NSFW'));
  }

  /** Probabilities in NSFW_CLASSES order for a 224x224 upright frame. */
  async classify(frame: Rgba): Promise<Float32Array> {
    fillInput(frame, this.input);
    const out = await runModel(this.session, this.input, [1, 3, SIDE, SIDE]);
    return probabilities(Object.values(out)[0]);
  }

  release(): Promise<void> {
    return this.session.release();
  }
}
