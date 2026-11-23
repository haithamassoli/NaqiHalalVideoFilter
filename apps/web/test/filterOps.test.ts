import { describe, expect, it } from 'vitest';
import { BLUR, DEFAULT_OPS, DEFAULT_STRICTNESS, DEFAULT_WHO, NONE } from '../src/core/filterOps';

// Mirrors FilterOps.kt's defaults; the full rule port and its tests come with milestone 2.
describe('FilterOps defaults', () => {
  it('match core FilterOps', () => {
    expect(DEFAULT_OPS).toEqual({
      removeMusic: false,
      censorWho: NONE,
      wholeFrameBlur: false,
      bodyBlur: false,
      bodyPrecise: false,
      censorNsfw: true,
      strictness: 40,
      blurAmount: 60,
      grayscale: false,
      solidColor: BLUR,
      keepStems: 'vocals',
    });
    expect(DEFAULT_STRICTNESS).toBe(40);
    expect(DEFAULT_WHO).toBe('women');
  });
});
