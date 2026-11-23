# M0 results — Apple port de-risk

Measured on **iPhone 17 Pro simulator** (iOS 26.5, Xcode 26.6, Swift 6.3.3) on an Apple Silicon Mac,
via `naqiTests/BenchTests.swift` and `naqiTests/ModelContractTests.swift`. Reproduce with:

```
scripts/fetch-models.sh
xcodebuild test -project naqi.xcodeproj -scheme naqi \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath build/DD
```

> **Simulator ≠ phone.** The simulator runs arm64 code on the Mac's CPU, so these are effectively
> *M-series Mac* numbers, and there is **no Apple Neural Engine** — the CoreML rows exercise the
> CPU/GPU partition only. They bound the problem and prove the code paths; the acceptance numbers in
> M7 must be re-taken on a real iPhone.

## Decision 1 — inference runtime → **ORT 1.24.2 via SPM, CPU EP for v1**

`https://github.com/microsoft/onnxruntime-swift-package-manager` at `1.24.2`, product `onnxruntime`
(static, ObjC bindings module `OnnxRuntimeBindings`). Ships an iOS-simulator arm64 slice; builds
clean under Swift 6 strict concurrency.

The CoreML EP **is available on the simulator** and is wired up behind `ComputeUnit.coreML`
(`naqi/ML/Ort.swift`), using `createMLProgram = true` and `onlyAllowStaticInputShapes = true`. It is
not the default because on CPU the graphs are already far past parity and the CPU EP is the path
that matches Android numerics exactly. Flipping the default is a one-line change once a device
measurement justifies it.

**No coremltools conversion is needed for v1.** That was the expected big lift; the measurements
below removed the need for it.

## Decision 2 — the fp16 NaN risk → **did not fire**

`htdemucs_s26_f16.onnx` carries 552 fp16 initializers with fp32 graph IO. Android's fp16 *execution*
path produced NaN. On Apple ORT the runtime up-casts and output is finite — verified not just on a
zero tensor (which can pass through a graph that still NaNs on signal) but on a 440 Hz tone:

```
htdemucs: fp16 weights produce finite fp32 output — PASSED
  out_wave [1,4,2,114660]  all finite, non-silent
  out_spec [1,4,4,2048,112] all finite
```

No fp32 re-export required. The `.onnx` artifacts ship unchanged from Android.

## Measurement method — read this before trusting any number below

Early runs in this document were wrong, and the way they were wrong is worth recording. Wall-clock
timings taken while other work runs measure the machine, not the code: the *same* htdemucs segment
read **617 ms** idle and **4231 ms** with parallel builds going. Two contributors, both invisible
unless looked for:

1. Parallel `xcodebuild` processes, and Swift Testing's own cross-suite parallelism running the
   heavy media suites simultaneously. Fixed with `-parallel-testing-enabled NO`.
2. **Spotlight indexing the build directory** — `mds` was burning 200 % CPU on derived data and the
   88 MB models. Fixed by moving output to `build.noindex/`, which Spotlight skips.

`BenchTests` now reports **min-of-N** rather than a single sample: contention can only ever make a
sample slower, so the minimum is the closest thing to true cost a shared machine can give. Numbers
below are min-of-3 (htdemucs) / min-of-5 (gate) at load < 4.

## Decision 3 — htdemucs throughput → **6.5x the Android baseline, on the CPU EP**

2.6 s segment, `Ort.computeThreads` intra-op threads, settled machine:

| provider | session ms | best infer | x-realtime | finite |
|---|---|---|---|---|
| **ORT CPU EP** | 482 | **728** | **3.57x** | yes |
| ORT CoreML EP | 23 251 | 997 | 2.61x | yes |
| *(S23 baseline)* | — | — | *0.55x* | — |

`x-realtime > 1` means faster than playback. **3.57x vs 0.55x is 6.5x the Android throughput**, and
the audio wall — dominant on two of the three job shapes — is therefore not the problem on Apple
that it is on Android. The PRD's Core ML/ANE spike is **deferred**: it was gated on "only if the
measured wall demands it", and it does not.

**The CoreML EP is slower here, and expensive to enter.** 997 ms vs 728 ms of inference, after
**23 seconds** of graph compilation at session creation. That compile is a one-off on device (Core ML
caches it), but the inference regression is not, and there is no ANE on the simulator to redeem it.
Keeping the CPU EP as the default is now a measurement, not a preference.

## Decision 4 — NSFW gate batching → **no, it does not help. Shipped at batch 1**

The graph has a **dynamic batch dimension** (`input['unk__615',3,224,224]`) that Android never
exploited, and batched inference is bit-identical to single-frame (verified to 1e-4 on four distinct
inputs). It still does not pay:

| batch | best ms | ms/frame |
|---|---|---|
| 1 | 9.5 | **9.50** |
| 2 | 19.4 | 9.68 |
| 4 | 33.5 | 8.38 |
| 8 | 80.6 | 10.07 |

Flat, within noise, and *worse* at 8 — independently reproduced by the analyze pass's own sweep
(11.87 / 12.60 / 12.63 / 12.32 ms/frame at 1/2/4/8). This reproduces Android's finding rather than
escaping it.

> **This corrects an earlier claim in this document.** A first pass reported "27 % less time per
> frame at batch 8". That was measured at **1 intra-op thread** on a contended machine — a single
> frame cannot saturate the cores, so batching flattered itself, and the advantage vanishes once the
> session is threaded correctly. The gate ships at **batch 1**; the batching mechanism is kept
> because it costs nothing, but it is not counted as a win until hardware says otherwise.

## Memory — htdemucs costs +842 MB resident

| | |
|---|---|
| before load | 260 MB |
| htdemucs loaded + one inference | 1102 MB |
| **delta** | **+842 MB** |

Android measured **1.30 GB** for the same 2.6 s segment, so Apple is leaner on the delta.

> ### CORRECTION (M7): the absolute is a real number, and the headroom is smaller
>
> This section originally said `phys_footprint` on the simulator "reports the host process", so
> "only the delta is trustworthy". **That is wrong.** `MemoryFootprint.current()` calls
> `task_info(mach_task_self_, TASK_VM_INFO, …)` — `mach_task_self_` is the *calling* task, and a
> simulator app is its own native macOS process. It reads that process's own footprint, not the
> Mac's and not the host tooling's.
>
> The measurement that settles it: on the **same** 12.8 s clip, a censor-only job peaked at
> **471 MB** and a music-only job at **1629 MB**. A figure that tracks which models were loaded by
> 3.5× is reporting this app's allocations. An ambient host reading could not do that.
>
> What stays true is narrower: a simulator process is not a phone. Different framework set,
> different allocator behaviour, x86-free but still macOS. So the absolute **bounds** the device
> figure rather than predicting it — but it is a real, workload-driven number, and treating it as
> unreadable hid a budget violation for a whole milestone.
>
> **Consequence.** Read as an absolute, 1102 MB of the 1536 MB budget leaves **434 MB** for the
> video pipeline, not the ~694 MB this section inferred from the delta. And the music-only job's
> measured 1629 MB peak is already **over** budget — see `docs/apple-port/m7-perf-results.md`.

`ModelRegistry.evict(_:)` exists so the arena is released once a job ends rather than held while the
user browses.

## Graph IO — dumped from the shipped artifacts, not from docs

| model | opset | inputs | outputs |
|---|---|---|---|
| `nsfw_mnv2_140_f32` | 17 | `input[N,3,224,224]` f32 | `prediction[N,5]` f32 |
| `genderage` | 12 | `data[N,3,96,96]` f32 | `fc1[1,3]` f32 |
| `htdemucs_s26_f16` | 18 | `input[1,2,114660]` f32, `x[1,4,2048,112]` f32 | `out_spec[1,4,4,2048,112]`, `out_wave[1,4,2,114660]` f32 |

sha256 matches the Android reference for all three (`df8a2c2c…` htdemucs, `049ce7c5…` NSFW).

## Two bugs the M0 tests caught

1. **Concurrent `CreateSession` on one graph segfaults ORT.** `ModelRegistry` now holds its lock
   across construction, not just the dictionary access. Loading htdemucs twice is not a slow path —
   it is an OOM kill on a phone.
2. **`AVAssetReader.addOutput:` raises an uncatchable ObjC exception** when the output's track came
   from a different `AVAsset` instance. `TrackReader` now derives the asset from the track, so the
   mismatch is unrepresentable.

## Open / carried forward

| item | status |
|---|---|
| Q1 device floor (4 GB iPhone RAM) | **open** — needs a device. `phys_footprint` on the simulator reports the host process and is not a phone number; only the delta is meaningful. Instrumentation is in place (`naqi/Core/MemoryFootprint.swift`). |
| htdemucs peak RAM vs the 1.5 GB budget | **open** — same reason. The 2.6 s segment was chosen on Android precisely to clear it (1.30 GB there). |
| Vision vs ML Kit recall/track continuity | in M3; findings go to `vision-tuning.md` |
| MKV/Opus ingest | **not started** — AVFoundation cannot demux Matroska; the drop-in-v1 vs embedded-demuxer decision is still open |
| Photos picker hands over originals, not transcodes | **not started** |
| Deployment floor set to iOS 18.0 / macOS 15.0 | provisional, pending Q1 |
