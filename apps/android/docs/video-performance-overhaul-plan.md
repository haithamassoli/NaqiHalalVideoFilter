# Video performance overhaul plan

Status: proposed

Written: 2026-08-02

Scope: on-device Android video censorship and music removal for short clips through feature-length video

Inputs reviewed:

- Current implementation under `app/src/main/java/com/haithamassoli/naqi/`
- Existing measurements in `docs/perf-plan.md`, `docs/long-film-plan.md`, `docs/long-film-followups.md`, and `docs/m0-spikes.md`
- `kawzaknobom/Sunnay_Colabs`
- `alganzory/HaramBlur`
- Android MediaCodec and Media3 behavior
- ONNX Runtime, XNNPACK, QNN, LiteRT, mobile vision models, and cinematic audio separation research

## Executive decision

The largest gains will not come from encoding more video segments in parallel. The current dominant costs are the 5 fps NSFW model and HTDemucs. Render is already a comparatively small stage. Blind segment fan-out adds codec allocation failures, duplicated GOP decoding, random I/O, thermal contention, CSD mismatch, and seam risk for a limited end-to-end win.

The recommended order is:

1. Establish a release-like, repeatable benchmark and a locked quality corpus.
2. Fix correctness paths that can currently expose content or invalidate comparisons.
3. Remove NudeNet and formalize the actual behavior as censoring all detected faces.
4. Eliminate avoidable allocations, inference, transcodes, and file copies.
5. Replace the NSFW gate with a small, rights-cleared, statically quantized mobile CNN if it beats the optimized baseline.
6. Replace four-stem HTDemucs with a task-specific music estimator that returns `original mix - estimated music`.
7. Test bounded audio/video branch overlap with explicit CPU budgets and thermal demotion.
8. Prefer one continuous video export with Media3 resume if hard-kill tests pass; retain sync-aligned segmented rendering as the fallback.
9. Enable at most two section workers only on device/codec profiles that pass sustained correctness and throughput gates.

Expected outcome, not a promise:

| Job | Minimum promotion target | Stretch target | Main lever |
|---|---:|---:|---|
| Censor only | 1.5x end-to-end | 2x | Remove NudeNet, cheaper NSFW gate, no-op remux |
| Music only | 2x separator throughput | 3x or better | Task-specific compact music model |
| Combined long video | 2x end-to-end | 3x | New models plus bounded branch overlap |
| Short cold job | 30% lower fixed overhead | 50% | Session warmup, no checkpoints, no wasted audio render |

These targets are relative to a new release-like baseline on each device. The repository has never completed the 155-minute combined soak, so no absolute completion-time claim is currently defensible.

## Product decisions hidden inside performance work

Performance cannot be optimized correctly until the output policy is explicit. Use the following defaults unless product requirements reject them.

| Question | Recommended default | Why it matters |
|---|---|---|
| Which faces are censored? | All detected faces | NudeNet is not a reliable gender classifier and already classifies tested male portraits as `FACE_FEMALE`; removing it is faster, safer, more deterministic, and removes an AGPL model |
| What should the feature be called? | `Censor faces and flagged scenes` | Calling all-face behavior `Censor women` would be misleading |
| What happens to uncertain or untracked faces? | Censor immediately and fail closed | A privacy filter should not expose a face because an ID, track, or attribute vote is missing |
| What counts as music? | Instrumental and sung music are removed; spoken dialogue and sound effects are retained | A training target cannot be designed until singing and diegetic music are labeled consistently |
| Default video output mode | Preserve | Keep source dimensions, timing, orientation, SDR/HDR class, and source codec where a validated encoder exists |
| Compatibility fallback | Explicit, never silent | H.264 8-bit SDR MP4 is useful, but it is not source-preserving |
| Minimum device floor | API 29, arm64, 6 GB physical RAM class | "Use as much RAM as needed" removes the old 1.5 GB design cap; it does not remove Android LMKD or device limits |
| Cloud processing | None | Current privacy promise remains fully on-device |

If selective apparent-female presentation remains non-negotiable, treat it as a separate model program. It must use a purpose-trained, calibrated classifier once per deterministic track, censor uncertain tracks, report subgroup results, and avoid claiming that pixels reveal gender identity. It must not block the all-face performance path.

## What is known today

### Measured pipeline

```text
Video analysis pass
  MediaCodec decode
  -> 10 fps YUV-to-RGB/downscale producer
  -> ML Kit face detection/tracking at 10 fps
  -> GantMan MobileNetV2 NSFW gate at 5 fps
  -> up to five NudeNet inferences per face track
  -> EDL

Video render pass
  Media3 Transformer decode
  -> OpenGL censor effect
  -> forced H.264 SDR encode

Audio branch
  decode/stats pass
  -> second decode
  -> 2.6-second four-stem HTDemucs chunks with 25% overlap
  -> retained-stem overlap-add
  -> 44.1-to-48 kHz conversion
  -> AAC-LC

Finalization
  sample-copy mux
  -> full temp-to-MediaStore copy
```

### Current evidence

| Evidence | Status | Meaning |
|---|---|---|
| Analyze improved from about 9.99 s to 3.92 s on the 12.8 s asset | Measured on Galaxy S23 | Existing producer/consumer and ML Kit/gate overlap is valuable and should stay |
| Current short analyze is about 3.6-4.1 s | Measured on Galaxy S23 | Run-to-run variance is material |
| NSFW gate is about 1.9 s and ML Kit await about 1.1 s in that analyze | Measured | The gate is now the main analysis target |
| YUV conversion is about 16-19 ms per sampled frame | Measured | Parallelizing conversion improved that sub-step but moved end-to-end analyze by 0%; do not repeat it now |
| Lowering face sampling from 10 fps to 5 fps exposed a face | Pixel-verified | Sampling cannot be reduced until tracking and quality evaluation change |
| NudeNet took about 2.7 minutes for 2,906 crop calls on a 155-minute pass | Measured | Removing it is a real but bounded win |
| NudeNet reports `FACE_FEMALE` around 0.69-0.83 on tested male portraits | Reproduced locally and upstream | The model does not implement the promised distinction |
| Segmented censor path peak was about 0.53 GB RSS | Measured | Video branch memory is modest |
| HTDemucs peak was about 1.29 GB RSS | Measured | Current audio graph dominates process memory |
| Render projected about 9.7 minutes for 155 minutes of source | Partially measured/extrapolated | Render is not the first optimization target |
| Audio was projected about 101 minutes for 155 minutes of source | Projected | This conflicts with later approximately 2.1 s/chunk observations and must be re-baselined |
| Combined 155-minute completion has never run | Open | All feature-length total times remain projections |
| Existing performance runs are debuggable and release optimization is disabled | Current build configuration | A production-like benchmark is mandatory before final decisions |

### Existing optimizations to preserve

- One sequential analysis decode, not per-frame seeks.
- A bounded `Channel(2)` and four-bitmap rotation.
- ML Kit execution overlapped with the NSFW gate.
- Decode/conversion overlapped with inference.
- Per-track crop eviction and early voting.
- Direct reusable HTDemucs input buffers.
- Six-thread CPU EP cap with spinning disabled.
- CPU arena and memory pattern disabled for HTDemucs.
- One continuous AAC encode for resumable long audio.
- Sync-sample-aligned render segments and absolute timestamp offsets.
- Atomic segment and analysis checkpoint commits.

### Work already measured and rejected

- Face sampling at 5 fps: faster but exposed a face.
- Parallel YUV row conversion: faster conversion, no end-to-end gain.
- Decoder operating-rate and priority hints: noise on the S23.
- XNNPACK for the FP16 HTDemucs graph: corrupted spectral output.
- FP32 HTDemucs re-export: casts were only about 4.6% of kernel time and memory would rise.
- ORT spinning for HTDemucs: slower.
- More than six HTDemucs CPU threads: slower.
- Unqualified concurrent audio/video under the old 1.5 GB limit: exceeded that artificial budget.

## Required correctness work before optimization

Faster incorrect output is a regression. Complete these items before comparing replacement models or schedulers.

### Censorship safety

1. Add a duration fallback. Today a failed duration probe can clamp NSFW intervals to approximately `[0, 1 ms]`, exposing later events.
2. Create an EDL entry for every face detection, including detections with no ML Kit tracking ID.
3. Replace wall-clock-sensitive ML Kit track IDs with app-owned deterministic association keyed by source PTS.
4. Reset association at scene cuts so interpolation never crosses a hard cut.
5. Censor a new detection immediately rather than waiting for track confirmation.
6. If more than eight regions are active, censor the whole frame instead of dropping smaller faces.
7. Prevent the exact no-op combination `blurAmount = 0` and `grayscale = false`.
8. Keep face analysis at 10 fps until rendered-output gates approve a lower cadence.
9. Canonicalize analysis colors from the source color range, primaries, transfer, and matrix instead of always applying BT.601 full-range arithmetic.
10. Define an HDR-to-analysis-SDR transform separately from output HDR preservation.

### Audio integrity

1. Count actual frames fed during the second audio decode; the current separator output length can reach the expected value by padding missing input with zeros.
2. Replace or bypass Sonic for quality-critical resampling. Existing measurements show material high-frequency distortion.
3. Measure and compensate AAC priming instead of spending almost the entire 50 ms sync budget.
4. Inspect PCM encoding and channel masks before downmixing multichannel inputs.
5. Define a failure threshold for non-finite separator output. Silencing an isolated sample is acceptable; silently replacing a large corrupt span is not.
6. Compare models on aligned PCM before AAC and again after AAC so codec and resampler damage are not blamed on the separator.

### Resume integrity

Add a checkpoint manifest. The current key is primarily URI plus options and a literal plan version. It does not prove that the underlying source or inference/render contract is unchanged.

The manifest must contain:

- Source size, stable content fingerprint, provider metadata, and first/last sample signatures.
- Exact segment plan in microseconds.
- Pipeline schema version.
- Model file hashes.
- Preprocessing and calibration versions.
- Face policy and tracker version.
- Audio sample rate, model geometry, overlap, and retained target definition.
- Render mode, shader version, encoder component, codec, bitrate policy, color policy, and CSD.

Any mismatch invalidates only the affected branch where safe; otherwise it invalidates the whole checkpoint.

## Target architecture

```text
Probe and plan
  source fingerprint
  + track/sample timeline
  + codec capabilities
  + storage and thermal baseline
  + scheduler profile

Video analysis branch
  one sequential hardware decoder
  -> bounded analysis-frame pool
  -> ordered face detector
  -> deterministic PTS tracker + shot boundaries
  -> static NSFW batches through bounded workers
  -> ordered results
  -> atomic EDL checkpoints

Audio branch
  decode/statistics
  -> bounded STFT/chunk queue
  -> task-specific music estimator
  -> original mix - estimated music
  -> ordered overlap-add
  -> atomic PCM/chunk checkpoints
  -> one continuous AAC encode

Video output
  no visual edits: sample-copy source video
  visual edits: one continuous Media3 GL render when resume is qualified
  fallback: sync-aligned video-only segments plus validated concat

Final output
  one continuous video track
  + copied or one-time encoded audio track
  -> sample-copy final mux
  -> frame/PTS/color/audio validation
  -> atomic MediaStore publication
```

### Short-video policy

- Do not create segment/checkpoint files unless predicted work exceeds the retry budget.
- Start loading image sessions while probing metadata and validating storage.
- Start loading the audio model only when music removal is selected.
- Keep process-scoped warmed sessions with an explicit memory-pressure close path.
- Render combined jobs video-only; do not carry source audio into a temp that the final mux discards.
- If the EDL is empty and there is no visual effect, sample-copy the original video instead of re-encoding it.
- If neither selected operation changes a compatible track, avoid creating an intermediate generation.
- Record cold and warm latency separately.

### Long-video policy

- Keep analysis and audio checkpoints independent.
- Make checkpoint duration a lost-work budget, not a hardcoded source duration.
- Use `checkpointSourceDuration = clamp(1 min, 10 min, targetRedoWall / measuredStageRate)` after enough profile data exists.
- Keep five minutes as the initial fallback.
- Stage a slow or unstable content-provider source into immutable local storage before parallel reads.
- Run a continuous render if Media3 resume passes the hard-kill matrix.
- Retain current sync-aligned manual segments for unqualified codecs, containers, devices, and hard-kill behavior.
- Final audio remains one continuous encode; never concatenate independent AAC sessions.

## Vision plan

### Decision 1: remove NudeNet

Make the initial optimized baseline censor all detected faces and remove:

- NudeNet model download and 12.2 MB artifact.
- Up to five crop bitmaps per track.
- Crop resize, float conversion, ONNX inference, YOLO decode, and NMS.
- `GenderVoter` and `blurUnknownFaces` behavior.
- AGPL model distribution risk.
- The main correctness dependency on a full-track attribute vote.

This is both a performance change and a product change. Rename the option and update the PRD, strings, tests, store copy, and privacy explanation in the same milestone.

### Decision 2: optimize the current gate before replacing it

Instrument these costs separately:

- 224x224 resize.
- Bitmap pixel extraction.
- NCHW packing and normalization.
- Tensor construction/copy.
- `OrtSession.run`.
- Result extraction.
- Allocated bytes and GC time.

Then implement a process-scoped gate runner with:

- Reused 224x224 bitmap or direct YUV-to-model resize.
- Reused pixel storage.
- Reused native-order direct input buffer.
- Reused fixed-shape `OnnxTensor` when the Java binding proves safe.
- Reused output storage where supported.
- Explicit session ownership per worker.
- Static batch artifacts rather than a dynamic batch assumption.

Promotion gate: at least 90% less transient allocation, logits within `1e-6` of the current path, and no more than 3% wall regression. Keep the change even if speed is modest when it removes GC risk on long videos.

### NSFW model bake-off

| Candidate | Role | Expected advantage | Main risk | Decision |
|---|---|---|---|---|
| Current MobileNetV2 1.4 FP32 | Baseline | Already integrated | Weak provenance, 17.3 MB, measured bottleneck | Keep only as baseline |
| Current model, static INT8 | Precision experiment | Small conversion effort | Calibration/recall loss or Q/DQ fallback | Test first |
| Custom MobileNetV4-Conv-Small | Primary production candidate | Small mobile backbone, static INT8 friendly | Requires rights-cleared training and calibration | Train and test |
| Custom EfficientNet-Lite0 | Quality challenger | Mature mobile deployment path | May be slower/larger than the smallest MobileNetV4 | Train and test |
| Marqo-style ViT-Tiny NSFW | Offline quality reference | Different architecture and stronger capacity | 384 input and mobile cost | Reference only |
| Yahoo OpenNSFW/OpenNSFW2 | Historical reference | Established baseline | Older binary taxonomy and heavier design | Reference only |

Do not compare public checkpoint accuracy percentages directly. Their datasets, class policies, preprocessing, and splits differ. Every candidate must be calibrated against this product's locked event-level corpus.

### Face detector bake-off

| Candidate | Role | License posture | Decision |
|---|---|---|---|
| ML Kit FAST without trusting its tracking IDs | Baseline | Google SDK terms | Keep initially |
| YuNet 2026 fixed-shape FP32/INT8 | Primary challenger | MIT | Test at 10 fps |
| CenterFace | Recall challenger | MIT | Test only if YuNet misses difficult faces |
| SCRFD official weights | Offline quality reference | Official weights are non-commercial research | Do not ship without new rights |

Detector promotion is based on rendered target coverage, not WIDER Face AP or isolated latency.

### Tracking and cadence

Build a small source-time tracker using:

- IoU.
- Center motion and scale change.
- A bounded Kalman or alpha-beta prediction.
- Shot-cut resets.
- Conservative short-gap filling.
- Ordered source PTS, never wall-clock completion order.

After that tracker passes at 10 fps, test adaptive cadence:

- 10 fps around new faces, cuts, motion, uncertainty, and crowded scenes.
- 5 fps only during stable, high-confidence tracks.
- Immediate return to 10 fps after a miss or scene change.

The prior unconditional 5 fps result is a rejection and remains the control.

### Optional localized explicit-content detector

A small explicit-parts detector may reduce unnecessary whole-frame censorship, but it is not needed for the first performance win. If pursued, use a cleanly trained NanoDet-Plus, PicoDet, or YOLOX-Nano style model with rights-cleared weights. Avoid the current NudeNet and Ultralytics AGPL weights unless their obligations are intentionally accepted.

## Audio plan

### Define the target

The production target should estimate only music and derive the retained track as:

```text
retained = original_mix - estimated_music
```

This has three advantages:

- The output is mixture-consistent by construction.
- Dialogue and effects not confidently identified as music remain intact.
- Only one target decoder is required.

Training labels must explicitly classify singing, diegetic music, tonal effects, crowd chants, alarms, and speech over music. DnR v3 alone is insufficient if singing must be removed because its music construction deliberately avoids vocal content.

### Low-risk current-model experiments

1. Define audio RTF as `wall_seconds / source_seconds`; lower is better. Stop using ambiguous phrases such as `1.3x realtime`.
2. Re-baseline 25% HTDemucs overlap in a non-debuggable build.
3. Test 10% and 5% overlap. Ten percent reduces inference calls by about 16.7% relative to 25% and has a maximum speedup near 1.2x.
4. Compare boundary PCM, transients, quiet passages, and music onsets against the 25% reference.
5. Test AAC directly at 44.1 kHz when the encoder supports it, removing the final 44.1-to-48 kHz conversion.
6. Reuse the STFT complex buffer rather than allocating it per chunk.
7. Profile STFT, ORT, output copies, iSTFT, overlap-add, PCM write, and AAC separately.

### Model bake-off

| Candidate | Compute/fit | Product fit | Mobile decision |
|---|---|---|---|
| Current four-stem HTDemucs s26 | 87.9 MB, about 1.29 GB measured peak | Wrong taxonomy and computes four stems | Baseline |
| DnR-trained Open-Unmix music model | Published BandIt benchmark reports 5.7 GFLOPs and much higher CPU throughput than Hybrid Demucs | Correct cinematic classes | First proof of concept; existing DnR checkpoint is evaluation-only if non-commercial |
| Existing MDX model | Mobile-friendly 2D convolutional speed control | Usually trained for songs, not cinematic effects | Performance control only |
| Custom compact MDX/TFC-TDF model | Target 5-20M parameters, fixed shape, one music mask | Exact task, strong INT8/XNNPACK/QNN fit | Primary production direction |
| Compact BSRNN/BandIt | Better cinematic quality potential | Correct taxonomy | Quality challenger, not assumed fast |
| Full BandIt | Published CPU benchmark is slower than Hybrid Demucs | Strong quality reference | Desktop/offline reference |
| Mel-Band RoFormer | Large attention model | Strong music-separation research | Reject for on-device baseline |
| DeepFilterNet/DTLN/VoiceFilter | Fast speech enhancement | Removes noise or isolates speech, not dialogue plus effects | Reject for wrong semantics |

Suggested compact production artifact:

- 44.1 or 48 kHz native rate selected from evaluation, not convenience.
- Fixed four-to-eight-second input.
- One complex music mask or complementary music/residual masks constrained to sum to one.
- Stereo-aware inference or one coherent mask shared across channels.
- FP32 STFT/iSTFT and graph I/O initially.
- Static INT8 internal convolution blocks.
- Less than 50 MB FP16 or 25 MB INT8.
- Less than 750 MB sustained peak PSS.
- No recurrent or attention layer unless it earns its mobile cost.

### Audio quality gates

| Gate | Requirement |
|---|---|
| Separator speed | At least 2x current throughput on the midrange target; 3x is the promotion goal for a new trained model |
| No-music identity | At least 40 dB output-to-input SNR on dialogue/effects-only clips |
| Music attenuation | Non-inferior to the selected quality reference at the same dialogue/effects score |
| Dialogue | No statistically significant regression in blinded listening; report STOI and optional ASR WER |
| Effects | No statistically significant regression in transient quality and listener ratings |
| Stereo | No audible image collapse or phase inconsistency |
| Seams | No clicks, pumping, gain steps, or resume-boundary difference |
| Reliability | Exact sample count, no unexplained NaN/Inf, deterministic resume |
| A/V sync | P95 at most 20 ms, absolute maximum 50 ms, drift at most 5 ms/hour |

## Runtime plan

### Image models

Use this order so model quality and runtime effects remain attributable:

1. ONNX Runtime/XNNPACK FP32 with fixed shapes and reusable direct buffers.
2. ONNX Runtime/XNNPACK static INT8.
3. LiteRT CPU with the same model and preprocessing.
4. LiteRT GPU FP16 only if CPU is still too slow and transfer cost is measured.
5. QNN HTP only for a fixed-shape quantized model after the portable CPU path passes.
6. Do not invest in NNAPI; it is deprecated on Android 15 and driver behavior is inconsistent.

For XNNPACK, retain one ORT intra-op thread, disable ORT spinning, and sweep the XNNPACK pool rather than always claiming every core. Verify provider assignment; unsupported compute silently falling back to CPU can erase the expected gain.

QNN is an optional Snapdragon profile, not the only production path. It requires a custom ORT Android build and Qualcomm libraries; the HTP backend requires quantized, fixed-shape graphs. Cache compiled contexts only when they are keyed by SoC, OS, runtime, model hash, and calibration version.

### Audio models

- Keep CPU EP for the current FP16 HTDemucs graph.
- Prefer a 2D convolutional replacement that can run fully on XNNPACK or LiteRT CPU.
- Test static QDQ INT8 before QNN.
- Disable CPU fallback when validating a QNN artifact so partial offload cannot masquerade as success.
- Do not infer multiple current HTDemucs chunks concurrently by default; two 1.29 GB sessions plus CPU oversubscription are unlikely to help.
- Revisit two ordered chunk workers after the compact model is below 750 MB and per-worker thread counts can be partitioned.

## Parallelism and scheduler

### First concurrency prize: independent branches

Audio is independent of video analysis/render until final mux. Test these schedules independently:

| Schedule | Shape | Hypothesis |
|---|---|---|
| S0 | Analyze -> render -> audio | Correctness baseline |
| S1 | Analyze -> audio and render concurrently | Likely best current overlap because render uses codec/GPU while audio uses CPU |
| S2 | Audio and analyze concurrently -> render | Higher contention because both inference branches are CPU-heavy |
| S3 | Audio concurrently with analyze -> render | Maximum theoretical overlap; only useful with explicit thread budgets |
| S4 | Audio concurrently with the full analyze/render video branch | Lowest theoretical wall; highest sustained thermal risk |

Promote a concurrent schedule only when a completed combined job is at least 15% faster, outputs are equivalent, no LMKD event occurs on the 6 GB target, and sustained severe thermal status is absent.

### Resource-token scheduler

| Task | Video decoder | Video encoder | GPU | CPU | Storage |
|---|---:|---:|---:|---:|---:|
| Analysis | 1 | 0 | 0 initially | High | Read |
| GL render | 1 | 1 | 1 | Low-medium | Read/write |
| Current HTDemucs | 0 | 0 | 0 | Very high | Read/write |
| Compact separator | 0 | 0 | Optional | Medium-high | Read/write |
| AAC encode | 0 | 0 | 0 | Low | Read/write |
| Final mux/publish | 0 | 0 | 0 | Low | High sequential I/O |

Scheduler rules:

- Unknown device profiles start sequentially.
- Reserve at least two CPU cores for the OS, codecs, and app coordination.
- Partition a fixed total worker budget; do not let XNNPACK claim every core while the separator also claims six.
- Sample thermal status/headroom no more than once per second.
- Demote only at a safe batch, chunk, or segment boundary.
- Demote on codec resource errors, severe thermal state, low-memory callbacks, or rolling throughput loss over 15%.
- Never promote concurrency halfway through an ML Kit tracking pass; switch only after deterministic source-time tracking exists.
- Cache the winning profile by build fingerprint, OS, codec component names, resolution/codec class, model hash, and scheduler version.
- Invalidate the profile after an OS update, codec component change, or model/runtime update.

### Gate batching and workers

The NSFW gate is frame-independent and can use ordered batching without splitting face tracking.

Test:

- Static batch sizes 1, 2, 4, and 8.
- One and two gate sessions.
- XNNPACK pools of 1, 2, 4, and device-appropriate maximum threads.
- Queue depths 1, 2, 4, and 8.

Each queued item owns immutable pixels or a dedicated pool slot; the current rotating bitmaps cannot be retained past the callback without copying or increasing the pool. Results are reordered by source PTS before hysteresis.

Promotion gate: at least 10% additional full-analyze improvement over the best batch-1 runner, identical event decisions at locked thresholds, bounded memory, and no face-coverage regression caused by scheduling.

### Section parallelism

Use sections primarily as checkpoints. Do not make N-way decode/render the default.

Analysis section workers become eligible only after deterministic tracking and shot-boundary reconciliation. Even then, compare them against one decoder plus batched gate workers; repeated GOP decode and random I/O may lose.

Render section workers become eligible only when all of these are true:

- Separate `MediaExtractor`, `Transformer`, codecs, EGL state, and output per worker.
- Same hardware encoder component is pinned for all workers.
- Closed random-access boundaries are validated for the source codec.
- Output B-frames are disabled for manual segment mode.
- MIME, dimensions, crop, sample aspect ratio, profile, level, bit depth, rotation, color metadata, HDR metadata, and every CSD buffer match.
- Every segment decodes independently.
- Absolute source timestamps are used; measured segment durations are never accumulated.
- Two workers provide at least 1.3x sustained render throughput and at least 10% relevant job-level improvement over 30 minutes.

Production cap: two render workers. Three or more remain rejected unless a future device-specific profile proves at least 1.65x throughput with no thermal, codec, or quality failure.

## Rendering and video quality

### Output modes

| Mode | Contract |
|---|---|
| Passthrough | No visual edits: compressed video samples and compatible untouched audio samples are copied bit-for-bit |
| Preserve | Keep source dimensions, frame PTS/VFR, orientation policy, SDR/HDR class, bit depth, and source codec where a validated hardware encoder exists |
| Compatible | Explicit H.264 8-bit SDR MP4, with AAC when audio must be encoded |

Current output is compatible mode because it forces H.264 and OpenGL HDR-to-SDR. Do not label it preserve.

Capture `Transformer.Listener.onFallbackApplied`. Preserve mode treats a codec, resolution, or HDR fallback as a failed preserve export and offers compatible mode explicitly instead of silently changing output.

### Bitrate policy

Replace resolution-only caps with a calibrated pixel-rate and codec-aware policy:

```text
target = min(
  encoder_maximum,
  storage_ceiling,
  max(
    source_video_bitrate * generation_factor * codec_conversion_factor,
    width * height * effective_fps * bits_per_pixel_floor
  )
)
```

Calibrate generation factor, codec conversion factor, and bits-per-pixel floor on low-motion, high-motion, animation, grain, dark scenes, and screen recordings. A 1080p60 source cannot share the same ceiling as 1080p24, and HEVC-to-H.264 conversion needs more than a source-bitrate multiplier.

Prefer VBR for offline quality. Test two- and four-second GOPs for continuous output. Keep two seconds and zero B-frames for resumable/manual segment output until longer GOPs and reorder depth pass the failure matrix.

### Continuous render and resume

Media3 1.10.1 contains `Transformer.resume(composition, newPath, oldPath)`. It resumes a previously cancelled MP4 by finding reusable encoded video, writing to a new path, and processing the remainder. It is not proof of arbitrary SIGKILL recovery.

Spike requirements:

- Alternate `render-a.mp4` and `render-b.mp4` generations.
- Gracefully call and await `cancel()` before considering a partial export resumable.
- Validate that video-only compositions resume correctly.
- Verify censor-only audio remains bit-identical; do not allow resume to re-encode it.
- Pixel-check EDL timing immediately before and after the resume point.
- Inject SIGKILL, reboot, FGS timeout, low-storage failure, and repeated kill during resume/remux/copy.
- Fall back to the last parseable generation or current segmented render.

Promote continuous render only when every injected failure yields a valid final file and redo is no worse than the current five-minute segment bound. Until then, the existing segmented path remains the production recovery mechanism.

### One-decode pipeline decision

Do not rewrite the app around one raw decode pass now. Removing gender voting reduces future context, but NSFW pre-roll still needs at least 500 ms of retained frames, and preserving current face-gap interpolation needs up to two seconds.

At 30 fps, a two-second tight RGBA ring is about 475 MiB at 1080p and 1.85 GiB at 4K before decoder, encoder, model, and GL allocations. At 60 fps those values double. A raw MediaCodec/EGL implementation would also rebuild Media3 color, HDR tone mapping, rotation, VFR, encoder fallback, muxing, cancellation, and resume behavior for a practical censor-only gain likely below the theoretical 27% analyze/render overlap ceiling.

If this is revisited, restrict the spike to SDR 1080p30, keep a two-pass fallback, and compare it against analyzing segment N+1 while Media3 renders segment N.

## Storage and finalization

- Compute scratch from predicted encoded bytes and resume generations, not integer multiples of source file size.
- Measure the actual temp volume and MediaStore target volume independently.
- Reuse a completed `audio.m4a` on resume when its manifest matches.
- Add cancellation and progress during multi-gigabyte publish copies.
- Test a larger copy buffer immediately; profile before building a custom direct-to-MediaStore muxer.
- Spike writing the final mux directly to a pending MediaStore file descriptor only if atomicity and recovery remain clear.
- Avoid an intermediate joined-video file; current one-pass concat plus audio is the right shape.

## Benchmark foundation

### Build type

Add a `benchmark` build type with:

- `debuggable = false`.
- Release-equivalent compiler and R8 settings.
- A separate `DEBUG_HOOKS` build field so deterministic autorun and forced-segment hooks remain available.
- No UI tooling or debug-only allocations.
- Fixed model/runtime logging.

Do not compare a candidate benchmark build against the current debuggable baseline and call the entire difference an algorithm win. Re-baseline the unchanged pipeline first.

### Instrumentation

Add Perfetto slices and structured run output for:

- Source probe and local staging.
- Decode wait and decode output.
- YUV conversion/resize/color transform.
- Face inference.
- Tracking.
- NSFW resize, pack, tensor, run, and postprocess.
- Audio decode/statistics.
- STFT, separator run, iSTFT, overlap-add, PCM write, and AAC.
- Render decode, shader, encode, mux, and publish.
- Queue occupancy and worker idle time.
- Model/session load and warmup.
- Codec component names and output formats.
- PSS/RSS/native heap, allocations, GC, CPU time/frequency, I/O bytes, thermal status/headroom, and battery energy.

Every run record includes app commit, pipeline manifest hash, model hashes, runtime versions, device build fingerprint, battery level, charge state, ambient/case state, and source hash.

### Corpus

| Group | Required assets |
|---|---|
| Short | 3 s and 30 s 1080p H.264/AAC; easy negative, difficult positive, cold/warm |
| Medium | Existing 12.8 s and 193 s assets; 10-minute mixed-motion clip |
| Long | 30-minute stress clip and one real 155-minute/2-hour film |
| Face safety | Small, profile, occluded, motion-blurred, brief, segment-crossing, crowded >8, null-ID, varied presentation and skin tone |
| NSFW safety | Explicit events, benign skin-heavy scenes, sports, beach, medical, cartoons, dark/compressed scenes, rapid cuts |
| Audio | Dialogue only, effects only, music only, singing, spoken word over music, tonal effects, crowd, alarms, explosions, silence, mono/stereo/5.1 |
| Containers | MP4, fragmented MP4 without `sidx`, MKV, WebM, H.264 B-frame, HEVC CRA/open GOP, sparse keyframes |
| Timing | 23.976, 24, 30, 60, VFR, delayed audio, negative priming/edit-list cases |
| Color | BT.601/709/2020, limited/full, HDR10 PQ, HLG, 90/180/270 rotation, square rotated input |

Ground truth must be independently annotated. Do not use the current models as truth.

### Run protocol

1. Use the same source bytes and output policy for paired runs.
2. Run short tests at least five times and long thermal tests at least three times.
3. Randomize candidate order.
4. Return the device to a comparable thermal state between groups.
5. Keep charging, screen, radios, power mode, and background load controlled.
6. Record cold and warm model runs separately.
7. Report devices separately; do not average unlike SoCs.
8. Use paired confidence intervals over videos/events, not millions of correlated frames.
9. Validate output before accepting performance numbers.

Required devices:

- Galaxy S23 / Snapdragon 8 Gen 2, preserving current continuity.
- A 6 GB Snapdragon 778G-class device or the actual minimum target.
- A Google Tensor device.
- A recent MediaTek or Exynos device if it is in the intended market.

## Acceptance gates

### Censorship

- Zero misses on a fixed critical-safety set.
- Event-level and target-frame recall non-inferior to the approved baseline at matched false-positive policy.
- Every detector box is censored, including null tracking IDs.
- No active face is dropped when more than eight appear; overflow becomes whole-frame censorship.
- For faces at least 48 sampled pixels and visible at least 300 ms, held-out target-frame recall is at least 95% and no uncovered run exceeds 100 ms.
- Report uncensored target pixel-area-time, not only classifier accuracy.
- Report false-censored minutes per hour and unnecessarily obscured output area.

### Video

- Passthrough compressed sample payloads and timestamps are bit-identical.
- No unexplained dropped or duplicate frames.
- Every expected source PTS maps to output within 1 ms where no intentional edit changes timing.
- Output duration differs by at most one frame.
- Uncensored-region mean VMAF is at least 95 and no corpus asset is below 93, subject to visual calibration of these thresholds.
- SDR chart median Delta E 2000 is below 2 and P95 below 5.
- Preserve HDR retains intended transfer, primaries, range, bit depth, and static metadata with no silent fallback.
- Rotation, crop, sample aspect ratio, and VFR behavior remain correct.

### Audio

- Exact expected sample count.
- P95 A/V offset at most 20 ms, absolute maximum 50 ms, drift at most 5 ms/hour.
- No clicks or gain discontinuities at model, resume, or video segment boundaries.
- No-music identity and music-attenuation gates pass before and after AAC.
- Blinded listening is non-inferior for dialogue naturalness and effects preservation.

### Performance

- New NSFW model reduces full analyze wall by at least 25% on S23 and the minimum target device.
- New audio model provides at least 2x separator throughput on the minimum target and meets quality gates.
- Parallel schedule reduces completed combined-job wall by at least 15% over the final sequential pipeline.
- Two render workers provide at least 1.3x sustained render throughput and 10% relevant job-level gain before qualification.
- No accepted result depends only on isolated model latency.
- Sustained throughput after 30 minutes remains within 15% of its post-warmup rate unless the sequential control throttles equally.

### Memory and thermal

- No LMKD kill, codec reclaim, allocation failure, or low-memory corruption in three long runs on the minimum device.
- The scheduler demotes safely under low-memory and severe thermal signals.
- Peak PSS, available memory, and swap pressure are reported rather than enforcing the obsolete 1.5 GB cap.
- Parallel mode must leave enough system headroom to survive a background app transition and notification/UI work.
- Severe or critical thermal status disqualifies a parallel profile unless the sequential control reaches the same state and parallel still improves total energy and wall time safely.

### Reliability and licensing

- All injected stops produce a valid final output or a resumable state with bounded redo.
- A deterministic segmented failure does not offer an endless Resume loop; it falls back once to continuous export.
- Every model has a source commit, artifact hash, conversion recipe, input/output contract, code license, weight license, training-data record, and redistribution decision.
- NudeNet is removed before release unless its AGPL obligations are deliberately accepted and its behavior is independently justified.
- The current GantMan NSFW artifact is replaced or its NOASSERTION provenance is resolved before release.

## Delivery phases

### Phase 0: measurement and policy

Deliverables:

- Product decisions for all-face policy, singing, output modes, and minimum device.
- Benchmark build with `DEBUG_HOOKS`.
- Structured per-stage metrics and Perfetto slices.
- Versioned short/medium/long corpus and annotations.
- One unchanged sequential baseline on every target device.
- One completed 155-minute combined baseline.

Exit:

- Current stage RTF, peak PSS, thermal curve, energy, output quality, and failure behavior are known.
- Ambiguous current audio timing is resolved.

### Phase 1: safety and persisted contracts

Deliverables:

- Duration fallback.
- All detections represented in the EDL.
- Whole-frame overflow beyond renderer region capacity.
- Non-no-op censor options.
- Source/pipeline checkpoint manifest.
- Correct analysis color pipeline.
- Actual audio-fed-frame invariant and non-finite-span policy.

Exit:

- Existing quality corpus passes before performance behavior changes.

### Phase 2: low-risk speed wins

Deliverables:

- Remove NudeNet and rename the feature to all-face behavior.
- Reusable gate preprocessing/tensor buffers.
- Short combined render is video-only.
- Empty-EDL video passthrough.
- Model warmup overlapped with probe/preflight.
- 44.1 kHz AAC and HTDemucs overlap experiments.
- Larger publish-copy buffer and cancellation/progress.

Exit:

- Each change has an isolated paired result and passes the full output validator.

### Phase 3: vision bake-off

Deliverables:

- Current gate FP32 and INT8 artifacts.
- Custom MobileNetV4 and EfficientNet-Lite candidates.
- ORT/XNNPACK and LiteRT CPU comparison.
- Batch/thread/queue sweep.
- YuNet versus ML Kit comparison.
- Deterministic source-time tracker with scene cuts.

Exit:

- One rights-cleared gate and one face detector/tracker combination pass quality first, then the 25% analyze-wall gate.

### Phase 4: audio bake-off

Deliverables:

- Locked music/dialogue/effects policy and corpus.
- HTDemucs 25/10/5% overlap baseline.
- DnR Open-Unmix proof of concept.
- Existing MDX speed control.
- Compact custom MDX/TFC-TDF model.
- BandIt quality reference.
- PCM and listening evaluation before and after AAC.

Exit:

- A rights-cleared model is Pareto-superior to HTDemucs and reaches at least 2x target-device throughput, or HTDemucs remains with its best validated overlap.

### Phase 5: adaptive concurrency

Deliverables:

- Resource-token scheduler.
- S0-S4 branch schedule sweep.
- CPU partition sweep.
- Thermal/memory demotion.
- Device profile cache and invalidation.
- Optional two-worker compact-audio and render experiments.

Exit:

- At least 15% completed combined-job gain with equivalent output and no minimum-device reliability regression.

### Phase 6: render quality and recovery

Deliverables:

- Passthrough, Preserve, and Compatible modes.
- Codec/fps/color-aware bitrate policy.
- Explicit Media3 fallback handling.
- Continuous render/resume failure-injection spike.
- Current segmented fallback retained until the resume gate passes.
- Output frame/PTS/color/A-V validator.

Exit:

- Preserve mode passes its device/codec matrix or is not offered on that profile.
- Continuous render replaces segmented render only on profiles where recovery is at least as strong.

### Phase 7: release qualification

Deliverables:

- Three completed feature-length runs per supported device class.
- Short cold/warm latency report.
- Censor-only, music-only, and combined end-to-end report.
- Thermal, memory, energy, storage high-water, and resume report.
- Model cards and license inventory.
- Honest user-facing limits.

Exit:

- No release claim relies on a projection.

## Reference repository findings

### Sunnay_Colabs

Useful concepts:

- Optional whole-person rather than face-only regions.
- User-defined time and fixed-screen censor regions.
- Desktop `mdx_extra` as an offline audio quality reference.

Do not copy:

- The repository has no source license.
- ByteTrack IDs are discarded before rendering.
- Static boxes are held for plus/minus one second without interpolation.
- The normal path incurs repeated H.264/OpenCV/H.264 generations and MP3/AAC audio generations.
- Long audio is hard-split without an outer overlap/crossfade.
- Model downloads and dependencies are not reproducibly locked.
- No controlled performance or quality benchmark exists.

### HaramBlur

Useful concepts:

- Low-resolution inference with full-resolution presentation.
- Persistent warmed models.
- Bounded inference queues and stale-result rejection.
- Run expensive optional inference only after cheaper gates require it.
- Engage censorship quickly and release it conservatively.

Do not copy:

- It is a browser extension that applies CSS blur to an entire live element; it has no video export, codec, mux, audio, or quality pipeline.
- Frames cross a JPEG/Blob/message/decode path before inference.
- It has no region tracking or interpolation.
- Its public code is AGPLv3.
- No controlled end-to-end benchmark exists.
- The current store rewrite is closed source and does not validate the old public implementation.

## Research references

- [Sunnay_Colabs](https://github.com/kawzaknobom/Sunnay_Colabs)
- [HaramBlur public source](https://github.com/alganzory/HaramBlur)
- [Media3 Transformer 1.10.1 source, including resume](https://github.com/androidx/media/blob/1.10.1/libraries/transformer/src/main/java/androidx/media3/transformer/Transformer.java)
- [Android MediaCodec concurrent-instance hint](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances())
- [Android codec performance points](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities#getSupportedPerformancePoints())
- [ONNX Runtime quantization](https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html)
- [ONNX Runtime XNNPACK EP](https://onnxruntime.ai/docs/execution-providers/Xnnpack-ExecutionProvider.html)
- [ONNX Runtime QNN EP](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [LiteRT](https://github.com/google-ai-edge/LiteRT)
- [MobileNetV4](https://arxiv.org/abs/2404.10518)
- [YuNet in OpenCV Zoo](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
- [NanoDet](https://github.com/RangiLyu/nanodet)
- [PicoDet](https://github.com/PaddlePaddle/PaddleDetection/tree/release/2.8/configs/picodet)
- [Open-Unmix](https://github.com/sigsep/open-unmix-pytorch)
- [BandIt cinematic audio separation](https://github.com/kwatcharasupat/bandit)
- [Divide and Remaster dataset](https://zenodo.org/records/5574713)
- [MDX-Net](https://arxiv.org/abs/2111.12203)
- [Demucs](https://github.com/facebookresearch/demucs)

## Final recommendation

Build the optimized sequential pipeline first, then earn concurrency with measurements.

The likely winning production shape is:

```text
all-face censoring
+ deterministic source-time tracking
+ small INT8 NSFW CNN
+ task-specific compact music estimator
+ empty-EDL passthrough
+ preserve-quality continuous render
+ checkpointed analysis/audio
+ audio/render overlap on qualified devices
+ sequential fallback everywhere else
```

Do not start with N-way video section rendering or a raw one-pass MediaCodec rewrite. Both spend engineering and correctness budget on stages that are not the measured bottleneck. Model work, avoidable-work removal, and bounded branch overlap have the largest credible payoff while preserving video quality.
