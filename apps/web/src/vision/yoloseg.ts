// Port of apps/android/app/src/main/java/com/haithamassoli/naqi/analysis/PersonDetector.kt: YOLO11n-seg
// people (COCO class 0) for the optional body mode, exported by models/export_person.py (FP32, static 640,
// opset 17). The model's weights are AGPL-3.0 (NOTICE); it is fetched only when body censoring is on.
//
// Input `images` [1,3,640,640] RGB in 0..1, the upright analysis frame letterboxed into the centre over
// 114/255 grey, nearest-neighbour. Output `output0` [1,116,8400] per anchor: cx, cy, w, h (letterbox px),
// 80 class scores, 32 mask coefficients; `output1` [1,32,160,160] mask prototypes at a quarter of the
// letterbox. Same thresholds, class filter, NMS and mask sampling as Android (and iOS' PersonDetector).

import { overlap } from '../core/bodyTracker';
import { nrect, rectHeight, rectWidth, type NRect } from '../core/contracts';
import type { BodyMask } from '../core/edl';
import { openModel, runModel, type ort } from '../ort';
import type { Rgba } from './preprocess';

export const SIDE = 640;
export const ANCHORS = 8400;
const CLASSES = 80;
const COEFFS = 32;
const PROTO_SIDE = 160;
/** Same thresholds as iOS' PersonDetector.swift and Android's PersonDetector.kt. */
export const CONFIDENCE = 0.25;
export const NMS_IOU = 0.7;
/** At most this many people per frame, like the validated reference. */
const MAX_PEOPLE = 300;
/** Long side of a stored BodyMask, in cells. */
export const MASK_SIDE = 32;

/** Where the frame sits in the letterbox: letterbox px = frame px × scale + offset. */
export interface Letterbox {
  scale: number;
  left: number;
  top: number;
}

/** A person box in letterbox pixels, and the anchor whose mask coefficients go with it. */
export interface Candidate {
  box: NRect;
  anchor: number;
}

/**
 * Fills the [1,3,640,640] RGB/255 input with `frame` centred over 114/255 grey, nearest-neighbour scaled
 * so its longer side is 640.
 */
export function fillInput(frame: Rgba, out: Float32Array): Letterbox {
  const { width, height, data } = frame;
  const scale = SIDE / Math.max(width, height);
  const w = Math.round(width * scale);
  const h = Math.round(height * scale);
  const left = Math.trunc((SIDE - w) / 2);
  const top = Math.trunc((SIDE - h) / 2);
  const plane = SIDE * SIDE;
  out.fill(114 / 255);
  for (let y = 0; y < h; y++) {
    const row = Math.min(Math.trunc(y / scale), height - 1) * width;
    for (let x = 0; x < w; x++) {
      const s = 4 * (row + Math.min(Math.trunc(x / scale), width - 1));
      const i = (y + top) * SIDE + x + left;
      out[i] = data[s] / 255;
      out[plane + i] = data[s + 1] / 255;
      out[2 * plane + i] = data[s + 2] / 255;
    }
  }
  return { scale, left, top };
}

/**
 * Decodes `output0`: person anchors scoring above CONFIDENCE whose best class is person
 * (classes=[0], multi_label=False), best first (ties by anchor), greedy NMS at IoU 0.7, at most 300.
 * Boxes stay in letterbox pixels. Non-finite numbers or a short output throw: a detector that cannot be
 * read must stop the export, not report "nobody".
 */
export function decode(out: ArrayLike<number>): Candidate[] {
  if (out.length < (4 + CLASSES + COEFFS) * ANCHORS) throw new Error('The body detector returned malformed output.');
  const candidates: number[] = [];
  for (let i = 0; i < ANCHORS; i++) {
    const person = out[4 * ANCHORS + i];
    if (!Number.isFinite(person)) throw new Error('The body detector returned a non-finite score.');
    if (person <= CONFIDENCE) continue;
    let best = true;
    for (let c = 5; c < 4 + CLASSES; c++) {
      const score = out[c * ANCHORS + i];
      if (!Number.isFinite(score)) throw new Error('The body detector returned a non-finite score.');
      if (score > person) {
        best = false;
        break;
      }
    }
    if (best) candidates.push(i);
  }
  candidates.sort((a, b) => out[4 * ANCHORS + b] - out[4 * ANCHORS + a] || a - b);
  const kept: Candidate[] = [];
  for (const i of candidates) {
    const cx = out[i];
    const cy = out[ANCHORS + i];
    const bw = out[2 * ANCHORS + i];
    const bh = out[3 * ANCHORS + i];
    if (!Number.isFinite(cx + cy + bw + bh)) throw new Error('The body detector returned a non-finite box.');
    if (bw <= 0 || bh <= 0) continue;
    const box = nrect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
    if (kept.some((k) => overlap(k.box, box) > NMS_IOU)) continue;
    kept.push({ box, anchor: i });
    if (kept.length === MAX_PEOPLE) break;
  }
  return kept;
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/** A letterbox box -> normalized to the `width` x `height` frame, clamped to it; null when nothing is left. */
export function toFrame(box: NRect, width: number, height: number, lb: Letterbox): NRect | null {
  const r = nrect(
    clamp01((box.left - lb.left) / (lb.scale * width)),
    clamp01((box.top - lb.top) / (lb.scale * height)),
    clamp01((box.right - lb.left) / (lb.scale * width)),
    clamp01((box.bottom - lb.top) / (lb.scale * height)),
  );
  return rectWidth(r) > 0 && rectHeight(r) > 0 ? r : null;
}

/**
 * The silhouette over `box` (normalized to the `width` x `height` frame): `sigmoid(coefficients ·
 * prototypes) > 0.5`, i.e. the logit's sign, sampled at each cell centre of a grid whose long side is
 * MASK_SIDE cells. Each cell centre maps frame -> letterbox -> prototype cell.
 */
export function maskFor(
  out: ArrayLike<number>,
  proto: ArrayLike<number>,
  anchor: number,
  box: NRect,
  width: number,
  height: number,
  lb: Letterbox,
): BodyMask {
  const plane = PROTO_SIDE * PROTO_SIDE;
  if (proto.length < COEFFS * plane) throw new Error('The body detector returned malformed mask prototypes.');
  const coefficients = Array.from({ length: COEFFS }, (_, c) => out[(4 + CLASSES + c) * ANCHORS + anchor]);
  const bw = rectWidth(box) * width;
  const bh = rectHeight(box) * height;
  const long = Math.max(bw, bh);
  const cols = Math.max(1, Math.round((MASK_SIDE * bw) / long));
  const rows = Math.max(1, Math.round((MASK_SIDE * bh) / long));
  const bits = new Uint8Array((cols * rows + 7) >> 3);
  const cell = (v: number) => Math.min(PROTO_SIDE - 1, Math.max(0, Math.trunc(v / 4)));
  for (let y = 0; y < rows; y++) {
    const py = cell((box.top * height + ((y + 0.5) * bh) / rows) * lb.scale + lb.top);
    for (let x = 0; x < cols; x++) {
      const px = cell((box.left * width + ((x + 0.5) * bw) / cols) * lb.scale + lb.left);
      let logit = 0;
      for (let c = 0; c < COEFFS; c++) logit += coefficients[c] * proto[c * plane + py * PROTO_SIDE + px];
      if (!Number.isFinite(logit)) throw new Error('The body detector returned a non-finite mask.');
      const i = y * cols + x;
      if (logit > 0) bits[i >> 3] |= 1 << (i % 8);
    }
  }
  return { rect: box, width: cols, height: rows, bits };
}

export interface Person {
  box: NRect;
  mask: BodyMask | null;
}

export class YoloSeg {
  private readonly input = new Float32Array(3 * SIDE * SIDE);

  private constructor(
    private readonly session: ort.InferenceSession,
    private readonly masks: boolean,
  ) {}

  /** With `masks` each person carries a silhouette (precise mode); otherwise the prototypes are never read. */
  static async load(masks: boolean): Promise<YoloSeg> {
    return new YoloSeg(await openModel('yolo11n-seg.onnx', 'body detector'), masks);
  }

  /** People in `frame` as upright boxes normalized to it (unpadded), best score first. */
  async detect(frame: Rgba): Promise<Person[]> {
    const lb = fillInput(frame, this.input);
    const names = this.masks ? ['output0', 'output1'] : ['output0'];
    const { output0: out, output1: proto } = await runModel(this.session, this.input, [1, 3, SIDE, SIDE], names);
    const people: Person[] = [];
    for (const { box, anchor } of decode(out)) {
      const rect = toFrame(box, frame.width, frame.height, lb);
      if (!rect) continue;
      people.push({ box: rect, mask: proto ? maskFor(out, proto, anchor, rect, frame.width, frame.height, lb) : null });
    }
    return people;
  }

  release(): Promise<void> {
    return this.session.release();
  }
}
