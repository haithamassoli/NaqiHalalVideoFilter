# tasks.md — On-Device Video Filter (Android)

Source: `prd-video-filter-android.md`. Milestones are dependency-ordered. M0 added ahead of the PRD's build order to burn down its two flagged risks (htdemucs on-device speed, Media3 Transformer feasibility) before feature work.

## M0 — Foundations & de-risk spikes
**Exit:** both risky paths proven on a physical device with measured numbers; all three models validated against reference outputs.

Status (2026-07-22): code foundations built + verified on a Galaxy S23 (API 36). Spike decisions in `m0-spikes.md`. All three models sourced/converted, parity-checked against their references, installed in `assets/models/` (gitignored — `scripts/fetch-models.sh`), and smoke-run on device via `ml/Models.kt`. Still blocked: htdemucs benchmark (needs an SD 778G-class device) and test-asset curation (needs real videos).

- [x] Repo scaffold: Kotlin, Compose, minSdk 26, `arm64-v8a` only, coroutines, WorkManager + foreground-service skeleton (no-op job with progress notification + cancel) — verified on device: enqueue → FGS (`mediaProcessing`) → staged progress notif + Cancel action → SUCCESS and CANCELLED lifecycles confirmed via `WM-WorkerWrapper`
- [x] ONNX Runtime Android ≥1.19 integrated; XNNPACK EP verified on device; NNAPI behind a debug flag — ORT 1.27.0; on-device readout `providers=[CPU, NNAPI, XNNPACK, WEBGPU]`; NNAPI behind `ModelSmoke.useNnapi` + API≥27; smoke now zero-tensor-infers all three bundled models on launch
- [x] Source/convert NSFW 5-class classifier (Porn/Sexy/Hentai/Neutral/Drawing) to ONNX/TFLite; lock class order + preprocessing — GantMan MobileNetV2 1.4-224 → ONNX NCHW f32 via tf2onnx; parity vs TF reference max|Δ|=3.6e-7, argmax 8/8; contracts locked in `ml/Models.kt`; 84 ms → [1,5] on S23. Labeled-image spot-check carried to M1 gate QA (no labeled set here); f16 rejected (XNNPACK fp16 depthwise-conv failure) — see `m0-spikes.md`
- [x] Source NudeNet v3 320n ONNX; lock preprocessing — v3.4 release artifact (sha pinned), 18-class order + letterbox//255/RGB contract locked in `ml/Models.kt`; 76 ms → [1,22,2100] on S23. `FACE_FEMALE`/`FACE_MALE` crop validation carried to M1 QA (needs sample crops); AGPL-3.0 flagged for license review
- [x] Export htdemucs f16 ONNX via demucs.onnx (STFT/iSTFT outside graph) — torch 2.13 dynamo export, opset 18, f16 87 MB; parity vs torch reference on 7.8 s synthetic: f32 75/89 dB, f16 61.5/65.9 dB SNR (spec/wave); 7.8 s segment ≈ 9 s on S23 (smoke config). Full 30 s overlap-add stems parity lands with the M2 chunk driver
- [ ] SPIKE: htdemucs device benchmark — 60 s stereo @44.1 kHz on SD 778G-class device; record ×realtime, peak RAM, thermal; go/no-go vs the ≤25 min acceptance budget — BLOCKED (needs SD 778G device; only an S23 flagship is available)
- [x] SPIKE: Media3 Transformer — custom `GlShaderProgram` (grayscale test effect) + audio replacement via Composition; decide Transformer vs raw MediaCodec fallback; write decision down — DECISION: **Transformer (GO)**, compiles against media3 1.10.1 in `spike/GrayscaleTransformerSpike.kt`; decision written in `m0-spikes.md` (full device transcode pending a test asset)
- [ ] Curate local test assets: 5-min 1080p30 main clip, HDR clip, rotated/portrait clip, MKV+Opus clip, no-audio clip, beach/gym/lingerie gate set, cartoon/illustration set, face set incl. profile-only track — BLOCKED (no video files here; manifest in `m0-spikes.md`)

## M1 — Censor pipeline end-to-end (PRD build order 1)
**Exit:** pick video → censor-only job → saved copy; face + gate acceptance criteria green; audio passthrough verified.

Status (2026-07-22): pipeline built and E2E-verified on the S23 (12 s 1080p30 QA clip ≈ 18 s warm): female-face blur lands correctly (landscape + rotated-90 input), forced-interval full-frame blur+grayscale verified frame-by-frame, audio passthrough **bit-identical** (ADTS md5), no-audio clip completes, cancel mid-job leaves Movies/ untouched + temp cleaned (WM `CANCELLED` confirmed). QA finding: NudeNet 320n `FACE_MALE` is effectively dead on real portraits (Einstein/Obama/Trump crops → `FACE_FEMALE` 0.7–0.86, `FACE_MALE` ≤ 0.07; reproduced with upstream nudenet package, so preprocessing is correct) ⇒ the vote censors ~all faces — safe direction, PRD-accepted limitation; evaluate 640m in M3 tuning. Rotation is decoder-dependent in media3 1.10 (S23: rot-90 arrives pre-rotated, rot-270 stored + forwarded matrix) — `CensorEffect` detects per stream via frame-vs-probe dims. Debug E2E hooks: `MainActivity` autorun extras + `force_intervals_ms`; local clips in gitignored `qa-assets/`.

Pass 1 — analysis
- [x] Decode-only frame sampler with timestamps; downscaled feeds at 5 fps (gate) and 10 fps (faces) — `analysis/FrameSampler`: one sequential MediaCodec YUV pass at 10 fps, gate consumes every 2nd sample; upright bitmaps (rotation baked)
- [x] NSFW gate: strictness→threshold interpolation table in one config object; delta rule `nsfw ≥ 0 AND nsfw > sfw` — `analysis/NsfwGate`; 0 firings on SFW QA clips at s=50 on device
- [x] Hysteresis `[t−0.5s, t+1.5s]` + interval merge; unit tests on synthetic probability sequences — `NsfwGateTest` 14 tests
- [x] ML Kit face detection (bundled flavor, fast mode, tracking ON) @ 10 fps; box interpolation to full fps; 25% padding — `analysis/FaceTracker`; interpolation via EDL keyframes at render time; note: tracker may keep one id across a hard cut (two-face QA clip intermittently yields 1 spanning track)
- [x] Gender per track: ≤5 frontal crops → NudeNet majority vote (score ≥ 0.35); `blurUnknownFaces` handling; unknown ⇒ skip — `analysis/GenderVoter`; see FACE_MALE caveat above
- [x] EDL builder + serialization: censor intervals + per-frame face regions; precedence (full-frame ⇒ skip faces) — `edl/Edl`, org.json round-trip, `EdlTest` 8 tests

Pass 2 — render/encode
- [x] GL effects: separable Gaussian blur (downscale→blur→upscale for large sigma), grayscale, combinable; sigma mapped from blur-amount × resolution — `render/CensorEffect`, downscale ∈ {1,2,4,8} keeping σ_low ≤ 4, sigma keyed on short side
- [x] EDL-driven render integration per M0 decision (Transformer `GlShaderProgram` or MediaCodec+GLES) — Transformer `CensorGlEffect`; upright↔stored rect mapping decided per stream in `configure()` (`NRect.toStoredSpace`, `NRectRotationTest`)
- [x] Encode: H.264 with bitrate cap table; HDR→SDR tonemap; rotation preserved — caps by pixel tier, effective = min(source, cap) verified; `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` configured but device-unverified (no HDR asset); rotation verified on rot-90 clip
- [x] Audio passthrough fast path for censor-only jobs (no audio re-encode) — transmux, extracted AAC bit-identical to source
- [x] WorkManager job wiring: staged progress (pass 1 / pass 2), cancel cleans temp, no partial file in `Movies/` — MediaStore IS_PENDING publish; cancel re-checked after the un-cancellable copy window; pre-Q branch has manifest+runtime `WRITE_EXTERNAL_STORAGE` (device-untested, no API ≤28 hardware)
- [ ] Milestone check: face criteria (full-track censor incl. profile frames within frontal-started tracks; state changes only at EDL boundaries) + strictness 100/0 criteria on QA sets — BLOCKED (beach/gym/cartoon/profile-face QA sets still missing; face criteria spot-verified on the synthetic portrait clips only)

## M2 — Audio pipeline (PRD build order 2)
**Exit:** music-removal-only job ≤15 min on target device; video passthrough bit-identical; A/V drift <50 ms.

Status (2026-07-26): built in a prior session, then **reviewed and device-verified** here. All three job shapes run green on the S23. Measured on `qa-assets/`: music-only 5-min 1080p **8.2 min** at the original 7.8 s segment, **~3.4 min** after the M3 re-export (budget ≤15 min); video track **bit-identical** (elementary-stream MD5 *and* packet PTS/size sequence match) on both the 30 s and 5-min clips; **A/V lag a constant 2048 samples = 42.67 ms** measured by cross-correlation at the start, middle and end of the 5-min clip — i.e. no progressive drift, the double 44.1k↔48k resample contributes nothing, and the whole error is uncompensated AAC encoder priming (budget <50 ms, so ~7 ms of margin). Combined path verified (84 s for 30 s in, censor render + replaced audio). Cancel mid-job: **2 s latency (one chunk), no partial file in `Movies/`, temp cache clean**. Ingest verified on 44.1 kHz stereo, 48 kHz stereo and 48 kHz mono sources.

A 61-agent adversarial review of the 1 209 new lines raised 27 findings; 21 were refuted, 6 confirmed. Two were fixed here (preflight, free-space); one more (5.1 downmix) was fixed because it broke the feature's whole premise. The remaining three are recorded under *Known M2 limitations* below rather than silently carried.

- [x] Demux + decode to f32 stereo PCM; resample to 44.1 kHz — `audio/AudioDecoder`; verified on 44.1k stereo / 48k stereo / 48k mono. **>2 ch now folds via ITU-R BS.775** (center at −3 dB): the original code kept ch0/ch1 verbatim, which drops the center channel — where a 5.1 film puts nearly all dialogue — so `vocals` came back empty on exactly the content this feature exists for
- [x] Chunked overlap-add htdemucs driver per demucs.onnx reference — `audio/DemucsSeparator`; ring-buffer bounds, tail `TensorChunk.padded` handling and the `emitted == totalFrames` invariant all held across every device run
- [x] Streaming stem sum: only `keep_stems` materialized per chunk (never 4 full stems); soft-clip guard — one iSTFT per chunk on the summed masked spec
- [x] Streaming AAC-LC 48 kHz 192 kbps encode with 1-chunk lookahead — `audio/AacWriter`; temp disk stays O(1) in track length (peak temp = one `.m4a` + one mux copy). **The "<2 GB on 2 h input" claim is NOT verified** — the mux temp is a full-size copy of the source, so a 2 h movie exceeds 2 GB by construction; the preflight now sizes for that instead of asserting it
- [x] Video passthrough fast path: remux original video samples, zero video re-encode — `audio/Remux`; **bit-identity proven**, not assumed
- [x] Combined-ops path: processed audio muxed with pass-2 video output — verified on device
- [x] `keep_stems` option (`vocals` / `vocals+other`) plumbed end-to-end — one wire value from the Options UI to `DemucsSeparator.keep`; `vocals_other` exercised on device
- [x] Preflight: free space ≥ 2× source + 2 GB; no-audio-track error when music removal selected — moved into `work/Preflight` and now runs for **every** job shape (see M3 failure taxonomy); combined correctly reserves 3× source
- [x] Milestone check: runtime, bit-identity, A/V sync on 5-min clip — all three green, numbers above

### Known M2 limitations (confirmed by review, deliberately carried)
- **Resampler quality.** Ingest and egress both use media3's `SonicAudioProcessor`, which is 2-tap linear interpolation with no anti-imaging filter (it exists for playback speed, not SRC). A 48 kHz source — i.e. essentially every video — takes ~−27 dB in-band distortion at 8 kHz on the way in and again on the way out, and htdemucs is fed the pre-distorted mix. Fix is a polyphase FIR (L=160/M=147) on top of the existing `audio/Dsp` FFT layer.
- **A/V lag 42.67 ms.** Within the <50 ms budget but with thin margin, and it is a fixed offset, not drift. Removing it means trimming the encoder's priming frames and rebasing PTS in `AacWriter`.
- **Audio start anchor.** `AacWriter` is anchored at the source's first audio PTS clamped to ≥ 0, and `MediaMuxer` writes no edit list — a source whose audio genuinely starts late (positive first PTS) would have that offset collapsed to 0. Every QA asset has a first PTS of ~0 or slightly negative, so this is untested rather than known-broken.

## M3 — Product complete (PRD build order 3)
**Exit:** every PRD acceptance criterion green on target device, including airplane-mode E2E.

Status (2026-07-26): feature-complete in code; acceptance is green on everything the available hardware and assets can exercise. The htdemucs **segment re-export is the headline change** — the shipped graph is now 2.6 s instead of the checkpoint's 7.8 s, which took peak RSS from **3.24 GB to 1.30 GB** (PRD budget <1.5 GB, previously missed by 2×) and wall-clock from 4.2× to **1.33× realtime**, for ~2 dB of agreement with the trained configuration. Full measurement table in `m0-spikes.md`.

- [x] Compose screens: pick/ops (require ≥1 op), options (strictness, blur amount, grayscale, keep_stems, advanced `blurUnknownFaces`), jobs/library — `ui/NaqiApp` (three steps, `BackHandler`, no nav dependency) + `ui/screen/{PickOps,Options,Jobs}Screen`; visually verified end-to-end on device. Continue now requires a picked video *and* ≥1 op (it previously enqueued a null Uri). Nav state is `rememberSaveable` and re-attaches to a live job on cold start, so a minutes-long job survives rotation and process death
- [x] Model downloader: first-use fetch to app-private storage, hash verification, resume, progress, offline error states — `ml/ModelDownloader`; sha256 pinned per model, HTTP `Range` resume from `.part`, hash verified **before** the atomic rename, typed errors (`NO_SOURCE / OFFLINE / HTTP / HASH_MISMATCH / NO_SPACE / IO`). NudeNet points at its public release; the two locally converted artifacts resolve against `BuildConfig.NAQI_MODEL_BASE_URL` (empty ⇒ clean "no source configured", never a crash). **Set `-PnaqiModelBaseUrl=https://…` to enable real downloads.** Bundled assets remain the first resort, so the dev/QA flow is unchanged
- [x] Output handling: `Movies/<AppName>/` via MediaStore; done-notification actions (Open / Share / Delete original) — `work/JobNotifications.done`. Open/Share are `content://`-only (a pre-Q `file://` would throw `FileUriExposedException` in the receiving app). **Delete original opens an in-app confirmation** rather than deleting on one notification tap, and falls back to the system delete request when SAF's read-only grant can't delete
- [x] Failure taxonomy surfaced with per-cause messages: DRM, unsupported codec, no audio track, low space — `work/Preflight` (+ `PreflightTest`). This was a real hole: an unreadable source used to escape `doWork` as a raw `IOException`, logging a stack trace and showing the user nothing (reproduced on device, then fixed and re-verified)
- [x] Thermal yield between chunks — `AudioPipeline.thermalYield`, backing off 0.5 s / 2 s at `MODERATE` / `SEVERE`+ between htdemucs chunks. **2 h soak not run** (see below)
- [ ] Tuning pass on QA sets; freeze threshold/cadence/hysteresis constants — BLOCKED, unchanged since M1: needs the beach/gym/lingerie, cartoon/illustration and profile-face sets. Tuning against anything else would fit the constants to the wrong data
- [ ] Full acceptance sweep: all PRD criteria + airplane mode + cancel-mid-job cleanup — **green:** airplane-mode E2E, cancel-mid-job cleanup, peak RAM 1.30 GB < 1.5 GB, music-only runtime, video bit-identity, A/V drift 42.67 ms < 50 ms, no-audio-track error. **Still blocked:** the strictness-100/0 and female-face criteria (QA sets above); the SD 778G timing budget (only an S23 is available — every number here reads optimistically fast). ~~the 2 h movie soak (no 2 h asset)~~ — **partially closed 2026-07-27, see M4 below**

## M4 — Phase 0 of `long-film-plan.md` (feature-length input)

Status (2026-07-27): the long-film plan's Phase 0 is done in code and measured on device. Full detail and the
revised evidence table live in `long-film-plan.md`; this is the numbers-only record.

**Asset:** `qa-assets/movie-test.mp4` — a real **155.4 min** film, 1728×720 @ 23.976, H.264 1.14 Mbps + AAC
44.1 kHz stereo, 1.33 GB. The planned "concatenate the 5-min clip 24×" was unnecessary. Staged per
`adb push` → `run-as cp` into `files/` (the app can't open `/sdcard` paths).

**Instrumented combined soak, S23, on charger** (`work/JobStats.kt`, one `SOAK` line per stage in logcat):

| stage | measured | vs plan estimate |
|---|---|---|
| analyze @10 fps | **70.5 min** (4 227 928 ms) | est. 35 min → **2.0× worse** |
| gender vote | **2.7 min** (160 238 ms) | unbudgeted; feared a long stall, isn't one |
| render | **~9.7 min** (64 % done in 374 270 ms) | est. 30 min → **3× better** |
| separate / mux | **not run** — soak stopped by hand after render | carried at M2's 0.65× |
| **combined, projected** | **~3.1 h** | est. ~2.5 h |

- **Peak RSS 985 MB** through render (kernel `VmHWM`), against the 1.30–1.45 GB the htdemucs stage reaches
  on a 30 s clip — so htdemucs, not the film length, still sets the memory peak.
- **`FaceTracker`: 3 362 tracks, 2 906 retained crops.** VmRSS fell **988 → 481 MB** on `finish()`, i.e.
  ~500 MB of crop bitmaps held for the whole pass — matching the plan's back-of-envelope almost exactly,
  but *not* fatal: they are freed before the memory-hungry stage starts.
- **Thermal status 0 for the entire 79 min** (AP ≤ ~50 °C, on charger). The thermal-yield path never fired.
- Gate: **11 332 firings**, ≥229 merged censor intervals at strictness 50 on real film content.
- Cancel mid-render: clean — worker `cancelled`, every temp deleted, nothing published.
- Reference full run, 30 s clip combined: **69.8 s** total (analyze 18.8 / vote 0 / render 2.6 / separate
  47.0 / mux 0.9 / publish 0.2), peak RSS 1.40 GB. Consistent with M2's 84 s.

**`MediaMuxer` past 4 GiB: not a wall.** `spike/MuxerLimitSpike.kt` wrote 4 831 840 641 bytes / 921 943
samples in one muxer, got **`co64` (64-bit chunk offsets)**, and read the tail back intact past the 4 GiB
boundary — `VERDICT=OK`. Phase 2's segment concat is not blocked by output size. One device, one API level,
and `MediaMuxer` offers no way to *request* co64, so this is observed behaviour rather than a guarantee.

**Shipped with it:** up-front duration warning + confirm above 30 min (`work/Eta.kt`, `OptionsScreen`), and a
live ETA refined from observed throughput shown in `JobsScreen` **and in the FGS notification** — the only
surface visible during a multi-hour job. Known ceiling: the live ETA extrapolates over overall percent, and
the progress bands are *not* proportional to real cost (analyze+vote = 25 points for 73 min, render = 25
points for 10 min), so it over-promises at the render→separate boundary. Reweighting needs the `separate`
number at feature length, which the shortened soak did not produce.

## M5 — Phase 1 + Phase 2 of `long-film-plan.md` (segment + checkpoint)

Status (2026-07-28): both phases implemented and device-verified on the S23. The decision gate is answered
**films**, so the PRD's "job resume after process death" non-goal (`prd:12`) is now contradicted by shipped
behaviour and should be removed. Full detail and per-item evidence live in `long-film-plan.md`; this is the
numbers-only record.

**Assets.** `qa-assets/women-music-3min-video.mp4` (193 s, 1920×802 @ 23.976, H.264 + AAC 48 kHz, faces
*and* music) is the new workhorse — it exercises the face-crop path and the audio path at a length short
enough to kill and resume by hand. `test-video.mp4` (12.8 s) drives the concat spike. The retention A/B used
the 155-min `movie-test.mp4`. `qa5min.mp4` turns out to have **no faces at all** (`tracks=0`), so it cannot
verify anything on the censor side.

**Phase 1 — retention, measured on the film (8 min analyzed):**

| | before (Phase 0 soak) | after |
|---|---|---|
| retained crop bitmaps | 2 906 (~500 MB), held to end of pass | **peak 12**, bounded by concurrent faces |
| live tracks | 3 362, never evicted | **1–2** |
| peak RSS during analyze | 988 MB | **500 MB** |
| `finish()` (the vote stall) | 2.7 min, progress frozen | **1 ms**, votes spread across the pass |

**Phase 2 — kill/resume, verified by SIGKILL at each interruptible stage** (4 forced segments via
`--el segment_ms 60000`):

| killed during | survived | redone | cost |
|---|---|---|---|
| analyze, mid-seg 2 | `an-000/001.json`; seg 2's in-flight work correctly not persisted | segs 2–3 | — |
| render, mid-seg 2 | 4× `an-*.json`, `seg-000/001.mp4`, + `seg-002.mp4.part` | segs 2–3; stale `.part` discarded | **16.7 s vs ~3 min** |
| separate, chunk 20/100 | `audio.pcm` 6 791 400 B == `audio.json` 1 697 850 frames == `20·STRIDE − MAX_SHIFT` | 1 chunk of inference | **5 s vs 37 s** |

- WorkManager restarted the worker itself after every SIGKILL, so the automatic resume trigger needed **no
  code**; `Result.retry()` proved unnecessary. Only `STOP_REASON_CANCELLED_BY_APP` deletes the work dir.
- **Concat fidelity:** output 192.917 s vs source 192.911 s (**6 ms**, PRD budget 50 ms); audio track
  **9 044 frames, identical to source** (copied verbatim, never re-encoded on the censor-only path).
- **Audio resume is clean at the signal level:** max sample deltas across the 38.5 s resume seam
  (4 999 / 2 203 / 8 464 / 14 838) sit inside the local range (11 895 at 37.25 s, 13 880 at 39.25 s) — no click.
- **CSD spike PASSED** (`spike/SegmentConcatSpike.kt`): byte-identical SPS/PPS across two clipped exports,
  clips rebased to PTS 0, joined file decoded all 299 frames at `maxPts = 5000000 + 4933333` exactly.
- Combined segmented job peak RSS **1.18 GB** (htdemucs still sets the peak; PRD budget 1.5 GB).

**All five job shapes re-verified on device after the refactor** (it touched `Preflight`'s signature, the temp
locations, the `VideoSource`→`TrackSource` rename and `AudioPipeline`'s signature, so the M1/M2 paths were at
real risk): censor-only unsegmented, music-only (`resumable=false`), combined unsegmented, censor-only
segmented, and combined segmented with the resumable separator — plus kill/resume on the last two.

**Open, and honest about it:**

- **~2 video frames are lost per segment seam** (4 619 vs 4 625 frames; a 148 ms inter-frame gap at the 60 s
  seam against a normal 41.7 ms). Reads as a ~100 ms freeze per seam — no drift, no desync. At the 5-min
  production segment length a 155-min film has ~30 such seams. Cause not chased: the fix is a per-segment
  overlap plus a drop rule and a new monotonicity invariant, to buy back 1–3 frames per five minutes.
- **The `separate` stage on the RESUMED run measured 4.7× realtime (~10 s/chunk) against ~2 s/chunk on the
  pre-kill run of the same code path.** Thermal status was 0 throughout, and the resumable path itself is
  clearly not the cause (the pre-kill run used it too). Unexplained; needs an isolated re-run on a quiet
  device before it is attributed to anything.
- **Censor-only + non-AAC source audio has no resume.** The segmented concat copies the source audio track
  sample-for-sample, and framework `MediaMuxer` only accepts AAC/AMR — so an AC-3/E-AC-3/DTS film (i.e. a
  real film) falls back to the unsegmented route: correct output, no resume. The fix is one AAC transcode
  pass over the source audio. This is the biggest remaining gap for the actual target use case.
- An **unseekable** source (fragmented MP4 with no `sidx`) will fail the clipped export rather than falling
  back; not reproduced, no asset for it.
- Feature-length end-to-end is still **not** run to completion — every Phase 2 number here comes from the
  193 s asset with forced 60 s segments, plus the Phase 0 film measurements for analyze/retention.
