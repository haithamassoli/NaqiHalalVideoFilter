// Port of core/src/test/kotlin/com/haithamassoli/naqi/analysis/NRectRotationTest.kt. Fixture: an upright
// rect hugging the TOP-LEFT corner. For rotation 90 it comes from the stored frame's BOTTOM-LEFT; for 270
// from the TOP-RIGHT; for 180 from the BOTTOM-RIGHT.
import { describe, expect, it } from 'vitest';
import { nrect, toStoredSpace, type NRect } from '../src/core/contracts';

const topLeft = nrect(0, 0, 0.2, 0.4);

function expectRect(expected: NRect, actual: NRect): void {
  expect(actual.left).toBeCloseTo(expected.left, 6);
  expect(actual.top).toBeCloseTo(expected.top, 6);
  expect(actual.right).toBeCloseTo(expected.right, 6);
  expect(actual.bottom).toBeCloseTo(expected.bottom, 6);
}

describe('NRect.toStoredSpace', () => {
  it('identity for unrotated', () => expectRect(topLeft, toStoredSpace(topLeft, 0)));
  it('rotation 90 maps to bottom-left', () => expectRect(nrect(0, 0.8, 0.4, 1), toStoredSpace(topLeft, 90)));
  it('rotation 270 maps to top-right', () => expectRect(nrect(0.6, 0, 1, 0.2), toStoredSpace(topLeft, 270)));
  it('rotation 180 maps to bottom-right', () => expectRect(nrect(0.8, 0.6, 1, 1), toStoredSpace(topLeft, 180)));
  it('negative and overflow degrees normalize', () => {
    expectRect(toStoredSpace(topLeft, 90), toStoredSpace(topLeft, -270));
    expectRect(toStoredSpace(topLeft, 90), toStoredSpace(topLeft, 450));
  });
});
