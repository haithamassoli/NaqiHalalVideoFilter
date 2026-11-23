import { describe, expect, it } from 'vitest';
import {
  BLUR,
  DEFAULT_OPS,
  DEFAULT_STRICTNESS,
  DEFAULT_WHO,
  EVERYONE,
  MEN,
  NONE,
  WOMEN,
  anyOp,
  censorFaces,
  whoFromLegacy,
  whoOrNull,
} from '../src/core/filterOps';

// Mirrors FilterOps.kt's defaults.
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

// Ported from apps/android/app/src/test/java/com/haithamassoli/naqi/model/FilterOpsWireTest.kt.
describe('FilterOps who parsing', () => {
  it('every state survives the wire', () => {
    for (const who of [NONE, EVERYONE, WOMEN, MEN]) expect(whoOrNull(who)).toBe(who);
    expect(whoOrNull(' Women ')).toBe(WOMEN);
  });

  it('an absent key reads as absent in both spellings', () => {
    expect(whoOrNull(null)).toBeNull();
    expect(whoOrNull('')).toBeNull();
    expect(whoOrNull('   ')).toBeNull();
  });

  it('an unrecognised value censors', () => {
    expect(whoOrNull('true')).toBe(EVERYONE);
    expect(whoOrNull('female')).toBe(EVERYONE);
  });

  it('the legacy boolean maps to the two states it could express', () => {
    expect(whoFromLegacy(true)).toBe(EVERYONE);
    expect(whoFromLegacy(false)).toBe(NONE);
  });

  it('nothing picked means no op; censoring faces is an op', () => {
    expect(anyOp(DEFAULT_OPS)).toBe(false);
    expect(censorFaces({ censorWho: WOMEN })).toBe(true);
    expect(anyOp({ removeMusic: false, censorWho: MEN })).toBe(true);
  });
});
