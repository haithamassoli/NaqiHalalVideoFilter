// YOLO11n-seg decode on synthetic outputs: a port of apps/android's PersonDetectorTest, plus the letterbox.
import { describe, expect, it } from 'vitest';
import { nrect } from '../src/core/contracts';
import { maskAt } from '../src/core/edl';
import { ANCHORS, SIDE, decode, fillInput, maskFor, toFrame } from '../src/vision/yoloseg';

function output(): { out: Float32Array; anchor: (i: number, cx: number, cy: number, w: number, h: number, person: number) => void } {
  const out = new Float32Array(116 * ANCHORS);
  const anchor = (i: number, ...values: number[]) => values.forEach((v, a) => (out[a * ANCHORS + i] = v));
  return { out, anchor };
}

describe('YOLO11n-seg decode', () => {
  it('keeps person anchors after the class filter and NMS', () => {
    const { out, anchor } = output();
    anchor(0, 200, 300, 100, 200, 0.9);
    anchor(1, 205, 300, 100, 200, 0.8); // same person, suppressed at IoU > 0.7
    anchor(2, 500, 300, 50, 100, 0.6);
    anchor(3, 100, 100, 50, 50, 0.7);
    out[(4 + 3) * ANCHORS + 3] = 0.75; // a dog outscores person there
    anchor(4, 300, 500, 50, 50, 0.25); // at the floor, not above it
    const kept = decode(out);
    expect(kept.map((k) => k.box)).toEqual([nrect(150, 200, 250, 400), nrect(475, 250, 525, 350)]);
    expect(kept.map((k) => k.anchor)).toEqual([0, 2]);
  });

  it('samples the prototype inside the upright box (precise mode)', () => {
    const { out, anchor } = output();
    anchor(0, 200, 300, 100, 200, 0.9);
    out[84 * ANCHORS] = 1; // the first coefficient selects prototype 0
    const proto = new Float32Array(32 * 160 * 160);
    // Prototype 0 is positive left of letterbox x 200 (prototype column 50).
    for (let y = 0; y < 160; y++) for (let x = 0; x < 160; x++) proto[y * 160 + x] = x < 50 ? 1 : -1;
    const [{ box, anchor: a }] = decode(out);
    expect(box).toEqual(nrect(150, 200, 250, 400));
    // A 360x640 portrait letterboxes at scale 1 with 140 px on the left: upright x 10..110.
    const lb = { scale: 1, left: 140, top: 0 };
    const upright = toFrame(box, 360, 640, lb)!;
    expect(upright.left).toBeCloseTo(10 / 360, 6);
    expect(upright.top).toBeCloseTo(200 / 640, 6);
    expect(upright.right).toBeCloseTo(110 / 360, 6);
    expect(upright.bottom).toBeCloseTo(400 / 640, 6);
    const mask = maskFor(out, proto, a, upright, 360, 640, lb);
    expect([mask.width, mask.height]).toEqual([16, 32]);
    expect(mask.rect).toBe(upright);
    for (const y of [0, 31]) {
      expect(Array.from({ length: 16 }, (_, x) => maskAt(mask, x, y))).toEqual([...Array(8).fill(true), ...Array(8).fill(false)]);
    }
    expect(maskAt(mask, 16, 0) || maskAt(mask, -1, 0)).toBe(false);
  });

  it('throws on unreadable output instead of finding nobody', () => {
    const { out, anchor } = output();
    anchor(0, 200, 300, 100, 200, 0.9);
    out[(4 + 10) * ANCHORS] = NaN;
    expect(() => decode(out)).toThrow(/non-finite/);
    expect(() => decode(new Float32Array(10))).toThrow(/malformed/);
    const ok = output();
    ok.anchor(0, NaN, 300, 100, 200, 0.9);
    expect(() => decode(ok.out)).toThrow(/non-finite box/);
    const bad = output();
    bad.anchor(0, 200, 300, 100, 200, 0.9);
    const proto = new Float32Array(32 * 160 * 160).fill(NaN);
    expect(() => maskFor(bad.out, proto, 0, nrect(0.1, 0.1, 0.5, 0.5), 640, 640, { scale: 1, left: 0, top: 0 })).toThrow(/mask/);
  });

  it('clamps boxes to the frame and drops those left in the letterbox bars', () => {
    const lb = { scale: 1, left: 140, top: 0 };
    expect(toFrame(nrect(100, -10, 200, 700), 360, 640, lb)).toEqual(nrect(0, 0, 60 / 360, 1));
    expect(toFrame(nrect(0, 100, 130, 200), 360, 640, lb)).toBeNull();
  });
});

describe('YOLO11n-seg input', () => {
  it('letterboxes the frame into the centre over 114 grey, RGB / 255', () => {
    // 2x1: a red pixel and a blue one -> scale 320, 640x320 at top 160.
    const frame = { data: new Uint8ClampedArray([255, 0, 0, 255, 0, 0, 255, 255]), width: 2, height: 1 };
    const out = new Float32Array(3 * SIDE * SIDE);
    expect(fillInput(frame, out)).toEqual({ scale: 320, left: 0, top: 160 });
    const plane = SIDE * SIDE;
    const at = (x: number, y: number) => [0, 1, 2].map((c) => out[c * plane + y * SIDE + x]);
    const grey = Math.fround(114 / 255);
    expect(at(0, 0)).toEqual([grey, grey, grey]);
    expect(at(0, 160)).toEqual([1, 0, 0]);
    expect(at(319, 479)).toEqual([1, 0, 0]);
    expect(at(320, 300)).toEqual([0, 0, 1]);
    expect(at(639, 480)).toEqual([grey, grey, grey]);
  });
});
