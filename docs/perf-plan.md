# perf-plan.md — making a feature-length job finish sooner

Source: `tasks.md` M4/M5 (the measured soak), `long-film-plan.md` Phase 0, `long-film-followups.md`.
Written 2026-07-28, after a read of the analyze/render/separate hot paths.

---

## RESULTS — all phases executed 2026-07-28 (S23, `test-video.mp4` 12.8 s + `women-music-3min-video.mp4`)

**Analyze is 61 % faster with byte-identical EDL output. Phase 2 was implemented, measured, and reverted
for a censoring regression. Phase 4 is rejected on a measured number.**

| asset | analyze @HEAD | analyze now | change |
|---|---|---|---|
| `test-video.mp4` | 9 985 ms | **3 917 ms** | **−60.8 %** |
| `women-music-3min-video.mp4` | 100 674 ms | **37 867 ms** | **−62.4 %** |

`gateFirings`, `intervalCount` and every interval span are identical to HEAD on both assets, and the
rendered output pixel-matches the 10 fps reference at every timestamp carrying a censor box.

| item | verdict |
|---|---|
| 1.1 `lastPct` guard | **shipped** — segmented analyze 11 546 → 10 142 ms (−12.2 %), back to parity with unsegmented |
| 1.2 instrumentation | **shipped** — and it overturned this plan's premise, see below |
| 1.3a gate ∥ face detect | **shipped** |
| 1.3b decode ∥ inference | **shipped** — `Channel(2)` + 4-slot bitmap rotation |
| 1.4a benchmark build type | **NOT DONE** — trap, see 1.4 |
| 1.4b decoder hints | **tried, measured −0.5 % (noise), REMOVED** |
| 2 — 5 fps dial | **implemented, measured, REVERTED** — left a face uncensored |
| 3.1 thread count | **shipped** — 6 (capped, not hardcoded), −4.8 % |
| 3.2 spinning | **kept at "0"** — "1" measured 2.7 % *slower* |
| 3.3 ORT profiling | **answered: do NOT re-export f32.** Cast = 4.6 % of kernel time |
| 4 — concurrent separate | **REJECTED** — 1.82 GB against a 1.5 GB budget |

### The headline: the plan aimed at the wrong target

1.2 measured, on 128 sampled frames: `wall=9936ms detect=3118ms gate=1563ms`.

| cost | share | per call |
|---|---|---|
| **decode + hand-rolled YUV→RGB convert** | **52.9 %** | 41.1 ms/frame |
| ML Kit face detect | 31.4 % | 24.4 ms/frame |
| NSFW gate | 15.7 % | 24.4 ms/call |

The convert is **bigger than both ML models combined**. Everything Phase 2 proposed was aimed at ML.
Cost model `10*(41.1+24.4) + 5*24.4 = 776 ms/s` reproduces the measurement (775 ms/s) to under 1 %, and
held at 53–55 % across every run. After 1.3 the loop is **producer-bound on that convert**.

### Two findings that outlive this plan

1. **"Byte-identical EDL" is only half-achievable, and not because of any change here.** The gate half is
   byte-identical, always. The face-track half is **not reproducible even on a fixed build** (HEAD alone
   gave 11/11/11/13 censor tracks over four identical runs). ML Kit is fed
   `InputImage.fromBitmap(bitmap, 0)` and has **no frame-timestamp API for bitmap tracking**, so its track
   continuity is wall-clock sensitive: every speedup re-segments the tracks. This cannot be fixed from our
   side, and it means the exit criterion as written is unmeetable by any perf work. Coverage survives a
   split (`Edl.regionsAt` unions spans); the exposure is the gender vote, which can fail **open**.
2. **Counts cannot detect a censoring regression — only pixels can.** At 5 fps `censorFaceTracks` fell
   27→20 while `tracks` fell 211→128, so the censored *fraction* went **up** (12.8 %→15.6 %), which reads
   as an improvement. A frame-by-frame diff of the rendered video found the truth. Use the diff method
   (below) for any future change to sampling.

### The method that caught it, for reuse

```sh
ffmpeg -i a.mp4 -i b.mp4 -lavfi "[0:v]scale=320:-1,format=gray[x];[1:v]scale=320:-1,format=gray[y];\
[x][y]blend=all_mode=difference,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=diff.txt" -f null -
```
Median YAVG of 0.0 over 4 625 frames means pixel-identical; the outliers are the timestamps to eyeball.
Ignore fade-to-black frames — encoder noise dominates there and reads as a false positive.

---

`long-film-followups.md` lists "making the pipeline faster" under **deliberately not doing**, on the
grounds that htdemucs at ~1.3× realtime is the model's floor. That stays true and this plan does not
argue with it — **it is about the other 45 % of the job**, which has never been optimized or even
broken down, plus one thing that is a regression rather than a slowness.

## Where the time actually goes

One instrumented combined run, `qa-assets/movie-test.mp4` (155.4 min, 1728×720 @ 23.976, S23, on
charger). Everything below the line is projected, and says so.

| stage | cost | share | provenance |
|---|---|---|---|
| **separate** (htdemucs) | ~101 min | ~55 % | **projected** — M2's 0.65× source, never run at feature length |
| **analyze** @10 fps | **70.5 min** | ~37 % | measured; 93 240 sampled frames at **~45 ms each** |
| render | **9.7 min** | 5 % | measured (64 % in 6.2 min, extrapolated) |
| vote + mux + publish | ~3 min | 2 % | vote measured at 2.7 min, since spread across the pass |

Two consequences set the whole shape of this plan:

- **A censor-only job is ~90 % analyze.** Nothing else in it is worth touching.
- **A combined job is analyze + separate, roughly half each.** Render is already 3× cheaper than the
  plan estimated and is not on the table.

**What is NOT known: the split inside those 45 ms.** ML Kit face detection, the NSFW gate, the
hand-rolled YUV→RGB convert and the decode itself have never been timed separately. Every ordering in
Phase 2 below is a *hypothesis* until item 1.2 lands. That is the reason the instrumentation is the
first task and not an afterthought.

## Phase 1 — free: no quality change, no tuning, no QA set

**Exit:** one film-length analyze pass that is measurably faster than 70.5 min with byte-identical
EDL output, and a per-sub-step breakdown to aim Phase 2 at.

### 1.1 The per-frame progress write is a regression, not a slowness

`FilterWorker.kt:302` calls `report(stage, pct)` on **every sampled frame** — that is a WorkManager
`setProgress` (an *awaited* Room/SQLite write) plus a `setForeground` (a notification binder IPC),
~93 000 times over a film. The unsegmented path guards exactly this at `FilterWorker.kt:499`/`:506`
with a `lastPct` check; the segmented path lost it.

The waste is near-total, not marginal: with 31 segments over a 40-point band, `pct` takes **~40
distinct values** across those ~93 000 calls. Everything else writes a value identical to the last one.

Note what this does to the headline number: **70.5 min was measured on the unsegmented Phase 0 path**,
which had the guard. A film today takes the segmented path and is therefore *slower than the number
this plan is planning against*, by an amount nobody has measured.

- [x] **DONE.** `lastPct` added to `analyzeSegments`, hoisted OUTSIDE the segment loop so it also
  suppresses the seam duplicate and the checkpoint-resume post. Measured: segmented analyze
  **11 546 → 10 142 ms (−12.2 %)**, now within 2.6 % of the unsegmented path. Note `stats.tick()` lives
  inside `report()` and does a binder `currentThermalStatus` call, so this removed ~93 000 IPCs too —
  at the cost of dropping thermal sampling to ~40/film, exactly as the unsegmented path already did.
- [x] **Checked, and BOTH LEFT ALONE — the plan's description of `renderSegments` was wrong.** It does
  not post per transformer callback: `RenderPipeline` drives it from a fixed 500 ms wall-clock poller
  (`RenderPipeline.kt:55,:117-120`), so ~1 160 calls over a 9.7 min render, and via the fire-and-forget
  `setProgressAsync`, not the awaited `report()`. `reportBand` is ~3 600 async calls per film, ~26×
  below 1.1's old rate with no awaited SQLite write. Neither is 1.1's shape.

### 1.2 Instrument the 45 ms before optimizing it

Two timers, not three: the convert+decode cost falls out by subtracting the two ML costs from the
segment's wall time.

- [x] **DONE**, on both `analyzeSegments` and `analyze()`, logged as `wall=/detect=/gate=`.
- [x] **DONE.** Result at the top of this file: the convert is 52.9 %, not ML.
- [ ] **Caveat left open.** After 1.3a, `tDetect` brackets only the ML Kit *await*; `onFaces` — which
  contains `sweep()` → `GenderVoter` → **NudeNet inference** — is unattributed and lands in the residual
  alongside convert+decode. Harmless on `test-video.mp4` (`peakLiveCrops=0`, no vote ever runs) but it
  silently inflates the apparent convert cost on any asset that votes, i.e. exactly the asset this item
  tells you to measure with. A third accumulator around `onFaces` would close it.

### 1.3 The analyze loop is 100 % serial

Decode → YUV convert (Kotlin, per-pixel) → ML Kit (`FaceTracker.kt:70`, blocking `Tasks.await` on its
own thread) → ORT/XNNPACK → next frame. Nothing overlaps. The codec is idle for the ~45 ms of ML work,
ML Kit is idle during the convert, and on an 8-core S23 the pass uses one or two of them.

Two independent changes, cheapest first, both landing after 1.2 says which is worth it:

- [x] **DONE.** `FaceTracker.onFrame` split into `detect(bitmap): Task<List<Face>>` + `onFaces(...)`;
  `onFrame` deleted rather than left as an unused wrapper. Per-frame order is now
  `detect → Infer.nsfw → Tasks.await → onFaces`. The await stays inside the sampler callback, which is
  what keeps the bitmap alive for both readers. Measured effect: `detect=` collapses from 3 118 ms to
  ~1 114 ms — ML Kit now finishes mostly *inside* the gate's latency.
- [x] **DONE, full version.** `Channel(capacity = 2)` with a **reused 4-slot bitmap rotation**
  (`RING = QUEUE + 2`, strictly greater than the `QUEUE + 1` frames the consumer can hold), so nothing
  is allocated per frame. The consumer is the child coroutine and the decode loop is the parent, so a
  throw out of `onFrame` cancels the loop instead of parking it on a full channel. Bonus win the plan
  did not predict: removing the per-frame `Bitmap.createBitmap` allocation is why the combined result
  beat the −47 % the cost model projected and landed at **−61 %**.

### 1.4 Two measurements that cost no code at all

- [ ] **NOT DONE — and it is a trap as written.** AGP derives `BuildConfig.DEBUG` from `isDebuggable`,
  so a `benchmark` type with `debuggable false` turns off the very `--el segment_ms` and autorun hooks
  needed to drive the soak. Making it useful is not "~6 lines": it needs a separate
  `buildConfigField("boolean", "DEBUG_HOOKS", …)` plus updating every gated call site in `MainActivity`
  and `FilterWorker`. That changes how the app gates debug behaviour, so it is a deliberate decision,
  not a side effect of a perf task. **Still worth doing** — the convert is 53 % of analyze and is exactly
  the kind of tight JVM loop `debuggable=true` penalises. Original note follows:
- [ ] **Re-measure on a non-debuggable build.** `debuggable=true` costs 10–30 % on tight JVM loops and
  `FrameSampler.toUprightBitmap` is exactly one (3 `DirectByteBuffer.get` per output pixel, ~171 k
  pixels/frame). Native ORT and ML Kit are unaffected, so this only moves the convert — but if the
  convert turns out to be 15 % of analyze, it is ~2 min of a film for zero code. **Friction, stated
  honestly:** `--el segment_ms` and the autorun hook are `BuildConfig.DEBUG`-gated, so a release soak
  is driven by hand, and `release {}` has no signing config today. A `benchmark` build type
  (`debuggable false`, `initWith debug`) is ~6 lines if this proves worth repeating.
- [x] **TRIED AND REMOVED.** Measured **−0.5 %** on an S23 analyze pass (9 985 → 9 938 ms, i.e. noise).
  1.2 explains why: the 53 % "decode+convert" share is dominated by the *convert*, which no decoder hint
  touches. A comment at the call site records this so nobody re-adds it. Original note follows:
- [ ] **Ask the decoder to run flat out.** Some vendor decoders clock to display rate unless told
  otherwise; two lines before `codec.configure` (`FrameSampler.kt:97`):
  ```kotlin
  format.setInteger(MediaFormat.KEY_OPERATING_RATE, Int.MAX_VALUE)
  format.setInteger(MediaFormat.KEY_PRIORITY, 1) // 1 = non-realtime/offline
  ```
  If 1.2 shows decode is a small share, this changes nothing — cheap enough to try anyway.

## Phase 2 — the dial: sample at 5 fps — **IMPLEMENTED, MEASURED, REVERTED**

**Exit:** a side-by-side of the same clip at 10 and 5 fps where the difference is not visible on the
censor boxes — or a named case where it is, and the dial goes back.

> ### VERDICT 2026-07-28: the named case exists. The dial went back.
>
> Everything the plan predicted came true, and it still failed. At 5 fps with 1.3's overlaps, analyze
> was **−72.8 %** on `test-video.mp4` and **−73.2 %** on `women-music-3min-video.mp4`, and the gate
> output was **byte-identical** — `gateFirings=265`, `intervalCount=11`, all eleven interval spans
> exact. (The 5 fps grid lands on the same instants the old even-index frames did: 0/200/400 ms. The
> gate's *sample instants*, not just its rate, are preserved.)
>
> Then a frame-by-frame diff of the two rendered outputs (4 625 frames, median difference **0.0**):
>
> | t | 10 fps | 5 fps | |
> |---|---|---|---|
> | **13.56 s** | face blurred | **face fully visible** | **fail-open — a face left uncensored** |
> | 10.16 s | region clear | large blur applied | fail-closed — over-censoring |
> | ~188.7 s | fade to black | fade to black | encoder noise on dark frames, not censoring |
>
> **Counts would have passed this change.** `censorFaceTracks` 27→20 alongside `tracks` 211→128 means
> the censored *fraction* rose, 12.8 %→15.6 %. Only pixels showed the uncovered face.
>
> Reverted: `fps` 5f→10f at both call sites, `index % 2` gate cadence restored, `SPAN_PAD_MS` back to
> 50, `FaceTrackerLogicTest` assertions back to 950/1250. **Item 1.3 is kept** — it is independent of
> the sampling rate and still delivers −61 % with the censor boxes intact.
>
> **If this is retried**, the mechanism to fix first is the gender vote, not the keyframe density:
> `CROP_SPREAD_MS` is 700 ms of *source* time, so at 5 fps a track needs ~3.5 s on screen to fill its
> 5-crop budget instead of ~1.75 s. A track that falls short votes UNKNOWN and fails **open**. Making
> `CROP_SPREAD_MS` scale with the sample gap is the obvious first move.

This is the single biggest lever in analyze and it is one constant, but it trades quality, so it is its
own phase and it does not ship on a hunch.

Today: 10 fps sampling, faces on every frame, gate on every 2nd (5 fps). **Sample at 5 fps and run the
gate on every sampled frame** and the gate's rate is *unchanged* — only ML Kit and the convert halve.
Projected ~25–30 % off analyze (~20 min on a film) with **zero change to gate sensitivity**.

What actually degrades is face-box temporal density: keyframes 200 ms apart instead of 100 ms.
`Edl.rectAt` (`Edl.kt:91`) already linearly interpolates between them and the rects carry a 25 % pad, so
it degrades smoothly rather than falling off a cliff. Fast pans, fast cuts and faces on screen for under
~400 ms are where it would show.

- [x] **Done, then reverted** — see the verdict box above.
- [x] **VERIFIED, not assumed.** Both are compared against `ptsMs`, which is source time
  (`isStale(lastSeenMs, nowMs)` and `ptsMs - state.lastCropMs < CROP_SPREAD_MS`), so neither needs a
  value change. **But source-time-correct is not behaviour-neutral**: `CROP_SPREAD_MS` gates crops per
  unit of *source*, so halving the frame rate halves the crops a track can bank in the same window.
  That is the fail-open mechanism above, and it is the thing this checkbox's "no change needed" hides.
- [ ] **NOT DONE, but its precondition is now MET and it is the top remaining lever.** 1.2 says the
  convert is 53 %, and after 1.3 the loop is producer-bound on exactly it, so `maxDim` 640 → 480
  (convert −44 %) with `MIN_FACE_PX` 48 → 36 is the highest-value item left in this file. Held back
  deliberately: it is a second quality-affecting dial, and bundling it with the 5 fps change would have
  made the regression above impossible to attribute. Ship it on its own and diff the pixels.
- [ ] **Blocked on judgement, not on QA sets.** The strictness/female-face acceptance criteria need the
  QA sets that have been blocked since M1, but *this* change does not touch gate sensitivity — the
  question it asks is "do the boxes still track", which `women-music-3min-video.mp4` answers on screen.

## Phase 3 — audio config, not a smaller model

**Exit:** the `separate` stage is either measurably faster or confirmed to be at its config floor,
with the numbers written down.

None of these change the model or the graph, so none of them contradict the followups' position.

- [x] **SWEPT AND SHIPPED — 6.** Median per-chunk ms over chunks 3–7 (warm-up dropped), 12.8 s clip:
  **threads 8 = 2 244 · 6 = 2 136 · 4 = 2 155**. The hypothesis was right: 6 wins by **−4.8 %**, and
  four of its five chunks came in under 8's *fastest*. Shipped as
  `availableProcessors().coerceAtMost(6)` — capped, not hardcoded, so a 4-core device still gets 4.
- [x] **SWEPT — "0" KEPT, and now for a second reason.** spinning "1" measured **2 305 ms vs 2 244 ms,
  i.e. 2.7 % SLOWER**. It was chosen for power; it is also the faster setting, so there is no trade
  left to make. `maxThermal=0` in every config, so thermal was not the discriminator on this clip.
  Peak RSS moved <0.2 % across all four configs, confirming thread count does not drive the memory peak.
- [x] **DONE — ANSWER: DO NOT RE-EXPORT.** Profiled a 7-chunk run, 14 805 kernel events, 10 509 ms
  total kernel time:

  | op | ms | share | calls |
  |---|---|---|---|
  | Mul | 1 739 | 16.5 % | 2 184 |
  | NhwcFusedConv | 1 502 | 14.3 % | 532 |
  | Gemm | 1 490 | 14.2 % | 308 |
  | Transpose | 1 406 | 13.4 % | 1 708 |
  | Add | 1 119 | 10.6 % | 1 379 |
  | **Cast** | **488** | **4.6 %** | **2 891** |

  ORT *is* bracketing ops with `Cast` — 2 891 of them, more calls than anything but `Mul` — so the
  suspicion was correct about presence and wrong about cost: 169 µs each, **4.6 % of kernel time**.
  An f32 re-export could recover at most that while roughly doubling weight memory, against the one
  budget with no headroom (**1.29 GB measured** vs 1.5 GB). Not worth it. The profiling scaffold has
  been deleted now that the number is recorded here. (`Transpose` at 13.4 % is the more interesting
  target if anyone revisits the graph.) Original note follows:
- [ ] **Profile five chunks** (`enableProfiling`). The graph is f16 on the **CPU EP** (XNNPACK is
  disqualified here for the fp16 corruption bug — see `naqi-m2-findings`). If ORT is bracketing ops
  with `Cast` nodes, that is a large silent tax and an f32 re-export would pay it back. It costs RAM,
  which is the one budget with no headroom (1.30–1.45 GB against 1.5 GB), so **measure before
  exporting anything**.

## Phase 4 — run separate concurrently with analyze+render

**Exit:** not this plan. A decision gate.

Separate is independent of analyze and render until the mux. Running them concurrently takes a combined
film from ~3.1 h to ~`max(101, 83)` + mux ≈ **1.9 h** — a bigger win than every other item here
combined. It is also the only one that is not lazy:

- **Memory.** htdemucs peaks 1.30–1.45 GB; analyze peaks ~500 MB post-Phase-1 retention fix. ~1.9 GB
  together, against a 1.5 GB budget and a 6 GB target device that has never been tested at all.
- **Everything sequential assumes it is sequential.** Progress bands, `JobStats.etaMs`, the checkpoint
  ordering and cancellation all read as one stage at a time.
- The mid-range device that would benefit most is the one most likely to be OOM-killed by it.

- [x] **DECIDED WITH A NUMBER: DO NOT BUILD IT.** Phases 1–3 are in and peak RSS was re-measured:

  | stage | measured peak RSS |
  |---|---|
  | separate (htdemucs) | **1.29 GB** |
  | segmented censor-only (analyze + render) | **0.53 GB** |
  | concurrent, as this phase proposes | **~1.82 GB** |

  Against a **1.5 GB budget** — roughly 20 % over, and not a rounding error. The peaks genuinely
  coincide rather than merely summing on paper: htdemucs holds its high-water for the entire separate
  stage (that is *why* the arena is disabled), so any overlap window has both peaks live at once.
  The 3.1 h → 1.9 h prize is real, but it is bought by blowing the memory budget on the S23 — and the
  mid-range 6 GB device this phase names as its main beneficiary is the one most likely to be
  lmkd-killed by it. Revisit only if the separator's peak drops below ~1.0 GB.

## Deliberately not doing

- **Optimizing render.** 9.7 min of a 3.1 h job, and already 3× better than estimated.
- **A smaller separation model.** Unchanged from `long-film-plan.md` — different project.
- **NNAPI / QNN for the image models.** NNAPI is deprecated and QNN needs per-device Qualcomm libs; the
  gate is ~46 000 inferences, so the payoff is real, but not before Phase 1 says the gate is the cost.
- **Anything that changes EDL output as a side effect of going faster.** Phase 1 must be byte-identical;
  Phase 2 changes output deliberately and says so.

## Open

- Every number here is a **Galaxy S23** and reads optimistically fast. The SD 778G-class blocker is
  unchanged and inherited by every projection in this file.
- `separate` at feature length is still projected from a 5-min clip. `long-film-followups.md` item 4
  (one completed film run) closes that, and closes the ETA band reweighting with it.

### Opened by this round (2026-07-28)

- **No feature-length run was made.** Every number above is from a 12.8 s and a 193 s asset, by
  instruction. The −61 % should scale (it is a per-frame effect), but the film-length claim is
  unverified, and so is the segmented path under 1.3b's channel over thousands of segments.
- **`maxDim` 640 → 480 is the top remaining lever** and its precondition is met — see Phase 2.
- **Instrumentation gap:** `onFaces`/NudeNet voting is unattributed post-1.3a; see 1.2's open item.
- **1.4a needs a `DEBUG_HOOKS` decision** before a non-debuggable soak is possible at all.
- **ML Kit face-track segmentation is wall-clock sensitive and cannot be made deterministic** from our
  side. Any future perf change will move `censorFaceTracks`; that is expected, and **counts must not be
  used as the quality gate**. Diff the rendered pixels instead (method recorded at the top of this file).
- **The combined shape was never re-measured** after these changes — only censor-only and music-only.
  `runCombined`/`runSegmented` share the same `analyze` code so the win should carry, but the
  progress-band arithmetic and the ETA reweighting under a 2.5× faster analyze are untested.
