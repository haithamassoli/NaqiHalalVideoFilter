# M7 — performance and memory, measured

> **Headline:** on an M-series Mac — real VideoToolbox codecs, Vision on its own compute device —
> the port runs the Android baseline clip **1.50× faster than the S23 overall and 2.55× faster on
> analyze**, at 234 MB peak.
>
> On the **iPhone 17 Pro simulator** the same clip runs **5.4× slower**. That difference is the
> hardware the simulator does not have, and §2 is the evidence rather than the assertion. Anyone
> quoting a number from this document must say which row it came from.

## Summary — three platforms, one clip

| stage | **M-series Mac** | ×real | S23 | ×real | Mac vs S23 | iPhone 17 Pro **simulator** |
|---|---:|---:|---:|---:|---:|---:|
| analyze | **45 018 ms** | 14.28× | 114 648 ms | 5.61× | **2.55×** | 337 172 ms (1.91×) |
| render | **91 106 ms** | 7.06× | 89 411 ms | 7.19× | 0.98× | 767 335 ms (0.84×) |
| **total** | **136 124 ms** | **4.72×** | 204 752 ms | 3.14× | **1.50×** | 1 104 507 ms (0.58×) |

| | Mac | simulator |
|---|---:|---:|
| peak footprint, censor-only | **234 MB** | 472 MB |
| detect failures | **0** | 6 |
| face tracks found | 97 | 94 |

Read the two interesting rows carefully:

- **Analyze is the architectural win.** 2.55× the S23, and 7.5× the simulator. Vision on a real
  compute device beats ML Kit on the S23's; the simulator's `pinned to CPU` fallback was hiding it
  entirely.
- **Render is at parity, not ahead** — 0.98×, despite the Mac's enormous power and thermal advantage
  over a phone. Both sides are hardware-encoder-bound, so this stage is roughly a hardware draw and
  the Mac's headroom buys almost nothing. That is the honest reading, and it is the number most
  likely to get *worse* on a passively cooled phone.

**An M-series Mac is not a phone.** It has far more sustained power and cooling than an iPhone, so
1.50× is an upper bound on what a device will do, not a prediction. What it does establish — which
the simulator could not — is that the port's architecture is sound and competitive on real silicon.

## 0. What was run

`qa-assets/tv1-h264.mp4` — the **same clip** Android published its numbers against
(`docs/perf-plan-v3.md` §0): 643.0 s, 1920×1080, **29.97 fps**, H.264 + AAC. Transcoded from the
repo's `test-video-1.webm` (AV1 + Opus) because AVFoundation cannot demux WebM.

Censor-only, default options, via `BenchTests.tv1EndToEnd`, **Release configuration**.

## 1. The number

| stage | Apple (Release) | ×realtime | S23 | ×realtime | ratio |
|---|---:|---:|---:|---:|---:|
| analyze | 337 172 ms | 1.91× | 114 648 ms | 5.61× | **0.34×** |
| render | 767 335 ms | 0.84× | 89 411 ms | 7.19× | **0.12×** |
| **total** | **1 104 507 ms** | **0.58×** | **204 752 ms** | **3.14×** | **0.19×** |

Per frame: analyze **52.4 ms** per sampled frame against the S23's 17.8 ms; render **39.8 ms** per
frame against **4.6 ms**.

## 2. Why this is not a verdict on the port

### 2.1 The Debug→Release delta localizes the cost, and it is not in our Swift

The first run of this benchmark was accidentally a **Debug** build. That mistake turned out to be
the most informative measurement of the session, because comparing the two says *where the time
lives* without a profiler:

| stage | Debug (`-Onone`) | Release (`-O`) | improvement |
|---|---:|---:|---:|
| analyze | 410 932 ms | 337 172 ms | 18 % |
| render | 776 254 ms | 767 335 ms | **1.2 %** |

Turning off every Swift optimization changes the render pass by **1.2 %**. Our per-frame Swift is
therefore ~1 % of render; the other ~99 % is inside Core Image and the H.264 encoder. Analyze gains
18 %, so the sampler and the gate kernel are real but minor next to Vision itself.

**Optimising this port's Swift cannot move these numbers.** That is a measurement, not an excuse.

### 2.1b …and the Mac run confirms it directly

The Debug→Release delta said the cost was not in our Swift. The Mac run says where it *was*: the
same binary, the same clip, **7.5× faster analyze and 8.4× faster render** purely from running
where the hardware exists. Nothing in this port changed between those two rows.

### 2.2 Both dominant costs are paths the simulator cannot accelerate

- **No hardware video encoder or decoder.** Render is 19 271 frames of 1080p encoded in software.
  The S23's 4.6 ms/frame is its hardware encoder.
- **Vision is pinned to CPU.** The log carries `Vision default compute device cannot run here;
  pinned to CPU` every run — the simulator cannot build a GPU inference context
  (`Code=9 "Could not create inference context"`). Android's ML Kit ran on GPU/NNAPI.

These are the two stages, and they are exactly the two things an A19 Pro has dedicated silicon for.

### 2.3 Directions the comparison is unfair, both ways

Flattering Apple: an M-series Mac, not a phone; AV1→H.264 is cheaper to decode.
Penalising Apple: no hardware codec; Vision on CPU; Android ran `censorWho=everyone`, which skips
the gender vote entirely, so this run does strictly more work per track.

### 2.4 Contamination, disclosed

A stale `musicOnly` row in the persisted queue drained inside the test host and ran **11 s during a
767 s render** (11:27:58→11:28:09). Under 1 % of wall time, so the timing stands; it did load
htdemucs into the process, so that run's 2209 MB peak is **not** a censor-only figure and is not
used anywhere below.

## 3. Memory — one clean pass, one real violation

`phys_footprint` is this app's own task, not the host's. See the CORRECTION in `m0-results.md`; the
old "only the delta is trustworthy" note is what kept the violation below invisible for a milestone.

| workload | peak | budget | |
|---|---:|---:|---|
| censor-only, 643 s @ 1920×1080 | 472 MB | 1536 MB | ✅ comfortable |
| music separation, 12.8 s clip — **before** §3.2 | 1721–1881 MB | 1536 MB | ❌ was over |
| **music separation, 12.8 s clip — after** | **1115 MB** | 1536 MB | ✅ **fixed** |

Note the shape: the *90-minute* censor job peaks lower than a *12.8-second* music job. Footprint
tracks frame area and model working set, not duration — which is why `m5-soak-results.md`'s 236 MB
proves no per-segment leak but says nothing about the budget.

### 3.1 Fixed: 1639 MB was being retained after the job

`m0-results.md` said "`ModelRegistry.evict(_:)` exists so the arena is released once a job ends
rather than held while the user browses." **It was never called from the job path** — the only
app-side caller was `AboutScreen`. So every music job left htdemucs resident for the life of the
process:

```
still held after separation 1639 MB → after evict 59 MB   (gave back 1580 MB)
```

`JobRunner.separate` now evicts on a `defer`, so a cancelled or failed job releases it too.
Guarded by a live assertion in `BenchTests.demucsFootprint`.

### 3.2 FIXED: the arena shim — 1774 MB → 1115 MB, and 43 % faster

**Resolved.** `naqi/ML/NaqiOrtArena.mm` makes the two C++ API calls ORT's Objective-C wrapper omits,
`DisableCpuMemArena()` and `DisableMemPattern()`, and applies them to **htdemucs only** — keyed on
the graph inside `ModelRegistry.model` rather than passed by callers, because a parameter is one
call site away from being forgotten and the symptom of forgetting is a jetsam kill.

| | before | after |
|---|---:|---:|
| peak during separation | 1721–1881 MB | **1115 MB** ✅ under the 1536 MB budget |
| retained after separation | 1639 MB | 1005 MB (→ 72 MB after evict) |
| separation, 4 threads (shipped) | 6 323 ms | **3 603 ms** |
| separation, 1 thread — clean baseline both runs | 9 196 ms | **7 423 ms** |

**It got faster, not slower** — 43 % at the shipped thread count, 19 % on the clean like-for-like
single-thread comparison. That is the opposite of the expected trade, and it is explicable rather
than lucky: holding ~1.8 GB costs page faults and memory compression that outweigh what the arena's
reuse saves. Below a certain footprint, less memory *is* speed.

Applied to htdemucs alone on purpose. The arena is exactly what makes repeated allocation cheap, and
nsfw/genderage run thousands of times per job where htdemucs runs once per chunk — those would
likely pay for losing it, and they are not the graphs breaking the budget.

Three implementation notes, each of which cost a build:

- **`@import` is unavailable in Objective-C++ here** (`-fno-cxx-modules`) and the ObjC bindings'
  headers are not on the app target's include path, so the private accessor is reached with a typed
  `objc_msgSend` cast rather than a category. Safety rests on `respondsToSelector:`, not on the
  cast: if ORT drops the accessor the shim returns NO, the session is still valid and correct, and
  the log says so.
- **`FOUNDATION_EXTERN` is load-bearing.** Without `extern "C"` the ObjC++ definition is C++-mangled
  while Swift's bridging-header import wants the plain C symbol. The only symptom is
  `Undefined symbols: _NaqiOrtDisableArena` at link — *after* the `.o` has compiled cleanly.
- `SWIFT_OBJC_BRIDGING_HEADER` had to be added to both app-target configurations. Synchronized
  groups compile the `.mm` automatically but cannot supply a build setting.

The `withKnownIssue` wrapper is **gone**: it failed the moment the expectation started passing,
which is exactly how it was meant to announce the fix. `demucsFootprint` is now a live budget gate.

### 3.2b Why it took a shim — the history, kept because the reasoning still holds

Eviction cannot touch this — it is the working set, not retention. Neither is `Demucs.seg` a lever:
114 660 frames is the graph's own segment length.

Android hit the same wall on the same graph and needed `DisableCpuMemArena` **and**
`DisableMemPattern`; without them lmkd killed it at 5.6 GB RSS. ORT's Objective-C wrapper exposes
neither — `ort_session.h` offers only `addConfigEntryWithKey`, `setIntraOpNumThreads`,
`setGraphOptimizationLevel` — so the fix is the C-API shim `Ort.swift` already names in its
`ponytail:` comment.

Recorded as a **`withKnownIssue`**, not a skipped test: it fails if the peak ever comes *under*
budget, so whoever lands the shim is told to delete the wrapper.

#### The access path, so it does not have to be rediscovered

Verified against the pinned artifact rather than assumed:

- `DisableCpuMemArena` and `DisableMemPattern` are C API **functions**
  (`ORT_API2_STATUS(...)`, `onnxruntime_c_api.h:1375,1393`). They are **not** session config keys —
  the full list in `onnxruntime_session_options_config_keys.h` has no arena entry — so
  `addConfigEntryWithKey` can never reach them, however it is spelled.
- The package's **`objectivec/ort_session_internal.h`** declares a private category:
  `- (Ort::SessionOptions&)CXXAPIOrtSessionOptions`. That header is not in the public `include/`,
  but the method is compiled into the ObjC target, so an `.mm` in the app can re-declare the
  category and call `DisableCpuMemArena()` / `DisableMemPattern()` on the C++ object. ~20 lines.

**Why it was not done in the session that found it**, so the next person can weigh the same things:

1. It needs Xcode project surgery — a bridging header (`SWIFT_OBJC_BRIDGING_HEADER`) and an `.mm`
   with correct target membership. Synchronized groups add Swift files automatically; build
   settings are hand work.
2. It depends on a **private API of a third-party package**. Acceptable — it breaks loudly at link
   time on an ORT upgrade, not silently — but it is a real dependency to take on deliberately.
3. **The benefit is unverified on Apple.** Android needed both flags on this graph; nobody has shown
   they recover the ~240 MB here. Measure first with a throwaway build before wiring it in.

#### The cheap lever was tried, and it does not work

ORT's memory-pattern planner allocates per intra-op thread, so fewer threads *should* mean a smaller
peak. Swept on the Mac (`BenchTests.demucsThreadSweep`, budget 1536 MB):

| intra-op threads | wall | peak | |
|---:|---:|---:|---|
| 1 | 9 196 ms | **1838 MB** | ❌ over — and this is the **clean-baseline** run |
| 2 | 6 867 ms | 1815 MB | ❌ over (cumulative — see below) |
| 4 (shipped) | 6 323 ms | 1881 MB | ❌ over (cumulative) |

**Disproven.** The spread is ~3.5 % and **not monotonic** — 2 threads is the lowest, 4 the highest,
1 in between — which rules out per-thread scratch as the driver. Meanwhile 1 thread costs **45 %**
more wall time than 4. Fewer threads buys nothing and is expensive.

Only the first row has a clean baseline; the suite is `.serialized`, so rows 2 and 3 inherit the
previous run's retention and their absolutes are inflated (the test prints ⚠ when it detects this).
That does not weaken the conclusion: the **minimum** thread count, measured clean, is 1838 MB —
still ~300 MB over. There is no thread setting that gets under the budget.

That the peak is flat across thread counts is itself evidence for the arena hypothesis: the memory
is sized by the graph and its allocation pattern, not by how many workers walk it. The shim is the
remaining lever.

**Risk if unfixed:** the PRD's ceiling exists for the 4 GB device floor (open question Q1). A
1.77 GB peak on a 4 GB iPhone is a plausible jetsam kill, and jetsam gives no warning.

## 3.5 The one algorithmic lever, and why it was not pulled

Every frame is decoded and re-encoded, including the ones needing no censor: the "skip" path
(`sink.append(src, …)`) skips the *blur*, not the encode, because `AVAssetWriter` cannot copy a
compressed sample through mid-stream.

So there is a real optimisation available — split the timeline at keyframes, passthrough-remux the
uncensored ranges, re-encode only the censored ones, concat. The machinery already exists
(`Remux.concat`, the segment path).

**It would have saved ~10 % on this clip**, because analyze censored **17 261 of 19 271 frames
(89.6 %)**. It only pays on sparsely-censored content, it can only cut on keyframe boundaries, and
it buys those seams the costs in hazards 11–13. Not worth it on this evidence; worth revisiting if
device numbers show render still dominating on lightly-censored sources.

## 3.6 Parity on the Mac, and one thing it disproved

Full suite on `platform=macOS`: **115 tests / 12 suites pass** at `-O`. The count ladder is
121 (iOS Debug) − 3 `#if DEBUG` UI tests = 118 (iOS Release) − 3 iOS-only `ExtensionTests` = 115.

Three tests had to be fixed first, and the bug was in the tests: they treated `ShareInbox.container`
as a nil check. On macOS that URL **resolves** without the App Group entitlement — the build scopes
`CODE_SIGN_ENTITLEMENTS` to `[sdk=iphone*]` on purpose — and only the write fails, with EPERM. The
guard is now a write probe that still reports an iOS entitlement regression as a failure.

**Hazard 14 is simulator-specific.** The Vision `kVTImageRotationNotSupportedErr` that killed a job
fired 6 times on the simulator and **zero** times on the Mac over the identical clip. It is the
CPU-pinned Vision path, not the content. The `DetectFailures` tolerance stays — a device can still
fail transiently under thermal or memory pressure, and the cost of being wrong is a dead 90-minute
job — but nobody should go hunting for it on hardware.

Face tracks: 97 on the Mac against 94 on the simulator. Partly the 6 dropped frames, partly that
CPU and GPU Vision do not have to agree exactly. Not a parity failure, but the reason two runs of
"the same" analyze differ.

## 4. What still needs hardware

Everything in §1, and the device half of §3. A phone-to-phone claim needs the phone; the simulator
bounds the problem and proves the paths, which is what it is good for. The two M7 exit criteria that
say "needs hardware" are unchanged by this document.

## 5. How to re-run

```bash
xcodebuild -scheme naqi -configuration Release -destination 'platform=iOS Simulator,id=<udid>' \
  -derivedDataPath build.noindex ENABLE_TESTABILITY=YES build-for-testing

D=$(xcrun simctl get_app_container <udid> com.haithamassoli.naqi data)
cp qa-assets/tv1-h264.mp4 "$D/Documents/bench-tv1.mp4"

xcodebuild test-without-building -scheme naqi -configuration Release \
  -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath build.noindex \
  "-only-testing:naqiTests/BenchTests/tv1EndToEnd()"
```

Two traps, both hit while producing this document:

- **`-configuration Release` is not optional.** The app's `-naqiScreen` harness is `#if DEBUG`, so
  driving the app binary measures `-Onone` and reports ~5.8× slower than Android for no reason but
  the compiler flag.
- **The `()` on the test identifier is not optional.** Without it — and with three other gating
  mechanisms that were tried first — the run reports `Test run with 0 tests in 1 suite passed`,
  which is indistinguishable from a benchmark that ran and succeeded.

The staged clip **is** the opt-in, and the test deletes it on the way out. Leaving it in place makes
every later full-suite run take 25 minutes instead of 2.5, with nothing in the output saying why.
