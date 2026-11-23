// Port of apps/windows/app/src/main/kotlin/com/haithamassoli/naqi/desktop/vision/YuNet.kt: OpenCV's YuNet
// face detector (opencv_zoo face_detection_yunet_2023mar.onnx, MIT). Input fixed at [1,3,640,640], BGR in
// 0..255, no normalization; the frame sits at the top-left of a zero canvas, so boxes come back in frame
// pixels with no letterbox offset. Same decode, score threshold and NMS as Windows.

import { overlap } from '../core/bodyTracker';
import { nrect, type NRect } from '../core/contracts';
import { openModel, runModel, type ort } from '../ort';
import type { Rgba } from './preprocess';

export const SIDE = 640;

/**
 * OpenCV's own default is 0.9. Lower, because a miss leaves a face visible while a false box only blurs a
 * patch of background: the app's safe direction is covering.
 */
export const SCORE_THRESHOLD = 0.6;

/** OpenCV's default NMS IoU. */
export const NMS_IOU = 0.3;

const STRIDES = [8, 16, 32] as const;
export const OUTPUTS = STRIDES.flatMap((s) => [`cls_${s}`, `obj_${s}`, `bbox_${s}`]);

export type Scored = [score: number, box: NRect];

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/**
 * OpenCV's `FaceDetectorYN` decode (modules/objdetect/src/face_detect.cpp): per stride, one anchor per grid
 * cell; score = sqrt(cls * obj), both clamped to [0, 1]; centre = (cell + offset) × stride, size =
 * exp(log-size) × stride. Boxes are in 640-canvas pixels. A missing or short output throws: a detector that
 * cannot be read must stop the export, not report "no faces".
 */
export function decode(outputs: Readonly<Record<string, ArrayLike<number>>>): Scored[] {
  const out: Scored[] = [];
  for (const stride of STRIDES) {
    const cols = SIDE / stride;
    const n = cols * cols;
    const cls = outputs[`cls_${stride}`];
    const obj = outputs[`obj_${stride}`];
    const bbox = outputs[`bbox_${stride}`];
    if (!cls || !obj || !bbox || cls.length < n || obj.length < n || bbox.length < 4 * n) {
      throw new Error(`The face detector returned malformed output for stride ${stride}.`);
    }
    for (let i = 0; i < n; i++) {
      const score = Math.sqrt(clamp01(cls[i]) * clamp01(obj[i]));
      // NaN fails `>=` too, but a NaN score means the model broke, not "no face": say so.
      if (Number.isNaN(score)) throw new Error('The face detector returned NaN scores.');
      if (score < SCORE_THRESHOLD) continue;
      const cx = ((i % cols) + bbox[4 * i]) * stride;
      const cy = (Math.trunc(i / cols) + bbox[4 * i + 1]) * stride;
      const w = Math.exp(bbox[4 * i + 2]) * stride;
      const h = Math.exp(bbox[4 * i + 3]) * stride;
      if (!Number.isFinite(cx + cy + w + h)) throw new Error('The face detector returned a non-finite box.');
      out.push([score, nrect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)]);
    }
  }
  return out;
}

/** Greedy NMS, best score first. The IoU is scale-free, so it works on pixel boxes as well. */
export function nms(boxes: readonly Scored[]): Scored[] {
  const kept: Scored[] = [];
  for (const b of [...boxes].sort((x, y) => y[0] - x[0])) {
    if (!kept.some((k) => overlap(k[1], b[1]) > NMS_IOU)) kept.push(b);
  }
  return kept;
}

/**
 * Fills the [1,3,640,640] BGR input with `frame` at the top-left (nearest-neighbour scaled down only if a
 * side is over 640, which analysis frames never are), zeros elsewhere. Returns the drawn size.
 */
export function fillInput(frame: Rgba, out: Float32Array): [number, number] {
  const scale = Math.min(1, SIDE / Math.max(frame.width, frame.height));
  const dw = Math.max(1, Math.trunc(frame.width * scale));
  const dh = Math.max(1, Math.trunc(frame.height * scale));
  const plane = SIDE * SIDE;
  out.fill(0);
  const px = frame.data;
  for (let y = 0; y < dh; y++) {
    const row = Math.trunc((y * frame.height) / dh) * frame.width;
    for (let x = 0; x < dw; x++) {
      const s = 4 * (row + Math.trunc((x * frame.width) / dw));
      const i = y * SIDE + x;
      out[i] = px[s + 2]; // B
      out[plane + i] = px[s + 1]; // G
      out[2 * plane + i] = px[s]; // R
    }
  }
  return [frame.width * scale, frame.height * scale];
}

export class YuNet {
  private readonly input = new Float32Array(3 * SIDE * SIDE);

  private constructor(private readonly session: ort.InferenceSession) {}

  static async load(): Promise<YuNet> {
    return new YuNet(await openModel('face_detection_yunet_2023mar.onnx', 'face detector'));
  }

  /** Faces as raw (unpadded) boxes normalized to `frame`, best score first. May reach past [0, 1]. */
  async detect(frame: Rgba): Promise<NRect[]> {
    const [fw, fh] = fillInput(frame, this.input);
    const outputs = await runModel(this.session, this.input, [1, 3, SIDE, SIDE], OUTPUTS);
    return nms(decode(outputs)).map(([, b]) => nrect(b.left / fw, b.top / fh, b.right / fw, b.bottom / fh));
  }

  release(): Promise<void> {
    return this.session.release();
  }
}
