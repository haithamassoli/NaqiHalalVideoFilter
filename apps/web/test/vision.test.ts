// Model pre- and post-processing on synthetic tensors: no models, no canvas.
import { describe, expect, it } from 'vitest';
import { nrect } from '../src/core/contracts';
import { NSFW_CLASSES, fires } from '../src/core/nsfwGate';
import { CONF_FLOOR, SIDE as GA_SIDE, cropToTensor, voteFromLogits } from '../src/vision/genderage';
import { SIDE as NSFW_SIDE, fillInput as nsfwInput, probabilities } from '../src/vision/nsfw';
import { Slots, analysisSize, type Rgba } from '../src/vision/preprocess';
import { NMS_IOU, SCORE_THRESHOLD, SIDE, decode, fillInput as yunetInput, nms } from '../src/vision/yunet';

/** Zeroed YuNet outputs for all three strides. */
function outputs(): Record<string, Float32Array> {
  const o: Record<string, Float32Array> = {};
  for (const s of [8, 16, 32]) {
    const n = (SIDE / s) ** 2;
    o[`cls_${s}`] = new Float32Array(n);
    o[`obj_${s}`] = new Float32Array(n);
    o[`bbox_${s}`] = new Float32Array(4 * n);
  }
  return o;
}

function rgba(width: number, height: number, pixel: (x: number, y: number) => [number, number, number]): Rgba {
  const data = new Uint8ClampedArray(4 * width * height);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const [r, g, b] = pixel(x, y);
      data.set([r, g, b, 255], 4 * (y * width + x));
    }
  }
  return { data, width, height };
}

describe('YuNet decode', () => {
  it('decodes one anchor: centre from cell + offset, size from exp(log-size), score sqrt(cls*obj)', () => {
    const o = outputs();
    // stride 16: 40 columns; cell (x 5, y 3) is anchor 3*40+5.
    const i = 3 * 40 + 5;
    o.cls_16[i] = 0.81;
    o.obj_16[i] = 1;
    o.bbox_16.set([0.5, 0.25, Math.log(2), Math.log(3)], 4 * i);
    const boxes = decode(o);
    expect(boxes.length).toBe(1);
    const [score, b] = boxes[0];
    expect(score).toBeCloseTo(0.9, 6);
    // centre (5.5*16, 3.25*16) = (88, 52); size (32, 48)
    expect(b.left).toBeCloseTo(72, 4);
    expect(b.top).toBeCloseTo(28, 4);
    expect(b.right).toBeCloseTo(104, 4);
    expect(b.bottom).toBeCloseTo(76, 4);
  });

  it('drops scores under the threshold and clamps cls/obj to [0, 1]', () => {
    const o = outputs();
    o.cls_8[0] = SCORE_THRESHOLD ** 2 - 0.01; // just under after sqrt
    o.obj_8[0] = 1;
    o.cls_32[7] = 5; // clamps to 1
    o.obj_32[7] = SCORE_THRESHOLD ** 2; // exactly at the threshold: kept
    const boxes = decode(o);
    expect(boxes.length).toBe(1);
    expect(boxes[0][0]).toBeCloseTo(SCORE_THRESHOLD, 6);
  });

  it('throws on missing, short or NaN output instead of reporting no faces', () => {
    const missing = outputs();
    delete missing.bbox_32;
    expect(() => decode(missing)).toThrow();
    const short = outputs();
    short.cls_8 = new Float32Array(10);
    expect(() => decode(short)).toThrow();
    const nan = outputs();
    nan.cls_16[0] = NaN;
    expect(() => decode(nan)).toThrow();
    const badBox = outputs();
    badBox.cls_8[0] = 1;
    badBox.obj_8[0] = 1;
    badBox.bbox_8[0] = NaN;
    expect(() => decode(badBox)).toThrow();
  });

  it('NMS keeps the best of overlapping boxes and every separate one', () => {
    const a = nrect(0, 0, 100, 100);
    const a2 = nrect(5, 5, 105, 105); // IoU ~0.82 with a
    const b = nrect(300, 300, 400, 400);
    const kept = nms([[0.7, a2], [0.9, a], [0.65, b]]);
    expect(kept.map(([s]) => s)).toEqual([0.9, 0.65]);
    // Exactly at NMS_IOU is not suppressed (Windows' strict >).
    expect(nms([[0.9, nrect(0, 0, 1, 1)], [0.8, nrect(0, 0, NMS_IOU, 1)]]).length).toBe(2);
  });

  it('fills BGR at the top-left of a zero 640 canvas, unnormalized', () => {
    const frame = rgba(4, 2, (x, y) => [10 + x, 20 + y, 30]);
    const t = new Float32Array(3 * SIDE * SIDE).fill(7);
    expect(yunetInput(frame, t)).toEqual([4, 2]);
    const plane = SIDE * SIDE;
    const at = (x: number, y: number) => [t[y * SIDE + x], t[plane + y * SIDE + x], t[2 * plane + y * SIDE + x]];
    expect(at(3, 1)).toEqual([30, 21, 13]); // B, G, R
    expect(at(4, 0)).toEqual([0, 0, 0]);
    expect(at(0, 2)).toEqual([0, 0, 0]);
  });

  it('scales a frame over 640 down to fit', () => {
    const frame = rgba(1280, 2, () => [1, 2, 3]);
    expect(yunetInput(frame, new Float32Array(3 * SIDE * SIDE))).toEqual([640, 1]);
  });
});

describe('genderage', () => {
  it('votes male, female, or abstains under the confidence floor', () => {
    expect(voteFromLogits([0, 2, 0.3])).toBe(1);
    expect(voteFromLogits([2, 0, 0.3])).toBe(-1);
    expect(voteFromLogits([0, 0, 0.3])).toBe(0); // p = 0.5
    const edge = Math.log(CONF_FLOOR / (1 - CONF_FLOOR)); // p exactly CONF_FLOOR
    expect(voteFromLogits([0, edge * 0.99, 0])).toBe(0);
    expect(voteFromLogits([0, edge * 1.01, 0])).toBe(1);
  });

  it('throws on unusable logits rather than casting a vote', () => {
    expect(() => voteFromLogits([NaN, 1, 0])).toThrow();
    expect(() => voteFromLogits([1])).toThrow();
  });

  it('crops a 1.5x square around the box, nearest-neighbour, edge-clamped, RGB 0..255', () => {
    const frame = rgba(100, 100, (x, y) => [x, y, 200]);
    const t = new Float32Array(3 * GA_SIDE * GA_SIDE);
    // A 25x25 px box centred at (50, 50): the crop is 37.5 px from (31.25, 31.25), step 37.5/96.
    cropToTensor(frame, nrect(0.375, 0.375, 0.625, 0.625), t);
    const plane = GA_SIDE * GA_SIDE;
    expect([t[0], t[plane], t[2 * plane]]).toEqual([31, 31, 200]);
    const last = GA_SIDE * GA_SIDE - 1;
    expect([t[last], t[plane + last]]).toEqual([68, 68]); // 31.25 + 95 * 0.390625 = 68.36
    // A box at the corner reaches past the frame: clamped to the edge pixels.
    cropToTensor(frame, nrect(0, 0, 0.1, 0.1), t);
    expect([t[0], t[plane]]).toEqual([0, 0]);
  });
});

describe('NSFW', () => {
  it('packs the 224 picture NCHW RGB / 255', () => {
    const frame = rgba(NSFW_SIDE, NSFW_SIDE, (x) => [255, x, 0]);
    const t = new Float32Array(3 * NSFW_SIDE * NSFW_SIDE);
    nsfwInput(frame, t);
    const plane = NSFW_SIDE * NSFW_SIDE;
    expect(t[0]).toBe(1);
    expect(t[plane + 51]).toBeCloseTo(51 / 255, 6);
    expect(t[2 * plane + 51]).toBe(0);
    expect(() => nsfwInput(rgba(2, 2, () => [0, 0, 0]), t)).toThrow();
  });

  it('feeds the gate in class order', () => {
    const p = (k: Partial<Record<(typeof NSFW_CLASSES)[number], number>>) =>
      probabilities(NSFW_CLASSES.map((c) => k[c] ?? 0));
    expect(fires(p({ porn: 0.9, neutral: 0.1 }), 40)).toBe(true);
    expect(fires(p({ neutral: 0.95, porn: 0.05 }), 40)).toBe(false);
  });

  it('throws on output that is not five probabilities', () => {
    expect(() => probabilities([0.2, 0.2, 0.2, 0.2])).toThrow();
    expect(() => probabilities([0.2, NaN, 0.2, 0.2, 0.2])).toThrow();
    expect(() => probabilities([0.2, -1, 0.2, 0.2, 0.2])).toThrow();
    expect(Array.from(probabilities([0, 0, 1.0005, 0, 0]))[2]).toBeCloseTo(1.0005, 4); // int8 rounding
  });
});

describe('sampling', () => {
  it('analysis size: longer side to 640, never up, even sides', () => {
    expect(analysisSize(1920, 1080)).toEqual([640, 360]);
    expect(analysisSize(1080, 1920)).toEqual([360, 640]);
    expect(analysisSize(320, 241)).toEqual([320, 240]);
    expect(analysisSize(3000, 3)).toEqual([640, 2]);
  });

  it('slots take one frame per 100 ms from the first, and re-anchor after a gap', () => {
    const s = new Slots(10);
    const taken = [0, 33_333, 66_667, 100_000, 133_333, 200_001, 700_000, 750_000, 800_000].filter((us) => s.take(us));
    expect(taken).toEqual([0, 100_000, 200_001, 700_000, 800_000]);
  });
});
