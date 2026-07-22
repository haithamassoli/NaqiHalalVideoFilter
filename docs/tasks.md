# tasks.md — On-Device Video Filter (Android)

Source: `prd-video-filter-android.md`. Milestones are dependency-ordered. M0 added ahead of the PRD's build order to burn down its two flagged risks (htdemucs on-device speed, Media3 Transformer feasibility) before feature work.

## M0 — Foundations & de-risk spikes
**Exit:** both risky paths proven on a physical device with measured numbers; all three models validated against reference outputs.

- [ ] Repo scaffold: Kotlin, Compose, minSdk 26, `arm64-v8a` only, coroutines, WorkManager + foreground-service skeleton (no-op job with progress notification + cancel)
- [ ] ONNX Runtime Android ≥1.19 integrated; XNNPACK EP verified on device; NNAPI behind a debug flag
- [ ] Source/convert NSFW 5-class classifier (Porn/Sexy/Hentai/Neutral/Drawing) to ONNX/TFLite; lock class order + preprocessing; parity-check against reference on a labeled image set
- [ ] Source NudeNet v3 320n ONNX; validate `FACE_FEMALE`/`FACE_MALE` on sample crops; lock preprocessing
- [ ] Export htdemucs f16 ONNX via demucs.onnx (STFT/iSTFT outside graph); parity-check stems vs desktop reference on a 30 s clip
- [ ] SPIKE: htdemucs device benchmark — 60 s stereo @44.1 kHz on SD 778G-class device; record ×realtime, peak RAM, thermal; go/no-go vs the ≤25 min acceptance budget
- [ ] SPIKE: Media3 Transformer — custom `GlShaderProgram` (grayscale test effect) + audio replacement via Composition; decide Transformer vs raw MediaCodec fallback; write decision down
- [ ] Curate local test assets: 5-min 1080p30 main clip, HDR clip, rotated/portrait clip, MKV+Opus clip, no-audio clip, beach/gym/lingerie gate set, cartoon/illustration set, face set incl. profile-only track

## M1 — Censor pipeline end-to-end (PRD build order 1)
**Exit:** pick video → censor-only job → saved copy; face + gate acceptance criteria green; audio passthrough verified.

Pass 1 — analysis
- [ ] Decode-only frame sampler with timestamps; downscaled feeds at 5 fps (gate) and 10 fps (faces)
- [ ] NSFW gate: strictness→threshold interpolation table in one config object; delta rule `nsfw ≥ 0 AND nsfw > sfw`
- [ ] Hysteresis `[t−0.5s, t+1.5s]` + interval merge; unit tests on synthetic probability sequences
- [ ] ML Kit face detection (bundled flavor, fast mode, tracking ON) @ 10 fps; box interpolation to full fps; 25% padding
- [ ] Gender per track: ≤5 frontal crops → NudeNet majority vote (score ≥ 0.35); `blurUnknownFaces` handling; unknown ⇒ skip
- [ ] EDL builder + serialization: censor intervals + per-frame face regions; precedence (full-frame ⇒ skip faces)

Pass 2 — render/encode
- [ ] GL effects: separable Gaussian blur (downscale→blur→upscale for large sigma), grayscale, combinable; sigma mapped from blur-amount × resolution
- [ ] EDL-driven render integration per M0 decision (Transformer `GlShaderProgram` or MediaCodec+GLES)
- [ ] Encode: H.264 with bitrate cap table; HDR→SDR tonemap; rotation preserved
- [ ] Audio passthrough fast path for censor-only jobs (no audio re-encode)
- [ ] WorkManager job wiring: staged progress (pass 1 / pass 2), cancel cleans temp, no partial file in `Movies/`
- [ ] Milestone check: face criteria (full-track censor incl. profile frames within frontal-started tracks; state changes only at EDL boundaries) + strictness 100/0 criteria on QA sets

## M2 — Audio pipeline (PRD build order 2)
**Exit:** music-removal-only job ≤15 min on target device; video passthrough bit-identical; A/V drift <50 ms.

- [ ] Demux + decode to f32 stereo PCM; resample to 44.1 kHz
- [ ] Chunked overlap-add htdemucs driver per demucs.onnx reference
- [ ] Streaming stem sum: only `keep_stems` materialized per chunk (never 4 full stems); soft-clip guard
- [ ] Streaming AAC-LC 48 kHz 192 kbps encode with 1-chunk lookahead; temp disk <2 GB verified on 2 h input
- [ ] Video passthrough fast path: remux original video samples, zero video re-encode
- [ ] Combined-ops path: processed audio muxed with pass-2 video output
- [ ] `keep_stems` option (`vocals` / `vocals+other`) plumbed end-to-end
- [ ] Preflight: free space ≥ 2× source + 2 GB; no-audio-track error when music removal selected
- [ ] Milestone check: runtime, bit-identity, A/V sync on 5-min clip

## M3 — Product complete (PRD build order 3)
**Exit:** every PRD acceptance criterion green on target device, including airplane-mode E2E.

- [ ] Compose screens: pick/ops (require ≥1 op), options (strictness, blur amount, grayscale, keep_stems, advanced `blurUnknownFaces`), jobs/library
- [ ] Model downloader: first-use fetch to app-private storage, hash verification, resume, progress, offline error states
- [ ] Output handling: `Movies/<AppName>/` via MediaStore; done-notification actions (Open / Share / Delete original)
- [ ] Failure taxonomy surfaced with per-cause messages: DRM, unsupported codec, no audio track, low space
- [ ] Thermal yield between chunks; 2 h movie soak test — no OOM, no thermal kill
- [ ] Tuning pass on QA sets; freeze threshold/cadence/hysteresis constants
- [ ] Full acceptance sweep: all PRD criteria + airplane mode + cancel-mid-job cleanup
