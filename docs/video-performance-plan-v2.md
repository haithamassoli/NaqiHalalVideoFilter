# Video performance plan v2

Status: proposed — supersedes `video-performance-overhaul-plan.md`

Written: 2026-08-02 · Scope: on-device analyze/render/separate, short clips through feature length

---

## TL;DR

**Audio separation is ~65 % of the wall and analyze is ~27 %. Render is 4 %.** The v1 plan spends most
of its length on the 4 %, and its audio figure is derived from a unit-ambiguous number that the repo's
own measurements contradict by 1.8×.

**Splitting the video into parallel sections cannot help**, because both dominant stages already own
every core. But **running the audio branch and the video branch at once can**, because the separator is
capped at 6 of 8 threads and leaves two cores idle for hours. Those are different ideas (§3).

Measure these five first — none takes more than a day except one unattended run:

0. **Re-stage a feature-length QA asset.** `movie-test.mp4` and the five `women-*` files are gone from
   the gitignored `qa-assets/` (§4.4). Steps 2 and 5 cannot run without one.
1. Re-baseline on a non-debuggable build. Everything was measured with `debuggable=true` and R8 off.
2. Settle what the separator actually costs (1.8× disagreement) and whether it degrades over long runs
   (an unexplained 5.9× on one resumed run). If it degrades, nothing else matters.
3. Check whether the fp16 graph is running fp16 kernels or casting to fp32 — possibly 1.3–1.8× free.
4. Split the gate timer: "gate = 1.9 s" includes preprocessing, and the model alone is ~7 ms.
5. Run the music detector over the film for run-length and confidence distributions.

Then, in payoff order — savings on a 155-min combined job:

| | | |
|---|---|---:|
| **A1** | Skip the separator where there is no music (YAMNet, Apache-2.0, ~1 % of separator cost). Measured yield ~1.3–1.5× on film — the run-length distribution, not the duty cycle, is what limits it. | **25–45 min** |
| **S1** | Run the audio branch concurrently with the video branch | 15–30 min |
| **A2** | Separator overlap 25 % → 10 % — one constant, endorsed by Demucs' own README | 16–28 min |
| **V1** | Stop building a 640 px RGB bitmap nobody needs; feed ML Kit NV21 and the gate a 224² tensor | ~23 min |
| **V2** | Gate: reused direct buffer, and XNNPACK threads 8 → 2/4 (8 measured *worst*) | 10–20 min |
| **V3** | Gate: static INT8 — measured 2.92×, artifact already built | 10–15 min |
| | plus V4/V5/A3/S2–S5 and the cheap ones | ~25 min |

Roughly **1.7–1.9×, with no model training at all.** Replacing htdemucs (§6) is the next step and is a
real project. NPU offload is not viable and has been removed from the roadmap. Smart rendering —
re-encoding only the GOPs that contain faces — measures **1.9 %** and should not be built (§6b).

Two things here are not performance work and should be done regardless: **NudeNet is AGPL-3.0 in a
closed-source APK** (§5.4), and **`FaceTracker.kt:84` silently never censors any face ML Kit failed to
assign a tracking id to** (§7).

---

## 0. Review of `video-performance-overhaul-plan.md`

That plan is well researched and its *engineering judgement* is mostly right. Its **prioritisation is
wrong**, because it is built on a cost table that is off by 1.7× on the largest stage, and it spends
most of its page count on stages that are 4 % of the wall.

### What it gets right — keep

| Item | Why it stands |
|---|---|
| Remove NudeNet | The model does not do what the feature claims (`m0-spikes.md:35` — Einstein/Obama/Trump all score `FACE_FEMALE` 0.69–0.83). It is AGPL, 12.2 MB, and its removal unblocks a much bigger win than the 2.7 min it costs — see §5.1 |
| "Do not start with N-way section rendering" | Correct, and for a stronger reason than the plan gives — see §3 |
| Benchmark build type | Correct, and it is item #1, not Phase 0 infrastructure — see §4.1 |
| `retained = mixture − music` as the audio target | The right formulation. Mixture-consistent by construction, one decoder, and it fails safe toward keeping dialogue |
| Empty-EDL passthrough | Real, cheap |
| Reusable gate buffers / no per-call allocation | Real; the plan understates it (§5.2) |
| Duration fallback, all-detections-in-EDL, no-op censor guard | Genuine safety bugs. `FaceTracker.kt:84` is `val id = face.trackingId ?: continue` — a detected face with no tracking id is silently never censored |
| "Stop saying 1.3× realtime" | Correct, and it is the reason the plan's own cost table is wrong |

### What it gets wrong

**1. The audio number is unreliable, and it is ~65 % of the job.**
`perf-plan.md:86` carries separate at "~101 min, from M2's 0.65×". The repo uses "×realtime" in two
opposite senses (`m0-spikes.md:43` means wall/source; `long-film-followups.md:48` means source/wall),
and beneath that ambiguity the S23 measurements themselves disagree by **1.7–1.8×**. Normalising every
whole-stage measurement to per-chunk time using the shipped geometry (`STRIDE = 85_995` @ 44.1 kHz =
1.950 s of audio per chunk):

| Asset | separate wall | chunks | ms/chunk |
|---|---:|---:|---:|
| 12.8 s (`perf-plan.md:259`) | — | 7 | **2136** (directly timed, median chunks 3–7) |
| 30 s (`tasks.md:102`) | 47.0 s | 16 | 2938 (includes session load + stats pass) |
| 81.9 s (`Eta.kt:37`) | 93.6 s | 43 | **2177** |
| 300 s (`tasks.md:43`) | 204 s | 155 | **1316** |
| 634 s (`Eta.kt:38`) | 390 s | 326 | **1196** |

Short assets say ~2.1–2.2 s/chunk; long assets say ~1.2–1.3. Subtracting a generous 12 s of fixed
cost still leaves 1.90 vs 1.16. Feature length is therefore somewhere between **~95 and ~170 minutes**
and the repo cannot currently say which. Three live figures coexist: `long-film-plan.md:146` "~1.3×
realtime is the floor", `long-film-followups.md:373` "measured 1.15×, not 1.3×", and `Eta.MUSIC = 0.68`.

This is the single largest cost in the product and its uncertainty is larger than most of the wins
below. It is measurement #1 (§4.2).

**2. Because of (1), the plan's effort is allocated backwards.** Phases 5 and 6 — the resource-token
scheduler, S0–S4 schedule sweep, two render workers, continuous-render resume with a SIGKILL
injection matrix, Passthrough/Preserve/Compatible modes, the calibrated pixel-rate bitrate policy —
all target **render, which is 4 % of the wall**. Perfect render would save 9.7 minutes of ~258.

**3. It rules out the convert on a measurement that does not generalise.** "Parallel YUV row
conversion: faster conversion, no end-to-end gain" is true — on a 12.8 s clip. At feature length the
same loop is **~26 of the 70.5 analyze minutes** (§2). Both facts hold, for the reason in §3, and the
plan carries only the first.

**4. Its primary answer to both bottlenecks is "train a custom model."** Custom MobileNetV4 NSFW is
"primary production candidate"; custom compact MDX/TFC-TDF is "primary production direction". For a
solo developer, rights-cleared training data plus calibration plus subgroup reporting for two
production models is a multi-quarter program with no partial credit. It belongs behind every cheaper
option, not in front of them — and §5.5 shows the cheapest option is an off-the-shelf Apache-2.0 model
that may on its own beat what the training program was aiming for.

**4b. Its audio bake-off table has a factual error and a licensing hole.** It credits "DnR-trained
Open-Unmix" with *"5.7 GFLOPs and much higher CPU throughput than Hybrid Demucs"* and lists compact
BandIt/BSRNN as a quality challenger. In BandIt's own Table 1 the 5.7 GFLOPs figure is **Open-Unmix**;
**BandIt is 364.1 GFLOPs against Hybrid Demucs' 85.0**, and the paper's CPU benchmark measures BandIt
at ~0.3 chunks/s against Hybrid Demucs' ~1.1 — **the whole BandIt/BSRNN/Banquet family is ~3.7×
slower than what already ships.** Separately, the weights for Open-Unmix (CC BY-NC-SA), BandIt v1
(CC BY-NC) and Banquet (CC BY-NC-SA) are all non-commercial. Three of the plan's audio candidates
cannot legally ship, and its "first proof of concept" is one of them.

**4c. "Estimate only music and take the residual" does not make HTDemucs faster.** The plan presents
this as a compute win. HTDemucs has one shared encoder, one cross-transformer and one shared decoder;
the four stems are output *channels* of the final layer. Predicting one instead of four saves a few
hundred multiplies out of ~85 GFLOPs. It is a correctness and quality idea — a good one, see §5.10 —
but it buys no speed on this architecture, and the code already does the only part that scales with
stem count (summing kept spectrograms before a single iSTFT, `DemucsSeparator.kt:214`).

**5. It has no line for the 5.9× separator slowdown.** `long-film-followups.md:230`: a resumed run
averaged **11.2 s/chunk** against 1.9 s/chunk before the kill. Never explained, never controlled. If
the separator degrades with run length rather than with resume, feature-length audio is not 170
minutes, it is 900+, and every other number in every plan is irrelevant. This is measurement #1.

**6. Its entry gates block all work.** A versioned corpus across 8 groups (~60 assets, independently
annotated), four device classes, and one completed 155-minute combined baseline — before Phase 1.
The repo has had no QA set since M1 (`naqi-status`) and has exactly one device. Written this way, the
plan never starts.

### What I cut

Deleted outright: resource-token scheduler · S0–S4 sweep · two render workers · continuous-render
resume + SIGKILL matrix · Preserve/Compatible mode split · calibrated bitrate policy · face-detector
bake-off · optional localized explicit-content detector · the 4-device matrix · the 60-asset corpus.

None of these are bad ideas. They are 4 %-of-wall ideas, and this document is about the other 96 %.

The **face-detector bake-off** deserves a specific reason, since the v1 plan gives it a whole section.
ML Kit detection is 8.6 ms/frame — 19 % of analyze, ~13 min of a ~258 min job. No candidate is
meaningfully faster at equal recall, and the licensing is worse in every direction: SCRFD's weights are
non-commercial and InsightFace's terms reach the *models trained on their annotations*, which
plausibly contaminates YuNet, CenterFace, RetinaFace-mobile and ULFG alike; every YOLO-face repo is
Ultralytics-derived and therefore AGPL or enterprise-licensed; BlazeFace is ruled out by its own model
card (requires the face box to be ≥20 % of the image side, caps at ~10 detections). Swapping the
detector is a licensing, telemetry and dependency decision that should be made on those grounds when
someone wants to make it — not a performance item, and not a blocker for anything here.

---

## 1. The corrected budget

155.4-minute film, combined job, Galaxy S23, on charger. Sources: `tasks.md:84–103`,
`perf-plan.md:86–89`, `DemucsSeparator.kt:282–284,383`.

| Stage | Cost | Share | Provenance |
|---|---:|---:|---|
| **separate** (htdemucs) | **95–170 min** | **55–65 %** | 4 783 chunks × 1.20–2.14 s — see §0, point 1, unresolved |
| **analyze** @10 fps | **70.5 min** | **27 %** | measured (93 240 frames @ 45 ms) |
| render | 9.7 min | 4 % | measured (64 % in 6.2 min) |
| vote (NudeNet) | 2.7 min | 1 % | measured |
| mux + publish | ~5 min | 2 % | measured on shorter assets |
| **total** | **~183–258 min (3.0–4.3 h)** | | plan asserts 3.1 h as a point estimate |

No feature-length combined run has ever completed (`long-film-followups.md:253`), so every figure in
the "film" column of this document — including mine — is arithmetic on shorter runs.

Short clip, 30 s combined, same device (`tasks.md:102`): 69.8 s total — separate 47.0 · analyze 18.8
· render 2.6 · mux 0.9 · publish 0.2. **Separate is 67 % of a short job too.** Of its 47 s, ~13 s is
fixed overhead (87 MB ORT session load, two full audio decodes, AAC).

### Inside analyze — 45 ms per sampled frame

Per-call costs from `naqi-analyze-is-gate-bound` (gate 1900 ms / 64 calls, detect 1100 ms / 128 calls):

| Sub-step | ms/frame | min of 70.5 | Share |
|---|---:|---:|---:|
| YUV→RGB convert (Kotlin, `FrameSampler.convertRows`) | ~17 | 26.4 | 38 % |
| NSFW gate (ORT, every 2nd frame) | ~14.9 | 23.1 | 33 % |
| ML Kit detect | ~8.6 | 13.4 | 19 % |
| decode + tracker + progress | ~4 | 6.2 | 9 % |
| **sum** | **44.5** | **69.1** | vs 45 / 70.5 measured |

The fit is close enough to trust the shape. Note what it says: **at feature length the analyze pass
costs the *sum* of its sub-steps, not the max.** The `Channel(2)` producer/consumer split buys
nothing, because there is no idle core for the producer to run on.

### Against the product budget

PRD acceptance (`prd-video-filter-android.md:91`): 5-min 1080p30, both ops, SD 778G class, **≤ 25 min**;
music-only **≤ 15 min**. A 5-minute clip on the S23 is ~8.2 min today. A 778G at 2–2.5× lands at
17–21 min against a 25 min budget, and music-only lands at 11–14 against 15. **The product's own
acceptance criteria are currently marginal on the target device, and audio is why.** That is the
actual reason to do this work.

---

## 2. The thesis

> ~92 % of the wall is CPU-bound ONNX/ML Kit inference. Three levers work, in this order:
> **do less inference** (§5.5, §5.6, §5.1), **run the two branches at once** because the separator
> permanently leaves two cores idle (§5.8), and **move inference onto silicon nothing else is using**
> (§6). Splitting a stage into parallel sections is not a lever — it re-divides a saturated CPU.

---

## 3. Why "split it into sections and run them in parallel" cannot help here

This was the premise of the request, so it deserves a straight answer rather than a gate table.

Section fan-out converts wall time into throughput only when there is idle hardware for the extra
sections to run on. **Within** a stage there is none:

- **analyze** — the NSFW gate runs XNNPACK with `intra_op_num_threads = availableProcessors`
  (`Models.kt:199`), plus ML Kit and the convert coroutine on top. Already oversubscribed on 8 cores.
- **separate** — ORT CPU EP at `min(cores, 6)` (`DemucsSeparator.kt:392`), swept, and 6 beat 8.

Splitting the video into N sections re-divides the same saturated CPU N ways: N sections at 1/N speed.
That is why parallelising the YUV convert across cores cut `convert=` 24–32 % and moved analyze wall
**0 %** (`naqi-analyze-is-gate-bound`) — the cores were never free.

**Between** stages it is a different story, and this is where the parallelism instinct is right.

`separate` uses **6 of 8 cores, permanently**, for 55–65 % of the job. Not by accident: the sweep found
8 threads *slower* than 6 (2244 vs 2136 ms) because every intra-op barrier waits on the S23's little
cores. So two cores sit idle for one to three hours, and the fix is not to give htdemucs more threads —
that was measured — but to give the spare cores different work.

Ceiling on the win: `(2/8) × separate ≈ 24–42 min`, bounded above by the video branch's own 80 min, so
the video branch is not the limit. Realistically less, because the spare cores are the weak ones — that
is *why* they are spare. Call it 15–30 min, and measure it.

This was already computed and rejected once. `perf-plan.md:291-318` killed it on **memory alone**:
1.29 GB (separate) + 0.53 GB (segmented censor) ≈ 1.82 GB against a 1.5 GB PRD budget, with the exit
condition *"revisit only if the separator's peak drops below ~1.0 GB."* **That budget has since been
lifted.** The rejection has no remaining basis on an 8 GB device; on a 6 GB minimum-spec device it
still needs proving.

Only **render** (4 % of the wall) uses genuinely different silicon — hardware encoder plus GPU. Two
render workers, at the v1 plan's own 1.3× bar, would save 2.2 minutes.

**What is idle and worth taking:**

| Unit | Idle during | Usable for |
|---|---|---|
| 2 CPU cores | all of `separate` (55–65 % of the job) | the whole video branch — §5.8 |
| GPU | ~96 % of the job | YUV→RGB + downscale (§5.1); possibly the gate via LiteRT GPU |
| NPU / Hexagon | 100 % of the job | the gate, possibly the separator, via ORT QNN EP (§6) |

So: **branch-level overlap, yes — it is one of the largest single wins available. Section-level
fan-out, no.** They are not the same idea, and the difference is which resource is actually free.

---

## 4. Do this first — before optimising anything

### 4.1 Re-baseline on a non-debuggable build

`app/build.gradle.kts` has `release { optimization { enable = false } }`, and every number in §1 was
taken from a **debuggable** build (the soak autorun is `BuildConfig.DEBUG`-gated, `MainActivity.kt:60`).

Native code — ORT, ML Kit, MediaCodec — does not care. **The Kotlin YUV convert loop does**, and it is
26 of the 70.5 analyze minutes. A tight numeric loop typically gives back 1.3–2× when ART is allowed
to optimise it.

Add a `benchmark` build type: `debuggable = false`, release-equivalent compilation, `isMinifyEnabled`
matching release, and keep the `DEBUG_HOOKS` autorun/forced-segment hooks behind their own build field.
Re-run the 30 s and 193 s references. **Nothing below can be ranked until this number exists**, and it
may be the cheapest win in the document.

### 4.2 Settle what the separator actually costs

Two unresolved anomalies sit on the same stage, and **one run settles both.** The per-chunk instrument
already exists (`chunk N/total NNNNms`, `AudioPipeline.kt:91`). Run `movie-test.mp4` music-only to
completion, uninterrupted, and plot chunk time against chunk index.

- **The 1.8× disagreement (§0, point 1).** Short assets measure ~2.1 s/chunk, long ones imply ~1.2. If the
  curve starts high and settles low, the short-asset numbers were warm-up and the long-asset numbers
  are right — separate is ~95 min, not 170, and A1/A2 shrink accordingly. If it is flat at ~2.1, the
  long-asset numbers came from a different configuration and need chasing down.
- **The 5.9× resume anomaly.** `long-film-followups.md:230` — a resumed run averaged 11.2 s/chunk
  against 1.9 before the kill, compared first-20-chunks against last-81, and never controlled. If the
  uninterrupted curve also climbs to ~11 s, the separator degrades with run length and **everything
  else in this plan stops** until the cause is found: a film would never finish. Hypothesis worth
  testing first — arena OFF plus memory-pattern OFF (`DemucsSeparator.kt:377–378`, both required to
  stop lmkd kills) means each of ~4 700 chunks raw `malloc`/`free`s tens of MB of transient tensors,
  and native-heap fragmentation over thousands of cycles is exactly the shape of a monotonic slowdown
  a 7-chunk control cannot see.
- If the curve is flat and low, the anomaly is resume-specific and cheap to find: the per-chunk
  checkpoint write-plus-rename, the PCM append, and `feed()` running ahead through skipped chunks.

This is roughly a 3-hour unattended run on one asset. It is the highest-information hour available.

### 4.3 Check whether the fp16 graph is actually running in fp16

Potentially the largest single win in this document, and it costs one profiling run.

ORT's public docs still say the CPU EP has no fp16 ops. **That is out of date.**
`onnxruntime/core/providers/cpu/fp16/fp16_conv.cc` registers `FusedConvFp16`, gated on
`MLAS_F16VEC_INTRINSICS_SUPPORTED`, which is defined for ARM64; 1.25.0 release notes list FP16-on-CPU-EP
work. Every ARMv8.2-A core has native fp16 NEON arithmetic — including the 778G's Cortex-A78 and A55,
so this is not an S23-only path.

But the existing ORT profile already shows **`Cast` at 4.6 % of kernel time over 2 891 calls**
(`perf-plan.md` 3.3), which is the signature of a graph casting fp16→fp32 and back around fp32 kernels.
`perf-plan.md` read that 4.6 % as "casts are cheap, an fp32 re-export is not worth it" — the more
interesting reading is that the casts exist at all, meaning the Convs may not be resolving to the fp16
kernels.

Profiling the graph off-device confirms the casting is real and quantifies it: ORT inserts
`InsertedPrecisionFreeCast_*` nodes, **201 `Cast` calls per run costing 6.9 % of kernel time**, and the
CPU EP computes in fp32 — the model is *stored* fp16 and you pay the conversion without getting the
speed.

**That off-device run cannot settle the Android question, and it is important not to read it as if it
does.** `MLAS_F16VEC_INTRINSICS_SUPPORTED` is defined for `MLAS_TARGET_ARM64 && !__APPLE__` — macOS is
explicitly excluded from the fp16 kernels. So the 6.9 % is the *expected* result on a Mac and says
nothing about an S23, where those kernels may well be selected.

**Run it on device**, with `ORT_ENABLE_PROFILING`, and read the **resolved kernel names**, not just op
times. Two outcomes: the Convs already resolve to `FusedConvFp16`, in which case A0 is worth only the
~7 % of cast overhead; or they do not, in which case getting them onto the fp16 path is worth **1.3–1.8×
on 55–65 % of the job** for an export flag. It may also explain part of the 1.29 GB peak — materialised
fp32 weights are ~176 MB resident rather than 88 MB.

Related, and settled: **do not retry XNNPACK for htdemucs.** Two independent reasons. Its f16 GEMM
microkernels accumulate *in fp16* (`f16-gemm-*-neonfp16arith-*.c` uses `float16x8_t` accumulators with
no fp32 accumulator anywhere), which is the mechanism behind the spectral-branch corruption already
observed — the same class as `onnxruntime#18992`. And the XNNPACK EP supports only 11 ops
(`Conv`/`ConvTranspose`/pooling/`Gemm`/`MatMul`/`Softmax`/`Resize` plus quantised variants, all 2D
only), so a graph full of Slice/Pad/Transpose/GLU would partition-thrash with layout conversions at
every boundary. It is the wrong tool even with the fp16 bug fixed.

### 4.4 Lock a regression set, not a corpus

⚠️ **The asset situation is worse than the v1 plan assumes, and this needs fixing before §4.2 or §4.5
can run.** As of 2026-08-02 `qa-assets/` contains only `test-video.mp4` (12.8 s), `a week in my life
vlog.webm` (19 m, AV1/Opus) and `test-video-1.webm`. The five `women-*` assets — the container/codec
matrix and the face-censoring workhorse — were removed during this review, and **`movie-test.mp4`, the
155-minute film that every feature-length number in the repo derives from, is also gone.** `qa-assets/`
is gitignored, so none of it is recoverable from the repo.

Consequences: §4.2 (the separator curve) and §4.5 (run lengths on real film) both name `movie-test.mp4`
and cannot run until a feature-length asset exists again. Re-staging one is the first task in this
document. The container variants (AC-3, 5.1, Opus, fragmented-MP4-without-`sidx`, MKV) were cheap to
generate from one source with ffmpeg and should be regenerated the same way — they are the only
coverage of the non-AAC transcode path in §5.9's S2.

The v1 plan's 60-asset annotated corpus is why it never starts. Replace it with what can actually be
kept: one feature-length film, one 3-minute face-heavy clip, `test-video.mp4`, the regenerated
container variants, and the new AV1/WebM vlog — which is worth keeping deliberately, since it is the
first AV1, no-B-frame, Opus-audio source in the set and exercises paths nothing else does.

The gate is **not new ground truth**. It is: for a fixed input and fixed options, the EDL is
byte-identical to the pre-change EDL, and the output passes the existing frame/PTS/A-V checks. That
gates every mechanical change in §5 without annotating anything. Only model *replacement* needs
labels, and only for the model being replaced.

---

### 4.5 The music duty cycle — largely answered already

A1's value is **entirely** content-dependent: the ceiling on speedup is exactly `1 / music_duty_cycle`.
On a music video it is 1.0× and the work is wasted.

**Population statistic.** AVASpeech-SMAD (ISMIR 2021 LBD; Netflix + Georgia Tech; 160 × 15-min feature
film excerpts, *human* frame-level labels) gives music-active time as **43.4 % mean / 41.5 % median**,
p10 8.9 %, p90 83.6 %. TVSM-test (15 h TV) agrees at 43 %; OpenBMAT (27.4 h) at ~50 %.

**But duty cycle is the wrong number, and this is the finding that matters.** A chunk-level gate can
only skip a *whole* 2.6 s chunk. What governs its yield is the **run-length distribution of non-music**,
not the total non-music fraction. Measured with `inaSpeechSegmenter` on the app's exact chunk grid
(`SEG` 2.600 s, `STRIDE` 1.950 s, `MAX_SHIFT` 0.500 s):

| Asset | music | non-music | non-music in runs < 1 chunk | **chunks skipped @ T=0.5** |
|---|---:|---:|---:|---:|
| `women-music-3min-video.mp4` | 85.4 % | 14.6 % | **74.9 %** | **3.0 %** |
| 19-min talking-head vlog | 21.9 % | 78.1 % | 15.4 % | **46.4 %** |

14 % non-music bought 3 %. 78 % non-music bought 46 %. On the music-heavy asset there is no operating
point to tune at all — T=0.10 and T=0.99 both give 3.0 %.

Interpolating those two measured points to a 43 %-music film gives **~32 % of chunks skipped ⇒ ~1.5×**,
not the 2.4× the duty cycle alone implies. Before dilation and before a conservative threshold.
**Budget A1 at 1.3–1.5× on typical film content.** It is still the largest single audio win, but it is
not the 2× the raw duty cycle suggests, and the spread across titles (p10 8.9 % to p90 83.6 % music) is
far wider than the mean — roughly 40 % of films will get nearly nothing from it.

**The recall risk is worse than the yield.** On the vlog, **all 278 chunks skipped at T=0.5 contain at
least one speech frame** — so every skip decision is taken on dialogue audio, which is exactly where
quiet background music is hardest to see. OpenBMAT finds **~70 % of music in real broadcast is
background**, under speech or effects, and DnR's mixing targets put music at −24 LUFS against speech at
−17, i.e. **~7 LU below dialogue**. An energy threshold cannot do this job; it needs a real model, and
the model will be asked the hard question every single time.

Still to measure locally, and cheap: the same run-length and confidence distributions on
`movie-test.mp4`, which is the only feature-length source and the only one that is actually
representative. Report the confidence *distribution*, not a binary count — the number that decides
whether this ships is not "how much can be skipped" but "how much can be skipped at a threshold
conservative enough that quiet music under dialogue is never missed."

### 4.6 Split the gate timer — "gate = 1.9 s" is not 1.9 s of inference

`tGate` in `FilterWorker.kt:683-685` wraps the whole of `Infer.nsfw`: `createScaledBitmap`, `getPixels`,
a 150 528-iteration Kotlin float loop, `OnnxTensor.createTensor` (which copies a heap buffer into a
fresh direct allocation), `session.run`, and result extraction. Off-device the **model alone measures
7.3 ms** while the whole call is attributed to "the gate".

So the 23.1 min this document charges to the NSFW gate is some unknown split between a model that V3
makes 2.92× cheaper and preprocessing that V1/V2 delete outright. Until that split exists, V1, V2 and
V3 cannot be ranked against each other, and it is entirely possible that **preprocessing is the larger
half** — which would make V1+V2 worth considerably more than the ~31 min §5 assigns them.

Three `System.nanoTime()` pairs inside `Infer.nsfw`. Do it before anything else in §5.

Everything in §4 is under a day's work except §4.2, which is one unattended 3-hour run.

## 5. The work, ranked by measured payoff

Savings are against the §1 baseline (~258 min, S23, film). Verify each against §4.1's re-baseline.

| # | Change | Saves (film) | Effort | Risk |
|---|---|---:|---|---|
| **A0** | **Force the fp16 Convs onto fp16 kernels, if they aren't already (§4.3)** | **0 or 30–70 min** | S | none — measure first |
| **A1** | **Skip the separator where there is no music** | **25–45 min** | M | **recall** |
| **S1** | **Run the audio branch concurrently with the video branch** | **15–30 min** | M | memory, thermal |
| A2 | Separator overlap 25 % → 10 % | 16–28 min | S | seam quality |
| **V2** | **Gate: direct buffer, no per-call alloc, XNNPACK threads 8 → 2/4** | **~10–20 min** | S | none |
| V1 | Stop building an RGB bitmap for ML Kit; convert straight to the gate tensor | ~23 min | M | low |
| **V3** | **Gate: static INT8 — measured 2.92×, artifact already built** | **~10–15 min** | M | recall |
| V5 | Gate cadence 5 fps → 2 fps (raise `PRE_MS` to match) | ~9 min | XS | product |
| A3 | Sample the stats pass instead of a second full decode | ~5 min | S | level shift |
| S4 | Empty-EDL video passthrough | up to 9.7 min when it applies | S | low |
| V4 | Drop NudeNet | 2.7 min + 500 MB + unblocks V1, removes AGPL | S | product |
| S2 | `setRemoveAudio(true)` on the combined render | s to ~10 min | XS | none |
| A4 | Read only the kept stem out of ORT | few % of separate | S | none |
| S5 | Stop writing the final video three times | I/O only | M | low |
| A6 | Encode AAC at 44.1 kHz; delete the Sonic egress resample | small — but a real *quality* win | S | encoder support |
| S3 | Don't load htdemucs for censor-only; warm sessions during probe; probe once | ~10–20 s/job | S | none |
| A5 | Kotlin STFT: reuse the 3.67 MB/chunk buffer, stop promoting to `Double` | **unknown — measure first** | S | none |
| **B1** | **Replace htdemucs (SCNet, or TFC-TDF)** | ~50–115 min | XL | high |
| ~~B2~~ | ~~NPU offload~~ — **rejected outright**, see §6 | — | — | — |

Everything above B1 is roughly **~258 → 110–140 min on the pessimistic audio number**, without training
a single model — the same 2–2.5× the v1 plan hoped to get from a model program, and V3's artifact is
already built. B1 is the second 2× and is a proper project (§6). B2 turned out not to exist.

Two of these are measurements pretending to be tasks: **A5** has never been instrumented (it sits
inside the un-timed `separate` stage — 448 double-promoting FFT-4096 per chunk plus 3.67 MB of
allocation, which could be anywhere from 2 % to 15 % of the audio stage), and **A1**'s value is
entirely content-dependent. Size both before building either.

---

### 5.1 V1 — the analyze pass builds a bitmap nobody needs

Today: MediaCodec YUV → hand-rolled Kotlin per-pixel convert → 640-px upright ARGB bitmap → (a)
`InputImage.fromBitmap()`, which ML Kit converts *back* to its own internal format, and (b)
`Bitmap.createScaledBitmap(224)` → `getPixels` → `IntArray` → heap `FloatBuffer` for the gate.

Three conversions of the same pixels, at 230 400 px/frame, 93 240 times.

The 640-px RGB bitmap exists for exactly one reason: NudeNet face crops. **Remove NudeNet (V4) and
nothing needs it.** `FaceTracker.onFaces` then only needs box coordinates.

Then:
- **ML Kit** takes `InputImage.fromByteBuffer(..., IMAGE_FORMAT_NV21)`. YUV420Flexible → NV21 is a
  byte repack — no arithmetic, no `coerceIn`, near-memcpy on semi-planar output.
- **The gate** needs 224×224 NCHW float, at 5 fps. Write a second variant of `convertRows` that walks
  straight from the YUV planes into a reused **direct** `FloatBuffer` at 224×224.

Convert work drops from 93 240 × 230 400 px to 46 620 × 50 176 px — **an 89 % reduction**, ~26 min →
~3 min, and it deletes the gate's preprocessing (V2) in the same change.

**Verify the premise with one Perfetto run first (~15 min).** The atrace slices already exist by name:
`ImageCopy: NV12->I420`, `ImageCopy: NV12->NV12`, `ImageCopy: generic` in `Codec2BufferUtils.cpp`. Any
`ImageCopy:` slice in the trace means the conversion is being paid. The repo has the
`perfetto-trace-analysis` and `perfetto-sql` skills available.

Note the current `COLOR_FormatYUV420Flexible` request (`FrameSampler.kt:136`) is already correct and
should not change: `GraphicView2MediaImageConverter` *tries to alias* the mapped gralloc planes with no
copy and only falls back to libyuv when aliasing fails. Asking for a concrete layout like
`COLOR_FormatYUV420Planar` would guarantee a full-frame conversion on every Qualcomm NV12 component.

**The bigger cost is session-wide, not per-frame.** Configuring a decoder with no output Surface makes
`CCodecBufferChannel` set `C2MemoryUsage::CPU_READ` for the whole session, so the allocator picks a
linear, non-UBWC layout for **every** decoded frame — 279 000 frames on the film to serve 93 000
samples. That is bandwidth compression given up across the entire decode, and it is the real argument
for a Surface path.

**If the GPU route is taken, it is first-party — do not hand-roll it.** Media3 already ships:
- `ExperimentalAnalyzerModeFactory` — a Transformer configured for analysis rather than export.
- `ByteBufferGlEffect`, which exists explicitly to *"pass the frame to other heterogeneous compute
  components … another GPU context, FPGAs, or NPUs"*. Its `Processor.configure(w, h)` returns a `Size`
  and **"when the returned dimensions differ … the image will be scaled"**, and `getScaledRegion` gives
  the crop. Downscale and crop for free.
- `GlUtil.schedulePixelBufferRead` (non-blocking) + `mapPixelBufferObject` (blocks for the previous
  read) — the PBO async readback, already on the dependency list via `media3-common`.

Google split its own two implementations along exactly this axis: streaming → PBO async, one-shot
seek-and-grab → blocking `glReadPixels`. This app decodes linearly, so the PBO path is the right one.
Two models at different input sizes = two small FBO passes off the one `EXTERNAL_OES` texture with two
PBOs, still far cheaper than one 1080p readback.

**Caveat that must be re-gated, not assumed:** libyuv's YUV→RGB uses a divisor of 64 rather than 256 to
avoid 16-bit overflow, with *"maximum color error 3.5 / 14"*. Moving the conversion into a GL sampler
shifts pixels by a few LSBs, and the gate's thresholds were QA-tuned against the current path. Re-run
the regression set after the switch.

**Free companion, ~6 lines:** `Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES` marks samples
*"not depended on by other samples … any disposable sample can be safely omitted, and the rest of the
track will remain valid"*, and the renderer drops them before they reach the decoder. It works at any
API level, unlike `BUFFER_FLAG_DECODE_ONLY` (API 34+). Camera-original IPPP content has none; the
B-frame-bearing downloaded and transcoded films this app ingests are often 30–50 % droppable, and those
are disproportionately frames the 10 fps sampler discards anyway. Gate it on the observed drop rate
rather than assuming a number.

**Not changing: the linear decode.** Media3's own maintainers make the case — a seek within a GOP still
decodes every frame back to the keyframe (*"if the keyframes are every 5 s, and the content is 30 fps,
this might require decoding at least 30×4=120 frames — just to show you one"*), `MediaCodec.flush()` is
a blocking binder round-trip into the vendor HAL, and B-frames force a flush anyway
(`shouldFlushCodec()` when `maxNumReorderSamples > 0`). Linear decode wins for any GOP ≥ ~6 frames; a
100 ms sample interval is an order of magnitude clear of the crossover. `FrameSampler` is already right.

### 5.2 V2 — the gate allocates ~1 MB per call and hands ORT a heap buffer

`Infer.nsfw` (`ml/Infer.kt:31`) allocates per call: a 224×224 `Bitmap`, an `IntArray(50 176)`, and
`FloatBuffer.allocate(150 528)`. About 1 MB × 46 500 calls ≈ **46 GB of churn on one film.**

`FloatBuffer.allocate` is a **heap** buffer, so ORT copies it to native memory on every `run`. The
htdemucs path already got this right (`HtdemucsSession` uses `ByteBuffer.allocateDirect`); the image
path did not.

Fix: one reused direct `FloatBuffer`, one reused `OnnxTensor` of fixed shape, filled in place by V1's
direct YUV→tensor walk.

**And sweep the thread count — the current setting is probably the worst one available.**
`imageSessionOptions()` sets `setIntraOpNumThreads(1)` and hands XNNPACK `availableProcessors` = 8.
That structure is exactly per ORT's XNNPACK EP guidance and is right; the *number* was never swept.
Measured off-device on this model class:

| Config | inferences/s |
|---|---:|
| 1 session × 1 thread | 20.1 |
| **1 session × 2 threads** | **47.8** |
| 1 session × 4 threads | 42.3 |
| 1 session × 8 threads *(current)* | **19.5** |

**8 threads measured 2.4× worse than 2.** The mechanism transfers even if the absolute numbers do not:
an S23 has little cores that become stragglers in every parallel conv, which is the same effect that
made 6 beat 8 for htdemucs. A/B 2 / 4 / 6 on device. If it holds, it is a one-line change that is both
faster *and* frees cores for the YUV producer and ML Kit.

**Do not batch.** This was the obvious next idea and the ONNX already has a dynamic batch dim, so it was
free to test: batch 2 = 0.65×, batch 4 = 0.87×, batch 8 = 0.47× per frame against batch 1. Depthwise-
separable convnets saturate at batch 1 on CPU; larger batches only add cache pressure. The v1 plan's
batch-size sweep with a 10 %-improvement promotion gate can be deleted.

### 5.3 V3 — static INT8 gate, on the model you already ship

Already built and benchmarked off-device (ORT 1.27 CPU EP, round-robin interleaved, best-of-9×10):

| Model | ms | rel |
|---|---:|---:|
| GantMan MNv2-1.4 fp32 (current) | 7.29 | 1.00× |
| **GantMan MNv2-1.4 INT8** | **2.50** | **2.92×** |
| SwiftFormer-XS (`image-safety-classifier-xs`) | 9.38 | 0.78× |
| MobileNetV4-Conv-S (ImageNet, needs fine-tune) | 2.99 | 2.44× |

**2.92× and 17.3 → 5.1 MB, with no new model, licence, dataset or dependency.** Recipe that worked:
rewrite `AveragePool[7,7]` → `GlobalAveragePool` with symbolic H/W (bit-parity, max|Δ| 7.3e-11), then
`quant_pre_process` + `quantize_static(QDQ, QInt8/QInt8, per_channel=True)` calibrated on 100 real
frames from `women-music-3min-video.mp4`. ORT fuses it to 75 nodes / 52 `QLinearConv` — real integer
kernels, and XNNPACK registers `QLinearConv`, so the fast path exists on-device.

Accuracy: argmax agreement with fp32 is 91.1 % on 360 real frames, and `fires()` flips 8–20 of 360 —
but **after `intervals()` hysteresis that collapses to 95.1–98.8 % interval recall** (1.5–4.0 s missed
out of 180 s). Hysteresis absorbs most of the drift, which is the metric that matters. Calibration
method barely moves it (MinMax/Percentile/Entropy within one flip); the drift is intrinsic to 8-bit
activations on MobileNetV2's linear bottlenecks. Buy it back by **re-tuning `NsfwGate.TABLE` against
the INT8 model** — those constants are QA-tuned against fp32 today and were always going to be
re-tuned.

**Must be validated numerically on-device before it is trusted.** The repo has three separate fp16
corruption incidents on XNNPACK; an int8 path deserves the same suspicion.

**Do not lower input resolution instead.** This was the obvious alternative and it is measured worse:
argmax agreement vs fp32@224 is **71.7 % @192, 68.6 % @160, 65.6 % @128 — against INT8@224's 91.1 %**.
Interval recall 98.9 / 92.9 / 89.6 % vs INT8's 98.4 %. Dropping to 192 costs 3–7× more decision drift
than INT8 for less speed — the classic train/test resolution discrepancy. Resolution is only safe if
the model is fine-tuned at the target resolution, which is a training project.

**Do not swap the model.** Every candidate was checked: the 2026 "edge-optimised" SwiftFormer-XS is
**22 % slower** than the 2020 model on an ARM CPU (equal MACs, but 362 nodes of transformer glue with
only 84 XNNPACK-eligible; its published 0.7 ms is on the iPhone Neural Engine). Marqo-384 is ViT-Tiny
at 384² = 3.2 GMACs, 5.5× current compute. Every ViT-Base option is ~327 MB. And on the only
independent benchmark that exists — UnsafeBench (CCS'25) — every model card's self-reported ~98 %
lands at **59–77 %** (FalconsAI 59.0 %, NudeNet 68.0 %, AdamCodd 69.0 %). Treat vendor accuracy claims
as marketing.

**Two of the five most-downloaded NSFW models on HuggingFace are non-commercial licensed**
(`TostAI/nsfw-image-detection-large` CC-BY-NC-SA, `giacomoarienti/nsfw-classifier` CC-BY-NC-ND).
Reaching for "the popular one" ships a violation.

### 5.3b V5 — gate cadence 5 fps → 2 fps

Every commercial video-moderation service samples far below 5 fps: AWS Rekognition Video at 3 fps and
recommending 0.3–1 fps when self-sampling *"given the redundancy of information in videos"*, Hive at
1 fps, Google Video Intelligence ≈ 1/s. The app runs 5.

But cadence is **a minimum-event-duration guarantee, not an accuracy dial**: 5 fps catches anything
≥200 ms, 1 fps only ≥1 s. This is a product decision — what is the shortest shot the app promises to
catch? And if cadence drops, **`NsfwGate.PRE_MS` must rise to at least the sample interval** (it is
500 ms today, which is already below a 1 fps interval).

No published accuracy-vs-fps curve exists for adult content in *video*. The adaptive-sampling
literature (SCSampler, AdaFrame, OCSampler, FrameExit) optimises video-*level* labels, not temporal
localisation, and must not be cited as licence to sample sparsely. Measure your own curve on the QA
assets — half a day, and worth more than the entire literature.

Cheap companion: **shot-boundary gating**. A luma histogram is essentially free inside the existing
`convertRows()` YUV walk, and PySceneDetect's histogram detector reaches 89.84 % recall on BBC. Bias
recall-first — a false cut costs exactly one CNN call. (Frame-difference gating is a different and
worse idea: NoScope's own ablation gives it 3× against the cheap-model cascade's 340×, and its authors
state it only works on static-camera video. Skin-tone prefiltering is a hard no — Fleck & Forsyth's
own filter passes only 79.3 % of nude images, so it discards a fifth of true positives before the CNN
runs, and it fails unevenly across skin tones.)

### 5.4 V4 — remove NudeNet

**The licensing is the reason to do this, and it is not a performance question.** NudeNet 320n is
**AGPL-3.0** and is currently bundled in a closed-source commercial APK
(`app/src/main/assets/models/nudenet_320n.onnx`, 12.2 MB). Either the obligations are deliberately
accepted with legal advice, or the model comes out. Everything below is a bonus on top of resolving
that.

The model also does not do what the feature claims (`m0-spikes.md:35`: `FACE_FEMALE` fires 0.69–0.83 on
male portraits while `FACE_MALE` stays ≤0.07, reproduced against the upstream pip package), and on the
one independent benchmark that exists it scores 68.0 % on UnsafeBench-Sexual against its own marketing.
It holds ~500 MB of crop bitmaps through the analyze pass (`naqi-long-film-phase0`).

Direct saving 2.7 min and 12 MB; the real value is unblocking V1 and deleting `GenderVoter`,
`blurUnknownFaces`, and the whole crop-retention machinery.

This is a **product change** — the feature becomes "censor all detected faces". Rename the option and
update the PRD, strings, and store copy in the same milestone. It is also the honest description of
current behaviour: the vote already censors ~every face (`m0-spikes.md:35`).

### 5.5 A1 — gate the separator on music activity

htdemucs runs on every 1.95 s of audio regardless of content. A film soundtrack is not continuously
scored: dialogue-only stretches, room tone and silence each get a full 2.1 s of four-stem separation
and produce output that should have been the input.

Gate it. On a chunk with no music, **pass the mixture through bit-exact** — faster *and* higher
quality, because separating a music-free chunk can only add artifacts.

**Use YAMNet.** Apache-2.0 (tensorflow/models), MobileNet-v1 depthwise-separable, 3.7 M weights,
**69.2 M multiplies per 960 ms frame** — about **72 MMAC/s against Hybrid Demucs' 14.2 GFLOP/s, i.e.
~1 % of separator cost.** 16 kHz mono, 521 AudioSet classes. No training, no licensing surface.

Ship it as **ONNX through the existing ORT runtime**, not via MediaPipe Tasks Audio. Same result, one
fewer dependency, one fewer model format, and it reuses `NaqiModel`/`ModelSmoke`/`ModelDownloader`
exactly as they stand — a new enum entry with a sha256 and a shape. The 16 kHz mono input also comes
free: `AudioDecoder` already produces f32 PCM, and the gate can read the stats pass rather than a
third decode.

The gate score is the max over two contiguous class ranges in `yamnet_class_map.csv`:

- **132–276** — the music block (`132 Music`, `133 Musical instrument`, … `265 Soundtrack music`,
  `266 Lullaby`, `267 Video game music`, … `276 Scary music`). 277 is `Wind`; natural sounds start there.
- **24–32** — vocal music, which sits separately: `24 Singing`, `25 Choir`, `26 Yodeling`, `27 Chant`,
  `28 Mantra`, `29 Child singing`, `30 Synthetic singing`, `31 Rapping`, `32 Humming`.

Including 24–32 matters: it catches a-cappella singing, the exact case a DnR-v3-trained music model
gets wrong (§5.10).

§4.5 measures the yield at **~1.3–1.5× on typical film content** — governed by the run-length
distribution of non-music, not by the duty cycle. Design:

- **Fail toward separating.** Bias the threshold hard toward false positives. A missed music chunk is
  an audible product failure; a spurious separation costs 2.1 s.
- **Dilate and hysteresis.** Never gate off within ±2 chunks of any music-positive frame — that
  protects fade-ins, fade-outs and stings.
- **Align to the OLA stride** so gate decisions land on chunk boundaries.
- **Pass through bit-exact**, not through the overlap-add path, so no seam is introduced.
- **Silence is free** — a chunk under a peak/RMS floor needs no model at all.

Prior art on this exact task: the mp3d entry at CDX'23 built MRX-C = MRX plus a CRNN predicting source
activity labels, and it beat the plain MRX baseline. Activity gating for cinematic separation is
published, not speculative.

If YAMNet's 960 ms frame proves too coarse or too heavy, Microsoft's MusicNet
([arXiv 2110.04331](https://arxiv.org/abs/2110.04331)) is the proof that this fits in **0.22 MB** at
**11.1 ms per 9 s clip, 81.3 % TPR at 0.1 % FPR** — but its weights were never released, so it is an
architecture to reimplement, not a drop-in. Start with YAMNet.

Content-dependence is the whole risk, which is why §4.5 measures the duty cycle before this is built.

### 5.6 A2 — overlap 25 % → 10 %

`STRIDE = int(0.75 × SEG)` (`DemucsSeparator.kt:283`) is Demucs' `apply.py` default, inherited
unexamined. Chunks scale as `1/(1−overlap)`, so 25 % → 10 % is **16.7 % fewer inferences, a 1.20×
throughput win**, for one constant.

It is not cargo cult so much as an untested default, and the model's own author says so — the Demucs
README: *"the default of 0.25 (25 %) … is probably fine. It can probably be reduced to 0.1 to improve
speed a bit."* Reference implementations do not agree with each other either: UVR treats 50 % as best
quality, and BandIt evaluates at a 0.5 s hop on 6 s chunks — 91.7 % overlap, twelvefold redundancy.

**No published SDR-vs-overlap ablation exists for any of these models.** Nobody has measured it, so
this has to be measured here. It stays mathematically sound at any overlap below 50 % because the
triangular window is normalised and `wsum` already divides out the window sum
(`DemucsSeparator.kt:102-105`, matching `transition_power = 1`).

Gate on boundary PCM against the 25 % reference — transients, quiet passages, music onsets — using the
same spec/wave-agreement dB metric the segment sweep already used (`m0-spikes.md:43-45`). Test 10 %
and 5 %. One dependency: `skipChunks` assumes `ceil(SEG/STRIDE) = 2` (`DemucsSeparator.kt:82-91`),
which still holds at both — make it an explicit assertion rather than a comment.

One dependency: `skipChunks` in the resume path assumes `ceil(SEG/STRIDE) = 2` (documented at
`DemucsSeparator.kt:82–91`). At 10 % overlap that is still 2. At 5 % it is still 2. Safe, but the
assertion should become explicit.

### 5.7 A3 — stop decoding the whole track twice

`AudioPipeline.removeMusic` runs `AudioDecoder.stats` over the entire track to get one mean and one
std, then decodes it again to feed the separator.

The normalisation is exactly invertible — it is applied on feed and undone on emit — so its only job
is presenting the model with roughly unit-variance input. A scalar estimated from a 10 % stratified
sample is within a fraction of a dB of the full-track value. Saves a full decode of a 155-minute
soundtrack, and it is a visible chunk of the 13 s fixed overhead on a 30 s clip.

Verify by computing both and reporting the delta on the regression set; if the resulting output
differs measurably, keep the full pass and take the loss.

### 5.8 S1 — run the two branches at once

`runCombined` (`FilterWorker.kt:615–628`) is analyze → render → separate → mux, strictly sequential.
There is no `async` or stage-level parallelism anywhere in the file.

The win is not that render uses different silicon (it does, but render is 4 %). The win is that
**`separate` holds only 6 of 8 cores for 55–65 % of the job**, and cannot use more — 8 threads
measured slower (§3). Running the video branch alongside it soaks up cores that are otherwise idle for
one to three hours. Ceiling `(2/8) × separate`; realistically 15–30 min because the spare cores are
the little ones.

Shape: start `separate` at t=0 on its own coroutine, run analyze → render alongside, join before mux.
Both branches already checkpoint independently, so the failure model does not change.

What must hold before promoting it:
- **Peak RSS.** 1.29 + 0.53 ≈ 1.82 GB measured (`perf-plan.md:305-318`). Fine on 8 GB, unproven on the
  6 GB minimum spec. Measure with `VmHWM`, and keep a sequential fallback keyed on available memory.
- **Thermal.** The Phase 0 soak held thermal status 0 for 79 min *on charger* with one branch running.
  Two branches for three hours is a different question. Demote to sequential at severe status, at a
  chunk boundary.
- **A completed combined job must actually be faster.** Not a stage, the job.

The v1 plan's S0–S4 sweep is not needed to find this. There is one schedule worth testing, and this
is it.

### 5.9 The cheap ones

- **S2** — `runCombined` renders with `segment = null` → `setRemoveAudio(false)` (`RenderPipeline.kt:98`),
  so Transformer writes the source audio into `render.mp4`; `Remux.mux` at `:628` then reads only the
  video track out of it. For an AAC source that is a wasted transmux and a few hundred MB. For a
  non-AAC source (MKV/Opus, AC-3) media3 cannot transmux, so it is a **full audio decode + AAC encode
  that is then discarded** — measured at 12.9 s per 193 s track, so ~10 min on a film. One boolean.
- **A4** — `HtdemucsSession.infer` copies all four stems out of ORT every chunk (`:348-349`): 18.35 MB,
  of which 9–14 MB is drums/bass that `keep` discards immediately. Read only the kept stems' slices.
- **S5** — the final video is written to disk three times: `render.mp4` → `mux.mp4` → MediaStore
  (`FilterWorker.kt:616`, `:628`, `:633`). ~1.7 GB each way on the reference film. `m0-spikes.md:9`
  records that a multi-sequence `Composition` can mux video and audio in one pass, which removes one
  full generation. The MediaStore copy is separately worth a larger buffer plus cancellation.
- **A6** — `AacWriter` hardcodes a 44 100 → 48 000 Sonic resample (`AacWriter.kt:31-39`). AAC-LC
  encodes 44.1 kHz natively. The resample buys nothing and costs the ~−27 dB in-band distortion
  `tasks.md:58` records, on ingest *and* egress. This is primarily a **quality** fix; take it as one.
- **A5** — `Stft.forward` allocates a fresh 3.67 MB `FloatArray` per chunk (`Dsp.kt:134`) — ~17.5 GB of
  large-object churn over a film — and the FFT butterflies promote to `Double` and back
  (`Dsp.kt:52-58`). Both trivially fixable. **But the whole `separate` stage is un-instrumented
  internally**, so this could be 2 % or 15 %. Time STFT / ORT / iSTFT / overlap-add / PCM write
  separately before touching any of it.
- **S3** — `ModelSmoke.run()` creates an ORT session for the 87 MB htdemucs graph even for a
  censor-only job, on the pick screen. Load it only when music removal is selected, and warm sessions
  during probe/preflight. `FrameSampler.probe()` runs 4× for an unsegmented censor job and N+3 for a
  segmented one, and `resolveBitrate` opens an extractor *per segment* — 35–70 container opens per
  film job. Probe once, pass it down.
- **S4** — empty EDL → transmux. There is no `edl.isEmpty()` guard anywhere in `render/`; the effect is
  attached unconditionally (`RenderPipeline.kt:99-104`), and `setVideoMimeType(H264)` plus
  `HDR_MODE_TONE_MAP_HDR_TO_SDR` mean an HEVC or HDR source is always converted whether or not anything
  is censored.

  **The mechanism matters: pass an *empty* effects list, not a no-op effect.**
  `TransformerUtil.shouldTranscodeVideo()` ends with
  `return !combinedEffects.isEmpty() && maybeCalculateTotalRotationDegreesAppliedInEffects(…) == -1;`
  — so an empty list makes Transformer skip decoder, GL and encoder entirely and do a container copy. A
  `CensorGlEffect` that happens to draw nothing still costs the full decode→GL→encode.

  **This is job-level only. Do not extend it per-segment.** It is tempting — on a film most 5-minute
  segments contain no regions — but a transmuxed segment carries the *source* codec configuration and a
  re-encoded one carries the encoder's, and `Remux.concat` can only write one track format
  (`RenderPipeline.kt:81-83` derives one bitrate for the whole job precisely for this reason).
  `MediaMuxer` exposes no way to write multiple `stsd` entries in a single track. Mixing the two would
  produce a file whose second sample entry is silently wrong. Job-level passthrough is safe and cheap;
  per-segment passthrough is a muxer project, and render is 4 % of the wall.
- **Progress posts** — `AudioPipeline.kt:92`, `:267`, `:333` post progress per chunk with no
  `lastPct` guard: ~4 800 `setProgressAsync` + `setForegroundAsync` calls where ~96 distinct values
  exist. The identical bug in analyze measured −12.2 % when fixed (`perf-plan.md` 1.1). Async here, so
  probably seconds — but it is two lines.

---

### 5.10 A1 also improves quality, which is why it outranks a model replacement

`prd-video-filter-android.md:80` already states the stem tradeoff plainly and accepts it:

> `vocals` keeps dialogue + any singing, drops everything else (movies: clean dialogue, SFX lost).
> `vocals+other` keeps SFX/ambience at the cost of melodic-music leakage. Known and accepted tradeoff.

That is honest and correct, and it is not a bug — but note what it means: **neither option removes
singing, and neither delivers "remove music, keep dialogue and effects."** `vocals` destroys every
sound effect; `vocals_other` leaks every guitar, string and synth. The v1 plan's own product target
("instrumental and sung music are removed; spoken dialogue and sound effects are retained") is met by
neither, which is the honest justification for B1 existing at all.

The reason this belongs in the performance plan: **A1 partially closes the gap for free.** On a chunk
the gate calls music-free, the mixture passes through bit-exact — so in `vocals` mode the sound
effects in every dialogue-only stretch are *preserved* instead of destroyed, and in `vocals_other`
mode nothing is touched that did not need touching. The gate is a speed change and a quality change at
the same time, on the documented limitation, without retraining anything.

It does not fix the singing case — sung music still reaches the separator and still survives it. Only
B1 fixes that.

## 6. The one big bet, and the one that turned out not to exist

Only start these once §5 is measured and the gap to target is known.

### B1 — replace htdemucs

Only worth starting if §5 leaves the target short, or once §5.10 forces the issue. Order:

**1. Check whether fp16 is actually running as fp16 (free, do this in §4).** ORT's public docs still
claim the CPU EP has no fp16 ops; that is **out of date**. `onnxruntime/core/providers/cpu/fp16/`
registers `FusedConvFp16` gated on `MLAS_F16VEC_INTRINSICS_SUPPORTED`, which is defined for ARM64, and
1.25.0 release notes list FP16-on-CPU-EP work. Every SoC from ARMv8.2-A onward — including the 778G's
Cortex-A78/A55 — has native fp16 NEON arithmetic. But the existing ORT profile shows **`Cast` at 4.6 %
over 2 891 calls**, which is what a graph casting to fp32 and back looks like. If the Convs are
resolving to fp32 kernels, forcing the fp16 path is worth **1.3–1.8× for a profiling run and an export
flag.** Confirm with `ORT_ENABLE_PROFILING` and read the resolved kernel names.

**Where htdemucs' time actually goes**, from an ORT profile of the shipped graph (42.4 M params, opset
18, 1531 nodes, **every dimension a literal — the 2.6 s segment is baked in, zero symbolic dims**):

| Group | Share |
|---|---:|
| Transformer (MatMul + Softmax + LayerNorm + Erf + Gemm) | 33.6 % |
| Conv + ConvTranspose | 33.2 % |
| Elementwise | 15.7 % |
| fp16↔fp32 casts | 6.9 % |
| Layout (Reshape/Transpose/…) | 5.4 % |
| InstanceNormalization | 3.6 % |

Ten `Softmax` = the 5 cross-transformer layers × 2 branches. **Attention is only a third of the cost**,
so a perfect attention optimisation caps out at ~1.5× — conv is an equally large target. There is no
LSTM/GRU and no custom domain; every op is standard `ai.onnx`. The XNNPACK problem is not exotic ops
but *fragmentation*: 74 `InstanceNormalization`, 56 `Erf`, 26 `LayerNormalization` and more, ~15 % of
nodes, interleaved throughout, which would shatter the graph into many partitions.

**2. SCNet-small — the best replacement on the board.** 10.08 M params, MIT code, published CPU
**RTF 0.669 vs HTDemucs 1.38 — 2.06× faster with +1.5 dB SDR** (9.00 vs 7.52 on MUSDB18-HQ), and a 4×
parameter cut that should reach the 750 MB target. Cost: a training run on DnR v3 (CC BY-SA, so
retraining is legal), ONNX export of a dual-path bi-GRU bottleneck, and on-device memory validation.
It will never run on XNNPACK or QNN — the GRU bottleneck rules that out — but the CPU EP is where you
already are.

**3. TFC-TDF-UNet v3, 2-output.** Pure 2D conv + time-distributed FC, **no RNN, no attention** — the
only family that keeps an accelerator door open, because it is the only one XNNPACK's 11 supported ops
can actually cover. Code MIT (kuielab), training harness MIT (ZFTurbo), dataset CC BY-SA. Highest
ceiling, highest effort.

**Licensing is the real wall here, not quality.** Verify before investing in any checkpoint:

| Artifact | Code | Weights | Shippable? |
|---|---|---|---|
| Demucs (current) | MIT | MIT | yes |
| SCNet | MIT | unstated — verify | probably |
| MRX / cocktail-fork (MERL) | MIT | **MIT** | yes — the only ready-made permissive DnR model |
| BandIt v2 | Apache-2.0 | CC BY-SA 4.0 | yes, with share-alike |
| BandIt v1 | Apache-2.0 | **CC BY-NC 4.0** | **no** |
| Banquet | MIT | **CC BY-NC-SA 4.0** | **no** |
| Open-Unmix (all UMX) | MIT | **CC BY-NC-SA 4.0** | **no** |
| DnR v3 dataset | — | CC BY-SA 4.0 | retraining is legal |

MRX is the only free lunch and its quality is weak (Music SI-SDR 4.2 dB on DnR; −2.49 dB global on
real film at CDX'23). One day of on-device RTF measurement establishes a floor — it is BLSTM on
magnitude, so it lives in Open-Unmix's cheap FLOP regime, not BandIt's.

**Rejected outright:** BandIt / BandIt v2 / BSRNN / Banquet as a *speed* play — 3.7× slower than
Hybrid Demucs on CPU by the authors' own benchmark. BS-/Mel-Band RoFormer — 72–93 M params per stem.
Apollo — codec-artifact restoration, wrong task. DeepFilterNet3 / GTCRN / DTLN — trained to treat
everything that is not speech as noise, so `mixture − speech` destroys every sound effect. INT8 — no
trustworthy published evidence for any spectrogram-masking separator, and STFT magnitudes span 60–100 dB
inside a single tensor, so per-tensor scales are hopeless. Segments below 2.6 s — the existing sweep
already shows sublinear memory return against 2 dB of quality (`m0-spikes.md:43-45`).

### B2 — NPU offload — **rejected, not deferred**

I expected this to be the second big bet. It is not viable, for four independent reasons, any one of
which would be sufficient:

1. **ORT's QNN EP is broken on Android right now.** `onnxruntime-qnn` #679 (open, 2026-07-29): the
   plugin EP registers but advertises no QNN device, because ARM64 NPU discovery calls `opendir("/dev")`
   and SELinux denies it for `untrusted_app`. The fix, PR #683, is unmerged **and requires API 31.**
   This app is minSdk 29.
2. **Size.** `qnn-runtime` is 68 MB compressed / 206 MB uncompressed; a minimum viable AOT payload is
   ~26 MB (one DSP arch) to ~105 MB (a real device fleet), plus ~88 MB more for on-device JIT. The
   release APK is already very large with ORT, youtubedl-android and three bundled models.
3. **The ceiling is small and the marshalling eats it.** Qualcomm AI Hub reports MobileNet-v2 on
   QCS8550 — the S23's SoC — at 0.634 ms float / 0.460 ms w8a8. But AI Hub measures pure graph execute;
   the same class of model measured 2 ms on AI Hub against ~5 ms end-to-end through a delegate. Roughly
   3 ms of fixed marshalling against ~1 ms of compute, at 5 fps.
4. **Licensing.** The Qualcomm AI Stack licence permits app-embedded redistribution but enumerates
   *"biometric categorization systems (sensitive characteristics)"* as an Unacceptable Risk Application.
   The feature as originally specified votes on apparent gender from detected faces. V4 removes that,
   but it is a question for counsel before any Qualcomm binary ships.

**LiteRT NPU is worse.** LiteRT #7891, on a Galaxy S23 Ultra, logs *"NPU initialised successfully"*
while actually running XNNPACK at 146 ms; with the official NPU libraries it reached **5.6 s per
inference**. #7858 took 23 days to diagnose as "the APK didn't include the QNN `.so` files, so all NPU
calls silently fell back to CPU." Silent fallback is the norm, not the exception.

**NNAPI** is deprecated as of Android 15. Zero work warranted.

Delete NPU offload from the roadmap. The GPU is still worth using — but through `ByteBufferGlEffect`
(§5.1), for the pixel work, not for inference.

---

## 6b. Settled — do not spend time re-investigating these

Each of these was chased down during this review and closed. Recorded so nobody re-opens them.

| Question | Answer |
|---|---|
| Can the decoder downscale its own output? | **No.** Codec2 defines `raw.scaled-size`/`raw.scaled-crop`, but `CCodecConfig` maps neither, and `MediaFormat` has no key. `KEY_MAX_WIDTH/HEIGHT` are an adaptive-playback *ceiling*, the opposite request. `SurfaceTexture.setDefaultBufferSize` is documented as overridden by video producers. `ImageReader` dimensions are likewise ignored — Media3 hardcodes 640×360 and comments *"the default width and height are ignored when writing from MediaCodec"*. GPU downscale is the only option. |
| Do concurrent hardware **encoders** speed up render? | No — render sits at the hardware encode ceiling. |
| Is **smart rendering** worth it — copy untouched GOPs, re-encode only the ones with faces? | **No: measured 1.9 %.** On `women-music-3min-video.mp4`, 68 % of sampled frames contain a face; after the app's `PRE_MS 500 / POST_MS 1500` hysteresis that merges into 4 intervals covering 93.3 % of the timeline, leaving **exactly one of 90 GOPs untouched**. The result is robust: it is 1.9 % at every detector threshold from 0.3 to 0.8, and **even with hysteresis set to zero it is 7.2 %** — faces are simply everywhere. Only at a threshold that misses two-thirds of real faces does it become interesting, which is not an operating point a censoring app can use. Separately, that file is **open-GOP** (106 I-frames but only 90 IDR), so usable splice points are fewer than the I-frame count suggests. |
| Do concurrent **decoders** have headroom? | Yes, ~2× provably: the Qualcomm driver caps one session at 480 fps against a ~953 fps-of-1080p core budget. Irrelevant here — analyze is gate-bound, not decode-bound — but it means the decoder is never the constraint. |
| `KEY_OPERATING_RATE` / `KEY_PRIORITY` on the decoder? | Advisory `C2Tuning` only. Already tried and removed on 2026-07-28 (−0.5 %, noise). Media3 keeps it off by default and warns MAX_VALUE is *slower* on some devices. |
| `KEY_LOW_LATENCY`? | Actively harmful for batch. It shrinks frames-in-flight from ~15–23 to ~7, i.e. less hardware pipelining, on a job that only wants throughput. Media3 never sets it. |
| `MediaMetadataRetriever.getFramesAtIndex` for sampling? | Worst available tool. It reuses the decoder only for *strictly consecutive* indices; sampling 10 of 30 fps never hits that, so it pays a full codec create/configure/seek/teardown per frame. The current sequential decode is right. |
| Seek-per-sample instead of linear decode? | No. A seek within a GOP still decodes back to the keyframe, `MediaCodec.flush()` is a blocking binder round-trip into the vendor HAL, and B-frames force a flush regardless. |
| Is `COLOR_FormatYUV420Flexible` the right request? | Yes, keep it — it is the one choice that lets the converter alias gralloc planes without a copy. |

One latent risk worth pinning while nearby: `RenderPipeline` leaves `VideoEncoderSettings`
`operatingRate`/`priority` unset, and Media3's overflow workaround for SM8550 — this exact SoC — is
guarded on `SDK_INT` 31–34. At API 35+ Media3 reverts to requesting `OPERATING_RATE = Integer.MAX_VALUE`
on a chipset Google blacklisted for throwing at configure. Rendering works on the current device, so
this is not an active bug; pin it anyway with
`setEncoderPerformanceParameters(operatingRate = 1000, priority = 1)`.

## 7. Correctness work that is not optional

Faster wrong output is a regression. These are small and they gate §5.

1. **`FaceTracker.kt:84` — `val id = face.trackingId ?: continue` silently discards any detection ML
   Kit found but did not assign a tracking id to. It is never blurred.** This is the worst bug in the
   pipeline, because the failure is content-correlated rather than random: ML Kit's tracker is
   motion-based with no re-identification (its maintainers, on the official repo, issues #235 and
   #363: a face that leaves and re-enters becomes a new id, and fast motion churns ids), so ids are
   least likely to be populated exactly at scene cuts and fast pans. Compounding it, the app samples
   10 fps out of a 23.976 fps source, which presents the tracker with ~2.4× the per-step motion it is
   designed for, and there is no API to tell it the sampling interval.

   Cheapest fix, independent of any detector change: fall back to an IoU match against the previous
   frame instead of `continue`, and censor immediately either way. Size it first by logging raw
   `faces.size` against the post-filter count on one real clip — that number is currently unknown.

   Note that **V4 largely dissolves this class of problem**: once every detected face is censored,
   tracking ids matter only for interpolating across gaps, not for deciding whether to blur. The
   feature stops depending on a tracker that was never built for sampled frames.
2. Duration-probe failure can clamp NSFW intervals to `[0, 1 ms]`, exposing everything after.
3. Prevent the no-op combination `blurAmount = 0 && grayscale = false`.
4. Whole-frame censor when active regions exceed renderer capacity, rather than dropping the smallest.
5. Count frames actually fed to the separator; today missing input can be zero-padded to the expected
   length and look correct.
6. Keep face sampling at 10 fps. 5 fps was tried and exposed a face.

---

## 8. Measurement protocol

Short, because a protocol nobody runs measures nothing.

- Every run on the `benchmark` build type (§4.1). Never compare across build types.
- Paired runs: same source bytes, same options, randomised order, comparable thermal start, on charger.
- 5 repeats for short assets, 3 for long. Report median and spread, not a mean.
- Report cold and warm separately — model load is 13 of the 47 s on a 30 s clip.
- Per-stage `SOAK` lines already exist. Add per-sub-step timing inside analyze (convert / detect /
  gate / rest) — §1's split is derived, not measured, and V1 is sized from it.
- Validate output before accepting any number: EDL byte-identity for mechanical changes, plus
  duration, PTS mapping, and A/V drift.
- One device is the honest scope today. Report S23 numbers as S23 numbers; the 778G budget in
  `prd-video-filter-android.md:91` stays formally unverified until that hardware exists. Do not
  average across SoCs.

---

## 9. Targets

Against the §1 baseline, on the S23, after §4.1 re-baselines it.

| Job | Now | After §5 | Lever |
|---|---:|---:|---|
| Film, combined | 183–258 min | 95–150 min | A1, A2, S1, V1–V3 |
| Film, censor only | ~83 min | 30–40 min | V1, V2, V3, V4, S4 |
| Film, music only | 95–170 min | 55–115 min | A1, A2, A3 |
| 30 s clip, combined | 69.8 s | ~30 s | A1, A2, V1, S3, S1 |

Roughly **1.7–1.9×**, dominated by the audio items. It was 2× before §4.5's run-length measurement cut
A1 from 2.4× to ~1.4×; that is what measuring first is for.

Add **A0** on top of all four if §4.3 finds the fp16 Convs are falling back to fp32 — that is another
1.3–1.8× on the largest stage, and it is a profiling run away from being known.

These are arithmetic on measured sub-step costs, not promises. Three numbers can move them all, and
all three are measurements that come first:

- **§4.1** may make everything cheaper at once (debuggable → benchmark build).
- **§4.2** decides whether audio is 95 or 170 minutes — a 1.8× swing on 65 % of the job — and could
  reveal that it is neither, if the separator degrades with run length.
- **§4.5** (the music duty cycle) is now largely answered — median 41.5 % on 160 human-labelled
  film/TV clips — but the local run-length and confidence distributions still gate A1's threshold.

Do not start §5 before those three exist. Everything in §0 argues that the previous plan's problem was
not effort or ambition; it was ranking work against numbers that had never been checked.
