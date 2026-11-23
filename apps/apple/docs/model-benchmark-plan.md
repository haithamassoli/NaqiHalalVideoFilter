# Naqi: model selection and performance re-measurement plan

**Date:** 2026-09-30 · **Status:** plan only, nothing implemented · **Scope:** every ML model in the filter pipeline, the runtimes that execute them, and the measurement method used to choose between them.

**Out of scope for this round:** licensing. Every candidate below is tested on merit alone. Each result row records the model's license so the final choice can be filtered afterwards; nothing is dropped for licensing now.

**Inputs:** five research passes on 2026-09-28/30 (audio separation, audio tagging, scene/NSFW classifiers and VLMs, faces/gender/persons/trackers, runtimes and profiling), the iOS 27 SDK shipped with Xcode 27.0, and the existing measurements in `docs/apple-port/m7-perf-results.md` and `apple-silicon-performance-plan-2026-09.md`. Reported numbers are the authors' own unless marked *measured here*; treat them as screening hints, not evidence.

---

## 0. Summary

1. **There is no hardware baseline.** Every performance number in this repo comes from the simulator or an M3 Mac. Stage 0 measures the current stack on physical iPhones before anything is swapped.
2. **Choose per slot, then as a stack.** Nine slots (§1). Each slot gets a candidate list, a product-level metric, and a pass/fail gate written down before any test runs.
3. **Narrow the candidates in stages** (§7):
   - offline quality screening on the Mac with reference weights;
   - conversion to an Apple runtime and a parity check;
   - on-device micro-benchmarks;
   - the top 1–2 per slot inside the real pipeline;
   - human review, then a decision per device tier.
4. **The long pole is labelled data, not code.** Two corpora (§4), labelled once, reused by every round. Big offline "teacher" models pre-label and humans correct, so labelling time stays bounded.
5. **Reuse what exists** (§8):
   - `BenchTests`, `Stage` signposts and `MemoryFootprint` for on-device runs;
   - one small Python package under `scripts/modelbench/` for offline scoring.

   No new app abstraction until two implementations of a slot actually need to coexist.
6. **Output:** a results log (`docs/benchmarks/results/*.jsonl`), a per-slot report, and a decision table naming one stack per device tier, with at most two variants per slot.

---

## 1. The slots being chosen

| Slot | Job | Current | Question to answer |
|---|---|---|---|
| **S1** Music gate | Which 2.6 s audio chunks contain music | YAMNet ONNX, 16 MB, threshold 0.15, ±2-chunk dilation | Can a free or smaller model miss less music and skip more chunks? |
| **S2** Separator | Keep speech, remove music | htdemucs 4-stem, ONNX fp32, 173 MB, ORT CoreML EP on GPU (ANE fails) | Which model gives the best music removal and speech quality per second of compute, per device tier? |
| **S3** Singing / recitation classifier *(new)* | Optionally mute singing after separation; must never touch Quran recitation or adhan | none | Can any model separate singing from recitation reliably enough to ship as an option? |
| **S4** Scene gate | Whole-frame censor for nudity **and** immodesty (swimwear, revealing clothes, kissing, dancing, nightclub) | GantMan MobileNetV2 1.4, 5 classes, 17 MB, 5 fps | Which model or combination catches immodest scenes with the fewest false-censored seconds? |
| **S5** Face detector | Face boxes at 10 fps | Vision `DetectFaceRectanglesRequest` (default revision) on a 640-px long-side frame | Which detector finds small, profile and veiled faces best within budget? |
| **S6** Tracker | Link detections into tracks at 10 fps | greedy IoU + centre distance | Does a published tracker reduce fragments (each fragment re-votes gender)? |
| **S7** Gender | Decide women/men per track | InsightFace genderage, 96 px face crop, ≤5 votes, `minFacePx = 80` | Which model gives the highest female recall, especially on small faces, children, hijab/niqab and profiles? |
| **S8** Person mask *(new)* | Blur the whole body (hair, clothing), not just the face | none | Is a whole-body blur affordable at 5–10 fps on the lowest tier, and which mask model is stable over time? |
| **S9** Runtime | Execute the chosen models | ONNX Runtime 1.24.2: CoreML EP (MLProgram CPU+GPU, NeuralNetwork ALL) and XNNPACK | ORT (upgraded to 1.30), native Core ML, Core AI (iOS 27), or MLX: which one per model and device tier? |

**The slots interact:**
- S1/S2/S3 share decoded audio.
- S4/S5/S7/S8 share decoded frames.
- In a combined job, the audio and video branches run at the same time and compete for the ANE, GPU and memory.

A slot winner is provisional until Stage 5 measures it inside the full pipeline.

---

## 2. Rules

1. **Performance counts only on physical devices.** The simulator is for correctness only.
2. **Measure quality offline once, and speed on each device.** Once a converted model is shown to match its reference (Stage 3), its quality is device-independent; its speed, memory and heat are not.
3. **Use product metrics, not paper metrics.** SDR, AP and accuracy are used for screening. Decisions use:
   - leaked seconds of immodest content;
   - women's face-frames left uncovered;
   - residual music;
   - change in speech intelligibility;
   - harm to recitation;
   - false-censored seconds per hour;
   - full-job wall time.
4. **Weigh errors unevenly.** Leaving music, a woman's face or an immodest scene uncovered is a hard-gate failure. Over-censoring is a cost that is minimised, not a gate, except where it makes the product unusable (§5 caps it).
5. **Write down the gates before running.** Gates and scoring weights (§5, §10) are fixed and committed before Stage 2 starts. They change only by a dated edit to this file, never after seeing results.
6. **Change one thing at a time.** Each run records model, precision, runtime, compute unit, device, OS build, thermal state and input hash (Appendix A).
7. **Build no infrastructure the stages do not need.** One Python package for offline work, one parameterised Swift test suite for devices, no new app protocols until Stage 5.

---

## 3. Devices and operating systems

| Tier | Device | Chip | RAM | Role |
|---|---|---|---|---|
| T-low | iPhone 12 Pro | A14 | 6 GB | Floor device: memory ceiling and thermal endurance |
| T-low | iPhone 14 Plus | A15 | 6 GB | Most common older phone class |
| T-mid | iPhone 14 Pro | A16 | 6 GB | Last chip without Core AI ahead-of-time compile support |
| T-mid | iPhone 16 Plus | A18 | 8 GB | Mid-tier |
| T-high | iPhone 17 Pro | A19 Pro | 12 GB | Top phone; referenced in the download plan, confirm it is available |
| T-high | M3 Mac | M3 | — | Mac tier and the offline scoring machine |
| to acquire | one A-series iPad and one M-series iPad | — | — | Equal-priority requirement from the September performance plan |

**OS versions:**
- Latest iOS 26.x and iOS 27.0.x, same build on every device of a tier.
- The app floor stays iOS 18 / macOS 15. Candidates that exist only on iOS 27 need a measured fallback:
  - Core AI;
  - `DetectFaceRectanglesRequest` revision 4;
  - `DetectHumanRectanglesRequest` revision 3;
  - `GenerateIterativeSegmentationRequest`;
  - Foundation Models image input;
  - SensitiveContentAnalysis `detectedTypes`.

**Blocker:** the September download plan records every physical device as disconnected. Stage 0 cannot start until at least the 12 Pro, 14 Pro and 17 Pro are connected and trusted for development (`xcrun devicectl list devices`).

**Facts to keep in mind:**
- Core AI's ahead-of-time compile (`xcrun coreai-build compile`) covers **A17 Pro and later and M1 and later only**. A14/A15/A16 always specialise on the device, so their cold start must be measured separately.
- Weight-and-activation int8 (W8A8) quantisation only pays off on the A17 Pro and later and M4 and later Neural Engine. The M3 does not qualify.
- iOS 27 requires the `com.apple.developer.background-tasks.continued-processing.inference` entitlement for **any Neural Engine use while backgrounded**. Background runs must be measured with and without it.

---

## 4. Evaluation corpora

Build these first. Everything else reuses them. Media stays outside git, like `qa-assets/`. A committed `scripts/modelbench/manifest.json` lists every file with its sha256, source, duration, labels path and split. Splits are **by video, channel or film**, never by frame, so near-duplicate frames cannot leak between train and test.

### 4.1 Audio corpus (`naqi-audio-eval`)

**A. Synthetic mixes (have ground-truth stems → SDR, SIR, WER)**
- Speech sources, each with a transcript:
  - Arabic: MASC, QASR/MGB-2, SADA, Common Voice ar, ClArTTS.
  - English: LibriSpeech / DnR v3 speech.
  - Quran recitation: EveryAyah (verse text available), Tadabur.
  - Adhan: ~30 clips, collected by the team (no public corpus exists).
  - A cappella nasheeds: ~40 clips, collected.
- Music beds:
  - MUSDB18-HQ instrumentals (`other+bass+drums`);
  - DnR v3 music;
  - FMA excerpts;
  - full songs **with** vocals (for the singing tests).
- Mix grid: speech-to-music ratio of −5, 0, +5, +10 and +20 dB. 30 s per mix. About 600 mixes, stratified by speech type × ratio × music type.
- Standard sets, so our numbers can be compared with published ones:
  - MUSDB18-HQ test (vocals SDR);
  - DnR v3 test (speech SDR). DnR v3's only Arabic is 4.1 h from one Levantine speaker, so it cannot stand in for our Arabic set.

**B. Real clips (human timeline labels → gate, singing and leakage metrics)**
- 80–100 clips of 1–5 minutes across the target genres:
  - lectures with nasheed intros;
  - vlogs over background music;
  - cartoons;
  - documentaries;
  - news openers;
  - Reels/TikToks with trending songs;
  - Quran recitation videos;
  - adhan;
  - weddings;
  - sports with crowd chants.
- Labels are timeline segments: `speech`, `music_instrumental`, `singing`, `recitation`, `adhan`, `silence_effects`, with music loudness `fg / bg / low_bg` (the OpenBMAT scheme).
  - Tool: Audacity label tracks, which export TSV. Nothing else is needed.
- Public sets for gate calibration:
  - OpenBMAT (27.4 h of TV, loudness classes);
  - AVASpeech-SMAD (45 h, frame labels);
  - MUSAN;
  - Jamendo SVD (singing detection).

### 4.2 Video corpus (`naqi-video-eval`)

**Scene gate (S4)**
- 200–300 clips of 1–3 minutes.
- Labels are timeline segments with:
  - **severity:**
    - S0 none;
    - S1 mild (sleeveless, short skirt, tight);
    - S2 moderate (swimwear, cleavage, lingerie-like, suggestive dance, kissing);
    - S3 partial nudity or sexual suggestion;
    - S4 explicit.
  - **tags:** `nudity, swimwear, revealing, cleavage, kissing, dancing, nightclub, drawing`.
- Which severities must be blurred is decided per strictness level, separately from the labels.
- Include hard negatives, the Pornography-800 "difficult" idea:
  - modest beach and pool;
  - swimming and gymnastics;
  - medical;
  - statues and art;
  - cartoons;
  - skin-heavy but modest scenes.
- Double-label 15%. Report weighted Cohen's κ for severity and segment IoU for boundaries.
- Public supplements:
  - LSPD (500k images incl. `sexy`, 4,000 videos; by request);
  - NPDI Pornography-800 / 2k (by request);
  - Open Images V7 image-level labels `Bikini, Kiss, Dance, Belly dance, Nightclub, Swimwear, Miniskirt`;
  - Kinetics-700 (`kissing`, `belly dancing`, `salsa dancing`);
  - AVA (`kiss`, `dance`, `hug`);
  - SenBen (movie frames with `immodesty`, `kissing`, `sexually suggestive` tags), if access is granted.

**Faces and gender (S5–S7)**
- Public:
  - WIDER FACE val (easy/medium/hard);
  - MIAP (Open Images boxes with perceived gender presentation and age);
  - FairFace val and UTKFace (gender on crops);
  - Casual Conversations v2 (video, self-reported gender, skin tone);
  - DanceTrack val and MOT17 subsampled to 10 fps (tracking).
- Own set: 60 clips (~2 min each) chosen for the weaknesses:
  - crowds and wide shots (faces under 32 px);
  - hijab and niqab;
  - children;
  - profile and back-of-head;
  - fast cuts;
  - low light.
- Face tracks are pre-labelled offline by a teacher ensemble:
  - SCRFD-34G or RetinaFace-R50 at full resolution;
  - BoT-SORT;
  - MiVOLO face+body.

  Humans then correct misses, identity switches and gender at 2 fps, and boxes are interpolated between corrected keyframes.

**Person masks (S8)**
- COCO val2017 person subset (mask AP).
- DAVIS 2017 / MOSE val (temporal mask quality).
- 30 own clips with pseudo-ground-truth masks from SAM 3 / SAM 2.1-large on the Mac, human-corrected every 1 s.

**Pipeline timing clips (fixed; used for every end-to-end run)**

| Clip | Why |
|---|---|
| `tv1-h264.mp4`: 643 s, 1080p, 29.97 fps, H.264 (existing) | Continuity with the M7 and S23 numbers |
| 2-min 4K HEVC 60 fps | Decode/encode ceiling |
| 60-s portrait 1080×1920 (Reel/TikTok) | The most common shared clip |
| 30-min 720p 25 fps lecture with nasheed intro | Typical long talk; the gate should skip most chunks |
| 90-min film (`qa-assets/long-film.mp4`, existing) | Soak, thermals, checkpoint path |
| 60-min audio-only (m4a) | Audio-only job shape |
| 1-min HDR (HLG or PQ) | Tone-map path |

---

## 5. Metrics and proposed gates

The gates below are **proposals to confirm and commit before Stage 2** (rule 5). "Baseline" means the current model measured in Stage 0 on the same corpus.

### 5.1 Runtime metrics (every slot)

| Metric | How |
|---|---|
| Cold / cached / resident load | Measured separately. Cold = no compiled cache; cached = new process with a warm cache; resident = session already loaded. Reset only the cache under test. |
| Latency p50 / p90 per inference | 10 warm-ups discarded, then ≥50 timed runs × 3 process launches; median, p10/p90, IQR, bootstrap 95% CI |
| Pipeline throughput | Frames/s or ×realtime inside the real pass, from `Stage` signposts |
| Compute placement | % of ops and of estimated cost on ANE/GPU/CPU, plus the number of device transitions. Sources: `MLComputePlan` (Core ML), ORT `ProfileComputePlan`, the Core AI Instruments template. |
| Peak memory | `ledger_phys_footprint_peak` from `TASK_VM_INFO`, read in a **fresh process per candidate**. This is the kernel's own high-water mark, so it catches load-time peaks that the sampled `MemoryFootprint` misses. `os_proc_available_memory()` gives headroom. |
| Bundle and cache size | Model bytes in the app, compiled-cache bytes on disk |
| Energy | Power Profiler template on iPhone (can record on-device without a cable), `powermetrics --samplers cpu_power,gpu_power,ane_power` on Mac |
| Sustained | 15-minute loop: per-minute median and `ProcessInfo.thermalState`; report first-minute vs last-minute ratio |

### 5.2 S1 music gate (scored at 2.6 s chunk level, after the ±2 dilation)
- **Pass rate p** = chunks sent to the separator ÷ all chunks.
- **Music miss rate** = ground-truth music seconds inside skipped chunks ÷ all music seconds, reported per loudness class.
- **Cost** = gate ms per minute of audio.
- Sweep threshold × dilation to trace miss rate against pass rate.
- **Gate:** miss ≤ 1.0% overall and ≤ 3% on `low_bg`. Among passing configurations, pick the lowest p.
- **Log per-class scores on recitation and adhan first.** The current gate's index range 24–32 includes AudioSet **27 Chant** and **28 Mantra**, and 132–276 includes *Middle Eastern music*, *Vocal music* and *A capella*. For S1 a false trigger only costs compute; for S3 it would be harm.

### 5.3 S2 separator
- **Synthetic mixes:**
  - speech-stem SI-SDR and SI-SDR improvement over the mixture;
  - SIR, which directly measures music leakage (`fast_bss_eval`);
  - ESTOI.

  Report each per speech type (Arabic lecture / English / Quran / adhan / a cappella) × ratio bucket.
- **Speech preservation:**
  - WER of the output against the known transcript;
  - Whisper large-v3 for Arabic and English, `tarteel-ai/whisper-base-ar-quran` for recitation;
  - reported as ΔWER against the clean speech source.
- **Real clips, no reference available:**
  - residual music = SoundAnalysis `music` + `singing` probability and the S1 winner's score, averaged over ground-truth music regions of the output;
  - DNSMOS / UTMOSv2 of the output relative to the input.
- **Listening test:**
  - 20 clips × the top 4 models × 5 listeners from the target audience;
  - blind, MUSHRA-lite, rating 0–100 on both "music remaining" and "speech naturalness";
  - original and htdemucs included as anchors.
- **Gates:**
  - Recitation and adhan mixes: ΔWER ≤ +3 points absolute, and SI-SDR no more than 1 dB below baseline.
  - SIR ≥ baseline on the music-bed mixes.
  - Listening "speech naturalness" no more than 10 points below baseline.
  - Any stem-policy change (e.g. dropping ambience) is recorded as a product decision, not hidden in a score.

### 5.4 S3 singing / recitation
- Segment F1 for `singing` vs `speech` vs `recitation/adhan`, with `sed_eval` at 1 s segments.
- **Harm rate** = recitation + adhan seconds flagged as singing ÷ all recitation + adhan seconds.
- **Gate:** harm ≤ 0.5% **and** singing recall ≥ 90%. If no candidate passes, S3 does not ship. That is an acceptable outcome.

### 5.5 S4 scene gate
- **Frame level (5 fps samples):** PR-AUC per severity; TPR at 1% and 5% FPR.
- **Event level:** measured after the pipeline's own hysteresis, simulated from the `Edl.swift` constants:
  - pre-roll 500 ms, post-roll 1,500 ms;
  - bridge 400 ms, minimum full-frame 500 ms.

  Metrics:
  - event recall (any overlap);
  - **leaked seconds per positive hour**;
  - **false-censored seconds per hour** on the negative set;
  - onset lag (should be ≤ 0 because of pre-roll).
- **Strictness calibration:** the slider maps to different thresholds for each model. Fit that mapping on the calibration split and report metrics at strictness 0 / 50 / 100 on the test split.
- **Gate (default strictness):**
  - S3–S4 event recall ≥ 99%;
  - S2 event recall ≥ 95%;
  - false-censored ≤ 60 s/h on the negative set.
- Report the S1 tier separately; strictness 100 is expected to catch it.

### 5.6 S5 detector, S6 tracker, S7 gender (scored alone and together)
- **S5:**
  - WIDER FACE AP (E/M/H) at our operating resolution (640 long side) **and** at 960, 1280 and 2×2 tiles;
  - recall by face size in source pixels (<16, 16–32, 32–64, 64–128, >128);
  - recall on MIAP female-presenting boxes, on the hijab/niqab subset and on profiles.
- **S6:**
  - HOTA, IDF1 and ID switches at 10 fps (TrackEval, DanceTrack val subsampled, plus our face tracks);
  - **fragments per true track**.
- **S7:**
  - track-level female recall at fixed male-censored rates;
  - accuracy by face size (16/24/32/48/64/96+ px), age band (children), hijab/niqab and profile;
  - MiVOLO body-only accuracy separately.
- **Combined product metric:**
  - **uncovered women-face-frames**: ground-truth women's face boxes at native frame rate that are less than 90% covered by the EDL region after interpolation and 25% padding;
  - divided by all ground-truth women's face-frames.
- **Track-leak rate:** share of female tracks with **any** uncovered frame. This is what a viewer notices, because one exposed frame per track reads as a failure.
- Report both metrics split by face size, skin tone, veil and age. Face-size bins are <24, 24–48 and >48 px.
- **Gates:**
  - uncovered ≤ 0.5% for faces ≥ 24 px (smaller faces reported separately);
  - female track recall ≥ 99%;
  - men censored ≤ 20% of male tracks.
- Also sweep `minFacePx` (currently 80 px on the 640 frame), `voteCap`, `genderConfidenceFloor` and the default-when-no-vote rule. These pipeline knobs can move the metric as much as a model swap.

### 5.7 S8 person mask
- Mask AP (COCO person), J&F (DAVIS/MOSE), Boundary IoU.
- **Temporal flicker** = mean IoU of the same person's mask between consecutive rendered frames.
- **Product:** % of ground-truth women's body area covered per frame after mask dilation, and leaked boundary pixels.
- **Gate:** coverage ≥ 98% of body area on 95% of frames. The cost must fit the T-low budget in §5.9.

### 5.8 S9 runtime conversion parity (Stage 3)
- **Audio outputs:** SNR ≥ 40 dB against the reference fp32 output, and the Stage 2 metric within 0.2 dB.
- **Classifiers:** top-1 agreement ≥ 99.5%; score correlation ≥ 0.99.
- **Detectors:** matched box IoU ≥ 0.95; AP within 0.5 points.
- **Masks:** mask IoU against the reference ≥ 0.97.
- fp16 must be checked with `MLModelValidator.find_failing_ops_with_infinite_output/nan_output` (coremltools) or the Core AI debugger. htdemucs is known to overflow in fp16.

### 5.9 Speed and memory targets (whole job, proposed)

| Job shape | T-low (A14/A15) | T-mid | T-high |
|---|---|---|---|
| Music-only, 30-min lecture | ≥ 3× realtime | ≥ 5× | ≥ 10× |
| Censor-only, `tv1` 1080p | ≥ 1.5× realtime | ≥ 2.5× | ≥ 4× |
| Both, `tv1` | ≥ 1.2× realtime | ≥ 2× | ≥ 3× |

- **Peak memory:** ≤ 1,536 MiB on 6 GB phones. Measure a per-class budget for 8 GB/12 GB phones, iPads and the Mac in Stage 0.
- **Cold start for the first job:** report it; no gate yet.

---

## 6. Candidates

Priority:
- **R1** = tested in round 1.
- **R2** = tested only if no R1 candidate passes, or as a cheaper fallback.
- **X** = reference or teacher only: offline labelling or measuring the quality ceiling.

Sizes and scores are as reported by the source.

### S1 music gate

| ID | Candidate | Notes | P |
|---|---|---|---|
| G0 | YAMNet (current) | 3.7M params, AudioSet mAP 0.306 | baseline |
| G1 | Apple SoundAnalysis `version1` | Built into the OS, 0 MB; 303 labels incl. `music`, `singing`, `choir_singing`, `rapping`, `humming`, `speech`; no `chant` label. Measured ~880× realtime on an M3 in research. The iOS 27 SDK has no newer public identifier. | R1 |
| G2 | CED-tiny / CED-mini | 5.5M / 9.6M, 16 kHz, mAP 0.481 / 0.490; ONNX exists ([mispeech/ced-tiny](https://huggingface.co/mispeech/ced-tiny), sherpa-onnx int8 6.1 MB) | R1 |
| G3 | EfficientAT mn04 / mn10 / dymn04 | 0.98M / 4.9M / 2.0M, 32 kHz, mAP 0.432 / 0.471 / 0.450; PyTorch only ([repo](https://github.com/fschmid56/EfficientAT)) | R1 |
| G4 | PANNs CNN10 / MobileNetV2 | 25 / 21 MB, mAP 0.380 / 0.383 | R2 |
| G5 | PretrainedSED `frame_mn06` / `frame_mn10` | Frame-level (40 ms) output; 1.6M / 3.8M ([repo](https://github.com/fschmid56/PretrainedSED)) | R2 |
| G6 | inaSpeechSegmenter; INA `ssl-music-detection` (2026) | Speech/music specialists; INA reports F1 91.2 on MIREX + OpenBMAT | R2 |
| G7 | Silero VAD (Core ML 0.9 MB); `Speech.SpeechDetector` (iOS 26) | Speech presence as a complementary signal | R2 |
| X | AST, BEATs, PaSST, CED-base | Offline references for labelling | X |

### S2 separator

**A. Vocal separators: keep every voice, which is safest for recitation**

| ID | Candidate | Size / rate | Reported quality | Apple-side evidence | P |
|---|---|---|---|---|---|
| M0 | htdemucs (current) | 42M, 44.1 kHz | Multisong vocals 8.24 | fp32 GPU only; every Core ML port fails on the ANE | baseline |
| M1 | htdemucs_ft (vocals) | 42M | 8.38 | Same limits as M0 | R2 |
| M2 | UVR MDX-Net Kim Vocal 2 / Voc_FT | ~16M, 59–67 MB | 9.61–9.73 | **Core ML fp16 port running on the ANE** ([gyoom/UVR-MDX-CoreML](https://huggingface.co/gyoom/UVR-MDX-CoreML)); STFT stays outside the model, which matches our vDSP path | **R1** (speed) |
| M3 | MDX23C InstVoc HQ | 448 MB | 10.13–10.20 | ONNX/TensorRT export exists | R2 |
| M4 | Mel-RoFormer Kim / unwa FT2 | 228M | 10.98–11.06 | **Core AI port**: 8 s chunk in 1.23 s on iPhone 17 Pro GPU, cold 3.8 s ([mlboydaisuke](https://huggingface.co/mlboydaisuke/MelBandRoformer-Vocal-CoreAI)); iOS 27 only | **R1** (quality) |
| M5 | unwa Mel-RoFormer small | 203 MB | 10.75 | none | **R1** |
| M6 | BS-RoFormer: Resurrection / anvuew / Leap Xe | 204 MB+ | 11.34 / 11.42 / 11.76 | none; Leap Xe is the best downloadable single model | **R1** (ceiling, Mac/T-high) |
| M7 | Windowed RoFormer (Smule) | — | MUSDB 11.17 with 44.5× fewer attention FLOPs | none | R2 |
| M8 | SCNet masked small / XL IHF | 42 / 214 MB | 8.35 / 9.68 | none | R2 |
| M9 | DTTNet | 5M | MUSDB 10.12, Multisong 8.74 | none | **R1** (tiny) |
| X | Logic-style `bs_logic_6stem`, MVSep hosted models | — | 11.27–12.33 | Quality reference only | X |

**B. Dialogue/cinematic separators: keep speech (optionally effects) and put singing with the music**

| ID | Candidate | Notes | P |
|---|---|---|---|
| D1 | TIGER-DnR | 3 × 1.4M params, 44.1 kHz mono, DnR speech SI-SDR 15.5; LiteRT fp16 port with STFT as conv ([litert-community](https://huggingface.co/litert-community/TIGER-DnR-LiteRT)); fp16 needs eps ≥ 1e-4 | **R1** |
| D2 | BandIt v2 multilingual | 149 MB, 48 kHz mono, DnR v3 speech 12.30 ([Zenodo](https://zenodo.org/records/12701995)) | **R1** |
| D3 | Banquet 4-stem (dialogue / singing / instrumental / effects) | 19.7M; dialogue SNR 14.9, singing 9.9; needs a precomputed PaSST query embedding ([Zenodo](https://zenodo.org/records/13327983)). Gives S3 a singing stem for free | **R1** |
| D4 | BandIt Plus (DnR v2), MRX, TUSS, CDX23 Demucs | Older or larger cinematic models | R2 |

**C. Separators trained on speech vs sung music**

| ID | Candidate | Notes | P |
|---|---|---|---|
| V1 | `AliceN/Roformer-SpeechSep` (BS 102 MB / Mel 435 MB) | Trained to separate a speaker from music **that contains vocals**. The most direct fit for "remove singing", and the most likely to damage recitation. Must pass §5.3 recitation gates first. ([HF](https://huggingface.co/AliceN/Roformer-SpeechSep)) | **R1** |
| V2 | Jasper "musicless" Mel-RoFormer (`nomusic` / `music`), 457 MB | Unscored ([ckpt](https://huggingface.co/noblebarkrr/mvsepless_resources/resolve/main/mel_band_roformer/mbr_musicless_jasper.ckpt)) | **R1** |

**D. Speech enhancement and system units**

| ID | Candidate | Notes | P |
|---|---|---|---|
| E1 | `AUSoundIsolation` HighQualityVoice (iOS 18) | 0 MB, public Audio Unit, offline via AVAudioEngine manual rendering. In a research test on an M3: ~38× realtime on 120 s, SoundAnalysis `music` confidence 0.32 → 0.07. Drops ambience. Its handling of singing and device availability are unknown. | **R1** |
| E2 | `AUSoundIsolation` Voice (iOS 16) | Older model | R2 |
| E3 | DeepFilterNet3 | 2.1M, 48 kHz; Core ML ANE 2.2 MB ([aufklarer](https://huggingface.co/aufklarer/DeepFilterNet3-CoreML)); likely weak on loud music | **R1** (cheap baseline) |
| E4 | MossFormer2_SE_48K, ZipEnhancer, FRCRN | ClearerVoice-Studio; ONNX with STFT built in | R2 |
| X | UniPASE (546M), SAM Audio small/base (text prompt "music"; MLX 1.2–2.5 GB) | Mac-only quality ceiling and teacher | X |

**Things to watch in every separator:**
- **Sample rate and channels.** BandIt v2 is 48 kHz mono; TIGER is mono. Mono models run per channel, and that cost is counted.
- **Chunk length.** RoFormers use 8–11 s windows versus our 2.6 s. A longer window changes memory and the lookahead of the ring buffer, and it must be re-swept.
- **Stem semantics.** Record whether each model keeps ambience and effects.

### S3 singing / recitation classifier

| ID | Candidate | Notes | P |
|---|---|---|---|
| C1 | SoundAnalysis labels `singing`, `choir_singing`, `rapping`, `humming` vs `speech` | 0 MB; run on the separated voice | **R1** |
| C2 | CED / EfficientAT AudioSet outputs (Singing, A capella, Vocal music, **Chant, Mantra**, Speech) | Log what recitation scores before trusting anything | **R1** |
| C3 | Energy of Banquet's singing stem (D3) or V1's music stem | Comes free if D3 or V1 is chosen for S2 | **R1** |
| C4 | Linear probe on CED / EfficientAT embeddings, trained on our recitation / adhan / nasheed / song / speech clips | Expected to be needed: no public model or study separates tarteel from singing. Maqam-478 and EveryAyah can supply recitation positives. | **R1** if C1–C3 fail the harm cap |
| C5 | Distilled singing-voice detector (65.7K-parameter model, [arXiv 2011.04297](https://arxiv.org/abs/2011.04297)) | Jamendo-trained; needs retraining with recitation negatives | R2 |

### S4 scene gate

**Dedicated classifiers**

| ID | Candidate | Size / input | Why | P |
|---|---|---|---|---|
| N0 | GantMan MobileNetV2 1.4 (current) | 17 MB, 224 | `sexy` class; ~91.5% on its own data | baseline |
| N1 | OwenElliott image-safety-classifier xs / s / m | 3–11M, 224, SwiftFormer | 97.8–98.1% on its own ~320k set; NSFW includes "highly suggestive"; ONNX in repo with preprocessing built in; **Core ML `s` exists** ([HF](https://huggingface.co/InspiratioNULL/image-safety-classifier-s-CoreML)) | **R1** |
| N2 | Marqo nsfw-image-detection-384 | 5.6M, 384, ViT-Tiny | 98.56% on its own set; Core ML via zorrobyte NSFWScanner (~11 MB fp16) | **R1** |
| N3 | viddexa nsfw-detection-2 nano / mini | 4M / 17.7M, EfficientNet | LSPD F1 for `sexy` 85.2 / 90.9 | **R1** |
| N4 | AdamCodd vit-base-nsfw-detector | 86M, 384 | Deliberately strict: cleavage and "too much skin" count as NSFW; ONNX fp16 / int8 in repo | **R1** (strict variant) |
| N5 | prithivMLmods `siglip2-x256-explicit-content` | 93M, 256 | "Enticing or Sensual" class F1 0.928 | **R1** (quality) |
| N6 | Freepik nsfw_image_detector | 86M, 448, EVA02 | Four levels (neutral / low / medium / high); best reported on mild content | R2 on device, **X** teacher |
| N7 | NudeNet v3 320n detector | ~3M, 320, YOLOv8n | Part-level `*_COVERED` / `*_EXPOSED` boxes (breast, buttocks, belly, armpits): a region blur, not a whole frame | **R1** (complement) |
| N8 | lucid-nsfw-4class | 5.6M, 384 | Marqo backbone with a `suggestive` class | R2 |
| N9 | MobileNetV4-conv-small NSFW | 2.5M, 224 | Smallest modern backbone | R2 |
| N10 | EraX-NSFW YOLO11n | 5.5 MB | Has a `make_love` act class | R2 |

**Apple built-ins**

| ID | Candidate | Notes | P |
|---|---|---|---|
| N11 | `ClassifyImageRequest` | 0 MB, 1,303 labels. Relevant ones present: `swimsuit`, `sunbathing`, `pool`, `beach`, `dancing`, `bellydance`, `nightclub`, `disco_ball`, `wedding_dress`, `leotard`, `bath`, `shower`, `bed`. **No** `bikini`, `kiss`, `lingerie`, `cleavage`. A context signal, not a gate. | **R1** (complement) |
| N12 | `GenerateImageFeaturePrintRequest` revision 2 + linear probe | 0 MB, 768-d, ~3.3 ms on M3 (measured in research). Train probes for `kissing`, `dancing`, `revealing`, `swimwear` on our labels. | **R1** |
| N13 | SensitiveContentAnalysis | Returns a verdict only when the user has enabled Sensitive Content Warning or Communication Safety. Boolean output; iOS 27 adds `.sexuallyExplicit` / `.goreOrViolence` types; nudity only. Per-frame use means `analyzeImage` on each sample. | R2 (opt-in secondary) |

**Embedding model + linear probes** (one image model can serve S4 probes *and* S7 gender, see H5)

| ID | Candidate | Image tower | Latency reported | P |
|---|---|---|---|---|
| N14 | MobileCLIP-S0 / S2 (v1, Apple Core ML packages exist) | 11.4M / 35.7M, 256 | 1.5 / 3.6 ms on iPhone 12 Pro Max | **R1** |
| N15 | MobileCLIP2-S0 / S2 | same towers, better training | same | **R1** |
| N16 | SigLIP2 B/16-256 | 93M | 5.2 ms on M5 Pro, 100% of ops on ANE ([FluidInference Core ML](https://huggingface.co/FluidInference/siglip2-base-patch16-256-coreml)) | **R1** |
| N17 | PE-Core T16 / S16 (Meta) | 6.1M / 23.8M, 384 | Core ML port claimed for ANE | R2 |
| N18 | OpenCLIP B/32-256 DataComp, TinyCLIP 39M | 86M / 39M | 6.2 / 5.2 ms on iPhone 12 Pro Max | R2 |

**Vision-language models: a verifier on flagged spans only, or offline labellers**

| ID | Candidate | Notes | P |
|---|---|---|---|
| L1 | Apple Foundation Models, image `Attachment` (iOS 27, Apple Intelligence devices) | Guardrails block "adult materials" on image input. Record the **refusal rate itself as a signal** and the latency per image. | R2 |
| L2 | FastVLM 0.5B / 1.5B | TTFT 166 ms (0.5B at 1024²) on M1 Max; Core ML vision encoder + MLX LLM | R2 |
| L3 | Qwen3-VL-2B (official Core AI export at 448²), LFM2.5-VL-450M, Gemma 4 E2B (iPhone 17 Pro text TTFT 0.3 s), SmolVLM2-500M, MiniCPM-V 4.6 | On-device second opinion on the Mac/T-high tier | R2 |
| X | VisionGuardrail-4B/9B (strict prompt already flags cleavage, swimwear, lingerie), LlavaGuard 7B with an edited policy, ShieldGemma 2, Freepik EVA02, SigLIP2 so400m zero-shot | Offline teacher ensemble for pre-labelling the corpus and training a student | X |

**Distillation track (only if no off-the-shelf candidate passes §5.5)**
- Student: SwiftFormer-S (N1 backbone), the MobileCLIP-S0 image tower, or a FeaturePrint probe, plus a small temporal head over per-frame embeddings.
- Teacher: the X ensemble above.
- Recipe:
  - teacher and student see the same augmented view;
  - KL on soft labels, plus BCE on human labels;
  - evaluate only on the human-labelled, video-disjoint test split.

### S5 face detector

| ID | Candidate | Size | WIDER hard (protocol) | Apple-side evidence | P |
|---|---|---|---|---|---|
| F0 | Vision `DetectFaceRectanglesRequest` (current revision) | 0 | — | baseline | baseline |
| F1 | Vision revision 4 (iOS 27) | 0 | unknown; Apple says only "better precision and recall, tighter boxes". It is the default on iOS 27, so the 25% `keyframePad` must be re-tuned for it | new in the iOS 27 SDK | **R1** |
| F2 | YuNet_n / YuNet 2023mar | 76K / 55K params | 81.1 (orig) / 75.0 | Core ML fp16 640² ([camstack](https://huggingface.co/camstack/camstack-models/tree/main/faceDetection/yunet/coreml)) | **R1** |
| F3 | SCRFD 500M / 2.5G / 10G | 0.6–3.9M | 68.5 / 77.9 / 83.1 (VGA); 500M reaches 82.0 at original size | Core ML fp16 (camstack) | **R1** |
| F4 | YOLOv8n/s-face (lindevs), YOLO11n-face | 3.0M / 11.1M | 79.4 / 82.9 | Ultralytics Core ML export | **R1** (s) |
| F5 | RetinaFace MobileNet0.25 / MV2 (yakhyo) | 0.44M / 3.2M | 73.8–81.0 / 83.6–86.6 | Core ML 640² for MNet0.25 | R2 |
| F6 | DamoFD 2.5G / 10G | 0.44M / 1.27M | 78.7 / 84.1 (VGA) | none | R2 |
| F7 | BlazeFace full-range | 1.1 MB | — | Out of scope beyond 5 m and for profiles, per its model card | R2 |
| X | SCRFD-34G, RetinaFace-R50, TinaFace, EgoBlur | heavy | 85–93 | Offline teachers for labels | X |

Resolution matters as much as the model: RetinaFace-MNet0.25 hard AP is 47.3 at VGA and 81.0 with a large resize. Sweep 640, 960 and 1280 long side and 2×2 tiles for every detector.

### S6 tracker

| ID | Candidate | Notes | P |
|---|---|---|---|
| T0 | Current greedy IoU + centre distance | baseline | baseline |
| T1 | ByteTrack | Standard; weak at low frame rates | **R1** |
| T2 | OC-SORT | Its authors list low frame rate as a known weakness | **R1** |
| T3 | C-BIoU | Buffered IoU widens the match at large frame gaps; 360+ FPS on CPU; the cheapest fit for 10 fps | **R1** |
| T4 | BoT-SORT / Deep OC-SORT + re-ID (face embedding MobileFaceNet, OSNet x0.25, or FeaturePrint) | Re-ID across cuts cuts fragments | **R1** |
| T5 | Vision `TrackObjectRequest` between samples | Uses Apple's own tracker | R2 |

Evaluate offline with `boxmot` (supports reduced-fps evaluation and variable-time-step Kalman filters). Port only the winner to Swift; the candidates are 200–400 lines each.

### S7 gender

| ID | Candidate | Notes | P |
|---|---|---|---|
| A0 | InsightFace genderage (current) | 0.3M params, 96 px; no official accuracy | baseline |
| A1 | MiVOLO v2 (face + body, 384², 6-channel) | FairFace 97.5, LAGENDA 97.99; body input covers faces too small to vote. Public ONNX is age-only, so **the gender head must be exported ourselves from PyTorch**. | **R1** |
| A2 | MiVOLO-D1 (224²) | Body-only gender 93.6–96.7 on published sets | R2 |
| A3 | FairFace ResNet-34 | Gender 0.957 on external sets; drops to 0.833 at ages 0–9 | **R1** |
| A4 | SigLIP2 gender classifiers (`prithivMLmods/Gender-Classifier-Mini`, `Realistic-Gender-Classification`) | ~97% self-reported | **R1** |
| A5 | Linear probe on the S4 embedding winner (N12/N14–N16) on face and body crops | Free if S4 uses an embedding model; CLIP linear probe reaches 96.5–97.7 on FairFace | **R1** |
| A6 | dima806 FairFace ViT, rizvandwiki ViT | ~92–93% | R2 |
| X | FaceXFormer, CLIP ViT-L probe | References | X |
| — | Apple Foundation Models | Excluded: Apple's acceptable-use rules forbid inferring sensitive attributes from biometric data | — |

For every candidate also sweep:
- minimum face size for a vote;
- vote count;
- confidence floor;
- the no-vote default (censor vs skip).

### S8 person mask

| ID | Candidate | Notes | P |
|---|---|---|---|
| P0 | Vision human rectangles (revision 3 on iOS 27) + box blur | Cheapest; no mask | **R1** (baseline) |
| P1 | Vision `GeneratePersonInstanceMaskRequest` | Per-person masks, maximum 4 people | **R1** |
| P2 | Vision `GeneratePersonSegmentationRequest` fast / balanced + face-track association | Semantic mask for all people | **R1** |
| P3 | Vision `GenerateIterativeSegmentationRequest` seeded with a body box (iOS 27) | Promptable, SAM-like; fast / balanced / accurate. The model downloads before first use (`downloadAssets`), so time that separately. No video memory: it must be re-seeded on every frame. | **R1** |
| P4 | YOLO26n-seg / YOLO11n-seg | YOLO26n-seg reported at 4.8 ms on iPhone 17 Pro ANE | **R1** |
| P5 | EdgeTAM, seeded by the detector every N frames | 15.7 FPS video on iPhone 15 Pro Max; temporal stability | **R1** |
| P6 | RF-DETR-Seg N, EfficientTAM-S at 512², RVM MobileNetV3 matting | Heavier or less proven on device | R2 |
| P7 | YOLOE-26n-seg with a "woman" text prompt fixed at export | Open-vocabulary; the export is a plain Core ML segmenter. Gender from a prompt must still pass the S7 gates. | R2 |
| X | SAM 3 / SAM 3.1, SAM 2.1-large | Pseudo-ground truth on the Mac | X |

**Association rule to test:** a person mask belongs to a face track when the face box sits in the top third of the person box with IoU-over-face ≥ 0.8. The track's gender verdict decides whether the body is blurred.

### S9 runtime and precision

| ID | Runtime | When | P |
|---|---|---|---|
| R0 | ONNX Runtime 1.24.2 (current) | Baseline | baseline |
| R1 | ONNX Runtime 1.30.0 | 1.27 and 1.28 added CoreML-EP support for Sin, Cos, Tile, GatherND, Where and more. Re-count htdemucs partitions; if they fall, this is the cheapest possible win. | **R1**, in Stage 0 |
| R2 | Native Core ML (coremltools 9, MLProgram) | PyTorch → `torch.jit.trace` / `torch.export` → Core ML. The ONNX → Core ML route is gone (coremltools removed it in 6.0). | **R1** for every PyTorch candidate |
| R3 | Core AI (iOS/macOS 27) | `torch.export` + `coreai-torch`; `.aimodel`; ahead-of-time compile on A17 Pro+ / M1+; explicit `specialize()`, persistent cache. iOS 27.0 bugs are reported: wrong fp16 pose outputs, wrong in-graph `argmax`, stale caches. Retest on 27.1. | **R1** on T-high, R2 elsewhere |
| R4 | MLX / mlx-swift | GPU only, no ANE; relevant for VLMs and big separators on the Mac | R2 |
| R5 | ExecuTorch (Core ML backend) | Only if direct coremltools conversion fails | R2 |
| R6 | LiteRT | Only for candidates that exist only as TFLite (e.g. TIGER-DnR port) | R2 |

**Precision variants per model:** fp32, fp16, int8 weights, 6-bit and 4-bit palettised, W8A8 (A17 Pro+/M4+ only).

**Known Neural Engine design rules** for converted models:
- Input and output tensors laid out as (B,C,1,S);
- 1×1 Conv2d instead of Linear;
- attention split per head;
- STFT as fixed DFT conv or matmul, with no complex tensors;
- layer norm and softmax pinned to fp32 via `FP16ComputePrecision(op_selector=…)` when they overflow;
- mask fill of −6e4, not −1e9.

---

## 7. Stages

### Stage 0: hardware baseline of the current stack (first; ~1 week once devices are connected)
1. Build Release.
2. Run on T-low, T-mid, T-high and the Mac:
   - existing `BenchTests.tv1EndToEnd`, `demucsProviders` and `demucsFootprint`;
   - the pipeline clip set (§4.2) for all four job shapes.
3. Add the missing clocks from the September plan (§4 "Missing measurements"):
   - Start-to-published wall time;
   - model load/compile inside `AnalyzePass` and `AudioPipeline`;
   - download-only time.
4. Record for every model:
   - compute placement (`ProfileComputePlan` for ORT);
   - the three load states;
   - `ledger_phys_footprint_peak`.
5. Upgrade ONNX Runtime 1.24.2 → 1.30.0 on a branch and re-measure. Re-count htdemucs partitions.
6. Run the current models on the corpora (§4) as soon as each corpus exists. That gives every slot a quality baseline on the same data as the challengers.

**Exit:** one baseline table per device tier (speed, memory, heat, cold start) and per slot (quality), committed to `docs/benchmarks/baseline.md`.

### Stage 1: corpora and offline harness (parallel with Stage 0; ~2–3 weeks, labelling is the long pole)
- Build `scripts/modelbench/` (§8.1), the manifest and the label import.
- Pre-label with teacher ensembles:
  - audio: AST/BEATs tags + SAM Audio stems as hints;
  - video: VisionGuardrail/LlavaGuard/Freepik severity, SCRFD-34G faces, SAM 3 masks.

  Then human-correct.
- Commit the gates and weights (§5, §10) before Stage 2.

**Exit:** manifest committed; κ and segment IoU for the double-labelled subset are reported; every current model has its §5 metrics on the test split.

### Stage 2: offline quality screening on the Mac (~2 weeks)
- Run every R1 candidate with its **reference weights** (PyTorch or ONNX fp32) on the relevant corpus.
- Score with the product metrics.
- Record Mac CPU/GPU time per minute of media only as a rough cost filter: at 20× the baseline cost, a candidate needs a correspondingly large quality gain to continue.
- Keep at most **3 per slot** that pass the gates, plus the baseline. If none passes, promote R2 candidates or start the distillation track for that slot.

**Exit:** a per-slot shortlist with quality metrics, and a reason recorded for every rejection.

### Stage 3: conversion and parity (~1–2 weeks)
- Convert each shortlisted model to the Apple runtimes that fit it (R2 Core ML always; R3 Core AI for T-high; R1 ORT 1.30 as the no-conversion control). Precision order: fp16 first, then int8 / palettised.
- Check parity (§5.8). **Re-score quality on the converted model**, since fp16 can move metrics.
- Dump compute placement. Where ops fall to CPU, try the ANE design rules (§6 S9). Stop after two attempts per model.

**Exit:** for each shortlisted model, the best converted artefact per tier, with parity and placement recorded.

### Stage 4: on-device micro-benchmarks (~1–2 weeks)
- Every converted artefact × every device.
- Record:
  - cold, cached and resident load;
  - latency p50 and p90;
  - peak memory;
  - energy;
  - 15-minute sustained loop.
- Protocol in §9.
- Drop artefacts that exceed the memory budget or are dominated on quality, latency and memory by another artefact of the same slot.

**Exit:** a per-slot, per-tier Pareto set of at most 2.

### Stage 5: pipeline integration and full jobs (~2 weeks)
- Wire the Pareto survivors into the app behind a **DEBUG-only** `UserDefaults` switch:
  - one `switch` at each model call site;
  - no protocol until one of them ships alongside another.
- **Audio stacks:** S1 × S2 × S3, pruned to ≤ 8 combinations.
- **Video stacks:** S4 × (S5+S6+S7) × S8, pruned to ≤ 8 combinations.
- Run every combination on the pipeline clip set, 5 repetitions:
  - cold and warm;
  - T-low, T-mid, T-high, Mac.
- Include the combined-job shape, to measure ANE/GPU contention between the analyse and separate branches.
- Dump EDLs and score them against the video corpus with the combined product metrics (§5.5, §5.6). The EDL is the ground truth for coverage, so no render is needed for scoring.
- Run one 90-minute soak on the 12 Pro with the leading stack, checking thermals, memory and checkpoint resume.
- Also measure the video-path levers the separator/detector choice depends on (H9 in §11).

**Exit:** a full-job table per tier; the stacks that pass every gate.

### Stage 6: human review and decision (~1 week)
- **Listening test** (§5.3) on the surviving audio stacks.
- **Visual review:** 5 reviewers from the target audience watch 20 filtered clips per surviving video stack, in randomised order. They flag:
  - every leaked face;
  - every leaked scene;
  - every needless blur.
- Apply the decision method (§10).

**Exit:** `docs/benchmarks/decision.md` naming one stack per tier, with the evidence rows and the rejected alternatives.

### Stage 7: adoption
- Update `Models.swift` contracts and `ModelContractTests`.
- Update `scripts/fetch-models.sh` / `prepare-apple-models.py`, or add the new conversion scripts.
- Bump the checkpoint generation where output semantics change.
- Re-run the kill/resume and integrity suites.
- Then run the licensing review deferred from this round (§12).

---

## 8. Harness

### 8.1 Offline (Mac), `scripts/modelbench/`

A single uv-managed Python package. **One adapter function per candidate, not a class hierarchy.**

```
scripts/modelbench/
  manifest.json          # corpus files, sha256, labels, split (committed)
  corpus.py              # load manifest, mixes, labels
  adapters/audio.py      # def run_htdemucs(wav) -> stems ; def run_mdx_kim2(wav) -> stems ; ...
  adapters/vision.py     # def run_gantman(frames) -> probs ; def run_scrfd10g(frames) -> boxes ; ...
  edl_sim.py             # the Edl.swift hysteresis, bridging, min-duration and interpolation, 1:1 with its constants
  score_audio.py         # SI-SDR, SIR, ESTOI, WER, DNSMOS, gate miss/pass, harm rate
  score_vision.py        # PR-AUC, event metrics, WIDER/COCO AP, TrackEval, coverage, Boundary IoU
  report.py              # results/*.jsonl -> markdown tables
```

**Libraries:**
- inference and conversion: `torch`, `onnxruntime`, `coremltools` 9, `coreai-torch`;
- audio scoring: `fast_bss_eval`/`museval`, `pystoi`, `speechmos`, `jiwer` + `whisper`;
- event and tracking scoring: `sed_eval`, `sed_scores_eval`, `TrackEval`;
- detection and masks: `pycocotools`, `boundary-iou-api`, `scikit-learn`;
- trackers: `boxmot`;
- Mac-only models: `mlx-vlm`.

**Parity check for the simulated EDL:** `edl_sim.py` must reproduce the app's EDL for the current models on `test-video.mp4`. Otherwise the Python event metrics do not describe the app.

Converted Core ML models are run from Python with coremltools `predict` for the parity checks in Stage 3. Speed is **never** taken from Python.

### 8.2 On-device, extend `naqiTests/BenchTests.swift`

- Add a `ModelBench` suite: one parameterised Swift Testing `@Test` over a candidate list selected by an environment variable. Each argument is a small closure adapter, because Vision, SoundAnalysis and `AUSoundIsolation` are not model files: `load() / infer(fixedInput) / unload()`.
- Candidate artefacts are **not bundled**. Stage them into the app container per run with `xcrun devicectl device copy to …`, or a bench-only resource folder excluded from the shipping target.
- **Each candidate runs in its own `xcodebuild test` invocation**, so it gets a fresh process and a clean `ledger_phys_footprint_peak`.
- Reuse `Stage` for signposts and `MemoryFootprint` for sampled memory. Add one function that reads `ledger_phys_footprint_peak` from `TASK_VM_INFO`; the field exists in the iOS 27 SDK's `mach/task_info.h`.
- Write one JSON line per run (Appendix A) to the test's attachment and to `/tmp`, collected with `xcrun xcresulttool get test-results metrics`.

**Commands:**

```sh
# micro-bench, one candidate per process
xcodebuild test -project naqi.xcodeproj -scheme naqi -configuration Release \
  -destination 'platform=iOS,id=<UDID>' ENABLE_TESTABILITY=YES \
  NAQI_BENCH_CANDIDATE=S4.N1.s.coreml.fp16 \
  '-only-testing:naqiTests/ModelBench' -resultBundlePath /tmp/mb-<run-id>.xcresult

# placement / ANE activity trace
xcrun xctrace record --template 'Core AI' --device <UDID> --launch -- <app>   # iOS 27
xcrun xctrace record --template 'Core ML' --device <UDID> --launch -- <app>

# Mac power, including the ANE rail
sudo powermetrics --samplers cpu_power,gpu_power,ane_power -i 200
```

---

## 9. Measurement protocol

1. **Build:** Release, no debugger attached, same OS build on every device in a tier. Wait 5 minutes after a reboot (system photo/ML analysis can compete for the ANE).
2. **Device state:**
   - airplane mode, Low Power Mode off, screen brightness fixed;
   - micro-benchmarks unplugged at ≥ 50% battery;
   - long soaks both unplugged and plugged in, reported separately, because users often charge during long jobs.
3. **Thermal:** start each configuration only at `.nominal`; 3–5 minutes of cool-down between configurations; log `thermalState` every minute.
4. **Micro-benchmark:** 10 warm-ups discarded, ≥ 50 timed inferences, 3 process launches. Report:
   - median, p10/p90, IQR;
   - bootstrap 95% CI of the median.

   The minimum is kept only as a diagnostic.
5. **Order:** ABBA or randomised order between candidates, so heat and background drift don't favour whichever runs first.
6. **Load states:** cold, cached and resident, measured and reported separately.
   - Cold reset: clear the ORT cache directory, `AIModelCache.deleteEntries` for Core AI, a new model path for Core ML.
7. **Full jobs:** 5 repetitions, median and spread. Include one cold-cache first job per device.
8. **Sanity check:** confirm the intended tests ran. A passing run with zero tests (a missing fixture that turns an opt-in test off) is not a result.

---

## 10. Decision method

1. **Gates first:** a candidate or stack that fails any §5 gate is out, whatever its speed.
2. **Pareto, per tier:** among passing stacks, keep those not dominated on:
   - product quality;
   - full-job wall time;
   - peak memory;
   - bundle + cache MB.
3. **Tie-break with weights** (commit before Stage 2):
   - quality 45;
   - full-job speed 25;
   - peak memory 10;
   - size 10;
   - cold start 5;
   - energy 5.

   Each dimension is normalised to the baseline.
4. **Per-tier stacks are allowed:** e.g. a RoFormer on T-high and MDX-Net on T-low. Cap: **at most 2 variants per slot** across all tiers, because every variant is a contract, a test and a conversion script to maintain.
5. **Record rejects:** candidate, device, input, settings, result and reason, so no one repeats them without new evidence.

---

## 11. Hypotheses to confirm or kill early

| # | Hypothesis | Cheapest test |
|---|---|---|
| H1 | `AUSoundIsolation` HighQualityVoice removes music at least as well as htdemucs, ≥ 2× faster, at 0 MB, but drops ambience and keeps singing | E1 vs M0 on the synthetic set and 20 real clips; ~1 day |
| H2 | SoundAnalysis replaces YAMNet with equal miss rate and lower cost | G1 vs G0 on OpenBMAT + our real set |
| H3 | ORT 1.30 cuts htdemucs partitions enough to matter | Stage 0 step 5 |
| H4 | An all-conv fp16 separator on the ANE (MDX-Net) beats htdemucs fp32 on GPU in ×realtime on A14–A16 | M2 vs M0, Stage 4 |
| H5 | One shared image embedding (FeaturePrint / MobileCLIP / SigLIP2) can serve both the S4 probes and S7 gender, replacing two models with one | N12/N14–N16 + A5 |
| H6 | Vision revision 4 closes the small-face gap to YuNet/SCRFD at 640; otherwise tiling is required | F1 vs F2/F3 by size bin |
| H7 | MiVOLO's body input removes the "small face → no vote" gap | A1 vs A0 on the <48 px subset |
| H8 | Whole-body masks at 5 fps with propagation fit the T-low budget | P1/P2/P5 on the 12 Pro |
| H9 | Decoding analysis frames at reduced resolution (`kCVPixelBufferWidthKey/HeightKey` on `AVAssetReaderTrackOutput`, or `kVTDecompressionPropertyKey_ReducedResolutionDecode`) cuts analyse wall time without hurting S5 recall | Stage 5, on `tv1` |
| H10 | Core AI's ahead-of-time compile removes the tens-of-seconds cold start on A17 Pro+ | R3 vs R2 cold load on the 17 Pro |
| H11 | Apple's Foundation Models refusal on a frame is itself a usable immodesty signal | L1 refusal rate on S0 vs S2–S4 frames |

H1, H2 and H3 cost days, not weeks, and each could change what Stage 2 needs to cover. Run them first, inside Stage 0.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| Devices stay disconnected | Stage 0 is blocked, not skipped. Nothing about speed is decided on the simulator. |
| Labelling takes longer than planned | Teacher pre-labels + human correction; label the test split fully and the calibration split sparsely |
| Offline quality differs after fp16 conversion | Stage 3 re-scores the converted model |
| Research figures are self-reported on private sets | They only decide what enters Stage 2; nothing is chosen on a paper number |
| iOS 27-only winners help few users | Every iOS 27-only pick needs a measured iOS 18–26 fallback in the same tier |
| Core AI 27.0 defects (fp16 outputs, `argmax`, stale caches) | Retest on 27.1; keep the Core ML artefact as the fallback |
| Singing removal harms recitation | Hard harm gate (§5.4); if nothing passes, the option does not ship |
| Combined-job contention erases per-slot gains | Stage 5 measures full stacks, not slots |
| Licensing deferred, then blocks the winner | Every result row records the license. The decision table lists the best stack **and** the best permissively licensed stack, so the choice survives the later review. |

---

## Appendix A: result record (one JSON line per run)

```json
{
  "run_id": "2026-10-07T10:21:03Z-17pro-S2.M2",
  "stage": 4, "slot": "S2", "candidate": "M2.kim_vocal_2",
  "artefact": {"file": "kim2_fp16.mlpackage", "sha256": "…", "runtime": "coreml",
               "precision": "fp16", "compute_units": "all", "license": "MIT"},
  "device": {"model": "iPhone18,1", "chip": "A19 Pro", "ram_gb": 12, "os": "27.0.1 (24A…)",
             "power": "battery", "battery_pct": 81, "low_power": false},
  "build": {"commit": "…", "config": "Release"},
  "input": {"corpus": "naqi-audio-eval", "item": "mix_0421", "sha256": "…", "seconds": 30.0},
  "load_ms": {"cold": 5120, "cached": 410, "resident": 0},
  "latency_ms": {"p10": 38.1, "p50": 40.2, "p90": 44.9, "n": 150, "launches": 3},
  "throughput_x_realtime": 11.8,
  "placement": {"ane_ops_pct": 97.5, "gpu_ops_pct": 0, "cpu_ops_pct": 2.5, "transitions": 2},
  "memory_mb": {"peak_ledger": 612, "sampled_max": 598, "settled": 71},
  "thermal": {"start": "nominal", "end": "fair", "minutes": 15, "last_over_first": 0.91},
  "energy": {"source": "power_profiler", "mJ_per_media_s": null},
  "quality": {"si_sdr": 11.4, "sir": 17.2, "d_wer": 1.1},
  "notes": ""
}
```

## Appendix B: key sources

- **Separation:**
  - [ZFTurbo pretrained models](https://github.com/ZFTurbo/Music-Source-Separation-Training/blob/main/docs/pretrained_models.md)
  - [mvsep Multisong leaderboard](https://mvsep.com/quality_checker/multisong_leaderboard?sort=vocals)
  - [DnR v3](https://github.com/kwatcharasupat/divide-and-remaster-v3)
  - ["Facing the Music" (dialogue / singing / music / effects)](https://arxiv.org/abs/2408.03588)
  - [community checkpoint mirror](https://huggingface.co/noblebarkrr/mvsepless_resources)
  - [htdemucs Core ML gotchas](https://github.com/tsyrenov1987/demucs-coreml-ios/blob/main/docs/GOTCHAS.md)
- **Audio tagging:**
  - [CED](https://github.com/RicherMans/CED)
  - [EfficientAT](https://github.com/fschmid56/EfficientAT)
  - [OpenBMAT](https://zenodo.org/records/3381249)
  - [sed_eval](https://github.com/TUT-ARG/sed_eval)
  - [SoundAnalysis identifiers](https://developer.apple.com/documentation/soundanalysis/snclassifieridentifier)
- **Scene gate:**
  - [OwenElliott image-safety-classifier](https://huggingface.co/OwenElliott/image-safety-classifier-s)
  - [Marqo nsfw-384](https://huggingface.co/Marqo/nsfw-image-detection-384)
  - [Freepik detector](https://huggingface.co/Freepik/nsfw_image_detector)
  - [NudeNet v3](https://github.com/notAI-tech/NudeNet)
  - [SenBen](https://arxiv.org/abs/2604.08819)
  - [LSPD](https://sites.google.com/uit.edu.vn/LSPD)
  - [UnsafeBench](https://arxiv.org/abs/2405.03486)
  - [SensitiveContentAnalysis](https://developer.apple.com/documentation/sensitivecontentanalysis)
- **Embeddings and VLMs:**
  - [ml-mobileclip](https://github.com/apple/ml-mobileclip)
  - [SigLIP2 Core ML](https://huggingface.co/FluidInference/siglip2-base-patch16-256-coreml)
  - [FastVLM](https://github.com/apple/ml-fastvlm)
  - [Foundation Models image input](https://developer.apple.com/documentation/foundationmodels/analyzing-images-with-multimodal-prompting)
  - [mlx-swift-lm](https://github.com/ml-explore/mlx-swift-lm)
- **Faces and persons:**
  - [YuNet](https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet)
  - [SCRFD / camstack Core ML](https://huggingface.co/camstack/camstack-models)
  - [MiVOLO](https://github.com/WildChlamydia/MiVOLO)
  - [FairFace](https://github.com/dchen236/FairFace)
  - [EdgeTAM](https://github.com/facebookresearch/EdgeTAM)
  - [boxmot](https://github.com/mikel-brostrom/boxmot)
  - [TrackEval](https://github.com/JonathonLuiten/TrackEval)
  - [MIAP](https://arxiv.org/abs/2105.02317)
- **Runtimes and profiling:**
  - [Core AI](https://developer.apple.com/documentation/coreai)
  - [coreai-models](https://github.com/apple/coreai-models)
  - [coremltools optimisation performance](https://apple.github.io/coremltools/docs-guides/source/opt-quantization-perf.html)
  - [ORT CoreML EP](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html)
  - [ml-ane-transformers](https://github.com/apple/ml-ane-transformers)
  - [continued-processing inference entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.background-tasks.continued-processing.inference)
