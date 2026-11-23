# spec-models.md — ML model contracts & ORT runtime configuration

Exact porting spec extracted from the shipped Android app
`/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter`.
Every constant below is quoted from real Kotlin/Python/ONNX with a `file:line` citation, or from a
`python3 -c onnx` dump of the actual asset bytes (§0). Nothing here is inferred.

Runtime: ONNX Runtime **1.27.0** (`gradle/libs.versions.toml:9`), Android AAR
`com.microsoft.onnxruntime:onnxruntime-android` (`app/build.gradle.kts:162`), ABI filter
**arm64-v8a only** (`app/build.gradle.kts:37`), `minSdk = 29` / `targetSdk = 36`
(`app/build.gradle.kts:16-17`).

---

## 0. Raw ONNX graph dump (verbatim)

Produced by `onnx==1.22.0` under `/Library/Frameworks/Python.framework/Versions/3.14/bin/python3`
against `app/src/main/assets/models/` on 2026-08-04. Reproduce with the script at
`/private/tmp/claude-501/.../scratchpad/dump_onnx.py`.

```
==============================================================================
FILE      : genderage.onnx
bytes     : 1322532
sha256    : 4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb
ir_version: 7
producer  : '' ''
domain    : '' model_version: 0
doc_string: ''
opset     : ai.onnx=12
metadata  : {}
graph.name: 'mxnet_converted_model'
INPUTS:
  - name='data' shape=[None,3,96,96] dtype=FLOAT
OUTPUTS:
  - name='fc1' shape=[1,3] dtype=FLOAT
nodes     : 102 | initializers: 161
op_counts : {'BatchNormalization': 31, 'Conv': 31, 'Relu': 31, 'Flatten': 2, 'Gemm': 2, 'GlobalAveragePool': 2, 'Concat': 1, 'Mul': 1, 'Sub': 1}
init_dtypes: {'FLOAT': 161}
==============================================================================
FILE      : nsfw_mnv2_140_f32.onnx
bytes     : 17319892
sha256    : 049ce7c51eaf3db429f0ffb22ba23345e7ec2483356432ea67e83446ed5cfe9e
ir_version: 8
producer  : 'tf2onnx' '1.16.1 15c810'
domain    : '' model_version: 0
doc_string: ''
opset     : ai.onnx=17, ai.onnx.ml=2
metadata  : {}
graph.name: 'tf2onnx'
INPUTS:
  - name='input' shape=[unk__615,3,224,224] dtype=FLOAT
OUTPUTS:
  - name='prediction' shape=[unk__616,5] dtype=FLOAT
nodes     : 104 | initializers: 111
op_counts : {'Conv': 52, 'Clip': 35, 'Add': 11, 'AveragePool': 1, 'MatMul': 1, 'Mul': 1, 'Softmax': 1, 'Squeeze': 1, 'Sub': 1}
init_dtypes: {'FLOAT': 110, 'INT64': 1}
==============================================================================
FILE      : nsfw_mnv2_140_int8.onnx
bytes     : 5105042
sha256    : 6070dd6da875025b4c8df960a3a46dff583fb2bf8a499e214f98a8984674bba9
ir_version: 8
producer  : 'onnx.quantize' '0.1.0'
domain    : '' model_version: 0
doc_string: ''
opset     : ai.onnx=17, ai.onnx.ml=2
metadata  : {'onnx.infer': 'onnxruntime.quant', 'onnx.quant.pre_process': 'onnxruntime.quant'}
graph.name: 'tf2onnx'
INPUTS:
  - name='input' shape=[unk__615,3,224,224] dtype=FLOAT
OUTPUTS:
  - name='prediction' shape=[unk__616,5] dtype=FLOAT
nodes     : 316 | initializers: 461
op_counts : {'DequantizeLinear': 177, 'QuantizeLinear': 70, 'Conv': 52, 'Add': 11, 'GlobalAveragePool': 1, 'MatMul': 1, 'Mul': 1, 'Softmax': 1, 'Squeeze': 1, 'Sub': 1}
init_dtypes: {'INT8': 179, 'FLOAT': 177, 'INT32': 104, 'INT64': 1}
==============================================================================
FILE      : htdemucs_s26_f16.onnx
bytes     : 87851483
sha256    : df8a2c2c8dd06ca279f58646dacc007b3e1e07436f31a3189408f0336c56eba5
ir_version: 10
producer  : 'pytorch' '2.13.0'
domain    : '' model_version: 0
doc_string: ''
opset     : ai.onnx=18
metadata  : {}
graph.name: 'main_graph'
INPUTS:
  - name='input' shape=[1,2,114660] dtype=FLOAT
  - name='x' shape=[1,4,2048,112] dtype=FLOAT
OUTPUTS:
  - name='out_spec' shape=[1,4,4,2048,112] dtype=FLOAT
  - name='out_wave' shape=[1,4,2,114660] dtype=FLOAT
nodes     : 1531 | initializers: 625
op_counts : {'Mul': 314, 'Reshape': 265, 'Add': 231, 'Transpose': 135, 'Conv': 92, 'InstanceNormalization': 74, 'Div': 60, 'Erf': 56, 'Split': 56, 'MatMul': 54, 'Sigmoid': 48, 'Gather': 27, 'LayerNormalization': 26, 'Unsqueeze': 18, 'Gemm': 10, 'Softmax': 10, 'Squeeze': 10, 'ConvTranspose': 8, 'Slice': 8, 'Cast': 6, 'Expand': 4, 'ReduceMean': 4, 'ScatterND': 4, 'Cos': 2, 'Sin': 2, 'Sqrt': 2, 'Sub': 2, 'Tile': 2, 'Concat': 1}
init_dtypes: {'FLOAT16': 552, 'INT64': 73}
==============================================================================
FILE      : yamnet.onnx
bytes     : 16093406
sha256    : afe82472f2f6250570b63d4f106e7a74b5232cfd17086d39076d80a4273d01f8
ir_version: 8
producer  : 'tf2onnx' '1.16.1 15c810'
domain    : '' model_version: 0
doc_string: ''
opset     : ai.onnx=15, ai.onnx.ml=2
metadata  : {}
graph.name: 'tf2onnx'
INPUTS:
  - name='waveform' shape=[15600] dtype=FLOAT
OUTPUTS:
  - name='output_0' shape=[1,521] dtype=FLOAT
nodes     : 152 | initializers: 91
op_counts : {'Conv': 27, 'Relu': 27, 'Cast': 11, 'Concat': 11, 'Reshape': 9, 'Add': 8, 'Unsqueeze': 8, 'Mul': 6, 'Sub': 5, 'Div': 4, 'Gather': 4, 'Max': 4, 'MatMul': 3, 'Shape': 3, 'Slice': 3, 'Squeeze': 3, 'Transpose': 3, 'Pad': 2, 'Pow': 2, 'Range': 2, 'Split': 2, 'Ceil': 1, 'GlobalAveragePool': 1, 'Log': 1, 'Sigmoid': 1, 'Sqrt': 1}
init_dtypes: {'FLOAT': 62, 'INT32': 20, 'INT64': 9}
==============================================================================
onnx lib version: 1.22.0
```

**All four sha256 values pinned in `Models.kt` match the bytes on disk exactly** (`Models.kt:83`,
`:106`, `:127`, `:168`). `nsfw_mnv2_140_f32.onnx` is not pinned anywhere in Kotlin — it has no enum
entry (§4).

---

## 1. Model inventory

| # | file | bytes | MB | sha256 | opset | producer | ir_ver | enum | live? |
|---|---|---:|---:|---|---|---|---|---|---|
| 1 | `nsfw_mnv2_140_int8.onnx` | 5,105,042 | 5.11 | `6070dd6d…4bba9` | ai.onnx=17, ai.onnx.ml=2 | `onnx.quantize 0.1.0` | 8 | `NaqiModel.NSFW_GATE` (`Models.kt:81-86`) | **YES** |
| 2 | `htdemucs_s26_f16.onnx` | 87,851,483 | 87.85 | `df8a2c2c…6eba5` | ai.onnx=18 | `pytorch 2.13.0` | 10 | `NaqiModel.HTDEMUCS` (`Models.kt:104-109`) | **YES** |
| 3 | `yamnet.onnx` | 16,093,406 | 16.09 | `afe82472…3d01f8` | ai.onnx=15, ai.onnx.ml=2 | `tf2onnx 1.16.1 15c810` | 8 | `NaqiModel.YAMNET` (`Models.kt:125-130`) | **YES** |
| 4 | `genderage.onnx` | 1,322,532 | 1.32 | `4fde69b1…52fb` | ai.onnx=12 | *(empty)* | 7 | `NaqiModel.GENDERAGE` (`Models.kt:166-171`) | **YES (conditional)** |
| 5 | `nsfw_mnv2_140_f32.onnx` | 17,319,892 | 17.32 | `049ce7c5…cfe9e` | ai.onnx=17, ai.onnx.ml=2 | `tf2onnx 1.16.1 15c810` | 8 | *(none)* | **NO — vestigial** |

Bundled total 127,692,355 B (127.69 MB). Runtime-live total 110,372,463 B (110.37 MB).
`app/src/main/assets/models/` is **gitignored** (`.gitignore:14`).

### 1.1 IO tensors, per model

#### NSFW_GATE — `nsfw_mnv2_140_int8.onnx`

| dir | name | graph shape | runtime shape fed | dtype | layout |
|---|---|---|---|---|---|
| in | `input` | `[unk__615,3,224,224]` | `[1,3,224,224]` (`Infer.kt:36`) | float32 | NCHW, RGB |
| out | `prediction` | `[unk__616,5]` | `[1,5]` | float32 | **softmax in-graph** (`Softmax` node present, §0) |

Output index semantics — `NSFW_CLASSES` (`Models.kt:175`), **alphabetical, index-locked**:

| index | class | role (`NsfwGate.kt:31-32`) |
|---:|---|---|
| 0 | `drawings` | SFW |
| 1 | `hentai` | NSFW |
| 2 | `neutral` | SFW |
| 3 | `porn` | NSFW |
| 4 | `sexy` | NSFW |

Consumer decision rule (`analysis/NsfwGate.kt:44-51`), strictness ∈ [0,100] linear interpolation
between the two columns (`NsfwGate.kt:20-26`):

| class | thr @ s=0 | thr @ s=100 |
|---|---:|---:|
| `porn` | 0.75 | 0.10 |
| `sexy` | 0.90 | 0.10 |
| `hentai` | 1.00 | 0.50 |
| `neutral` | 0.30 | 1.00 |
| `drawings` | 0.50 | 0.50 |

`fires ⇔ nsfwMax ≥ 0 AND nsfwMax > sfwMax`, where `nsfwMax = max_{porn,sexy,hentai}(p−thr)` and
`sfwMax = max_{neutral,drawings}(p−thr)`. Hysteresis `PRE_MS = 500`, `POST_MS = 1500`
(`NsfwGate.kt:13-14`); each firing at `t` censors `[t−500, t+1500]` ms, merged when
`s ≤ end + 1` (`NsfwGate.kt:66`), clamped to `[0, durationMs]`.

#### HTDEMUCS — `htdemucs_s26_f16.onnx`

| dir | name | shape | dtype | meaning |
|---|---|---|---|---|
| in | `input` | `[1,2,114660]` | float32 | mix waveform, stereo planar, 44 100 Hz, 2.6 s |
| in | `x` | `[1,4,2048,112]` | float32 | CaC spectrogram (see §2.2) |
| out | `out_spec` | `[1,4,4,2048,112]` | float32 | masked CaC spec, **4 stems × 4 CaC ch × 2048 bins × 112 frames** |
| out | `out_wave` | `[1,4,2,114660]` | float32 | time-branch waveform, 4 stems × 2 ch × SEG |

Weights are FLOAT16 (552 initializers), **IO is float32** — `keep_io_types=True`
(`scripts/htdemucs_post.py:33`).

Stem index order (`DemucsSeparator.kt:570-571`): **0 = drums, 1 = bass, 2 = other, 3 = vocals.**

Inputs and outputs are **matched by tensor RANK, not by name** (`DemucsSeparator.kt:670-678`,
`:688-698`): input rank 3 → wav, rank 4 → spec; output rank 5 → spec, rank 4 → time.

Geometry constants (`DemucsSeparator.kt:529-569`):

| const | value | derivation |
|---|---:|---|
| `SEG` | 114 660 | `int(2.6 s × 44 100)` |
| `STRIDE` | 103 194 | 10 % overlap; `check(STRIDE < SEG && SEG <= 2*STRIDE)` at `:142` |
| `MAX_SHIFT` | 22 050 | 0.5 s; deterministic `shift_offset = 0` draw |
| `BINS` | 2 048 | `NFFT/2` — Nyquist bin dropped |
| `LE` | 112 | `ceil(SEG / HOP)` |
| `STEM_SPEC` | 917 504 | `4 * BINS * LE` floats = 3.67 MB per stem |
| `NFFT` | 4 096 | private |
| `HOP` | 1 024 | private |
| `DILATE` | 2 | music-gate dilation, ±2 chunks (`:583`) |
| `LOOKAHEAD` | 206 388 | `DILATE * STRIDE` (`:584`) |
| `IN_CAP` | 435 708 | `2*SEG + LOOKAHEAD` (`:614`) |
| `OUT_CAP` | 217 854 | `SEG + STRIDE` (`:615`) |
| `GATE_RING` | 8 | scores kept for the ±DILATE window (`:612`) |
| `DILATE2_MIN_SCORE` | 0.02f | second dilation tier floor (`:609`) |

#### YAMNET — `yamnet.onnx`

| dir | name | shape | dtype | note |
|---|---|---|---|---|
| in | `waveform` | `[15600]` | float32 | **RANK 1, not `[1,15600]`** (`Models.kt:117`, `MusicGate.kt:117-118`) |
| out | `output_0` | `[1,521]` | float32 | AudioSet per-class scores, `yamnet_class_map.csv` order |

The class map CSV is **deliberately not shipped** (`Models.kt:118-119`). Only two index ranges are
used, **inclusive at both ends** (`MusicGate.kt:150`):

| range | meaning | verified anchors (`scripts/yamnet_export.py:69`) |
|---|---|---|
| `132..276` | the AudioSet music block, `Music` … `Scary music` | 132=`Music`, 276=`Scary music`, 277=`Wind` (excluded) |
| `24..32` | vocal music: Singing, Choir, Yodeling, Chant, Mantra, Child singing, Synthetic singing, Rapping, Humming | 24=`Singing`, 32=`Humming` |

Score = **max over both ranges** (`MusicGate.kt:125`), then max over tiled frames
(`MusicGate.kt:76-84`). `THRESHOLD = 0.15f` (`MusicGate.kt:164`), `SILENCE_PEAK = 0.001f`
(−60 dBFS peak, `MusicGate.kt:167`).

#### GENDERAGE — `genderage.onnx`

| dir | name | graph shape | runtime shape fed | dtype | layout |
|---|---|---|---|---|---|
| in | `data` | `[None,3,96,96]` | `[1,3,96,96]` (`Infer.kt:39`) | float32 | NCHW, RGB, **0..255 unscaled** |
| out | `fc1` | `[1,3]` | `[1,3]` | float32 | **raw logits, NOT softmax** |

Output index semantics (`Models.kt:142-145`, `Infer.kt:99`):

| index | meaning |
|---:|---|
| 0 | female logit |
| 1 | male logit |
| 2 | age ÷ 100 — real age is `out[2] × 100` (never consumed by the shipped app) |

Consumer (`FilterWorker.kt:978-980`): `p = 1 / (1 + exp(−(out[1] − out[0])))` = P(male);
`if (max(p, 1−p) < CONF_FLOOR) abstain(0) else if (p ≥ 0.5) male(+1) else female(−1)`.
`CONF_FLOOR = 0.60f` (`FilterWorker.kt:1346`). `VOTE_CAP = 5` classifications per track
(`FaceTracker.kt:231`); `MIN_FACE_PX = 80` upright px minimum side (`FaceTracker.kt:253`).
Tally rule (`FaceTracker.kt:302-305`): `WOMEN → censor unless maleVotes > femaleVotes`;
`MEN → censor unless femaleVotes > maleVotes`; `EVERYONE → censor`. **Abstention ⇒ censor.**

---

## 2. Preprocessing contracts

The two image models share a byte layout and differ **only in the 1/255 scale**. `Infer.kt:95-96`
calls this out explicitly: *"the gate above wants 1/255 on the same layout, so the two fills are NOT
interchangeable."* This is the single highest-risk copy-paste trap in the port.

| | NSFW_GATE | GENDERAGE | YAMNET | HTDEMUCS |
|---|---|---|---|---|
| tensor | `[1,3,224,224]` | `[1,3,96,96]` | `[15600]` | `[1,2,114660]` + `[1,4,2048,112]` |
| layout | NCHW planar | NCHW planar | rank-1 | planar / CaC |
| channel order | **RGB** | **RGB** | n/a | L,R planar |
| scale | **÷ 255.0** | **NONE (0..255)** | already in [−1,1] | raw float PCM |
| mean/std | none | `input_mean=0.0, input_std=1.0` | none | none |
| resize | **stretch to 224²** (non-uniform, aspect NOT preserved) | square crop then nearest to 96² | n/a | n/a |
| interpolation | **nearest-neighbour** | **nearest-neighbour** | n/a | n/a |
| crop | full source crop-rect | `max(boxW,boxH) × 1.5` **square** on box centre | n/a | n/a |
| pad | none | **clamp to edge pixel** (not black/reflect) | zero-pad tail | reflect (torch) |
| buffer | DIRECT, native byte order | DIRECT, native byte order | DIRECT, native byte order | DIRECT, native byte order |
| floats | 150 528 (602 112 B) | 27 648 (110 592 B) | 15 600 (62 400 B) | 229 320 + 917 504 |

### 2.1 NSFW_GATE preprocessing — exact algorithm

Source: `FrameSampler.convertToTensor` (`FrameSampler.kt:531-566`), split at runtime into
`gatherGate` (`:596-624`, producer thread) + `gateFromGathered` (`:642-656`, consumer thread).
A unit test pins the composition of the halves to be **bit-identical** to `convertToTensor`
(`FrameSampler.kt:523-529`). `convertToTensor` has **no production call site** and is retained as the
executable specification — port it as the reference and keep the split as an optimisation only.

1. `GATE_SIDE = 224` (`FrameSampler.kt:60`).
2. Build index maps over the decoder's **crop rect**, NOT the downscaled ML-Kit buffer
   (`FrameSampler.kt:399-400`):
   `gx[i] = crop.left + i * cropW / 224`, `gy[i] = crop.top + i * cropH / 224` (integer division).
   *Building these over the already-downscaled 640-px buffer was tried and measured 91.24 %
   censored-timeline recall against a ≥ 99.20 % bar — see §6.7.*
3. Rotation is applied inside the walk, mapping upright output → unrotated display coordinate
   (`FrameSampler.kt:544-549`):

   | rotation | dx | dy |
   |---:|---|---|
   | 0 (else) | `ox` | `oy` |
   | 90 | `oy` | `223 − ox` |
   | 180 | `223 − ox` | `223 − oy` |
   | 270 | `223 − oy` | `ox` |

4. `sx = gx[dx]`, `sy = gy[dy]`; chroma is 4:2:0 subsampled as `cx = sx shr 1`, `cy = sy shr 1`
   (`FrameSampler.kt:552-553`).
5. **Integer BT.601 full-range YUV→RGB, `shr 10`, clamped** (`FrameSampler.kt:554-559`) — reproduce
   *exactly*; the strictness table is QA-tuned against these numbers:
   ```
   y = Y                       (0..255)
   u = U − 128 ; v = V − 128
   r = clamp(y + ((1436*v) >> 10), 0, 255)
   g = clamp(y − ((352*u + 731*v) >> 10), 0, 255)
   b = clamp(y + ((1815*u) >> 10), 0, 255)
   ```
   `>>` is an **arithmetic** shift on signed Int (Kotlin `shr`).
6. Planar write, absolute indices (`FrameSampler.kt:560-563`), `plane = 224*224 = 50176`:
   `out[i] = r/255f`, `out[plane+i] = g/255f`, `out[2*plane+i] = b/255f`, `i = oy*224 + ox`.

Frame sampling that feeds it: `fps = 10f, maxDim = 640` (`FilterWorker.kt:606`, `:1082`),
`gateEvery = 2` default (`FrameSampler.kt:123`) ⇒ **the gate runs at 5 fps / 200 ms**; a 400 ms
sampling interval is discussed in `perf-plan-v4.md:108` as the post-A1 state.

### 2.2 HTDEMUCS preprocessing — the out-of-graph STFT

STFT/iSTFT live **outside** the ONNX graph (`Models.kt:88-95`). Implementation `audio/Dsp.kt`
class `Stft(nfft = 4096, hop = 1024)` (`Dsp.kt:124`), matching
`torch.stft(center=True, normalized=True, pad_mode="reflect", periodic hann)` plus the demucs
`_spec`/`_ispec` pad-and-slice (`Dsp.kt:111-116`).

| step | rule | citation |
|---|---|---|
| level-A pad | `padL = hop/2*3 = 1536`; `padR = padL + le*hop − T` (= 1564 at `T=114660`) | `Dsp.kt:244-245` |
| paddedSeg | `T + padL + padR = (le+3)*hop = 117 760` | `Dsp.kt:246` |
| center pad | additional `nfft/2 = 2048` each side, reflect | `Dsp.kt:176-177` |
| reflect mode | **torch reflect — mirror EXCLUDING the edge sample**, not the C++ off-by-one | `Dsp.kt:116`, `:233` |
| window | periodic Hann, length 4096 | `Dsp.kt:111` |
| frames computed | only `f ∈ [2, 2+le)` — frames sliced `[2 : 2+le]` | `Dsp.kt:184`, `:144` |
| normalisation | `1/sqrt(nfft)` (`fwdScale`) | `Dsp.kt:143`, `:193-194` |
| bins kept | `0 .. nfft/2 − 1` = 0..2047, **Nyquist dropped** | `Dsp.kt:192` |
| CaC flatten | `[4][bins][le]` C-order, **channel-major, real-before-imag**: `[ch0.re, ch0.im, ch1.re, ch1.im]` | `Dsp.kt:142-143`, `:179-180` |

Inverse (`Dsp.kt:162-172`): rebuild conjugate-symmetric spectrum with **Nyquist and the four boundary
frames zeroed**, overlap-add with the window, divide by the window sum-of-squares envelope, trim
center + level-A pad (`offset = nfft/2 + padL`, `Dsp.kt:227`).

Stems are assembled **outside** the graph: `stem = istft(sum of masked spec) + time branch`
(`Models.kt:95-96`, `DemucsSeparator.kt:411-425`). Overlap-add uses a triangle weight
`w[i] = min(i+1, SEG−i) / (SEG/2)` (`DemucsSeparator.kt:169-171`).
Non-finite outputs are counted and **silenced to 0f** (`DemucsSeparator.kt:269-274`) — retain this
guard; see §6.5.

Post-separation soft-clip guard, required by the encoder (`DemucsSeparator.kt:758-762`):
```
softclip(x) = x                                             if |x| ≤ 0.95
            = sign(x) * (0.95 + tanh((|x| − 0.95)/0.05) * 0.05)   otherwise
```

### 2.3 YAMNET preprocessing

| step | rule | citation |
|---|---|---|
| input | mono 44 100 Hz float | `MusicGate.kt:65` |
| resample | **naive linear interpolation** 441:160 → 16 000 Hz. Explicitly NOT soxr/Sonic; measured Δscore ≤ 0.13, flips one frame at a 0.5 threshold, zero flips at 0.15 | `MusicGate.kt:87-108` |
| `RATIO` | `44100.0 / 16000.0` | `MusicGate.kt:139` |
| output length | `out16kLength(frames) = ((frames − 1) / RATIO).toInt() + 1` | `MusicGate.kt:193` |
| silence short-circuit | peak `< 0.001f` ⇒ return 0f without any inference | `MusicGate.kt:72-74` |
| frame length | `FRAME = 15600` (0.975 s) | `MusicGate.kt:137` |
| tiling | stride `FRAME`, **last frame flush against the end**: `start = min(start + FRAME, n − FRAME)` | `MusicGate.kt:82` |
| tail pad | zero-fill when the window ends first | `MusicGate.kt:113-115` |
| early exit | stop as soon as `best ≥ THRESHOLD` (0.15) | `MusicGate.kt:81` |
| tensor rank | `longArrayOf(15600)` — **rank 1** | `MusicGate.kt:118` |

### 2.4 GENDERAGE preprocessing — exact algorithm

Source: `FrameSampler.cropToTensor` (`FrameSampler.kt:693-733`). Reads out of the **NV21 buffer ML
Kit was handed** (unrotated, `dispW × dispH`), not a bitmap.

1. `CROP_SIDE = 96` (`FrameSampler.kt:63`).
2. `rect` is the **raw (unpadded) ML Kit box** in upright-normalised [0,1] space, NOT the padded EDL
   rect (`FaceTracker.kt:217-219`).
3. **Square crop, InsightFace geometry** (`FrameSampler.kt:697-700`):
   ```
   half = max(rect.width * uprightW, rect.height * uprightH) * 1.5 / 2
   x0   = (rect.left + rect.right)/2 * uprightW − half
   y0   = (rect.top + rect.bottom)/2 * uprightH − half
   step = half * 2 / 96
   ```
   The `1.5×` factor matches `FaceTracker.KEYFRAME_PAD = 0.25f` (`FaceTracker.kt:270`), which grows
   each axis 25 % ⇒ 1.5× total — but the crop must be a **square**, not the EDL's per-axis pad
   (`FrameSampler.kt:674-677`).
4. Index maps clamp into the frame — **edge-pixel smear, not black/reflect padding**
   (`FrameSampler.kt:702-703`): `uxMap[i] = clamp(int(x0 + i*step), 0, uprightW−1)`, same for y.
5. Rotation table, upright → unrotated display (`FrameSampler.kt:714-719`):

   | rotation | dx | dy |
   |---:|---|---|
   | 0 (else) | `ux` | `uy` |
   | 90 | `uy` | `uprightW − 1 − ux` |
   | 180 | `uprightW − 1 − ux` | `uprightH − 1 − uy` |
   | 270 | `uprightH − 1 − uy` | `ux` |

6. NV21 addressing (`FrameSampler.kt:720-723`): luma at `dy*dispW + dx`; chroma base
   `dispW*dispH`, `ci = chromaBase + (dy shr 1)*dispW + (dx shr 1)*2`, **V at `ci`, U at `ci+1`**.
7. Same BT.601 integer coefficients as §2.1 step 5, then **write raw 0..255 floats**
   (`FrameSampler.kt:728-730`): `out[i] = r.toFloat()`, `out[plane+i] = g`, `out[2*plane+i] = b`,
   `plane = 96*96 = 9216`. **No division by 255.**

NV21 packing (`FrameSampler.packNv21`, `:478-503`), which the crop reads from: full luma plane of
`dispW*dispH`, then half-res **V,U** pairs; chroma taken at the 2×2 block's top-left source pixel
via `sx shr 1` / `sy shr 1`. `dispW`/`dispH` are rounded **down to even** with floor 2
(`FrameSampler.kt:359-360`).

---

## 3. ORT session configuration

Two distinct option sets. A model must never smoke-test under different options than it infers under
(`Models.kt:297-300`).

### 3.1 `imageSessionOptions()` — NSFW_GATE, GENDERAGE, YAMNET (`Models.kt:302-306`)

| setting | value | why |
|---|---|---|
| execution provider | **XNNPACK** via `addXnnpack(mapOf("intra_op_num_threads" to "4"))` | XNNPACK is NOT on by default in ORT-Android; registered explicitly (`m0-spikes.md:16`). XNNPACK registers `QLinearConv`, so the INT8 gate hits real integer kernels (`Models.kt:41-42`) |
| `setIntraOpNumThreads` | **1** | ORT's own XNNPACK EP guidance: XNNPACK owns the parallelism, ORT must not double-thread (`Models.kt:274-276`) |
| `session.intra_op.allow_spinning` | **"0"** | these run on a worker already saturating the CPU (`Models.kt:298-299`) |
| `XNNPACK_THREADS` | **4** | `Models.kt:294`; see sweep below |
| graph optimization level | **default (ORT_ENABLE_ALL)** — never set | no call to `setOptimizationLevel` anywhere in the repo |
| CPU arena | **default (on)** — never disabled here | only htdemucs disables it |
| memory pattern | **default (on)** — never disabled here | ditto |

XNNPACK thread sweep, off-device, this model class (`Models.kt:282-288`):

| XNNPACK threads | inferences/s |
|---:|---:|
| 1 | 20.1 |
| 2 | **47.8** |
| 4 | 42.3 |
| 8 *(the old `availableProcessors` value)* | 19.5 |

8 measured **2.4× worse than 2**. 4 is a compromise pending an on-device A/B; **2 / 4 / 6 still need
that A/B** (`Models.kt:288-289`). Mechanism: little cores become stragglers in every parallel conv.

**Do NOT batch** (`Models.kt:291-293`): batch 2 = 0.65×, batch 4 = 0.87×, batch 8 = 0.47× per frame
against batch 1. Depthwise-separable convnets saturate at batch 1 on CPU.

Rejected as a re-tune target: per-worker gate sessions / `XNNPACK_THREADS` retune is 100 %
consumer-side ⇒ **zero wall** (`perf-plan-v4.md:335`).

### 3.2 `HtdemucsSession.sessionOptions()` — HTDEMUCS only (`DemucsSeparator.kt:715-730`)

Explicitly **not** `imageSessionOptions` (`DemucsSeparator.kt:711-714`).

| setting | value | why (quoted) |
|---|---|---|
| execution provider | **CPU EP (multi-threaded), NOT XNNPACK** | *"XNNPACK's fp16 kernels corrupt this f16 graph's spectral branch on-device (broadband-noise stems; time branch survives) — same family of fp16 defects that already disqualified XNNPACK for the NSFW model in M0"* (`DemucsSeparator.kt:716-718`) |
| `setIntraOpNumThreads` | `min(availableProcessors, 6)` (`DemucsSeparator.kt:745`) | swept on S23 2026-07-28, median per-chunk ms over chunks 3–7: **8 → 2244 · 6 → 2136 · 4 → 2155**. Capped rather than hardcoded so a 4-core device still gets 4 (`DemucsSeparator.kt:738-742`) |
| `session.intra_op.allow_spinning` | **"0"** | swept: `0 → 2244`, `1 → 2305`. Chosen for power and is *also* faster (`DemucsSeparator.kt:738`, `:743`) |
| `setCPUArenaAllocator` | **false** | *"with the arena, each run's high-water stays resident across every chunk and Samsung's global memory watchdog kills the app; without it RSS drops back between chunks"* — lmkd killed the app at **5.6 GB RSS** without these two (`DemucsSeparator.kt:712-721`) |
| `setMemoryPatternOptimization` | **false** | same clause |
| `setOptimizedModelFilePath` | **implemented, measured, REMOVED** | see §6.6 |

Host sweep, session-count × intra-op threads, 8 chunks (`perf-plan-v4.md:243-249`):

```
1 session × 8 threads   699.1 ms/chunk   3.72× realtime
1 session × 4 threads   405.3 ms/chunk   6.41× realtime
4 sessions × 1 thread   398.6 ms/chunk   6.52× realtime
2 sessions × 4 threads  533.9 ms/chunk   4.87× realtime
```
Chunk-level parallelism buys **1.5 %**; two concurrent sessions double RSS toward the lmkd kill and
are *slower* than serial on the 49 % of chunk pairs containing a skip (`perf-plan-v4.md:251-254`).

**Untried knob, explicitly flagged:** `ExecutionMode.ORT_PARALLEL` + `setInterOpNumThreads`. htdemucs
is two parallel towers until the cross-domain transformer; inter-op exploits that at zero extra RSS
(`perf-plan-v4.md:169`). The intra-op sweep was run; this one never was.

### 3.3 Session lifecycle

| behaviour | rule | citation |
|---|---|---|
| creation | lazy, cached per-process in a `ConcurrentHashMap` keyed on `NaqiModel`, resolved with **`computeIfAbsent`, NOT `getOrPut`** — `getOrPut` is get-then-put with no locking, so racing callers each create a session and the loser leaks off-heap native memory | `Infer.kt:42`, `:123-131` |
| thread safety | `OrtSession.run` is thread-safe; `Infer.nsfw`/`genderAge` are safe concurrently **only if each caller owns its own input buffer** — the buffer is viewed in place, sharing corrupts the tensor silently | `Infer.kt:26-31` |
| `close()` | idempotent, job-teardown only; running it against a live inference closes a session under `run` | `Infer.kt:28-31`, `:117-121` |
| tensor caching | **deliberately not done.** `createTensor` over a DIRECT native-order buffer is a zero-copy *view*; caching would mean caching per-buffer (the sampler cycles a ring) and would pin ~2.4 MB direct memory per analyzed segment ⇒ ~100 MB on a film, to save one JNI call against a ~7 ms model run | `Infer.kt:68-79` |
| heap vs direct | `FloatBuffer.allocate` is a HEAP buffer and ORT **copies it into native memory on every run**. All production buffers are `ByteBuffer.allocateDirect(...).order(nativeOrder()).asFloatBuffer()` | `Infer.kt:18-19`, `MusicGate.kt:43-44`, `DemucsSeparator.kt:638-641`, `FilterWorker.kt:963-964` |
| htdemucs buffers | two DIRECT buffers allocated once and reused; heap arrays make ORT `allocateDirect` ~14 MB per call, and that non-movable churn OOMs ART's 256 MB heap mid-job | `DemucsSeparator.kt:623-626` |
| output arrays | `specOut`/`timeOut` are **reused across calls**; caller must consume before the next `infer` | `DemucsSeparator.kt:625-626`, `:636-637` |
| output compaction | only `keep` stems are read back out of ORT; the graph emits all four either way | `DemucsSeparator.kt:628-631` |
| genderage availability | memoized once per process (`genderAgeInstalled`); callers must ask **once before the pass**, not per crop | `Infer.kt:44-61` |
| model resolution | `ModelDownloader.installed(...) ?: extracted(...)` — whatever is already in `filesDir/models` wins | `Models.kt:268-269` |

### 3.4 `ModelSmoke` — startup validation (`Models.kt:186-271`)

Process-scoped `@Volatile cached` result (`Models.kt:192-195`); re-running creates three ORT sessions
including the 87 MB htdemucs, which the UI would otherwise do on every navigation back to the pick
screen.

| model | smoke action |
|---|---|
| HTDEMUCS | **load-only.** A full run peaks at multiple GB / ~9 s; running it concurrently with an M2 filter job got the process **lmkd-killed** (`Models.kt:213-216`) |
| all others | one zero-tensor inference; feeds matched to graph inputs **by rank**, name-agnostic, from `smokeShapes` (`Models.kt:221-227`) |

`smokeShapes` (`Models.kt:34`): NSFW `[[1,3,224,224]]`, HTDEMUCS `[[1,2,114660],[1,4,2048,112]]`,
YAMNET `[[15600]]`, GENDERAGE `[[1,3,96,96]]`.

**Port hazard:** the smoke opens *every* model — htdemucs included — under `imageSessionOptions()`
(XNNPACK) at `Models.kt:211`, even though the real htdemucs session uses the CPU EP for the fp16
corruption reason in §3.2. Load-only means no inference happens, but a Swift port that reuses the
smoke path for warm-up must not let XNNPACK execute this graph.

---

## 4. Live vs vestigial

| model | live at runtime? | evidence |
|---|---|---|
| `nsfw_mnv2_140_int8.onnx` | **LIVE.** `NaqiModel.NSFW_GATE` (`Models.kt:82`), called by `Infer.nsfw` (`Infer.kt:83-91`) | — |
| `nsfw_mnv2_140_f32.onnx` | **VESTIGIAL at runtime, load-bearing in the build.** No enum entry, never opened by any Kotlin. Kept as (a) the input to `scripts/nsfw_int8_quantize.py`, (b) the A/B baseline a recall regression is diffed against; the whole A/B is swapping `assetName` + `sha256` back | `Models.kt:72-75`, `scripts/fetch-models.sh:17-21` |
| `htdemucs_s26_f16.onnx` | **LIVE.** `HtdemucsSession` (`DemucsSeparator.kt:633-659`) | — |
| `yamnet.onnx` | **LIVE.** `MusicGate.open` (`MusicGate.kt:177-190`), 3 inferences/chunk. Fails **open**: null ⇒ separate every chunk, i.e. exact pre-A1 behaviour | `MusicGate.kt:171-176` |
| `genderage.onnx` | **LIVE, CONDITIONAL.** Session created only when `censorWho ∈ {WOMEN, MEN}` AND the file is installed; Everyone/Off allocate nothing | `FilterWorker.kt:955-961` |
| `nudenet_320n.onnx` | **DELETED.** AGPL-3.0 in a closed-source APK, and the gender vote it powered censored ~every face | `scripts/fetch-models.sh:7-10`, `Infer.kt:23-25` |

Also stale-in-docs but absent from code: `ModelSmoke.useNnapi` / the NNAPI-behind-a-flag path
(`m0-spikes.md:16`) **does not exist** in the current `Models.kt`. Do not port it.

---

## 5. Measured latencies

Device = Samsung Galaxy S23 (SM-S911U1, SD 8 Gen 2, API 36) unless marked *host*. Acceptance target
is an SD 778G-class device, which is **not** what any of these were measured on (`m0-spikes.md:60-61`).

### 5.1 Per-inference

| model | measurement | value | source |
|---|---|---:|---|
| NSFW fp32 | single inference, best-of-9 median-of-10, CPU EP, batch 1, *host* | **8.21 ms** | `Models.kt:44`; harness `nsfw_int8_quantize.py:257-270` |
| NSFW INT8 | same | **2.44 ms** (3.37×) | `Models.kt:44` |
| NSFW INT8 | `gate=` = `session.run` only, on-device, 643 s clip | **9.13 ms/gate-frame** (29 358 ms total) | `perf-plan-v3.md:33` |
| NSFW gate stage | fp32 whole-run `gate=` vs INT8 whole-run `gate=` on S23 | **61 745 ms → 26 844 ms (2.30×)** | `Models.kt:57-59` |
| NSFW smoke | first session create + one zero-tensor run | **84 ms → [1,5]** | `m0-spikes.md:25` |
| GENDERAGE | ms/crop **including the crop fill**, women / men modes | **4.87 / 5.01 ms** | `plan-censor-who.md:404-417` |
| GENDERAGE total | 281 crops = 1.4 s against a ~150 s analyze = **0.9 %** | | `plan-censor-who.md:417` |
| YAMNET | per inference, arm64 *host*, 3 inferences/chunk. **Device figure unmeasured.** | **0.9–1.2 ms** | `MusicGate.kt:21-22` |
| HTDEMUCS | 2.6 s chunk, *host*, 1 session × 4 threads | **405.3 ms/chunk (6.41× realtime)** | `perf-plan-v4.md:246` |
| HTDEMUCS | 2.6 s segment, device, 30 s clip | **1.33× realtime**, peak RSS **1.30 GB** | `m0-spikes.md:45` |
| HTDEMUCS | S23 sweep, median per-chunk over chunks 3–7 | **2136 ms** @ 6 threads | `DemucsSeparator.kt:738` |
| HTDEMUCS | session create | **881 ms** (0.23 % of a 385 420 ms `separate`) | `perf-plan-v4.md:447` |
| HTDEMUCS smoke | load-only | **409 ms load → [1,4,4,2048,112]** | `m0-spikes.md:25` |
| ML Kit detect | *(not ORT, for budget context)* | **1.59 ms/frame** | `perf-plan-v3.md:32` |

### 5.2 Stage totals (643 s source, S23)

Analyze, pre-A1 (`perf-plan-v3.md:26-36`):

| side | counter | total ms | per unit |
|---|---|---:|---:|
| producer | `nv21=` | 64 818 | 10.08 / frame |
| producer | `gateFill=` | 29 493 | 9.17 / gate frame |
| producer | subtotal | **94 311** | 82 % of wall |
| consumer | `detect=` | 10 209 | 1.59 / frame |
| consumer | `gate=` (`session.run`) | 29 358 | 9.13 / gate frame |
| consumer | subtotal | 39 567 | 35 % of wall |
| residual | | ~20 332 | |
| **analyze wall** | | **114 648** | |

Post-A1 split (`perf-plan-v4.md:493-494`, `:504`):
```
producer   nv21 60 677 + gateFill 27 638 = 88 315  ->  nv21-equiv 66 768 + gateGather 14 677 = 81 445
consumer   detect  9 427 + gate    27 245 = 36 672  ->  detect 9 969 + gateFill 39 634 + gate 25 446 = 75 049
```
Gather half costs **4.57 ms/gate-frame**. A1 measured **224 755 → 192 991 ms (−14.1 %)**
(`perf-plan-v4.md:96`).

Music-removal job (`perf-plan-v3.md:47-49`, `perf-plan-v4.md:413`):
```
separate = 449 376 ms   of which ORT session.run = 328 080 ms (73%), DSP = 10 597 ms (2.4%), residual = 110 699 ms (24.6%)
separate split:  stft=4079  ort=270177  istft+ola=4622  gate=7453  gather=105  flush=7953  encode=26584
```

---

## 6. Numerical-stability findings — every one, do not drop any

### 6.1 fp16 incident #1 — XNNPACK fp16 depthwise conv fails on the NSFW model
*"f16 was tried and rejected: XNNPACK's fp16 depthwise-conv path fails on-device
(`xnn_create_convolution2d_nhwc_fp16` error 2) — keep f32 weights."* (`m0-spikes.md:33`,
`tasks.md:12`.) The gate ships fp32-IO / INT8-weights, never fp16.

### 6.2 fp16 incident #2 — XNNPACK fp16 kernels corrupt the htdemucs spectral branch
*"XNNPACK's fp16 kernels corrupt this f16 graph's spectral branch on-device (broadband-noise stems;
time branch survives)."* (`DemucsSeparator.kt:716-718`.) This is why htdemucs runs the **CPU EP**.
**Any Apple EP change (Core ML / ANE / GPU) re-opens this exact failure mode and must be re-validated
on real audio, not on host SNR.**

### 6.3 fp16 incident #3 — NaN out of the separator
NaN/±Inf out of inference reached the AAC encoder, where *"`roundToInt` throws on it rather than
saturating, which turned one corrupt sample out of the separator into a lost multi-minute job."*
(`AacWriter.kt:87-89`.) Two guards now exist and both must be ported:
`DemucsSeparator.finite()` counts and zeroes (`DemucsSeparator.kt:269-274`), and `AacWriter` keeps a
boundary guard `if (v.isFinite()) (v*32767).roundToInt().coerceIn(-32768,32767) else 0`
(`AacWriter.kt:92-93`). `yamnet_export.py:22-24` cites this as one of the two reasons YAMNet is not
fp16 either.

### 6.4 The fp16 lever on htdemucs is measured-dead — the weights never run in fp16
(`perf-plan-v3.md:263-292`.) Verified against the shipped `.so`: ORT's CPU-EP fp16 island is
**Conv + Pool only**; htdemucs has **92 Conv and zero Pool**, and **no Conv is adjacent to another
Conv** — so every one of the 92 is an isolated fp16 node and `IsIsolatedFp16NodeOnCpu` converts each
back to fp32 at graph-optimization time. That is what the **201 `InsertedPrecisionFreeCast`** nodes
are; the file itself contains only 6 `Cast` nodes (confirmed in §0's dump: `'Cast': 6`).

Consequences a Swift port must inherit:
- **fp32 *is* the runtime behaviour** (`perf-plan-v4.md:190-191`). fp16 storage buys file size only.
- Cast overhead is 2.0 % of `separate` — under the 3 % bar. Shipping the fp32 graph instead costs
  +85 MB bundle and +85 MB storage for **zero** RSS/bandwidth improvement, and would invalidate the
  63.4/69.0 dB parity numbers (`perf-plan-v3.md:284-291`).
- **On Apple this changes.** ANE/GPU execute fp16 natively; a Core ML EP would *not* demote, so both
  the parity numbers and the RSS/latency model in this document become unverified.

### 6.5 INT8 htdemucs is **2× slower**, not faster — the gate's 2.30× does not transfer
Four quantizations of the shipped graph vs the fp32 baseline (`perf-plan-v4.md:193-198`):

| variant | speed | spec SNR | wave SNR | size |
|---|---:|---:|---:|---:|
| dynamic INT8, Conv + MatMul | **0.55×** | 2.8 dB | 23.6 dB | 61 MB |
| dynamic INT8, **Conv only** | **0.44×** | 2.2 dB | 23.6 dB | 146 MB |
| static QDQ, Conv + MatMul (noise calib) | 1.25× | 0.6 dB | 14.5 dB | 70 MB |
| dynamic INT8, **MatMul only** | **1.19×** | **56.7 dB** | **43.5 dB** | 88 MB |

*"`ConvInteger` is the poison"*: Conv/ConvTranspose is **47.6 % of the 91.96 GFLOP per 2.6 s
segment**; a kernel with no fused requantization and no fast ARM path makes the model slower than
fp32 *and* destroys the spectral branch (`perf-plan-v4.md:200-204`).
**MatMul-only is parked, not dead** — worth ~1.19× on 73 % of `separate` ≈ −52 s of a 449 s stage,
but 43.5 dB is a real step down from the device-verified 63.4/69.0 dB fp16 parity; gate it on a
real-audio A/B, not host SNR (`perf-plan-v4.md:206-212`).

### 6.6 Segment length 2.6 s is the **optimum**, not a RAM compromise — the dial is flat
The cross-domain transformer has 10 `Softmax` at sequence lengths 896 (waveform tower) and 448
(spectral tower), both linear in segment length ⇒ attention is **O(T²)** (`perf-plan-v4.md:216-218`).

FLOP census per 2.6 s segment (`perf-plan-v4.md:220-224`):
```
Conv / ConvTranspose   43.75 GFLOP   47.6%
FFN MatMul             38.76 GFLOP   42.1%
Attention               9.45 GFLOP   10.3%   <- quadratic in SEG
```

| SEG | GFLOP / s of audio | vs shipped | device peak RSS | device wall | vocals vs 7.8 s | f16 parity spec/wave |
|---|---:|---:|---:|---:|---:|---:|
| 1.3 s | 33.55 | −5.1 % | — | — | — | — |
| **2.6 s (shipped)** | **35.37** | — | **1.30 GB** | **1.33× RT** | **24.2 dB** | **63.4 / 69.0 dB** |
| 3.9 s | 37.19 | +5.1 % | 1.61 GB | 1.37× RT | 26.1 dB | 64.4 / 68.2 dB |
| 5.2 s | 39.01 | +10.3 % | — | — | — | — |
| 7.8 s (checkpoint native) | 42.64 | **+20.6 %** | 3.24 GB | 1.4–4.2× RT | reference | 61.5 / 65.9 dB |

Sources: `perf-plan-v4.md:226-238`, `m0-spikes.md:41-47`, `Models.kt:98-102`.
**Free RAM does not reopen this dial.** Changing `SEG` means re-export + new sha256 + new
`smokeShapes` + the `check(STRIDE < SEG && SEG <= 2*STRIDE)` (which **fails at 7.8 s**) + invalidation
of every saved `audio.json` (`perf-plan-v4.md:299`).

### 6.7 The gate tensor must be gathered from the **source** pixels, not the downscaled buffer
Refilling the gate tensor from the already-packed 640-px NV21 (perf-plan-v4 "A4") is 4 700 ms cheaper
and **under-censors** (`FrameSampler.kt:574-580`, `perf-plan-v4.md:466-471`):

| | A4 measured | bar |
|---|---:|---:|
| censored-timeline recall | **91.24 %** | ≥ 99.20 % |
| under-censored vs baseline | **34.5 s** of a 643 s clip | ~0 |
| net censored time | −15.1 s | ≥ 0 |
| `gateFirings` / `intervalCount` | 719 / 75 | 781 / 76 |

*"'nearest of nearest of 1920' lands on different source pixels than 'nearest of 1920', and the chroma
is subsampled at 640 rather than at source. Those pixels are gone; A4 cannot be made faithful. Do not
retry it."* Lowering the gate input resolution is the same trap measured separately: **71.7 %
agreement @192 vs 91.1 % for INT8@224** (`Models.kt:75-76`).

### 6.8 INT8 gate accuracy — the numbers that authorised the swap
(`Models.kt:39-71`.) Built by `scripts/nsfw_int8_quantize.py`:
AveragePool[7,7] → GlobalAveragePool (bit-exact, max|Δ| 0) → `quant_pre_process` →
`quantize_static(QDQ, QInt8/QInt8, per_channel=True)` calibrated on **100 real frames**.
ORT fuses to **75 nodes / 52 `QLinearConv`**.

| metric | value |
|---|---|
| off-device argmax agreement vs fp32, 360 real frames | **96.1 %** |
| censored timeline @ strictness 50, after hysteresis | strict **SUPERSET** of fp32's — 0 ms missed |
| worst point of the strictness sweep | 95.5 % interval recall @ strictness 0 |
| S23, 643 s source, fp32 | 867 firings, 400.7 s censored, `gate=61745 ms` |
| S23, 643 s source, INT8 | 921 firings, 416.9 s censored, `gate=26844 ms` |
| INT8 recall of fp32 censored timeline | **99.20 %** (32 of 4 010 sampled 100 ms points), censors **+16.2 s** |
| INT8 vs INT8 control, identical input | **100.00 % recall, +0.0 s** — run-to-run noise floor is zero |
| size | 17.3 → 5.1 MB |

Calibration/eval sampling (`nsfw_int8_quantize.py:47-51`): film split into `SEGMENTS = 10` chunks;
eval `offset 2 000 ms, 36 frames/seg, step 200 ms` = 360 frames; calibration
`offset 14 000 ms, 10 frames/seg, step 4 000 ms` = 100 frames — **disjoint by construction**.

### 6.9 Determinism boundaries — what is and is not diffable
(`Models.kt:62-67`, `perf-plan-v4.md:138`.) `intervals = intervalsFor(firings) + overflowSpans(faceTracks)`.
The face half comes from ML Kit, which is **NOT deterministic**: **4 786 vs 4 550 faces on identical
input** across two identical single-threaded runs. The censored *timeline* is nonetheless bit-stable.
**⇒ an EDL/timeline diff is a valid gate; a face-count diff is not.** The audio path *is*
deterministic — `.m4a` byte-identity is a valid gate (`perf-plan-v4.md:167`, `DemucsSeparator.kt:506`).

### 6.10 tf2onnx is not byte-reproducible
Two runs over the same SavedModel produce numerically identical graphs with **different serialized
bytes**, so re-exporting YAMNet changes its sha256 — take the new one from the script's output rather
than assuming a mismatch means a bad artifact (`Models.kt:120-123`, `yamnet_export.py:31-35`).
Affects `yamnet.onnx` and `nsfw_mnv2_140_f32.onnx` (both `producer: tf2onnx`).

### 6.11 Conversion parity references
| model | parity | source |
|---|---|---|
| NSFW fp32 vs TF SavedModel | max\|Δ\| = **3.6e-7**, argmax 8/8 | `m0-spikes.md:33` |
| htdemucs f32 vs torch, 7.8 s synthetic | **75 / 89 dB** (spec/wave) | `tasks.md:14` |
| htdemucs f16 vs torch, 7.8 s synthetic | **61.5 / 65.9 dB** | `tasks.md:14`, `m0-spikes.md:43` |
| htdemucs f16 @ 2.6 s | **63.4 / 69.0 dB** | `m0-spikes.md:45` |
| YAMNet trimmed vs upstream | parity-checked; dropped outputs are upstream of the scores | `yamnet_export.py:19-21` |

Note (`m0-spikes.md:47`): conversion parity is measured ONNX-vs-torch *at the same segment*, so it
**cannot see context loss** — the "vocals vs 7.8 s" column in §6.6 is the metric that can.

### 6.12 Gate policy is tuned against the exact integer colour arithmetic
`NsfwGate.TABLE`'s strictness thresholds are QA-tuned against the exact BT.601 integer coefficients,
`shr 10` and `coerceIn` included; the test asserts the split halves reproduce `convertToTensor`
**bit for bit rather than within a tolerance** (`FrameSampler.kt:633-636`). A float BT.601 matrix, a
different rounding, or vImage's YUV conversion is a **behaviour change**, not a refactor.

---

## 7. Download / staging strategy

### 7.1 Where files live

| stage | path | citation |
|---|---|---|
| build-time source | `app/src/main/assets/models/<assetName>` — **gitignored** | `Models.kt:17-18`, `.gitignore:14` |
| runtime path | `filesDir/models/<assetName>` (`mkdirs()` on access) | `ModelDownloader.kt:66` |
| download temp | `filesDir/models/<assetName>.part` | `ModelDownloader.kt:98` |
| asset-copy temp | `filesDir/models/<assetName>.tmp` | `Models.kt:252` |

**One directory, one file name per model.** A bundled-asset copy and an M3 download land at exactly
the same path, so `Infer` never has to know which source installed a file (`Models.kt:262-267`,
`ModelDownloader.kt:50-53`). Files are copied to a real path because **ORT wants a real file path**
(`Models.kt:246`).

Resolution order (`Models.kt:268-269`): `installed()` (length > 0, `ModelDownloader.kt:69-70`) →
`extracted()` (asset copy, `Models.kt:247-260`) → `null` ⇒ offer a download.

### 7.2 Download protocol (`ModelDownloader.kt:79-193`)

| aspect | rule |
|---|---|
| client | `HttpURLConnection` only — *"three GETs do not justify an HTTP client dependency"* (`:52`) |
| dispatcher | `Dispatchers.IO`; cancelling the coroutine stops the read loop and **keeps** the `.part` for resume (`:83`, `:176`) |
| buffer | `BUFFER = 64 * 1024` (`:60`) |
| connect timeout | **15 000 ms** (`:144`) |
| read timeout | **30 000 ms** (`:145`) |
| resume | offset = `part.length()`, sent as `Range: bytes=<have>-` (`:141`, `:146`) |
| 206 PARTIAL | range honoured, append (`:149`) |
| 200 OK | range ignored ⇒ `have = 0`, **overwrite** (`:150`) |
| 416 | delete the part, **recurse once**; with `have == 0` no Range is sent so 416 cannot recur (`:151-157`) |
| other codes | `DownloadError.HTTP`, detail `"HTTP <code> <message>"` (`:158-161`) |
| space check | fail up front if `dir.usableSpace < remaining + SPACE_MARGIN_BYTES`, `SPACE_MARGIN_BYTES = 64 MiB` (`:63`, `:167-169`) |
| progress | `onProgress(bytesDone, totalBytes)`; total = `-1` when the server sends no length (`:78`, `:165`) |
| short body | `total > 0 && done < total` ⇒ `OFFLINE "connection lost at N of M bytes"`, part kept (`:186-188`) |
| integrity | SHA-256 of the **whole part**, streamed in 64 kB chunks, compared `ignoreCase` — *"on a resume this process never saw the bytes already on disk, so only a full re-read can prove the file"* (`:102-109`, `:195-206`) |
| on mismatch | **delete the part** so corrupt bytes cannot wedge every later resume (`:106`, `:32`) |
| install | `part.renameTo(dest)` only after the hash passes — *"the path ORT loads never holds unverified bytes"* (`:54-56`, `:111-116`) |
| ENOSPC | has no dedicated exception on Android; detected by `e.message.contains("ENOSPC")` (`:120-122`) |

Error taxonomy (`ModelDownloader.kt:22-40`): `NO_SOURCE`, `OFFLINE`, `HTTP`, `HASH_MISMATCH`,
`NO_SPACE` (partial **kept** for resume), `IO`.

### 7.3 URL resolution — and why nothing is actually downloadable today

`sourceUrl` (`ModelDownloader.kt:213-218`): `model.downloadUrl` if non-null, else
`BuildConfig.NAQI_MODEL_BASE_URL` + (`/` if needed) + `assetName`; **empty base ⇒ null ⇒ `NO_SOURCE`**
rather than requesting `"null/htdemucs…"`.

**All four enum entries have `downloadUrl = null`** (`Models.kt:84`, `:107`, `:128`, `:169`) and
`NAQI_MODEL_BASE_URL` defaults to `""` (`app/build.gradle.kts:25`,
`project.findProperty("naqiModelBaseUrl") ?: ""`). ⇒ **In the shipped configuration the downloader
can never fetch anything; every model is a bundled APK asset.** The downloader is complete,
tested-by-construction infrastructure waiting on a host. Port it, but know that the bundled path is
the only live one — and that `perf-plan-v3.md:287` reasons about htdemucs specifically as *"bundled
APK assets (`downloadUrl = null` for HTDEMUCS)"*.

### 7.4 `scripts/` — how each artifact is produced

| script | produces | key mechanics |
|---|---|---|
| `fetch-models.sh` | orchestrator | `cd "$(dirname $0)/.."`, `DEST=app/src/main/assets/models` (`:11-13`) |
| ” | `nsfw_mnv2_140_f32.onnx`, `htdemucs_s26_f16.onnx` | **not fetchable** — prints `MISSING … regenerate per docs/m0-spikes.md` (`:19-21`) |
| ” | `nsfw_mnv2_140_int8.onnx` | runs `python3 scripts/nsfw_int8_quantize.py`, no-op if the fp32 input is missing (`:25-26`) |
| ” | `yamnet.onnx` | runs `python3 scripts/yamnet_export.py` (`:28`) |
| ” | `genderage.onnx` | `curl -fL` **buffalo_l.zip (289 MB)** from `github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip`, `unzip -o -j "$ZIP" buffalo_l/genderage.onnx -d "$DEST"`, `rm` the zip (`:33-40`) |
| ” | integrity | `shasum -a 256 -c` against the hardcoded `GENDERAGE_SHA`, **run even when the file was already present**; mismatch ⇒ `exit 1`, *"a silently different graph is worse than a missing one"* (`:41-46`) |
| `gantman_tf_convert.py` | `nsfw_f32.onnx` | **two-pass** `tf2onnx.convert --saved-model … --opset 17`: pass 1 discovers the graph input name, pass 2 re-runs with `--inputs-as-nchw` (`m0-spikes.md:33`, `:26-40`) |
| `nsfw_int8_quantize.py` | `nsfw_mnv2_140_int8.onnx` | `globalize_avgpool` → `quant_pre_process` → `quantize_static(QDQ, QInt8/QInt8, per_channel=True)`; then argmax agreement, best-of-9 median-of-10 latency, `NsfwGate.fires` flips, interval recall. Re-implements `NsfwGate` in numpy (`:53-100`) so the gate policy is checked, not just the tensor |
| `htdemucs_export.py` | `htdemucs.onnx` | `get_model("htdemucs")`, **`core.segment = segment`** before `training_length` is derived, `torch.onnx.export(..., opset_version=18, input_names=["input","x"], output_names=["out_spec","out_wave"])` (`:29-46`) |
| `htdemucs_post.py` | `htdemucs_f16.onnx` | `float16.convert_float_to_float16(m, keep_io_types=True)` (`:33`), then torch-vs-ORT parity on a deterministic synthetic signal (220 Hz + 554.37 Hz + vibrato 110 Hz + noise, `rng seed 0`), reporting rel / SNR dB / max\|Δ\| per output, matched **by ndim** (`:39-79`) |
| `yamnet_export.py` | `yamnet.onnx` | Kaggle SavedModel `google/yamnet/tensorFlow2/yamnet/1`, archive sha256 pinned `b80da2a1…e5e0` (`:55-56`); pins input to 15600, trims outputs to `output_0`, **no fp16**; asserts `CLASS_MAP_ANCHORS = {24:Singing, 32:Humming, 132:Music, 276:Scary music, 277:Wind}` on every run against the SavedModel's own CSV (`:62-69`). tf2onnx needs TF, which does not build on python3.14 ⇒ that one step shells out to `uv run --python 3.11` (`:28-29`) |

Licences: htdemucs MIT; GantMan NSFW `NOASSERTION — review`; YAMNet Apache-2.0; InsightFace
`genderage.onnx` — **licence question explicitly waived by the owner on 2026-08-03**
(`Models.kt:135-137`), but the **`NOTICE` omission is real and outstanding** — no InsightFace /
`genderage` entry (`perf-plan-v4.md:344`, `:360`). Carry this to the Apple bundle's acknowledgements.

---

## 8. Android-platform-bound items → Apple equivalents

| # | Android construct | citation | Apple equivalent / action | risk |
|---|---|---|---|---|
| 1 | `com.microsoft.onnxruntime:onnxruntime-android:1.27.0` | `libs.versions.toml:9` | `onnxruntime-objc` / `onnxruntime-c` pod, **or** Core ML. Version parity matters: 1.27.0 fixed 16 KB page alignment (Android-only concern) | med |
| 2 | **XNNPACK EP** via `addXnnpack(map)` | `Models.kt:305` | ORT-iOS default builds ship the **Core ML EP, not XNNPACK**. Either build ORT with `--use_xnnpack`, or re-benchmark the three image models on the CPU/Core ML EP. **The `QLinearConv` integer fast path that makes the INT8 gate 2.30× exists because XNNPACK registers it** (`Models.kt:41-42`) | **HIGH** |
| 3 | `addConfigEntry("session.intra_op.allow_spinning","0")` | `Models.kt:304`, `DemucsSeparator.kt:727` | `OrtSessionOptions` `AddConfigEntry` (C API); not exposed on the ObjC facade — reach through `ORTSessionOptions`' C handle | low |
| 4 | `setCPUArenaAllocator(false)` / `setMemoryPatternOptimization(false)` | `DemucsSeparator.kt:728-729` | C API `DisableCpuMemArena` / `DisableMemPattern`. **Mandatory**: the reason was a 5.6 GB RSS OOM-kill; iOS jetsam limits are *tighter* than Android lmkd | **HIGH** |
| 5 | `Runtime.getRuntime().availableProcessors().coerceAtMost(6)` | `DemucsSeparator.kt:745` | `ProcessInfo.processInfo.activeProcessorCount`, still capped at 6. The 6-beats-8 mechanism is *little-core stragglers* — Apple's E-cores reproduce it; consider `.processorCount` vs QoS-limited width and re-sweep | med |
| 6 | `context.assets.open("models/…")` | `Models.kt:251` | `Bundle.main.url(forResource:withExtension:)`, or On-Demand Resources for the 88 MB htdemucs. Note: ORT wants a **path**, and a Bundle resource already *is* one ⇒ the copy-to-filesDir step may be droppable for bundled models, but the download path still needs the writable dir | low |
| 7 | `context.filesDir/models` | `ModelDownloader.kt:66` | `FileManager.default.url(for: .applicationSupportDirectory, …)/models`, **`isExcludedFromBackup = true`** (110 MB of regenerable model weights must not go to iCloud) | med |
| 8 | `HttpURLConnection` + manual `Range:` resume | `ModelDownloader.kt:142-157` | `URLSession` background download task (native resume data), or keep the manual `.part` + `Range` scheme with `URLSession` data task. Manual scheme is closer to the audited invariants | low |
| 9 | `dir.usableSpace` | `ModelDownloader.kt:167` | `URL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])` | low |
| 10 | `MessageDigest.getInstance("SHA-256")` streaming | `ModelDownloader.kt:195-206` | `CryptoKit.SHA256` with `.update(data:)` over 64 kB chunks | low |
| 11 | ENOSPC sniffed from `e.message` | `ModelDownloader.kt:120-122` | `POSIXError.ENOSPC` / `NSFileWriteOutOfSpaceError` — a real typed error exists; this workaround is Android-only | low |
| 12 | `ByteBuffer.allocateDirect(...).order(nativeOrder())` zero-copy view | `Infer.kt:68-79` etc. | `ORTValue(tensorData: NSMutableData, …)` over a manually managed `UnsafeMutableRawPointer`. **The zero-copy property is load-bearing** (heap buffers cost a full copy per run, and 14 MB/call OOM'd ART) | med |
| 13 | ART 256 MB heap / lmkd / Samsung memory watchdog | `DemucsSeparator.kt:623-626`, `:719-721` | iOS **jetsam**. htdemucs peaks 1.30 GB at SEG 2.6 s — that is already near an iPhone foreground limit on 4 GB devices. **The single biggest port risk after §8.2** | **HIGH** |
| 14 | `OrtEnvironment.getAvailableProviders()` in the smoke | `Models.kt:199` | `ORTGetAvailableExecutionProviders` equivalent / hardcode; keep the *"DEVICE RUNTIME"* readout — it is the on-device proof that every model loads | low |
| 15 | ML Kit `FaceDetector` (`PERFORMANCE_MODE_FAST` + `enableTracking()`, `minFaceSize` left at the 0.1 default) | `FaceTracker.kt:203-207`, `perf-plan-v4.md:279-283` | Vision `VNDetectFaceRectanglesRequest` + `VNTrackObjectRequest`. **Out of this spec's scope**, but the `genderage` crop geometry (`1.5×` square, upright-normalised box) is defined against ML Kit's box convention and must be re-validated against Vision's | **HIGH** |
| 16 | `MediaCodec` `COLOR_FormatYUV420Flexible` plane walk, NV21 | `FrameSampler.kt:462-503` | `CVPixelBuffer` (`kCVPixelFormatType_420YpCbCr8BiPlanar*`). The **integer BT.601 full-range** arithmetic must be reproduced exactly (§6.12); do **not** substitute vImage/Accelerate YUV conversion without a bit-exactness test | **HIGH** |
| 17 | `BuildConfig.NAQI_MODEL_BASE_URL` from a Gradle property | `app/build.gradle.kts:25` | Info.plist key or an xcconfig-driven build setting | low |
| 18 | arm64-v8a-only ABI filter (universal AAR ships 4 ABIs ≈ 108 MB of `.so`) | `m0-spikes.md:17` | N/A — Apple is arm64 only. Do carry the equivalent framework-slice discipline | none |
| 19 | 16 KB page alignment (Play requirement, Android 15+) | `m0-spikes.md:18` | N/A | none |
| 20 | NNAPI behind a flag | `m0-spikes.md:16` | **Does not exist in the shipped code.** The Apple analogue would be the Core ML EP — treat as new work gated on §6.2/§6.4 re-validation | — |

---

## 9. Numbered porting contracts (the bit-for-bit list)

1. Feed NSFW_GATE `[1,3,224,224]` f32 NCHW **RGB ÷ 255**; read `[1,5]` **already-softmaxed** in
   `drawings, hentai, neutral, porn, sexy` order.
2. Feed GENDERAGE `[1,3,96,96]` f32 NCHW **RGB in 0..255, unscaled**; read `[1,3]` **raw logits**
   `[female, male, age/100]`. Never share the fill code with (1).
3. Feed YAMNET a **rank-1 `[15600]`** tensor of 16 kHz mono in [−1,1]; read `[1,521]`; score = max
   over indices `132..276` ∪ `24..32` inclusive; music iff ≥ **0.15**.
4. Feed HTDEMUCS `[1,2,114660]` f32 planar mix **and** `[1,4,2048,112]` f32 CaC spec
   (`ch0.re, ch0.im, ch1.re, ch1.im`, `1/sqrt(4096)`-normalised, Nyquist dropped, frames `[2:114]`);
   read `[1,4,4,2048,112]` + `[1,4,2,114660]`, stems `drums, bass, other, vocals`.
   Match inputs/outputs **by rank**.
5. Reproduce the integer BT.601 full-range coefficients `1436 / 352 / 731 / 1815`, `>> 10`,
   `clamp(0,255)` exactly — in both the 224² and 96² walks.
6. Nearest-neighbour everywhere. **Non-uniform stretch** to 224² over the full crop rect (aspect NOT
   preserved). **Square** `max(w,h)×1.5` crop for 96², edge-clamped.
7. Build gate index maps over the **decoder's crop rect at source resolution**, never over the
   downscaled 640-px buffer (§6.7).
8. htdemucs runs on the **CPU EP with the arena and memory-pattern planner OFF**, 6 intra-op threads
   (capped by core count), spinning off. Never XNNPACK (§6.2), never fp16 kernels (§6.4).
9. The three image models run **XNNPACK-equivalent, 1 ORT intra-op thread, spinning off, 4 EP
   threads**, batch 1 always (§3.1).
10. Never quantize htdemucs Conv/ConvTranspose (§6.5). Never raise `SEG` (§6.6).
11. Silence non-finite separator output and count it; keep the encoder-boundary guard too (§6.3).
12. Verify SHA-256 of the whole file before it reaches the path the runtime loads; delete the partial
    on mismatch (§7.2).
13. Sessions are process-cached, created at most once under a real lock, closed only at job teardown;
    every input buffer is caller-owned and zero-copy (§3.3).
14. Regression gate = **censored-timeline recall ≥ 99.20 %, differences only in the censors-more
    direction**. A face-count diff is not a valid gate; an `.m4a` byte diff is (§6.9).

---

## 10. Open / unverified, carried forward

| # | item | status |
|---|---|---|
| 1 | XNNPACK thread count **2 / 4 / 6 on-device A/B** | never run; the constant is `Models.kt:294` and *"this constant is the whole of the change needed to run it"* |
| 2 | YAMNet **device** latency | unmeasured; only a 0.9–1.2 ms arm64 host figure exists (`MusicGate.kt:22`) |
| 3 | htdemucs on an **SD 778G-class** device (the actual acceptance target) | blocked, never run — every number here is an S23 (`m0-spikes.md:54-55`, `:60-61`) |
| 4 | `ExecutionMode.ORT_PARALLEL` + inter-op threads on htdemucs | never swept (`perf-plan-v4.md:169`) |
| 5 | MatMul-only INT8 htdemucs (1.19×, 43.5 dB) | parked, gated on a real-audio A/B (`perf-plan-v4.md:206-212`) |
| 6 | SCNet-small as a htdemucs replacement (RTF 0.669 vs 1.38, +1.5 dB SDR, MIT code, **weights licence unverified**) | the only live replacement candidate (`perf-plan-v4.md:264`) |
| 7 | `NOTICE` entry for InsightFace / `genderage` | **missing**, one-line fix (`perf-plan-v4.md:344`, `:360`) |
| 8 | genderage balanced accuracy on real crops | never labelled; only the abstention rate (6.8 % / 3.5 %) and the selector-works ratio exist (`plan-censor-who.md:404`) |
| 9 | `yamnet_export.py:34` refers to `NaqiModel.MUSIC_GATE.sha256`; the enum constant is `YAMNET` | stale doc reference, harmless |
