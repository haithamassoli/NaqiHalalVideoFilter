# Naqi — Render Pass & Video Encode: exact porting spec (Android → Apple)

Extracted from the shipped Android app at `/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter`,
commit-state 2026-08-04. Every number below is quoted from source with a `file:line` citation.
media3 version is **1.10.1** (`gradle/libs.versions.toml:10`); media3 internals are cited from the
sources jars in `~/.gradle/caches`, because several shipped behaviours are *media3 defaults the app
deliberately does not override* and a Swift port must reproduce them explicitly.

Citation shorthand:

| Tag | File |
|---|---|
| `CE` | `app/src/main/java/com/haithamassoli/naqi/render/CensorEffect.kt` |
| `RP` | `app/src/main/java/com/haithamassoli/naqi/render/RenderPipeline.kt` |
| `CT` | `app/src/main/java/com/haithamassoli/naqi/analysis/Contracts.kt` |
| `ED` | `app/src/main/java/com/haithamassoli/naqi/edl/Edl.kt` |
| `FW` | `app/src/main/java/com/haithamassoli/naqi/work/FilterWorker.kt` |
| `CP` | `app/src/main/java/com/haithamassoli/naqi/work/Checkpoint.kt` |
| `RX` | `app/src/main/java/com/haithamassoli/naqi/audio/Remux.kt` |
| `TR` | `app/src/main/java/com/haithamassoli/naqi/media/Tracks.kt` |
| `FS` | `app/src/main/java/com/haithamassoli/naqi/analysis/FrameSampler.kt` |
| `FO` | `app/src/main/java/com/haithamassoli/naqi/model/FilterOps.kt` |
| `SP` | `app/src/main/java/com/haithamassoli/naqi/spike/SegmentConcatSpike.kt` |
| `m3:DEF` | media3 1.10.1 `androidx/media3/transformer/DefaultEncoderFactory.java` |
| `m3:VES` | media3 1.10.1 `androidx/media3/transformer/VideoEncoderSettings.java` |
| `m3:TU` | media3 1.10.1 `androidx/media3/transformer/TransformerUtil.java` |
| `m3:GLU` | media3 1.10.1 `androidx/media3/common/util/GlUtil.java` |
| `m3:GLP` | media3 1.10.1 `androidx/media3/common/util/GlProgram.java` |
| `m3:BGSP` | media3 1.10.1 `androidx/media3/effect/BaseGlShaderProgram.java` |

---

## 0. Pipeline shape in one contract

Per output frame the shader runs **one of three** paths (`CE:165-201`):

| Path | Trigger | Draws |
|---|---|---|
| **Copy** | `!full && regions.isEmpty()` (`CE:178`) | 1 draw: `COPY_FRAGMENT` into the already-focused output FBO |
| **Whole-frame** | `edl.fullFrameAt(tMs) == true` (`CE:174`) | 2 blur draws (if blur enabled) + 1 composite draw with `uCensorAll=1`, `uRegionCount=0` |
| **Regions** | `!full && regions.isNotEmpty()` | 2 blur draws (if blur enabled) + 1 composite draw with `uCensorAll=0`, `uRegionCount=N≤8` |

The two blur passes are **whole-frame and geometry-blind** (`CE:192-193`) — they never know a rect
exists. Only the composite is geometry-aware. This is why whole-frame mode measured **0.20 % render
delta over three runs** (`docs/plan-whole-frame-blur.md` §6.2, table rows A/B/C: 89 411 / 89 259 /
89 437 ms) and is stated as free.

Output resolution is **always** the input resolution: `configure()` returns
`Size(inputWidth, inputHeight)` (`CE:162`). No scaling effect exists anywhere in the pipeline.

---

## 1. Censor effect

### 1.1 Inputs and gating

| Field | Type | Default | Source |
|---|---|---|---|
| `blurAmount` | Int, 0–100 | **60** | `FO:47` |
| `grayscale` | Bool | **false** | `FO:48` |
| `solidColor` | Int ARGB, `0` ⇒ "blur, not solid" | `BLUR = 0` | `FO:55`, `FO:105` |
| `timeOffsetMs` | Long | `0` (whole timeline) / `segment.startMs` | `CE:53`, `RP:132` |
| `meta` | `VideoMeta(width,height,rotationDegrees,durationMs,fps)` | probe result | `CT:30-36` |

Derived gates (evaluated once at construction):

```kotlin
val solid       = solidColor != FilterOps.BLUR            // CE:97
val solidRgb    = solidRgb(solidColor)                    // CE:98
val blurEnabled = blurAmount > 0 && !solid                // CE:101
```

`solidRgb(argb)` = `[((argb>>16)&0xFF)/255f, ((argb>>8)&0xFF)/255f, (argb&0xFF)/255f]` (`CE:67-71`).
Alpha is discarded. **No transfer conversion** — media3's SDR pipeline is electrical (sRGB) and a
hex-picked colour already is. Known defect: under `useHdr` the composite is linear, so the fill lands
darker than the swatch (`CE:60-66`, ponytail note, unfixed).

Offered swatches (`FO:108-114`): `0xFF9E9E9E` gray, `0xFF000000` black, `0xFFFFFFFF` white,
`0xFF2C3E50` navy, `0xFF1E3A2F` green. All opaque by construction.

**Edge case that must be preserved:** `blurAmount == 0 && !grayscale && solidColor == BLUR` ⇒
`blurEnabled == false`, `uUseBlur=0`, `uUseSolid=0`, `uGrayscale=0`, so the composite computes
`mix(orig.rgb, orig.rgb, mask) == orig.rgb`. The censor is a **visual no-op that still pays a full
transcode**. Nothing guards it.

### 1.2 Blur amount → Gaussian sigma (exact)

`CE:137-147`:

```kotlin
val sigmaPx  = max(0.1f, blurAmount / 100f * 40f * (min(inputWidth, inputHeight) / 1080f))  // CE:140
val d        = intArrayOf(1, 2, 4, 8).firstOrNull { sigmaPx / it <= 4f } ?: 8               // CE:142
val newLowW  = max(1, inputWidth  / d)                                                       // CE:143
val newLowH  = max(1, inputHeight / d)                                                       // CE:144
val sigmaLow = sigmaPx / d                                                                   // CE:145
radius       = min(MAX_RADIUS, ceil(2.5f * sigmaLow).toInt()).coerceAtLeast(1)               // CE:146
kernel       = gaussianKernel(sigmaLow, radius)                                              // CE:147
```

Contract, numbered:

1. **σ is in FULL-RES pixels.** Max σ at 1080p is 40 px (`blurAmount=100`). Floor is `0.1f`.
2. **Keyed on the SHORT SIDE** — `min(inputWidth, inputHeight)`, referenced to 1080. Orientation-
   invariant by construction, so a rotated-portrait and a true-portrait video blur identically
   (`CE:138-139`). `inputWidth/inputHeight` are the **texture dims the effect actually received**,
   which may be stored or upright (§2.2) — `min()` makes that irrelevant.
3. **Divisions are float.** `blurAmount / 100f` and `min(w,h) / 1080f` are Float division; do not
   integer-divide.
4. **Downscale factor `d` ∈ {1,2,4,8}**: the smallest that keeps `sigmaPx / d <= 4f` (inclusive `<=`),
   falling back to 8. Thresholds: `d=1` iff σ≤4, `d=2` iff σ≤8, `d=4` iff σ≤16, else `d=8`.
5. **Scratch size uses INTEGER division** `inputWidth / d`, then `max(1, …)`. For 854×480 at d=4 that
   is 213×120, *not* 213.5. The texel step is `1/lowW`, so the truncation is observable.
6. **radius = clamp(ceil(2.5·σ_low), 1, 10)**, `MAX_RADIUS = 10` (`CE:27`). `ceil` on Float then
   `toInt()`.
7. **The kernel truncates below 2.5σ only when σ_low > 4**, i.e. only when `sigmaPx > 32`. That is
   `blurAmount > 80` at 1080p but `blurAmount > 40` at 4K. A faithful port must reproduce the
   truncation, not silently widen the kernel.

Worked table (16:9 sources; `lowW×lowH` from integer division):

| short side | blurAmount | σ_px | d | scratch | σ_low | radius | kernel half-width |
|---:|---:|---:|---:|---|---:|---:|---|
| 480 (854×480) | 60 | 10.667 | 4 | 213×120 | 2.667 | 7 | 2.5σ |
| 720 (1280×720) | 60 | 16.000 | 4 | 320×180 | 4.000 | 10 | 2.5σ |
| 1080 (1920×1080) | 10 | 4.000 | 1 | 1920×1080 | 4.000 | 10 | 2.5σ |
| 1080 | 20 | 8.000 | 2 | 960×540 | 4.000 | 10 | 2.5σ |
| 1080 | 40 | 16.000 | 4 | 480×270 | 4.000 | 10 | 2.5σ |
| 1080 | 50 | 20.000 | 8 | 240×135 | 2.500 | 7 | 2.5σ |
| **1080** | **60 (default)** | **24.000** | **8** | **240×135** | **3.000** | **8** | 2.5σ |
| 1080 | 80 | 32.000 | 8 | 240×135 | 4.000 | 10 | 2.5σ |
| 1080 | 100 | 40.000 | 8 | 240×135 | 5.000 | 10 | **2.0σ truncated** |
| 2160 (3840×2160) | 60 | 48.000 | 8 | 480×270 | 6.000 | 10 | **1.67σ truncated** |

`240×135 at 1080p/blurAmount 60` is independently confirmed in `docs/plan-whole-frame-blur.md:178`.
`blurAmount 50 ⇒ σ 20 px at 1080p` is confirmed at `docs/plan-whole-frame-blur.md:129`.

### 1.3 Kernel construction (exact)

`CE:290-300`:

```kotlin
private fun gaussianKernel(sigma: Float, radius: Int): FloatArray {
    val k = FloatArray(MAX_RADIUS + 1)          // 11 entries, tail stays 0
    var sum = 0f
    for (i in 0..radius) {
        val w = exp(-(i * i).toFloat() / (2f * sigma * sigma))
        k[i] = w
        sum += if (i == 0) w else 2f * w        // centre counted once, sides twice
    }
    for (i in 0..radius) k[i] /= sum
    return k
}
```

- No `1/(σ√2π)` factor; normalization is by the **truncated** discrete sum, so `k[0] + 2·Σk[1..r] == 1`
  exactly regardless of truncation.
- Array is length **11** always; entries `radius+1 … 10` are zero.
- Uploaded with `glUniform1fv(loc, kernel.size /* 11 */, kernel, 0)` (`CE:211`).

### 1.4 Downscale → blur → upscale strategy

`CE:188-196`, `CE:203-213`:

1. Save `GL_FRAMEBUFFER_BINDING` and `GL_VIEWPORT` (`CE:190-191`).
2. **Horizontal pass:** source = the **full-res input texture**, destination = `scratchFbo[0]`
   (`lowW×lowH`), `uTexelStep = (1/lowW, 0)` (`CE:192`).
   *The downscale happens here, as part of the blur pass* — the fragment shader runs at `lowW×lowH`
   and samples the full-res texture with `GL_LINEAR` at `[0,1]`-normalized positions. There is **no
   box pre-filter**: the downscale is bilinear point sampling and it aliases. A port that inserts a
   proper box/mipmap downscale will not be bit-comparable.
3. **Vertical pass:** source = `scratchTex[0]` (already low-res), destination = `scratchFbo[1]`,
   `uTexelStep = (0, 1/lowH)` (`CE:193`).
4. Restore FBO + viewport (`CE:194-195`).
5. **Upscale is implicit**: the composite samples `uBlurTex = scratchTex[1]` at full-res `vTexCoord`
   with `GL_LINEAR` (`CE:220`, `CE:398`) — a bilinear magnification from `lowW×lowH` to full res.

Blur fragment shader (`CE:324-346`), verbatim semantics:

```glsl
vec3 acc = texture2D(uTexSampler, vTexCoord).rgb * uWeights[0];
for (int i = 1; i <= 10; i++) {         // literal MAX_RADIUS bound
  if (i > uRadius) break;               // dynamic early-out
  vec2 o = uTexelStep * float(i);
  acc += (texture2D(uTexSampler, vTexCoord + o).rgb
        + texture2D(uTexSampler, vTexCoord - o).rgb) * uWeights[i];
}
gl_FragColor = vec4(acc, 1.0);          // NOTE: alpha forced to 1.0 in the scratch
```

- Taps per pass: `1 + 2·radius`, max **21**.
- **Only `.rgb` is blurred**; scratch alpha is a constant 1.0. The composite never reads blur alpha,
  it keeps `orig.a` (`CE:404`), so this is harmless but must not be "fixed" into something that
  changes the RGB result.
- Precision: `highp` if `GL_FRAGMENT_PRECISION_HIGH` else `mediump` (`CE:326-330`). Metal fragment
  math is fp32 by default — that matches the `highp` branch, which is what every modern device takes.

### 1.5 Grayscale, and how it combines with blur

`CE:394-404`:

```glsl
vec3 base;
if (uUseSolid == 1) {
  base = uSolidColor;                                   // solid wins outright, grayscale ignored
} else {
  base = (uUseBlur == 1) ? texture2D(uBlurTex, vTexCoord).rgb : orig.rgb;
  if (uGrayscale == 1) {
    float luma = dot(base, vec3(0.2126, 0.7152, 0.0722)); // BT.709
    base = vec3(luma);
  }
}
gl_FragColor = vec4(mix(orig.rgb, base, mask), orig.a);
```

| Rule | Value |
|---|---|
| Luma coefficients | **R 0.2126, G 0.7152, B 0.0722** (BT.709), `CE:400` |
| Order of operations | **gray(blur(x))** — grayscale is applied to the already-blurred base, never before |
| Grayscale + solid | grayscale **suppressed** (`CE:393` "graying a flat fill would only turn the picked colour into a gray one") |
| Blur + solid | blur passes **skipped entirely**, `blurEnabled = blurAmount > 0 && !solid` (`CE:101`) |
| Blend | `mix(orig.rgb, base, mask)` — linear lerp by the coverage mask |
| Alpha | `orig.a` passed through unmodified |
| Colour space | electrical/sRGB (media3 SDR pipeline). No linearization anywhere. |

### 1.6 Shader edge / clamp behaviour

Two distinct questions; both matter.

**(a) Frame borders.** Every texture bind in this pipeline goes through media3's
`GlUtil.bindTexture` — called from `GlUtil.createTextureUninitialized` at texture creation
(`m3:GLU:780`) *and again on every `bindAttributesAndUniforms()`* via
`GlProgram.Uniform.bind` (`m3:GLP:531-538`). It sets:

| Parameter | Value | Source |
|---|---|---|
| `GL_TEXTURE_MAG_FILTER` | `GL_LINEAR` | `m3:GLU:826` |
| `GL_TEXTURE_MIN_FILTER` | `GL_LINEAR` | `m3:GLU:828` (and re-set to `TEXTURE_MIN_FILTER_LINEAR`, `m3:GLP:544-545`) |
| `GL_TEXTURE_WRAP_S` | **`GL_CLAMP_TO_EDGE`** | `m3:GLU:830` |
| `GL_TEXTURE_WRAP_T` | **`GL_CLAMP_TO_EDGE`** | `m3:GLU:832` |

So blur taps that fall outside `[0,1]` **replicate the edge texel**. Not black, not wrapped, not
mirrored. Apple equivalent: `MTLSamplerAddressMode.clampToEdge` on both axes, `minFilter = .linear`,
`magFilter = .linear`, no mipmaps.

**(b) Region borders.** There is **no clamping at all at a rect edge**. The blur is computed over the
whole frame before any geometry is considered, so:

- A pixel just inside a region samples blurred content that **includes pixels from outside the
  region** (bleed inward is intentional and unavoidable).
- The mask decides *how much* of that global blur shows, never *where the blur reads from*.
- The mask is a **strictly outward** feather (§2.5) so the hard rect is always at `mask == 1`;
  softening can never uncover a pixel the hard rect covered (`CE:377-380`).

**(c) Scratch textures are never cleared.** `BaseGlShaderProgram.shouldClearTextureBuffer()` is not
overridden and the output FBO clear path (`m3:BGSP:160-162`) does not apply to the app's own
`scratchFbo`. Both blur passes write every pixel of their target, so this is safe — but a Metal port
must use `.dontCare`/full-coverage draws consistently, not rely on a clear.

### 1.7 Geometry, draws, texture pool

| Item | Value | Source |
|---|---|---|
| Vertex attribute | `aFramePosition`, 4 components (`HOMOGENEOUS_COORDINATE_VECTOR_SIZE = 4`) | `CE:274-278`, `m3:GLU:76` |
| Quad vertices | `(-1,-1,0,1) (1,-1,0,1) (-1,1,0,1) (1,1,0,1)` | `m3:GLU:149-156` |
| Draw call | `glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)` — every pass | `CE:212`, `CE:239`, `CE:246` |
| Tex coord derivation | `vTexCoord = aFramePosition.xy * 0.5 + 0.5` (y-UP) | `CE:308` |
| `texturePoolCapacity` | **3** — an *untested* pipeline-depth experiment (`perf-plan-v4` §3 A6, "NOT ESTIMABLE"); costs 2 extra full-res output textures ≈ 16 MB at 1080p RGBA8888, double under HDR | `CE:95`, `CE:73-85`, `docs/perf-plan-v4.md:101` |
| Array uniform lookup | try `name`, fall back to `name[0]` — GLES exposes either | `CE:284-286` |
| `uRegions` upload | `glUniform4fv(loc, regions.size, data, 0)` — only the first N of 8 slots written; slots ≥ N are stale and never read because the loop breaks at `uRegionCount` | `CE:237`, `CE:375` |
| `uBlurTex` when blur off | bound to `inputTexId` (unused at runtime, but a sampler must bind a *complete* texture) | `CE:219-220` |

---

## 2. Region geometry

### 2.1 Coordinate space of an `NRect`

`CT:1-27`, `CT:11`:

| Property | Value |
|---|---|
| Type | `NRect(left, top, right, bottom)`, all `Float` |
| Units | **Normalized `[0,1]`** — never pixels |
| Origin | **Top-left, y-down** |
| Orientation | **UPRIGHT (display-oriented)** frame space, as produced by ML Kit against `FrameSampler.uprightSize` (`FS:457-458`, `CT:3-10`) |
| Derived | `width = right - left`, `height = bottom - top` (`CT:12-13`) |
| Padding already applied | rects in the EDL are **already 25 %-padded** by the tracker (`ED:7`, `docs/plan-censor-who.md:164`: `KEYFRAME_PAD = 0.25f` ⇒ 1.5× per dimension) — do **not** re-pad in the renderer |

`uprightSize(w, h, rot)` = `if (((rot % 360)+360)%360 % 180 == 90) h to w else w to h` (`FS:457-458`).

`VideoMeta.width/height` are the **stored, pre-rotation display size**, taken from the MediaFormat
crop rectangle when present: `crop-right - crop-left + 1` / `crop-bottom - crop-top + 1`, else
`KEY_WIDTH`/`KEY_HEIGHT` (`FS:436-444`; crop keys are *inclusive* pixel indices).
`VideoMeta.rotationDegrees` is normalized to `[0,360)` (`FS:83`).

### 2.2 Which space the shader receives — the decision

`CE:123-136`:

```kotlin
mapRotation = when {
    meta.rotationDegrees % 180 == 0 -> {
        if (meta.rotationDegrees == 180) Log.w(TAG, "180-rotated source: assuming pre-rotated frames")
        0
    }
    inputWidth == meta.width && inputHeight == meta.height -> {
        if (meta.width == meta.height) Log.w(TAG, "square rotated source: orientation ambiguous, mapping as stored")
        meta.rotationDegrees          // stored-orientation frames: map upright -> stored
    }
    else -> 0                          // dims already swapped: frames arrived upright
}
```

| `meta.rotationDegrees` | incoming texture dims | `mapRotation` | rect transform |
|---|---|---|---|
| 0 | any | 0 | identity |
| **180** | any (dimension-invisible) | **0** | **identity — assumed pre-rotated. Warned, never QA'd** (`CE:126-128`) |
| 90 | `== (meta.width, meta.height)` | 90 | `toStoredSpace(90)` |
| 90 | swapped | 0 | identity |
| 270 | `== (meta.width, meta.height)` | 270 | `toStoredSpace(270)` |
| 270 | swapped | 0 | identity |
| 90/270 with `meta.width == meta.height` | ambiguous | source rotation | mapped as stored, warned (`CE:132`) |

**Why the sniffing exists**: whether media3 hands effects upright or stored-orientation frames is
**decoder-dependent in media3 1.10** — both were observed on one S23: a rotation-90 input arrived
pre-rotated upright with the rotation dropped from the muxer, while rotation-270 arrived in stored
orientation with the display matrix forwarded (`CE:41-46`).

### 2.3 Rotation mapping maths (upright → stored)

`CT:21-26`, applied at `CE:176`:

```kotlin
fun toStoredSpace(rotationDegrees: Int): NRect = when (((rotationDegrees % 360) + 360) % 360) {
    90  -> NRect(top,          1f - right,  bottom,      1f - left)
    180 -> NRect(1f - right,   1f - bottom, 1f - left,   1f - top)
    270 -> NRect(1f - bottom,  left,        1f - top,    right)
    else -> this
}
```

Component-wise, output `(L', T', R', B')` from input `(L, T, R, B)`:

| rot | L' | T' | R' | B' |
|---:|---|---|---|---|
| 90 | `T` | `1-R` | `B` | `1-L` |
| 180 | `1-R` | `1-B` | `1-L` | `1-T` |
| 270 | `1-B` | `L` | `1-T` | `R` |

Semantics: "stored frames must be rotated `rotationDegrees` **clockwise** for display" (Android's
convention). This is the **inverse** of the display rotation.

**The 180 branch is dead code on the render path** — `configure()` forces `mapRotation = 0` for every
`rotation % 180 == 0` (`CE:125`). It is reachable only if a caller invokes `toStoredSpace(180)`
directly. A Swift port must decide 180 deliberately rather than inherit this by accident (§6.3).

### 2.4 y-flip into GL coordinates

`NRect` is y-down; GL/`vTexCoord` is y-up. The upload packs **(left, right, yLow, yHigh)** per region
(`CE:229-236`):

```
data[i*4 + 0] = r.left          // → r.x
data[i*4 + 1] = r.right         // → r.y
data[i*4 + 2] = 1f - r.bottom   // → r.z   (yLow)
data[i*4 + 3] = 1f - r.top      // → r.w   (yHigh)
```

Note the packing is **not** `(l,t,r,b)`; it is `(xMin, xMax, yMin, yMax)` with y flipped. On Apple,
if you render into a top-left-origin texture (Metal's default), the flip disappears and you must
either drop it or flip your sampling coordinate — pick one and pin it with a test.

### 2.5 Feathering (exact)

`CE:382-387`:

```glsl
vec2 f = max(vec2(r.y - r.x, r.w - r.z) * 0.15, 0.002);
mask = max(mask,
    smoothstep(r.x - f.x, r.x, vTexCoord.x) *
    (1.0 - smoothstep(r.y, r.y + f.x, vTexCoord.x)) *
    smoothstep(r.z - f.y, r.z, vTexCoord.y) *
    (1.0 - smoothstep(r.w, r.w + f.y, vTexCoord.y)));
```

| Item | Value |
|---|---|
| Feather fraction | **0.15** of the region's own size, **per axis independently** (`CE:382`) |
| `f.x` | `max(0.15 · (right − left), 0.002)` |
| `f.y` | `max(0.15 · (bottom − top), 0.002)` — since `r.w − r.z == bottom − top` |
| Floor | **0.002** normalized units per axis, component-wise; keeps `smoothstep` edges distinct on a degenerate rect (`CE:381`) |
| Direction | **OUTWARD ONLY.** `mask == 1` across the entire hard rect; the ramp lives strictly *outside* it (`CE:377-380`) |
| Combination | `max()` across regions — union, not sum. Overlapping feathers do not double-darken |
| Whole-frame seed | `float mask = float(uCensorAll);` (`CE:373`) — starts at 1, so the loop's `max()` is a no-op |
| `smoothstep` | GLSL semantics: `t = clamp((x−e0)/(e1−e0), 0, 1); return t·t·(3−2t)`. Metal's `smoothstep` is identical |
| Early-out | `if (mask <= 0.0) { gl_FragColor = orig; return; }` (`CE:389-392`) |

`0.15` is a hardcoded literal, deliberately not a uniform (`CE:352-353`).

### 2.6 Rounding of rects

**None.** Rects stay float-normalized end to end: interpolated as Float (`ED:169-174`), rotated as
Float (`CT:21-26`), uploaded as Float (`CE:229-237`), consumed as Float in the shader. There is no
pixel snapping, no `round()`, no even-alignment anywhere in the render path.

### 2.7 Region count clamp

`CE:182-187`:

```kotlin
val kept = if (regions.size <= MAX_REGIONS) regions
           else { Log.w(...); regions.sortedByDescending { it.width * it.height }.take(MAX_REGIONS) }
```

- `MAX_REGIONS = 8` (`CE:30`), matching `uniform vec4 uRegions[8]` (`CE:369`) and the shader loop
  bound `for (int i = 0; i < 8; i++)` (`CE:374`).
- Sort is **descending by normalized area**, keep the 8 largest — i.e. it **fails OPEN, dropping the
  smallest faces on exactly the frames with the most people in them**.
- Ordering: `toStoredSpace` is applied *before* the sort (`CE:176` then `CE:186`). Area is
  rotation-invariant so the outcome is the same either way.
- **This is compensated upstream, not in the shader.** `FilterWorker.overflowSpans` sweeps track
  lifetimes and promotes every instant where `> RENDERER_MAX_REGIONS` tracks are active into a
  whole-frame censor interval (`FW:1192-1215`, `RENDERER_MAX_REGIONS = 8` at `FW:1358`), so
  `regionsAt` returns empty there under full-frame precedence and the shader never sees an overflow.
  The sweep deliberately **over-counts** (a track with no keyframe near `t` still counts), which
  censors slightly more — the safe direction (`FW:1188-1190`).

---

## 3. Encoder settings

### 3.1 Resolution-tier bitrate cap table (exact)

`RP:275-281`. Input is `pixels = sourceWidth × sourceHeight` as a `Long`, taken from the **stored**
video track dimensions (never rotated, so wide/tall variants bin identically):

| Condition | Pixel bound | **Cap (bit/s)** |
|---|---:|---:|
| `pixels <= 854L * 480` | 409 920 | **4 000 000** |
| `pixels <= 1280L * 720` | 921 600 | **10 000 000** |
| `pixels <= 1920L * 1080` | 2 073 600 | **16 000 000** |
| `pixels <= 2560L * 1440` | 3 686 400 | **24 000 000** |
| else (4K and above) | — | **45 000 000** |

Comments record the intent: 16 Mbps "vs ~17-20 Mbps camera original", 45 Mbps "vs ~45-50 Mbps camera
original" (`RP:278`, `RP:280`).

### 3.2 Effective bitrate resolution

`RP:226-267`:

```
effective = sourceBitrate > 0 ? min( (sourceBitrate * 1.3f).toInt(), cap ) : cap
```

| Step | Detail | Source |
|---|---|---|
| `GEN2_HEADROOM` | **1.3f** — second-generation encode must also spend bits reproducing the source encoder's artifacts | `RP:64`, `RP:214-218` |
| Probe 1 | `MediaExtractor` → first `video/` track → `KEY_WIDTH`, `KEY_HEIGHT`, `KEY_BIT_RATE` (per-track, video only) | `RP:230-238` |
| Probe 2 (fallback) | `MediaMetadataRetriever` → `METADATA_KEY_VIDEO_WIDTH/HEIGHT` if `pixels == 0`; `METADATA_KEY_BITRATE` if track bitrate absent — **whole-file bitrate incl. audio, an accepted upper bound** | `RP:246-260` |
| Arithmetic | Float multiply, then `toInt()`. **Deliberate**: `it * 1.3` as Int overflows above ~165 Mbps, whereas `Float.toInt()` saturates to `Int.MAX_VALUE` and `min()` then picks the cap | `RP:264-266` |
| Absent-key handling | `getInteger` **throws** when a key is absent below API 29; wrapped in `intOrNull` | `RP:283-284` |
| Invariant | **One bitrate per job.** Hoisted out of the segment loop (`FW:683`) because `Remux.concat` writes one track format and because each call opens a container (35–70 opens per film otherwise) | `RP:219-224`, `FW:678-683` |

### 3.3 Everything the encoder is configured with

`RP:151-179`, plus media3 defaults that the app relies on without stating them.

| Setting | Shipped value | Set by | Source |
|---|---|---|---|
| Video codec | **H.264** (`MimeTypes.VIDEO_H264`) — requested **only when not passthrough** | app | `RP:176` |
| Codec fallback | **enabled** (`DefaultEncoderFactory.Builder.enableFallback` defaults `true`) — media3 may substitute a supported format | media3 default | `m3:DEF:78` |
| Bitrate | §3.2 | app | `RP:154` |
| Bitrate mode | **VBR** (`BITRATE_MODE_VBR`) | media3 default | `m3:VES:91`, `m3:DEF:353` |
| I-frame interval | **2.0 s** (media3's own default is 1.0 s — overridden because 1 s "spends a big slice of the budget on I-frames") | app | `RP:158`, `m3:VES:51` |
| `KEY_OPERATING_RATE` | **1000** | app | `RP:164` |
| `KEY_PRIORITY` | **1** (best-effort) | app | `RP:164` |
| B-frames | **not configured** — `maxBFrames` stays `NO_VALUE`, `enableCodecDbLite` defaults false, and media3's H.264 path explicitly "Don't configure B-frames, because it doesn't work on some devices" | media3 default | `m3:DEF:79`, `m3:DEF:435`, `m3:DEF:765` |
| Profile | **AVCProfileHigh** — the app requests none, so media3's `adjustMediaFormatForH264EncoderSettings` picks High on API ≥ 29 | media3 | `m3:DEF:751`, `m3:DEF:767` |
| Level | **highest supported for that profile on the device** (`EncoderUtil.findHighestSupportedEncodingLevel`) | media3 | `m3:DEF:761-770` |
| Colour format | `COLOR_FormatSurface` (SDR path) | media3 | `m3:DEF:389-391` |
| HDR | **`HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`, applied only when NOT passthrough.** Passthrough keeps the default `HDR_MODE_KEEP_HDR`, which is the only mode that does not force a transcode | app | `RP:138-141` |
| Frame rate | `KEY_FRAME_RATE = round(source frameRate)`; if the source reports none, media3 substitutes **30** | media3 | `m3:DEF:278-279`, `m3:DEF:355` |
| fps preservation | **PTS are passed through; no rate conversion, no frame drop/dup.** No `repeatPreviousFrameIntervalUs`, no speed provider | app (by omission) | `RP:126-136` |
| Resolution | **unchanged** | app | `CE:162` |
| Rotation into the encoder | media3 asserts `format.rotationDegrees == 0` at the encoder; rotation rides as container metadata or is already baked by the decoder | media3 | `m3:DEF:286` |
| Audio encoder settings | **untouched** (`AudioEncoderSettings.DEFAULT`) — this is what keeps `audioNeedsEncoding()` false and the audio transmux alive | media3 default | `m3:DEF:77`, `m3:TU:103` |
| Progress poll | 500 ms (`PROGRESS_POLL_MS`), progress coerced to `0..100` | app | `RP:58`, `RP:185` |
| Threading | Transformer build/start/poll/cancel **all on the main Looper**; cancel re-posted to main because it may arrive off-thread | app | `RP:169-170`, `RP:201-204` |

**Why `operatingRate=1000, priority=1` is pinned** — a latent-risk pin, not a perf change: media3's
`KEY_OPERATING_RATE` overflow workaround for **SM8550 (this exact SoC)** is guarded on
`SDK_INT 31..34` (`m3:DEF:711-718`), so from API 35 media3 reverts to requesting
`Integer.MAX_VALUE` on a chipset Google blacklisted for throwing at `configure` (`RP:159-164`,
`docs/video-performance-plan-v2.md:974-979`). **No Apple equivalent exists — drop it, do not emulate.**

### 3.4 Codec choice and fallback, stated as a contract

1. H.264 is requested for *every* re-encode, on *every* segment, because `Remux.concat` can write only
   one track format (`RP:172-174`).
2. H.265/HEVC encode is device-dependent on Android and was rejected up front — "fall back to H.264"
   (`docs/m0-spikes.md:13`).
3. **The bitrate cap is a ceiling, not a target** (`RP:272-274`). A low-bitrate source encodes low.
4. **Known hole**: if media3 finds the source cannot be transmuxed into MP4 (VP9/WebM), it transcodes
   anyway **at its own default bitrate, not the tier cap** — the tuned factory is `null` on the
   passthrough path (`RP:148-150`, ponytail note).
5. The shipped configuration is explicitly labelled **compatible mode, not preserve mode**, because it
   forces H.264 and OpenGL HDR→SDR (`docs/video-performance-overhaul-plan.md:504`).

---

## 4. Fast paths (transmux vs re-encode)

### 4.1 Video

The **only** video passthrough is the job-level guard (`RP:98-111`):

```kotlin
val passthrough = segment == null
    && edl.censorIntervalsMs.isEmpty()
    && edl.faceTracks.isEmpty()
```

When true, the app withholds **four** things, each of which independently forces a transcode:

| Withheld | Would force transcode via |
|---|---|
| the effect (`Effects.EMPTY`, not a no-op effect) | `shouldTranscodeVideo` ends `return !combinedEffects.isEmpty() && …` (`m3:TU:184-185`) |
| `setVideoMimeType(H264)` | requested mime ≠ input mime ⇒ true (`m3:TU:157-164`) |
| `setEncoderFactory(tuned)` | `videoNeedsEncoding()` = `!requestedVideoEncoderSettings.equals(DEFAULT)` (`m3:DEF:476-478`, `m3:TU:151-152`) |
| `setHdrMode(TONE_MAP…)` | `hdrMode != HDR_MODE_KEEP_HDR` ⇒ true (`m3:TU:154-155`) |

**A `CensorGlEffect` that happens to draw nothing still costs the full decode → GL → encode.** The
effect must be *absent*, not idle (`RP:99-104`).

Media3's own remaining conditions for a container copy (all must also hold, `m3:TU:140-186`):
one sequence, one item; muxer supports the input sample mime or its alternative; `pixelWidthHeightRatio == 1f`;
default speed provider.

**Job-level only. Never per segment** — even though on a film most 5-minute segments contain no
regions. A transmuxed segment carries the *source* codec configuration and a re-encoded one carries
the encoder's, and `MediaMuxer` exposes no way to put a second `stsd` entry in one track: a mixed
concat produces a file whose second sample entry is silently wrong (`RP:105-111`,
`docs/video-performance-plan-v2.md:817-826`).

### 4.2 Audio

`setRemoveAudio(segment != null || removeAudio)` (`RP:127`).

| Job shape | `removeAudio` | Audio handling | Source |
|---|---|---|---|
| **Censor-only, unsegmented** (`runCensorOnly`) | false | Audio has no processors and is not removed ⇒ media3 **transmuxes** it alongside the re-encoded video. This file **is** the published output; no mux step | `FW:707-720`, `FW:714-715`, `RP:123-127` |
| **Combined (music removal + censor)** | **true** | Render video-only. `Remux.mux` reads only the video track back out, so Transformer writing audio would be wasted | `FW:798`, `RP:71-77` |
| **Segmented** (`segment != null`) | forced true | **Per-segment AAC cannot be concatenated** (encoder frames do not align with arbitrary clip boundaries). One continuous audio track is muxed once at the end | `RP:66-70`, `FW:368-369` |
| **Music-only** | n/a — no render pass at all; the source **video track is copied sample-for-sample** by `Remux.mux` | | `FW:735-760` |

Cost of getting this wrong, measured: for an AAC source it is a wasted transmux of a few hundred MB;
for a non-AAC source (MKV/Opus, AC-3) media3 **cannot transmux at all**, so it is a full audio decode
+ AAC encode that is then discarded — **12.9 s per 193 s track, ~10 min on a film**
(`RP:72-77`, `docs/video-performance-plan-v2.md:781-786`).

Media3's audio transmux condition (`m3:TU:89-129`): no gaps, `audioNeedsEncoding()` false, no
requested audio mime, muxer supports the input mime, no slow-motion flatten, default speed, **and no
audio processors on either the item or the composition**.

Segmented-route audio source selection (`FW:424-435`, `RX:52-89`):

| `ConcatAudio` | Condition | Concat input |
|---|---|---|
| `COPY` | source audio mime ∈ `{audio/mp4a-latm, audio/3gpp, audio/amr-wb}` | source track copied verbatim, bit-identical (`RX:52`) |
| `TRANSCODE` | any other mime, **or unreadable** (never `NONE` — losing audio silently is the worse failure) | one AAC transcode up front, before the two long passes, written `.part` then renamed (`FW:393-405`) |
| `NONE` | source has no audio track | `Remux.concat(audio = null)`, video-only output |

---

## 5. Per-frame EDL application

### 5.1 Time base conversion

`CE:173`:

```kotlin
val tMs = presentationTimeUs / 1000 + timeOffsetMs
```

- EDL/analysis timeline is **milliseconds**; media3 presentation time is **microseconds**. Converted
  at this boundary and nowhere else.
- Integer division ⇒ truncation toward zero.
- `timeOffsetMs = segment?.startMs ?: 0L` (`RP:132`).
- **Why the offset exists:** media3 hands effects **clip-relative** timestamps —
  `ExoAssetLoaderVideoRenderer.java:185` computes
  `presentationTimeUs = decoderOutput.presentationTimeUs - streamStartPositionUs`, so a segment
  starting at 5 min sees its first frame as 0 (`CE:168-172`). Without the offset every segment past
  the first would censor the wrong moments.
- **Segment boundaries must stay whole milliseconds**: `presentationTimeUs/1000 + startMs` equals
  `floor(absoluteUs/1000)` only when the offset is a whole ms (`FW:484-486`).

Verified at pixel level: censoring 6000..8000 ms — a window lying only inside segment 1 — produced
blurred frames at 6.5 s and 7.5 s and sharp frames at 1.0/3.0/4.5/9.0 s
(`docs/long-film-plan.md:120`, probe at `SP:66-68`, `SP:110`).

### 5.2 Lookup order per frame

`CE:174-176`:

```kotlin
val full = edl.fullFrameAt(tMs)
val regions = if (full) emptyList() else edl.regionsAt(tMs).map { it.toStoredSpace(mapRotation) }
```

`fullFrameAt` (`ED:19-25`) — **linear scan**, inclusive on both ends:

```kotlin
for (i in censorIntervalsMs.indices) { val r = censorIntervalsMs[i]; if (tMs >= r.first && tMs <= r.last) return true }
return false
```

`regionsAt` (`ED:28-39`) — returns empty under full-frame precedence, else one rect per active track,
membership test **inclusive** `tMs >= tr.startMs && tMs <= tr.endMs`, allocating a lazily-created
`ArrayList(2)`.

`full` is computed **twice** per frame (`CE:174` and again inside `regionsAt` at `ED:29`) — harmless
duplication, but note the whole-frame path skips `regionsAt` entirely, which is why whole-frame mode
is *cheaper* on the CPU per frame (`docs/plan-whole-frame-blur.md:183-185`).

Intervals are merged and disjoint by construction (`mergeRanges`, `ED:100-118`), so the linear scan
stays short: a 155-min film's 3 362 tracks collapse to a few dozen ranges (`ED:135-145`).

### 5.3 Between sampled frames — rect interpolation

`ED:151-175`. Analysis produces keyframes at the sampling cadence; render runs at source fps. For a
render time `tMs` inside a track:

| Case | Result |
|---|---|
| `keyframes.isEmpty()` | `null` — track contributes nothing at this instant |
| `tMs <= firstKeyframe.time` | **first rect, held constant** (clamp) |
| `tMs >= lastKeyframe.time` | **last rect, held constant** (clamp) |
| otherwise | **linear interpolation** between the bracketing keyframes |

Bracket search is a binary search for the largest index with `time <= tMs` (`ED:158-165`):

```kotlin
var lo = 0; var hi = kf.size - 1
while (lo < hi) { val mid = (lo + hi + 1) ushr 1; if (kf[mid].first <= tMs) lo = mid else hi = mid - 1 }
```

Interpolation (`ED:166-174`), degenerate case first:

```kotlin
if (t1 <= t0) return r0
val f = (tMs - t0).toFloat() / (t1 - t0).toFloat()
NRect(r0.left   + (r1.left   - r0.left)   * f,
      r0.top    + (r1.top    - r0.top)    * f,
      r0.right  + (r1.right  - r0.right)  * f,
      r0.bottom + (r1.bottom - r0.bottom) * f)
```

Each of the four edges is interpolated **independently** — the rect can change aspect ratio between
keyframes. `f` is computed in Float, from Long differences.

A track is active over `[startMs, endMs]` even where it has no keyframe near `t`; the clamp above is
what fills those instants. That asymmetry is why `overflowSpans` over-counts (`FW:1188-1190`).

### 5.4 Whole-frame path

- Trigger: any merged interval in `censorIntervalsMs` contains `tMs`.
- Shader: `uCensorAll = 1`, `uRegionCount = 0` ⇒ `mask = 1.0` everywhere, the region loop exits at
  `i = 0`, and the whole frame is replaced by `base` at full strength (`CE:221`, `CE:373-374`).
- Blur passes still run — they were already whole-frame (`docs/plan-whole-frame-blur.md:177-180`).
- **Precedence is absolute**: `regionsAt` returns empty inside a full-frame span, enforced in *two*
  independent places (`ED:29` and `CE:176`).
- Whole-frame spans are a **step function** — no interpolation, hard on/off at the millisecond
  boundary. Anti-strobe is handled entirely at EDL build time, not in the renderer.

EDL-build constants that shape those spans (relevant because the renderer's smoothness depends on them):

| Constant | Value | Source | Why |
|---|---:|---|---|
| `BRIDGE_MS` | **400 ms** | `ED:97` | Gap under which two whole-frame spans merge rather than strobe |
| `MIN_FULL_MS` | **500 ms** | `ED:132` | Shortest whole-frame span kept, applied **after** the merge. Measured: run B produced 6 spans under 1 s, three of exactly 100 ms — a full-screen flash from an ML Kit false positive (a cardboard box). Run C: 0 spans under 500 ms, shortest 701 ms, shortest clear window 601 ms, coverage unchanged at 90.8 % (`docs/plan-whole-frame-blur.md` §6.3) |
| `RENDERER_MAX_REGIONS` | **8** | `FW:1358` | Must equal `CE:30`'s `MAX_REGIONS` |

Dropping a short span **costs no coverage** — `regionsAt` then returns that track's own rect, so the
face stays censored and only the flash goes (`ED:126-131`).

---

## 6. Rotation landmines and decoder-dependent behaviour

Every item here is a workaround the code already carries. Dropping any of them silently corrupts
output.

### 6.1 Effect input orientation is decoder-dependent
`CE:41-46`. **Observed BOTH on one S23**: rotation-90 input arrived pre-rotated upright with rotation
dropped from the muxer; rotation-270 arrived in stored orientation with the display matrix forwarded.
Resolved by comparing the received texture dims against the probed stored dims in `configure()`.
**Apple note:** with `AVAssetReaderTrackOutput` the pixel buffers are *always* stored-orientation and
rotation lives in `AVAssetTrack.preferredTransform` — deterministic. The sniffing heuristic can and
should be replaced by an unconditional `toStoredSpace(rotation)`, but that decision must be explicit
and tested for all four rotations, because it changes 180 behaviour (§6.3).

### 6.2 Square rotated source is undecidable
`CE:131-133`. When `meta.width == meta.height`, the dimension test cannot distinguish stored from
upright. The code **assumes stored** and logs a warning. A square 90/270 source that arrives upright
will have its rects rotated wrongly.

### 6.3 180° rotation is dimension-invisible — assumed pre-rotated, never QA'd
`CE:125-129`. `rotation % 180 == 0` short-circuits to `mapRotation = 0`, so a 180-rotated source is
assumed to arrive already upright. The code logs
`"180-rotated source: assuming pre-rotated frames"` and the comment says **"QA a real 180 asset in
M3"** — which the docs do not record as having happened. If the assumption is wrong, every rect on a
180 source is off by a point reflection. `NRect.toStoredSpace(180)` exists and is correct
(`CT:23`); it is simply never reached.

### 6.4 Clipped exports are rebased to zero and frame-accurate
`ExoAssetLoaderVideoRenderer.java:185` subtracts `streamStartPositionUs`; `:186` drops frames whose
rebased PTS went negative (`CE:168-172`, `RP:115-119`, `SP:37-42`). Measured by `SegmentConcatSpike`:
`rebased = b.firstPtsUs < 500_000L` (`SP:132`).

### 6.5 Segment cuts must land on sync samples
`CP:48-60`. media3 ends a clipped read at the first sample **in decode order** whose pts reaches the
clip end (`ClippingMediaPeriod.java:430`), so on any B-frame stream the frames that *display* before
the boundary but *decode* after it are never read: **1–3 per seam, 49 frames over 31 seams on a 2.6 h
film**. Snapping to the next sync sample loses **0** on both test assets (un-snapped loses 1 on
`test-video.mp4` and 6 on `women-music-3min-video.mp4`). The plan's alternative — snapping to the
middle of a frame interval — was simulated and **does not work** (1 and 4 frames lost).

Snap function (`FW:497-515`), one extractor for the whole plan:

```kotlin
ext.seekTo(ms * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
val t = ext.sampleTime
when {
    t < 0L          -> durationMs   // no sync sample at/after ms ⇒ collapse this and every later cut into the final segment
    t < ms * 1000   -> ms           // seek did not move forward (fragmented mp4 with no sidx) ⇒ nominal cut, lossy but segmented
    else            -> t / 1000     // µs → ms truncation lands AT or BEFORE the sync sample, which is what both ends of a seam need
}
```

Failure of the whole snap is **not** retried un-snapped: it gives up segmentation entirely
(`FW:516-523`), because `jobKey` does not encode the plan and a resume under a different plan would
place segments at wrong absolute times.

### 6.6 Segment plan constants

| Item | Value | Source |
|---|---:|---|
| `SEGMENT_MS` | **300 000 ms** (5 min) | `CP:37` |
| Segmentation threshold | `durationMs >= 30 min` (`Eta.CONFIRM_THRESHOLD_MS = 30L*60*1000`) | `CP:68`, `Eta.kt:27` |
| Also unsegmented when | `durationMs <= 0` or `durationMs <= segmentMs` | `CP:67`, `CP:69` |
| Segment count | `ceil(durationMs / segmentMs)` | `CP:70` |
| Cuts | `[0] + (1 until count).map { cutAtMs(i*segmentMs).coerceIn(0, durationMs) } + [durationMs]`, then `.distinct().sorted()` | `CP:74-78` |
| 0 and `durationMs` | **never snapped** — they are the film's own ends | `CP:71` |
| Per-export fixed cost | ~0.5–0.7 s (Transformer + encoder init); ~19 s over 31 segments on a 155-min film | `CP:28-31` |
| Segment file | `seg-%03d.mp4`, written as `.part` then renamed — a file under its final name *means* complete | `CP:108`, `FW:690-701` |
| Resume test | `segmentFile(dir, i).length() > 0` | `CP:111` |

### 6.7 Concat is verified end-to-end, not assumed
`SegmentConcatSpike` (`SP:59-175`) asks three questions with two exports:

| Check | Assertion | Source |
|---|---|---|
| CSD equality | `a.csd0 == b.csd0 && a.csd1 == b.csd1` — `MediaMuxer` accepts one format per track, so a mismatch means the tail cannot be decoded | `SP:137` |
| Geometry equality | `width`, `height`, `mime` all equal | `SP:138` |
| Rebasing | `b.firstPtsUs < 500_000L` | `SP:132` |
| Real decode | decode every frame of the joined file with a decoder configured from segment 0's CSD; `decoded.frames >= a.samples + b.samples - 2` and no error | `SP:162-164` |
| PTS monotonicity | **explicitly NOT asserted** — sample PTS are not monotonic on any stream with B-frames | `SP:48-49` |
| Probe read buffer | `ByteBuffer.allocate(1 shl 21)` = 2 MiB, **doubled on `IllegalArgumentException`** until a sample fits | `SP:206`, `SP:213-217` |
| Decode timeout | `dequeueOutputBuffer(info, 10_000L)` µs | `SP:286` |
| Probe params | `SEG_A_MS = 5 000`, `SEG_B_MS = 10 000`, censor window `6 000..8 000`, `blurAmount = 60`, `grayscale = false` | `SP:63-68`, `SP:119` |

### 6.8 Other decoder/container behaviours the code depends on

| Behaviour | Detail | Source |
|---|---|---|
| `MediaFormat.getInteger` throws on an absent key below API 29 | every read wrapped | `RP:283-284` |
| `KEY_FRAME_RATE` is Float on some devices, Integer on others | `try getFloat / catch ClassCastException → getInteger` | `FS:431-433` |
| fps is unreliable | `MediaFormat` reports **24** for 24000/1001 content on the reference device | `CP:59-60` |
| fps fallback | 30 when the track omits `KEY_FRAME_RATE` | `FS:86` |
| Display size vs coded size | crop keys are **inclusive**: `crop-right − crop-left + 1` | `FS:435-444` |
| Rotation source of truth | `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION` first, `KEY_ROTATION` as fallback, normalized to `[0,360)` | `FS:82-83` |
| Decoders cannot downscale their own output | Codec2 defines `raw.scaled-size` but `CCodecConfig` maps neither and `MediaFormat` has no key. **GPU downscale is the only option** — which is why the blur pass doubles as the downscaler | `docs/video-performance-plan-v2.md:964` |
| `MediaMuxer` past 4 GiB | switches to `co64` on its own — measured **4 831 840 641 bytes / 921 943 samples, readback intact, VERDICT=OK**. Observed, not contracted (one device, one API level) | `docs/long-film-plan.md:52` |
| Track lookup | first track whose mime **starts with** `"video/"` / `"audio/"`; no track selection, no extractor mutation | `TR:17-28` |

---

## 7. Apple-platform bindings required

| Android / media3 | Apple equivalent | Notes |
|---|---|---|
| `Transformer` + `EditedMediaItem` + `Composition` | `AVAssetReader` + `AVAssetWriter` loop, or `AVAssetExportSession` + `AVMutableVideoComposition` | The reader/writer loop is the closer match: it gives per-frame PTS, cancellation, and progress without a compositor contract |
| `GlEffect` / `BaseGlShaderProgram` | `AVVideoCompositing` with Metal, or a manual `CVPixelBuffer → MTLTexture` (`CVMetalTextureCache`) pass | `texturePoolCapacity = 3` maps to 3 in-flight drawable textures; it is an unmeasured experiment, so pick a depth and measure |
| GLES 2.0 `#version 100` shaders | MSL | `texture2D` → `texture.sample(sampler, uv)`; `mix`, `dot`, `smoothstep`, `clamp` are identical |
| `GL_CLAMP_TO_EDGE` + `GL_LINEAR` | `MTLSamplerAddressMode.clampToEdge`, `.linear` min/mag, no mip | §1.6 — this is load-bearing for blur at frame edges |
| FBO/viewport save+restore (`CE:190-195`) | separate `MTLRenderPassDescriptor` per pass | Metal has no global framebuffer state; the save/restore simply disappears |
| `glUniform4fv(loc, N, …)` into `vec4[8]` | a fixed 8-element `float4` array in a uniform buffer + a `regionCount` | Only the first N entries are meaningful; keep the `break` on count |
| `VideoEncoderSettings.bitrate` | `AVVideoAverageBitRateKey` | §3.2 |
| `setiFrameIntervalSeconds(2f)` | `AVVideoMaxKeyFrameIntervalDurationKey = 2.0` | Prefer the *duration* key over `AVVideoMaxKeyFrameIntervalKey` (frame count) so VFR sources behave |
| media3-picked `AVCProfileHigh` + highest level | `AVVideoProfileLevelKey = AVVideoProfileLevelH264HighAutoLevel` | `AutoLevel` is the analogue of "highest supported level" |
| "Don't configure B-frames" | `AVVideoAllowFrameReorderingKey = false` | Matches `m3:DEF:765` and keeps the concat's single-`stsd` assumption easy |
| `BITRATE_MODE_VBR` | `AVVideoAverageBitRateKey` without `AVVideoQualityKey` (VBR is the default) | Do not set `AVVideoExpectedSourceFrameRateKey` to anything other than the source fps |
| `KEY_OPERATING_RATE` / `KEY_PRIORITY` | **none — drop** | Android-only SM8550 workaround (§3.3) |
| `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` | tone map in the Metal shader, or `AVAssetWriterInput` output colour properties (BT.709 primaries / transfer / matrix) + a `VTPixelTransferSession` | The shipped behaviour is: **any censored job tone-maps HDR→SDR; a passthrough job keeps HDR** (`RP:138-141`). Preserve that asymmetry |
| `MediaExtractor` / `MediaMetadataRetriever` probe | `AVAsset` + `AVAssetTrack`: `naturalSize`, `preferredTransform`, `estimatedDataRate` (Float bit/s, per track), `nominalFrameRate`, `AVAsset.duration` | `estimatedDataRate` is the direct analogue of `KEY_BIT_RATE` |
| `MediaMuxer` concat with one track format | `AVAssetWriter` appending sample buffers with a per-part PTS offset, or `AVMutableComposition` | The single-format constraint is `MediaMuxer`'s; `AVAssetWriter` has the same one-format-per-track rule, so the "one bitrate, one codec per job" invariant carries over unchanged |
| `SEEK_TO_NEXT_SYNC` cut snapping | `AVAssetReaderTrackOutput` over a `CMTimeRange` + inspecting sync samples via `AVSampleCursor` (`stepByDecodeTime` / `presentationTimeStamp`) | The B-frame decode-order tail loss (§6.5) is a container property, not an Android one — re-verify on AVFoundation before assuming it is absent |
| `WorkManager` foreground worker | `BGProcessingTask` + a user-visible progress surface | Out of scope here; noted because the 6 h foreground-service cap shapes the checkpointing |

---

## 8. Constant index (single lookup table)

| Constant | Value | Location |
|---|---:|---|
| `MAX_RADIUS` | 10 | `CE:27` |
| `MAX_REGIONS` | 8 | `CE:30` |
| `RENDERER_MAX_REGIONS` | 8 | `FW:1358` |
| `texturePoolCapacity` | 3 | `CE:95` |
| σ scale factor | 40.0 (px at 1080p, blurAmount 100) | `CE:140` |
| σ reference short side | 1080 | `CE:140` |
| σ floor | 0.1 px | `CE:140` |
| downscale candidates | {1, 2, 4, 8} | `CE:142` |
| downscale σ target | ≤ 4.0 (low-res px) | `CE:142` |
| radius factor | 2.5 (`ceil`) | `CE:146` |
| kernel array length | 11 | `CE:119`, `CE:333` |
| blur loop bound | 10 (literal) | `CE:338` |
| composite loop bound | 8 (literal) | `CE:374` |
| feather fraction | 0.15 | `CE:382` |
| feather floor | 0.002 | `CE:382` |
| BT.709 luma | (0.2126, 0.7152, 0.0722) | `CE:400` |
| `FilterOps.BLUR` sentinel | 0 | `FO:105` |
| default `blurAmount` | 60 | `FO:47` |
| default `grayscale` | false | `FO:48` |
| default `strictness` | 40 | `FO:80` |
| `GEN2_HEADROOM` | 1.3f | `RP:64` |
| `PROGRESS_POLL_MS` | 500 | `RP:58` |
| i-frame interval | 2.0 s | `RP:158` |
| operatingRate / priority | 1000 / 1 | `RP:164` |
| bitrate tiers | 4 / 10 / 16 / 24 / 45 Mbps | `RP:275-281` |
| `BRIDGE_MS` | 400 ms | `ED:97` |
| `MIN_FULL_MS` | 500 ms | `ED:132` |
| `SEGMENT_MS` | 300 000 ms | `CP:37` |
| `CONFIRM_THRESHOLD_MS` | 1 800 000 ms | `Eta.kt:27` |
| muxable audio mimes | `audio/mp4a-latm`, `audio/3gpp`, `audio/amr-wb` | `RX:52` |
| media3 default i-frame interval (overridden) | 1.0 s | `m3:VES:51` |
| media3 default frame rate (fallback) | 30 | `m3:DEF:56` |

---

## 9. Open risks carried forward from the Android build

1. **180° rotation is unverified.** `CE:128` warns and assumes; no QA asset exercised it.
2. **Square rotated sources are ambiguous** and resolve to "stored" by fiat (`CE:132`).
3. **`texturePoolCapacity = 3` is an unmeasured experiment** with a known cost (~16 MB at 1080p,
   double under HDR) and an unknown gain (`CE:73-85`, `perf-plan-v4` A6 "NOT ESTIMABLE").
4. **HDR solid fills land darker than the swatch** — no linearization in `solidRgb` (`CE:60-66`).
5. **A non-MP4-muxable source (VP9/WebM) that reaches the passthrough branch transcodes at media3's
   own default bitrate**, bypassing the tier cap entirely (`RP:148-150`).
6. **`blurAmount=0` + `grayscale=false` + no solid** is a full transcode that changes nothing.
7. **Segmented sources were never exercised in whole-frame mode**
   (`docs/plan-whole-frame-blur.md` §6.5).
8. **`MIN_FULL_MS = 500` is one measurement deep** — read off six spans on one clip
   (`docs/plan-whole-frame-blur.md` §6.5).
9. **Fast-cut QA against `BRIDGE_MS = 400` was never run** — the reference asset is a vlog
   (`docs/plan-whole-frame-blur.md` §6.5).
10. **`MediaMuxer`'s co64 behaviour past 4 GiB is observed on one device/API, not contracted**
    (`docs/long-film-plan.md:52`). The AVFoundation equivalent must be re-verified independently.
