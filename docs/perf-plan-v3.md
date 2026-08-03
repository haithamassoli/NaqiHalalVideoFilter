# Perf plan v3 — measured 2026-08-03 on an S23 (`R3CW5070LGM`)

Supersedes nothing. `video-performance-plan-v2.md` shipped and its §5 results stand; this plan starts
where that one stopped, from **four numbers that had never been recorded on any device** — the
`nv21=`, `gateFill=`, `detect=` and `gate=` timers all shipped in plan-v2 and nobody had ever read
them together.

Reading them changes the target. **Analyze is no longer gate-bound.** Delete that assumption from
`naqi-analyze-is-gate-bound` when this lands.

---

## 0. Where the time actually is

Censor-only, `tv1.webm` (643 s), `censorWho=everyone`, cooled to ≤32.5 °C, `maxThermal=0`:

| stage | wall | share |
|---|---:|---:|
| analyze | 114 648 ms | 56 % |
| render | 89 411 ms | 44 % |
| publish | 343 ms | 0.2 % |
| **total** | **204 752 ms** | 3.1× realtime |

Analyze splits across two coroutines that run **concurrently** behind `Channel(2)`, so
`wall ≈ max(producer, consumer)`:

| side | work | measured | ms/frame |
|---|---|---:|---:|
| **producer** (decode loop) | `nv21=` | 64 818 ms | **10.08** / frame |
| | `gateFill=` | 29 493 ms | **9.17** / gate frame |
| | **subtotal** | **94 311 ms** | **82 % of the analyze wall** |
| consumer (ML thread) | `detect=` | 10 209 ms | 1.59 / frame |
| | `gate=` (session.run) | 29 358 ms | 9.13 / gate frame |
| | subtotal | 39 567 ms | 35 % of wall |
| residual (decode + overhead) | | ~20 332 ms | |

Three consequences, and they all cut against the current docs:

1. **The producer is the bottleneck.** 94.3 s of a 114.6 s wall. Every millisecond saved on the
   *consumer* — the NSFW gate, ML Kit, the gender vote — buys **zero** wall until the producer drops
   below 39.6 s. That retires "analyze is gate-bound on the NSFW gate (~78 % of wall)".
2. **ML Kit detect is 1.59 ms/frame**, not the ~8.6 ms the docs carry (measured pre-V1, when it was
   handed a Bitmap). Face detection is now the cheapest thing in the pass.
3. **The INT8 gate did its job and is done.** `session.run` at 9.13 ms/gate-frame is real work, but it
   is hidden entirely under the producer. There is nothing left to win there.

Music-removal jobs are a different shape and the separator still dominates them: `separate` =
449 376 ms, of which ORT `session.run` = 328 080 ms (73 %), DSP = 10 597 ms (2.4 %), residual =
110 699 ms (24.6 %).

### The order to do it in

Every number below survived an adversarial pass; the ones that did not are listed as refuted with the
reason. Two of the top three items are **measurements, not optimizations** — that is deliberate, and
§1 explains why.

| | item | effort | value |
|---|---|---|---|
| 1 | **§1** split the `nv21=` timer | ~6 lines | decides §2 entirely; 5× swing on its top item |
| 2 | **§3.0** split the audio residual | ~10 lines | decides §3.1 and §3.4 |
| 3 | **§3.1** non-blocking audio decoder drain | ~5 lines | the same anti-pattern that once cost 135 s on a 193 s track |
| 4 | **§2.2** move `gateFill` to the idle consumer | small | ~25 s of a 114.6 s analyze, pure scheduling |
| 5 | **§3.2** two-tier music guard | small | ~40 s of `separate` |
| 6 | **§2.1** bulk-copy decoder rows to heap | medium | ~15 s, but only if §1's hypothesis holds |
| 7 | **§5.1** prefer H.264 in the download selector | ~2 lines | ~10 s of render, needs a matched-bitrate re-measure |
| — | **§4** close plan-v2 §A0 as measured-dead | 0 lines | stops budgeting device runs against a dead lever |

Nothing here is a rewrite. The largest item is medium-effort, and the first three are under 25 lines
combined.

---

## 1. Item 0 — split the `nv21=` timer before spending one line optimizing it

**~6 lines, no correctness surface, and it decides whether every idea below is worth 25 s or 4 s.**
Nothing else in this plan starts until it lands.

`nv21=` is not `packNv21`'s cost. `t0` is taken at `FrameSampler.kt:227`, **before**
`codec.getOutputImage(outIndex)` at `:228`, and `convertNs` is accumulated at `:243`, **after**
`image.close()` has already run in the `finally` at `:236`. So:

```
nv21= = getOutputImage + cropRect/sxMap/syMap setup + packNv21
        + InputImage.fromByteBuffer + image.close()    − gateFill
```

That matters because the arithmetic does not close. The identical loops, run on a desktop JVM with
the same source dimensions:

| loop | desktop JVM | S23 | ratio |
|---|---:|---:|---:|
| `packNv21` (1920×1080 → 640×360) | 0.270 ms | **10.08 ms** | **37×** |
| `convertToTensor` (→ 224² NCHW) | 0.353 ms | **9.17 ms** | **26×** |

A phone is 3–5× slower than a desktop at scalar Java, not 26–37×. **The arithmetic is not the cost.**
The leading hypothesis is that both loops read straight out of the decoder's output `Image` planes —
dmabuf/ION memory, typically uncached or write-combined on the CPU side, where a strided gather is an
order of magnitude worse than a linear read. `gateFill` is measured *strictly inside* `toFrame` and
contains no `getOutputImage` at all, so its 26× cannot be buffer mapping — only where it reads from.

It is a hypothesis, not a finding, and one cross-check already strains it: `packNv21` reads **more**
bytes from those same planes (345 600) than `convertToTensor` does, yet the two are within 10 % of each
other per frame. Something other than byte count is in there. That is precisely why this is item 0 and
not a fix. If the hypothesis survives, the fix is one bulk `Buffer.get(byteArray)` per row into heap
then gather from heap (§2.1), and every "do fewer pixels" idea below is attacking ~10 % of the number.
If it does not, §2's whole ranking changes.

**Why this is item 0 and not item 3:** this repo has already spent two optimizations on an unmeasured
residual. `perf-plan.md:355-413` parallelised the YUV convert across cores on the strength of a
"41 ms/frame" figure that was a `wall − detect − gate` subtraction; it cut `convert=` by 24-32 % and
moved the wall by **0 %** across 20 runs, and was reverted. The real figure turned out to be
16-19 ms. This is the same mistake queued up again, one layer down.

Instrument, in one pass: `getOutputImage` alone, `packNv21` alone, `InputImage.fromByteBuffer` alone,
`image.close()` alone, plus the existing `gateFill`. Then one A/B of the heap bulk-copy against the
current strided read. One cooled device run each.

---

## 2. Analyze — ranked after item 0, and every number here is provisional on it

**These savings do not add up, in the arithmetic sense.** They draw on one pool — the same
`(10.08 − getOutputImage) × 6 430` producer pixel budget — and two pairs are mutually exclusive
(2.3 needs the NV21 on covered gate frames, which 2.5 is trying not to produce; 2.3 needs chroma,
which the chroma-skip idea removes). Summing the column gives 74 s out of a 94.3 s producer, which is
not a real number. Take them one at a time, re-measuring after each.

| # | change | effort | claimed | after refutation | gate |
|---|---|---|---:|---:|---|
| 2.1 | Bulk-copy decoder rows into heap, gather from heap | medium | 36 000 ms | **15 000 ms** | item 0 |
| 2.2 | Move `gateFill` off the producer onto the idle consumer | small | 29 493 ms | **25 000 ms** | 2.3's check |
| 2.5 | Skip pixel work inside already-censored gate intervals | medium | 22 000 ms | **13 400 ms** | correctness |
| 2.4 | `gateEvery` 2 → 4 (gate at 2.5 fps) | trivial | 14 700 ms | **14 700 ms** | correctness |
| 2.6 | `maxDim` 640 → 480 | trivial | 17 000 ms | **8 700 ms** | ranked last on purpose |
| 2.3 | Fill the gate tensor from the packed NV21, not the source planes | small | 12 000 ms | **4 700 ms** | item 0 |
| — | Skip the NV21 chroma plane for ML Kit | trivial | 10 800 ms | **refuted — 0** | — |
| — | Parallelise the repack across workers | large | 32 400 ms | **refuted — 0** | — |

**And `nv21=`/`gateFill=` are wall-clock, not CPU.** They are `nanoTime` deltas on a coroutine sharing
8 cores with the consumer, ML Kit's own executor and ORT's XNNPACK pool; scheduler preemption,
`frames.send()` parking and any thermal step all land inside them. So they are an **upper bound** on
removable time and nothing here returns 1:1 — but the non-linearity cuts both ways, because freeing
producer CPU also frees cores for the consumer.

**2.2 is the one free lunch in the table** and it is pure scheduling, not a re-run of the rejected
parallel-convert. The producer is busy 94.3 s; the consumer is busy 39.6 s of the same wall, i.e.
~75 s idle. `gateFill` is 29.5 s of *producer* time doing work the consumer consumes anyway. Moving it
across the channel boundary puts the producer at 64.8 s and the consumer at 69.1 s — a new wall around
69 s against 114.6 s — without making any loop faster. **It is not pixel-equivalent**, so it inherits
2.3's accuracy check.

**2.3's real risk is not speed, it is the gate's thresholds.** Filling from the 640-px NV21 changes the
gate's sampling grid from "nearest of 1920" to "nearest of nearest of 1920". `NsfwGate.TABLE` was
QA-tuned against the current grid, and plan-v2 measured that dropping the gate's input resolution
outright costs accuracy (71.7 % argmax agreement at 192² vs 91.1 % for INT8 at 224²). This is a far
smaller perturbation — but it needs the same check: argmax agreement against the current pipeline over
the 360-frame set `Models.kt` documents, before it ships. Its own saving is small (4 700 ms: `packNv21`
already reads *more* bytes from the same gralloc planes than `convertToTensor` does, which caps the
"reads are the cost" hypothesis); it earns its place by unblocking 2.2.

**2.5's in-interval proof holds** and was verified independently: `Edl.regionsAt` returns empty under
`fullFrameAt`, `CensorEffect.drawFrame` *independently* forces `regions = emptyList()` when `full`,
`coveredUntilMs = max(firing + POST_MS)` can never exceed the EDL's coverage because `NsfwGate.intervals`
only ever merges or extends, and `censorSpans` always includes those intervals. So face work inside an
already-firing interval genuinely cannot reach a rendered pixel. Watch the 50 ms trailing edge: a track
starting at `ptsMs − SPAN_PAD_MS` can land after the interval ends.

**2.4 is trivial to write and the one with a real product cost.** At `gateEvery=4` the gate samples
every 400 ms instead of 200 ms, so it can miss an NSFW event shorter than 400 ms — and that failure
direction is *less* censoring, which is the direction this app never takes. The arithmetic is confirmed
(1 607 × 9.174 ms); the correctness argument is not. Measure censored-timeline overlap against the
current build before believing it; `Models.kt` records the protocol (99.20 % recall was the bar INT8
had to clear, and it censored 16.2 s *more*).

**`maxDim` 640 → 480 is ranked last on purpose.** Cheapest edit, most dangerous: fewer input pixels
means small faces stop being detected — the same fail-open that killed the 5-fps experiment
(`perf-plan.md:198-223`, where every count improved and a rendered-pixel diff found a fully visible
face at 13.56 s). Note also that it does **not** shrink `gateFill`: `convertToTensor` builds `gx`/`gy`
over the crop rect, not over `dispW`/`dispH` (`FrameSampler.kt:346-353`), so the gate reads the
decoder's planes at full source resolution regardless. `perf-plan.md:485`'s claim that it shrinks the
gate's input too is **stale** — written when the gate re-scaled a bitmap. Fix that line.

**The chroma skip is refuted, and one reason is worth keeping:** `wantChroma = classifier != null ||
wantGate` is a per-**pass** constant, not a per-frame flag — `genderVoter` is non-null exactly when
`censorWho` is WOMEN or MEN. So it saves **zero** in the two modes this repo just shipped.

---

## 3. Audio — the dominant stage on a **music-only** job, and read that word carefully

`separate` = 449 376 ms measured on a music-only run. ORT is 73 % of it, DSP 2.4 %, and the residual
is **24.6 % (110 699 ms)** — which is the surprise, and which is a subtraction, not a measurement.

**Before any of this: S1 ships, and it inverts the idle-core premise.** `FilterWorker.branches()`
runs `AudioPipeline.removeMusic` concurrently with analyze+render (`FilterWorker.kt:400-416`, "the
separator starts at t=0 and the two video passes run alongside it"). Every number below comes from a
run with no video branch in it. **On a combined job, audio ms saved buy zero wall until the audio
branch drops below the video branch** — §0's concurrency trap one level up, and the video branch it
is racing is itself producer-bound on CPU pixel work. State the job shape with every audio number or
the number means nothing.

### 3.0 — split the 110 699 ms residual first (~10 lines)

Same discipline as §1, same reason. `DemucsSeparator` already has the pattern (`stftNs`/`inferNs`/
`olaNs` at `:235-245`, added because plan-v2 §5.9 refused to rank work on an unmeasured split). Four
more counters — `gateNs` around `scoreChunk`, `gatherNs` around `inferChunk`'s ring walk, `flushNs`
around `flush`, `decodeNs` in `AudioDecoder`'s pump — decide 3.1 and 3.4 both. Until they exist,
"400 ms/chunk of residual" is a number with no owner.

### 3.1 — drain the audio decoder without blocking (~5 lines) — **do this first**

`AudioDecoder.decode`'s pump (`AudioDecoder.kt:255-310`) queues at most **one** input buffer per
iteration and then calls `dequeueOutputBuffer(info, TIMEOUT_US = 10_000)` — a blocking 10 ms wait —
about **115 times per chunk**. This is the exact anti-pattern `AacWriter.drainEncoder`'s KDoc
(`AacWriter.kt:136-143`) records as having cost **135 s on a 193 s track, ~129 s of it sleep**.

Drain output non-blocking and block only when genuinely starved. No threads, no channels, no
buffer-aliasing analysis. It dominates 3.4 on ms-per-line by a wide margin and may eat most of it —
so it must be measured before 3.4 is written, not after.

### 3.2 — two-tier music guard: hard ±1 chunk, ±2 only for chunks scoring ≥0.02

**~40 000 ms** (proposer claimed 50 600). Replicated off-device against the shipped gate on
`tv1.webm`'s real audio — same grid, same resample, same `MUSIC_RANGES`, same `T` — giving 141/276
chunks skipped against the device's 136/276, with 271/276 chunk-level agreement. The 20 additional
dropped chunks score **≤0.0139**, i.e. below white noise. `separateChunk` already scores forward to
`c+DILATE` and reads `c-DILATE` from the ring, and `LOOKAHEAD = 2*STRIDE` already covers a ±2 tier,
so no lookahead change is needed. This is the miss-rate dial, so the acceptance test is the worst
chunk it stops separating, not the chunk count.

### 3.4 — `session.dynamic_block_base` on the htdemucs session

**~5 000 ms** (proposer claimed 16 400). The option is real, not assumed: `session.dynamic_block_base`
is a literal string in the shipped `jni/arm64-v8a/libonnxruntime.so` (1.27.0) next to the thread-pool
dump, `addConfigEntry` reaches it, and grep confirms this repo never sets it. It changes only how the
pool partitions a parallel-for — identical values, identical order, zero output risk. Host sweep is
noisy (dbb=1 5.3 % better on min-of-5), so it is a device sweep or a drop, and it goes **after** 3.1.

### Dropped from this section

- **Re-sweeping `INTRA_OP_THREADS`.** The premise was false and git settles it: `SEG` has been
  `114_660` (2.6 s) since `6bd549e` (2026-07-26), and the thread sweep ran `c84d098` (2026-07-28)
  with that constant already in place. The device has already answered this question, at this
  geometry, with this ORT, under these options, and the answer was that 8 is **4.8 % worse**. The
  "3.9 s geometry" is a **stale KDoc** on `NaqiModel.HTDEMUCS` (`Models.kt:93-96`), contradicted three
  lines later by its own `smokeShapes`. **Action: fix the KDoc**, spend no device run.
- **The ≥30 min film path was never measured, by anyone.** `Eta.CONFIRM_THRESHOLD_MS` is 30 min, so
  `tv1.webm` took `removeMusic`, not `removeMusicResumable` — the path every actual film takes. Three
  things differ there and none are costed: no AAC encoder in the chunk loop at all;
  `Checkpoint.writeAudio` firing a JSON write **every chunk** on the critical path (`:363`); and
  `encodePcm` (`:415-441`) as a fully serial AAC pass over the whole scratch *after* separation ends,
  which no per-chunk pipelining touches and which is minutes of pure tail on a 155-min film. This is
  probably the largest audio item in the plan and it currently has no number at all.

---

## 4. The fp16 lever is dead — an autopsy, not an action

`video-performance-plan-v2.md:302-333` calls fp16 htdemucs "**the single largest unmeasured lever**",
worth 0 or 30–70 min per film for an export flag. It is worth **0**, and the reason is structural.

Verified directly against `app/src/main/assets/models/htdemucs_s26_f16.onnx`:

```
Conv: 92   |   Pool-ish: 0   |   Conv whose data input is another Conv: 0
initializer dtypes: {FLOAT16: 552, INT64: 73}
```

and against the shipped runtime, `jni/arm64-v8a/libonnxruntime.so`, which contains `FusedConvFp16`,
`PoolFp16`, `MlasHalfGemmBatch` and `IsIsolatedFp16NodeOnCpu` — and **no other fp16-specialised CPU
kernel class**.

So: the weights really are fp16 and the arm64 fp16 kernels really do exist, but ORT's CPU EP fp16
island is **Conv + Pool only**, htdemucs has 92 Conv and **zero** Pool, and **no Conv is adjacent to
another Conv**. Every one of the 92 is an isolated fp16 node, and `IsIsolatedFp16NodeOnCpu` converts
each back to fp32 at graph-optimization time. That is what the 201 `InsertedPrecisionFreeCast` nodes
are; the file itself contains only 6 `Cast` nodes.

**Do not act on this.** Shipping the fp32 graph instead was the obvious follow-on and it is refused:
the cast overhead is 2.0 % of `separate`, under this plan's own 3 % bar, while the models are
*bundled* APK assets (`downloadUrl = null` for HTDEMUCS), so it costs **+85 MB of bundle and +85 MB of
device storage** — and because ORT already demotes the weights to FLOAT at load, runtime RSS and
memory bandwidth do not improve at all. It would also invalidate the 63.4/69.0 dB fp16 parity numbers
M2/M3 device-verified and force re-verification of `skipChunks`' bit-identical-resume contract.

The value here is the negative result. **Close plan-v2 §A0 as measured-dead**, keep `nonFinite` as the
belt it already is, and stop budgeting device sessions against it.

---

## 5. Render — not GPU-bound, and the lever is in the **downloader**

Three runs, same clip, same session, three different EDLs:

| run | mode | render | analyze |
|---|---|---:|---:|
| A | rect blur | 89 411 ms | 114 648 ms |
| B | whole-frame | 89 259 ms | 121 110 ms |
| C | whole-frame + span floor | 89 437 ms | 150 291 ms |
| | **spread** | **0.20 %** | **31.1 %** |

Analyze degraded 31 % across those three runs as the phone loaded up. Render did not move.

And then a direct ablation settled it causally rather than by inference. Identical 156.128 s /
4 679-frame excerpt, `whole_frame=true`, back-to-back on a rested S23:

| blur setting | GL work per frame | render |
|---|---|---:|
| `blurAmount=60` → d=8, 240×135 scratch, 17 taps | 1.10 M texel fetches | 22 003 ms |
| `blurAmount=5` → **d=1, full-res 1920×1080**, 11 taps | **45.6 M texel fetches** | 22 078 ms |

**41× the fragment work and +33 MB/frame of render-target traffic, for +0.34 %.** Both the ALU
hypothesis and the bandwidth hypothesis die in one measurement. Supporting telemetry from the 643 s
job: `gpu_busy_percentage` 55.4 % mean while `kgsl` sat at **289 MHz against a 719 MHz ceiling** —
22.3 % of available throughput, with the governor never asking for more — and a flat 213–220 fps in
every 2 s window across the whole 89 s.

**The actual ceiling is the hardware AV1 decoder** (`c2.qti.av1.decoder`): 1.123 ms of the
4.702 ms/frame. The same clip re-encoded to H.264 rendered at 3.579 ms/frame.

### 5.1 — prefer H.264 in the download format selector (~2 lines, ~10 000 ms)

`Downloader.kt:37-43` holds the fixed yt-dlp selector strings and `:153` is the `-f` site. Preferring
an `avc1` variant hands the pipeline a codec its decoder is faster at. Not rejected item 8 — that was
about asking the *decoder* to scale, not about which codec you hand it.

**Discounted from 21 600 ms to ~10 000 ms because the measurement is confounded:** the H.264
comparison clip was re-encoded at 1.50 Mbps and `tv1.webm`'s own AV1 bitrate was never stated. Entropy
decode scales with bits parsed, not codec alone, so an unknown share of the 1.123 ms/frame is bitrate,
not AV1. Re-measure at matched bitrate before committing. Also weigh what it costs the user: AV1 at
equal quality is a smaller download, so this trades bytes for seconds.

### 5.2 — everything else in the render path is a cleanup, not perf work

Overriding `shouldClearTextureBuffer()` to skip media3's redundant full-res `glClear`; removing
`Edl.regionsAt`'s duplicate internal `fullFrameAt` call; the per-frame `ArrayList`/`NRect`/`IntArray`
allocations in `drawFrame`; `texturePoolCapacity = 1`. All real, all make the code smaller — take them
if you are in the file anyway. **Expect zero measurable ms**: §5's ablation says the GPU is at 22 % of
its available throughput and does not care.

Also settled: whole-frame blur costs nothing at render time (`plan-whole-frame-blur.md §6.2`).

### Refuted in this section

- **Pipelining analyze and render through the segmented route** (claimed 45 000 ms). It breaks the
  invariant the code documents twice: `censorSpans` "runs ONCE over the whole timeline, never per
  segment", and `runSegmented`'s KDoc — "building it per segment would clip up to 1.5 s of censoring
  at every seam". Concretely `NsfwGate.PRE_MS = 500`, so a firing 300 ms into segment N+1 must censor
  the last 200 ms of segment N. Handing a segment to the renderer before the next one is analyzed
  lets faces and NSFW frames through. This is rejected-item-6's precedent exactly.
- **media3's decoder operating-rate hint** (claimed 3 000 ms). The APIs all check out; it was already
  tried and recorded as settled. And the evidence says the decoder is architecture-limited, not
  clock-limited: it already delivers 212.7 fps on a 29.97 fps stream with no operating rate set.

---

## 6. Not doing

Everything in plan-v2's rejected list still stands (NPU, smart rendering, gate batching, lower gate
resolution, XNNPACK thread changes, decoder-side downscaling). Two additions from this round:

- **Skipping the NV21 chroma plane** when only ML Kit reads it. Refuted: "ML Kit ignores chroma" is an
  assumption nobody measured, it changes the detector's input bytes on 3 215 frames, and after item 0
  the recoverable share is near zero anyway.
- **Parallelising the repack across workers.** This is `perf-plan.md`'s rejected item wearing a new
  name. The old null result was *not* "the convert was cheap" — the convert got 24-32 % faster and the
  wall moved 0 %. Every source of the contention that explains that is still present.
- **HaramBlur's per-frame pixel-difference cache** (they run a mean-squared-difference skip on both
  models for video). Tempting, and field-proven at 90 k users — but it is a *consumer*-side saving,
  and §0 says consumer savings buy zero wall. Revisit only after the producer drops below 39.6 s.

---

## 7. Measurement protocol — non-negotiable for every number above

`naqi-perf-v2-settled` records this phone producing meaningless numbers after ~5 consecutive long
runs (370 MB free, 3.75 GB zram, chunk times 19 s → 70 s at 497 %/800 % CPU idle — swap thrash, not
thermal). §5's table shows the mild version: 31 % analyze drift across three runs in one session.

So: **cool to ≤32.5 °C between runs** (`dumpsys battery | grep temperature`), one configuration per
run, and never compare a run to one taken more than two runs earlier in the same session. Prefer
in-run counters (`nv21=`, `gateFill=`, `detect=`, `gate=`) to stage wall-clock — they are per-unit and
survive a drifting device, which is exactly how §5's render conclusion was reached while analyze was
falling apart around it.
