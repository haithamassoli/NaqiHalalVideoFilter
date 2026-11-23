# Naqi — Apple port spec: the ANALYZE pass (pass 1)

Source of truth: the shipped Android build at
`/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter`, commit state of 2026-08-04.
Every number below is quoted from Kotlin with a `file:line` citation. Nothing here is inferred
unless it is explicitly marked **[INFERRED]** or **[UNKNOWN]**.

Paths are relative to `app/src/main/java/com/haithamassoli/naqi/`.

**What the analyze pass is:** one sequential decode of the source video that feeds two consumers —
an NSFW whole-frame gate and a face tracker — and emits one `Edl` (edit decision list) describing
every whole-frame censor interval and every per-face censor track. Pass 2 (render) consumes only
the `Edl`. Nothing else crosses the boundary.

---

## 0. Contract summary — the numbers a Swift engineer must not change

| # | Constant | Value | Citation |
|---|---|---|---|
| 0.1 | Sample rate (decode → emit) | `10f` fps | `analysis/FrameSampler.kt:121`, call sites `work/FilterWorker.kt:606,1082` |
| 0.2 | Gate consumption stride | every `2`nd emitted frame ⇒ 5 fps | `analysis/FrameSampler.kt:123` |
| 0.3 | ML Kit input long side | `640` px (`maxDim`) | `analysis/FrameSampler.kt:122`, call sites `:606,:1082` |
| 0.4 | NSFW gate tensor side | `224` (`GATE_SIDE`) | `analysis/FrameSampler.kt:60` |
| 0.5 | Gender crop tensor side | `96` (`CROP_SIDE`) | `analysis/FrameSampler.kt:63` |
| 0.6 | Frame ring depth | `RING = QUEUE + 2 = 4` | `analysis/FrameSampler.kt:67-68` |
| 0.7 | Producer→consumer queue depth | `QUEUE = 2` | `analysis/FrameSampler.kt:67` |
| 0.8 | Decoder dequeue timeout | `10_000L` µs | `analysis/FrameSampler.kt:57` |
| 0.9 | Hysteresis pre-roll | `PRE_MS = 500L` | `analysis/NsfwGate.kt:14` |
| 0.10 | Hysteresis post-roll | `POST_MS = 1500L` | `analysis/NsfwGate.kt:15` |
| 0.11 | Face-track span pad | `SPAN_PAD_MS = 50L` | `analysis/FaceTracker.kt:267` |
| 0.12 | Keyframe rect pad | `KEYFRAME_PAD = 0.25f` per side ⇒ 1.5× per axis | `analysis/FaceTracker.kt:270` |
| 0.13 | Track eviction gap | `EVICT_AFTER_MS = 2_000L` (source time) | `analysis/FaceTracker.kt:264` |
| 0.14 | Gender votes per track | `VOTE_CAP = 5` | `analysis/FaceTracker.kt:231` |
| 0.15 | Gender min face size | `MIN_FACE_PX = 80` (max side, upright px) | `analysis/FaceTracker.kt:253` |
| 0.16 | Gender confidence floor | `CONF_FLOOR = 0.60f` | `work/FilterWorker.kt:1346` |
| 0.17 | Whole-frame bridge gap | `BRIDGE_MS = 400L` | `edl/Edl.kt:97` |
| 0.18 | Whole-frame min duration | `MIN_FULL_MS = 500L` | `edl/Edl.kt:132` |
| 0.19 | Renderer max regions / overflow trigger | `8` | `work/FilterWorker.kt:1358`, must match `render/CensorEffect.kt:30` |
| 0.20 | Default strictness | `40` | `model/FilterOps.kt:80` |
| 0.21 | Segment length (long sources) | `5 * 60 * 1000` ms | `work/Checkpoint.kt:37` |
| 0.22 | "Long source" threshold | `30 * 60 * 1000` ms | `work/Eta.kt:27` |
| 0.23 | Concurrent-branch RAM floor | `6_656 MiB` | `work/FilterWorker.kt:1369` |

---

## 1. Frame sampling

### 1.1 Probe (metadata, no decode)

`FrameSampler.probe(context, uri): VideoMeta` — `analysis/FrameSampler.kt:74-92`.

| Field | Source | Fallback | Citation |
|---|---|---|---|
| `width` | `crop-right − crop-left + 1` if both crop keys present, else `KEY_WIDTH` | — | `:436-439` |
| `height` | `crop-bottom − crop-top + 1` if both crop keys present, else `KEY_HEIGHT` | — | `:441-444` |
| `rotationDegrees` | `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION` | track `KEY_ROTATION`, then `0`; then normalized `((r % 360) + 360) % 360` | `:82-83` |
| `durationMs` | MMR `METADATA_KEY_DURATION` (already ms) | track `KEY_DURATION` (µs) `/1000`, then `0L` | `:84-85` |
| `fps` | `KEY_FRAME_RATE` (Float, or Integer on some devices) | `30f` when the key is absent | `:86`, `:431-433` |

`width`/`height` are **pre-rotation (stored) dimensions** — `analysis/Contracts.kt:29-36`.
`fps` from the probe is **never used by the sampler**; the sample rate is the caller's `fps` argument.

The container is opened once per job in `work/FilterWorker.kt:200`, and `sample()` additionally
probes once itself for the rotation it hands the detector (`analysis/FrameSampler.kt:131`).

### 1.2 Decode loop

`FrameSampler.sample(context, uri, fps = 10f, maxDim = 640, gateEvery = 2, startMs = 0L, endMs = Long.MAX_VALUE, onFrame)`
— `analysis/FrameSampler.kt:118-319`.

Ordered contract:

1. `rotation = probe(...).rotationDegrees.let { if (it % 90 == 0) it else 0 }` — a rotation that is
   not a multiple of 90 degrades to 0 rather than throwing. `:131`
2. `gateStride = gateEvery.coerceAtLeast(1)` — 0 would divide by zero. `:132`
3. `slotIntervalUs = (1_000_000f / fps).toLong().coerceAtLeast(1L)` ⇒ **100 000 µs at 10 fps**. `:133`
4. `windowed = startMs > 0L || endMs != Long.MAX_VALUE`; `startUs = startMs * 1000`;
   `endUs = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE else endMs * 1000`. `:134-136`
5. Select the **first** track whose MIME starts with `"video/"`. `:194`
6. If `startMs > 0L`, `extractor.seekTo(startUs, SEEK_TO_PREVIOUS_SYNC)` — frames between the sync
   sample and `startMs` are decoded but never emitted. `:198`
7. Configure the decoder with `KEY_COLOR_FORMAT = COLOR_FormatYUV420Flexible`, **no surface**. `:201,:210`
   Do **not** request a concrete layout (see §9.1).
8. `nextSlotUs = if (windowed) startUs else Long.MIN_VALUE`. `:218`
9. Per output buffer:
   - `ptsUs = info.presentationTimeUs`
   - if `nextSlotUs == Long.MIN_VALUE && info.size > 0` then `nextSlotUs = ptsUs` (grid anchors to the
     first decoded frame). `:241`
   - if `info.size > 0 && ptsUs >= endUs`: release, **break**. Trusts the sample's own pts, not EOS,
     because decode order ≠ display order. `:244-247`
   - `render = info.size > 0 && ptsUs >= nextSlotUs`. `:248`
   - if `render`: `nextSlotUs += slotIntervalUs`; then `if (nextSlotUs <= ptsUs) nextSlotUs = ptsUs + slotIntervalUs`
     (resync after a gap). **This advance happens whether or not the image was obtained.** `:277-280`
   - release the output buffer **before** handing the frame to the consumer. `:281`
   - on a non-null frame: `frames.send(frame)`; `slot = (slot + 1) % RING`; `emitted++`. `:284-286`
10. `outputDone` when `BUFFER_FLAG_END_OF_STREAM`. `:297`
11. `outIndex < 0` (TRY_AGAIN / FORMAT_CHANGED / BUFFERS_CHANGED) ⇒ `continue`. `:238`
12. Cooperative cancellation is checked once per loop iteration (`coroutineContext.ensureActive()`). `:221`

**Gate cadence:** `wantGate = emitted % gateStride == 0`, evaluated **before** `emitted++`
(`:259` vs `:286`) ⇒ gate fires on emitted frames 0, 2, 4, … ⇒ exactly 5 fps at `fps=10, gateEvery=2`.

**Timestamp → EDL time:** `ptsMs = ptsUs / 1000` (integer division, truncation), computed exactly once,
at `analysis/FrameSampler.kt:258`. Every downstream time — firings, `FaceSample.ptsMs`, keyframe
times, `startMs`/`endMs` — is this value or arithmetic on it. **EDL time is absolute source
milliseconds, never segment-relative** (`work/Checkpoint.kt:87`).

### 1.3 Per-frame conversion (`toFrame`, `:343-413`)

```
crop  = image.cropRect                       // exclusive-right
cw    = crop.width();  ch = crop.height()
longest = max(cw, ch)
scale = if (longest > maxDim) maxDim / longest else 1f     // downscale only, never up   :356
dispW = max(2, round(cw * scale)) and ~1                   // round DOWN to even, floor 2 :359
dispH = max(2, round(ch * scale)) and ~1                   //                              :360
```

Two nearest-neighbour index maps, integer arithmetic, crop offset baked in (`:369-370`):

```
sxMap[i] = crop.left + i * cw / dispW     (i in 0..<dispW)
syMap[j] = crop.top  + j * ch / dispH     (j in 0..<dispH)
```

`nv21` is a **direct** buffer of `dispW * dispH * 3 / 2` bytes, reused from the ring when its
capacity matches, and `clear()`ed before each pack because the detector may have moved
position/limit (`:371-375`).

**Pixel format handed to the detector: NV21, UNROTATED, with the rotation passed as metadata**
(`:387`). This is load-bearing — see §1.4.

`packNv21` (`:478-503`) — every output byte is a source byte, no arithmetic:

```
for oy in 0..<h:  for ox in 0..<w:
    out[oy*w + ox] = Y[yBase + syMap[oy]*yRow + sxMap[ox]*yPix]
plane = w*h
for cy in 0..<h/2:
    sy = syMap[cy*2] >> 1
    for cx in 0..<w/2:
        sx = sxMap[cx*2] >> 1
        out[plane + cy*w + cx*2    ] = V[vBase + sy*vRow + sx*vPix]   // NV21: V first
        out[plane + cy*w + cx*2 + 1] = U[uBase + sy*uRow + sx*uPix]   // then U
```

Both chroma layouts a flexible decoder can produce are handled with **no branch** — semi-planar
(pixelStride 2, the Qualcomm NV12 case) and fully planar (pixelStride 1) differ only in the stride
values, which are parameters (`:466-470`).

### 1.4 Upright coordinate space — the one invariant that must not regress

`uprightSize(width, height, rotationDegrees)` — `analysis/FrameSampler.kt:457-458`:

```
if (((rot % 360) + 360) % 360) % 180 == 90  ->  (height, width)   else  (width, height)
```

The detector is given the **unrotated** buffer plus `rotationDegrees`; it rotates internally and
returns boxes in the **upright** space. `sample()` therefore hands the consumer `uprightW/uprightH`,
not the buffer's own dimensions (`:385`, `:410`). **Every `NRect` in the EDL is normalized against
this upright size** (`analysis/Contracts.kt:3-10`, `analysis/FaceTracker.kt:34-36`).

Pass 2 maps back to stored space with `NRect.toStoredSpace(rotationDegrees)`
(`analysis/Contracts.kt:21-26`) — quoted here only so the port keeps the two halves consistent:

| rotation | mapping |
|---|---|
| 90 | `NRect(top, 1-right, bottom, 1-left)` |
| 180 | `NRect(1-right, 1-bottom, 1-left, 1-top)` |
| 270 | `NRect(1-bottom, left, 1-top, right)` |
| else | identity |

### 1.5 Buffer lifetime (the rule that breaks a naive port)

Both buffers behind one `onFrame` call are **ring slots valid for that call only**
(`analysis/FrameSampler.kt:100-104`). The consumer must finish with them — including awaiting the
detector — before returning. The Android consumer awaits the detection Task inside the callback
(`work/FilterWorker.kt:626,1094`). Anything that retains a frame must copy.

---

## 2. NSFW gate

### 2.1 Model and IO

| Property | Value | Citation |
|---|---|---|
| Model | GantMan `nsfw_model` MobileNetV2 1.4-224, tf2onnx, transposed to NCHW, **statically quantized INT8 (QDQ, QInt8/QInt8, per-channel, 100 calibration frames)** | `ml/Models.kt:38-43` |
| Shipped file | `nsfw_mnv2_140_int8.onnx`, sha256 `6070dd6d…4bba9`, 5.1 MB | `ml/Models.kt:82-83` |
| fp32 alternative kept alongside | `nsfw_mnv2_140_f32.onnx`, 17.3 MB | `ml/Models.kt:73-75` |
| Input | `[1,3,224,224]` f32 NCHW **RGB, scaled 1/255, no mean/std** | `ml/Models.kt:78`, `ml/Infer.kt:36` |
| Output | `[1,5]` **softmax in-graph** | `ml/Models.kt:79` |
| Class order (index-locked) | `["drawings", "hentai", "neutral", "porn", "sexy"]` (upstream alphabetical) | `ml/Models.kt:175` |
| Session options | intra-op 1 thread, `session.intra_op.allow_spinning=0`, XNNPACK EP with `intra_op_num_threads=4` | `ml/Models.kt:294`, `:302-306` |

**INT8-vs-f32 selection logic: there is none at runtime.** The enum entry hardcodes one
`assetName` + `sha256`; swapping the model is an edit to `ml/Models.kt:82-83`, not a branch
(`ml/Models.kt:73-75`). Model resolution is: whatever file already sits in `filesDir/models/<assetName>`
wins (an earlier asset copy, or a download), otherwise the bundled asset is copied in, otherwise null
(`ml/Models.kt:268-269`).

Measured, S23, same 643 s source (`ml/Models.kt:55-58`):

| build | firings | censored | gate time |
|---|---:|---:|---:|
| fp32 | 867 | 400.7 s | 61 745 ms |
| INT8 | 921 | 416.9 s | 26 844 ms |

INT8 recall of the fp32 censored timeline = **99.20 %**, and it censors 16.2 s **more** — it errs
toward covering. Run-to-run noise floor of the censored timeline is 0 (two INT8 runs = 100.00 %),
even though ML Kit face counts are nondeterministic (4786 vs 4550) (`ml/Models.kt:59-67`).

### 2.2 Tensor fill — the exact pixel math (bit-exactness required)

The fill is split across two threads but is **bit-identical to the single-function reference**
`convertToTensor` (`analysis/FrameSampler.kt:531-566`), which is kept in the tree as the executable
specification and is pinned by an equivalence test at all four rotations and both chroma layouts
(`:523-529`).

**Producer half — `gatherGate` (`:596-624`)**, over the **crop rect, NOT the 640-px picture**
(`:397-400`):

```
gx[i] = crop.left + i * cw / 224      (i in 0..<224)
gy[j] = crop.top  + j * ch / 224
```

i.e. a full-frame **stretch** to 224², each axis with its own scale factor — exactly what
`Bitmap.createScaledBitmap(frame, 224, 224, true)` produced (`:394-398`, `:511-515`).

Rotation is applied **in this walk**, mapping upright output → unrotated display coordinate
(`:608-613`, identical table at `:544-549` and `:714-719`):

| rotation | dx | dy |
|---|---|---|
| 90 | `oy` | `side − 1 − ox` |
| 180 | `side − 1 − ox` | `side − 1 − oy` |
| 270 | `side − 1 − oy` | `ox` |
| else | `ox` | `oy` |

then `sx = gx[dx]`, `sy = gy[dy]`, `cx = sx >> 1`, `cy = sy >> 1` (4:2:0 subsampling **at source
resolution**), and three raw bytes are written per output pixel at `i = 3 * (oy*224 + ox)`:
`Y`, `U`, `V` (`:614-621`).

**Consumer half — `gateFromGathered` (`:642-656`)**, a flat loop, integer BT.601 **full-range**:

```
y = gathered[3i]   & 0xFF
u = (gathered[3i+1] & 0xFF) - 128
v = (gathered[3i+2] & 0xFF) - 128
r = clamp(y + ((1436*v) >> 10), 0, 255)
g = clamp(y - ((352*u + 731*v) >> 10), 0, 255)
b = clamp(y + ((1815*u) >> 10), 0, 255)
out[            i] = r / 255f          // R plane
out[   plane  + i] = g / 255f          // G plane
out[ 2*plane  + i] = b / 255f          // B plane      (plane = 224*224 = 50 176)
```

**`>>` is an ARITHMETIC shift on a signed Int** — for negative products this floors, which is *not*
integer division by 1024. Swift's `>>` on `Int32`/`Int` matches; `/1024` does not. The strictness
thresholds in §2.3 are QA-tuned against these exact numbers (`:517-518`, `:632-636`).

Buffer contract: `out` must be **direct, native byte order**, `3 * 224² = 150 528` floats
(602 112 bytes); all reads and writes are **absolute-indexed**, so no buffer position moves and the
ORT tensor view stays valid (`:509-511`, `:638-640`). One buffer is allocated per job, not per call
(`work/FilterWorker.kt:560-563`).

### 2.3 Strictness → per-class threshold

`NsfwGate.TABLE`, keyed by class **name**, `(thr at s=0, thr at s=100)` — `analysis/NsfwGate.kt:19-25`.
The PRD lists them in a different order; **never index by that order** (`:18`).

| class | index in `NSFW_CLASSES` | thr @ s=0 | thr @ s=100 | thr @ s=50 (check) | thr @ s=40 (default) |
|---|---:|---:|---:|---:|---:|
| `drawings` | 0 | 0.50 | 0.50 | 0.500 | 0.500 |
| `hentai` | 1 | 1.00 | 0.50 | 0.750 | 0.800 |
| `neutral` | 2 | 0.30 | 1.00 | 0.650 | 0.580 |
| `porn` | 3 | 0.75 | 0.10 | 0.425 | 0.490 |
| `sexy` | 4 | 0.90 | 0.10 | 0.500 | 0.580 |

The s=0 / s=100 / s=50 columns are asserted in `app/src/test/java/com/haithamassoli/naqi/analysis/NsfwGateTest.kt:15-33`.

Interpolation — `analysis/NsfwGate.kt:34-37`:

```
thr(c, s) = t0[c] + (t100[c] - t0[c]) * clamp(s, 0, 100) / 100f
```

`s` is clamped to `[0,100]` (`:35`); the division is **float** (`/ 100f`), the multiply happens
before it. Out-of-range strictness returns the endpoint (`NsfwGateTest.kt:36-39`).

### 2.4 Fire predicate

`NsfwGate.fires(probs: FloatArray, strictness: Int): Boolean` — `analysis/NsfwGate.kt:44-50`.

```
nsfwIdx = indices of { "porn", "sexy", "hentai" }        // = {3, 4, 1}   :30
sfwIdx  = indices of { "neutral", "drawings" }           // = {2, 0}      :31

nsfwMax = max over c in nsfwIdx of (probs[c] - thr(c, s))   , init -inf
sfwMax  = max over c in sfwIdx  of (probs[c] - thr(c, s))   , init -inf

fire  <=>  nsfwMax >= 0f  AND  nsfwMax > sfwMax
```

Notes that matter:
- The two scalars are **margins** (`p − thr`), not probabilities. `nsfwMax >= 0` is the "cleared its
  own threshold" test; `nsfwMax > sfwMax` is a **veto** by the strongest SFW margin.
- The veto is **strict** `>`, so an exact tie fires.
- At high strictness `neutral`'s threshold → 1.00, so its margin can never win: the veto disables
  itself by design (`:42-43`, and `NsfwGateTest.kt:52-58` pins both sides).
- A firing records `ptsMs` of that sampled frame into a flat list
  (`work/FilterWorker.kt:624`, `:1092`).

### 2.5 Where the gate runs relative to detection

Per consumed frame, in this order (`work/FilterWorker.kt:615-630`, identical at `:1085-1097`):

1. `tracker.detect(image)` → start detection, **do not await** (it runs on the detector's own executor).
2. If `gateBytes != null`: `gateFromGathered(gateBytes, gateInput)` → `Infer.nsfw(...)` →
   `NsfwGate.fires(probs, strictness)` → append `ptsMs` to firings.
3. `Tasks.await(task)` → faces.
4. `tracker.onFaces(faces, image, uprightW, uprightH, ptsMs)` (this is where the gender votes run).

Detection and the gate therefore **overlap**; the await must stay inside the callback so the ring
buffers survive both.

---

## 3. Hysteresis and interval merge

`NsfwGate.intervals(firingsMs: List<Long>, durationMs: Long): List<LongRange>` —
`analysis/NsfwGate.kt:56-75`. `LongRange` is **inclusive on both ends**.

```
if firings.isEmpty()   -> []                                            :57
sorted = firings.sorted()                                               :58   (input need not be sorted)
start = clamp(sorted[0] - 500,  0, durationMs)                          :60
end   = clamp(sorted[0] + 1500, 0, durationMs)                          :61
for i in 1..<sorted.size:
    s = clamp(sorted[i] - 500,  0, durationMs)
    e = clamp(sorted[i] + 1500, 0, durationMs)
    if s <= end + 1:               // overlapping OR gap-free adjacent   :65
        if e > end: end = e
    else:
        emit start..end;  start = s;  end = e
emit start..end                                                          :73
```

| Rule | Value |
|---|---|
| Expansion window | `[t − 500 ms, t + 1500 ms]` (2 000 ms wide before clamping) |
| Clamp | both endpoints into `[0, durationMs]`, **per firing, before merging** |
| Merge test | `s <= end + 1` — merges overlapping *and* exactly-touching spans (a 1 ms uncovered gap splits) |
| Ordering | input sorted ascending first; output is time-ordered and disjoint |
| Dedup | duplicate firings collapse naturally (`s <= end + 1` always true) |

Pinned edge cases (`NsfwGateTest.kt:74-99`): `[1000, 3001]` → one span `500..4501`;
`[1000, 3002]` → two spans `500..2500`, `2502..4502`; firing at 200 with duration 10 000 →
`0..1700`; firing at 9800 → `9300..10000`.

**Unknown duration:** the caller passes `Long.MAX_VALUE`, never `1`, when `durationMs <= 0`
(`work/FilterWorker.kt:1172`). This is a fixed bug ("correctness item 7.2", `:1151-1155`): clamping
the far end to a 1 ms duration collapsed every interval to `[0,1]` — the gate fired, the EDL said so,
and nothing was censored. **Do not reintroduce a `coerceAtLeast(1)` here.**

**Hysteresis runs ONCE over the whole timeline**, never per segment, because a censor interval that
straddles a segment seam must stay one interval — this is why per-segment checkpoints persist raw
firings rather than intervals (`work/FilterWorker.kt:566-568`, `:657-660`).

---

## 4. Face tracking

### 4.1 Detector configuration

`analysis/FaceTracker.kt:203-208`:

```kotlin
FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .enableTracking()
        .build(),
)
```

| Option | Value | Note |
|---|---|---|
| Performance mode | `PERFORMANCE_MODE_FAST` | explicitly set, `:205` |
| Tracking | **enabled** | `:206` — supplies the tracking ids the whole design rests on |
| Landmarks | not set ⇒ ML Kit default `LANDMARK_MODE_NONE` | deliberately: `LANDMARK_MODE_ALL` would tax detection on every frame, a cost never budgeted (`ml/Models.kt:153-157`, `docs/plan-censor-who.md:166-168`) |
| Contours | not set ⇒ default `CONTOUR_MODE_NONE` | — |
| Classification | not set ⇒ default `CLASSIFICATION_MODE_NONE` | no smile/eyes-open; gender is a **separate ONNX model**, ML Kit has no gender output (`docs/plan-censor-who.md:155-156`) |
| Min face size | not set ⇒ ML Kit default `0.1f` (fraction of the shorter image side) | **[INFERRED from ML Kit defaults — no explicit call in the Kotlin]** |
| Library | `com.google.mlkit:face-detection:16.1.7` (bundled model) | `gradle/libs.versions.toml:12,33` |

The detector is created lazily and **kept across frames** — tracking ids persist only within one
detector instance (`:59-60`, `:203`). It is closed per segment (`work/FilterWorker.kt:654`) /
per job (`:816`), which resets the id space.

Threading: **not thread-safe.** One frame's `detect` → `onFaces` must complete before the next
frame's starts, and neither may overlap `closeDetector()` (`:43-46`).

### 4.2 Track id management

`analysis/FaceTracker.kt:114-157`.

| Case | Behaviour | Citation |
|---|---|---|
| ML Kit supplied a tracking id | key the track by it (positive, monotonically increasing) | `:125,:129` |
| ML Kit supplied **no** id | `untrackedCount++` and assign a **synthetic id counting DOWN from −1** (`nextSyntheticId--`), so it can never collide | `:66`, `:125` |
| Id already live | append a sample to the existing track | `:129` |
| Id reused after eviction | starts a **fresh** track ⇒ two EDL spans, each covering its own samples | `:126-128` |

The predecessor code was `val id = face.trackingId ?: continue`, which **silently dropped**
untracked detections — never blurred, and content-correlated (id assignment fails exactly at scene
cuts and fast pans, and the app samples 10 fps out of 23.976 ≈ 2.4× the per-step motion the tracker
was built for). Each untracked detection is now its own one-frame track and is censored like any
other (`:119-124`). **Do not restore the drop.**

Live tracks are held in a `LinkedHashMap<Int, FaceTrack>` (insertion-ordered) — `:54`.

### 4.3 Sample recording and the 25 % padding

Per detected face (`:127-131`):

```
box  = face.boundingBox                      // pixels, UPRIGHT space
rect = NRect(box.left / uprightW, box.top / uprightH, box.right / uprightW, box.bottom / uprightH)
track.samples += FaceSample(ptsMs, rect)
```

The stored sample rect is the **raw, unpadded** normalized box. Padding is applied **later**, once,
when the track is turned into an EDL span (`:287` → `padRect`).

`padRect` — `analysis/FaceTracker.kt:313-322`:

```
dx = r.width  * 0.25f          // width  = right - left    (Contracts.kt:12)
dy = r.height * 0.25f          // height = bottom - top    (Contracts.kt:13)
NRect( clamp(r.left  - dx, 0, 1),
       clamp(r.top   - dy, 0, 1),
       clamp(r.right + dx, 0, 1),
       clamp(r.bottom+ dy, 0, 1) )
```

Answering the exact question:
- **Which dimension:** *both*, independently — each axis grows by 25 % of **its own** extent
  (`dx` from width, `dy` from height), i.e. 1.5× per axis, and a non-square box stays non-square.
- **Before or after clamping:** the pad is computed from the **unclamped** rect, then each edge is
  clamped to `[0,1]` **after** the pad is added. So a face at the frame edge gets an asymmetric
  final rect (one side clipped, the other still padded); the deltas are *not* redistributed.
- **Normalized space**, not pixels — the padding is applied in upright-normalized `[0,1]` coordinates.
- Pinned by `FaceTrackerLogicTest.kt:54-61`: `(0.4,0.4,0.6,0.6)` → `(0.35,…,0.65)`;
  `(0,0,1,1)` → unchanged.

### 4.4 Span construction

`edlFor(track, who)` — `analysis/FaceTracker.kt:281-289`:

```
if track.samples.isEmpty()                          -> null      (defensive; unreachable via FaceTracker)
if !shouldCensor(femaleVotes, maleVotes, who)       -> null      (the whole Women/Men feature)
FaceTrackEdl(
  startMs   = max(0, samples.first().ptsMs - 50),
  endMs     = samples.last().ptsMs + 50,            // NOT clamped to duration
  keyframes = samples.map { it.ptsMs to padRect(it.rect) },
)
```

`SPAN_PAD_MS = 50` is exactly half the 100 ms sample gap at 10 fps, so between-sample frames stay
covered at a span's edges (`:266-267`). A one-sample track therefore spans 100 ms
(`FaceTrackerLogicTest.kt:32-37`).

### 4.5 Box interpolation back to full frame rate

Done at **consume** time, not at analyze time — `edl/Edl.kt:151-175` (`FaceTrackEdl.rectAt`):

1. no keyframes ⇒ `null`
2. `tMs <= keyframes.first().time` ⇒ first rect (clamp, no extrapolation)
3. `tMs >= keyframes.last().time` ⇒ last rect
4. otherwise binary search for the largest index `lo` with `kf[lo].time <= tMs`
   (`mid = (lo + hi + 1) ushr 1`), then **component-wise linear interpolation** between `kf[lo]`
   and `kf[lo+1]` with `f = (tMs − t0) / (t1 − t0)` in Float; degenerate `t1 <= t0` ⇒ `r0`.

So the EDL stores rects at 10 fps and the renderer lerps to whatever the output frame rate is.

### 4.6 Sweep / eviction / finish

| Step | Behaviour | Citation |
|---|---|---|
| `sweep(nowMs)` runs **after every frame's faces are processed**, with `nowMs = that frame's ptsMs` | `:156`, `:163-174` |
| Staleness test | `isStale(lastSeen, now) = now - lastSeen >= 2_000` — **source time, not wall clock** | `:310`, pinned `FaceTrackerLogicTest.kt:64-72` |
| "Last seen" | `track.samples.last().ptsMs` — no separate field; a live track always holds ≥1 sample | `:167-169` |
| On stale | `emit(track)` then remove from the live map | `:170-171` |
| `emit` | `edlFor(track, who)`; non-null ⇒ append to `emittedEdls`; null **and** samples non-empty ⇒ `sparedCount++` | `:180-184` |
| `finish()` | emit every still-live track, `tracks.clear()`, return `emittedEdls.sortedBy { it.startMs }` (a copy) | `:187-195` |

Emission order is track-**end** order; `finish()` re-sorts by `startMs` purely so `Edl.toJson()`
stays diffable against the verified M1 runs — rendered pixels do not depend on it, since
`Edl.regionsAt` unions the active rects (`:191-194`).

Counters exposed for the soak log (`:75-97`): `trackCount` (cumulative), `faceCount` (raw
detections), `untrackedCount`, live size, `sparedCount`.

---

## 5. Gender vote

Active only when `censorWho ∈ {women, men}` **and** `genderage.onnx` is installed; otherwise the
classifier is `null` and the whole feature costs nothing — no crop, no tensor, no ORT call
(`analysis/FaceTracker.kt:32-33,:137`, `work/FilterWorker.kt:955-961`).

### 5.1 Which crops get a vote ("what makes a crop frontal")

There is **no explicit frontality test** — no landmarks, no pose, no head-angle threshold. "Frontal"
is approximated purely by **size ordering within the track**. The guards, in the exact order they
are evaluated (cheapest first, nothing below allocates) — `analysis/FaceTracker.kt:133-149`:

| # | Guard | Effect if hit | Citation |
|---|---|---|---|
| 1 | `classifier == null` | skip (Everyone/Off/model missing) | `:137` |
| 2 | `id < 0` | **untracked detections are NEVER classified** | `:143` |
| 3 | `track.votesTried >= 5` | cap reached | `:144` |
| 4 | `px = max(box.width(), box.height())` (raw ML Kit box, **unpadded, upright px**); `px < 80` | too small to classify | `:145-146` |
| 5 | `px <= track.classifiedPx` | not bigger than the biggest already classified in this track | `:147` |
| — | otherwise | `votesTried++`, `classifiedPx = px`, run the vote | `:148-154` |

Guard 5 is the "spend the 5 on the biggest crops" rule: a crop is classified only if it is strictly
larger than every crop already classified in that track (`docs/plan-censor-who.md:264-266`).
Guard 2's reason: a synthetic id is fresh every frame, so `VOTE_CAP` could not bound it and a
fast-pan run of N untracked frames would cost N classifications (`:139-142`).

`MIN_FACE_PX = 80` is measured, not guessed — 479 hand-labelled crops on an S23
(`analysis/FaceTracker.kt:236-252`):

| crop max side | n | abstain | correct |
|---|---:|---:|---:|
| 40–80 px | 28 | 7.1 % | **76.9 %** |
| 80–160 px | 78 | 6.4 % | 95.9 % |
| 160–320 px | 222 | 0.9 % | 96.8 % |
| 320+ px | 39 | 0.0 % | 94.9 % |

### 5.2 Crop geometry — InsightFace's square, not the EDL rect

`cropToTensor` — `analysis/FrameSampler.kt:693-733`. The model trains on a **square** of side
`max(boxW, boxH) * 1.5` centred on the box centre, resized to 96². The EDL's padded rect grows each
axis independently and is therefore **not** interchangeable (`:676-679`).

```
half = max(rect.width * uprightW, rect.height * uprightH) * 1.5f / 2f       :697
x0   = (rect.left + rect.right) / 2f * uprightW - half                      :698
y0   = (rect.top  + rect.bottom) / 2f * uprightH - half                     :699
step = half * 2f / 96                                                       :700
uxMap[i] = clamp(Int(x0 + i*step), 0, uprightW - 1)                         :702   // toInt() truncates
uyMap[j] = clamp(Int(y0 + j*step), 0, uprightH - 1)                         :703
```

`rect` here is the **raw, unpadded** ML Kit box in upright-normalized space (`analysis/FaceTracker.kt:151`,
`:218-222`).

Sampling reads the **already-packed NV21** (`dispW × dispH`, unrotated), not the decoder planes
(`:665-671`) — the pixels are already in memory and a 9 216-px gather is noise next to the gate's
50 176. Per output pixel: upright → display via the same rotation case table (`:714-719`, generalized
off a square: at 90/270 `uprightW == dispH` and `uprightH == dispW`), then

```
ci = dispW*dispH + (dy >> 1)*dispW + (dx >> 1)*2
y  =  nv21[dy*dispW + dx]      & 0xFF
v  = (nv21[ci]                 & 0xFF) - 128      // NV21: V first
u  = (nv21[ci + 1]             & 0xFF) - 128      // then U
r,g,b = same BT.601 integer coefficients as §2.2, same >>10, same clamp
out[i] = r; out[plane+i] = g; out[2*plane+i] = b     // 0..255 FLOATS, NOT /255
```

**Out-of-frame samples clamp to the edge pixel** (edge replication), where the Python reference pads
with black/reflect (`:687-691`). Measured consequence: 147 of 240 crops in one clip had an edge
outside the frame and the median crop had **23.7 % of its 1.5× square outside**
(`docs/plan-censor-who.md:391-395`).

### 5.3 genderage input/output contract

| Property | Value | Citation |
|---|---|---|
| Model | InsightFace `buffalo_l` / `genderage.onnx`, 1.3 MB, opset 12, **shipped verbatim** (no conversion, no quantization) | `ml/Models.kt:133-136,:166-171` |
| sha256 | `4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb` | `ml/Models.kt:168` |
| Input name / shape | `data`, `[N,3,96,96]`; the app always feeds `[1,3,96,96]` | `ml/Models.kt:140`, `ml/Infer.kt:39` |
| Input scaling | **RGB 0..255, NO scaling, no mean subtraction** (`input_mean=0.0, input_std=1.0`) | `ml/Models.kt:140-142` |
| **The trap** | the NSFW gate is 1/255 on the *identical* layout — a copy-pasted fill silently feeds this graph 1/255 of its trained range | `ml/Models.kt:142-143` |
| Output | `fc1` `[1,3]`, **raw logits, NOT softmax** | `ml/Models.kt:143-144` |
| Output semantics | `out[0]` = female, `out[1]` = male, `out[2] × 100` = age | `ml/Models.kt:144-145` |
| Buffer | direct, native order, `3 × 96² = 27 648` floats (110 592 B), **one for the whole job** | `work/FilterWorker.kt:962-964` |
| Missing model | `Infer.genderAge` returns `null` (never throws) ⇒ abstain ⇒ censor | `ml/Infer.kt:99-104` |

Per-crop verdict — `work/FilterWorker.kt:974-981`:

```
p = 1f / (1f + exp(-(out[1] - out[0])))        // softmax over two logits == sigmoid of the difference
if (out == null)                     -> 0      // abstain: model not installed
if (max(p, 1f - p) < 0.60f)          -> 0      // abstain: below CONF_FLOOR
else if (p >= 0.5f)                  -> +1     // male
else                                 -> -1     // female
```

Any throw during crop-fill or inference ⇒ vote 0 (abstain), counted, logged **once**
(`work/FilterWorker.kt:982-991`). Tallies land in `track.maleVotes` / `track.femaleVotes`; a 0 votes
for nobody (`analysis/FaceTracker.kt:150-154`).

`CONF_FLOOR = 0.60` came off a measured sweep (`docs/plan-censor-who.md:365-376`):

| CONF_FLOOR | abstain | female acc | male acc | balanced | women exposed | men visible |
|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | 0.0 % | 94.1 % | 80.0 % | 87.1 % | 5.9 % | 80.0 % |
| **0.60 (shipped)** | **2.5 %** | **95.1 %** | **88.9 %** | **92.0 %** | **4.8 %** | **80.0 %** |
| 0.70 | 5.4 % | 96.7 % | 88.9 % | 92.8 % | 3.1 % | 80.0 % |
| 0.80 | 12.0 % | 98.1 % | 100 % | 99.1 % | 1.7 % | 70.0 % |
| 0.95 | 23.2 % | 99.6 % | 100 % | 99.8 % | 0.3 % | 50.0 % |

### 5.4 Vote rule, tie-break, and what "no vote" means

`shouldCensor(femaleVotes, maleVotes, who)` — `analysis/FaceTracker.kt:302-307`, read **at eviction
only**, never earlier, so a track is judged once it is over and all its votes are in (`:178-179`,
`docs/plan-censor-who.md:278-282`):

```
NONE     -> false
WOMEN    -> !(maleVotes   > femaleVotes)
MEN      -> !(femaleVotes > maleVotes)
else     -> true                     // EVERYONE, and ANY unrecognized value: cover it
```

| Situation | Women mode | Men mode | Everyone | Off |
|---|---|---|---|---|
| female majority | censor | spare | censor | no |
| male majority | spare | censor | censor | no |
| **tie (e.g. 2/2)** | **censor** | **censor** | censor | no |
| **0/0 — no vote cast** | **censor** | **censor** | censor | no |

"No vote cast" covers: crop below 80 px, untracked detection, model not installed, ORT throw, every
classification abstained, `VOTE_CAP` spent entirely on abstentions. All of them censor. This
fail-safe is pinned by `FaceTrackerLogicTest.kt:79-93` and is described as the one test that must
never flip.

**Mapping Who → which tracks get censored:** the verdict lands in exactly one branch —
`edlFor` returns `null` for a spared track (`analysis/FaceTracker.kt:283`), so a spared track simply
never becomes a `FaceTrackEdl`. The EDL, the renderer and all of pass 2 are untouched by the mode.

Wire values (persisted literals) — `model/FilterOps.kt:66-69`: `"none" | "everyone" | "women" | "men"`.
Unrecognized input resolves to `EVERYONE` (`model/FilterOps.kt:98-102`), and an absent/empty string
resolves to `null` so the caller can fall back to the legacy boolean.

Device proof that the selector actually selects (S23, one run per mode,
`docs/plan-censor-who.md:404-408`):

| mode | crops | abstained | tracks censored | spared | ms/crop |
|---|---:|---:|---:|---:|---:|
| everyone | 0 | — | 126 / 126 | 0 | — |
| women | 281 | 6.8 % | 134 / 151 | **17** | 4.87 |
| men | 285 | 3.5 % | 69 / 136 | **67** | 5.01 |

Cost: 1.4 s against a ~150 s analyze pass = **0.9 %** (`docs/plan-censor-who.md:415-420`). §5's
"~1 ms/crop" estimate was 5× optimistic; `VOTE_CAP` bounding cost per *track* is what makes that
irrelevant.

**Known caveat to carry over, not to fix silently:** 23 % of everything the vote classified is **not
a face** — ML Kit false-positives on a protein tub, a taxi wheel, earrings, and a dog at
p(male)=1.00. At floor 0.60, 41 of 112 junk crops vote male, and in Women mode a male vote is what
*spares* a track (`docs/plan-censor-who.md:385-390`).

---

## 6. EDL data model

### 6.1 Types

```kotlin
data class NRect(left: Float, top: Float, right: Float, bottom: Float)   // Contracts.kt:11
data class FaceTrackEdl(startMs: Long, endMs: Long, keyframes: List<Pair<Long, NRect>>)  // Edl.kt:8
data class Edl(censorIntervalsMs: List<LongRange>, faceTracks: List<FaceTrackEdl>)       // Edl.kt:16
```

| Field | Type | Unit / space | Notes |
|---|---|---|---|
| `censorIntervalsMs[i]` | inclusive `[first, last]` Long range | absolute source **milliseconds** | whole-frame censor spans |
| `faceTracks[i].startMs` / `.endMs` | Long | absolute source ms | `first sample − 50` (clamped ≥0) / `last sample + 50` (unclamped) |
| `faceTracks[i].keyframes[j].first` | Long | absolute source ms | the sample's own pts |
| `faceTracks[i].keyframes[j].second` | `NRect` of Float | **upright-normalized `[0,1]`**, already 25 %-padded and clamped | |

`faceTracks` is sorted by `startMs` at EDL build (`work/FilterWorker.kt:664`, `:1111` via
`FaceTracker.finish()`); `keyframes` are in sample order, i.e. ascending time.

### 6.2 Serialized JSON schema

`Edl.toJson()` / `Edl.fromJson()` — `edl/Edl.kt:41-87`. Written with `org.json` (Android platform).

```json
{
  "censorIntervalsMs": [ [500, 3000], [12000, 14500] ],
  "faceTracks": [
    {
      "startMs": 950,
      "endMs": 1250,
      "keyframes": [
        [1000, 0.35, 0.30, 0.65, 0.70],
        [1100, 0.36, 0.31, 0.66, 0.71]
      ]
    }
  ]
}
```

| JSON path | Encoder | Decoder | Citation |
|---|---|---|---|
| `censorIntervalsMs` | array of 2-element arrays `[first, last]`, both `Long` | `p.getLong(0)..p.getLong(1)` | `:43`, `:63-66` |
| `faceTracks[].startMs` / `.endMs` | `Long` | `getLong` | `:54`, `:83` |
| `faceTracks[].keyframes[]` | 5-element array `[timeMs, left, top, right, bottom]`; the four rect components are written as **`Double`** (`rect.left.toDouble()`) | `getLong(0)`, then `getDouble(1..4).toFloat()` | `:48-51`, `:76-81` |

Round-trip note for Swift: the rects are `Float` in memory, widened to `Double` for JSON, narrowed
back to `Float` on read. A Swift `Codable` port must serialize the `Float32` value widened to
`Double` (not a re-parsed decimal string) to keep byte-diffs against Android runs meaningful.

The per-segment checkpoint wraps this: `{"firingsMs":[…], "edl":{…}}`, written atomically via
`<name>.tmp` + rename to `an-%03d.json` (`work/Checkpoint.kt:90-107`). A segment checkpoint stores
**bare tracks only** — `Edl(emptyList(), segTracks)` (`work/FilterWorker.kt:643`) — so intervals are
always rebuilt globally.

### 6.3 Query methods and the precedence rule

`edl/Edl.kt:19-39`:

```
fullFrameAt(t) = ANY i:  t >= censorIntervalsMs[i].first && t <= censorIntervalsMs[i].last   // inclusive both ends
regionsAt(t)   = if (fullFrameAt(t)) []                                   // <-- THE PRECEDENCE RULE
                 else [ tr.rectAt(t) for tr in faceTracks
                        where t >= tr.startMs && t <= tr.endMs            // inclusive both ends
                        and rectAt(t) != null ]
```

**Precedence contract (numbered, because pass 2 depends on it):**

1. A whole-frame censor interval **blanks the entire frame** and **suppresses all face regions** at
   that timestamp (`edl/Edl.kt:14`, `:29`).
2. `censorIntervalsMs` is scanned linearly and is **not required to be sorted or disjoint** — the
   scan is a pure OR (`:20-24`). In rect mode the list is literally the concatenation
   `intervalsFor(firings) + overflowSpans(tracks)` with no merge (`work/FilterWorker.kt:1167`).
   Only the whole-frame path merges (§7).
3. `faceTracks` stays in the EDL even in whole-frame mode; the intervals suppress it via (1), and
   keeping it leaves the JSON diffable (`docs/plan-whole-frame-blur.md:32-34`).
4. Both methods run **once per rendered frame** and must stay allocation-light (`edl/Edl.kt:13`);
   `regionsAt` allocates its list lazily with capacity 2 (`:35-37`).

### 6.4 The full set of things that become a whole-frame interval

`FilterWorker.censorSpans(firings, durationMs, tracks)` — `work/FilterWorker.kt:1166-1169`:

```
base = intervalsFor(firings, durationMs) + overflowSpans(tracks)
return if (wholeFrameBlur) promoteFacesToFullFrame(base, tracks) else base
```

**(a) Gate hysteresis intervals** — §3, plus a debug-only hook: `force_intervals` parses
`"startMs-endMs,startMs-endMs"` and appends the parsed spans; malformed segments are skipped
(`work/FilterWorker.kt:1171-1176`, `:1372-1380`). Debug builds only (`BuildConfig.DEBUG_HOOKS`).

**(b) Renderer-overflow promotion ("correctness item 7.4")** — `work/FilterWorker.kt:1192-1215`.
`CensorEffect` composites at most **8** rects per frame and silently drops the **smallest** beyond
that — it fails *open*, on exactly the frames with the most people (`render/CensorEffect.kt:30,:182-186`).
Spans where more than 8 tracks are simultaneously active are promoted to whole-frame so the renderer
never sees an overflowing frame:

```
if (tracks.size <= 8) return []
events = for each track: (startMs, +1) and (endMs + 1, -1)      // end is INCLUSIVE, so +1
events.sortBy { time }
active = 0; from = -1
sweep: apply EVERY delta at one instant BEFORE reading `active`   // avoids inventing a 1 ms gap
       if (active > 8) { if (from < 0) from = t }
       else if (from >= 0) { emit from..(t - 1); from = -1 }
```

The sweep counts track *lifetimes* and therefore over-counts slightly against `regionsAt` (a track
with no keyframe near `t` contributes nothing there) — over-censoring is the safe direction here
(`:1188-1190`). Note: an open span at the end of the event list is **not** emitted (the loop only
closes on a downward crossing) — with balanced +1/−1 events `active` always returns to 0, so this is
unreachable in practice.

---

## 7. Whole-frame mode

Opt-in `FilterOps.wholeFrameBlur` (default **false**) — `model/FilterOps.kt:45`, read once at
`work/FilterWorker.kt:116`. Applies **only to faces**; the NSFW gate has always been whole-frame.
Analyze cost is **zero by construction**: the flag is read once and applied once, as a single merge
over the finished track list (`docs/plan-whole-frame-blur.md:191-192`).

### 7.1 The promotion rule

`promoteFacesToFullFrame(intervals, tracks)` — `edl/Edl.kt:146-148`:

```
mergeRanges(intervals + tracks.map { it.startMs..it.endMs })
    .filter { it.last - it.first >= MIN_FULL_MS }        // MIN_FULL_MS = 500
```

`mergeRanges(ranges, bridgeMs = 400)` — `edl/Edl.kt:100-118`:

```
if ranges.size <= 1 -> return ranges AS-IS          // NOT sorted, NOT filtered, pass-through
sorted = ranges.sortedBy { it.first }
start = sorted[0].first; end = sorted[0].last
for r in sorted[1..]:
    if (r.first <= end + 400) { if (r.last > end) end = r.last }    // bridge
    else { emit start..end; start = r.first; end = r.last }
emit start..end
```

| Property | Value | Citation |
|---|---|---|
| Input | every gate interval + every overflow span + **every censored** face track span (spared tracks never exist as `FaceTrackEdl`) | `:147` |
| Bridge gap | `BRIDGE_MS = 400 ms` — spans up to 400 ms apart merge | `edl/Edl.kt:97` |
| Sort | by `first`, ascending; output is sorted and disjoint | `:102-104` |
| **Min-duration floor** | **`MIN_FULL_MS = 500 ms`**, tested as `last - first >= 500` on the inclusive endpoints | `edl/Edl.kt:132`, `:148` |
| Filter ordering | the floor runs **AFTER** the merge, so two blips 300 ms apart become one ≥500 ms span and survive together | `edl/Edl.kt:143-145` |

### 7.2 Why the floor exists (measured, do not drop)

A one-sample face track spans `first−50 .. last+50` = **100 ms** = 2–3 frames of the entire picture
blinking out and back; it reads as a decode glitch. On `tv1.webm` (643 s) run B produced **6 spans
under 1 s, three of exactly 100 ms**; pulling the frame at 485.97 s showed the cause was **a
cardboard box on a floor** — an ML Kit false positive (`docs/plan-whole-frame-blur.md:194-204`).
`BRIDGE_MS` cannot remove an *isolated* short span; only the floor can.

**Dropping a short span costs no coverage:** `regionsAt` returns rects wherever no full-frame span is
active, so a dropped promotion falls back to that track's own blurred rect. The face stays censored;
only the flash goes (`edl/Edl.kt:128-131`).

The floor cannot drop a gate interval either: hysteresis floors those at 2 s
(`edl/Edl.kt:143-145`). **[Edge case not covered by the code comment]** — a gate interval clamped at
a timeline edge *can* be shorter (a firing at `t=0` yields `0..1500`, still fine; a source shorter
than 500 ms could yield a sub-floor span). Treat as an accepted, unmeasured corner.

### 7.3 Measured outcome (S23, `tv1.webm`, 643 s, censor-only, `censorWho=everyone`)

| run | mode | render | analyze | spans | coverage | output |
|---|---|---:|---:|---:|---:|---:|
| A | rect (default) | 89 411 ms | 114 648 ms | 74 | 64.8 % | 158.1 MB |
| B | whole-frame, no floor | 89 259 ms | 121 110 ms | 29 | 90.8 % | 154.4 MB |
| C | whole-frame + floor (shipped) | 89 437 ms | 150 291 ms | 24 | 90.8 % | 154.4 MB |

Render moved **0.20 %** across all three — whole-frame is free at render time; the analyze variance is
thermal drift, not the flag (`docs/plan-whole-frame-blur.md:160-173`). Run C: 0 spans under 500 ms,
shortest span 701 ms, shortest clear window 601 ms (`:206-210`).

**Tell the user:** on face-heavy footage this is a *mostly obscured* video (90.8 % covered here vs
64.8 % the gate covered anyway), and the output is a re-encode, so it is not reversible
(`docs/plan-whole-frame-blur.md:212-216`, `model/FilterOps.kt:38-43`).

---

## 8. Memory management and concurrency

### 8.1 Track eviction (long-film-plan Phase 1)

The predecessor never evicted: the live map grew for the length of the film and every track held up
to 5 crop `Bitmap`s until `finish()`. Measured on a 155-min film: **3 362 tracks, 2 906 retained
crops, ~500 MB retained**, VmRSS 988 MB → 481 MB the instant `finish()` recycled them, and
`finish()` itself stalled for **2.7 min** with the progress bar frozen
(`docs/long-film-plan.md:43`, `:64-65`).

What shipped:

| Mechanism | Effect | Citation |
|---|---|---|
| Per-track eviction at `EVICT_AFTER_MS = 2 000` ms of source time | live map holds a handful of tracks instead of 3 362 | `analysis/FaceTracker.kt:39-43`, `:264` |
| The verdict is **four ints per live track**, never a retained crop | this, not the classifier, is the 500 MB fix | `analysis/Contracts.kt:47-63` |
| Each crop is classified inside the detector callback and dropped | | `analysis/Contracts.kt:49-50` |
| Fresh `FaceTracker` per segment on the segmented route | bounds growth by construction, on top of eviction | `work/FilterWorker.kt:597-602` |
| Measured after | 151 cumulative tracks over 8 min but **peakLiveCrops=12, liveTracks=1**; peak RSS 500 MB vs 988 MB; `finish()` 2.7 min → **1 ms** | `docs/long-film-plan.md:65` |

Two bugs found in review of the *first* eviction draft, both worth guarding in the port:
a track still on screen after its vote refilled with more crops nothing would ever recycle; and the
vote-and-recycle step was not idempotent, so a second call voted an empty list and **silently
downgraded a FEMALE verdict to uncensored** (`docs/long-film-plan.md:66`).

### 8.2 Fixed-size buffers held by the analyze pass

| Buffer | Size | Lifetime | Citation |
|---|---|---|---|
| NV21 ring | 4 × `dispW*dispH*3/2` (≈ 4 × 460 800 B at 640×480) | whole pass; grows on first use, reused when capacity matches | `analysis/FrameSampler.kt:152`, `:372` |
| Gate gather ring | 4 × `3 × 224²` = 4 × 150 528 B ≈ **602 kB** | whole pass; a slot keeps its buffer across non-gate frames | `analysis/FrameSampler.kt:153`, `:271`, `:401` |
| Gate float tensor | `3 × 224² × 4` = **602 112 B**, direct + native order | one per **job** (`by lazy`), shared across segments | `work/FilterWorker.kt:560-563` |
| Gender crop tensor | `3 × 96² × 4` = **110 592 B**, direct + native order | one per **job**, shared across segments | `work/FilterWorker.kt:962-964` |
| ORT sessions | cached process-wide in a `ConcurrentHashMap` via `computeIfAbsent` | closed once at job teardown | `ml/Infer.kt:42`, `:128-131`, `:118-121` |

Nothing frees the rings; they die with the pass (`analysis/FrameSampler.kt:143-144`). The direct
buffers are direct **because ORT wraps a direct native-order buffer zero-copy and copies a heap one
on every run** (`ml/Infer.kt:68-76`).

### 8.3 The analyze pass's own concurrency model

```
sample() coroutineScope                             FrameSampler.kt:154
├── launch { for (f in frames) onFrame(...) }       :160-166   CONSUMER  (child)
│      finally { frames.cancel() }
└── decode+convert loop stays in THIS coroutine     :168-298   PRODUCER
       Channel<Frame>(capacity = 2)                 :155
```

| Rule | Reason | Citation |
|---|---|---|
| The **consumer is the child** and the decode loop is the parent | a throw out of `onFrame` (ORT dying mid-pass) cancels the loop instead of leaving it parked in `send()` | `:156-159` |
| `frames.cancel()` in the consumer's `finally` | covers a `CancellationException` thrown **by** `onFrame`, which would otherwise complete the child quietly | `:163-165` |
| `frames.close()` in the producer's `finally` | ends the consumer's loop on **any** exit, cancel included | `:311` |
| `RING (4) > QUEUE (2) + 1` | the decoder must not overwrite pixels the consumer is still reading | `:65-68` |
| One decode coroutine, one convert path — **no producer fan-out** | Phase 5 measured splitting the old RGB loop across cores at **0 %** wall movement | `:145-151` |
| `KEY_OPERATING_RATE=MAX_VALUE` + `KEY_PRIORITY=1` | **tried and REMOVED 2026-07-28**: −0.5 % (9 985 → 9 938 ms), i.e. noise | `:207-209` |
| ORT: intra-op 1, spinning off, XNNPACK 4 threads | 8 threads measured **2.4× worse than 2** on this model class (20.1 / 47.8 / 42.3 / 19.5 inferences per second at 1/2/4/8) | `ml/Models.kt:277-294` |
| Do **not** batch the gate | batch 2 = 0.65×, batch 4 = 0.87×, batch 8 = 0.47× per frame vs batch 1 | `ml/Models.kt:290-292` |

Producer/consumer split cost, measured (`docs/perf-plan-v4.md:493-503`):

```
producer   nv21 60 677 + gateFill 27 638 = 88 315  ->  nv21-equiv 66 768 + gateGather 14 677 = 81 445
consumer   detect 9 427 + gate 27 245    = 36 672  ->  detect 9 969 + gateFill 39 634 + gate 25 446 = 75 049
```

**`packNv21` is untouched code and it still went +10.0 %** — the producer is pinned to the prime
core (cpu7, 25 samples of 25) and the consumer now wants the same core and cache. **Any future item
that moves work between these two threads must budget for that contention.**

### 8.4 Job-level concurrency (S1)

`FilterWorker.branches(audio, video)` — `work/FilterWorker.kt:845-872`. The audio (music-removal)
branch runs **concurrently with analyze → render** when the device qualifies.

| Rule | Detail | Citation |
|---|---|---|
| Gate | `am != null && !am.isLowRamDevice && totalMem >= 6 656 MiB` | `:296-303`, `:1369` |
| Why 6.5 GiB | the old 7 GiB bar sat above the 8 GB class (measured S23 `totalMem` = **7 072 MiB**); the 6 GB class reports ~5.2–5.3 GiB. Concurrent peak is ~1.82 GB | `:1360-1369` |
| Structure | `audio` is the `async` child; `video` runs in the calling coroutine | `:858-861` |
| Failure semantics | video throws ⇒ scope cancels the audio child and rethrows video's exception; audio throws ⇒ `coroutineScope` rethrows the **child's original cause**, not the cancellation | `:833-839` |
| `videoDone` set in a `finally` | releases an audio branch parked in thermal demotion — the scope's cancellation cannot interrupt a blocking sleep | `:839-841`, `:862-864` |
| Sequential fallback | `video()` then `audio { false }` | `:851-857` |
| Progress | under the concurrent schedule neither branch may post an absolute percent; each owns a **share** and the sum is posted (monotonic because both shares are) | `:890-900` |
| The real win | `separate` holds only 6 of 8 cores for 55–65 % of the job **and cannot use more** (8 intra-op threads measured *slower*: 2244 vs 2136 ms/chunk), so two cores sit idle for hours. Ceiling `(2/8) × separate` | `:823-831` |

### 8.5 Segmented analyze (long sources)

| Rule | Detail | Citation |
|---|---|---|
| Trigger | `durationMs >= 30 min` (or a positive debug `segment_ms` override) | `work/Checkpoint.kt:65-69`, `work/Eta.kt:27` |
| Segment length | 5 min; count `ceil(duration / segmentMs)` | `work/Checkpoint.kt:37,:70` |
| Boundaries | interior cuts snapped to the next **sync sample** via `cutAtMs`, `distinct().sorted()`, then `zipWithNext()` into `RenderSegment(index, from, to)` | `work/Checkpoint.kt:71-79`, `render/RenderPipeline.kt:54` |
| Per segment | fresh `FaceTracker`; the gender voter, its 110 kB buffer, the gate buffer, ORT sessions and the vote counters are the **job's** and outlive every segment — safe only because segments run in **sequence** | `work/FilterWorker.kt:596-602` |
| Sample grid anchoring | windowed passes anchor the grid to `startMs`, not to the first decoded frame, so segment N samples the same timestamps in a fresh job and after a resume | `analysis/FrameSampler.kt:112-116`, `:218` |
| Checkpoint | `an-NNN.json` = that segment's `firingsMs` + `Edl(emptyList(), segTracks)`, written only once both halves exist, atomically (`.tmp` + rename) | `work/FilterWorker.kt:640-643`, `work/Checkpoint.kt:102-107` |
| Resume | a segment with an `an-NNN.json` is skipped entirely; its firings and tracks are folded into the global lists | `work/FilterWorker.kt:586-594` |
| Global assembly | hysteresis and the 7.4 overflow promotion run **once** over the whole timeline, after every segment | `work/FilterWorker.kt:657-664` |
| Detector lifetime | `closeDetector()` in a `finally` per segment ⇒ tracking ids restart at each segment | `work/FilterWorker.kt:653-655` |

---

## 9. Android-platform-bound items and their Apple equivalents

| # | Android mechanism | Citation | Apple equivalent / porting note |
|---|---|---|---|
| 9.1 | `MediaCodec` + `MediaExtractor`, `COLOR_FormatYUV420Flexible`, ByteBuffer path, `getOutputImage()` | `FrameSampler.kt:200-210,:253` | `AVAssetReader` + `AVAssetReaderTrackOutput` with `kCVPixelBufferPixelFormatTypeKey`. **Must not request a converted/planar format that forces a full-frame copy** — the Android choice is deliberate: `COLOR_FormatYUV420Flexible` lets the framework *alias* the mapped gralloc planes with no copy (`:202-206`). On Apple, `CVPixelBufferLockBaseAddress` + per-plane `bytesPerRow` is the direct analogue; the walks already take strides as parameters. |
| 9.2 | Plane layout: `planes[0]=Y, [1]=U, [2]=V`, arbitrary `rowStride`/`pixelStride` | `FrameSampler.kt:362-365` | iOS bi-planar `420YpCbCr8` gives plane 0 = Y, plane 1 = **CbCr interleaved**. Map as `uBase = plane1Base, uPix = 2` and `vBase = plane1Base + 1, vPix = 2`, `uRow = vRow = plane1BytesPerRow` — which is exactly the semi-planar case the no-branch code already handles. |
| 9.3 | **Colour range** — the code applies BT.601 **full-range** math to whatever bytes the decoder produced, with no 16–235 expansion | `FrameSampler.kt:557-559` | **[RISK]** To be bit-identical you must feed Swift the *same raw bytes*. `kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange` yields the stream's native limited-range bytes (matching Android for typical H.264); `…FullRange` would have VideoToolbox expand them and change every RGB value, hence every gate probability. **Verify empirically before locking.** |
| 9.4 | `MediaMetadataRetriever` rotation metadata | `FrameSampler.kt:82-83` | `AVAssetTrack.preferredTransform` → angle; normalize to `[0,360)` and degrade non-multiples of 90 to 0 (`:131`). |
| 9.5 | `Image.cropRect` (exclusive-right) | `FrameSampler.kt:352` | `CVImageBufferGetCleanRect` / `CVPixelBufferGetWidth` vs `…GetBytesPerRow` padding. Android's crop keys are **inclusive** pixel indices (`+1` at `:436-444`) while `cropRect` is exclusive — keep the two conventions straight. |
| 9.6 | ML Kit `FaceDetector` with `enableTracking()` and stable integer `trackingId` | `FaceTracker.kt:203-208`, `:125` | **[HIGHEST-RISK ITEM]** Vision has no drop-in equivalent. `VNDetectFaceRectanglesRequest` gives boxes with no identity; `VNSequenceRequestHandler` + `VNTrackObjectRequest` gives per-tracker identity but requires explicit track creation/teardown and behaves differently at scene cuts. The whole design (per-track spans, `VOTE_CAP`, eviction) is built on ids. Note Vision returns **normalized, bottom-left-origin** boxes — Android's are top-left-origin pixels. Also note ML Kit's tracker is nondeterministic (4786 vs 4550 faces on identical input, `ml/Models.kt:64-66`), so the port cannot be validated on face counts — only on the **censored timeline**. |
| 9.7 | ML Kit `InputImage.fromByteBuffer(nv21, w, h, rotation, IMAGE_FORMAT_NV21)`; rejects rotations that are not 0/90/180/270 | `FrameSampler.kt:387`, `:128-131` | Vision takes a `CVPixelBuffer` + `CGImagePropertyOrientation`. Preserve the "hand over unrotated pixels + a rotation, get upright boxes back" contract, or every `NRect` in the EDL changes space. |
| 9.8 | ONNX Runtime Android, XNNPACK EP | `ml/Models.kt:302-306` | `onnxruntime-objc` / `onnxruntime-c` for iOS; XNNPACK EP exists in ORT's iOS builds. **Do not silently switch to Core ML EP for the INT8 QDQ graph** — the strictness table is tuned against these exact outputs, and the repo has three fp16-corruption incidents on record (`ml/Models.kt:50-52`). |
| 9.9 | `org.json` EDL serialization | `edl/Edl.kt:41-87` | `Codable`. Keep the array-of-arrays keyframe encoding and the `Float→Double` widening (§6.2). |
| 9.10 | Kotlin `Channel` + coroutines, `coroutineContext.ensureActive()` | `FrameSampler.kt:154-166,:221` | `AsyncStream` with `.bufferingNewest(2)` / an `AsyncChannel`, plus `Task.checkCancellation()`. Preserve the parent/child inversion in §8.3 — the failure semantics depend on it. |
| 9.11 | `ByteBuffer.allocateDirect(...).order(nativeOrder())` for zero-copy ORT tensors | `FilterWorker.kt:560-563,:962-964` | `UnsafeMutableRawPointer` / `Data` with a stable base address, or an `ORTValue` over a `Data` buffer. The whole point is that ORT must not copy per call. |
| 9.12 | `ActivityManager.MemoryInfo.totalMem` / `isLowRamDevice` for the S1 gate | `FilterWorker.kt:296-303` | `os_proc_available_memory()` / `ProcessInfo.physicalMemory`; the 6.5 GiB threshold is Android-calibrated and needs re-measuring on Apple silicon. |
| 9.13 | WorkManager foreground service, 6 h FGS cap, `stopReason` | `FilterWorker.kt:530-543` | `BGProcessingTask` + `beginBackgroundTask`; iOS gives far less background time, so the **5-minute segment checkpoint (§8.5) becomes more important, not less**. |
| 9.14 | `/proc/self/task/<tid>/stat` field 39 CPU probe, `Process.getThreadPriority` | `FrameSampler.kt:425-428` | Instrumentation only — drop it, or replace with `os_signpost`. |

---

## 10. Measured workarounds that must NOT be dropped

Each of these exists because a device measurement killed the "obvious" alternative.

| # | Rule | What happens if it is dropped | Citation |
|---|---|---|---|
| 10.1 | **The gate samples the SOURCE planes, not the already-downscaled 640-px NV21.** | Attempt A4 did exactly that: 4 700 ms cheaper and **under-censors** — censored-timeline recall **91.24 %** against a ≥99.20 % bar, **34.5 s** of clip lost, firings 781 → 719. "Nearest of nearest of 1920" lands on different source pixels and the chroma is subsampled at 640 rather than at source; those pixels are simply gone, so it **cannot be tuned back. Do not retry it.** | `FrameSampler.kt:574-580`, `docs/perf-plan-v4.md:99`, `:457-471` |
| 10.2 | **The gate's fill splits at the gather/arithmetic seam, and the two halves must stay bit-identical to `convertToTensor`.** | Anything else silently re-opens A4. The equivalence is pinned by a zero-delta test at all four rotations and both chroma layouts; on device it reproduced firings 781, intervals 76 and a byte-identical 386.9 s censored timeline (100.00 % recall). Honest gain: **−3.4 % total**, not −14.4 % (the larger figure was thermal drift). | `FrameSampler.kt:523-529,:568-594`, `docs/perf-plan-v4.md:474-489` |
| 10.3 | **`maxDim` does not shrink the gate's input.** The gate's index maps are built over the **crop rect**. | The gate would silently become A4. This explicitly corrects an older doc (`perf-plan.md:485`). | `FrameSampler.kt:396-400` |
| 10.4 | **Detection input is downscaled to 640 px but NOT rotated.** | Detect cost scales with input area (~8.6 ms/frame at 640 px); handing over native 1080p trades the whole saving for a slower detector. Rotating in the walk costs arithmetic for nothing — the detector rotates internally for free. | `FrameSampler.kt:381-385` |
| 10.5 | **No RGB bitmap anywhere in the pass.** | The removed path walked every sampled frame into a 640-px upright ARGB bitmap, which the detector converted *back* to YUV and the gate re-scaled to 224² — three conversions of 230 400 px, 93 240 times on a film = **38 % of the analyze pass**. | `FrameSampler.kt:31-37` |
| 10.6 | **NV21 dimensions must be even** (`and 1.inv()`, floor 2). | ML Kit computes plane sizes as `w*h + w*h/2`; an odd dimension disagrees with the bytes written. | `FrameSampler.kt:357-360` |
| 10.7 | **`nv21.clear()` before every pack.** | The detector may leave the ring slot with a moved position/limit; an absolute `put()` is bounds-checked against `limit()`. | `FrameSampler.kt:373-375` |
| 10.8 | **Untracked detections are censored but never gender-classified.** | Censoring them fixes a silent content-correlated coverage hole at scene cuts; classifying them would void the per-track cost bound (a fast-pan run of N frames = N classifications). | `FaceTracker.kt:119-124,:139-143` |
| 10.9 | **Unknown duration ⇒ `Long.MAX_VALUE`, never 1.** | Every censor interval collapses to `[0, 1 ms]`: the gate fires, the EDL records it, and nothing is censored. | `FilterWorker.kt:1151-1155,:1172` |
| 10.10 | **`MIN_FULL_MS = 500` runs AFTER `mergeRanges`.** | Isolated 100 ms whole-frame spans (ML Kit false positives on a cardboard box) flash the entire picture; running the filter before the merge would also drop blips that should have bridged together. | `edl/Edl.kt:143-148`, `docs/plan-whole-frame-blur.md:194-210` |
| 10.11 | **Verdict is read at eviction, never earlier; a spared track produces no span.** | Reading it early judges a track before its votes are in; a non-idempotent vote once **silently downgraded a FEMALE verdict to uncensored**. | `FaceTracker.kt:178-184`, `docs/long-film-plan.md:63` |
| 10.12 | **0/0 and ties censor.** | Women/Men would start exposing every face the classifier cannot read — the one failure the feature must not have. | `FaceTracker.kt:291-307`, `FaceTrackerLogicTest.kt:79-93` |
| 10.13 | **Gender crop is InsightFace's square (`max(w,h)*1.5`), not the EDL's per-axis padded rect.** | Feeding the EDL rect stretches every non-square face against what the model saw in training. Same 1.5 factor, different shape. | `FrameSampler.kt:673-679` |
| 10.14 | **genderage input is 0..255 unscaled; the gate is 1/255.** | A copy-pasted fill feeds genderage 1/255 of its trained range. Two near-identical walks exist for exactly this reason. | `ml/Models.kt:140-143`, `FrameSampler.kt:680-681` |
| 10.15 | **`RENDERER_MAX_REGIONS = 8` must track `CensorEffect.MAX_REGIONS`.** | Drift is benign in one direction only: shader grows and this does not ⇒ a few needless whole-frame promotions; this grows and the shader does not ⇒ the 7.4 fail-open bug returns (the *smallest* rects are dropped, on the frames with the most people). | `FilterWorker.kt:1351-1358`, `render/CensorEffect.kt:30,:182-186` |
| 10.16 | **Do not set `KEY_OPERATING_RATE`/`KEY_PRIORITY`; do not fan the producer out; do not batch the gate.** | All three measured at or below noise, or worse. | `FrameSampler.kt:145-151,:207-209`, `ml/Models.kt:290-292` |

---

## 11. Open questions the port must resolve

| # | Question | Why it is open |
|---|---|---|
| 11.1 | **Colour range** (§9.3). Which CVPixelBuffer format reproduces Android's raw decoder bytes for the QA clips? | The gate's whole threshold table is calibrated against those bytes. Highest bit-exactness risk. |
| 11.2 | **Face-track identity on Vision** (§9.6). | No `trackingId` equivalent. Everything per-track — spans, `VOTE_CAP`, eviction, the 2 s gap — is built on it. |
| 11.3 | ML Kit's default `minFaceSize = 0.1` is **[INFERRED]**, never written in the Kotlin. | Vision has no equivalent knob; the effective smallest detected face may differ, which moves both coverage and the 80 px vote floor. |
| 11.4 | ORT EP choice on iOS for the INT8 QDQ NSFW graph. | `QLinearConv` kernel selection is what makes INT8 fast on Android; the repo has three fp16-corruption incidents and insists on physical-device validation (`ml/Models.kt:50-52`). |
| 11.5 | `CONCURRENT_MIN_TOTAL_MEM = 6.5 GiB` is Android-calibrated. | Needs re-measuring; iOS reports memory differently and jetsam limits, not total RAM, are the real constraint. |
| 11.6 | `genderage.onnx` has **no download URL** (`downloadUrl = null`); it ships inside `buffalo_l.zip` (289 MB) and the fetch script unzips one file. | A build without the asset degrades Women/Men to "no vote" ⇒ Everyone — safe, but silent (`ml/Models.kt:169`, `docs/plan-censor-who.md:450-452`). |
| 11.7 | The male sample in the gender eval is **10 crops across 3 tracks**. | The 80 % "men visible" headline rests on it; `qa-assets` is two vlogs by women (`docs/plan-censor-who.md:378-381,:440-442`). |
| 11.8 | Fast-cut QA against `BRIDGE_MS = 400` was never run; `MIN_FULL_MS = 500` is one measurement deep. | `docs/plan-whole-frame-blur.md:218-223` |
| 11.9 | Segmented (≥30 min) sources were never exercised in whole-frame mode. | `censorSpans` runs once over the whole timeline for exactly this reason, but it is untested (`docs/plan-whole-frame-blur.md:231-232`). |
