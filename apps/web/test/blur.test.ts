// The censor effect's rules, after apps/windows' CensorTest (the canvas drawing itself needs a browser).
import { describe, expect, it } from 'vitest';
import { nrect } from '../src/core/contracts';
import { faceTrackEdl, type Edl } from '../src/core/edl';
import { BLUR, SOLID_COLORS } from '../src/core/filterOps';
import { MIN_EFFECTIVE_BLUR, blurPlan, censorStyle, coverAt, coverMask } from '../src/render/blur';

const alpha = (m: NonNullable<ReturnType<typeof coverMask>>, x: number, y: number) =>
  x < m.x0 || y < m.y0 || x >= m.x0 + m.width || y >= m.y0 + m.height ? 0 : m.rgba[4 * ((y - m.y0) * m.width + (x - m.x0)) + 3];

describe('censorStyle', () => {
  it('turns the no-op style into a light blur', () => {
    expect(censorStyle({ blurAmount: 0, grayscale: false, solidColor: BLUR })).toEqual({ solid: null, amount: MIN_EFFECTIVE_BLUR, grayscale: false });
  });
  it('gives grayscale blur at 0 the same light blur: grey alone leaves a face recognizable', () => {
    expect(censorStyle({ blurAmount: 0, grayscale: true, solidColor: BLUR })).toEqual({ solid: null, amount: MIN_EFFECTIVE_BLUR, grayscale: true });
  });
  it('keeps blur 0 when a fill does the censoring', () => {
    expect(censorStyle({ blurAmount: 0, grayscale: false, solidColor: SOLID_COLORS[1] }).amount).toBe(0);
  });
  it('makes a solid fill opaque and win over grayscale', () => {
    expect(censorStyle({ blurAmount: 60, grayscale: true, solidColor: SOLID_COLORS[3] })).toEqual({
      solid: 'rgb(44 62 80)',
      amount: 60,
      grayscale: false,
    });
  });
});

describe('blurPlan', () => {
  it('scales sigma to the short side and keeps the low-res sigma at most 4', () => {
    expect(blurPlan(100, 1920, 1080)).toEqual({ scale: 8, sigma: 5 }); // 40 px: past 1/8, as Android caps it
    expect(blurPlan(60, 1920, 1080)).toEqual({ scale: 8, sigma: 3 }); // 24 px
    expect(blurPlan(10, 1920, 1080)).toEqual({ scale: 1, sigma: 4 }); // 4 px
    expect(blurPlan(100, 400, 300).scale).toBe(4); // 11.1 px
  });
});

describe('coverMask', () => {
  // 0.3..0.7 of a 100-px frame: pixels 30..69 inside; the feather band is 15% of 0.4 = 6 px outside.
  const region = nrect(0.3, 0.3, 0.7, 0.7);

  it('covers the rect fully and nothing past the feather', () => {
    const m = coverMask([region], 100, 100)!;
    for (let y = 30; y <= 69; y++) for (let x = 30; x <= 69; x++) expect(alpha(m, x, y)).toBe(255);
    for (let y = 0; y < 100; y++) {
      for (let x = 0; x < 100; x++) if (!(x >= 24 && x <= 75 && y >= 24 && y <= 75)) expect(alpha(m, x, y)).toBe(0);
    }
    const edge = alpha(m, 27, 50);
    expect(edge).toBeGreaterThan(0);
    expect(edge).toBeLessThan(255);
  });

  it('clips rects past the frame edge', () => {
    const m = coverMask([nrect(-0.2, 0.8, 0.3, 1.3)], 100, 100)!;
    expect(m.x0).toBe(0);
    expect(m.y0 + m.height).toBe(100);
    expect(alpha(m, 0, 99)).toBe(255);
    expect(alpha(m, 29, 80)).toBe(255);
  });

  it('takes the larger of overlapping masks and ignores empty rects', () => {
    expect(coverMask([nrect(0.5, 0.5, 0.5, 0.6)], 100, 100)).toBeNull();
    const m = coverMask([region, nrect(0.6, 0.6, 0.9, 0.9)], 100, 100)!;
    expect(alpha(m, 50, 50)).toBe(255);
    expect(alpha(m, 85, 85)).toBe(255);
  });
});

describe('coverAt', () => {
  const box = nrect(0.1, 0.1, 0.3, 0.3);
  const body = nrect(0.5, 0.2, 0.8, 0.9);
  const mask = { rect: body, width: 1, height: 1, bits: new Uint8Array([1]) };
  const edl: Edl = {
    censorIntervalsMs: [[1000, 2000]],
    faceTracks: [
      faceTrackEdl(0, 3000, [[0, box], [3000, box]]),
      faceTrackEdl(0, 3000, [[0, body], [100, body]], true, [mask, mask]),
    ],
  };

  it('covers the whole frame inside a censor interval', () => {
    expect(coverAt(edl, 1500)).toBe('full');
  });
  it('covers face rects, and body silhouettes while they are observed', () => {
    expect(coverAt(edl, 50)).toEqual({ regions: [box], masks: [mask, mask] }); // between two masked samples: both
    expect(coverAt(edl, 2500)).toEqual({ regions: [box, body], masks: [] }); // held past the last sample: the box
  });
  it('covers nothing outside every span', () => {
    expect(coverAt(edl, 3001)).toEqual({ regions: [], masks: [] });
  });
});

describe('coverMask silhouettes', () => {
  // A 4x4-cell mask over 0.2..0.6 of a 100-px frame (10-px cells) with only the top-left 2x2 cells set.
  const rect = nrect(0.2, 0.2, 0.6, 0.6);
  const mask = { rect, width: 4, height: 4, bits: new Uint8Array([0b00110011, 0]) };

  it('covers set cells fully, ramps outward over one cell, and leaves the rest of the box clear', () => {
    const m = coverMask([], 100, 100, [mask])!;
    for (let y = 20; y < 40; y++) for (let x = 20; x < 40; x++) expect(alpha(m, x, y)).toBe(255);
    expect(alpha(m, 15, 30)).toBe(255); // half a grown cell past the outline
    expect(alpha(m, 10, 30)).toBeGreaterThan(0); // the outward ramp
    expect(alpha(m, 10, 30)).toBeLessThan(255);
    expect(alpha(m, 4, 30)).toBe(0); // past the ramp
    expect(alpha(m, 55, 55)).toBe(0); // inside the box, outside the silhouette
    expect(alpha(m, 55, 25)).toBe(0);
  });

  it('takes the larger of a rect and a silhouette', () => {
    const m = coverMask([nrect(0.5, 0.5, 0.6, 0.6)], 100, 100, [mask])!;
    expect(alpha(m, 25, 25)).toBe(255);
    expect(alpha(m, 55, 55)).toBe(255);
  });

  it('ignores an empty mask rect', () => {
    expect(coverMask([], 100, 100, [{ ...mask, rect: nrect(0.2, 0.2, 0.2, 0.6) }])).toBeNull();
  });
});
