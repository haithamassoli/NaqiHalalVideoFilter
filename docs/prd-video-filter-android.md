# PRD — On-Device Video Filter (Android, Kotlin)

## Summary
Android app that filters a locally selected video entirely on-device and saves a filtered copy. Two independent operations, run alone or together: (1) remove music from the audio track via stem separation; (2) censor women in the video — female faces always (when enabled), plus whole-frame censoring when an NSFW classifier gate fires. Censor style: blur amount slider + grayscale toggle. No network, no telemetry, original file untouched.

## Scope
- Input: one local video (SAF picker). MP4/MKV/WebM, H.264/H.265, AAC/Opus/MP3 audio.
- Output: new file in `Movies/<AppName>/`, source resolution and fps preserved.
- Processing: background job (WorkManager + foreground service), progress notification per stage, cancellable.

## Non-goals (v1)
Overlay on other apps; streaming sources; real-time playback filtering; DRM content; iOS; cloud processing; male-face blurring; per-region NSFW blur (superseded by whole-frame decision); Bandit-class dialogue/music/effects model (future upgrade).

**"Job resume after process death" was a non-goal here and is no longer one** — removed 2026-07-28 when the
`long-film-plan.md` decision gate was answered *films*. It could not both stand and support the 2 h movie
the streaming requirement below already contemplates: resume is exactly the mechanism a feature-length
input needs. A long job now checkpoints per segment and survives a SIGKILL, the 6 h foreground-service cap
and a reboot; see `long-film-plan.md` Phase 2 and `tasks.md` M5 for the device evidence. Short clips are
unaffected and still run in one pass.

## User flow
1. Pick video → 2. Choose ops: `[Remove music] [Censor women] (either or both required)` → 3. Options screen → 4. Start job → notification progress → 5. Done: saved copy + "Open / Share / Delete original?".

## Options (user-facing)
| Option | Control | Applies to |
|---|---|---|
| Strictness | Slider 0–100 | NSFW gate thresholds only. Never affects face blur. |
| Blur amount | Slider 0–100 → Gaussian sigma scaled to resolution | Faces + full-frame censor |
| Grayscale | Toggle, combinable with blur | Faces + full-frame censor |
| Keep stems | `vocals` (default) \| `vocals+other` | Music removal. Drums/bass never kept. |

The censor op is **"Censor faces": every detected face is blurred.** `video-performance-plan-v2.md` §5.4
removed the NudeNet gender vote (AGPL-3.0 in a closed-source APK, and `m0-spikes.md:35` shows it fired
`FACE_FEMALE` 0.69–0.83 on male portraits, so it already censored ~every face). The "Blur unknown
faces" advanced toggle went with it — there is no unresolved bucket left. The rest of this document
still describes the vote and needs the same pass.

## Models
| Model | Job | Runtime | Size | Cadence (pass 1) |
|---|---|---|---|---|
| NSFW classifier, NSFWJS-style 5-class MobileNet (Porn/Sexy/Hentai/Neutral/Drawing) | Whole-frame gate | ONNX Runtime / TFLite | ~5–10 MB | 5 fps sampled |
| ML Kit Face Detection (bundled flavor, fast mode, tracking ON) | Face boxes + track IDs | Play Services lib, offline | small | 10 fps sampled |
| NudeNet v3 320n | Gender per face track via `FACE_FEMALE`/`FACE_MALE` | ONNX Runtime | ~7 MB | ≤5 frontal crops per track |
| htdemucs 4-stem (demucs.onnx export: STFT/iSTFT outside graph) | Stem separation | ONNX Runtime, XNNPACK; NNAPI behind flag | ~160 MB f16 | chunked overlap-add |

Models downloaded on first use to app-private storage (htdemucs too large to bundle). Hash-verified. All inference on-device.

## Video pipeline — two-pass
Two-pass is mandatory: majority-vote gender needs the full face track before its first frame renders, and censor pre-roll needs the gate timeline ahead of encoding.

### Pass 1 — analysis (decode only, no encode)
1. **NSFW gate** @ 5 fps sampled frames, downscaled to model input.
   - Thresholds per class from strictness `s` (linear interpolation, constants in one config object, tune in QA):

   | Class | s=0 | s=100 |
   |---|---|---|
   | Porn | 0.75 | 0.10 |
   | Sexy | 0.90 | 0.10 |
   | Hentai | 1.00 | 0.50 |
   | Neutral | 0.30 | 1.00 |
   | Drawing | 0.50 | 0.50 |

   - Decision per sample: `nsfw = max over {Porn,Sexy,Hentai} of (p − thr)`; `sfw = max over {Neutral,Drawing} of (p − thr)`; fire iff `nsfw ≥ 0 AND nsfw > sfw`.
   - **Hysteresis:** each firing sample at time `t` censors `[t − 0.5s, t + 1.5s]`; overlapping intervals merge. Output: censor-interval list.
2. **Faces** @ 10 fps: ML Kit boxes + track IDs. Interpolate boxes to full fps, pad 25%.
3. **Gender per track:** up to 5 frontal crops spread across the track → NudeNet → majority of `FACE_FEMALE` vs `FACE_MALE` (det score ≥ 0.35). Female → censor entire track span. No frontal samples / no votes → skip unless `blurUnknownFaces`.
4. Emit EDL: censor intervals + per-frame face regions to censor.

### Pass 2 — render + encode
- Media3 Transformer preferred: custom `GlShaderProgram` applies EDL per frame. Fallback if Transformer audio replacement proves inflexible: raw MediaCodec decode → GLES → MediaCodec encode → MediaMuxer.
- Precedence: active censor interval ⇒ full-frame effect, skip face regions.
- Effect: separable Gaussian blur (downscale→blur→upscale for large sigma) and/or grayscale, per options.
- Encode H.264, bitrate = min(source bitrate, resolution-tier cap). HDR input tonemapped to SDR.

### Fast paths
- Censor-only job → audio track passthrough (no audio re-encode).
- Music-removal-only job → video track passthrough (remux original video samples; zero video re-encode).

## Audio pipeline (music removal)
1. Demux → decode to f32 stereo PCM, resample to 44.1 kHz.
2. htdemucs chunked overlap-add (per demucs.onnx reference implementation).
3. Sum only `keep_stems` **per chunk, streaming** — never materialize all 4 stems (2 h movie ≈ 2.5 GB/stem f32). Soft-clip guard on the sum.
4. Stream-encode AAC-LC 48 kHz stereo 192 kbps as chunks resolve (1-chunk lookahead).
5. Mux with video per pass 2 / fast path.
- Semantics: `vocals` keeps dialogue + any singing, drops everything else (movies: clean dialogue, SFX lost). `vocals+other` keeps SFX/ambience at the cost of melodic-music leakage. Known and accepted tradeoff.

## Tech stack
Kotlin, Jetpack Compose (3 screens: pick/ops, options, jobs/library). minSdk 26, target latest, `arm64-v8a` only. Coroutines + WorkManager. ONNX Runtime Android ≥1.19. Media3 Transformer. No analytics SDKs.

## Preflight & failure
- Free space ≥ 2× source size + 2 GB, else abort with message.
- DRM / unsupported codec / no audio track (when music removal selected) → clear per-cause error.
- Thermal: chunked work yields between segments; no hard fail on throttle, just slower.

## Acceptance criteria
- 5-min 1080p30 video, both ops, mid-range 2022 SoC (SD 778G class): completes ≤ 25 min, peak RAM < 1.5 GB, no frame drops in output, A/V sync drift < 50 ms.
- Music-removal-only on same clip: ≤ 15 min, video bit-identical to source (passthrough verified).
- Strictness 100: beach/gym/lingerie test set ≥ 95% of frames censored. Strictness 0: cartoon/illustration test set 0 false positives.
- Female-face test set: face censored for full on-screen duration including profile frames within a track that started frontal; no strobing (censor state changes only at EDL boundaries).
- Airplane mode end-to-end run succeeds (post model download).
- Cancel mid-job leaves no partial file in `Movies/`, temp dir cleaned.

## Known limitations (shipped as-is)
- Face never seen frontal → no gender → not censored (unless `blurUnknownFaces`).
- Gender model errors in both directions; majority vote mitigates, doesn't eliminate.
- High strictness intentionally over-censors (Neutral veto disabled by design).
- `vocals`-only on movies silences sound effects.

## Build order
1. M1: censor pipeline end-to-end (pass 1 + pass 2, fast path audio passthrough).
2. M2: audio pipeline + video passthrough fast path.
3. M3: options UI, model downloader, acceptance test set, tuning.

## Future
Bandit-v2-class dialogue/music/effects model when a mobile port is viable; per-region NSFW blur; job resume; NudeNet 640m accuracy option; male-face toggle.
