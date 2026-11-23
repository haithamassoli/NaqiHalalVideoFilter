// Mirrors core/src/main/kotlin/com/haithamassoli/naqi/model/FilterOps.kt (fields, defaults, wire
// literals). The full port with its rules and tests lands with the rest of core/ (plan milestone 2).

export const NONE = 'none';
export const EVERYONE = 'everyone';
export const WOMEN = 'women';
export const MEN = 'men';
export type CensorWho = typeof NONE | typeof EVERYONE | typeof WOMEN | typeof MEN;

/** What "censor faces" opens on before the user picks; `censorWho` itself defaults to NONE. */
export const DEFAULT_WHO: CensorWho = WOMEN;
export const DEFAULT_STRICTNESS = 40;
/** `solidColor` value meaning "blur, no solid fill". */
export const BLUR = 0;
/** Offered opaque fills (ARGB), in swatch order. */
export const SOLID_COLORS = [0xff9e9e9e, 0xff000000, 0xffffffff, 0xff2c3e50, 0xff1e3a2f] as const;

export interface FilterOps {
  removeMusic: boolean;
  censorWho: CensorWho;
  wholeFrameBlur: boolean;
  bodyBlur: boolean;
  bodyPrecise: boolean;
  censorNsfw: boolean;
  /** NSFW gate strictness, 0..100. */
  strictness: number;
  /** 0..100. */
  blurAmount: number;
  grayscale: boolean;
  /** BLUR (0) or an opaque ARGB colour. */
  solidColor: number;
  /** Spleeter keeps vocals only; "vocals_other" is not offered on the web (plan §4). */
  keepStems: 'vocals';
}

export const DEFAULT_OPS: Readonly<FilterOps> = Object.freeze({
  removeMusic: false,
  censorWho: NONE,
  wholeFrameBlur: false,
  bodyBlur: false,
  bodyPrecise: false,
  censorNsfw: true,
  strictness: DEFAULT_STRICTNESS,
  blurAmount: 60,
  grayscale: false,
  solidColor: BLUR,
  keepStems: 'vocals',
});
