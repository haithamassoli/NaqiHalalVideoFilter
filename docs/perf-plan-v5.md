# Perf plan v5 — the per-element call, and the thread that was doing three jobs

**Supersedes `perf-plan-v4.md` §3's projection table and §5's ranking of C3/C4. Everything v4
*measured* stands, and §11 is the baseline for every number below.**

v4 asked which stage is on the wall for each job shape and answered it correctly. This plan asks the
next question — *what is that stage actually spending time on* — and the answer for both branches
turns out to be the same shape of mistake, made twice:

- **Blur** was paying **per `ByteBuffer` element call**, not per byte of pixel data. 691 200 calls per
  frame in `packNv21`, 301 056 more per gate frame in `gateFromGathered`.
- **Music removal** was running its MediaCodec decoder, its htdemucs inference and its AAC encoder
  **on one thread**, so 82 173 ms of codec round trips sat on the critical path next to ORT while six
  of the S23's eight cores idled through them.

Both are now fixed. Neither changes a single output byte.

Written after a 51-agent research pass (14 candidate levers, each put through a mechanism skeptic, a
correctness skeptic and a prior-art skeptic). Two levers took fatal refutations, two more were
self-refuted by their own investigators, and one verifier settled a model question by **building the
artifact and benchmarking it** — see §4.1.

---

## 0. The arithmetic error that reranked everything

`perf-plan-v4` §11 closes with *"the consumer is now at 75 049 ms against a producer of 81 445 —
**6.4 s apart**"*, and concludes that A2 "makes the consumer the wall almost immediately, so A2 and A8
are now one decision, not two."

**That comparison is wrong.** 81 445 is the producer's *instrumented subtotal*; it omits the hardware
decoder, which runs on the producer coroutine (`FrameSampler.kt:232` dequeues and `:276` releases,
both inside the decode loop). The residual is not overhead and v4 §3 already priced it at 1.055
ms/frame:

```
producer  pack 66 287 + gateGather 14 677 + misc 481 + decoder 19 942 = 101 387 = analyze, exactly
consumer  detect 9 969 + gateFill 39 634 + gate (ORT) 25 446          =  75 049
```

The consumer is not 6.4 s behind. It is **26 338 ms of slack**. Three of the fourteen lanes converged
on this independently, and it changes three rankings at once:

| item | v4's read | actual |
|---|---|---|
| A2 (`packNv21`) | "makes the consumer the wall almost immediately" | has **26 338 ms** of room before that happens |
| A8 (`gateEvery` 2→4) | −32 540 ms, blocked on correctness | **0 ms of wall** — and A8 also halves `gatherGate`, so its true wall delta is −7 338 even at the point where the consumer *does* bind |
| gate-side work generally | "one decision, not two" | worth **exactly 0 ms until the producer drops below 75 049** |

So the order is forced: fix the producer, and only then does anything on the consumer buy a
millisecond. Both shipped, in that order, in one change.

---

## 1. What shipped

### A2 — `packNv21` reads each source row in ONE bulk call

`analysis/FrameSampler.kt`

**The mechanism is not the one the plan assumed.** v4 A2 sized this at −15 000 ms on a bytes-moved
argument, and `perf-plan-v4` §9 separately killed *"packNv21 is latency-bound at 29.2 ns/get"* as
built on the pre-M1 misattribution. Three pure-gather loops from this repo's own runs — no arithmetic
in any of them — settle it:

| loop | dmabuf bytes | element calls | ms/frame | ns/CALL | ns/byte |
|---|---:|---:|---:|---:|---:|
| `packNv21` pre-A1 (v3 §1) | 345 600 | 691 200 | 10.08 | **14.58** | 29.2 |
| `packNv21` post-A1 (v4 §11) | 345 600 | 691 200 | 10.31 | **14.91** | 29.8 |
| `gatherGate` (v4 §11) | 150 528 | 301 056 | 4.565 | **15.16** | 30.3 |

Read volume differs 2.30×, luma stride differs 3 B vs 8.57 B, chroma subsampling differs — and
**ns/call is flat to 4 %**. That also kills the "the gralloc plane is uncached, so pay per byte"
model, which was the working hypothesis going in: at stride 3 every 64 B line already carries ~21
sampled bytes, so a stride-3 read would be ~2.8× cheaper *per byte* than a stride-8.57 one if the line
were cached at all. It is not. **The call is the cost.**

Per output row, one `get(byteArray, 0, span)` over exactly the span the strided loop already walked —
first byte `sxMap[0]*pix`, last byte `sxMap[w-1]*pix` — then the nearest-neighbour gather out of an
L1-resident heap scratch. **691 200 element calls per frame become 1 260.**

- Same cache lines, same address range, so a shrunk `limit()` still throws exactly where it threw.
- Per-row, not whole-plane: "copy the rows once" means copying the two intervening rows too, 3.1
  MB/frame into a scratch that blows the X3's L2 and turns 345 600 L1 hits into L2 misses. Per-row is
  6.4 kB, and every array is under ART's 12 kB large-object threshold, so all four are TLAB-allocated
  and die in eden.
- The planes are read through `duplicate()`. `toFrame` captures `yBase`/`uBase`/`vBase` from these
  positions *before* the pack, `gatherGate` re-reads the same planes *after* it, and MediaCodec hands
  the same plane buffer back for a recycled output index — a moved `position()` would poison the next
  frame. New test `packing moves no plane position and leaves the output buffer alone` is the guard.

**Expected: pack 66 287 → 5 000–16 000 ms.** The patch keeps all 288 000 iterations of index
arithmetic, which v3's own desktop control puts at 0.8–1.4 ms/frame — that is the floor, not zero.
Producer → ~45 000.

**Why it ships despite a 3× estimate band:** analyze floors at the consumer either way, so the wall
saving is **insensitive to `pack` anywhere in [3 000, 40 000]**. Robustness, not the ns/call table, is
the argument.

**Owed:** one cooled S23 run; read `pack=` off the `sample:` line (`FrameSampler.kt:291-293`).
`gateGather=` must be **unchanged** (comparability control — same file, same shape, untouched) and
`render` within 0.20 % (thermal control).

### K1 — `gateFromGathered` runs its loop over heap arrays

`analysis/FrameSampler.kt`, `work/FilterWorker.kt`

Same disease, other thread. 39 634 ms over 3 215 gate frames is 12.33 ms/frame — **~245 ns per pixel
for ~10 arithmetic operations**.

Android has no `DirectFloatBuffer`: `ByteBuffer.asFloatBuffer()` returns a `ByteBufferAsFloatBuffer`
whose `put(int, float)` is two virtual dispatches down a nine-frame chain, while `put(float[])` is one
`Memory.pokeFloatArray` memcpy (the buffer is `nativeOrder()`, so no byte swap). Same on the way in.
One bulk read in, one bulk write out, and the 50 176-iteration body becomes ordinary array code the
ART inliner can flatten and bounds-check once.

The arithmetic is **untouched** — same integer BT.601 coefficients, same `shr 10`, same `coerceIn`,
same `/255f`, same order — so the tensor is bit-identical and `FrameSamplerConvertTest`'s
zero-tolerance equivalence against `convertToTensor` still passes at all four rotations and both
chroma layouts. That test is the whole safety argument, and it is the one that already caught A4.

Scratch is **caller-owned** (`FilterWorker.gateYuv`/`gateRgb`), for `gateInput`'s exact reasons:
`FrameSampler` is an `object`, and per-frame allocation would be 752 kB × 3 215 ≈ 2.4 GB of
large-object churn.

**Expected: gateFill 39 634 → ~10 000 ms.** Consumer → ~45 400.

**Owed:** `gateFill=` on the analyze line. **Kill line is 20 000 ms, not 12 000** — at least ~30 % of
the 39 634 is thread placement and off-CPU time rather than loop body, so 12 000 is the *non-body
floor* and reverting at it would revert a change that worked. Take the baseline on a `benchmark`
(non-debuggable) build: `app/build.gradle.kts` says outright that every number in this repo came from
a debuggable build, and `android:debuggable=true` restricts the ART inliner, which is the named
mechanism here.

### C10 — the audio decoder and the AAC encoder leave the ORT thread

`audio/AudioPipeline.kt` (only; `AudioDecoder`, `AacWriter` and `DemucsSeparator` are untouched apart
from three KDoc corrections)

`AudioDecoder.stream`'s sink called `separator.feed` inline, and the separator's `emit` called
`writer.write` inline. One thread, three jobs:

```
ort     270 177 ms   70.1 %
decode   55 589 ms   14.4 %   ← blocked on MediaCodec while ORT's 6 workers were parked
encode   26 584 ms    6.9 %   ← same
```

Neither counter is arithmetic. `decode` is 1.73 ms per 20 ms Opus packet; `encode` is 3.63 ms per
16 kB buffer, and the quantize loop inside it is 57 M iterations that this run's own `gather=105ms`
prices at **under 0.9 s**. Both are codec round trips, so they hide behind inference almost entirely.

Two bounded `Channel`s and two `Dispatchers.IO.limitedParallelism(1)` coroutines. `DECODE_QUEUE = 128`
is sized to bank a whole chunk period (~882 frames per sink call × 128 ≈ 1.09 × `STRIDE`) — a shallower
queue and the consumer goes straight back to waiting on the codec at the serial build's rate.
`ENCODE_QUEUE = 2`: one encoding, one queued, 1.65 MB against a measured 1.27 GB peak.

**Byte-identical.** Nothing touches an arithmetic path: the separator still sees the same batches in
the same order from one driver, and the encoder still sees the same frame counts in the same order, so
the same `samplesOut` PTS walk writes the same `.m4a`. The per-batch copy is not optional —
`AudioDecoder` reuses `stereo`/`outFloat` and `DemucsSeparator` reuses one `emitBuf`, so neither array
survives the call it arrived in. 227 MB per side over a 10-minute job, ~1.2 MB/s.

Three things this had to get right, all of which have a failure mode worse than being slow:

1. `pcmQ.close(t)` **with the cause**. A bare `close()` reads to the consumer as a clean end of
   stream, and `DemucsSeparator.finish` would then resolve the track length from a truncated feed and
   publish a short film as a complete one (correctness item 7.5).
2. `cancel()` in both `finally`s. Scope cancellation cannot interrupt a blocking `trySendBlocking`;
   only closing the channel can.
3. `writer.finish()` on the encoder lane, `writer.close()` in the outer `finally` — separated by
   `coroutineScope`'s join, so the writer can never be closed out from under the lane.

The **film path** takes the ingress half only: `removeMusicResumable`'s `emit` is a page-cache write to
the int16 scratch (~1 s over a whole film), not an AAC encode, so there is no egress half worth moving.
Its one AAC pass runs at the end in `encodePcm`. That is worth ~13 min off a ~108 min audio branch.

**Expected: separate 385 420 → 310 000–340 000 ms, central ~320 000 (−17 %).** The band is the ORT
tax, not the mechanism: ~43 s of the 82 173 is real CPU (`long-film-followups.md:48-53` — the same
decode→`AacWriter` pair, serial and idle, was 12.9 s per 193 s track), and it now overlaps inference.
Priced at **+8 %** on ORT from this repo's own thread sweep (+2 threads ≈ +5 %). Even charging the full
A1 contention precedent (+10 % on the untouched loop, i.e. +27 018 ms) the lever is **still −55 155**.
It is positive in every world; only its size is uncertain.

**Owed:** `decode=` and `encode=` must both come back **near 0** — after this change they measure
consumer starvation and separator blocking, not decoding and encoding, so one run attributes both
halves. `ort=` must not rise above 270 177. And the `.m4a` must be **byte-identical** to the pre-change
build (the pipeline is deterministic — `DemucsSeparator.kt:506`).

### Documentation debt closed while in the files

`AacWriter`, `DemucsSeparator` and `MusicGate` each claimed **thread-confined**. After C10 they are
**serialized** — one driver, one call at a time, each call seeing the last one's writes — but the
driving thread's identity can change across a coroutine hop. That is what MediaCodec's synchronous
mode and these classes' reused buffers actually require, and `limitedParallelism(1)` plus coroutine
dispatch supply both halves. Left uncorrected, that is exactly the contradiction `perf-plan-v4` §10
records as *"why the §4 concurrency bug would survive review."*

---

## 2. Projections

**BLUR — censor-only, 643 s clip, baseline 191 327 ms serial**

| after | producer | consumer | analyze | + render | total |
|---|---:|---:|---:|---:|---:|
| today | 101 387 | 75 049 | 101 387 | 89 202 | **191 327** |
| + A2 | ~45 100 | 75 049 | 75 049 | 89 202 | 164 251 |
| + A2 + K1 | ~45 100 | ~45 400 | ~50 400 | 89 202 | 139 602 |
| **discounted (35 % haircut on the saving)** | | | **~68 300** | 89 202 | **~157 500** |

**191 327 → ~157 500 ms, −18 %** — 3.1× realtime becomes 4.1×. The haircut covers inference risk in
two of the three magnitudes, not contention: both items *remove* work from both threads rather than
moving it between them, so the +10 % A1 coupling should be neutral-to-favourable here.

**MUSIC REMOVAL — baseline separate 385 420 ms**

| after | ort | decode + encode | rest | total |
|---|---:|---:|---:|---:|
| today | 270 177 | 82 173 | 33 070 | **385 420** |
| + C10 (+8 % ORT tax) | 291 791 | 0 (hidden) | 33 070 | 324 861 |
| **band** | | | | **310 000–340 000** |

**385 420 → ~320 000 ms, −17 %.** Audio stays the wall on this shape (video branch is 204 752), so
every millisecond lands 1:1 on the job.

**Nothing here helps both shapes.** On a music-removal job the video branch has ~180 s of slack, so A2
and K1 are worth exactly 0 ms there. On censor-only, C10 is worth exactly 0 ms. That is not a defect;
it is `perf-plan-v4` §1 still holding.

---

## 3. Items that fight, or draw the same milliseconds twice

1. **A2 vs A3 (skip work inside censored spans) — same producer pool.** A2 first ⇒ A3's per-frame
   saving collapses from 10.31 ms to ~1.5 ms on a producer already 25 s under the consumer ⇒ A3 worth
   0. A3 first ⇒ A2 worth ~0 on the covered 42–53 %. A2 wins: no product risk, no recall question,
   works in every `censorWho` mode. **And A3 is separately dead** — its own gender guard is
   `genderVoter != null`, and `FilterOps.DEFAULT_WHO = WOMEN` with `genderage.onnx` bundled, so it
   skips **zero frames on a default install**.
2. **A2 vs K1 — sequenced, not double-counted.** Different threads, and they multiply: K1 alone is 0 ms
   of wall, A2 alone caps at −26 338, together −51 000 raw. This is why both shipped at once, and why
   the measurement must read `pack=` and `gateFill=` separately.
3. **K1 vs A8 (`gateEvery` 2→4)** — same 39 634 ms consumer pool, and A8 also under-censors (it lands
   in the band A4 was reverted for). Don't spend the correctness argument.
4. **C10 vs C3 (deepen the decoder feed)** — same 55 589 ms decode pool. C10 hides it entirely; C3
   would shrink it. C3 is now worth ~0 on the wall.
5. **C10 vs C4b (`AacWriter` off-thread)** — C4b *is* C10's encode half. Not two items.
6. **INT8 vs the music gate** — a skipped chunk is a chunk INT8 never speeds up. Any future skip-rate
   gain is worth 2 290 ms/chunk today but only ~1 827 ms/chunk after §4.1 lands. Re-price after.

---

## 4. Next, in order

### 4.1 MatMul-only INT8 htdemucs — the largest single remaining item, and it is **measured**

**−54 577 ms of ORT (270 177 → 215 620) and −14.8 % peak RSS.** Not an estimate: a verifier built the
artifact from the shipped graph and benchmarked it against it on arm64/ORT 1.27 — **431.4 → 344.3
ms/chunk = 1.253×**. `DynamicQuantizeMatMul` is present in the shipped
`jni/arm64-v8a/libonnxruntime.so`. The RSS drop is the 201 `InsertedPrecisionFreeCast` nodes and the
fp32 demotion going away.

`perf-plan-v4` §6.1 parked this at 1.19× on host SNR alone. The recipe: de-fp16 the graph (exact,
storage-only), then

```python
quantize_dynamic(src, dst, op_types_to_quantize=["MatMul"], per_channel=True,
                 weight_type=QuantType.QInt8)
```

Conv/ConvTranspose stay fp32 — `ConvInteger` has no fast ARM path, which is why every Conv-touching
variant measured **0.44–0.55×**, i.e. slower than doing nothing.

**Not shipped here, deliberately.** This one changes the audio the user hears, and the repo's own gate
for a change of that class is a listening test. Three practical corrections to fold in when it lands:

- the artifact is **~92.4 MB, not 87.8** (+4.5 MB);
- do **not** keep the fp16 alongside it in `assets/` — ~180 MB of models against a ~134 MB AAB;
- renaming `assetName` **strands the old 87.85 MB in `filesDir/models` forever** —
  `ModelDownloader.installed()` is a bare `length() > 0` with no cleanup of superseded names.

**Owed, in order:** one cooled S23 music-removal run (`ort` ≤ 220 000, peak RSS ≤ shipped,
`nonFinite == 0`), **then the listening test on quiet passages of `qa-assets/women-music-3min`.**
Composed real-audio SNR is 41–63 dB per channel and no dB number decides hiss on a vocals stem. The
listening test *is* the gate, not a formality after it.

### 4.2 Overlap analyze and render — the only lever left that is bigger than everything above combined

Ceiling **~102 400 ms total for a censor-only job (−46 %)**, against ~157 500 after §1. Render is
89 202 ms and immovable on its own (see §5), so the only way past it is to stop running it *after*
analyze.

`perf-plan-v4` §7 killed the "one-segment lag" because `Edl.mergeRanges` bridges 400 ms
**transitively** before the `MIN_FULL_MS` filter, so a lagged schedule cannot reproduce the global EDL.
That refutation was aimed at *segment* granularity. **The horizon proof survives all three skeptics:**
the transitive bridge has a bounded lookahead, so everything older than the horizon is final and can be
rendered while pass 1 is still running.

Build the **12-line throwaway spike first**, `BuildConfig.DEBUG_HOOKS`-gated, returning `fail()`:
render a forced full-frame EDL *concurrently* with the real pass 1 and log `analyzeDone=` and `wall=`.
Render cost is EDL-independent to ±0.2 %, so this measures the only open question — can the two stages
share the SoC at all. **Decision rule, fixed in advance:** `analyzeDone ≤ 115 000` **and**
`wall ≤ 150 000` ⇒ build it; `wall ≥ 175 000` ⇒ record it dead in §9 and stop.

Four correctness fixes are mandatory before the real thing, and the first is severe:

1. **`RenderPipeline.kt:112` computes `passthrough` once, before `start`, against the EDL it was
   handed.** A mechanical `edl: () -> Edl` conversion evaluates it against an EMPTY live EDL and
   **publishes the source uncensored.**
2. The horizon is **2450 ms, not 2050** — a live track's span grows on its own, so `MIN_FULL_MS` and
   `EVICT_AFTER_MS` compose. Counterexample verified independently by all three skeptics.
3. Guard `censorWho == EVERYONE`: the per-track gender vote is non-monotone mid-track, so a track can
   flip toward *less* censoring after frames have already been rendered.
4. Guard `!removeMusic`: audio is the wall on that shape, and this would steal cores from it.

### 4.3 Music-gate far tier — bounded at ≤ 62 s, and costs **zero lines** to price

Do not ship counters. Set `DemucsSeparator.kt:609` `DILATE2_MIN_SCORE = 2f` on a throwaway build and
read the **existing** `music gate: skipped` line: at any value ≥ `THRESHOLD` the far tier is vacuous,
so `skipped_new − 158` is the far-tier count exactly, and that run's own `ort=` prices the lever
end-to-end rather than inferring count × mean.

Correcting the lane's own bracket: far candidates ⊆ {run_start−2, run_end+2}, so |far| ≤ 2 × runs;
the true ceiling is ≤ 34 chunks ≈ **≤ 62 s after §4.1**, and it is purchasable only by deleting the ±2
dilation tier that exists for fades and stings — with a listening test *already owed* for the smaller
0 → 0.02 move that shipped in v4.

---

## 5. Dead this round, with the reason

| item | why |
|---|---|
| **A8 / `gateEvery` 2→4** | 0 ms of wall (§0), and it also halves `gatherGate`, so even at the binding point it is −7 338, not −32 540. Still under-censors. |
| **A3 — skip work in censored spans** | Skips **zero frames on a default install**: its own guard is `genderVoter != null` and `DEFAULT_WHO = WOMEN` with the model bundled. Also fights A2 (§3.1). |
| **ML Kit / detect / FaceTracker / `minFaceSize`** | 100 % consumer-side against 26 338 ms of slack. Self-refuted by its own investigator. |
| **ORT session knobs (C5, C8, arena)** | `ORT_PARALLEL` profiles all 3 576 nodes onto **one tid** — +13.7 % pure overhead, because `DeviceBasedPartitioner` splits by device, not by graph topology. `dynamic_block_base` measured +1.70 % against a 0.10 % control. **No arena knob exists in the ORT Java API** at all. Self-refuted. |
| **Render (B5, concurrent graphs, tone-map skip)** | Two fatals. The "open" route needs `runSegmented`, which fires `concatAudio` → a **transcode for Opus** (~44.6 s, the entire claimed ceiling) plus a ~158 MB `Remux.concat` the unsegmented path never pays. And media3 1.10.1's `DebugTraceUtil` keeps only the first and last 10 events per (component, event), so the designated tiebreak cannot produce per-frame data at all. Perfetto/atrace is the only instrument. |
| **`flush`/`feed` modulo removal, `KEY_MAX_INPUT_SIZE`** | Both under 0.3 s. `encode`'s 26.6 s is 96–99 % `feedEncoder`, not the quantize loop — 57 M iterations of a load/round/coerce/2-store body cannot be 26.5 s, and this run's own `gather=105ms` prices an equivalent per-sample loop at 6.9 ns/iter. |
| **Real FFT in `Stft`** | 0.90 % of the audio wall for the 45 most delicate lines on the board — and all three skeptics independently found the same defect in the *proposed* code: feeding `ai` at k=0 into the untangle folds `Im(X[0])` into a 22.05 kHz component on every sample (−20 dB on a probe). No existing test can see it: `stft_small.json` has bin-0 imag exactly 0.0 across all 258 frames and `SpecFake` returns a scalar multiple of the forward's own output. Ranked last, correctly. |

---

## 6. The honest ceiling

**Blur floors at ~137 000 ms serial.** Producer bottoms at the hardware decoder's 19 942 + ~4 000 of
post-bulk `gatherGate` ≈ 24 000. Consumer bottoms at detect 9 969 + NSFW ORT 25 446 + `gateFill`'s
irreducible body ~12 000 ≈ **47 400** — so analyze ends up *consumer-bound*, which is the exact
inversion §0 says has not happened yet. Render is 89 202 and stays there: 76 % of its 4.702 ms/frame
is unattributed, a large part of that is the Opus→AAC transcode `runCensorOnly` pays via
`setRemoveAudio(false)`, and the only structural attack is fatally refuted above.

**To go below 137 000 you must overlap analyze and render (§4.2).** Nothing else on the board reaches
it.

**Music floors at ~250 000 ms.** ORT 215 620 post-§4.1, plus the ~33 070 ms of DSP, flush and gate that
no scheduling change touches. Below that needs **a different graph, not a different schedule**: the
remaining ORT is Conv/ConvTranspose-dominated (47.6 % of 91.96 GFLOP), `ConvInteger` has no fast ARM
path, `hdemucs_mmi` ceilings at 1.51× for −0.45 dB SDR with a serial BiLSTM the CPU EP cannot spread,
and `SEG` is already at its measured optimum. The only other route is a higher skip rate, and §4.3
bounds that entire remaining upside at ≤ 62 s.

---

## 7. Protocol

`perf-plan-v3` §7 verbatim, plus one addition earned this round: **read the counter the item names,
not the stage wall.** Every item in §1 has a named counter (`pack=`, `gateFill=`, `decode=`,
`encode=`) and a named control (`gateGather=`, `render`, `ort=`). Analyze drifted 31 % across three
runs in one v3 session and 17 % uniformly in v4 §11; stage wall cannot resolve any of these.

Build gate is unchanged: `./gradlew compileDebugKotlin testDebugUnitTest` — **130 tests green** (129
existing plus the new plane-position guard). `lintDebug` still has 31 pre-existing errors and is still
not a gate.
