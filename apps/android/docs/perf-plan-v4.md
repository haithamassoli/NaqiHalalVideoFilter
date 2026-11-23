# Perf plan v4 — three job shapes, three different walls

**Supersedes `perf-plan-v3.md` §3's framing and §0's implied ranking. Everything v3 *measured* stands.**

v3 asked "where is the time in a 643 s censor-only job" and answered it correctly. This plan asks the
question that actually decides what to build: **which stage is on the wall for the job the user is
running.** The answer is different for all three shapes the app ships, and two of the three are not the
one v3 optimized.

Written after five parallel research dives, each adversarially verified, plus host measurements taken
directly against the shipped ONNX graph (marked **[host]** below — arm64, ORT 1.27.0 CPU EP, same
runtime version as the APK; direction transfers to Snapdragon, magnitude does not).

---

## 0. The short answer to "is this the best thing to do?"

No. Three things were wrong with how the work was being aimed:

1. **On a music-removal job, every video optimization in v3 is worth exactly zero.** `branches()`
   runs audio and video concurrently, so wall = `max(audio, video)`. Measured: `separate` 449 376 ms
   against a whole video branch of 204 752 ms. Audio is **2.19× the entire video branch**, with 244 s
   of slack underneath it. v3 §3 states this backwards — it warns that *audio* savings buy zero wall.
   They pay 1:1; the video savings are the ones buying zero.
2. **On a feature film, analyze is 88 % of the video branch, not 56 %.** `long-film-plan.md:27-33`
   measured the 155-min film at analyze 70.5 min / render 9.7 min. The 56/44 split every perf
   discussion has run on is a property of one 10.7-minute 1080p AV1 clip.
3. **"Split it into sections and run them in parallel" is the right instinct at the wrong
   granularity.** Section-level fan-out is hard-gated at 30 minutes (`Checkpoint.kt:68` returns
   `emptyList()` below `Eta.CONFIRM_THRESHOLD_MS`), so it can never touch a short video — and it
   carries seams, a per-segment gender voter, and a lossier resume. **Frame-level** fan-out inside the
   existing single decode loop gets the same cores with none of that, and works on every clip length.

And on "you can change models": measured, not guessed. **INT8 htdemucs is 2× slower, not faster**
(§6.1). **Longer segments with free RAM are 20.6 % *more* expensive per second of audio**, because the
graph has a real O(T²) transformer bottleneck (§6.2). The one model swap with a credible case is
SCNet-small, and it is an XL training project (§6.4).

---

## 1. The three shapes, and what is on the wall for each

`FilterWorker.branches()` makes combined-job wall = `max(audio branch, video branch)`.

| shape | on the wall | worth **zero** |
|---|---|---|
| **A — censor-only, < 30 min** | analyze 114 648 + render 89 411, **serial** (56 % / 44 %) | every audio item |
| **B — censor-only, ≥ 30 min film** | analyze ~4 230 s + render ~582 s, serial, **segmented** (88 % / 12 %) | every audio item; render items aim at 12 % |
| **C — music removal, any length** | **the audio branch alone** — 449 s vs 205 s video (short); ~108 min vs ~70-80 min video (film) | **every video item in this plan** |

Shape C's video slack is 244 s on the short clip and ~28-38 min on a film. Nothing in §§3-4 reaches
the wall on shape C, and nothing in the corpus gets the audio branch down far enough to change that.

**Shape B's ratio is a projection from a 2026-07-27 run** that predates the INT8 gate, Phase 1 track
eviction and S1 concurrency, and that was stopped by hand after render. Correcting for those still
leaves film analyze at ~40-43 ms/sampled-frame against the short clip's 17.8 — **2.3× worse per frame
on a smaller (1728×720) source, and nobody knows why.** That unexplained gap is worth more than any
single item below, which is why M3 outranks everything.

---

## 2. Tier 0 — four measurements. Nothing below ships first.

| # | what | where | effort | why it gates |
|---|---|---|---|---|
| **M1** | **Split the `nv21=` timer** — `getOutputImage` / `packNv21` / `fromByteBuffer` / `close()` separately (v3 §1, unchanged) | `FrameSampler.kt:227-243` | ~6 lines | Decides A2, B2 and *all* producer parallelism, with a 5× swing. It is also the **post-mortem of `perf-plan.md:355-413`'s null result**: if `getOutputImage` is a serial gralloc map that dominates, then "convert 24-32 % faster, wall 0 %" is finally explained and every gather-parallelisation idea dies for the second time. Three of five dives built arithmetic on this misattribution. |
| **M2** | **Split the 110 699 ms audio residual** — `gateNs`/`gatherNs`/`flushNs`/`decodeNs`, **plus two v3 missed**: `yieldNs` around `AudioPipeline.thermalYield`'s `Thread.sleep`, and a session-create counter (`stats.stage("separate")` opens *before* `removeMusic` at `FilterWorker.kt:332`, so graph optimization over 1 531 nodes is billed to the residual and appears in no counter) | `AudioPipeline.kt`, `DemucsSeparator.kt:235-245`, `AudioDecoder.kt` | ~12 lines | Decides C2, C3, C4. First pass costs zero code: `grep -c 'yielding'` and `maxThermal` on logcat already captured. |
| **M3** | **One feature-length combined soak, run to completion** | none | 1 device session (~3 h) | **The highest-value run in this plan.** Shape B and shape C at film length are both projections from a partial, pre-INT8 run. It settles the audio:video ratio on the flagship job — *which decides whether §§3-4 matter at all* — plus the 2.3×/frame analyze gap, whether thermal fires across 108 min off charger, and whether `removeMusicResumable` behaves like `removeMusic`. |
| **M4** | **Where does the producer thread run?** One Perfetto CPU-track over analyze | probe only | trivial | `grep -rn "setThreadPriority\|THREAD_PRIORITY" app/src/main/java` returns **nothing**. The hottest thread in the app — 94.3 s of a 114.6 s wall — sits at default priority on `Dispatchers.Default` against 1×X3 + 2×A715 + 2×A710 + 3×A510, contending with ML Kit's executor and XNNPACK's threads. If it lands on an A510 for a meaningful fraction, the fix is ~2 lines. Unknown upside, near-zero cost to find out. |

Protocol is v3 §7 verbatim. One addition: **a concurrency A/B is exactly the comparison run-drift
destroys** (v3 §5 saw 31 % analyze drift across three runs in one session), so interleave
configurations inside one cooled session and read per-unit counters, never stage wall.

---

## 3. Shape A — censor-only, short clips

Baseline: analyze 114 648 (producer = `nv21` 64 818 + `gateFill` 29 493 + residual 20 332; consumer =
`detect` 10 209 + `gate` 29 358 = 39 567), render 89 411.

**Three corrections to v3's own arithmetic, all of which move the ranking:**

- **v3 §2.2's "a new wall around 69 s" is wrong.** It drops the 20 332 ms residual, which stays on the
  producer. Moving `gateFill` leaves the producer at **85 155 ms**, consumer at 69 060. The *saving*
  (~29.5 s) is right; the producer is still the wall.
- **That residual is the hardware decoder, not overhead.** 20 332 ms ÷ **19 271** decoded frames
  (643 s × 29.97 fps — the decoder decodes every frame, the sampler keeps every third) = **1.055
  ms/frame**, against v3 §5's directly measured `c2.qti.av1.decoder` at 1.123 ms/frame. **Analyze has
  a hard floor of ~21 s** that no CPU work touches, and the addressable pool is 94.3 s, not 114.6 s.
- **Consumer headroom is 11.68 ms/frame today** ((114 648 − 39 567) ÷ 6 430), falling to **2.50
  ms/frame** after A1. Both dive figures for this were wrong in opposite directions.

| # | item | files | gain | effort | quality | the ONE measurement |
|---|---|---|---|---|---|---|
| **A1** | **Move `gateFill` off the producer onto the idle consumer** (v3 §2.2) — **SHIPPED 2026-08-04, see §11.** Split at the gather/arithmetic seam, NOT at A4's pixel-source seam; gate tensor is bit-identical | `FrameSampler.kt` (`gatherGate`/`gateFromGathered`), `FilterWorker.kt` | measured **224 755 → 192 991 ms (−14.1 %)** with A4; ~−12 % re-measured with the faithful split. §3's projection of −14.4 % held | small | **none** — bit-identical tensor, pinned by a zero-delta equivalence test against `convertToTensor` | Done: `gateFirings`/`intervalCount` and censored-timeline recall vs the pre-diff build |
| **A2** | **Bulk-copy decoder rows to heap, gather from heap** (v3 §2.1) — **UNBLOCKED: M1 has run (§11) and `getOutputImage` is 0.4 %, not the dominator.** Now the top-ranked unshipped shape-A item | `FrameSampler.kt` (`packNv21`) | **−15 000 ms** of `pack`'s measured 77 435. The whole producer cost is the strided gather, at 34.9 µs/kB | medium | none | One A/B of per-row `Buffer.get(byteArray)` vs today's strided read. **Note: after A1 the consumer has < 20 s of headroom, so A2 likely makes the CONSUMER the wall — see §3's projection table** |
| **A3** | **Skip pixel work inside already-censored intervals** (v3 §2.5) | `FrameSampler.kt`, `FilterWorker.kt` | **8 000-13 400 ms** — discounted from v3's 13 400 because after A1 only the `nv21` share counts. Yield is coverage-dependent and was never histogrammed | medium | none — the proof holds (`Edl.regionsAt` empty under `fullFrameAt`; `CensorEffect.drawFrame` independently forces `regions = emptyList()`) | Log the covered-frame fraction on one pass **before** writing the skip; then censored-timeline **containment** (superset), not overlap % |
| **A4** | ~~Fill the gate tensor from the packed NV21~~ **DEAD — BUILT, MEASURED, REVERTED 2026-08-04. Do not retry; see §11.** It under-censored **34.5 s** of a 643 s clip at **91.24 %** censored-timeline recall against §3's own ≥ 99.20 % bar. "Nearest of nearest of 1920" lands on different source pixels and the chroma is subsampled at 640 — those pixels are gone, so it cannot be made faithful. A1 ships without it | — | **FAILED its needs-check** | — |
| **A5** | **Prefer `avc1` in the download format selector** (v3 §5.1) | `Downloader.kt:37-43,:153` | **−10 000 ms** of render. **Conditional:** the AV1↔H.264 comparison was bitrate-confounded (1.50 Mbps re-encode vs an unstated AV1 bitrate) | ~2 lines | none to pixels; costs the user download bytes | One matched-bitrate re-measure of the same content in both codecs |
| **A6** | **`texturePoolCapacity = 1 → 3`** | `CensorEffect.kt:86` | **NOT ESTIMABLE.** v3 §5.2 predicts zero on an ALU/bandwidth argument, but this is a *pipeline-depth* argument and untested: at capacity 1, `BaseGlShaderProgram` cannot begin frame N+1 until `FinalShaderProgramWrapper` releases N to the encoder surface. Per-frame, × 19 267 | one token | none | The cheapest unrun experiment on the 44 % stage, and it works on every clip and the unsegmented path. Interleaved A/B in one cooled session |
| **A7** | **`maxDim` 640 → 480** (v3 §2.6) | `FilterWorker.kt` sampler call | **−8 700 ms**. Ranked last on purpose | trivial | **regression risk** — the fail-open that killed the 5-fps experiment (`perf-plan.md:198-223`: a fully visible face at 13.56 s, found only by rendered-pixel diff) | Rendered-pixel diff, not counts |
| — | **`gateEvery` 2 → 4** (v3 §2.4) | — | **SUPERSEDED by A1.** After A1, halving gate frames removes 1 608 × (9.17 + 9.13) = 29 426 ms from the **consumer** and nothing from the 85 155 ms producer. **Zero wall, full product risk** | — | — | Only if a post-A1/A2/A3 re-measure shows the consumer is the wall — see A8 |

**A8 — if the consumer ever becomes the wall, HaramBlur supplies §2.4's missing correctness argument.**
Their shipping build uses `POSITIVE_THRESHOLD = 1 / NEGATIVE_THRESHOLD = 3` — one hit to censor, three
consecutive clears to stop. That asymmetry is what makes a coarser *positive* cadence safe. Widen
`NsfwGate.POST_MS` to cover the new 400 ms sampling interval so coverage is provably a superset of
today's, and the failure direction becomes *more* censoring. That converts §2.4 from "blocked on
correctness" to shippable — but only on a build where the consumer is measured as the wall.

**Shape A projection.** These draw on one pool and the wall changes sides as they land:

| after | producer | consumer | wall |
|---|---:|---:|---:|
| today | 114 648 | 39 567 | **114 648** |
| + A1 | 85 155 | 69 060 | **85 155** |
| + A2 | 70 155 | 69 060 | **70 155** |
| + A3 (mid) | 60 155 | 69 060 | **69 060** ← consumer takes over |
| + A8 | 60 155 | 39 634 | **60 155** |

**643 s censor-only: 204 752 → 158 000-168 000 ms realistic (1.22-1.30×), ~150 000 best case (1.37×),
i.e. 3.1× realtime → 4.3× realtime.** Not the 1.6-2.1× the dives claimed; every one of those was
priced against a misattributed timer or a code path this clip never takes.

---

## 4. Shape B — censor-only feature film. All conditional on M3.

Render is 12 % of this shape. **Every render item above, and every render proposal in the research
corpus, is aimed at the wrong stage on a film** — the same conclusion `long-film-plan.md:36-38`
reached in July.

| # | item | files | gain | effort | quality | measurement |
|---|---|---|---|---|---|---|
| **B1** | **A1-A4 apply unchanged**, and are worth ~2.3× more in absolute ms here (analyze 4 230 s vs 114.6 s) | as §3 | ~−1 100 s **if** the per-frame ratios transfer — and they demonstrably did not last time (the 2.3×/frame gap) | as §3 | as §3 | M3 |
| **B2** | **Frame-level producer fan-out** — keep ONE extractor and ONE codec, fan `packNv21` + `convertToTensor` across a small pool with N frames in flight | `FrameSampler.kt:113-273` | **NOT ESTIMABLE, conditional on M1.** Upper bound = `nv21`'s 64 818 ms minus the serial `getOutputImage` share | medium | none (identical pixel arithmetic) | **It must beat `perf-plan.md`'s null result, not just the thread constant** — M1 is that test. Real constraint: holding N `Image`s open against the codec's output-buffer count — bounded, measurable, never weighed |
| **B3** | **Section-level analyze fan-out** (the user's proposal, at the coarse grain) | `FilterWorker.kt:546-635`, `Infer.kt:36`, `FilterWorker.kt:923` | **Contested.** Capped by ~21.6 s of serial hardware decode that fan-out divides at most ~2×; blocked behind M1; and needs a **dispatcher redesign**, not support edits — `Tasks.await` at `:598` hard-blocks a `Dispatchers.Default` thread and the consumer runs on the caller's dispatcher, so each segment costs two pool slots with one blocking, against parallelism 8 | large | **needs-check, not none** — ML Kit publishes no behaviour for `PERFORMANCE_MODE_FAST` under CPU load, and fewer detections is the direction this app never takes | Censored-timeline recall ≥ 99.20 %, differences only in the censors-more direction. **EDL byte-diff is not a valid gate** (`Models.kt:62-67`: 4 786 vs 4 550 faces on two identical *single-threaded* runs) |
| **B4** | **Decode-side sample skipping** — `Mp4Extractor.FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES` on the media3 render path | render path only | plan-v2:513-519 sizes it at ~6 lines, 30-50 % droppable samples on downloaded films. Analyze uses raw `MediaExtractor` and has **no clean equivalent** (`BUFFER_FLAG_DECODE_ONLY` needs API 34 plus a dependency oracle the platform does not expose) | ~6 lines | none | Frames decoded vs frames read, one film |
| **B5** | **Parallel render segments** | `FilterWorker.kt:636-672` | **Gated, not ranked.** 76 % of render's 4.702 ms/frame is unattributed — GPU is at 22 %, decoder is 1.123 ms, nobody knows what owns the other 3.6 ms. Headroom in a non-limiting resource buys no ms, and this attacks 12 % of shape B | large | needs-check (retry over a partially written `.part`) | Attribute the 3.6 ms/frame first (`DebugTraceUtil.enableTracing` + atrace). Then `getMaxSupportedInstances` and `getAchievableFrameRatesFor(1920,1080)` on the **selected** H.264 encoder on **this** S23 — five lines, never asked for |

### Two real bugs B3 would trip, worth fixing on their own merits

Both are confirmed in code and both are **latent today** — they only bite if anything ever runs pass 1
concurrently. Fix them whether or not B3 ships.

- `FilterWorker.kt:923-941` — **one 110 KB gender-crop `FloatBuffer` shared for the entire pass**,
  whose KDoc says "safe to share because pass 1 is single-threaded … the segments run in sequence".
  Under concurrency this silently cross-contaminates gender votes, i.e. it is a **Women/Men quality
  bug**, not a crash. Worse, the comment at `:574-576` claims the opposite ("the voter is fresh
  too") — and *that contradiction is why the bug would survive review.*
- `Infer.kt:36` — a plain `HashMap` under `getOrPut`, with a class KDoc that says "one worker drives
  this at a time (thread-confined)". `OrtSession.run` is itself thread-safe; the session *cache* is
  not. Pre-warm before any fan-out, or use a `ConcurrentHashMap`.

---

## 5. Shape C — music removal. The only shape where audio is on the wall.

Baseline: `separate` 449 376 ms = ORT `session.run` 328 080 (73 %) + DSP 10 597 (2.4 %) + residual
110 699 (24.6 %). All of it pays 1:1 down to ~205 s (short) / ~70-80 min (film).

| # | item | files | gain | effort | quality | measurement |
|---|---|---|---|---|---|---|
| **C1** | **Two-tier music guard** — hard ±1 chunk, ±2 only for chunks scoring ≥ 0.02 (v3 §3.2) | `DemucsSeparator.kt` (`separateChunk`), `MusicGate.kt` | **−40 000 ms (8.9 %).** Already replicated off-device against the shipped gate on `tv1.webm`'s real audio: 141/276 skipped vs the device's 136/276, 271/276 chunk agreement, and the 20 extra dropped chunks score ≤ 0.0139 (below white noise). No lookahead change needed — `LOOKAHEAD = 2*STRIDE` already covers ±2. **Not one dive attacked this** | small | needs-check — it is the miss-rate dial | The **worst chunk it stops separating**, listened to. Not the chunk count |
| **C2** | **`SessionOptions.setOptimizedModelFilePath`** on the htdemucs session | `DemucsSeparator.kt:634-665` | **NOT ESTIMABLE until M2.** Graph optimization over 1 531 nodes is the pass that inserts the 201 `InsertedPrecisionFreeCast` nodes v3 §4 counted; it runs inside the measured stage and **compounds once per resume** on the film path. Serializing the optimized graph makes every later load skip it | trivial-small | none (identical graph) | M2's session-create counter, then one cooled music-only run |
| **C3** | **Non-blocking audio decoder drain** (v3 §3.1) — **demoted from v3's #3** | `AudioDecoder.kt:255-310` | **−10 000..14 000 ms, not 135 s.** v3's premise is superseded: `AudioDecoder.pump` consumes exactly one output buffer per iteration and has **no terminal-timeout call**, unlike `AacWriter.drainEncoder`, which returned on the first `TRY_AGAIN_LATER` after every input buffer. The ~115 dequeues/chunk are codec-latency waits, not 10 ms sleeps | ~5 lines | none | M2's `decodeNs`; byte-identical `.m4a` vs the serial build (the pipeline is deterministic — `DemucsSeparator.kt:506`) |
| **C4** | **`AacWriter.feedEncoder`'s surviving blocking `dequeueInputBuffer(TIMEOUT_US = 10_000)`** | `AacWriter.kt:122-123` | The 135-s-class sibling that **is** still live: ~26 calls/chunk × 276 ≈ **7 000 blocking waits** per 643 s job, on the separator's own thread, sitting inside the unsplit residual. Only `drainEncoder` was ever fixed | ~5 lines | none | M2 first. **Short clips only** — `removeMusicResumable` has no encoder in the chunk loop |
| **C5** | **`ExecutionMode.ORT_PARALLEL` + `setInterOpNumThreads`** | `DemucsSeparator.kt:634-665` | **NOT ESTIMABLE.** htdemucs is literally two parallel towers (time + frequency) side by side until the cross-domain transformer. Inter-op exploits that **inside one session at zero extra RSS** — which is the whole objection to the two-session proposal it replaces. The intra-op sweep was run; this knob never was | small | none | Host sweep first (`ORT_PARALLEL` × inter-op 2, intra-op 3), then one cooled device run reading `inferNs`. Byte-identical `.m4a` |
| **C6** | **Output-trimming re-export** — slice the graph's outputs to the kept stems | `scripts/htdemucs_export.py`, `Models.kt` | **NOT ESTIMABLE.** The graph computes and writes all four stems every chunk (14.7 MB spec + 3.7 MB time) and `DemucsSeparator` keeps only `OTHER=2` and `VOCALS=3`. A previous fix stopped the *driver* copying them; nothing stopped ORT computing them. ORT profile: 15.7 % elementwise + 5.4 % layout. Bounded by the final decoder layers, not the shared trunk | small | **none on the kept stems** — the only zero-quality-cost model lever on the board | Off-device: re-export, re-profile, compare host ms. Device run only after |
| **C7** | **Overlap `encodePcm` with separation (films only)** | `AudioPipeline.kt:415-441,:306-409` | **~300 s of pure serial tail on a 155-min film** → ~0 added wall. The one item from v3 §3's "Dropped" list that survives costing. The other two are retired: `Checkpoint.writeAudio` is a ~120-byte `writeText` + rename with **no fsync** — ~2 s over 3 975 chunks, negligible | medium | none | M3, then one film run reading `SOAK stage=separate` split at `separator.finish()` |
| **C8** | **`session.dynamic_block_base`** (v3 §3.4) | `DemucsSeparator.kt` | **−5 000 ms.** Real option (literal string in the shipped `libonnxruntime.so`, never set here), zero output risk. Host sweep is noisy | trivial | none | Device sweep after C3, or drop it |
| **C9** | **Resume re-decode (films)** | `AudioPipeline.kt:380-383`, `DemucsSeparator.kt:183-201` | **~300 s per kill.** The repo's own datum: re-decoding a 155-min soundtrack was "~5 min of a ~258 min job" (`AudioDecoder.kt:62`). It repeats on every resume, and `feed`/`flush` walk all 410 M samples through a per-sample `Long` modulo before the first new chunk runs | medium | none | M3, then one kill/resume at the 50 % mark |

**Shape C, 643 s clip:** audio 449 376 → **395 000-405 000 ms** with C1 + C3 + C4 (1.11-1.14×). Wall
tracks it 1:1 because audio stays far above the 204 752 ms video branch. Ceiling without a model swap
is ~380 s — the 328 s of ORT is untouchable by everything measured in §6.

**Shape C, 155-min film:** audio ~108 min against a video branch of ~70-80 min. C1 + C2 + C7 →
**~97-100 min.** Conditional on M3 three ways: it assumes the 49 % gate-skip rate holds on a feature
(music-gate yield is run-length-bound, not duty-cycle-bound), assumes no thermal yielding across
108 min off charger, and assumes `removeMusicResumable` behaves like `removeMusic`.

---

## 6. "You can change models" — measured, not guessed

### 6.1 INT8 htdemucs is 2× **slower**. The gate's 2.30× does not transfer. **[host]**

Quantized the shipped graph four ways and benchmarked each against the fp32 baseline (ORT demotes the
shipped fp16 weights to fp32 at load anyway — v3 §4 — so fp32 *is* the runtime behaviour):

| variant | speed | spec SNR | wave SNR | size |
|---|---:|---:|---:|---:|
| dynamic INT8, Conv + MatMul | **0.55×** | 2.8 dB | 23.6 dB | 61 MB |
| dynamic INT8, **Conv only** | **0.44×** | 2.2 dB | 23.6 dB | 146 MB |
| static QDQ, Conv + MatMul (noise calib) | 1.25× | 0.6 dB | 14.5 dB | 70 MB |
| dynamic INT8, **MatMul only** | **1.19×** | **56.7 dB** | **43.5 dB** | 88 MB |

(SNR columns from a music-like harmonic input; the noise-input run agrees on every ordering.)

**`ConvInteger` is the poison, and now there is a reason.** A FLOP census of the graph puts
**Conv/ConvTranspose at 47.6 %** of the 91.96 GFLOP per 2.6 s segment. Sending half the model to a
kernel with no fused requantization and no fast ARM path makes the model slower than fp32, and
destroys the spectral branch besides — consistently, on both input types.

**One survivor, and I disagree with the dives on it.** MatMul-only quantization is 1.19-1.24× at
43.5 dB wave / 56.7 dB spec. The corpus filed this as dead under plan-v2's "per-tensor scales are
hopeless across a 60-100 dB STFT" argument. The measurement says otherwise — but 43.5 dB is a real
step down from the 63.4/69.0 dB fp16 parity M2/M3 device-verified. **Verdict: parked, not dead.** It
is worth ~1.19× on 73 % of `separate` ≈ **−52 s of a 449 s stage on the one shape where audio is the
wall**. Gate it on a real-audio A/B against the shipped fp16 output, not on host SNR.

### 6.2 Free RAM does not reopen the `SEG` dial — it closes it. **[host]**

The graph has a genuine cross-domain transformer bottleneck: 10 `Softmax` at sequence lengths 896
(waveform tower) and 448 (spectral tower), both **linear in segment length**, so attention is
**O(T²)**. Census of the 91.96 GFLOP per 2.6 s segment:

```
Conv / ConvTranspose   43.75 GFLOP   47.6%
FFN MatMul             38.76 GFLOP   42.1%
Attention               9.45 GFLOP   10.3%   <- quadratic in SEG
```

| SEG | GFLOP per second of audio | vs today |
|---|---:|---:|
| 1.3 s | 33.55 | −5.1 % |
| **2.6 s (shipped)** | **35.37** | — |
| 3.9 s | 37.19 | +5.1 % |
| 5.2 s | 39.01 | +10.3 % |
| 7.8 s (checkpoint native) | 42.64 | **+20.6 %** |

Going back to the checkpoint's native 7.8 s segment costs **+20.6 % more compute per second of audio
and 3.24 GB of RAM.** This independently confirms the repo's own device table (7.8 s → 1.4-4.2×,
3.9 s → 1.37×, **2.6 s → 1.33× realtime** — the shipped value is the fastest). Shorter is only −5.1 %
before per-chunk fixed costs eat it. **The dial is flat. Leave it, and stop treating it as a
RAM-limited compromise — it is the optimum.**

### 6.3 Concurrent sessions ≈ wider threading, and 8 threads is worse. **[host]**

8 chunks through the shipped graph, varying session count × intra-op threads:

```
1 session × 8 threads   699.1 ms/chunk   3.72× realtime
1 session × 4 threads   405.3 ms/chunk   6.41× realtime
4 sessions × 1 thread   398.6 ms/chunk   6.52× realtime
2 sessions × 4 threads  533.9 ms/chunk   4.87× realtime
```

Chunk-level parallelism buys **1.5 %** over the current arrangement, and the 8-thread result
independently reproduces the device's "8 XNNPACK threads is 4.8 % worse" on entirely different
hardware. That kills two-concurrent-sessions (which also doubles RSS toward the lmkd kill the arena is
already disabled for, and is *slower* than serial on the 49 % of chunk pairs containing a skip), and
it is the reason C5's inter-op knob — same architectural parallelism, zero extra RSS — is the version
worth trying.

### 6.4 Replacement separators

| candidate | verdict |
|---|---|
| **SCNet-small** | **The only live one.** plan-v2 §B1's own #1 candidate: 10.08 M params, MIT code, published CPU **RTF 0.669 vs HTDemucs 1.38 — 2.06× faster at +1.5 dB SDR.** No dive costed it. XL effort (training run on DnR v3, ONNX export of a dual-path bi-GRU) and the weights licence is unverified — but it is the only remaining route into the 328 s of ORT, and **the only thing that could push shape C's audio branch below its video branch**, which would re-rank this entire document. |
| **UVR-MDX-NET-Voc_FT** | Dead. **Measured 375.1 ms per second of audio vs htdemucs' 152.3 — 2.46× slower** on identical host/runtime/options. |
| **`hdemucs_mmi`** | Dead. The transformer it drops is **33.6 % of runtime** by the repo's own ORT profile, not the 54 % of MACs a FLOP count implies — and a MAC count prices at zero the 31.6 % that is elementwise/cast/layout/InstanceNorm. Ceiling 1.51× for a published **−0.45 dB SDR**, and its BiLSTM replacement is a serial recurrence the CPU EP cannot spread over threads. |
| **Open-Unmix / BandIt / Banquet / Spleeter** | Dead on licence (CC BY-NC-SA weights) or quality, as plan-v2 already found. |

### 6.5 Vision models

- **MobileCLIP2 / CLIP zero-shot gender: dead.** The SwiftFormer-XS precedent settles the class — a
  published 0.7 ms ANE latency ran **22 % slower** than 2020 MobileNetV2 on ARM CPU, because only 84
  of 362 nodes were XNNPACK-eligible. CLIP additionally carries documented demographic bias, which on
  a censoring product means *unevenly distributed under-censoring*.
- **YuNet as a union second detector: parked, and plan-v2 needs a correction.** plan-v2:149-151's
  "InsightFace contamination" objection is **wrong** — opencv_zoo ships MIT, trained on WIDER FACE via
  libfacedetection.train. But plan-v2 rejected the bake-off on a second, independent ground
  (licensing/telemetry/dependency surface, "not a performance item"), and `qualityImpact` is
  **needs-check, not none**: a second, tighter box distribution feeding `enableTracking()` can
  re-associate a track and flip a per-track gender vote toward *less* censoring.
- **`setMinFaceSize`: parked, and it is a quality item, not a perf item.** `FaceTracker.kt:203-207`
  sets only `PERFORMANCE_MODE_FAST` + `enableTracking()`, so `minFaceSize` is the 0.1 default — a
  64 px head at `maxDim 640`, against Google's "at least 100×100 px" guidance. It spends consumer
  budget, which is 11.68 ms/frame today and 2.50 ms/frame after A1 — **so it is nearly free before A1
  and nearly unaffordable after.** Sequencing matters. Gate on a censored-timeline superset plus a
  rendered-pixel diff of the frames it adds.

---

## 7. Mutually exclusive proposals — the calls

| pair | winner | why |
|---|---|---|
| A1 (move `gateFill`) vs `gateEvery` 2→4 | **A1** | Same pool. After A1, `gateEvery` buys **zero wall** and still carries the full "can miss a < 400 ms NSFW event" product risk. A1 has no such risk. |
| **Frame-level fan-out (B2) vs section-level fan-out (B3)** | **B2** | Works on every clip length, including short clips where `Checkpoint.kt:68`'s 30-min gate makes B3 structurally unreachable. No seams, no per-segment gender voter, no lossier resume, no dispatcher redesign, no hysteresis surface. **B3's own central argument — that frames are independent — is an argument for B2.** |
| B2/B3 vs a GPU producer (`ByteBufferGlEffect`) | **B2/B3, decisively** | The GPU route is producer-only, so analyze floors at the consumer's 39.6 s and lands ~40 s — the same place, for 400-700 lines. Worse, it delivers RGBA8888 while `InputImage.fromByteBuffer` takes NV21/YV12 only, so ML Kit moves to the `fromBitmap` path the repo already measured at **8.6 ms vs 1.59 ms — +45 s onto the consumer**, making the net worse. And `FrameSampler` is a raw `MediaCodec` loop, while `ByteBufferGlEffect` exists only inside media3's Transformer graph. |
| Parallel render (B5) vs an analyze→render one-segment lag | **B5 by default — the lag is dead** | `Edl.mergeRanges` bridges 400 ms **transitively with no bound** before the post-merge `MIN_FULL_MS = 500` filter, so a lagged schedule cannot reproduce the global EDL in whole-frame mode — the feature just shipped. v3 §5's refutation stands and is now stronger than when written. |
| Two htdemucs sessions vs `ORT_PARALLEL` inter-op (C5) | **C5** | §6.3: two sessions add no cores, double RSS, and are slower than serial on the 49 % of chunk pairs containing a skip. |
| Raising `SEG` for speed vs for quality | **neither** | §6.2. Plus a change means re-export + sha256 + `smokeShapes` + a `check(STRIDE < SEG && SEG <= 2*STRIDE)` that fails at 7.8 s + invalidation of every saved `audio.json`. |

---

## 8. End-to-end projections

**643 s censor-only (shape A)** — 204 752 ms today:
- Safe (M1 + A1 + A4): **~175 000 ms, 1.17×**
- Realistic (+ A2 + A3, M1's hypothesis holding): **158 000-168 000 ms, 1.22-1.30×**
- Best case (+ A5 + A8): **~150 000 ms, 1.37×** → 3.1× realtime becomes 4.3× realtime
- Hard floor: analyze cannot go below ~21 s (decoder) + ~10 s (detect); render is 89 s of largely
  unattributed cost. **Sub-120 s requires attributing render's 3.6 ms/frame, which nobody has done.**

**155-min film** — two answers, depending on whether music removal is on:
- **Censor-only:** ~70 min video branch post-INT8. Applying A1-A4's ratios → **~52-58 min.** Every
  digit conditional on M3, because the film's unexplained 2.3×-per-frame analyze gap could swallow
  the whole gain.
- **With music removal:** wall = audio ≈ 108 min → **~97-100 min** with C1 + C2 + C7. The ~70-80 min
  video branch sits entirely inside that and contributes nothing. **If M3 shows the video branch has
  grown past audio — plausible if the 2.3× gap is real and the gate-skip rate is lower on a feature —
  the ranking inverts and §§3-4 become the plan. That is the single most consequential unmeasured
  fact in this document.**

**What does not add.** A1-A4 and B2/B3 all draw on the same `(10.08 − getOutputImage) × 6 430`
producer budget. A1 and `gateEvery` draw on the same gate-work pool. C1's skipped chunks remove ORT
time that C5/C6 would otherwise speed up. Summing any column produces a number that does not exist.
Re-measure after every item.

---

## 9. Not doing — with the reason

Everything on v3 §6 and plan-v2's rejected lists still stands. New this round:

| item | why dead |
|---|---|
| "packNv21 is latency-bound at 29.2 ns/get, therefore scales with threads" | Built entirely on the `nv21=` misattribution M1 exists to fix. `t0` is taken *before* `getOutputImage`; an unknown share is a gralloc map + `InputImage` wrap + unmap, none of it a byte gather. Every downstream figure inherits the error. |
| "Analyze is within 26 % of a 90.6 s hardware-decode floor" | 212.7 fps is the *end-to-end render* rate; the decoder is 1.123 ms of that 4.702 ms/frame. The real floor is ~21.6 s, and v3's own line 35 already printed it. |
| Per-worker ORT gate sessions / `XNNPACK_THREADS` retune | 100 % consumer-side (zero wall), and it extrapolates linearly from a table where scaling *collapses* (1→20.1, 2→47.8, 4→42.3, 8→19.5 inf/s). Already on plan-v2's rejected list, re-affirmed at v3:359-360. |
| "382 ms per-export fixed cost" and everything built on it | Two points define a line; both solving to the same intercept is algebra, not corroboration. Already measured: `Checkpoint.kt:26-28`, ~0.5-0.7 s on the S23 spike, ~19 s across 31 film segments. |
| "2.26× encoder ceiling" / the CDD "independent cross-check" | 441 Mpix/s is the whole serialized pipeline's output rate, not encoder load; 995 Mpix/s is a marketing 8K30 **capture** figure for HEVC/AV1, not the H.264 encoder this job configures. The "cross-check" was the dive's own (res × fps × count) sum, so its agreement measures nothing. |
| Analyze→render one-segment lag | `Edl.mergeRanges` BRIDGE_MS=400 chains transitively before the post-merge MIN_FULL_MS=500 filter, so the lagged schedule cannot reproduce the global EDL in whole-frame mode. Also puts two full-rate decode streams on the one hardware block v3 §5 named as render's ceiling. |
| Dynamic / full-static INT8 htdemucs | §6.1: **measured 0.55× and 0.44×**, spectral branch destroyed. |
| Section-level *audio* parallelism | Adds no cores (§6.3), and has **no overlap-add crossfade across a section boundary at all** — N hard seams per film in the vocal stem. Byte-identity, its own stated gate, is impossible by construction. |
| HaramBlur's pixel-diff cache, producer-side "fail-safe" variant | Skipping `gateFill` skips **the gate that produces the coverage the skip relies on**. Coverage cannot extend past the last firing + `POST_MS`, so continuous NSFW over a near-static shot — exactly what a difference metric selects for — drops out at the trailing edge. **Censors less.** v3 §6's consumer-side rejection also stands. |
| HaramBlur's `strictness × 0.75` for video | The opposite of this product's promise. Never copy. |
| `2 ms/MB` vs `20 ms/MB` cached/uncached anchors | A linux-kernel mailing-list post about unrelated hardware; 2 ms/MB = 500 MB/s is 20-40× pessimistic for a Cortex-X3. The *measurement* (time a bulk row read on device) survives; the pre-computed 44 s win and 2× regression do not. |
| `genderage.onnx` licence blocker | `Models.kt:128` records the waiver explicitly. The **NOTICE omission is real** and is a one-line doc fix, not a release blocker. |

**Parked, not dead:** MatMul-only INT8 htdemucs (§6.1), SCNet-small (§6.4), YuNet union detector and
`setMinFaceSize` (§6.5), and `ImageReader` + `USAGE_CPU_READ_OFTEN` behind M1 — though temper that
last one: `FrameSampler.kt:180-186` already records that `COLOR_FormatYUV420Flexible` makes the
converter **alias** the mapped gralloc planes with no copy, and forcing a linear CPU-readable
`YUV_420_888` out of a Qualcomm decoder can add a UBWC detile rather than remove a copy.

---

## 10. Documentation debt — zero device runs, fix while in the file

- `Models.kt:88-96` — `HTDEMUCS` KDoc still claims a 3.9 s / `[1,2,171990]` export, contradicted three
  lines later by its own `smokeShapes`. v3 §3 ordered this fix; still unfixed.
- `FilterWorker.kt:574-576` vs `:916-918` — "the voter is fresh too" contradicts "one buffer for the
  whole pass". **This contradiction is why the §4 concurrency bug would survive review.**
- `NOTICE` — no InsightFace / `genderage` entry.
- `perf-plan.md:485` — claims `maxDim` shrinks the gate's input. It does not: `convertToTensor` builds
  `gx`/`gy` over the crop rect, not `dispW`/`dispH` (`FrameSampler.kt:346-353`).
- `plan-v2:149-151` — the YuNet licence claim is wrong (opencv_zoo is MIT).
- `perf-plan-v3.md:198-200` — "audio ms saved buy zero wall until the audio branch drops below the
  video branch" is backwards; see §0 and §1.

---

## 11. Measured — S23, 2026-08-04. Tier 0 is done; four items are settled and two are dead.

All numbers below are one cooled S23 session (start ≤ 33.0 °C, `maxThermal=0` throughout) over
`qa-assets/tv1.webm` — the same 643 s clip §§3-5's baselines came from. Render reproduced at
**89 411 ms, to the millisecond**, which is the control: it confirms the clip, the build and the harness
match the baseline runs, and re-confirms `render` is codec-paced. Analyze ran ~17 % above §3's baseline
uniformly across every CPU counter (the drift v3 §5 documents), so **read the ratios, not the absolutes.**

### M1 — the `nv21=` split. The gralloc-map hypothesis is dead; `packNv21` IS the cost.

```
sample: frames=6430 getImg=308ms pack=77435ms mlkit=217ms close=9ms
```

| | ms | share |
|---|---:|---:|
| `codec.getOutputImage` | **308** | 0.4 % |
| `packNv21` | **77 435** | **99.3 %** |
| `InputImage.fromByteBuffer` | 217 | 0.3 % |
| `image.close()` | 9 | 0.01 % |

§2 said M1 decides A2, B2 and all producer parallelism "with a 5× swing", and that if `getOutputImage`
dominated, every gather-parallelisation idea died. **It does not dominate — it is 0.4 %.** So:

- **A2 (bulk-copy rows to heap) is LIVE**, and its −15 000 ms estimate now has a mechanism: the whole
  producer cost is the strided gather, at **34.9 µs/kB** for ~345 kB/frame.
- **B2 (frame-level producer fan-out) is LIVE.** Only 308 ms of the producer's 78 s is serial
  `Image`-bound work; the rest is per-frame arithmetic over already-mapped memory.
- §9's kill of "packNv21 is latency-bound, therefore scales with threads" was aimed at the
  *misattribution*, not at the conclusion. The conclusion now stands on a measurement.

### M4 — the producer already runs on the prime core. The item is closed, at zero cost.

```
sample: tid=7699 prio=0 cpu=[0,0,0,1,0,0,0,24]
```

25 samples, **24 on cpu7 (the Cortex-X3)**, 1 on cpu3, none on an A510. §2 wondered whether the hottest
thread in the app was landing on a little core and priced the fix at ~2 lines. It is not, and there is
nothing to fix — `setThreadPriority` would buy nothing. **Do not spend the 2 lines.**

### M2 — the 110 699 ms audio residual is attributed. `decode` was hiding 55.6 s of it.

```
separate split:    stft=4079 ort=270177 istft+ola=4622 gate=7453 gather=105 flush=7953 encode=26584
separate residual: decode=55589 yield=123 sessionCreateMs=881 gateOpenMs=171
```

Unattributed remainder is now **7 683 ms (2.0 %)**, from 114 343 ms measured on the baseline run.

- **`decode` = 55 589 ms = 14.4 % of `separate`, the second-largest item after ORT.** This re-ranks
  **C3**: §5 demoted it to "−10 000..14 000 ms" on the grounds that `AudioDecoder.pump` has no terminal
  timeout, and that reasoning stands — but the pool it draws from is 4× larger than anyone thought.
- **`yield` = 123 ms.** Thermal yielding is not a factor at this length off charger. One of M3's three
  open questions, answered for short clips.
- `gather` = 105 ms and `gateOpen` = 171 ms are noise. `stft`+`ola` = 8 701 ms confirms §5's DSP figure.

### C1 — shipped, and it moved further than predicted. This needs a listening test, not a re-measure.

| | baseline | after |
|---|---:|---:|
| chunks skipped | 136/276 (49 %) | **158/276 (57 %)** |
| ORT `session.run` | 323 151 ms | **270 177 ms** (−16.4 %) |
| `separate` | 447 875 ms | **385 420 ms** (−13.9 %) |

§5 predicted 141/276 and −40 000 ms. Measured is **158/276 and −62 455 ms** — the win is 1.6× the
estimate *because the behaviour change is 4.4× the estimate*. The rule is correct (an independent
brute-force over the boundary alphabet confirmed the new separate-set is a strict subset of the old, and
that every dropped chunk is exactly "own score < `DILATE2_MIN_SCORE` and its only music neighbour at
±2"), so the off-device replication simply mispredicted this clip.

**22 chunks ≈ 51 s of audio are no longer separated.** All scored below white noise (0.024) with no music
within ±1. That is a defensible bar, but it is the miss-rate dial and §5's own gate is *"the worst chunk
it stops separating, listened to"* — **still owed.** Revert is one constant: `DILATE2_MIN_SCORE = 0f`.

### C2 — implemented, measured, REMOVED. §5 called it "NOT ESTIMABLE until M2"; M2 estimated it.

```
sessionCreateMs = 881          0.23 % of a 385 420 ms stage
serialized graph = 157.6 MB    against the 87.9 MB .onnx
peak RSS         = 1 267 532 -> 1 401 700 KB   (+10.6 %, budget is 1.5 GB)
```

The graph serializes at ~1.8× the model because ORT demotes the fp16 initializers to fp32 at load
(§6.1) and writes the demoted form. **157 MB of the user's storage and 10.6 % of a RAM budget lmkd has
already killed this app over, to save at most 0.9 s per session create.** Even the film path, which pays
it once per resume, cannot make that worth the disk. Removed, with the numbers recorded at the site.

### A1 + A4 — A4 is dead; A1 ships faithful, and it is worth far less than §3 projected.

A4 — filling the gate tensor from the packed 640-px NV21 — **failed §3's own `needs-check` and was
reverted.** Measured against the pre-diff build on the same clip:

| | measured | §3's bar |
|---|---:|---:|
| censored-timeline recall | **91.24 %** | ≥ 99.20 % |
| under-censored vs baseline | **34.5 s** | ~0 |
| net censored time | −15.1 s | ≥ 0 |
| `gateFirings` / `intervalCount` | 719 / 75 | 781 / 76 |

Not a bug: "nearest of nearest of 1920" lands on different source pixels than "nearest of 1920", and the
chroma is subsampled at 640 rather than at source. **Those pixels are gone; A4 cannot be made faithful.**

**A4 is replaced by splitting `gateFill` at the gather/arithmetic seam instead of the pixel-source seam.**
The producer keeps the part that must touch the decoder's planes and hands the consumer raw Y/U/V bytes;
the consumer does the BT.601 arithmetic. Same source pixels, same coefficients, so the tensor is
bit-identical — pinned by a zero-delta equivalence test against `convertToTensor` at all four rotations
and both chroma layouts, and confirmed on device: **`gateFirings` 781, `intervalCount` 76 and a
byte-identical 386.9 s censored timeline, 100.00 % recall.**

**The honest speed number is −3.4 %, not −14.4 %.** Both runs below are the SAME clip at a matched start
temperature (29.1 °C vs 28.9 °C, `maxThermal=0` throughout) — the earlier −14.1 % figure compared a
33.0 °C baseline against a 28.9 °C build and is thermal drift, not gain.

| | baseline | A1 faithful | delta |
|---|---:|---:|---:|
| analyze | 108 203 | 101 387 | **−6 816 (−6.30 %)** |
| render | 89 248 | 89 202 | −46 (−0.05 %) — the control |
| **total** | **198 093** | **191 327** | **−6 766 (−3.42 %)** |

**Why §3 over-projected by 4×, and it is not the split's fault.** §3 modelled A1 as "pure scheduling; no
loop gets faster". It is not:

```
producer   nv21 60 677 + gateFill 27 638 = 88 315   ->   nv21-equiv 66 768 + gateGather 14 677 = 81 445
consumer   detect 9 427 + gate 27 245    = 36 672   ->   detect 9 969 + gateFill 39 634 + gate 25 446 = 75 049
```

**`packNv21` is untouched code and it went 60 677 -> 66 768, +10.0 %.** M4 shows the producer pinned to
cpu7 (the X3) 25 samples out of 25; the consumer now runs 39.6 s of arithmetic that wants the same core
and the same cache. So moving work off the producer *made the producer slower*, and the wall recovered
only 6 816 ms of the 12 961 ms the producer actually shed. **Any future item that moves work between
these two threads must budget for that contention** — §3's projection table assumes it away, and it is
the single reason its "+A1 -> 85 155" row did not happen.

Second-order: the gather half costs 4.57 ms/gate-frame, not the ~2.6 ms M1's 34.9 µs/kB predicts for
75 kB, so the seam splits 4.6/12.3 rather than 2.6/8.2.

### Where shape A stands

All matched-temperature, one cooled S23 session, `qa-assets/tv1.webm` (643 s), censor-only, rect blur.

| | analyze | render | total | vs baseline | quality |
|---|---:|---:|---:|---:|---|
| baseline | 108 203 | 89 248 | 198 093 | — | — |
| **+ A1 faithful + A6 (shipped)** | **101 387** | **89 202** | **191 327** | **−3.42 %** | **byte-identical EDL** |
| ~~+ A4~~ | 102 555 | 89 287 | 192 991 | reverted | **91.24 % recall — under-censors 34.5 s** |

**Next, in order, now that M1 has run.** A2 is unblocked and is the biggest remaining shape-A item:
`pack` is 66 287 ms of an 81 445 ms producer, all of it strided gather. But note the consumer is now at
75 049 ms against a producer of 81 445 — **6.4 s apart.** A2 makes the consumer the wall almost
immediately, so A2 and A8 are now one decision, not two.
