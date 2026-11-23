# Apple on-device inference runtime — porting spec

Scope: the inference runtime strategy for the Swift port of `NaqiHalalVideoFilter`. Everything below is
either quoted from the shipped Kotlin with a `file:line` citation, quoted from the Android `docs/`
(treated as ground truth), or **measured on this machine today** with a harness checked in under
`docs/apple-port/bench/`.

Measurement host: **Apple M3, 4 P-cores + 4 E-cores, 24 GB, macOS 26.6 (25G72)**, Xcode 26.6,
Swift 6.3.3. Runtime under test: **ONNX Runtime 1.24.2**, the exact `macos-arm64_x86_64` slice the
Swift app links via SPM — not a pip wheel. Simulator runs: iPhone 17 Pro, iOS 26.5 (23F77).

---

## 0. DECISION

### Build in v1

| # | Decision | Basis |
|---|---|---|
| D1 | **ONNX Runtime via SPM, pinned `1.24.2`.** Link the `onnxruntime` product **only**. Do **not** link `onnxruntime_extensions`. | §1. Extensions framework `Info.plist` has no `MinimumOSVersion` → App Store validation failure (§1.5). We use no custom ops. |
| D2 | **htdemucs runs on the CoreML EP, `MLComputeUnits=CPUAndGPU`, `ModelFormat=MLProgram`, `RequireStaticInputShapes=1`.** | §4. Measured **128.8 ms/chunk vs 554.8 ms on the best CPU config — 4.31×**, 18.2× realtime. |
| D3 | **Ship the fp32 htdemucs graph, not the fp16 one**, if the +85 MB can be paid (see D8). | §3.4. fp32-on-GPU is 160.2 ms (14.6× realtime, still 3.5× over CPU) at **86.6/112.5 dB** parity vs **46.3/61.1 dB** for fp16-on-GPU. |
| D4 | **`ModelCacheDirectory` is mandatory** for htdemucs, in Application Support, excluded from backup. | §4.4. Cold CoreML compile is **33–72 s**; warm is 10–24 s. Without it every session create pays the full compile. |
| D5 | **Never `MLComputeUnits=ALL` for htdemucs.** | §4.3. ANE compilation **fails** (`ANECCompile() FAILED`); `ALL` wastes the attempt and lands 2.3× slower than `CPUAndGPU`. |
| D6 | **NSFW gate: ship the fp32 graph with the batch dim frozen to 1, run on CoreML EP `ModelFormat=NeuralNetwork`.** | §5.2. **0.667 ms** vs 2.27 ms for Android's INT8-on-CPU choice — Apple **inverts** the Android decision. |
| D7 | **genderage + YAMNet: XNNPACK EP, `intra_op_num_threads=4`, batch frozen to 1.** | §5.3. 0.263 ms and 1.50 ms. CoreML's 120–1000 ms compile is not repayable at these run times. |
| D8 | **Bundle all models in the app** (no ODR, no Background Assets) for v1. | §6. ~122 MB (fp16 htdemucs) / ~207 MB (fp32) against a **4 GB** cap. ODR is deprecated; Background Assets is the v2 lever — note ~207 MB crosses the **200 MB cellular-download** threshold, which is the real argument against D3. |
| D9 | **CPU EP is the correctness reference and the simulator path.** Gate CoreML behind a runtime check that disables it on the simulator. | §2.4. CoreML EP on the simulator loads but is **2–5× slower** than CPU and throws `Espresso ... MpsGraph backend validation on incompatible OS`. |

### Defer

| # | Deferred | Why | Revisit when |
|---|---|---|---|
| F1 | coremltools → `.mlpackage`, run via Core ML directly | Needs a **PyTorch re-export** — coremltools 9 has no ONNX converter (§4.5). Weeks of work to re-derive a validated artifact. | If CoreML-EP compile latency or the 443 MB compiled cache (§4.4) proves unacceptable on device. |
| F2 | MPSGraph hand-port | Reimplementing a 1531-node graph by hand. Ceiling is roughly what CoreML GPU already delivers. | Never, unless F1 also fails. |
| F3 | Palettization / weight compression | Only meaningful once on the Core ML path (F1). ORT cannot consume a palettized `.mlpackage`. | With F1. |
| F4 | INT8 htdemucs | Android measured **0.55× and 0.44× — slower** (`perf-plan-v4.md:195-196`). No reason it inverts on Apple. | Never. |
| F5 | Concurrent htdemucs sessions | Android host measured +1.5 % for 2× RSS (`perf-plan-v4.md:245-248`). | Never. |

### The single riskiest open item

**D2/D3 are validated on an M3 Mac, not on an iPhone.** The iPhone GPU is a different part with a
different memory system, and the 443 MB compiled-model cache and 33–72 s compile are both worse on
device. **M0 must re-run `docs/apple-port/bench/parity.cc` on a physical iPhone before M2 is planned
around a 4.3× win.** Everything else in this document degrades gracefully; this one does not.

---

## 1. ONNX Runtime on Apple via SPM

### 1.1 Package facts (verified by cloning the repo and reading the resolved artifact)

| Fact | Value | How verified |
|---|---|---|
| Package URL | `https://github.com/microsoft/onnxruntime-swift-package-manager` | `Package.resolved` |
| Latest tag | **`1.24.2`** | `git ls-remote --tags`: only `v1.15.0, v1.16.0, v1.17.0, v1.18.0, v1.19.2, 1.20.0, 1.24.1, 1.24.2` exist |
| Resolved revision | `b7fb7f7dea8a2469e6335d95a61b8f36d0dc83b2` | `naqi.xcodeproj/.../Package.resolved` |
| swift-tools-version | `5.9` | tag `1.24.2` `Package.swift:1` |
| Declared platforms | `.iOS(.v15)`, `.macOS(.v14)` | `Package.swift:22-23` |
| Products | `onnxruntime`, `onnxruntime_extensions` — **both `type: .static`** | `Package.swift:26,29` |
| Swift module to import | `OnnxRuntimeBindings` | target name, `Package.swift` |
| Binary target | `pod-archive-onnxruntime-c-1.24.2.zip`, sha256 `f7100a99…600b54` | `Package.swift:98-100` |
| Extensions binary | `pod-archive-onnxruntime-extensions-c-0.13.0.zip` | `Package.swift:110-112` |

**The SPM package lags the ORT release train.** The current pip wheel is `1.28.0`; SPM's newest tag is
`1.24.2`. Assume ~4 minor versions of lag and do not plan on a fix landing in SPM promptly.

### 1.2 Slices — simulator arm64 **is** shipped

From `onnxruntime.xcframework/Info.plist`:

| LibraryIdentifier | Platform | Architectures | `MinimumOSVersion` |
|---|---|---|---|
| `ios-arm64` | ios | arm64 | **15.1** |
| `ios-arm64_x86_64-simulator` | ios / simulator | **arm64**, x86_64 | **15.1** |
| `macos-arm64_x86_64` | macos | arm64, x86_64 | 14.0 |

All three targets in scope (iPhone device, iPhone 17 Pro simulator, Apple Silicon Mac) are covered.

### 1.3 Execution providers actually compiled into the Apple binary

Ran `docs/apple-port/bench/eps.c` against the real slice — **on macOS and inside the iOS 26.5
simulator**. Identical output on both:

```
ORT build version: 1.24.2   (header ORT_API_VERSION=24)
GetAvailableProviders (3):
  - CoreMLExecutionProvider
  - XnnpackExecutionProvider
  - CPUExecutionProvider
SessionOptionsAppendExecutionProvider("XNNPACK") -> OK
SessionOptionsAppendExecutionProvider("CoreML")  -> OK
```

**XNNPACK ships on Apple.** This is not documented anywhere obvious and it matters: Android's image
models run on XNNPACK (`ml/Models.kt:305`), so that configuration ports directly. Note there is no
`OrtSessionOptionsAppendExecutionProvider_Xnnpack` exported symbol — XNNPACK is only reachable through
the **generic** name-based append (`appendExecutionProvider:providerOptions:error:` in ObjC).

### 1.4 Swift 6 strict concurrency

`grep NS_SWIFT_SENDABLE` over `objectivec/include/` returns **nothing**. No ORT type is `Sendable`.
Under the project's `SWIFT_VERSION = 6.0` (`naqi.xcodeproj/project.pbxproj:231`) every ORT type is
non-`Sendable` and cannot cross an isolation boundary without a wrapper.

Contract, matching what `naqi/ML/Ort.swift` already does:

1. `ORTEnv` is process-wide and created once — hold it in a `nonisolated(unsafe) static let`.
2. Wrap `ORTSession` in a `final class … : @unchecked Sendable`. This is sound: ORT documents
   `Run` as thread-safe for concurrent calls on one session.
3. Do **not** make the wrapper an `actor`. That serializes inference we want overlapped with decode.
4. `ORTValue` is **not** safe to share across threads. One buffer per caller — the same contract
   Android states at `ml/Infer.kt:26-31` ("safe to call concurrently **as long as each caller owns its
   own `input` buffer**").

### 1.5 App Store submission trap — do not link `onnxruntime_extensions`

`onnxruntime` issue #27396 reports SPM submissions rejected for a missing `MinimumOSVersion`. Checked
both frameworks in the resolved artifact:

| Framework | `MinimumOSVersion` present? |
|---|---|
| `onnxruntime.framework` (ios-arm64, simulator, macos) | **Yes** — 15.1 / 15.1 / 14.0 |
| `onnxruntime_extensions.framework` (ios-arm64, simulator) | **NO — key absent** |

So the bug is confined to the extensions framework. We use no custom ops, so the fix is to simply not
link that product. Both products are `type: .static`, so nothing is embedded as a framework anyway —
linking only `onnxruntime` removes the exposure entirely.

### 1.6 Package.swift stanza

```swift
// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "Naqi",
    platforms: [.iOS(.v18), .macOS(.v15)],
    dependencies: [
        .package(
            url: "https://github.com/microsoft/onnxruntime-swift-package-manager",
            exact: "1.24.2"          // exact: the package lags ORT releases; take upgrades deliberately
        ),
    ],
    targets: [
        .target(
            name: "NaqiML",
            dependencies: [
                // "onnxruntime" ONLY. Never "onnxruntime_extensions" — its framework Info.plist
                // has no MinimumOSVersion and App Store validation rejects it (see 1.5).
                .product(name: "onnxruntime", package: "onnxruntime-swift-package-manager"),
            ]
        ),
    ]
)
```

### 1.7 XcodeGen stanza

```yaml
packages:
  onnxruntime:
    url: https://github.com/microsoft/onnxruntime-swift-package-manager
    exactVersion: 1.24.2

targets:
  naqi:
    type: application
    platform: [iOS, macOS]
    deploymentTarget:
      iOS: "18.0"
      macOS: "15.0"
    dependencies:
      - package: onnxruntime
        product: onnxruntime      # NOT onnxruntime_extensions — see 1.5
    settings:
      base:
        SWIFT_VERSION: "6.0"
        OTHER_LDFLAGS: [-lc++]    # ORT is C++; the static archive needs libc++
```

`-lc++` is required: the product is a static archive of C++ objects and a pure-Swift target will not
pull in the C++ runtime on its own.

---

## 2. CoreML Execution Provider

### 2.1 Two APIs, and only one of them is sufficient

`objectivec/include/ort_coreml_execution_provider.h` exposes:

**V1 — `ORTCoreMLExecutionProviderOptions`** (boolean flags only):
`useCPUOnly`, `useCPUAndGPU`, `enableOnSubgraphs`, `onlyEnableForDevicesWithANE`,
`onlyAllowStaticInputShapes`, `createMLProgram`.

**V2 — `appendCoreMLExecutionProviderWithOptionsV2:error:`** takes an `NSDictionary`. Its
implementation (`ort_coreml_execution_provider.mm`) is a one-liner:

```objc
return [self appendExecutionProvider:@"CoreML" providerOptions:provider_options error:error];
```

i.e. it passes the dictionary straight through to the generic provider-options path — so **every key
in `coreml_provider_factory.h` works**, including ones V1 cannot express.

**Use V2.** V1 cannot set `ModelCacheDirectory`, which D4 makes mandatory.

### 2.2 Full V2 key table (from `coreml_provider_factory.h`, shipped in the artifact)

| Key | Values | Notes |
|---|---|---|
| `MLComputeUnits` | `CPUOnly`, `CPUAndGPU`, `CPUAndNeuralEngine`, `ALL` | **htdemucs → `CPUAndGPU`** (§4.3) |
| `ModelFormat` | `MLProgram`, `NeuralNetwork` | `MLProgram` needs Core ML 5+. NSFW gate needs `NeuralNetwork` (§5.2) |
| `RequireStaticInputShapes` | `"1"` / `"0"` | `"1"` makes the EP **refuse** dynamic-shaped nodes → silent CPU fallback (§2.3) |
| `EnableOnSubgraphs` | `"1"` / `"0"` | Not needed; no control-flow subgraphs in our models |
| `ModelCacheDirectory` | path | **Critical.** Without it the compiled model goes to a temp dir and is destroyed at session close |
| `SpecializationStrategy` | `Default`, `FastPrediction` | Untested here |
| `ProfileComputePlan` | `"1"` / `"0"` | Logs per-op hardware placement. Use during M0 device bring-up |
| `AllowLowPrecisionAccumulationOnGPU` | `"1"` / `"0"` | Leave `"0"` — we already have an fp16 precision problem (§3) |

Cache-key derivation, quoted from the header: *"1. User provided key in metadata_props if found
(preferred) 2. Hash of the model url … 3. Hash of the input/output names"*. It also warns: *"we do NOT
detect if the onnx model has changed and no longer matches the cached model."* **Therefore: version the
cache directory by model sha256** so a re-exported artifact cannot read a stale compiled model.

### 2.3 Which ops fall back to CPU — measured, not guessed

ORT partitions the graph and gives CoreML only the subgraphs it can take. For htdemucs the partition
count is **19** (`ls pc_gpu/*/ | grep -c mlprogram`), i.e. 19 separate CoreML models with 18
CPU↔CoreML boundary crossings per inference. It is still 4.3× faster than CPU, but that fragmentation
is where the remaining headroom lives.

Two failure modes actually hit, both of which look like "CoreML is slow" rather than an error:

1. **Dynamic shapes.** `nsfw_mnv2_140_f32.onnx` declares input `['unk__615', 3, 224, 224]`
   (`bench/graphinfo.py` output). With `RequireStaticInputShapes=1` the EP takes **nothing** and the
   whole graph silently runs on CPU — measured 17.5 ms, identical to CPU t=1. With
   `RequireStaticInputShapes=0` it does not fall back, it **hard-fails**:
   `E5RT … Input: input_DequantizeLinear_Output has unbounded dimension which is not supported.`
   **Fix: freeze the batch dim to 1 at build time** (`onnxruntime.tools.onnx_model_utils.make_input_shape_fixed`, see `bench/prep.py`).
2. **`MLProgram` parse failures on tf2onnx output.** The frozen-shape NSFW fp32 graph fails all three
   `MLProgram` compute units with `Unable to parse ML Program: in operation StatefulPartitionedCall/…`
   but compiles fine as `NeuralNetwork` — and then runs at 0.667 ms. Always A/B both `ModelFormat`
   values per model; do not assume `MLProgram` is the better one.

### 2.4 The simulator — CoreML EP loads, and you must still disable it

Measured by building `bench/gen.cc` against the simulator slice and running it under
`xcrun simctl spawn` on iPhone 17 Pro / iOS 26.5:

| Config | Simulator median | Native M3 median |
|---|---:|---:|
| CPU t=1 | 0.970 ms | 0.466 ms |
| XNNPACK t=4 | **0.395 ms** | **0.263 ms** |
| CoreML ALL/MLProgram | 1.796 ms | 0.967 ms |
| CoreML ANE/MLProgram | 2.756 ms | 0.289 ms |
| CoreML GPU/MLProgram | 2.355 ms | 0.989 ms |

Findings:

- `ORTIsCoreMLExecutionProviderAvailable()` returns **true** on the simulator, so it is **not** a usable
  capability check.
- **There is no ANE on the simulator.** The hardware is not virtualized; `CPUAndNeuralEngine` silently
  executes elsewhere. Measured 2.756 ms on simulator vs 0.289 ms native — a 9.5× gap on identical
  arm64 silicon.
- The GPU path is actively broken there:
  `Espresso exception: "Invalid state": MpsGraph backend validation on incompatible OS.`
- **CoreML is slower than plain CPU on the simulator in every configuration measured.**

**Contract: never draw a performance conclusion from the simulator, and force `compute = .cpu` when
`TARGET_OS_SIMULATOR`.**

```swift
#if targetEnvironment(simulator)
    // CoreML EP registers on the simulator but has no ANE, throws Espresso/MpsGraph errors on the
    // GPU path, and measured 2-5x SLOWER than the CPU EP. Measured 2026-08-04, iOS 26.5.
    let effective: ComputeUnit = .cpu
#else
    let effective = requested
#endif
```

---

## 3. The fp16 question

### 3.1 What the artifact actually is

`bench/graphinfo.py` on `htdemucs_s26_f16.onnx`:

```
ir_version 10   opsets [('ai.onnx', 18)]   producer pytorch 2.13.0
initializer dtypes: {'FLOAT16': 552, 'INT64': 73}
node count: 1531
inputs:  [('input', 'FLOAT', [1, 2, 114660]), ('x', 'FLOAT', [1, 4, 2048, 112])]
outputs: [('out_spec', 'FLOAT', [1, 4, 4, 2048, 112]), ('out_wave', 'FLOAT', [1, 4, 2, 114660])]
Conv: 92   ConvTranspose: 8   Pool-ish: 0   Conv-fed-by-Conv: 0
```

**Graph IO is fp32; only the weights are fp16.** This exactly reproduces the Android autopsy at
`perf-plan-v3.md:268-282` (`Conv: 92 | Pool-ish: 0 | Conv whose data input is another Conv: 0`,
`{FLOAT16: 552, INT64: 73}`) — the artifact is unchanged and that analysis carries over.

### 3.2 ORT CPU EP on Apple computes in **fp32**, and there is no way to make it not

Three independent lines of evidence:

1. **No fp16 kernels in the Apple binary.** String-scanning the iOS slice:
   `FusedConvFp16` = 0, `PoolFp16` = 0, `MlasConvFp16` = 0. (Android's `libonnxruntime.so` *does*
   contain `FusedConvFp16` and `PoolFp16` — `perf-plan-v3.md:275-277`.) The Apple build is strictly
   less fp16-capable than the Android one.
2. **ORT says so at load.** Session creation logs
   `Could not find a CPU kernel and hence can't constant fold Mul node 'node_mul_4'` (also `Cos`,
   `Sin`) — those nodes are fp16-typed and there is no fp16 CPU kernel.
3. **It is slower, not faster.** fp16 graph CPU t=1 = **1216.4 ms**; fp32 graph CPU t=1 = **1122.9 ms**.
   Half the weight bytes and it loses by 8 % — that is cast overhead, not fp16 arithmetic.

**There is no session option that forces fp32.** The complete precision-related key list in
`onnxruntime_session_options_config_keys.h` is `kOrtSessionOptionsAvx2PrecisionMode` (x64 only) and
`kOrtSessionOptionsMlasGemmFastMathArm64Bfloat16` (which *lowers* precision). **The model must be
converted.** `bench/prep.py` does it in 30 lines: rewrite FLOAT16 initializers to FLOAT32, retarget
`Cast(to=FLOAT16)` attributes, rewrite `value_info` element types. 88 MB → **172.6 MB**.

### 3.3 fp16 weights cost ~60 dB, and fp32 compute does not buy it back

Measured against an fp32-graph CPU t=1 gold, identical deterministic inputs
(`bench/parity.cc`, raw output in `bench/parity.txt`):

| Config | SNR vs gold, spec | SNR vs gold, wave |
|---|---:|---:|
| fp32 graph, CPU t=8 | 114.8 dB | 135.1 dB |
| **fp16 graph, CPU t=1** | **60.1 dB** | **73.4 dB** |
| fp16 graph, CPU t=8 | 60.5 dB | 73.4 dB |

The fp16 graph plateaus at ~60 dB **regardless of thread count**, because the loss was taken at export
when the weights were rounded — not at runtime. Running fp32 kernels over fp16 weights cannot recover
it. This is consistent with the Android-measured fp16 parity of 63.4/69.0 dB
(`audio/DemucsSeparator.kt:527`).

### 3.4 CoreML EP makes it worse, and the fp32 graph is the fix

| Graph | EP | median | SNR spec | SNR wave |
|---|---|---:|---:|---:|
| fp16 | CoreML `CPUAndGPU` | 128.8 ms | **46.3 dB** | **61.1 dB** |
| fp16 | CoreML `ALL` | 299.6 ms | **31.3 dB** | 59.6 dB |
| **fp32** | **CoreML `CPUAndGPU`** | **160.2 ms** | **86.6 dB** | **112.5 dB** |

Shipping the fp32 graph costs **31 ms/chunk (24 %)** and buys **+40 dB spectral / +51 dB wave**. On the
fp16 graph the CoreML GPU path lands *below* the fp16 export loss (46.3 < 60.1), i.e. Core ML is adding
its own fp16 rounding on top of the export's. **That is the D3 recommendation in one row.**

### 3.5 The NaN workaround must be ported regardless

`audio/DemucsSeparator.kt:225` documents a real, shipped incident:

> Observed 2026-07-29 on a 10.5-minute source: the run died at the very end, after 6.5 minutes of
> separation, with `IllegalArgumentException: Cannot round NaN value` from `AacWriter`'s int16 quantizer.

The mechanism is fp16's ~65504 ceiling reached by a passage far louder than the whole-track std used to
normalize. **No config in my parity run produced a non-finite value** (`nonFinite=0/0` everywhere) — but
that was synthetic input, and absence of reproduction is not absence of the bug. Port
`DemucsSeparator.finite()` (`:270-274`) and the `nonFinite` counter verbatim. **Core ML's GPU path runs
fp16 by default, so the Apple risk is at least as high as Android's, even on the fp32 graph.**

---

## 4. THE PERFORMANCE QUESTION — htdemucs

### 4.0 Correcting the brief

The task states "0.55× realtime on an S23". **That number is not htdemucs' speed.** `perf-plan-v4.md:195`
records `0.55×` as the measured *speedup factor* of a **dynamic-INT8 htdemucs experiment** — i.e. it was
about twice as slow as baseline, and was rejected. The real figures:

| Quantity | Value | Citation |
|---|---|---|
| htdemucs at the shipped 2.6 s segment | **1.33× realtime** on S23 | `perf-plan-v4.md:236` |
| Whole job, all stages | 3.1× realtime | `perf-plan-v3.md:22` |
| `separate` share of a film job | 55–65 % | `DemucsSeparator.kt:233-238` |
| Best Android host config | 405.3 ms/chunk = 6.41× realtime | `perf-plan-v4.md:246` |

Realtime below is computed against **`STRIDE` = 103 194 samples = 2.3400 s** — the audio actually
consumed per chunk — not `SEG`. Chunks overlap 10 % (`DemucsSeparator.kt:563`).

### 4.1 Measured ranking on M3 (`bench/parity.txt`, `bench/ortbench.cc`)

| Rank | Option | median ms/chunk | ×realtime | vs best CPU | Parity (spec/wave) |
|---:|---|---:|---:|---:|---|
| 1 | **CoreML EP, fp16 graph, `CPUAndGPU`** | **128.8** | **18.2×** | **4.31×** | 46.3 / 61.1 dB |
| 2 | **CoreML EP, fp32 graph, `CPUAndGPU`** | **160.2** | **14.6×** | **3.46×** | **86.6 / 112.5 dB** |
| 3 | CoreML EP, fp16, `ALL` | 299.6 | 7.8× | 1.85× | 31.3 / 59.6 dB |
| 4 | CPU EP, fp16, t=8 | 554.8 | 4.2× | 1.00× | 60.5 / 73.4 dB |
| 5 | CPU EP, fp32, t=8 | 617.8 | 3.8× | 0.90× | 114.8 / 135.1 dB |
| 6 | CPU EP, fp16, t=1 | 1216.4 | 1.9× | 0.46× | 60.1 / 73.4 dB |
| — | **XNNPACK EP** | 1587.3 | 1.5× | 0.35× | **CORRUPT — see 4.2** |

### 4.2 XNNPACK destroys htdemucs on Apple too — the Android workaround transfers

`audio/DemucsSeparator.kt:717-719` says:

> CPU EP (multi-threaded), NOT XNNPACK: XNNPACK's fp16 kernels corrupt this f16 graph's spectral branch
> on-device (broadband-noise stems; time branch survives)

**Reproduced exactly on Apple silicon.** In `bench/ortbench.cc`, XNNPACK t=4 and t=6 both returned
`snr_spec = -25.6 dB, snr_wave = -19.5 dB` against a CPU reference — negative SNR is noise louder than
signal. Note the signature matches the Android description precisely: the spectral branch is worse
(−25.6) than the time branch (−19.5).

**Contract: XNNPACK is forbidden for htdemucs on Apple. It is the correct choice for the small models
(§5) — the ban is per-model, not global.**

### 4.3 ANE cannot run htdemucs at all

Every `MLComputeUnits` value that permits the ANE emits, repeatedly:

```
E5RT encountered an STL exception. msg = MILCompilerForANE error:
failed to compile ANE model using ANEF. Error=_ANECompiler : ANECCompile() FAILED.
```

Consequences:

- `CPUAndNeuralEngine` = 436.9 ms — **3.4× slower** than `CPUAndGPU`.
- `ALL` = 299.6 ms — pays the failed ANE attempt, then partitions worse. **2.3× slower** than `CPUAndGPU`.
- `CPUAndGPU` = 128.8 ms — never attempts ANE, wins.

**The accelerator for htdemucs is the GPU, not the ANE.** Any plan premised on "put htdemucs on the
Neural Engine" is dead on arrival. This is the largest single correction in this document.

### 4.4 The costs CoreML imposes

| Cost | Measured | Mitigation |
|---|---|---|
| Cold compile | **33 393 – 72 184 ms** | `ModelCacheDirectory` (D4) |
| Warm compile | 10 601 – 24 399 ms | Still not free. Compile once per app version, off the critical path |
| Compiled cache, fp16 | **443 MB** | Application Support, `isExcludedFromBackup = true` |
| Compiled cache, fp32 | **615 MB** | Same. This is 3.6× the `.onnx` and is the strongest argument against D3 |
| Partitions | 19 | Inherent to the graph |

Two notes. Android hit the mirror-image of the disk problem and rejected it:
`DemucsSeparator.kt:646-657` removed `setOptimizedModelFilePath` because it cost "157 MB of the user's
storage" to save 0.9 s. **443–615 MB is 3–4× worse than the thing Android rejected.** The difference is
that CoreML buys 4.3×, where the serialized ORT graph bought 0.23 % — but this needs an explicit product
decision, and it must be measured on a real iPhone before it is relied upon.

### 4.5 coremltools — the Python situation, and why F1 is deferred

Actually ran this:

| Check | Result |
|---|---|
| `python3 --version` | **3.14.6** |
| `uv --version` | **0.11.26** — installed at `/opt/homebrew/bin/uv` |
| `uv venv --python 3.12` | Works; resolves `cpython-3.12.13` already on the box |
| `coremltools` under 3.12 | **9.0**, installs clean |
| coremltools declared support | Classifiers list Python **3.7 – 3.13**. **3.14 is not supported** |
| ONNX → Core ML conversion | **`ModuleNotFoundError: No module named 'coremltools.converters.onnx'`** |

So: **uv solves the Python problem completely** (`uv venv --python 3.12`; no pyenv needed), but it does
not solve the conversion problem. coremltools 9 converts from **PyTorch and TensorFlow only** — the ONNX
converter was removed years ago. Getting a `.mlpackage` therefore means re-exporting from the
`demucs.onnx` PyTorch fork that `scripts/htdemucs_export.py` already drives (`m0-spikes.md:52`), then
re-validating parity, `SEG` geometry, and the bit-identical-resume contract. That is a real project, and
the CoreML EP already delivers most of the win. **Hence F1: deferred, not rejected.**

### 4.6 Ranking with engineering cost and risk

| Option | Speedup vs CPU t=8 | Eng cost | Risk |
|---|---:|---|---|
| **(a) ORT CPU EP** | 1.00× (baseline) | **None** — already written | None. Ships today. The correctness reference |
| **(b) ORT CoreML EP** | **4.31× (fp16) / 3.46× (fp32)** | **Low** — ~20 lines + cache management | Med: 443–615 MB cache; 33–72 s cold compile; ANE unusable; unverified on iPhone |
| (c) coremltools → `.mlpackage` | ~4–5× (est., ≥ (b)) | **High** — PyTorch re-export + full re-validation | High: new artifact invalidates every measured parity number in the Android docs |
| (d) MPSGraph | Unknown, ≤ (c) | **Very high** — hand-port 1531 nodes | Very high. No path to reusing the `.onnx` |

**Recommendation: (b), with the fp32 graph if bundle size permits.** It is the only option whose
engineering cost is hours rather than weeks, it keeps the *same `.onnx` artifacts as Android* (which the
brief requires), and it preserves (a) as a one-enum-value fallback for the simulator, for correctness
A/Bs, and for any device where CoreML misbehaves.

---

## 5. The small models

### 5.1 Measured (`bench/small.txt`, ORT 1.24.2, M3, batch frozen to 1)

| Model | CPU t=1 | CPU t=4 | XNNPACK t=4 | CoreML MLProgram | CoreML NeuralNetwork |
|---|---:|---:|---:|---:|---:|
| `nsfw_mnv2_140_f32` (dynamic batch) | 16.96 | 7.92 | 5.35 | 17.53 *(no-op fallback)* | 17.22 |
| **`nsfw_f32` (batch fixed to 1)** | 17.54 | 6.98 | 6.39 | **FAILS to compile** | **0.667** |
| `nsfw_int8` (batch fixed to 1) | 4.84 | **2.27** | 2.41 | 6.35 | 8.67 |
| `genderage` (batch fixed to 1) | 0.466 | 0.436 | **0.263** | 0.967 | 0.312 |
| `yamnet` | 2.90 | 1.77 | **1.50** | 1.13 | 1.30 |

*(A caveat on my own harness: `gen.cc` re-seeds input per case, so its `maxAbsDiff` column is
meaningless and is not quoted here. Timings are unaffected. The htdemucs parity numbers in §3–4 come
from `parity.cc`, which does hold inputs identical.)*

### 5.2 The NSFW gate: Apple **inverts** the Android decision

Android's headline result (`ml/Models.kt:44`) is *"**3.37× faster (8.21 → 2.44 ms) and 17.3 → 5.1 MB**"*
for INT8 — a large, carefully validated win (99.20 % recall of the fp32 censored timeline,
`ml/Models.kt:58`). On Apple that inverts:

| Path | ms | Note |
|---|---:|---|
| **fp32 + CoreML `NeuralNetwork`** | **0.667** | **Winner — 3.4× faster than the Android choice** |
| INT8 + CPU t=4 (the Android choice) | 2.27 | |
| INT8 + CoreML | 6.35 | QDQ maps badly to Core ML |

**Recommendation (D6): ship the fp32 NSFW graph on Apple and run it on CoreML `NeuralNetwork`.**

This is a strong secondary win: it retires the INT8 artifact on Apple entirely and with it the 96.1 %
argmax-agreement caveat (`ml/Models.kt:45`) and the whole INT8 recall-regression surface. The
strictness-sweep thresholds in `analysis/NsfwGate.kt` were tuned against fp32 in the first place, so
this is *more* faithful to the tuned behaviour, not less. Cost: **+12 MB** bundle (17 MB fp32 vs 5.1 MB
INT8) and a 556 ms one-time CoreML compile.

Three conditions attach:

1. The batch dim **must** be frozen to 1 — with `['unk__615',3,224,224]` CoreML takes nothing.
2. `ModelFormat` **must** be `NeuralNetwork`. `MLProgram` fails to compile this tf2onnx graph
   (`Unable to parse ML Program: in operation StatefulPartitionedCall/…`).
3. 0.667 ms is an M3 number. Re-measure on iPhone before deleting the INT8 path.

### 5.3 genderage and YAMNet: XNNPACK, not CoreML

Both are already sub-millisecond-ish, and CoreML's fixed cost does not repay:

- **genderage** — XNNPACK t=4 **0.263 ms** vs CoreML ANE 0.289 ms, for a 122–170 ms compile. A track
  classifies at most `VOTE_CAP` crops; the compile never amortizes.
- **YAMNet** — XNNPACK t=4 **1.50 ms** vs CoreML `ALL` 1.13 ms, for a 698–1028 ms compile. `MusicGate`
  runs ~3 inferences per 2.6 s chunk (`audio/MusicGate.kt:21`), so CoreML saves ~1 ms per chunk against
  a ~130–550 ms chunk. Irrelevant.

These are the models the brief calls "trivially ANE-friendly", and the measurement disagrees: they are
too small for the ANE's fixed dispatch cost to pay off. **The ANE is not a win anywhere in this app.**

### 5.4 Thread counts port directly

`ml/Models.kt:280-289` swept XNNPACK threads on an S23 and found **2 → 47.8 inf/s, 4 → 42.3, 8 → 19.5**,
shipping 4 (`:294`) with a note that "2 / 4 / 6 still need that A/B". My M3 numbers agree with the shape
(t=4 beats t=1 by ~2× and 8 is never better), so **keep `XNNPACK_THREADS = 4`** and carry the open A/B
forward.

---

## 6. Model bundling and size

### 6.1 The budget is not tight

| Limit | Value | Source |
|---|---|---|
| Max **uncompressed** app size, iOS 9+ | **4 GB** | Apple, *Maximum build file sizes* |
| Max executable `__TEXT` (all sections) | **80 MB** | same |
| Cellular download limit | 200 MB | raised from 150 MB in 2017 |

| Payload | Size |
|---|---:|
| htdemucs fp16 | 88 MB |
| htdemucs fp32 (D3) | 172.6 MB |
| NSFW fp32 (D6) | 17 MB |
| NSFW INT8 (retired on Apple) | 5.1 MB |
| YAMNet | 16 MB |
| genderage | 1.3 MB |
| **Total, fp16 htdemucs** | **~122 MB** |
| **Total, fp32 htdemucs (D3+D6)** | **~207 MB** |

Both are far inside 4 GB. **The `__TEXT` limit is the one worth watching** — ORT is a 41 MB static
archive and links into the app executable rather than sitting beside it as a framework. Models are
resources and do not count, but check `__TEXT` after the first archive build.

The real constraint is the 200 MB cellular threshold: at ~207 MB the fp32 configuration lands just over
it, and users on cellular get "download over Wi-Fi" friction. **That is the actual argument against D3**
— not the 4 GB cap. If it matters, ship fp16 htdemucs (~122 MB) and accept 46.3/61.1 dB, or move
htdemucs to Background Assets.

### 6.2 Delivery mechanisms

| Mechanism | Status | Verdict |
|---|---|---|
| Bundled resources | Fine | **v1 (D8).** Simplest; no network, no first-run stall |
| On-Demand Resources | **Deprecated** as of iOS 27; removal planned. WWDC25 §325 says migrate | **Do not adopt** |
| Background Assets | iOS 16+; iOS 26 adds Managed + **Apple-Hosted** asset packs | **The v2 lever** if 200 MB bites |

Android already has the download path (`ml/ModelDownloader.kt`, `ModelSmoke.modelFile` resolves
`installed() ?: extracted()` — `ml/Models.kt:268-269`), so the app's model-resolution contract already
tolerates non-bundled models. Port that indirection even in v1 so Background Assets is a later swap
rather than a refactor.

### 6.3 Core ML compression is not available to us in v1

Palettization and quantization are **coremltools operations on a `.mlpackage`**. ORT cannot consume a
palettized `.mlpackage` — it consumes `.onnx` and compiles its own MLProgram internally. So compression
is gated behind F1.

For the record, if F1 ever happens: 8-bit palettization is roughly half the fp16 size; 4-bit needs
per-block or grouped-channel granularity to hold accuracy, and Apple's own ResNet50 example shows 4-bit
grouped-channel recovering to 69.3 / 72.3 / 73.1 % as group size tightens (16 / 8 / 4). Given htdemucs
already loses 40 dB to Core ML's fp16 GPU path (§3.4), **spending more precision on size is the wrong
direction for this model.** ONNX-side alternatives are already measured-dead: INT8 htdemucs is
**0.55×/0.44× — slower** (`perf-plan-v4.md:195-196`).

---

## 7. Android contracts the Swift port must preserve

These are inference-adjacent constants that a runtime change could silently break.

### 7.1 Model IO

| Model | Input | Layout / scaling | Output | Citation |
|---|---|---|---|---|
| NSFW gate | `[1,3,224,224]` f32 | NCHW **RGB, ×1/255**, no mean/std | `[1,5]` **softmax** | `ml/Infer.kt:36`, `ml/Models.kt:78-79` |
| genderage | `[1,3,96,96]` f32 | NCHW **RGB 0..255, UNSCALED** | `[1,3]` **raw logits** | `ml/Infer.kt:39`, `ml/Models.kt:139-143` |
| YAMNet | `[15600]` f32 — **rank 1, not `[1,15600]`** | mono 16 kHz, [-1,1] | `[1,521]` scores | `audio/MusicGate.kt:117-118`, `ml/Models.kt:117-118` |
| htdemucs | `[1,2,114660]` + `[1,4,2048,112]` f32 | see §7.2 | `[1,4,4,2048,112]` + `[1,4,2,114660]` | `ml/Models.kt:108`, `audio/DemucsSeparator.kt:672-676` |

**The scaling trap is called out explicitly in the Kotlin** (`ml/Models.kt:141-142`): the gate is 1/255
and genderage is unscaled *on the identical layout*, so "a copy-pasted fill silently feeds this graph
1/255th of its trained range."

### 7.2 htdemucs geometry — change only with a matching re-export

| Constant | Value | Citation |
|---|---:|---|
| `SEG` | 114 660 (= `int(2.6 × 44100)`) | `DemucsSeparator.kt:529` |
| `STRIDE` | 103 194 (10 % overlap) | `:563` |
| `MAX_SHIFT` | 22 050 (0.5 s pre-pad, `shift_offset = 0`) | `:564` |
| `BINS` | 2048 (NFFT/2, Nyquist dropped) | `:565` |
| `LE` | 112 (`ceil(SEG/HOP)`) | `:566` |
| `STEM_SPEC` | `4 × BINS × LE` = 917 504 | `:567` |
| `NFFT` / `HOP` | 4096 / 1024 | `:568-569` |
| Stem order | drums=0, bass=1, other=2, vocals=3 | `:570-571` |
| `IN_CAP` / `OUT_CAP` | `2·SEG + LOOKAHEAD` / `SEG + STRIDE` | `:614-615` |
| Invariant | `STRIDE < SEG && SEG <= 2·STRIDE` (**asserted**) | `:142` |

2.6 s is the **measured optimum**, not a RAM compromise: 7.8 s costs +20.6 % compute *and* 3.24 GB
(`ml/Models.kt:98-102`, `perf-plan-v4.md:230-236`).

### 7.3 Session options

| Model set | Android | Apple equivalent |
|---|---|---|
| Image models | XNNPACK, `intraOp=1`, `allow_spinning=0`, `intra_op_num_threads=4` (`ml/Models.kt:302-306`) | Same; XNNPACK ships (§1.3). NSFW moves to CoreML (D6) |
| htdemucs | **CPU EP** (not XNNPACK), `intraOp=min(cores,6)`, `allow_spinning=0`, **arena OFF**, **memory-pattern OFF** (`DemucsSeparator.kt:715-730,745-746`) | CoreML `CPUAndGPU` (D2). **Keep arena/pattern off** — Android disabled them because lmkd killed the app at 5.6 GB RSS (`:712-714`) |

`allow_spinning=0` was chosen for power and measured *also* faster (`DemucsSeparator.kt:742,746`) — port it.

### 7.4 MusicGate constants

| Constant | Value | Citation |
|---|---:|---|
| `FRAME` | 15 600 (0.975 s @ 16 kHz) | `MusicGate.kt:137` |
| `CLASSES` | 521 | `:138` |
| `MUSIC_RANGES` | `132..276` and `24..32`, **inclusive** | `:150` |
| `THRESHOLD` | 0.15f | `:164` |
| `SILENCE_PEAK` | 0.001f (−60 dBFS) | `:167` |
| `DILATE` / `DILATE2_MIN_SCORE` / `GATE_RING` | 2 / 0.02f / 8 | `DemucsSeparator.kt:583,609,612` |

The gate skips ~141/276 chunks (`DemucsSeparator.kt:593`), roughly halving effective htdemucs cost.
**Any speedup in §4 multiplies on top of this**, not instead of it.

---

## 8. Copy-pasteable Swift

### 8.1 Session creation with the CoreML EP

```swift
import Foundation
import OnnxRuntimeBindings

enum ComputeUnit: Sendable {
    case cpu                 // reference path; matches Android numerics
    case coreMLGPU           // htdemucs: MLComputeUnits=CPUAndGPU (ANE cannot compile it)
    case coreMLNeuralNetwork // NSFW fp32: MLProgram fails to parse this tf2onnx graph
    case xnnpack             // genderage, YAMNet
}

/// ORT 1.24.2. Every literal below is measured — see docs/apple-port/spec-inference-apple.md.
func makeSession(
    modelPath: String,
    modelSHA256: String,          // cache-dir key: CoreML does NOT detect model changes
    compute requested: ComputeUnit,
    threads: Int
) throws -> ORTSession {

    // The CoreML EP registers on the simulator but has no ANE, throws
    // `Espresso ... MpsGraph backend validation on incompatible OS` on the GPU path,
    // and measured 2-5x SLOWER than the CPU EP (iOS 26.5, 2026-08-04).
    #if targetEnvironment(simulator)
    let compute: ComputeUnit = (requested == .xnnpack) ? .xnnpack : .cpu
    #else
    let compute = requested
    #endif

    let opts = try ORTSessionOptions()
    try opts.setLogSeverityLevel(.warning)
    try opts.setGraphOptimizationLevel(.all)
    try opts.setIntraOpNumThreads(Int32(threads))
    // Chosen for power on Android and measured ALSO faster (DemucsSeparator.kt:742,746).
    try opts.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")

    switch compute {
    case .cpu:
        break

    case .xnnpack:
        // XNNPACK ships in the Apple ORT binary but has no dedicated append symbol —
        // it is only reachable through the generic name-based API.
        // FORBIDDEN for htdemucs: measured -25.6 dB spec / -19.5 dB wave (corrupt).
        try opts.appendExecutionProvider("XNNPACK",
                                         providerOptions: ["intra_op_num_threads": "4"])

    case .coreMLGPU, .coreMLNeuralNetwork:
        // V2 dictionary API, not ORTCoreMLExecutionProviderOptions: only V2 can set
        // ModelCacheDirectory, and a 33-72 s cold compile makes that mandatory.
        let cacheRoot = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("CoreMLCache/\(modelSHA256)", isDirectory: true)
        try FileManager.default.createDirectory(at: cacheRoot, withIntermediateDirectories: true)
        // 443 MB (fp16) / 615 MB (fp32) of compiled model. Never back this up.
        var res = URLResourceValues(); res.isExcludedFromBackup = true
        var mutable = cacheRoot; try? mutable.setResourceValues(res)

        var po: [String: String] = [
            // CPUAndGPU, never ALL: ANE compilation fails for htdemucs
            // (`MILCompilerForANE ... ANECCompile() FAILED`) and ALL pays for the
            // failed attempt -- 299.6 ms vs 128.8 ms.
            "MLComputeUnits": "CPUAndGPU",
            "RequireStaticInputShapes": "1",   // every graph has its batch dim frozen to 1
            "ModelCacheDirectory": cacheRoot.path,
            "AllowLowPrecisionAccumulationOnGPU": "0",  // we already lose 40 dB to fp16 on GPU
        ]
        po["ModelFormat"] = (compute == .coreMLNeuralNetwork) ? "NeuralNetwork" : "MLProgram"

        do {
            try opts.appendCoreMLExecutionProvider(withOptionsV2: po)
        } catch {
            // Partition/compile failure must degrade to CPU, never abort the job.
            Log.ml.warning("CoreML EP rejected: \(error.localizedDescription, privacy: .public); CPU")
        }
    }

    // htdemucs only: Android disabled both because lmkd killed the app at 5.6 GB RSS
    // (DemucsSeparator.kt:712-714). Keep them off on iOS -- jetsam is stricter.
    // try opts.disableCpuMemArena(); try opts.disableMemPattern()

    return try ORTSession(env: Ort.env, modelPath: modelPath, sessionOptions: opts)
}
```

### 8.2 Forcing fp32 — a build step, not a session option

There is no session option (§3.2). Run this once at build time and ship the output.

```python
# docs/apple-port/bench/prep.py -- run with: uv venv --python 3.12 && uv pip install onnx onnxruntime
# python3 on this box is 3.14, which neither coremltools nor some onnx wheels support.
import onnx, numpy as np
from onnx import numpy_helper, TensorProto

m = onnx.load("htdemucs_s26_f16.onnx")
g = m.graph
for init in g.initializer:                       # 552 FLOAT16 initializers -> FLOAT
    if init.data_type == TensorProto.FLOAT16:
        init.CopyFrom(numpy_helper.from_array(
            numpy_helper.to_array(init).astype(np.float32), init.name))
for vi in list(g.value_info) + list(g.input) + list(g.output):
    if vi.type.tensor_type.elem_type == TensorProto.FLOAT16:
        vi.type.tensor_type.elem_type = TensorProto.FLOAT
for node in g.node:                              # retarget Cast(to=FLOAT16)
    if node.op_type == "Cast":
        for a in node.attribute:
            if a.name == "to" and a.i == TensorProto.FLOAT16:
                a.i = TensorProto.FLOAT
onnx.save(m, "htdemucs_s26_f32.onnx")            # 88 MB -> 172.6 MB
```

And freeze the batch dim so the CoreML EP will accept the small models at all (§2.3):

```python
from onnxruntime.tools.onnx_model_utils import make_input_shape_fixed, fix_output_shapes
m = onnx.load("nsfw_mnv2_140_f32.onnx")
make_input_shape_fixed(m.graph, "input", [1, 3, 224, 224])   # was ['unk__615',3,224,224]
fix_output_shapes(m)
onnx.save(m, "nsfw_mnv2_140_f32_static.onnx")
```

---

## 9. What must be measured on device before M2

Ordered by how much of the plan collapses if the answer is bad.

1. **Re-run `bench/parity.cc` on a physical iPhone.** Every §4 number is an M3 Mac number. If the iPhone
   GPU does not reproduce ~4×, D2/D3 change.
2. **CoreML cold-compile time on device.** 33–72 s on an M3 is already near-unacceptable; if the iPhone
   is 3× worse, the compile must move to a first-run background task with visible UI.
3. **Compiled-cache size and jetsam headroom on device.** 443–615 MB of cache plus htdemucs' ~1.30 GB
   working set (`audio/DemucsSeparator.kt:523`) on a 6 GB phone is the same cliff Android's lmkd notes
   describe.
4. **Real-audio A/B of CoreML output vs the Android fp16 reference.** 46.3 dB (fp16) / 86.6 dB (fp32) are
   synthetic-input SNRs. Android's own standard is a real-audio A/B (`perf-plan-v4.md:212`).
5. **`nonFinite` count on a loud real film.** §3.5. Core ML's GPU path is fp16 by default.
6. **NSFW fp32-on-CoreML recall vs the tuned strictness sweep.** Confirm 0.667 ms holds on iPhone before
   deleting the INT8 artifact.
7. **XNNPACK thread A/B (2 / 4 / 6)** — the open question Android left at `ml/Models.kt:288`.

---

## Appendix — reproducing the measurements

`docs/apple-port/bench/` contains everything. All harnesses link the **real SPM artifact**, not a pip wheel:

```
ART=~/Library/Developer/Xcode/DerivedData/naqi-*/SourcePackages/artifacts/\
onnxruntime-swift-package-manager/onnxruntime/onnxruntime.xcframework
F="$ART/macos-arm64_x86_64/onnxruntime.framework"

clang++ -std=c++17 -arch arm64 -O2 -I"$F/Versions/A/Headers" parity.cc \
  "$F/Versions/A/onnxruntime" -framework Foundation -framework CoreML -framework Accelerate -o parity
./parity . /path/to/htdemucs_s26_f16.onnx
```

| File | Purpose |
|---|---|
| `eps.c` | Enumerate EPs compiled into the Apple binary; probe generic append |
| `parity.cc` | htdemucs fp32-gold parity + timing (§3.3, §3.4, §4.1) → `parity.txt` |
| `ortbench.cc` | htdemucs EP sweep incl. the XNNPACK corruption result (§4.2) |
| `gen.cc` | Generic single-model EP sweep (§5.1) → `small.txt`, `sim.txt`. **`maxAbsDiff` column is invalid** — inputs differ per case |
| `prep.py` | fp16→fp32 conversion; batch-dim freezing (§8.2) |
| `graphinfo.py` | Graph census: dtypes, op histogram, Conv adjacency (§3.1) |

Simulator build: swap the slice to `ios-arm64_x86_64-simulator`, add
`-isysroot $(xcrun --sdk iphonesimulator --show-sdk-path) -mios-simulator-version-min=18.0`, run under
`xcrun simctl spawn <UDID>`.
