# PRD — Naqi for iOS (Swift), no download/share

Port spec for the shipped Android app (`prd-video-filter-android.md` + M0–M5 in `tasks.md`). Everything
here is either a product decision already made and device-verified on Android, or an iOS-specific
decision this document makes. Numbers in *Baselines* are measured on a Galaxy S23 — treat them as the
targets to beat, not as estimates.

**Explicitly out of scope:** link download (yt-dlp), share-into-app ingest, the FIFO queue, and the
`Music/Naqi` audio-only shape — i.e. all of `prd-download-share.md`. Those bring GPL linkage, a bundled
ffmpeg and an App Store policy conflict, and none of it has an iOS analogue worth building.

---

## 1. The idea

One local video in, one filtered copy out, entirely on-device. Two independent operations, either or
both:

1. **Remove music** — separate the audio into stems and keep only the ones the user wants (dialogue and
   singing by default), so a film can be watched without its score.
2. **Censor women** — blur/grayscale female faces for their whole on-screen duration, plus the *entire
   frame* during stretches an NSFW classifier flags.

The promise is privacy-absolute: no network at runtime, no telemetry, no account, and the original file
is never modified. That promise is the product, not a feature of it — every design decision below defers
to it.

Audience: Muslim users who want mainstream media to fit their values. Positioning copy lives in
`store-listing.md` (EN + AR) and carries over verbatim.

---

## 2. Scope

| | |
|---|---|
| Input | one local video, picked by the user. MP4/MOV/M4V/MKV; H.264/HEVC video; AAC/MP3/AC-3 audio |
| Output | new file in the Photos library, album "Naqi". Source resolution, fps and rotation preserved |
| Processing | on-device only. Runs in-app foreground; survives interruption via checkpoints (§8) |
| Lengths | clips through feature films (155 min verified on Android). Long jobs are segmented |
| Platform | iOS 17+, arm64. iPhone and iPad, portrait + landscape |
| Locales | English + Arabic, RTL-correct |

**Non-goals:** real-time playback filtering · overlay on other apps · streaming/DRM sources · cloud
processing · male-face blurring as a separate toggle · per-region NSFW blur (superseded by whole-frame)
· link download · share-sheet ingest · multi-job queue.

---

## 3. User flow and screens

Three screens plus an About screen. No navigation framework needed — one enum of steps with a back
handler was enough on Android; `NavigationStack` is fine on iOS.

**1 · Pick & ops**
- "Pick video" → `PHPickerViewController` filtered to `.videos`, or `UIDocumentPickerViewController` for
  files outside Photos. Copy the picked item into the app sandbox before anything else touches it.
- Two operation cards: *Remove music*, *Censor women*. **Continue requires a picked video AND ≥1 op.**
- A privacy seal ("Your videos never leave your phone") sits on this screen. It is load-bearing copy.

**2 · Options** (§4). Shows an estimated duration probed off the main thread; above 30 min, Continue
first opens a confirm dialog whose body includes "plug in". A warning, never a refusal.

**3 · Jobs**
- Live stage name, percent, and "~N remaining" ETA refined from this device's observed throughput.
- Cancel. Resume (shown only when a failed job left a resumable checkpoint).
- On success: Open · Share (system share sheet on the *output*) · Delete original (in-app confirmation,
  never a one-tap destructive action).

**About**: version, licenses (rendered from a bundled NOTICE), privacy statement.

---

## 4. Options — exact semantics and defaults

| Option | Control | Default | Applies to |
|---|---|---|---|
| Strictness | Slider 0–100 | **50** | NSFW gate thresholds only. **Never** affects face blur |
| Blur amount | Slider 0–100 | **60** | Faces + full-frame censor |
| Grayscale | Toggle | **off** | Faces + full-frame censor, combinable with blur |
| Keep stems | `vocals` / `vocals+other` | **`vocals`** | Music removal. Drums and bass are never kept |
| Blur unknown faces | Advanced toggle | **off** | Faces whose gender never resolves |

`vocals` keeps dialogue and any singing and drops everything else — on a film that means clean dialogue
and *lost sound effects*. `vocals+other` keeps SFX and ambience at the cost of melodic-music leakage.
Known, accepted, and worth surfacing in the UI copy.

---

## 5. Video pipeline — two passes, and why

Two passes are **mandatory**, not an optimization: a face track's majority-vote gender must be known
before its first frame renders, and the gate's censor pre-roll must be known before encoding starts.

### Pass 1 — analysis (decode only, no encode)

Decode the video once at **10 fps sampled**, upright (rotation baked in). Each sampled frame feeds:

**a. NSFW gate — every 2nd sample (5 fps).** Downscale to the model input, run the 5-class classifier.

Thresholds interpolate linearly between `s=0` and `s=100`:

| Class | s=0 | s=100 |
|---|---|---|
| porn | 0.75 | 0.10 |
| sexy | 0.90 | 0.10 |
| hentai | 1.00 | 0.50 |
| neutral | 0.30 | 1.00 |
| drawings | 0.50 | 0.50 |

Per-sample decision:
```
nsfwMax = max over {porn, sexy, hentai} of (p[c] − thr(c, s))
sfwMax  = max over {neutral, drawings} of (p[c] − thr(c, s))
fire    = nsfwMax >= 0 && nsfwMax > sfwMax
```
At high strictness `neutral`'s threshold approaches 1.0 so its margin can never win — the SFW veto
disables *by design*. Over-censoring at 100 is intended.

**Hysteresis:** each firing at `t` censors `[t − 500 ms, t + 1500 ms]`; overlapping or gap-free adjacent
intervals merge; clamp to `[0, duration]`. Output is a time-ordered censor-interval list.
Keep this logic in a plain Swift type with no AVFoundation imports — it is the piece most worth unit
testing, and 14 tests already exist for it on Android.

**b. Face detection — every sample (10 fps).** Boxes + a track id per face. Constants:

| | |
|---|---|
| Frontal test | `|yaw| ≤ 20°` and `|roll| ≤ 25°` |
| Minimum face | 48 px short side, in the sampled (downscaled) bitmap |
| Crops per track | max 5, spread ≥ 700 ms apart, frontal only |
| Track eviction | unseen for 2000 ms |
| Track span padding | ±50 ms |
| Box padding at render | 25% of width/height |

**c. Gender vote, streamed per track.** When a track fills its 5 crops *or* goes stale, run the gender
classifier on its crops, take a majority of FEMALE vs MALE (detection score ≥ 0.35), keep the verdict,
**recycle the crop images, and drop the track from the live map.**

This "vote and recycle" is not an optimization to defer — on Android, holding crops to the end of the
pass retained ~500 MB on a feature film and froze progress for 2.7 min in one stall at the end. Voting
per track took peak live crops from 2 906 to **12** and the end-of-pass stall to **1 ms**. Two bugs to
not re-introduce: a track that stays on screen after voting must not refill its crop buffer, and the
vote must be idempotent (a second call on an emptied crop list returns UNKNOWN and silently downgrades a
FEMALE verdict to *uncensored*). Put the guard inside the function so no caller can reopen it.

Only FEMALE (or UNKNOWN with `blurUnknownFaces`) censors, so a wrong or missing vote fails toward "not
censored".

**d. Emit an EDL** (edit decision list): the merged censor intervals + per-frame face regions, as
normalized rects in **upright, top-left-origin frame space**. JSON-serializable — this is also the
checkpoint format.

### Pass 2 — render + encode

- Precedence: an active censor interval means **full-frame** effect and face regions are skipped.
- Effect: separable Gaussian blur and/or BT.709 grayscale, hard-edged (no feathering) outside regions.
- Sigma mapping, keyed on the short side so rotated and true-portrait video blur identically:
  ```
  sigmaPx  = max(0.1, blurAmount/100 * 40 * (min(w,h) / 1080))
  downscale d = first of {1,2,4,8} where sigmaPx/d <= 4, else 8
  radius   = clamp(ceil(2.5 * sigmaPx/d), 1, MAX_RADIUS)
  ```
- Encode H.264, bitrate = `min(source bitrate, resolution-tier cap)`. HDR input tone-mapped to SDR.

### Fast paths (both must apply *zero* video effects to stay a passthrough)

- **Censor-only job** → audio track copied sample-for-sample, no audio re-encode.
- **Music-removal-only job** → video track copied sample-for-sample, **zero video re-encode**. On
  Android this is verified bit-identical (elementary-stream MD5 *and* packet PTS/size sequence).

---

## 6. Audio pipeline (music removal)

1. Decode to **f32 stereo PCM at 44.1 kHz**. Sources >2 channels must fold via ITU-R BS.775 with the
   centre at −3 dB. *This one is load-bearing:* the first Android implementation kept ch0/ch1 verbatim,
   which drops the centre channel — where a 5.1 film puts nearly all dialogue — so `vocals` came back
   empty on exactly the content this feature exists for.
2. Run htdemucs chunked with overlap-add. Geometry, which must change together with the exported model:

   | | |
   |---|---|
   | Segment | 114 660 frames (2.6 s @ 44.1 kHz) |
   | Stride | 85 995 (25% overlap; truncate, don't round) |
   | Lead pad | 22 050 frames (0.5 s), deterministic shift offset 0 |
   | STFT | nfft 4096, hop 1024, 2048 bins (model drops Nyquist), 112 frames |
   | Stem order | drums=0, bass=1, other=2, vocals=3 |

   STFT/iSTFT run **outside** the model graph; stems = `istft(masked spec) + time branch`.
3. Sum only the kept stems **per chunk, streaming** — never materialize four full stems (a 2 h film is
   ~2.5 GB per stem in f32). One iSTFT per chunk on the summed masked spectrogram. Soft-clip the sum.
4. Encode AAC-LC 48 kHz stereo 192 kbps as chunks resolve (1-chunk lookahead), so temp disk stays O(1)
   in track length.
5. Mux with the video per §5.

---

## 7. Models

| Model | Job | Size | Cadence |
|---|---|---|---|
| NSFW 5-class MobileNetV2 1.4-224 (GantMan) | whole-frame gate | 17.3 MB f32 | 5 fps |
| Platform face detector | boxes + yaw/roll | — | 10 fps |
| NudeNet v3 320n | gender per face track | 12.2 MB | ≤5 crops/track |
| htdemucs 4-stem, f16, 2.6 s segment | stem separation | 87.9 MB | chunked |

**Locked contracts** — these are exact and were parity-checked against their reference implementations.
Getting preprocessing wrong produces plausible-looking garbage, so treat them as specification:

- **NSFW gate.** Input `input` `[1,3,224,224]` f32 **NCHW**, RGB, scaled `1/255`, **no mean/std
  normalization**. Output `prediction` `[1,5]` softmax. Class order is alphabetical and index-locked:
  `drawings, hentai, neutral, porn, sexy`. Keep **f32** — the f16 variant broke on Android's XNNPACK
  fp16 depthwise-conv path. (Worth re-testing under Core ML, where fp16 is native.)
- **NudeNet 320n.** Input `images` `[1,3,320,320]` f32 RGB `/255`; pad right/bottom to square *then*
  resize to 320. Output `output0` `[1,22,2100]`: rows 0–3 = `cx,cy,w,h` in 320-space, rows 4–21 =
  per-class scores in the locked 18-label order where **`FACE_FEMALE` = index 1, `FACE_MALE` = 12**.
  Thresholds: keep ≥ 0.2, NMS score 0.25 / IoU 0.45, vote ≥ 0.35.
- **htdemucs.** Inputs: mix waveform `[1,2,114660]` f32 and complex-as-channels spectrogram
  `[1,4,2048,112]` f32. Outputs: masked spectrogram `[1,4,4,2048,112]` and time branch `[1,4,2,114660]`.

Regeneration recipes for both converted artifacts are in `m0-spikes.md` §Regen pipelines.

### Two model decisions to make before writing code

**1 · NudeNet earns almost nothing, and it is AGPL-3.0.** Measured on Android (and reproduced with the
upstream Python package, so preprocessing was not the cause): `FACE_FEMALE` fires at 0.69–0.83 on
portrait crops of *men*, while `FACE_MALE` stays ≤ 0.07. The vote therefore censors essentially every
face — the safe direction, and accepted on Android, but it means 12 MB of weights, a crop-harvesting
path, a per-track inference loop and an **AGPL-3.0 dependency inside an App Store binary** all buy
approximately nothing over "blur every detected face".

Recommendation: ship "blur all faces" as the behaviour, drop NudeNet, and drop `blurUnknownFaces` with
it. That deletes the whole licence problem, a chunk of the analyze cost, and the trickiest part of pass
1 — for an output that is already what the Android build effectively produces. Revisit with NudeNet
`640m` or a permissively-licensed gender classifier if real discrimination is ever wanted.

**2 · The NSFW gate: keep the ONNX model, or use Apple's.** iOS 17 ships
`SensitiveContentAnalysis` (`SCSensitivityAnalyzer`), which is free, native, and needs no bundled
weights — but it returns a **boolean**, so the strictness slider, the whole threshold table and the
per-class delta rule die with it, and `analysisPolicy` is user-controlled in Settings (it reports
`.disabled` unless the user has Sensitive Content Warning on, which an app cannot turn on). Keep the
ONNX gate if strictness is a feature you want; it is the only way to keep it.

---

## 8. iOS platform mapping

| Android | iOS |
|---|---|
| SAF picker | `PHPickerViewController` (`.videos`) / `UIDocumentPickerViewController` |
| `MediaExtractor` probe | `AVURLAsset` + `AVAssetTrack`: `naturalSize`, `preferredTransform`, `nominalFrameRate`, `estimatedDataRate`; `asset.hasProtectedContent` for the DRM check |
| `MediaCodec` decode @10 fps | `AVAssetReader` + `AVAssetReaderTrackOutput`, output `kCVPixelFormatType_32BGRA` |
| ML Kit face detection | Vision `VNDetectFaceRectanglesRequest` (revision 3 gives `yaw`/`roll`/`pitch` directly) |
| ML Kit tracking ids | **not available** — match boxes across frames by IoU (~40 lines). ML Kit's own ids leaked across hard cuts anyway, so this is not a downgrade |
| NSFW / NudeNet ONNX | `onnxruntime-objc`, or convert with `coremltools` |
| htdemucs ONNX | `onnxruntime-objc` (safest — the graph and f16 weights already work) or Core ML |
| Custom FFT (`audio/Dsp.kt`) | `vDSP` (`vDSP_fft_zrip` / `vDSP_DFT`). Port the window and normalization **exactly** — parity hinges on the STFT convention |
| Ingest decode + resample + downmix | `AVAssetReaderAudioMixOutput` with `AVLinearPCMBitDepthKey: 32`, float, 2 ch, 44 100 Hz. Real polyphase SRC for free — this deletes Android's known −27 dB resampler limitation. **Verify centre-channel presence on a 5.1 asset** |
| Media3 Transformer + GLSL blur shader | `AVVideoComposition(asset:applyingCIFiltersWithHandler:)` + `AVAssetExportSession`. `request.compositionTime` is the EDL lookup key. Blur = `CIGaussianBlur` on `.clampedToExtent()` cropped back to extent; composite through `CIBlendWithMask` with a rect mask; grayscale = `CIColorMatrix` with BT.709 weights (0.2126/0.7152/0.0722) for parity |
| Bitrate cap / per-segment control | `AVAssetWriter` + `AVAssetWriterInputPixelBufferAdaptor` when export presets are too coarse |
| Clipped per-segment export | `AVAssetExportSession.timeRange` |
| `MediaMuxer` + `Remux.concat` | `AVMutableComposition` + `AVAssetExportPresetPassthrough` — concatenates without re-encoding, and 64-bit offsets are not a question |
| AAC encode | `AVAssetWriter` audio input, or `AVAudioConverter` |
| `MediaStore` → `Movies/Naqi` | `PHPhotoLibrary` + `PHAssetCreationRequest`, album "Naqi" (`NSPhotoLibraryAddUsageDescription`) |
| WorkManager + foreground service | **no equivalent** — see §9 |
| FGS progress notification | in-app progress + `UNUserNotificationCenter` on completion. A Live Activity (ActivityKit) is the real analogue for a multi-hour job; treat it as a later addition, it costs a widget target |
| `noBackupFilesDir/naqi-work/<key>/` | `Application Support/naqi-work/<key>/` with `isExcludedFromBackup = true`. **Not** `Caches` — the system purges it, and this holds hours of completed work |
| `ModelDownloader` (Range + `.part` + sha256) | `URLSession` background config + `downloadTask` + `resumeData`; hash with CryptoKit `SHA256`. Or ship the 88 MB model as an On-Demand Resource |
| `SharedPreferences` | `UserDefaults` |
| Compose + `values-ar` | SwiftUI + `Localizable.xcstrings`; RTL is automatic, verify mirrored sliders and progress |

### The perf lesson worth carrying over

Android's analyze pass, not render, is the expensive stage: on a 155-min film, analyze 70.5 min against
render 9.7 min. Two findings from instrumenting it:

- Serializing decode → convert → detect → classify wasted more than half the wall clock. Pipelining
  them (decode ∥ inference, gate ∥ face detect, bounded buffer of a few frames) cut analyze **61%** with
  byte-identical output. Build the loop concurrent from the start.
- After that, the loop is bound on the **NSFW classifier** (~78% of wall), not on decode or pixel
  conversion. iOS gets the colour conversion free in VideoToolbox — which was 53% of Android's
  per-frame cost — so aim any further tuning at the gate: batch it, or run it on the ANE.

Sampling at 5 fps instead of 10 was tried and reverted: it visibly drops face boxes on fast motion.
Keep 10 fps for faces, 5 for the gate.

---

## 9. Background execution — the one real architectural difference

Android grants a media-processing foreground service 6 hours per 24. iOS grants ~30 s from
`beginBackgroundTask`, or a `BGProcessingTask` the system schedules when it feels like it (typically
charging + idle) and can reclaim with seconds of warning via its expiration handler. Against a job that
runs roughly 1× the source duration, there is no way to hide this.

**Design:**

1. **Foreground is the primary mode.** While the app is frontmost, set
   `UIApplication.shared.isIdleTimerDisabled = true` and run. Short clips finish here and nothing else
   matters.
2. **Checkpoints are the mechanism, not a safety net.** Everything below is what makes a multi-hour job
   possible across many short windows *and* survivable when iOS kills the app.
3. **`BGProcessingTask`** requests continuation when the user leaves; the expiration handler must
   complete the current checkpoint write and stop cleanly. Assume minutes, not hours.
4. **A user-visible Resume** on the Jobs screen, for the case where the system never grants a window.
   This de-risks the entire scheduling question and cost one button on Android.
5. **Do not** hold a silent audio session to fake a background window. That is an App Store rejection.

### Segmentation and checkpointing (verified on Android, port as-is)

Process the timeline in **5-minute segments**; checkpoint after each; concatenate at the end. One
mechanism handles the memory peak, the interruption cost, and spanning multiple sessions.

- **No manifest.** Write every file as `<name>.tmp` and rename. A file under its final name *means*
  complete work, which makes it impossible to reference a half-written segment.
- Per-segment artifacts: `an-NNN.json` (that segment's firings + face-track EDL), `seg-NNN.mp4`
  (rendered), `audio.pcm` + `audio.json` (separator scratch + frame count).
- **The EDL is assembled globally**, not per segment — gate hysteresis merges firings across boundaries,
  so building it per segment would clip up to 1.5 s of censoring at every seam.
- **Concat offsets are the intended segment starts, not a running sum of measured durations.**
  Accumulating folds a sub-frame rounding error into every following segment: ~1 s of A/V drift across
  30 joins, 20× past the 50 ms budget.
- Guard each segment's format/geometry against the first and fail loudly rather than writing a file that
  muxes fine and decodes wrong.
- **Resumable audio.** The overlap-add ring cannot span segments, so append separated PCM to an
  **int16 LE stereo 44.1 kHz** scratch and do one AAC encode at the end. 44.1 kHz — not 48 — is
  load-bearing: resampling before the scratch restarts the SRC at the resume seam (different
  interpolation phase, an audible click, a rounding-different frame count), and at 44.1 kHz one scratch
  frame is one separator frame, which makes `framesEmitted * 4` an exact byte offset. On resume,
  re-decode the source (cheap next to inference) and skip already-emitted chunks, restarting exactly one
  chunk early to rebuild the ring: `skipChunks = (MAX_SHIFT + framesEmitted) / STRIDE − 1`.
  Scratch cost: 176 400 B per second of source ≈ 635 MB/hour.
- Job key = SHA-256 over the source identity plus every option that changes the output — **not** a task
  id, which changes when a resume re-enqueues.
- Distinguish **cancel** from **stop**: only an explicit user cancel deletes the work directory. Kills,
  timeouts and reboots are exactly the cases the checkpoints exist to survive. Sweep orphans by age
  (7 days), never "delete all temps at startup" — that deletes the completed segments this whole design
  exists to keep.

**Memory is tighter on iOS than on Android.** htdemucs at the 2.6 s segment peaks ~1.3 GB, and iOS
jetsam limits are a fraction of physical RAM. Check `os_proc_available_memory()` before starting the
separator and re-export the model at a shorter segment (the script takes it as an argument) if a target
device can't hold it. Segment length trades peak RSS against ~2 dB of agreement with the trained
configuration: 7.8 s → 3.24 GB, 3.9 s → 1.61 GB, **2.6 s → 1.30 GB**, at no speed penalty.

---

## 10. Preflight and failure

Validate **before** any long-running work starts, by opening the asset yourself rather than trusting the
picker — an unreadable, protected or exotic file only reveals itself when the demuxer touches it.

Required free space: `(tempCopies + 1) × sourceSize + extraScratch + 2 GB`, where `tempCopies` is 1 for
single-op shapes and 2 for combined (render temp + mux temp), the `+1` is the published copy, and
`extraScratch` is the PCM scratch from §9 when the audio path is resumable. Measure the volume you
actually fill.

Every failure gets one actionable localized sentence, addressed by a string key rather than a resolved
string so it re-localizes if the language changes: **DRM · unreadable/damaged · no video track · no
audio track (only when music removal was requested) · low space · out of space mid-job · unsupported
codec · generic.** An unrecognized cause resolves to *generic* — never leak the underlying error text to
the screen; log it with the full stack instead. Map mid-pipeline failures by inspecting the error chain
(a codec the device advertises but cannot start is the single most likely one).

Thermal: yield between chunks rather than failing (`ProcessInfo.thermalStateDidChangeNotification`, back
off ~0.5 s at `.serious` and ~2 s at `.critical`). Never a hard fail on throttle, just slower.

Progress bands, for parity with the Android ETA: analyze 0–25, render 25–50, separate 50–90, concat
90–99. Note the known ceiling — these bands are *not* proportional to real cost (analyze spends 25
points on ~70 min while render spends 25 on ~10 min), so a straight-line ETA over overall percent
over-promises at the render→separate boundary. Weight them by measured cost if you want a better one.

ETA factors (source duration × factor, a floor rather than a promise): **censor 0.28 · music 0.68 ·
combined 1.0**. Confirm dialog above 30 min.

---

## 11. Acceptance criteria

- 5-min 1080p30, both ops, on a mid-range target device: completes within budget, **peak RAM < 1.5 GB**,
  no dropped frames in the output, **A/V sync drift < 50 ms**.
- Music-removal-only on the same clip: **video track bit-identical to source** (verify the elementary
  stream, not just the file size).
- Censor-only: **audio track bit-identical**.
- Strictness 100 on a beach/gym/lingerie set: ≥ 95% of frames censored. Strictness 0 on a
  cartoon/illustration set: zero false positives.
- Female-face set: censored for the full on-screen duration including profile frames inside a track that
  started frontal, and **no strobing** — censor state changes only at EDL boundaries.
- Airplane mode end-to-end succeeds (after models are installed).
- Cancel mid-job leaves no partial file in Photos and no temp directory behind.
- Kill the app at each interruptible stage (analyze / render / separate) and resume: at most one
  segment's worth of work is redone.
- Segment concat: total duration within 50 ms of source; audio frame count identical to source on the
  censor-only path.

### Baselines to beat (Galaxy S23, measured)

| | |
|---|---|
| 30 s clip, combined | 69.8 s end-to-end; peak RSS 1.40 GB |
| 155-min film, analyze @10 fps | 70.5 min *before* the 61% pipelining win |
| 155-min film, render | ~9.7 min |
| 155-min film, combined | ~3.1 h projected |
| htdemucs throughput | ~1.33× realtime — this is the model's floor, not a tuning target |
| Peak RSS, segmented combined | 1.18 GB (htdemucs sets the peak, not film length) |
| Segment resume cost | render 16.7 s vs ~3 min; separator 5 s vs 37 s |
| A/V lag | constant 42.67 ms, no progressive drift (AAC encoder priming) |

---

## 12. Known limitations to carry forward

Shipped as-is on Android, and none of them are iOS-specific:

- A face never seen frontal gets no gender vote and is not censored (unless the unknown-faces toggle is
  on) — moot if you take §7's recommendation to blur all faces.
- High strictness intentionally over-censors; the SFW veto is disabled by design.
- `vocals`-only silences sound effects on films.
- ~2 video frames are lost per segment seam (reads as a ~100 ms freeze; no drift, no desync). The fix is
  a per-segment overlap plus a drop rule.
- A/V lag of ~43 ms from uncompensated AAC encoder priming — inside the 50 ms budget with thin margin.
  Fixable by trimming priming frames and rebasing PTS.
- An audio track whose first PTS is genuinely positive would have that offset collapsed to zero.
- Untested on Android and worth checking on iOS: HDR tone-mapping (no HDR asset was available).

---

## 13. Build order

Same order that worked on Android, which front-loads the half where iOS is easier:

1. **Skeleton** — picker, three screens, job runner, checkpoint directory, cancel. A no-op job that
   reports staged progress and cleans up after itself.
2. **Model runtime** — ONNX Runtime (or Core ML) wired up, all models loading, one zero-tensor inference
   each on a real device, IO shapes asserted against §7 on launch. Cheap, and it catches contract drift
   before any pipeline depends on it.
3. **Censor pipeline end-to-end** — AVAssetReader sampling → Vision faces → gate → EDL → CIFilter render
   → export, with audio passthrough. Build the analyze loop concurrent from day one (§8).
4. **Audio pipeline** — decode/downmix/resample, vDSP STFT, htdemucs chunk driver, streaming stem sum,
   AAC encode, video passthrough fast path. The only genuinely unknown part of the port; do it second so
   it fails early rather than late.
5. **Options UI, model download, localization, failure taxonomy, ETA.**
6. **Segmentation + checkpoints + Resume + BGProcessingTask.** Prove the passthrough concat *first* —
   everything else in this step is wasted if two separately-exported segments won't join cleanly.

### Cheap traps that cost real time on Android

- A probe that can cry wolf is worse than no probe. Two false "broken" verdicts came from instrument
  bugs: a debug hook re-firing on view recreation and writing two runs into one file, and a check that
  asserted monotonically increasing PTS — which any H.264 stream with B-frames violates by design.
- The failure channel is the thing nobody tests. Android's error message was read with the wrong type
  accessor for months, so *every* failure showed one generic string and the Arabic translations were
  unreachable. Test one real failure end-to-end to the screen, in both locales.
- Rotation handling is decoder-dependent. Decide upright-vs-stored space per stream by comparing actual
  frame dimensions against the probe, and verify on both a rotated-90 and a rotated-270 asset.
