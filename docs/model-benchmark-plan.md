# Model benchmark plan — re-measure, bake off, pick the stack

**Status:** plan, 2026-09-30. Nothing here is built yet.
**Goal:** end with one decision table: **which model and which runtime fill each slot** of the
pipeline, backed by numbers on a controlled rig and a device matrix, with every licence cleared.
**Inputs:** four research passes on 2026-09-30 (separators, audio gates, vision models, Android
runtimes/measurement), plus everything `perf-plan-v5.md`, `perf-plan-v4.md` §6 and `plan-censor-who.md`
§9 already measured. Candidate links are in [Appendix A](#appendix-a--candidate-catalog-with-sources).

---

## 0. Read this first — five findings that constrain every phase

1. **The shipped separator fails a licence check.** Demucs' author on the weights: *"The model weights
   are not covered by the MIT license, and are provided only for scientific purposes"*
   ([demucs#327](https://github.com/facebookresearch/demucs/issues/327), still open). This covers
   htdemucs, htdemucs_ft and hdemucs_mmi. GPL-3.0 forbids adding further restrictions, so weights
   restricted to research or non-commercial use can't be bundled even in a free app. **Replacing
   htdemucs is now a licence question as well as a speed question.**
2. **The shipped gender model fails too.** InsightFace `buffalo_l` is "for non-commercial research
   purposes only". The owner "waiver" recorded in `Models.kt`/`NOTICE` has no legal effect, because
   the restriction is InsightFace's to lift, not ours. Replacing genderage is mandatory.
3. **The NSFW gate's licence is cleaner than `NOTICE` says.** GantMan's `LICENSE.md` is MIT. The
   NOASSERTION tag comes from its third-party preamble. Update `NOTICE`; keep the model as the control.
4. **Qualcomm NPU (QNN) is measure-only.** `com.qualcomm.qti:onnxruntime-android-qnn:2.6.0` exists and
   is MIT, but it needs `com.qualcomm.qti:qnn-runtime`, which is proprietary. That library (a) needs a
   GPLv3 §7 linking exception from every copyright holder and (b) is licensed against "categorization of
   persons based on sensitive characteristics", which is what the gender vote does. **The
   licence-clean accelerator path is LiteRT GPU (Apache-2.0).**
5. **Two datasets taint most off-the-shelf weights.** MUSDB18 (non-commercial) is the only training data
   behind every public SCNet/DTTNet/Open-Unmix checkpoint. WIDER FACE (CC BY-NC-ND) is behind almost
   every face detector. NudeNet's training set was
   [flagged by C3P](https://www.protectchildren.ca/en/press-and-media/news-releases/2025/csam-nude-net)
   (2025-10-22) for ~680 suspected CSAM images. Every candidate below carries a licence colour, and
   **several good architectures are "retrain on clean data" candidates, not drop-ins.**

**Licence colours**, used in every table:
- 🟢 **Green** — code and weights are GPL-3.0-compatible, with no known non-commercial dependency in the training data.
- 🟡 **Amber** — permissive tag, but the training data is non-commercial, scraped, or undisclosed, or
  the licence is ambiguous. Needs an owner decision (§12).
- 🔴 **Red** — the weights themselves are non-commercial or research-only, or the terms are
  GPL-incompatible. Reference or teacher only; never ships.

Two facts that decide colours: CC BY-SA 4.0 is one-way compatible with GPLv3, so Bandit v2 is green.
AGPL-3.0 combines with GPL-3.0, so Ultralytics-derived weights are green on *licence*; judge them on data.

---

## 1. The slots, and what they cost today

Cost is stated as **compute-ms per video-minute at production cadence** (**c/min**) so slots compare
directly. Numbers are from the S23 `benchmark` build (`perf-plan-v5` §8, `plan-censor-who` §9). They
were taken on a rig that drifts ±30 %, which is why Phase 0 exists.

| Slot | Incumbent | Cadence | Unit cost (S23) | c/min | Licence |
|---|---|---|---|---:|---|
| **SEP** music separator | htdemucs 4-stem, 2.6 s segment, f16, CPU EP ×6 threads | per separated chunk (2.34 s stride) | ~2 297 ms/chunk ≈ 981 ms per audio-s | **~41 000** (70 % of chunks separated) | 🔴 |
| **GATE** music gate | YAMNet, max over classes 132–276 and 24–32, threshold 0.15 | 3 × 0.975 s frames per chunk | device unmeasured (host 0.9–1.2 ms/frame) | ~100 (est.) | 🟢 |
| **NSFW** whole-frame gate | GantMan MNv2-1.4 INT8, XNNPACK | 5 fps | 7.50 ms/frame ORT (+2.34 fill, +0.83 gather) | ~3 200 | 🟢 |
| **FACE** detect + track | ML Kit bundled, FAST, tracking, `minFaceSize` 0.1 | 10 fps | 1.46 ms/frame | ~880 | proprietary |
| **GEN** face gender | InsightFace genderage 96², ≤5 crops/track, floor 0.60 | per track | 4.87–5.01 ms/crop | ~180 (690 crops / 19 min vlog) | 🔴 |
| render (not a model) | Media3 GL + H.264 encode | every frame | ~3.6 ms/frame (131 s / 36.5k frames) | ~5 200 | — |

Current whole jobs on `BSKHKT-EP-02-FHD.mp4` (1522 s):
- **Censor-only:** 258 s (analyze 123 s + render 131 s), about 5.9× realtime.
- **Music-only:** ORT alone 1 038 s, with 199 of 651 chunks skipped. That is ≈ 0.68× the video's duration for separation alone, *on a flagship*.

**Music removal is the product's speed problem**: SEP is ~90 % of all model compute on a both-ops job. Everything else is
tuning or licence work.

New capabilities, benchmarked only after the swaps above (they add cost; nothing ships by default):

| Slot | Purpose |
|---|---|
| **GEN-B** body gender | Decide gender for tracks with no usable frontal face |
| **PER** person detector + tracker | Whole-body cover for "women" mode |
| **REG** region nudity | Cover body regions instead of the whole frame (owner-gated, §12) |
| **OV** open-vocabulary concepts | kiss / dance / alcohol filters — **deferred**; feasibility probe only |

---

## 2. Method — three tiers, fixed decision rules

| Tier | Where | Measures | Purpose |
|---|---|---|---|
| **T0 host screen** | Mac (arm64), Python, **same ORT version and session options as the app** | Quality on the eval corpora; relative cost (ms per unit); param/file size; peak RSS | Kill most candidates cheaply. No device time for anything that fails T0. |
| **T1 device micro** | S23 on the Phase 0 rig; in-app `bench_model` hook (§3.3) | ms per unit (p50/p90), cold and sustained; peak RSS; EP node placement | Real per-unit cost and runtime choice |
| **T2 device end-to-end** | Device matrix (§9), the real job via `autorun` | Wall per video-minute, peak RSS, battery %, output parity | Confirm the stack, not the model |

Rules the repo has already earned, kept verbatim in spirit:
- **Read the counter the item names, not the stage wall** (`perf-plan-v5` §7).
- **Decision rules are written before the run.** Every phase below has one.
- **Safety metrics are primary.** Seconds of music heard, seconds of women left exposed, and seconds of
  NSFW missed are gates. Speed and quality are only compared among candidates that pass the gates.
- **Host ratios lie for some graphs.** SwiftFormer-XS published 0.7 ms on the Apple Neural Engine but ran
  22 % *slower* than MobileNetV2 on ARM CPU, because only 84 of 362 nodes were XNNPACK-eligible
  (`perf-plan-v4` §6.5). Run `python -m onnxruntime.tools.check_onnx_model_mobile_usability` and check
  ORT verbose node placement before trusting any T0 cost number.
- **Compare at matched operating points.** Every classifier gets its own threshold curve, fitted on the
  dev split so that its false-positive rate matches the incumbent's. Recall is then compared at that
  point. Comparing each model at its own default threshold is meaningless.
- **Dev/test split.** Thresholds are tuned on dev. Test is touched once per decision.

**Do not reopen the following.** They are already measured dead; each needs *new* evidence before it is revisited:

| Item | Why it's dead |
|---|---|
| UVR Voc_FT | 2.46× slower than htdemucs (`perf-plan-v4` §6.4). This is not the same model as the 7.4M MDX candidates below. |
| hdemucs_mmi | Serial BiLSTM, −0.45 dB SDR |
| htdemucs longer segment | +20.6 % compute per second of audio at 7.8 s |
| XNNPACK fp16 on htdemucs | Corrupts the spectral branch on device |
| Conv INT8 via ConvInteger | No fast ARM path; 0.44–0.55× (slower than doing nothing) |
| NSFW input at 192 px | 71.7 % agreement vs 91.1 % for INT8 at 224 |
| ORT_PARALLEL | Profiles every node onto one thread; +13.7 % overhead |
| NNAPI | Deprecated in Android 15 |
| MobileCLIP / MobileCLIP2 | Apple AMLR licence, research-only |

---

## 3. Phase 0 — The measurement rig and a clean baseline

The last round could not resolve any wall delta under ~30 % (`perf-plan-v5` §8.3). Two runs of the same
APK differed 32 % on analyze because of heat, and `maxThermal=0` stayed zero throughout. Without this
phase, every number in later phases is noise.

### 3.1 Why the current rig can't see heat
- `PowerManager.currentThermalStatus` is the maximum over **skin** sensors
  ([ThermalManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/power/ThermalManagerService.java)).
  The SoC throttles its CPU clocks long before skin temperature reaches a status threshold. Jetpack
  Microbenchmark's throttle detector has the same blind spot on API 29 and later.
- `cmd battery unplug` fakes the state only; charging continues
  ([BatteryService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/BatteryService.java)).
- Microbenchmark is a poor fit for multi-second workloads: at least 30 warmup iterations, then 50
  measured. **Use a custom harness** (§3.3).

### 3.2 Rig protocol (replaces `perf-plan-v3` §7)

1. **Environment.** Room at 20–25 °C, logged. Phone on a stand with an air gap and no case. Airplane
   mode (Wi-Fi only if using wireless adb). Screen off. `pm clear` between job runs, as today.
2. **Stop charging heat.** Set Samsung Battery protection to **Maximum** (80–85 %) so the phone runs off
   the charger once it reaches the limit. Or run on battery over wireless adb.
3. **Start gate, not a timer.** Wait at least 5 min idle, then start only when all three hold:
   - battery temperature ≤ 32.5 °C;
   - `getThermalHeadroom(0)` ≤ the rig's recorded idle baseline + 0.02 (about 0.6 °C of skin);
   - idle `scaling_cur_freq` maxima look normal.

   Poll headroom every 1–5 s. The server drops its samples after 10 s idle, and calls less than 500 ms
   apart return NaN.
4. **Interleave.** Run candidates in ABBA order, or in randomized blocks. At least 5 blocks for T2 and 3
   blocks for T1. Discard the first run after install or reboot.
5. **Two numbers per candidate:**
   - **cool-start**: the first 60 s after the start gate;
   - **sustained**: after at least 5 min under load.

   Long films live in the sustained regime, so rank on sustained.
6. **Report median + IQR per arm, and a bootstrap 95 % CI of the paired A/B ratio.** A delta whose CI
   crosses 1.0 is "no measured difference", however large the median looks.
7. **Optional clock control.** Try `adb shell cmd power set-fixed-performance-mode-enabled true` and
   `Window.isSustainedPerformanceModeSupported()` once on the S23. Use whichever works, and record which
   mode was active in the results. Unsupported is the likely answer, which is fine.

### 3.3 Instrumentation to add (minimum set)

| What | Where | Notes |
|---|---|---|
| Per-chunk / per-N-frame telemetry: cpufreq per policy (`/sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq`, readable by apps), battery temperature (`ACTION_BATTERY_CHANGED`), `getThermalHeadroom(0)` (API 30+) | `JobStats.tick()` → one JSON-lines file in `filesDir/bench/` | logcat stays as it is. The JSON is what gets pulled. |
| **`bench_model` autorun hook** (`DEBUG_HOOKS`) | `MainActivity.maybeAutorun` | Extras: `-e bench_model /data/local/tmp/x.onnx`, `--ei bench_iters`, `--es bench_ep cpu\|xnnpack`, `--ei bench_threads`, `--ei bench_seconds` (sustained mode). Loads the model with the **production** session options, feeds a fixed seeded input, logs p50/p90/max, session-create ms, VmHWM, and the telemetry above. This is the T1 harness. |
| ORT profiling on demand | same hook, `--ez bench_profile true` → `SessionOptions.enableProfiling` | Per-node `provider` field shows EP placement. View with Perfetto or `ort_perf_view.html`. Also set the session log level to VERBOSE once per model to read the "Node(s) placed on [EP]" lines. |
| Perfetto trace on a subset of runs | `sched` + `power/cpu_frequency` config | Shows which cores the ORT threads land on (X3 / A715 / A510). |

`onnxruntime_perf_test` (built from source for Android) is optional. It's useful for EP and thread
sweeps, but its `-x` flag can't reproduce "ORT 1 thread + XNNPACK N threads", so the in-app hook is the
source of truth.

### 3.4 Baseline to record before any candidate

| Run | Shape | Clip |
|---|---|---|
| B-censor | `censorWho=women`, NSFW on | `BSKHKT-EP-02-FHD.mp4` |
| B-music | `removeMusic=true censorWho=none` | same |
| B-both | both ops | `women-music-3min` + `BSKHKT` |
| B-micro | T1 on every incumbent model (SEP, GATE, NSFW, GEN), sustained 5 min each | fixed inputs |

Also run B-micro on **ORT 1.30.0** as a control arm (latest, 2026-09-14). KleidiAI's new fp16 and conv
kernels only activate on SME/SME2 CPUs, so no gain is expected on the S23. The Exynos 2600 (Galaxy S26)
does have SME2, which is why ORT 1.30 is also tested there in §9.

**Output:** `docs/bench/baseline-2026-10.md`, the table every later phase compares against.
**Exit:** the same build measured twice under the start gate agrees within 5 % on every counter.
If it doesn't, fix the rig before going further.

---

## 4. Phase 1 — Evaluation corpora (quality ground truth)

This is the largest piece of work and the one the repo has skipped before. The shipped gender vote was
accepted on **10 male crops** (`plan-censor-who` §9.1).

Everything lives in `qa-assets/` (gitignored) with a committed `qa-assets/MANIFEST.md` listing source,
licence and split. Sensitive frames are never committed and are kept off cloud sync.

### 4.1 Audio

| Set | Build | Ground truth | Licence | Used by |
|---|---|---|---|---|
| **A1 synthetic mixes** | Script: speech + music at music-to-speech SNR bins {−5, 0, 5, 10} dB. About 240 × 10 s. Speech: [ClArTTS](https://huggingface.co/datasets/MBZUAI/ClArTTS) / Arabic Speech Corpus (Arabic), plus some English. Music: [MUSAN](https://www.openslr.org/17/) music and CC-BY [FMA](https://github.com/mdeff/fma) tracks. | Clean speech and music stems | CC BY 🟢 | SEP, GATE |
| A1-sing | Same recipe with singing: MUSAN vocals-flagged tracks, [VocalSet](https://zenodo.org/records/1193957), [Jamendo SVD](https://zenodo.org/records/2585988) | Singing stem | CC BY 🟢 | SEP (does singing survive?), GATE job 3 |
| A1-recite | Recitation and adhan over silence and over room tone. Sources: [QDAT](https://huggingface.co/datasets/obadx/qdat), [EveryAyah](https://huggingface.co/datasets/tarteel-ai/everyayah) (per-recording licence undocumented → eval only), own recordings | "Must pass through" | mixed | GATE job 3 |
| **A2 real-mix references** | [DnR v3](https://github.com/kwatcharasupat/divide-and-remaster-v3) test, Arabic subset; [CineAudioDB](https://huggingface.co/datasets/disco-eth/cineaudiodb) (real film stems, 2.46 h) | Dialogue / music / effects stems | CC BY-SA / CC BY 🟢 | SEP |
| **A3 real-world** | 30 clips × 2–3 min, one or more per user segment: cartoon, documentary, lecture with a music intro, news, a cappella nasheed, nasheed with duff, recitation, adhan, film scene with effects, vlog, sports, kids' song | Hand-labelled 1 s timeline: `speech / music / singing / recitation / sfx / silence` | own use | SEP (reference-free), GATE, listening test |
| A4 gate labels | [AVASpeech-SMAD](https://github.com/biboamy/AVASpeech_Music_Labels) (45 h, overlapping speech+music labels) and [AVA-Speech](https://sites.research.google/gr/ava/download/) | Frame labels | MIT / CC BY labels; audio from YouTube | GATE |
| Throughput | `BSKHKT-EP-02-FHD.mp4` (1522 s) | — | — | T2 |

**Decide before labelling (owner, §12):** does a duff-only nasheed count as music? Does a cappella
singing? The labels encode the product's religious policy. The models can't decide it.

### 4.2 Vision

| Set | Build | Licence | Used by |
|---|---|---|---|
| **V1 in-domain face crops** | Existing `files/dump-crops` hook over A3-style videos. Target ≥ 300 male and ≥ 300 female **tracks**, including content with many men (news, sports, lectures). Label per track; band by crop size (<40, 40–80, ≥80 px) and pose (frontal / profile / back / occluded). Keep the ~23 % non-face junk crops as their own class. | own | GEN, FACE |
| V1-ref | [FairFace](https://huggingface.co/datasets/HuggingFaceM4/FairFace) val; [LAGENDA](https://wildchlamydia.github.io/lagenda/) (face + body boxes) | CC BY / "CC 2.0" 🟢 | GEN, GEN-B |
| V2 body gender | LAGENDA body boxes; [PA-100K](https://github.com/xh-liu/HydraPlus-Net) test; Open Images V7 `Man/Woman/Boy/Girl` boxes | CC BY 🟢 | GEN-B, PER |
| **V3 in-domain faces** | 1 500 frames sampled at 1 fps from A3 videos, boxes labelled in CVAT (MIT) or Label Studio, including small, profile and occluded faces; then tracks interpolated between keyframes | own | FACE |
| V3-ref | WIDER FACE val (eval only); [AVA ActiveSpeaker](https://sites.research.google/gr/ava/download/) face tracks (film domain) | NC-ND ⚠️ / CC BY | FACE |
| **V4 NSFW policy set** | Frames labelled in three policy tiers: **must-cover** (explicit), **cover-at-strictness** (swimwear, lingerie, revealing clothing), **must-not-cover** (sport, ballet, babies, art, statues, surgery, cartoons, hijab and abaya). Sources: Open Images image-level classes (`Bikini`, `Swimwear`, `Lingerie`, `Wrestling`, `Ballet`, `Baby`, `Painting`, `Sculpture`, `Surgery`; CC BY), in-house film frames, and [UnsafeBench](https://huggingface.co/datasets/yiting/UnsafeBench) Sexual under its data use agreement. **No NudeNet data, no LAION originals.** | mixed | NSFW, REG |
| V4-video | 10 film clips with hand-labelled "must-cover" intervals, for timeline recall after hysteresis | own | NSFW |
| V5 persons | COCO val `person`; AVA boxes (film domain) | CC BY 🟢 | PER |

**Labelling policy doc** (`qa-assets/POLICY.md`, owner-approved). It defines: what counts as "a woman's
face that must be covered" (minimum pixel size, children, cartoons, back-of-head); what each NSFW tier
means at strictness 0 / 40 / 100; and what counts as music. Two labellers on 10 % of V1 and V4 to report
agreement. If humans disagree above 10 %, the metric is measuring the policy, not the model.

---

## 5. Phase 2 — Separator bake-off (SEP)

The biggest lever: SEP is ~41 s of compute per video-minute on a flagship, and the incumbent is 🔴.

### 5.1 Candidates

| ID | Model | Params · file | Vocals SDR (published) | Stems | Licence | Why it's here |
|---|---|---|---|---|---|---|
| SEP-0 | htdemucs 2.6 s f16 (incumbent) | 42M · 84 MB | MS 8.19–8.25 | 4 | 🔴 | Control |
| SEP-0q | SEP-0 with MatMul-only dynamic INT8 | 92 MB | parity 41–63 dB | 4 | 🔴 | Already measured 1.25× on host; listening test owed. It tells us what the incumbent can do. |
| **SEP-1** | **UVR_MDXNET_3_9662** | 7.42M · 29.7 MB | MS 8.72 | 2 | 🟡 (MIT-credit request, no licence file, data undisclosed) | Native ONNX, Conv/BN/MatMul only, STFT already outside the graph. About 38 GFLOP per audio-second vs ~128 for Voc_FT. S25 ORT CPU: 865 ms per 5.94 s window. |
| SEP-2 | UVR_MDXNET_9482 | 7.42M · 29.7 MB | MS 8.65 | 2 | 🟡 | Twin of SEP-1. S25 LiteRT GPU fp32: 246 ms per window, 3.5× its ORT CPU time. |
| SEP-3 | UVR Inst_Main / Inst_HQ_4 | 13–15M · 53–59 MB | MS 9.07 / 9.63 | 2 | 🟡 | A possible "HQ mode" at roughly incumbent cost |
| SEP-4 | htdemucs_ft vocals (`04573f0d`) | 42M | MS 8.33–8.43 | 4 | 🔴 | Quality at identical cost. Tells us whether the 2.6 s segment is the limit. |
| SEP-5 | SCNet Tran (fixed 2.75 s ONNX exists) / SCNet small | 10.4–10.6M · 42 MB | MS 8.27–8.42 | 4 | 🟡 (MUSDB-only weights) | Paper: 2.06× faster than htdemucs on CPU. **Architecture candidate:** internal rfft becomes DFT MatMuls; 12 LSTM nodes. |
| SEP-6 | DTTNet vocals | 4.96M · 20 MB | MS 8.74–8.78 | 1 | 🟡 (MUSDB-only) | Best SDR per parameter. BiLSTM; n_fft 6144 (not a power of two). No port exists yet. |
| SEP-7 | GTCRN / DPDFNet (speech enhancement) | 48K / 2.3–3.6M | n/a | speech | 🟢 | Nearly free. Candidate **"fast mode"** and post-filter. HaramMute-android already uses GTCRN for this. Also removes sound effects; may keep singing. |
| SEP-8 | DeepFilterNet3 | 8 MB | n/a | speech | 🟢 | Same role as SEP-7, 48 kHz |
| SEP-9 | Spleeter 2-stem | 2 × 9.8M | MS 5.82 | 2 | 🟢 | Clean-licence floor; very fast |
| SEP-R1 | Bandit v2 (dialogue / music / effects) | ~37M | DnR v3 dialogue 15.8 | 3 | 🟢 (CC BY-SA 4.0) | **Keeps effects and drops songs.** Too heavy to ship (Xeon RTF 1.34). Reference and distillation teacher. |
| SEP-R2 | anvuew BS-RoFormer | ~51M · 205 MB | MS 11.47 | 2 | 🟢 (GPL-3.0) | Quality ceiling and teacher. Costs 6–12× htdemucs. |
| SEP-R3 | Kim Mel-RoFormer | ~228M · 913 MB | MS 10.98 | 2 | 🟡 | Ceiling reference |

MS = MVSep Multisong SDR, the only scale with every candidate on it. Dropped: small RoFormers (they cut
parameters, not FLOPs: 280–390 GFLOP per audio-second), MDX23C (448 MB), Open-Unmix (MUSDB / NC-SA),
the HS-TasNet / RT-STT / Band-SCNet / Moises-Light / LiteCASS family (no weights released), SAM Audio,
AudioSep.

**Product note on 2-stem models:** they output vocals / instrumental only, so the current
**`vocals_other` ("keep sound effects") mode has no 2-stem equivalent.** Options:
- keep a 4-stem model just for that mode;
- use Bandit-class dialogue/music/effects separation (the real fix for films, training track §5.5);
- drop the mode.

This is an owner decision (§12). It doesn't block SEP-1's evaluation for the default `vocals` mode.

### 5.2 T0 — host screen

- **Harness:** `scripts/bench/score_sep.py`. It reproduces each model's exact STFT geometry, segment
  and overlap-add in numpy: 2.6 s / nfft 4096 / hop 1024 for htdemucs; 5.94 s / n_fft 6144 / dim_f 2048
  for SEP-1/2, **taken from UVR's `model_data_new.json`, not sherpa-onnx's metadata, which is wrong**.
  It runs on host ORT with the app's session options. No dB number without matching the geometry the app
  will actually run.
- **Quality metrics:**
  - **A1 / A2**, using [`fast_bss_eval`](https://github.com/fakufaku/fast_bss_eval) against the stems:
    **speech SI-SDR** (speech kept), **SIR vs music** (music leakage), **SAR** (artifacts).
    Reported per SNR bin.
  - **A1-sing:** singing energy retained. This tells us how much "vocals keeps singing" costs us.
  - **Arabic intelligibility:** Whisper large-v3 (MIT) CER on the output minus CER on the clean speech.
  - **A3, reference-free:**
    - DNSMOS P.835 SIG / BAK / OVRL (ONNX, MIT code).
    - **Residual-music seconds**: a *judge* music detector run over the output. The judge is TVSM or
      PANNs CNN14, never the gate under test, to avoid scoring a model with itself.
  - Calibrate the reference-free metrics against true SI-SDR on A1 first. The CineAudioDB authors found
    them unreliable on real mixes.
- **Cost:** host ms per audio-second. The htdemucs anchor on this host is 152.3 ms/s (`perf-plan-v4`
  §6.4). Also peak RSS, file size, and NaN/Inf check.
- **Quantization pass for survivors:**
  - dynamic MatMul-only INT8 (the SEP-0q recipe);
  - **static QDQ with QLinearConv** for the all-conv MDX graphs. These have fast XNNPACK kernels,
    unlike ConvInteger.
  - fp16 only if there are no NaNs.

  Parity for each: ≥ 35 dB against its own fp32.

**T0 → T1 advance rule** (for the default `vocals` mode). Advance if all three hold:
- speech SI-SDR ≥ SEP-0 − 0.5 dB at every SNR bin;
- SIR ≥ SEP-0 at 0 and 5 dB;
- host cost ≤ 0.7 × SEP-0.

An **HQ** slot advances instead at ≥ SEP-0 + 1 dB speech SI-SDR with cost ≤ 1.2 × SEP-0.
Speech-enhancement candidates (SEP-7/8) advance as **fast mode** if cost ≤ 0.1 × SEP-0 and
residual-music seconds on A3 ≤ 2 × SEP-0.

### 5.3 T1 — device

- **In-app `bench_model`, sustained 5 min per candidate:**
  - CPU EP at 4 and 6 threads, and XNNPACK (Conv-only graphs);
  - LiteRT GPU (Phase 5) for any conv-only survivor.
- **Metric:** device ms per audio-second, sustained p50, plus peak RSS.
- **Budget:** RSS ≤ SEP-0's 1.30 GB.

### 5.4 The gate that decides — listening

MUSHRA-lite on 12 quiet A3 passages (the hiss case `perf-plan-v5` §4.1 worries about), plus 4 loud
music-under-speech passages:
- **Listeners:** at least 5 from the target user group.
- **Two questions per clip:** "Is any music audible?" (yes / no) and "Speech quality" (1–5).

A candidate replaces SEP-0 only if the rate of "music audible" answers is **not higher** and the median
speech quality is **not lower**. No dB number overrides this test.

### 5.5 Training track (runs in parallel; decides the long-term answer)

The licence picture says the most likely end state is **a small architecture retrained on clean
data**. Candidates, all trainable in
[ZFTurbo MSST](https://github.com/ZFTurbo/Music-Source-Separation-Training):
- MDX-Net small (TFC-TDF) via kuielab/mdx-net;
- SCNet small (`config_musdb18_scnet.yaml`);
- DTTNet (`config_DTTNet_vocals.yaml`);
- Moises-Light (`config_musdb18_moises_light.yaml`);
- a LiteCASS-style 3-stem model (1.06M params, 0.72 GMAC/s; reimplement from
  [arXiv 2609.23453](https://arxiv.org/abs/2609.23453)).

Clean training data: DnR v3 (CC BY-SA, includes Levantine Arabic), MUSAN, Slakh2100 (CC BY), CC-BY FMA,
Arabic speech (ClArTTS, Arabic Speech Corpus). Teachers: SEP-R1 and SEP-R2 outputs. Whether
distillation carries the teacher's licence is an open legal question (§12).

**Rule:** start only after T0 has picked the architecture (SEP-1/5/6 results).

---

## 6. Phase 3 — Music-gate bake-off (GATE)

The cheapest big lever: every chunk the gate correctly skips or mutes is a chunk SEP doesn't run.
Three jobs, scored separately:
- **J1 music presence:** separate or pass through.
- **J2 music without speech:** mute the chunk, no separation. This is a new tier; valid for `vocals`
  mode only.
- **J3 singing vs speech, and recitation:** let recitation and adhan through; optionally a "remove
  singing" feature.

### 6.1 Candidates

| ID | Model | Size | Output | Licence | Notes |
|---|---|---|---|---|---|
| GATE-0 | YAMNet (incumbent) | 3.7M | 521 classes per 0.96 s | 🟢 | Control |
| **GATE-0b** | YAMNet **without classes 27–28 (Chant, Mantra)** | same | same | 🟢 | AudioSet defines Chant as "rhythmic speaking or singing… on reciting tones". The current gate very likely counts **Qur'an recitation and adhan as music** and separates them. Zero-cost fix if confirmed on A1-recite. |
| **GATE-1** | PretrainedSED `frame_mn06` / `frame_mn10` | 1.6M / 3.8M | 447 AudioSet-Strong classes every 40 ms, including Music, Speech, Singing, Chant, Humming | 🟢 (confirm that the release assets fall under the repo's MIT) | Trained on *overlapping* strong labels, so it reports music under dialogue. One model can cover all three jobs. Export gotcha: `seq_len=65` for 2.6 s chunks, not 250. M3 host: 6.0 / 12.4 ms per chunk. |
| GATE-2 | EfficientAT mn04 / mn05 | 1.0M / 1.4M | 527 per clip, mAP 43–44 | 🟢 MIT | Drop-in replacement for YAMNet. 32 kHz mel front-end must be reimplemented in Kotlin. |
| GATE-3 | CED-tiny int8 | 5.5M | 527 per clip, mAP 48.1 | 🟢 | ONNX ready via sherpa-onnx |
| GATE-4 | TVSM CRNN (Netflix) | 0.83M | speech + music every ~0.19 s | 🟢 | **Judge and reference.** ~100 ms per chunk on the M3 is too slow for a gate. |
| GATE-5 | inaSpeechSegmenter `smn` | 3.2 MB | speech / music / noise | 🟢 MIT | Its "music" label already means "music, no speech": a J2 candidate out of the box |
| GATE-6 | Silero VAD v6 | 2.3 MB | speech probability | 🟢 | **Veto only.** Instruments trigger false speech, which errs toward separating, the safe direction. |
| GATE-7 | pyannote segmentation-3.0 | 1.5M | speech activity | 🟢 | Alternative "no speech" check for J2 |

Excluded:
- TEN VAD: Agora non-compete terms.
- MS-CLAP: MS-PL.
- Essentia `voice_instrumental`: NC.
- SMAD CRNN: MUSDB NC-SA taint; eval-only curiosity.
- MarbleNet: NVIDIA Open Model License; check before use.

### 6.2 Metrics

All at a fixed operating point: **music recall ≥ 0.99 on A1 + A4**.

| Metric | Meaning | Role |
|---|---|---|
| **Missed-music seconds** | Music the gate let through unseparated | Safety gate |
| **False-mute seconds** (J2) | Speech muted | Hard gate: **0 s** on A1 and A3 speech |
| Wasted-separation seconds | Music-free audio sent to SEP | Cost |
| Recitation / adhan pass-through rate (J3) | — | Product |
| ms per chunk (T0, then T1) | — | Cost |
| **Projected SEP time on `BSKHKT`** | Replay each gate's decisions over the film's 651-chunk timeline → % skip / mute / separate × SEP unit cost | **This is the number that reaches the user** |

Everything is broken down by SNR bin.

**Decision rule.** Adopt a new gate (or GATE-0b) if:
- missed-music seconds ≤ GATE-0;
- false-mute = 0 s;
- projected SEP time ≤ GATE-0 − 10 %.

Adopt the J2 mute tier only after the listening test: 20 A3 chunks it would mute, played to 3
listeners, none of whom hears lost speech.

---

## 7. Phase 4 — Vision bake-off

### 7.1 GEN — face gender (mandatory: the incumbent is 🔴)

| ID | Model | Input · cost | Licence | Notes |
|---|---|---|---|---|
| GEN-0 | InsightFace genderage (incumbent) | 96² · 4.9 ms per crop (S23) | 🔴 | Control only. Independent test: 89.3 % on real ad faces. |
| **GEN-1** | OpenVINO OMZ `age-gender-recognition-retail-0013` | 62² BGR · 2.1M params · host INT8 0.52 ms | 🟡 (Apache; data undisclosed) | Closest drop-in. Convert from IR yourself and write a parity test against OpenVINO (PINTO's ONNX is NHWC). ±45° pose, ages 18–75. 95.8 % self-reported. |
| GEN-2 | SigLIP2 B/32-256 + linear probe trained on FairFace + LAGENDA | 256² · 5.7 GMACs · host safe-INT8 21 ms | 🟢 | Per track, not per frame. `perf-plan-v4` §6.5 killed *zero-shot CLIP* on XNNPACK coverage and demographic bias. A **trained probe** addresses the bias half; check node coverage first. INT8 gotcha: keep `fc2` and the pooling head fp32 (cosine drops to 0.81 otherwise). |
| GEN-3 | dima806 `fairface_gender_image_detection` (ViT-B) | 224² · host 174 ms | 🟢 (Apache + FairFace CC BY) | Cleanest licence chain; likely too heavy. Reference. |
| GEN-4 | MiVOLO v2 (`mivolo_d1_384`, face + body) | 2 × 384² · ~32 GMACs · host ~1 s | 🟡 (Apache since 2026-03; **pin HF revision `53393526`**, since the licence flipped several times; training data undisclosed) | **Accuracy ceiling and teacher.** Independent: 96.7 % on the same ad-face set. Needs a re-export with the gender head (the community ONNX is age-only). |
| GEN-5 | face-gender-lcnet-050 | 112² · host 1.9 ms | 🟡 (empty card) | Speed floor, if its accuracy holds |
| GEN-T | **Distill GEN-4 or GEN-2 into MobileNetV4-S / PP-LCNet** on FairFace + LAGENDA + Open Images Man/Woman | ~1–2 ms | 🟢 | The likely end state if GEN-1 is marginal |

**Metrics and bar:** `plan-censor-who` §3.3, verbatim, on **V1** (in-domain tracks) with FairFace as a
sanity check:
- balanced accuracy ≥ 90 % over tracks that vote, and neither class collapses;
- **men visible in Women mode ≥ 70 %**;
- **women exposed as low as possible**.

Deliver the CONF_FLOOR curve, not a single point. Stratify by size band and pose. Report junk-crop
behaviour separately: in Women mode, a male vote on an object is what spares it. Report cost per crop;
VOTE_CAP=5 makes it a per-track cost.

**Decision rule:** pick the cheapest candidate that clears the bar at a women-exposed rate ≤ GEN-0's
4.8 %. If none clears it, ship GEN-T, or ship Everyone-only until one does. A 🔴 model stays out
either way.

### 7.2 NSFW — whole-frame gate

| ID | Model | Cost | Licence | Notes |
|---|---|---|---|---|
| NSFW-0 | GantMan MNv2 INT8 (incumbent) | 7.5 ms per frame (S23) | 🟢 | Control |
| NSFW-1 | OwenElliott `image-safety-classifier-xs` / `-s` | 0.6 / 1.0 GMACs · host INT8 only 1.4× | 🟢 (MIT; proprietary training set) | Adds a gore (NSFL) class. **SwiftFormer-based: check XNNPACK node coverage before anything else** (see the §2 precedent). |
| NSFW-2 | MobileNetV4-S NSFW (taufiqdp) | 0.2 GMACs · host INT8 1.7 ms | 🟢 Apache | Same 5 classes as GantMan and ~4× cheaper, but **no accuracy published**. Our corpus decides. Re-export at opset ≥ 17 for per-channel INT8. |
| NSFW-3 | viddexa `nsfw-detection-2-nano` | EfficientNet-B0, 224 | 🟡 (LSPD research data) | Same 5 labels; self-reported F-macro 93.0 |
| NSFW-4 | Marqo `nsfw-image-detection-384` | host INT8 84 ms | 🟢 | **Second stage only**: confirms frames the gate is unsure about |
| NSFW-R | Freepik `nsfw_image_detector` (EVA02-B) | heavy | 🟢 MIT | Best independent F1 (81.1). Offline reference and distillation teacher. |

Dropped: Falconsai (independent recall 40.7 % on UnsafeBench-Sexual), and every NC-licensed variant.

**Metrics:**
- **V4-video:** "must-cover" interval recall after the production hysteresis (`NsfwGate`), at
  strictness 0 / 40 / 100. Each candidate gets its own strictness→threshold curve, fitted on dev so its
  over-cover seconds match NSFW-0's.
- **V4 hard negatives:** over-cover seconds.
- **Frame-level ROC:** secondary.

**Decision rule:**
- replace NSFW-0 if interval recall ≥ NSFW-0 at all three strictness points, with over-cover ≤ NSFW-0,
  and **cost ≤ 1.2×**; or
- accept a cost-neutral win of ≥ 5 pp recall at strictness 40.

### 7.3 FACE — detector

`perf-plan-v4` §6.5 established that this is a **quality and licence item, not a perf item**. ML Kit is
1.46 ms per frame. Any new detector also changes the boxes fed to tracking, so it can re-associate
tracks and flip gender votes. **Every FACE change re-runs §7.1's bar.**

| ID | Model | Licence | Notes |
|---|---|---|---|
| FACE-0 | ML Kit, `minFaceSize` 0.1 (incumbent) | proprietary (blocks F-Droid) | Control |
| FACE-0s | ML Kit, `minFaceSize` 0.05 | same | The parked quality item from `perf-plan-v4` §6.5. Cheapest recall gain. |
| FACE-1 | YuNet-n / YuNet-s `2026may` (dynamic input) | 🟡 (MIT / BSD; WIDER data) | 55–76k params; decode + NMS in Kotlin. WIDER hard 0.75–0.81. |
| FACE-2 | BlazeFace full-range | 🟢 (Apache, **consented training data**) | The only detector with clean data. Rated for ≤ 5 m and faces ≥ 5 % of the frame. |
| FACE-3 | YOLOv5n-face | 🟡 (GPL-3.0; WIDER data) | Best hard-set AP of the tiny models (0.805) |
| FACE-4 | face class of YOLO-Wholebody34 (§7.4) | 🟢 MIT | Only if PER-3 wins |

**Metric (V3):** **exposed face-seconds per video-minute** at matched junk-detection rate. A face is
exposed while no box plus pad covers it. Also report recall by size band (the fixed-resolution AP
collapse the YuNet paper shows), track fragmentation, and ms per frame at the real decode resolution.

**Decision rule:** switch only if exposed face-seconds drops ≥ 20 % at no higher junk rate. Otherwise
keep ML Kit and take FACE-0s if it wins.

### 7.4 New capabilities — only after 7.1–7.3 are settled

**GEN-B, body gender, and PER, person detection + tracking for whole-body cover:**

| ID | Model | Licence | Notes |
|---|---|---|---|
| GENB-1 | PaddleClas PULC `person_attribute` | 🟢 (Apache; trained only on PA-100K, CC BY) | 256×192, host INT8 2.8 ms. Gender attribute (index 22) and orientation Front / Side / Back (23–25). |
| GENB-2 | OMZ `person-attributes-recognition-crossroad-0230` | 🟡 | Convert yourself: PINTO's ONNX is off by up to 0.57 |
| PER-1 | RTMDet-nano person | 🟢 Apache | 0.31 GFLOPs at 320, person AP 40.3, NMS in graph |
| PER-2 | PicoDet-S / S-Pedestrian | 🟢 Apache | 9.6 ms on a Snapdragon 865 |
| **PER-3** | **PINTO YOLO-Wholebody34 N / T** | 🟢 MIT (own labels on COCO) | **One detector outputs body, male, female, face and head orientation.** Could replace FACE + GEN + PER at once. Self-reported male / female AP: N 0.59 / 0.43, T 0.77 / 0.72. |
| PER-4 | YOLO26n | 🟢 (AGPL; COCO) | NMS-free; strongest tiny generalist |
| Tracker | ByteTrack (MIT), no ReID, no camera-motion compensation | 🟢 | ~0.9 ms per frame desktop. ReID weights are trained partly on the retracted DukeMTMC. |

Masks are deferred: box cover first. The small segmenters are weak on multiple and distant people, and
one degrades badly under naive INT8.

**Metric:** **exposed body-seconds** for women in Women mode on film-domain clips (V2 + AVA), plus ID
switches and ms per frame. The **GEN-B gain** is the share of tracks with no usable face that get a
correct vote.

**REG, region nudity: owner-gated (§12).** NudeNet 320n (AGPL; dataset CSAM-flagged), EraX-Anti-NSFW
(AGPL per its checkpoint metadata), and Pakaho YOLO26s (AGPL, 10 days old) all train on scraped data.
If approved: run only as a second stage after NSFW fires. Keep the detect head fp32 (full INT8 zeroed
every score). Do NMS in Kotlin.

**OV, open vocabulary: deferred.** One probe only: SigLIP2 B/32 with precomputed text embeddings for 10
concepts, scored on AVA action labels (kiss, dance, drink, smoke; CC BY), to learn whether it is worth a
plan of its own.

---

## 8. Phase 5 — Runtime and accelerator track (applies to the winners)

| ID | Runtime | Licence | Applies to | What to measure |
|---|---|---|---|---|
| RT-0 | ORT 1.27, CPU EP or XNNPACK (incumbent) | 🟢 | all | Control |
| RT-1 | ORT 1.30.0 | 🟢 | all | A/B on S23 (expect ~0) and Exynos 2600 (SME2: KleidiAI paths live) |
| **RT-2** | **LiteRT 2.2 GPU** (CompiledModel, `Accelerator.GPU`, OpenCL). Convert with `onnx2tf` 2.6.9 (no TF needed) or `litert-torch` 0.9.4. | 🟢 Apache; minSdk 29 OK | conv-only SEP winners, NSFW, detectors | Whole graph on GPU? Parity ≥ 35 dB (SEP) / argmax agreement ≥ 99 % (vision), both fp32 and "fp16 with fp32 accumulation" (new in 2.2.0; plain fp16 gave only 23 dB SNR on MDX). Sustained ms per unit **while the video decoder is running**, since GPU contention with Media3 GL is the risk. |
| RT-3 | QNN HTP: `com.qualcomm.qti:onnxruntime-android-qnn:2.6.0` + `qnn-runtime` (V73 libraries only) | 🔴 **measure-only** (§0.4) | SEP | **Start with [Qualcomm AI Hub](https://workbench.aihub.qualcomm.com/)** (free; real S23, S24 and A73 devices; per-layer placement; ORT 1.27.1). No app change needed to learn the ceiling. Needs fixed shapes; QDQ A16W8 or fp16. Data point: htdemucs fp16 on the S24 NPU ≈ 100 ms per 1 s chunk. |
| RT-4 | ExecuTorch 1.x Vulkan (demixr already runs htdemucs) | 🟢 BSD | SEP | Optional. Only if RT-2 fails on the chosen separator. |

**Rules:**
- An accelerator counts only if **every node** lands on it (check placement) and parity holds.
  Otherwise partial fallback is timed as a speedup.
- The cost that counts is **sustained, under the real pass-1 or pass-2 load**, not an isolated
  microbenchmark.
- QNN results are recorded, but QNN ships only if the owner resolves §0.4.

---

## 9. Phase 6 — Device matrix and end-to-end (T2)

The PRD's acceptance device (SD 778G-class) has never been measured. The S23 reads optimistically fast.

| # | Device | SoC | Why | Access |
|---|---|---|---|---|
| D1 | Galaxy S23 | SD 8 Gen 2 | Flagship Snapdragon, the reference rig | owned |
| D2 | Galaxy A56 / A57 | Exynos 1580 / 1680 | Samsung holds 39 % of the Middle East (Omdia Q2 2026); upper-mid Exynos | buy |
| D3 | Redmi Note 15 Pro | Dimensity 7400 | Mid-range MediaTek (MediaTek is the largest global chip vendor) | buy |
| D4 | Galaxy A07 / A17 4G, **4 GB** | Helio G99 | The floor: best-selling cheap Samsung in MEA; tests the RAM limit | buy |
| D5 | Galaxy A73 | SD 778G | **The PRD acceptance device** | Samsung Remote Test Lab (free) |
| D6 | Galaxy S26 | Exynos 2600 (SME2) | ORT 1.30 KleidiAI arm; flagship outside the US | optional |
| D7 | Tecno / Infinix | Helio G81 | Iraq, Jordan, Pakistan | Android Device Streaming (Transsion partner lab) |

Remote farms (none controls temperature; use them for **relative** ranking on one device, OOM checks
and crashes, not absolute numbers):

| Farm | Terms | Use for |
|---|---|---|
| Samsung RTL | 20 credits/day, ≤ 10 h/day, adb via RDB | Galaxy A models |
| Firebase Test Lab | instrumentation runs up to 45 min; $5 per device-hour | Automated runs |
| AWS Device Farm | custom host shell, 150 min runs | `dumpsys thermalservice` from the host |
| Qualcomm AI Hub | models only, no APK | Snapdragon placement and latency |

**T2 runs:** the candidate stack vs the incumbent. Three job shapes (§3.4) on the throughput clip plus
five A3 clips. ABBA on D1; three runs each elsewhere.
- **Metrics:** wall per video-minute (cool-start and sustained), peak RSS (budget ≤ 1.5 GB on a 4 GB
  device), battery % per video-hour on battery, A/V sync, and output parity. Each swapped slot must
  reproduce its T0 quality within noise on the device.
- **Exit:** the stack clears every safety gate and is faster, or equal and licence-clean, on D1, D2 and
  D4.

---

## 10. Phase 7 — Choosing the stack

For each slot, in order:
1. **Licence gate:** 🟢, or 🟡 with the owner's decision recorded.
2. **Safety gate:** missed-music, false-mute, women-exposed, NSFW-missed and exposed-face seconds are no
   worse than the incumbent's, measured on the held-out test split.
3. **Budgets:** RSS within budget on D4; each new or swapped model's cost on APK size stated (models are
   already ~105 MB of ONNX).
4. **Choose:** the lowest sustained c/min at equal quality. Otherwise the best quality at ≤ the
   incumbent's c/min. Tie-breakers: fewer new dependencies, smaller file, simpler integration.

**Output:** `docs/bench/stack-decision.md`, one row per slot:

```
slot | winner | runtime | c/min (D1 / D2 / D4) | safety metrics vs incumbent | licence | evidence links
```

Each winner then gets its own integration PR, carrying its listening or visual test and the
`Models.kt` KDoc update. Nothing in this plan merges model swaps by itself.

---

## 11. Order, effort and the fast lane

Solo-developer estimates. Phases 0 and 1 run in parallel.

| Order | Phase | Effort | Blocks |
|---|---|---|---|
| 1 | P0 rig, instrumentation, baseline | 3–4 days | everything |
| 1 | P1 corpora and policy doc | 1.5–2 weeks | all quality numbers |
| 2 | **P3 gate** (GATE-0b first: a one-line check) | 4–5 days | projected SEP time |
| 2 | **P2 separator T0** (SEP-1/2/0q/4/7 first, since they need no export work) | 1 week | — |
| 3 | P2 T1 and listening | 4 days | — |
| 3 | **P4 GEN** (licence-forced) | 4 days | — |
| 4 | P4 NSFW, P4 FACE | 1 week | — |
| 4 | P5 LiteRT GPU on the conv-only winners; AI Hub QNN ceiling | 1 week | — |
| 5 | P4 new capabilities (GEN-B, PER) | 1.5–2 weeks | — |
| 5 | P2 training track (if T0 says retrain) | 3–6 weeks | — |
| 6 | P6 device matrix | 1 week | — |
| 7 | P7 decision | 1 day | — |

**Fast lane.** If only a fraction of this gets done, do this much:

> P0 → GATE-0b + GATE-1 → SEP-1 at T0/T1 + listening test → GEN-1 against the §3.3 bar.

That set attacks the real speed problem: fewer chunks separated, and a separator about half the cost.
It also fixes one licence blocker outright, and it points at the fix for the other.

---

## 12. Decisions only the owner can make

1. **Licence posture.** Which colours may ship on Play, and which on GitHub-only builds? In particular:
   is 🟡 "undisclosed or scraped training data" acceptable, and is distillation from an amber or red
   teacher acceptable?
2. **htdemucs (🔴) is the shipped separator today.** Keep it while the bake-off runs, or pull the Play
   release until a replacement lands?
3. **Religious policy for labels:**
   - Does duff count as music? Does a cappella singing?
   - Should a "remove singing too" option exist?
   - At what strictness does swimwear or revealing clothing get covered?
4. **The `vocals_other` ("keep sound effects") mode** has no 2-stem equivalent. Keep a 4-stem model for
   it, invest in a dialogue/music/effects model, or drop the mode?
5. **REG (region nudity):** proceed at all, given NudeNet's flagged training data?
6. **Budget:** buy D2–D4 (about $800 total), or rely on remote farms?

---

## Appendix A — Candidate catalog with sources

Verified on 2026-09-30 unless marked. "MS" is MVSep Multisong vocals SDR
([leaderboard](https://mvsep.com/quality_checker/multisong_leaderboard?sort=vocals)).

**Separators**

| ID | Weights | Code / weights licence |
|---|---|---|
| SEP-0/4 | `dl.fbaipublicfiles.com/demucs/hybrid_transformer/` (`955717e8`, `04573f0d`); maintained fork [adefossez/demucs](https://github.com/adefossez/demucs) | MIT / [research-only](https://github.com/facebookresearch/demucs/issues/327) |
| SEP-1/2/3 | [UVR model_repo](https://github.com/TRvlvr/model_repo/releases/tag/all_public_uvr_models); geometry from [model_data_new.json](https://raw.githubusercontent.com/TRvlvr/application_data/main/mdx_model_data/model_data_new.json); mobile numbers from [WluhWluh benchmark](https://github.com/WluhWluh/MusicSourceSeparation/blob/main/docs/android-litert-benchmark-2026-07-19.md) | [UVR README](https://github.com/Anjok07/ultimatevocalremovergui) credit request; no licence file |
| SEP-5 | [starrytong/SCNet](https://github.com/starrytong/SCNet); [MSST releases](https://github.com/ZFTurbo/Music-Source-Separation-Training/releases); 2.75 s ONNX: [t4t2k1m](https://huggingface.co/t4t2k1m/yt-precount-scnet-tran-onnx); [scnet-web-wasm](https://github.com/elicwhite/scnet-web-wasm); paper [2401.13276](https://arxiv.org/abs/2401.13276) | MIT; MUSDB data |
| SEP-6 | [DTTNet](https://github.com/junyuchen-cjy/DTTNet-Pytorch); MSST `v1.0.22/dttnet_vocalsg32_ep4082_fix.ckpt` | Apache; MUSDB data |
| SEP-7 | [GTCRN](https://github.com/Xiaobin-Rong/gtcrn); [DPDFNet](https://huggingface.co/Ceva-IP/DPDFNet); [sherpa-onnx enhancement models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speech-enhancement-models); precedent [harammute-android](https://github.com/mrRobot95/harammute-android) | MIT / Apache |
| SEP-8 | [DeepFilterNet](https://github.com/Rikorose/DeepFilterNet) | MIT / Apache |
| SEP-9 | [Spleeter v1.4.0](https://github.com/deezer/spleeter/releases/tag/v1.4.0) | MIT |
| SEP-R1 | [bandit-v2](https://github.com/kwatcharasupat/bandit-v2); weights [zenodo 12701995](https://zenodo.org/records/12701995) | Apache / CC BY-SA 4.0 |
| SEP-R2 | [anvuew/BS-RoFormer](https://huggingface.co/anvuew/BS-RoFormer) | GPL-3.0 |
| SEP-R3 | [KimberleyJSN/melbandroformer](https://huggingface.co/KimberleyJSN/melbandroformer) | MIT |

**Gates**

| ID | Source |
|---|---|
| GATE-0 | YAMNet [class map](https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv); [AudioSet "Chant"](https://research.google.com/audioset/dataset/chant.html) |
| GATE-1 | [PretrainedSED](https://github.com/fschmid56/PretrainedSED) ([2409.09546](https://arxiv.org/abs/2409.09546)); [EfficientSED](https://github.com/theMoro/EfficientSED) |
| GATE-2 | [EfficientAT](https://github.com/fschmid56/EfficientAT) |
| GATE-3 | [CED](https://github.com/RicherMans/CED), [sherpa-onnx audio tagging](https://github.com/k2-fsa/sherpa-onnx/releases/tag/audio-tagging-models) |
| GATE-4 | [TVSM](https://github.com/biboamy/TVSM-dataset) |
| GATE-5 | [inaSpeechSegmenter](https://github.com/ina-foss/inaSpeechSegmenter) |
| GATE-6 | [Silero VAD](https://github.com/snakers4/silero-vad); music caveats: [#565](https://github.com/snakers4/silero-vad/issues/565), [#663](https://github.com/snakers4/silero-vad/issues/663) |
| GATE-7 | pyannote segmentation-3.0 via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models) |

**Vision**

| ID | Source |
|---|---|
| NSFW-0 | [GantMan](https://github.com/GantMan/nsfw_model) (MIT in `LICENSE.md`) |
| NSFW-1 | [OwenElliott xs](https://huggingface.co/OwenElliott/image-safety-classifier-xs) |
| NSFW-2 | [taufiqdp MNv4-S](https://huggingface.co/taufiqdp/mobilenetv4_conv_small.e2400_r224_in1k_nsfw_classifier) |
| NSFW-3 | [viddexa nano](https://huggingface.co/viddexa/nsfw-detection-2-nano) |
| NSFW-4 | [Marqo 384](https://huggingface.co/Marqo/nsfw-image-detection-384) |
| NSFW-R | [Freepik](https://huggingface.co/Freepik/nsfw_image_detector); independent F1: [KidsNanny](https://arxiv.org/html/2603.16181v1), [UnsafeBench](https://arxiv.org/html/2405.03486) |
| GEN-1 | [OMZ 0013](https://github.com/openvinotoolkit/open_model_zoo/tree/master/models/intel/age-gender-recognition-retail-0013) |
| GEN-2 | [SigLIP2 B/32-256](https://huggingface.co/google/siglip2-base-patch32-256) ([ONNX](https://huggingface.co/onnx-community/siglip2-base-patch32-256-ONNX)) |
| GEN-3 | [dima806](https://huggingface.co/dima806/fairface_gender_image_detection) |
| GEN-4 | [MiVOLO](https://github.com/WildChlamydia/MiVOLO) ([licence commit](https://github.com/WildChlamydia/MiVOLO/commit/fd5e933931758a801210a3d58a54be5adb89f2f8)), [mivolo_v2](https://huggingface.co/iitolstykh/mivolo_v2) ([history](https://huggingface.co/iitolstykh/mivolo_v2/commits/main)); independent: [TimmaJ benchmark](https://huggingface.co/TimmaJ/age-gender-race-prediction/blob/main/benchmark/benchmark_vs_pretrained.csv) |
| GENB-1 | [PULC person_attribute](https://github.com/PaddlePaddle/PaddleClas/blob/release/2.6/docs/en/PULC/PULC_person_attribute_en.md) |
| PER-3 | [YOLO-Wholebody34](https://github.com/PINTO0309/PINTO_model_zoo/tree/main/471_YOLO-Wholebody34) |
| FACE-1 | [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet) |
| FACE-2 | [BlazeFace](https://ai.google.dev/edge/mediapipe/solutions/vision/face_detector) |
| FACE-3 | [yolov5-face](https://github.com/deepcam-cn/yolov5-face) |
| PER-1 | [RTMDet-nano person](https://github.com/open-mmlab/mmpose/tree/main/projects/rtmpose) |
| PER-2 | [PicoDet](https://github.com/PaddlePaddle/PaddleDetection/blob/release/2.8/configs/picodet/README_en.md) |
| PER-4 | [YOLO26](https://docs.ultralytics.com/models/yolo26/) |
| Tracker | [ByteTrack](https://github.com/FoundationVision/ByteTrack) |
| REG | [NudeNet](https://github.com/notAI-tech/NudeNet); [C3P finding](https://www.protectchildren.ca/en/press-and-media/news-releases/2025/csam-nude-net) |

**Runtimes and measurement**

| Topic | Source |
|---|---|
| ONNX Runtime | [releases](https://github.com/microsoft/onnxruntime/releases); [perf_test flags](https://raw.githubusercontent.com/microsoft/onnxruntime/main/onnxruntime/test/perftest/command_args_parser.cc); [profiling](https://onnxruntime.ai/docs/performance/tune-performance/profiling-tools.html); [mobile usability checker](https://onnxruntime.ai/docs/tutorials/mobile/helpers/model-usability-checker.html) |
| QNN | [plugin EP repo](https://github.com/onnxruntime/onnxruntime-qnn) ([v2.6.0](https://github.com/onnxruntime/onnxruntime-qnn/releases/tag/v2.6.0)); [Qualcomm licence](https://softwarecenter.qualcomm.com/api/download/software/licenses/ai_model_hub/v1/LICENSE.pdf); [GPL FAQ on non-free libraries](https://www.gnu.org/licenses/gpl-faq.html#GPLIncompatibleLibs) |
| LiteRT | [GPU](https://developers.google.com/edge/litert/next/gpu); [NPU (minSdk 31)](https://developers.google.com/edge/litert/next/npu); [onnx2tf](https://pypi.org/project/onnx2tf/); [litert-torch](https://github.com/google-ai-edge/litert-torch) |
| ExecuTorch | [backends](https://docs.pytorch.org/executorch/stable/backends-overview.html); [demucs-executorch](https://github.com/demixr/demucs-executorch) |
| Thermal | [ADPF thermal](https://developer.android.com/games/optimize/adpf/thermal); [PowerManager](https://developer.android.com/reference/android/os/PowerManager); [Perfetto cpufreq](https://perfetto.dev/docs/data-sources/cpu-freq) |
| Statistics | [Kalibera & Jones](https://kar.kent.ac.uk/33611/); [MLPerf Mobile rules](https://arxiv.org/pdf/2012.02328) |
| Device farms | [Qualcomm AI Hub](https://workbench.aihub.qualcomm.com/docs/); [Samsung RTL](https://developer.samsung.com/remote-test-lab); [Firebase Test Lab](https://firebase.google.com/docs/test-lab); [Device Streaming](https://developer.android.com/studio/run/android-device-streaming) |
| Market | [Omdia Middle East Q2 2026](https://www.thenationalnews.com/future/technology/2026/08/15/samsung-maintains-smartphone-market-share-lead-in-middle-east-amid-iran-war/); [Counterpoint chipset share Q1 2026](https://www.gsmarena.com/counterpoint_shipments_of_mediatek_and_qualcomm_chipsets_decline-news-73215.php) |
