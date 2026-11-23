# spec-performance.md — Apple port performance architecture

Companion to `spec-audio.md`, `spec-analyze.md`, `spec-render.md`, `spec-models.md`, `spec-jobs-ui.md`.
Those specify **what to build**. This specifies **how to make it significantly faster than Android**,
and — equally — **which Android wins must not be dropped on the floor.**

Ground truth, all re-read, not paraphrased:

| Source | What it is |
|---|---|
| `docs/perf-plan-v4.md` | Most recent measured analysis (2026-08-04). Three job shapes, three disjoint walls. §11 is the S23 measurement session. |
| `docs/perf-plan-v3.md` | The producer/consumer split of analyze, the render ablation, the measurement protocol. |
| `docs/long-film-plan.md` | The only feature-length run (155.4 min, 1728×720). |
| `docs/video-performance-overhaul-plan.md` | The rejected-work list and the quality bars. |
| Kotlin sources | Every constant below is quoted `file:line` from `app/src/main/java/com/haithamassoli/naqi/`. |

Android citation shorthand: `DS` = `audio/DemucsSeparator.kt`, `AP` = `audio/AudioPipeline.kt`,
`AD` = `audio/AudioDecoder.kt`, `AW` = `audio/AacWriter.kt`, `DSP` = `audio/Dsp.kt`,
`MG` = `audio/MusicGate.kt`, `FS` = `analysis/FrameSampler.kt`, `NG` = `analysis/NsfwGate.kt`,
`FT` = `analysis/FaceTracker.kt`, `CE` = `render/CensorEffect.kt`, `RP` = `render/RenderPipeline.kt`,
`FW` = `work/FilterWorker.kt`, `JS` = `work/JobStats.kt`, `ML` = `ml/Models.kt`, `IN` = `ml/Infer.kt`,
`CP` = `work/Checkpoint.kt`, `ET` = `work/Eta.kt`.

---

## 0. The two facts that decide this entire document

### 0.1 The same ONNX graph already runs 5.27× faster on arm64 host than on the S23

`perf-plan-v4.md:245-248` records a host sweep of the **shipped** `htdemucs_s26_f16.onnx` under **ORT 1.27.0
CPU EP, the same runtime version as the APK** (`perf-plan-v4.md:10-12`):

| config | host ms/chunk | S23 ms/chunk (`DS:737`) |
|---|---:|---:|
| 1 session × 8 threads | 699.1 | 2 244 |
| **1 session × 4 threads** | **405.3** | 2 155 (4 threads) |
| 4 sessions × 1 thread | 398.6 | — |
| 2 sessions × 4 threads | 533.9 | — |
| 1 session × 6 threads | — | **2 136** (shipped) |

**2 136 ÷ 405.3 = 5.27×, same graph, same runtime, same options family.** The host is documented only as
"arm64" (`perf-plan-v4.md:11`). The Android repo is developed on this Mac (Darwin 25.6.0). It is therefore
**highly likely, but not stated, that the 5.27× is already an Apple-Silicon-vs-Snapdragon number.**

> **M-A1 (the single highest-value 20-minute run in this plan): confirm the host.** Re-run
> `1 session × N threads` over 8 chunks of the shipped graph on (a) the dev Mac and (b) a physical
> iPhone, with `sysctl -n machdep.cpu.brand_string` / device model logged. If the 5.27× is Apple
> Silicon, **the audio wall is already 5× better before one line of Apple-specific code is written**
> and §1's ranking below is correct. If the host was an Ampere/Graviton box, every projection in §6
> for shape C drops by roughly that factor and the ANE spike (§1.5) moves from P2 to P0.

### 0.2 The analyze producer wall is a Snapdragon dmabuf artifact, and it does not exist on Apple

`perf-plan-v3.md:88-95` measured the *identical Kotlin loops* on a desktop JVM at the same source
dimensions:

| loop | desktop JVM | S23 | ratio |
|---|---:|---:|---:|
| `packNv21` 1920×1080 → 640×360 (`FS:478-503`) | **0.270 ms** | 10.08 ms | **37×** |
| `convertToTensor` → 224² NCHW (`FS:531-566`) | **0.353 ms** | 9.17 ms | **26×** |

M1 (`perf-plan-v4.md:380-388`) then proved *where* the S23 time goes:

```
sample: frames=6430 getImg=308ms pack=77435ms mlkit=217ms close=9ms
```

| | ms | share |
|---|---:|---:|
| `codec.getOutputImage` (the gralloc map) | 308 | 0.4 % |
| **`packNv21` (the strided byte gather)** | **77 435** | **99.3 %** |
| `InputImage.fromByteBuffer` | 217 | 0.3 % |
| `image.close()` | 9 | 0.01 % |

**34.9 µs/kB for ~345 kB/frame** (`perf-plan-v4.md:394`, `FS:583-585`). That rate is a strided read out of
uncached/write-combined ION/dmabuf memory on a Qualcomm SoC. On Apple, `CVPixelBuffer` output from
`AVAssetReaderTrackOutput` is IOSurface-backed in **unified, CPU-cacheable memory**; a
`CVPixelBufferLockBaseAddress(_, .readOnly)` read is a normal cached load.

**Consequence: the 81 445 ms Android producer (`perf-plan-v4.md:493`) is not a cost the Apple port
inherits. And the largest single piece of it — the 640-px NV21 repack — is not even needed** (§2.2).

### 0.3 The thesis

> The Apple port is expected to be **2.4–3.4× faster than Android on every job shape before any
> Apple-specific optimisation**, purely from (a) the CPU running the same ONNX graph and (b) the
> absence of the dmabuf gather penalty. **The engineering job is therefore mostly "do not lose that",
> not "find new wins."** Every item in §6 is ranked on that basis: items that *protect* the free win
> outrank items that *add* a new one.

---

## 1. The audio wall (htdemucs) — dominant on shape C, which is the flagship job

### 1.0 Where the 385 420 ms goes — measured, fully attributed

`perf-plan-v4.md:413-417`, one cooled S23 session over `qa-assets/tv1.webm` (643 s), music-only,
post-C1 build:

```
separate split:    stft=4079 ort=270177 istft+ola=4622 gate=7453 gather=105 flush=7953 encode=26584
separate residual: decode=55589 yield=123 sessionCreateMs=881 gateOpenMs=171
```

| item | ms | share of `separate` (385 420) | Kotlin owner |
|---|---:|---:|---|
| **ORT `session.run`** | **270 177** | **70.1 %** | `DS:405`, `HtdemucsSession.infer` `DS:661-705` |
| **audio decode** | **55 589** | **14.4 %** | `AD:127-341`, timed at `AP:204-212` |
| AAC encode (`emit`) | 26 584 | 6.9 % | `AW:81-98`, `AW:119-145` |
| flush (divide + softclip) | 7 953 | 2.1 % | `DS:469-497` |
| YAMNet music gate | 7 453 | 1.9 % | `MG:65-84`, 3 inferences/chunk max |
| iSTFT + OLA + spec sum | 4 622 | 1.2 % | `DSP:199-231`, `DS:411-432` |
| STFT | 4 079 | 1.1 % | `DSP:174-197` |
| session create | 881 | 0.23 % | `DS:658` |
| gate open | 171 | 0.04 % | `MG:177-190` |
| input-ring gather | 105 | 0.03 % | `DS:384-395` |
| thermal yield | 123 | 0.03 % | `AP:53-67` |
| unattributed | 7 683 | 2.0 % | — |

Job-shape context (`perf-plan-v4.md:43-52`): `FW.branches()` (`FW:845-872`) makes a combined job's wall
`max(audio, video)`. Measured `separate` **449 376 ms** (pre-C1) against a whole video branch of
**204 752 ms** — audio is **2.19× the video branch with 244 s of slack underneath it**. On shape C every
audio millisecond pays **1:1**; every video millisecond pays **0**.

### 1.1 Constants the Apple audio pipeline must not change

| # | Constant | Value | Citation |
|---|---|---|---|
| A.1 | `SEG` | `114_660` samples = 2.6 s @ 44.1 kHz | `DS:529` |
| A.2 | `STRIDE` | `103_194` = 10 % overlap, `int(0.90 × SEG)` | `DS:563` |
| A.3 | `MAX_SHIFT` | `22_050` (0.5 s zero pre-pad, deterministic `shift_offset = 0`) | `DS:564` |
| A.4 | `NFFT` / `HOP` | `4096` / `1024` | `DS:568-569` |
| A.5 | `BINS` | `2048` (Nyquist bin dropped) | `DS:565`, `DSP:125-126` |
| A.6 | `LE` | `112` = `ceil(SEG/HOP)` | `DS:566`, `DSP:129` |
| A.7 | `STEM_SPEC` | `4 × BINS × LE` = `917_504` | `DS:567` |
| A.8 | `IN_CAP` | `2*SEG + LOOKAHEAD` = `435_708` | `DS:614` |
| A.9 | `OUT_CAP` | `SEG + STRIDE` = `217_854` | `DS:615` |
| A.10 | `DILATE` / `LOOKAHEAD` | `2` / `2*STRIDE` = `206_388` | `DS:583-584` |
| A.11 | `DILATE2_MIN_SCORE` | `0.02f` (C1's two-tier gate) | `DS:609` |
| A.12 | `MusicGate.THRESHOLD` | `0.15f` | `MG:164` |
| A.13 | `SILENCE_PEAK` | `0.001f` (−60 dBFS) | `MG:167` |
| A.14 | YAMNet `FRAME` | `15_600` samples, **rank-1 input `[15600]`** | `MG:137`, `ML:120` |
| A.15 | `MUSIC_RANGES` | `132..276` and `24..32`, inclusive | `MG:150` |
| A.16 | `INTRA_OP_THREADS` | `min(availableProcessors, 6)` **← Apple must change this, see 1.4** | `DS:745` |
| A.17 | `ALLOW_SPINNING` | `"0"` | `DS:746` |
| A.18 | Arena / memory-pattern | both **off** | `DS:728-729` |
| A.19 | AAC out | AAC-LC, 44 100 Hz, stereo, 192 000 bit/s, `KEY_MAX_INPUT_SIZE = 16_384` | `AW:49-54` |
| A.20 | PCM scratch | int16 LE stereo 44.1 kHz = **176 400 B/s of source** | `AP:337-341`, `FW:251` |
| A.21 | Stats sampling | `STATS_WINDOWS = 20` × `WINDOW_US = 2_000_000` = 40 s | `AD:44-45` |
| A.22 | `softclip` knee | transparent ≤ 0.95, `tanh((a−0.95)/0.05)×0.05` above | `DS:758-762` |

### 1.2 Lever 1 — vDSP for the out-of-graph STFT/iSTFT: exact setup

**Why it is worth doing, and honestly how much.** The DSP performs ~59 MFLOP per separated chunk against
the graph's **91.96 GFLOP** (`perf-plan-v4.md:220-223`) — **0.064 % of the arithmetic** — yet
`stft + istft/ola = 8 701 ms` is **3.22 % of ORT's 270 177 ms**. That is a **~50× efficiency gap**, created
entirely by `Dsp.kt`'s scalar radix-2 loop with a per-butterfly `Float→Double→Float` promotion
(`DSP:66-73`, kept deliberately for `DspTest`'s 1e-4 tolerances — `DSP:14-27`).

Per separated chunk, exactly:

| work | count | Kotlin site |
|---|---:|---|
| forward 4096-pt FFT | 2 ch × 112 frames = **224** | `DSP:184-196` |
| inverse 4096-pt FFT | 2 ch × 112 frames = **224** | `DSP:208-225` |
| window multiply (4096 f32) | 448 | `DSP:187`, `DSP:223` |
| reflect pads | 4 | `DSP:235-239` |
| spec sum (`nKeep−1` × 917 504 adds) | 917 504 when `keepOther` | `DS:412-415` |
| OLA accumulate (f64) | 2 × 112 × 4096 = 917 504 FMA | `DSP:222-224` |
| envelope divide | 2 × 114 660 | `DSP:228-230` |
| time-branch sum + weighted OLA | `clen × nKeep × 2` | `DS:419-432` |

#### 1.2.1 Setup — create once, never per chunk

```swift
import Accelerate

// log2(4096) = 12. One setup per separator worker. FFTSetup is read-only after creation;
// still allocate per worker rather than sharing, because it costs ~48 KB of twiddles.
let log2n = vDSP_Length(12)
guard let fftSetup = vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2)) else { fatalError() }
// vDSP_destroy_fftsetup(fftSetup) on teardown.
```

**The window must be generated by hand, not by `vDSP_hann_window`.** Kotlin uses a *periodic* Hann with
denominator `nfft` and computes it in Double (`DSP:132`):

```swift
// EXACT port of Dsp.kt:132 — 0.5 * (1 - cos(2πn/4096)), computed in Double, stored as Float.
// Do NOT use vDSP_hann_window: its DENORM/NORM variants use the symmetric (N-1) denominator.
var hann = [Float](repeating: 0, count: 4096)
for n in 0..<4096 { hann[n] = Float(0.5 * (1.0 - cos(2.0 * Double.pi * Double(n) / 4096.0))) }
```

Envelope: `Dsp.kt:256-261` sums `win[i]²` over **all `nframes = le + 4 = 116` frames** into a
`DoubleArray(paddedSeg + nfft)` and later divides by `env[k] + 1e-8` (`DSP:229`). Precompute the
**reciprocal** in Double once per `T`, store as Float, and multiply:

```swift
// paddedSeg = T + padL + padR = 114660 + 1536 + 1564 = 117760 = (le+3)*hop  (Dsp.kt:244-247)
// buffer length = paddedSeg + nfft = 121856
var envD = [Double](repeating: 0, count: 121_856)
for f in 0..<116 { let s = f * 1024; for i in 0..<4096 { envD[s + i] += Double(hann[i]) * Double(hann[i]) } }
let recipEnv = envD.map { Float(1.0 / ($0 + 1e-8)) }   // one Double divide per cell, once per T
```

#### 1.2.2 Forward — per frame

`vDSP_fft_zrip` real forward output is **scaled by 2** relative to the mathematical DFT, and packs
`realp[0] = DC`, `imagp[0] = Nyquist.re`, `(realp[k], imagp[k]) = bin k` for `k = 1 … 2047`
(Apple, *Using Fourier Transforms*).

```swift
var splitRe = [Float](repeating: 0, count: 2048)
var splitIm = [Float](repeating: 0, count: 2048)
var frame   = [Float](repeating: 0, count: 4096)

// per frame f in 2..<114, per channel:
vDSP_vmul(sigB + f*1024, 1, hann, 1, &frame, 1, 4096)          // window

splitRe.withUnsafeMutableBufferPointer { rp in
 splitIm.withUnsafeMutableBufferPointer { ip in
  var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
  frame.withUnsafeBufferPointer { fp in
    fp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: 2048) { cp in
      vDSP_ctoz(cp, 2, &split, 1, 2048)                        // even/odd split pack
    }
  }
  vDSP_fft_zrip(fftSetup, &split, 1, log2n, FFTDirection(kFFTDirection_Forward))

  // ONE scale folds vDSP's ×2 convention with the model's 1/sqrt(4096):  0.5 / 64 = 0.0078125
  var s: Float = 0.0078125
  vDSP_vsmul(split.realp, 1, &s, split.realp, 1, 2048)
  vDSP_vsmul(split.imagp, 1, &s, split.imagp, 1, 2048)
 }
}
```

> **CONTRACT F1 (bin 0's imaginary part).** After `vDSP_fft_zrip`, `imagp[0]` holds the **Nyquist real
> part**, not DC's imaginary part. `Dsp.kt:192-195` writes `im[0]` from a full complex FFT, where it is
> exactly `0` for real input. The port **must write `0.0f`** into `cac[imBase + 0*le + t]`, not
> `splitIm[0]`. Getting this wrong corrupts bin 0 of every frame of every chunk.

CaC layout is channel-major, real-before-imag, C-order `[4][2048][112]`
(`DSP:142-144`, `DSP:179-180`): `reBase = reChan*2048*112`, `imBase = (reChan+1)*2048*112`,
`reChan ∈ {0, 2}`. Writing `cac[reBase + b*112 + t]` is a **stride-112 scatter** — do it with
`vDSP_vsmul` into a contiguous 2048-element temp and then `cblas_scopy(2048, tmp, 1, &cac[reBase+t], 112)`,
or restructure the scratch as `[bin][frame]` and transpose once per chunk with `vDSP_mtrans`.
The transpose is 917 504 floats per chunk (3.5 MB) — measure both.

#### 1.2.3 Inverse — per frame

```swift
// Fill packed split-complex directly from the summed CaC spec:
//   realp[0] = cac[re, bin 0]   ;  imagp[0] = 0   (the model's Nyquist is zero — Dsp.kt:209)
//   realp[k] = cac[re, bin k]   ;  imagp[k] = cac[im, bin k]   for k = 1..2047
vDSP_fft_zrip(fftSetup, &split, 1, log2n, FFTDirection(kFFTDirection_Inverse))
splitRe.withUnsafeMutableBufferPointer { rp in
 splitIm.withUnsafeMutableBufferPointer { ip in
  var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
  frame.withUnsafeMutableBufferPointer { fp in
    fp.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: 2048) { cp in
      vDSP_ztoc(&split, 1, cp, 2, 2048)                        // → 4096 real samples
    }
  }
 }
}
// vDSP inverse real FFT is scaled by N (=4096). Kotlin's Fft.inverse already folds 1/N (Dsp.kt:80-86)
// and then multiplies by invScale = sqrt(4096) = 64. Net Apple scale = 64 / 4096 = 1/64.
var s: Float = 1.0 / 64.0
vDSP_vsmul(&frame, 1, &s, &frame, 1, 4096)
// OLA: ola[start+i] += frame[i] * hann[i]
vDSP_vma(&frame, 1, hann, 1, ola + f*1024, 1, ola + f*1024, 1, 4096)
```

Trim + normalise (`DSP:226-230`): `offset = nfft/2 + padL = 2048 + 1536 = 3584`, then
`out[k] = ola[offset+k] * recipEnv[offset+k]` for `k in 0..<114_660` — one `vDSP_vmul`.

> **CONTRACT F2 (accumulator precision).** Kotlin accumulates OLA and the envelope in **Double**
> (`DSP:222-224`, `DSP:252-253`). The code above accumulates in Float. At most `nfft/hop = 4` frames
> overlap any sample, so the relative error is ≈ 4 × 2⁻²⁴ ≈ 2.4e-7 — three orders below the model's own
> measured 63.4/69.0 dB fp16 parity (`DS:527`). **Ship Float, gate it with a golden test at ≥ 100 dB SNR
> against a Kotlin-derived reference.** The strict path, if the golden fails, is
> `vDSP_vspdp` → `vDSP_vmaD` → `vDSP_vdpsp` at ~2× the OLA cost.

#### 1.2.4 Expected speedup

Accelerate is measured at **~107 GFLOPS for N = 4096 single-precision complex FFT** on Apple Silicon
(arXiv 2603.27569's baseline). A 4096-pt complex FFT is `5·N·log₂N` = 245 760 FLOP → **~2.3 µs**; a real
FFT is roughly half → **~1.2 µs**. Including the window multiply, `ctoz`/`ztoc`, the two `vsmul`s and the
strided scatter, budget **4–6 µs per frame**.

| | Android (S23) | Apple projected | multiple |
|---|---:|---:|---:|
| per 4096-pt frame (incl. window + pack) | ~154 µs¹ | 4–6 µs | **26–39×** |
| `stft` per chunk | 34.6 ms¹ | ~1.0 ms | ~35× |
| `stft + istft/ola` over the 643 s job | **8 701 ms** | **~400–900 ms** | **~10–20×** (the OLA/spec-sum halves vectorise less well than the FFT) |
| share of `separate` today | 2.26 % | ~0.2 % | **−8 s of 385 s = −2.1 %** |

¹ `4079 ms ÷ 118 separated chunks ÷ 224 frames` — 118 = 276 − 158 skipped (`perf-plan-v4.md:430`).

> **Rank honestly: the vDSP audio win is 2.1 % of the audio wall.** It earns P1 not on its own size but
> because (a) it is ~3 days at zero quality risk, (b) the *same* vectorisation applied to the analyze
> pass's gate fill is worth **39 634 ms → ~0.3 s** (§2.4), and (c) once ORT drops 5× the DSP share rises
> from 2.3 % to ~7 % of the audio branch.

### 1.3 Lever 2 — the audio decode (14.4 %, second largest item, nobody has attacked it)

`decode = 55 589 ms` (`perf-plan-v4.md:413`) for 643 s of audio. It is `AudioDecoder.decode`'s
`MediaCodec` pump (`AD:255-310`) plus the channel fold (`AD:196-229`) and one
`SonicAudioProcessor` resample session (`AD:165-176`). Every element of it is scalar Kotlin.

Apple replacement, all of it Accelerate/AVFoundation:

| Android stage | Apple | expected |
|---|---|---|
| `MediaCodec` audio decode, one output buffer per `dequeueOutputBuffer(TIMEOUT_US=10_000)` iteration (`AD:290`) | `AVAssetReader` + `AVAssetReaderAudioMixOutput`, or `AVAudioFile`/`AVAudioConverter` | HW/optimised decode, no 10 ms polls |
| PCM16 → f32 `/32768f` scalar loop (`AD:204-229`) | ask `AVAssetReaderAudioMixOutput` for `kAudioFormatFlagIsFloat` **directly** — no conversion pass at all | free |
| >2-ch BS.775 fold, `HALF_POWER = 0.70710678f` (`AD:33`, `AD:216-228`) | `vDSP_vsmul` + `vDSP_vadd` per channel, or an `AVAudioConverter` channel map | ~free |
| `SonicAudioProcessor` 2-tap linear resample to 44 100 (`AD:170-174`) | `AVAudioConverter` with `.mastering` quality, **or** `vDSP_vgenp`. Sonic is a playback-speed tool with no anti-imaging filter — `AW:14-19` already records it costing ~−27 dB in-band | **quality improvement as well** |
| Two full passes: `stats` (sampled, 20 windows) then `stream` (`AP:155`, `AP:206`) | keep both — the sampled stats pass is already the A3 optimisation (`AD:56-70`) | unchanged |

Projected: **55 589 ms → 5 000–10 000 ms** (a 5.5–11× multiple, −45 to −50 s of the audio branch, i.e.
**−12 to −13 % of the whole stage — bigger than every audio item in `perf-plan-v4` §5 combined**).

> **This is the largest *addressable* audio item and the Android plan never ranked it,** because M2 only
> attributed it on 2026-08-04 (`perf-plan-v4.md:419-421`: "the pool it draws from is 4× larger than
> anyone thought"). It is P0 for the Apple port.

### 1.4 Lever 3 — core selection and QoS. The Apple-only dial Android could not reach

`DS:734-745` swept intra-op threads on the S23 and found **6 beats 8 by ~5 %**, with the stated mechanism:
"every intra-op barrier waits on the slowest thread and the S23's little cores are it — 4 of 6's five
chunks came in under 8's fastest."

**On Apple that mechanism is stronger, not weaker.** An A19 Pro is **2 P-cores at 4.26 GHz + 4 E-cores at
2.60 GHz** — a wider gap than the S23's 1×X3 + 2×A715 + 2×A710 + 3×A510, and the E-cores are far narrower
in issue width. Putting an E-core inside an intra-op barrier costs more here.

> **CONTRACT T1 — never use `ProcessInfo.activeProcessorCount` for intra-op threads.** It returns 6 on an
> A19 Pro (2 P + 4 E) and 10–16 on an M-series. That is exactly the mistake `DS:745`'s
> `availableProcessors` made and the sweep punished. Use the P-core count:

```swift
/// Logical CPUs at the highest performance level. Apple Silicon only; falls back sanely.
func performanceCoreCount() -> Int {
    var n: Int32 = 0
    var size = MemoryLayout<Int32>.size
    if sysctlbyname("hw.perflevel0.logicalcpu", &n, &size, nil, 0) == 0, n > 0 { return Int(n) }
    return max(1, ProcessInfo.processInfo.activeProcessorCount / 2)
}
```

Session options — port table:

| Android (`DS:715-730`) | Apple | keep? | why |
|---|---|---|---|
| CPU EP, **not** XNNPACK | CPU EP (or CoreML — §1.5) | **yes** | XNNPACK's fp16 kernels corrupt this graph's spectral branch (`DS:717-719`). Same kernels, same graph, same risk. |
| `setIntraOpNumThreads(min(cores, 6))` | `performanceCoreCount()`, clamped `2…6` | **change** | T1 |
| `session.intra_op.allow_spinning = "0"` | same | **yes** | On Apple a spinning `.userInitiated` thread *holds* a P-core, so this is more important, not less. Re-sweep. |
| `setCPUArenaAllocator(false)` | same | **yes, critical** | Without it "lmkd killed the app at 5.6 GB RSS" (`DS:713-714`). Under jetsam the equivalent is a hard kill with no warning. |
| `setMemoryPatternOptimization(false)` | same | **yes, critical** | Same reason. |
| `setOptimizedModelFilePath` | **do not add** | — | Measured, implemented, and **removed**: 881 ms saved for 157.6 MB of disk and +10.6 % RSS (`DS:646-657`, `perf-plan-v4.md:446-455`). |

**QoS — this is the new lever.** `perf-plan-v4.md:497-502` measured the cost of *not* having it: after A1
moved 12 961 ms off the producer, the wall recovered only 6 816 ms, because `packNv21` — **untouched
code** — went 60 677 → 66 768 ms (+10.0 %) from cache/core contention with the now-busier consumer.
**47 % of the expected saving was eaten by contention Android had no way to arbitrate.**

Apple does:

| shape | audio branch QoS | video branch QoS | rationale |
|---|---|---|---|
| **A / B** (censor-only) | n/a | `.userInitiated` | one branch, take everything |
| **C** (music removal, any length) | **`.userInitiated`** | **`.utility`** | Audio is the wall with 244 s of slack under the video branch (`perf-plan-v4.md:49`). `.utility` biases the video branch onto E-cores, so it **cannot** steal a P-core from ORT. The video branch getting slower is free. |

Two lines. Expected: recovers most of the ~47 % contention loss that Android measured on a *smaller*
perturbation. **Highest impact-per-line item in this document.**

> `AP:53-67`'s `thermalYield` and `demoteWhile` (blocking `Thread.sleep` at `THERMAL_STATUS_SEVERE`) exist
> precisely because Android could not express "run the sibling branch on the weak cores." **On Apple, QoS
> replaces the demotion mechanism.** Keep a thermal *observer* (`ProcessInfo.thermalStateDidChangeNotification`)
> and keep the yield as a last resort, but the primary mechanism is QoS. Measured `yield = 123 ms` on the
> S23 off-charger at 643 s (`perf-plan-v4.md:422`) — not a factor at clip length; **unmeasured at film
> length on a passively-cooled iPhone, which is §7's headline unknown.**

### 1.5 Lever 4 — ANE. High ceiling, low confidence, spike it

**What is favourable:**
- The graph is **fully static** — `SEG` fixed, `[1,2,114660]` and `[1,4,2048,112]` in, `[1,4,4,2048,112]`
  and `[1,4,2,114660]` out (`ML:104-109`, `DS:672-676`). Core ML's biggest constraint is satisfied.
- The weights are **already fp16** — 552 `FLOAT16` initializers (`perf-plan-v3.md:271`). ANE is fp16
  native. **On Android this was worthless**: ORT's CPU EP fp16 island is Conv+Pool only, htdemucs has 92
  Conv and 0 Pool and no Conv adjacent to another Conv, so `IsIsolatedFp16NodeOnCpu` demoted every one and
  inserted 201 `InsertedPrecisionFreeCast` nodes (`perf-plan-v3.md:264-283`). **On Apple the same fp16
  weights are the fast path.** This is the single strongest Apple-only argument in the plan.
- 47.6 % of the FLOPs are Conv/ConvTranspose (`perf-plan-v4.md:221`) and ANE is fundamentally a
  convolution engine — 3.8× faster and 9× more efficient than the GPU on a 256-channel 3×3 conv.

**What is against:**
- The **partition-fragmentation failure mode is already in this repo's evidence.** SwiftFormer-XS, with a
  published 0.7 ms ANE latency, ran **22 % slower than 2020 MobileNetV2** on ARM CPU because only 84 of
  362 nodes were EP-eligible (`perf-plan-v4.md:269-271`). A 1 531-node graph with ConvTranspose, 10
  Softmax at sequence lengths 896/448, and complex reshapes is a prime candidate for the same outcome.
- ORT's CoreML EP is a *partitioner*, not a compiler: unsupported nodes fall back to CPU with a copy and a
  sync at every boundary. As of 2026 ORT still has no first-class ANE EP; CoreML EP + `MLProgram` +
  `MLComputeUnits.cpuAndNeuralEngine` is the whole surface, and ANE-only is a hint, not a guarantee.
- ANE rejects convolutions with very large channel counts (documented ≥ ~32 000). Not obviously hit here,
  but must be checked.
- **fp16 activations already produced NaN on this graph.** `DS:216-224`: "a passage far louder than the
  track average pushes intermediate activations toward fp16's ~65504 ceiling and a chunk can come back
  non-finite. Observed 2026-07-29 on a 10.5-minute source." ANE is fp16 end-to-end. `nonFinite` (`DS:225`)
  must port as a **first-class logged metric**, not a silent guard.

**Recommended route: bypass ORT.** Convert `htdemucs_s26_f16.onnx` to Core ML directly with `coremltools`
(`ct.convert(..., minimum_deployment_target=.iOS17, compute_precision=ct.precision.FLOAT16)`) and ship an
`.mlpackage`. One model, one compile, one ANE plan, Core ML's own layout compiler — instead of ORT's
node-by-node partitioner.

> **Spike M-A5, with a hard gate. Abandon on any failure:**
> 1. Model compiles; `MLModel.modelDescription` matches A.1–A.7 shapes exactly.
> 2. Run with `MLComputeUnits.cpuAndNeuralEngine`, capture a Core ML Instruments trace: **≥ 85 % of
>    per-chunk compute on ANE, ≤ 4 CPU↔ANE handoffs per inference.** (The SwiftFormer precedent is the
>    reason this is a gate and not a hope.)
> 3. **≥ 2.0× on `inferNs`/chunk** versus the ORT CPU EP baseline on the same device. Below 2.0× it is not
>    worth a second model artifact and a second numerical surface.
> 4. **Spec SNR ≥ 63.4 dB and wave SNR ≥ 69.0 dB** against the ONNX CPU output — the exact bar the fp16
>    export cleared on Android (`DS:527`).
> 5. `nonFinite == 0` over the full `qa-assets/tv1.webm` audio track and over the 155-min film.
> 6. **The C1 gate's chunk decisions must be unchanged** (`skippedChunks` identical), since a divergent
>    stem changes nothing there but a divergent *level* would.

### 1.6 Lever 5 — concurrent chunk workers. Dead on iPhone, live on Mac

`perf-plan-v4.md:245-255`: 4 sessions × 1 thread (398.6 ms/chunk) ≈ 1 session × 4 threads (405.3 ms/chunk).
Chunk-level parallelism buys **1.5 %** at equal core count and **doubles RSS**, which on Android was the
objection ("doubles RSS toward the lmkd kill the arena is already disabled for").

| target | P-cores | peak RSS at 1 session | verdict |
|---|---:|---:|---|
| iPhone (A18/A19 class) | **2** | 1.30 GB (`DS:522`) | **Dead.** 2 sessions × 1 thread ≈ 1 session × 2 threads (the host table's own finding), and 2 × 1.30 GB = 2.6 GB is a guaranteed jetsam kill on anything below an 8 GB device. |
| iPad Pro / M-series base | 4 | 1.30 GB | 2 workers × 2 threads: ~1.8× throughput at 2.6 GB. Viable, measure. |
| Mac (M5 Pro class, 8 P + 4 E) | 8–12 | 1.30 GB | **3 workers × 4 threads ≈ 2.6–3× the audio branch.** No jetsam. This is the biggest single Mac-only win available. |

**If Mac gets 3 workers, the ranking inverts on Mac**: audio ~25 s vs video ~60 s on the 643 s clip, so the
**video branch becomes the wall on Mac for shape C.** Design §5's actor graph so both branches can be the
wall; do not hard-code "audio is slower."

Ordering constraint: the separator's overlap-add ring is inherently sequential (`OUT_CAP = SEG + STRIDE` is
"exactly the span of the two chunks that can be live" — `DS:615`). **Chunk workers must therefore parallelise
`inferChunk`'s STFT→ORT→iSTFT only**, and hand results back to a single ordered OLA writer. `DS:298-311`'s
`processChunk` bookkeeping (`flushPos`, `emitted`, `chunksDone`) stays strictly serial or the resume
contract (`skipChunks`, `DS:158`) breaks.

### 1.7 Does the O(T²) transformer change the optimal segment size on Apple?

**No. Keep `SEG = 114_660`.**

The census is hardware-independent (`perf-plan-v4.md:220-232`): 10 `Softmax` at sequence lengths 896
(waveform tower) and 448 (spectral tower), both linear in segment length ⇒ attention is O(T²) and is
9.45 of 91.96 GFLOP (10.3 %) at 2.6 s.

| SEG | GFLOP per second of audio | vs shipped | peak RSS (`DS:522-525`) |
|---|---:|---:|---:|
| 1.3 s | 33.55 | −5.1 % | ~0.65 GB (extrapolated) |
| **2.6 s (shipped)** | **35.37** | — | **1.30 GB** |
| 3.9 s | 37.19 | +5.1 % | 1.61 GB |
| 5.2 s | 39.01 | +10.3 % | — |
| 7.8 s (checkpoint native) | 42.64 | **+20.6 %** | 3.24 GB |

What changes on Apple, and in which direction:

1. **Per-chunk fixed cost falls** (faster CPU ⇒ the non-scaling part is a smaller share) ⇒ the optimum
   moves **shorter**, not longer. Never longer.
2. **Jetsam is harder than lmkd** ⇒ 7.8 s (3.24 GB) is dead on every iPhone, permanently.
3. **On ANE** the dial changes shape entirely: ANE's working set is on-chip SRAM measured in tens of MB, so
   a 1.30 GB activation footprint means heavy DRAM traffic. If §1.5's spike succeeds, **re-sweep SEG on the
   ANE path specifically** — the answer there may be shorter still.
4. **One Apple-only variant is worth a spike:** re-export at `SEG = 1.3 s`. It costs **−5.1 % compute per
   second of audio** and roughly **halves peak RSS to ~0.65 GB**, which is exactly the headroom needed to
   run **two concurrent chunk workers on a 2-P-core iPhone at today's memory peak.** Net: −5.1 % arithmetic,
   ×2 fixed cost, ×~1.8 parallelism. Plausibly a 1.6–1.7× audio win on iPhone. Ranked P3 because it
   invalidates every saved `audio.json` and forces the `check(STRIDE < SEG && SEG <= 2*STRIDE)` re-proof
   (`DS:142`), the re-export, the sha256 and `smokeShapes` (`ML:108`).

**Do not raise SEG for any reason.** `perf-plan-v4.md:297` lists the full cost.

### 1.8 Audio measurement plan (M-A series)

| # | measurement | how | decides |
|---|---|---|---|
| **M-A1** | Confirm §0.1's host is Apple Silicon; get ms/chunk on iPhone + Mac | 8 chunks of the shipped graph, ORT CPU EP, threads ∈ {2, 4, 6, 8}, `perf-plan-v4` §6.3's harness | **Everything.** The entire shape-C projection. |
| **M-A2** | The full M2 counter split, ported verbatim | port `DS:239-267` + `AP:81-100`'s two log lines exactly, so the Apple line diffs against the Android line | Ranks every audio item, as it did on Android |
| **M-A3** | AVAssetReader audio decode ms for 643 s of AAC + of Opus | one pass, no separator | Sizes §1.3 (the 55.6 s item) |
| **M-A4** | vDSP STFT/iSTFT vs the ported Kotlin, ns/frame, plus golden SNR | JVM-free unit bench + `DspTest`'s goldens | §1.2, and F2's Float-vs-Double call |
| **M-A5** | Core ML/ANE spike, gated per §1.5 | Instruments Core ML template | §1.5 go/no-go |
| **M-A6** | Concurrent chunk workers: 1/2/3 × {1,2,4} threads, ms/chunk **and** peak `phys_footprint` | interleaved in one cooled session | §1.6, per device class |
| **M-A7** | **One 155-min film, run to completion, off charger, on a passively-cooled iPhone** | full soak | The Android plan's M3 has never been run (`perf-plan-v4.md:68`). It is still the highest-information run available, and on iPhone it also answers thermal, which Android answered only on charger (`long-film-plan.md:39`). |

Protocol is `perf-plan-v3.md:380-390` verbatim, with one addition already learned there: **interleave A/B
configurations inside one cooled session and read per-unit counters, never stage wall** — Android saw
**31 % analyze drift across three runs in one session** (`perf-plan-v3.md:303-308`).

---

## 2. The analyze wall — producer-bound on Android, and the producer disappears on Apple

### 2.0 The Android baseline, exactly

`perf-plan-v4.md:483-505`, matched-temperature, post-A1+A6, `tv1.webm` 643 s, 6 430 sampled frames /
3 215 gate frames / 19 271 decoded frames:

```
producer   nv21-equiv 66 768 + gateGather 14 677 = 81 445 ms      (12.67 ms / sampled frame)
consumer   detect 9 969 + gateFill 39 634 + gate 25 446 = 75 049  (11.67 ms / sampled frame)
analyze wall                                             101 387 ms
```

Hard floor: **~20 332 ms of hardware decode** — 1.055 ms/frame × 19 271 (`perf-plan-v4.md:88-90`).

### 2.1 What each Android cost becomes on Apple

| Android item | ms | Apple mechanism | projected ms | why |
|---|---:|---|---:|---|
| `packNv21` → 640-px NV21 for ML Kit (`FS:478-503`) | 66 768 | **deleted** — hand Vision the decoder's `CVPixelBuffer` | **0** | §2.2 |
| `gatherGate` (75 264 strided bytes, `FS:596-624`) | 14 677 | same gather out of a **cached** IOSurface | ~150 | 34.9 µs/kB → ~0.3 µs/kB |
| `gateFromGathered` (150 528 float writes, `FS:642-656`) | 39 634 | vDSP, bit-exact (§2.4) | ~300 | 6 vectorised passes over 50 176 |
| `Infer.nsfw` INT8 on XNNPACK (`IN:83-91`) | 25 446 | **fp32 graph on ANE via Core ML** (§2.5) | ~2 500 | MobileNetV2 is the archetypal ANE graph |
| ML Kit detect at 640 px (`FT:203-207`) | 9 969 | Vision `VNDetectFaceRectanglesRequest`, ANE/GPU | ~10 000–19 000 | **the new consumer wall — must be measured, not assumed** |
| hardware decode of 19 271 frames | 20 332 | VideoToolbox H.264/HEVC | 15 000–25 000 | **the new producer wall** |

**Projected analyze wall ≈ max(decode 15–25 s, Vision 10–19 s, gate ~3 s) + serialisation ≈ 25–35 s**
against Android's 101 387 ms ⇒ **~3.0–4.0×**.

> **The wall moves from "CPU pixel arithmetic" to "decoder throughput and Vision."** Every optimisation
> aimed at pixel loops is aimed at a stage that no longer exists. Re-rank after M-V1.

### 2.2 Keeping decoder-native 4:2:0 end to end

```swift
let output = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: [
    kCVPixelBufferPixelFormatTypeKey as String:
        Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),   // NV12 — the decoder's native layout
    kCVPixelBufferIOSurfacePropertiesKey as String: [:],         // IOSurface-backed (required for zero-copy)
    kCVPixelBufferMetalCompatibilityKey as String: true          // lets §3 make an MTLTexture over the same pages
])
output.alwaysCopiesSampleData = false                            // no defensive memcpy per frame
```

> **CONTRACT V1.** Never request `kCVPixelFormatType_32BGRA` from the reader. That forces a full-frame
> VideoToolbox colour conversion on every decoded frame — the Apple equivalent of the mistake
> `FS:201-206` explicitly forbids on Android ("a concrete layout like `COLOR_FormatYUV420Planar` would
> guarantee a full-frame conversion on every Qualcomm NV12 component"). Ask for 4:2:0 and convert only the
> 224²/96² tensors you actually need.

> **CONTRACT V2 — Vision takes the pixel buffer directly.**
> `VNImageRequestHandler(cvPixelBuffer:orientation:options:)`. **Do not build a 640-px intermediate.**
> The 640-px cap exists on Android only because `InputImage.fromByteBuffer` takes NV21/YV12 and ML Kit's
> cost scales with input area (`FS:381-384`, `plan-v2` §1's 8.6 ms at 640 px). Vision does its own
> internal resize. **Measure `detect` ms/frame at native 1080p vs a pre-downscaled 640 before deciding**
> (M-V2); only add a downscale if native measures slower.
> `orientation` comes from the track's `preferredTransform`, replacing `FS:131`'s
> `probe(...).rotationDegrees` — with the same "not a multiple of 90 ⇒ treat as 0" fail-safe (`FS:128-131`).

### 2.3 vImage vs Metal vs Core Image for the 224² and 96² tensors

| path | verdict | why |
|---|---|---|
| **Hand-written SIMD/vDSP over the locked base address** | **v1 choice** | The only path that can be made **bit-identical** to `FS:531-566`. See CONTRACT V3. |
| vImage (`vImageConvert_420Yp8_CbCr8ToARGB8888` + `vImageScale_ARGB8888`) | **rejected for the gate tensor**, fine for previews/thumbnails | vImage uses its own fixed-point YpCbCr matrix, not Kotlin's `1436 / 352 / 731 / 1815 >> 10`. |
| Metal / MPS | **rejected for analyze**, chosen for render (§3) | Zero-copy in, but reading a 224² f32 buffer back for ORT/Core ML costs a CPU↔GPU sync per frame; ~0.3–0.8 ms of latency to save ~0.1 ms of work. Wins only when the consumer is also on GPU. |
| Core Image | **rejected on the per-frame hot path** | Per-render filter-graph analysis; measured ~50 % CPU vs Metal's ~20 % on the same `CVPixelBuffer` operation. |

> **CONTRACT V3 — the gate tensor is a quality surface, not a performance surface.**
> Android's A4 tried to build the gate tensor from a cheaper pixel source and measured **91.24 %
> censored-timeline recall against a ≥ 99.20 % bar, under-censoring 34.5 s of a 643 s clip**, gate firings
> 781 → 719 (`perf-plan-v4.md:99`, `:462-470`, `FS:574-580`). It was **reverted and marked do-not-retry**.
> The Apple port must reproduce `FS:531-566` exactly:
> - `sxMap[i] = crop.left + i*cw/224`, `syMap[i] = crop.top + i*ch/224` — built over the **crop rect**,
>   never over a downscaled buffer (`FS:396-400`).
> - Rotation case table `FS:544-549` character for character.
> - Chroma at `sx shr 1`, `sy shr 1` (`FS:552-553`).
> - Integer BT.601 **full-range**: `r = clamp(y + ((1436*v) >> 10))`, `g = clamp(y − ((352*u + 731*v) >> 10))`,
>   `b = clamp(y + ((1815*u) >> 10))`, then `/255f` (`FS:557-563`).
> - `NsfwGate.TABLE` (`NG:19-25`) is QA-tuned against these exact numbers.
> Port `FrameSamplerConvertTest`'s zero-delta equivalence assertion (all four rotations, both chroma
> layouts) as a Swift Testing suite. It is the only thing standing between the port and A4's regression.

### 2.4 Vectorising the gate fill bit-exactly

The `>> 10` is an arithmetic shift = floor division by 1024. `1436·v` for `v ∈ [−128, 127]` peaks at
183 908 and `352·u + 731·v` at ±138 112 — both **exactly representable in Float** (< 2²⁴), and
`× (1/1024)` is exact (power of two). Therefore `floor(1436·v × (1/1024))` **equals** `(1436*v) shr 10`
for every integer input, and the whole loop vectorises with no bit difference:

```swift
// gathered: 3 × 50176 bytes, Y,U,V triples in FS:618-621's order.
// Split to planar first (one pass), then 6 vDSP passes per plane-group:
vDSP_vfltu8(yPlane, 1, &yF, 1, 50176)                       // UInt8 → Float
vDSP_vfltu8(uPlane, 1, &uF, 1, 50176); var m128: Float = -128
vDSP_vsadd(&uF, 1, &m128, &uF, 1, 50176)                    // u -= 128   (same for v)
var c1436: Float = 1436, inv1024: Float = 1.0/1024.0
vDSP_vsmul(&vF, 1, &c1436, &t, 1, 50176)
vDSP_vsmul(&t, 1, &inv1024, &t, 1, 50176)
vDSP_vfloor(&t, 1, &t, 1, 50176)                            // == (1436*v) shr 10, exactly
vDSP_vadd(&yF, 1, &t, 1, &r, 1, 50176)
var lo: Float = 0, hi: Float = 255
vDSP_vclip(&r, 1, &lo, &hi, &r, 1, 50176)
var inv255: Float = 1.0/255.0
vDSP_vsmul(&r, 1, &inv255, gateTensor, 1, 50176)            // R plane at offset 0
// G at offset 50176, B at offset 100352 — NCHW, exactly FS:561-563's layout.
```

**39 634 ms → ~300 ms over the job. ~130×.** (Against the desktop-JVM scalar figure of 0.353 ms/frame it is
~4×, which is the honest vectorisation multiple; the 130× includes escaping the Snapdragon dmabuf and the
Android core contention.)

Same treatment for `cropToTensor` (`FS:693-733`), noting its **different contract**: output is
**0..255 unscaled**, not `/255` (`ML:140-144`), and the crop is a *square* of side `max(w,h) × 1.5`
(`FS:697-700`), not `padRect`'s per-axis 25 %.

### 2.5 The NSFW gate: reverse Android's INT8 decision

Android ships `nsfw_mnv2_140_int8.onnx` (static QDQ, per-channel, `ML:81-86`) because on Snapdragon it was
**2.30× faster than fp32** on-device (61 745 → 26 844 ms, `ML:52-58`) with 99.20 % recall of the fp32
censored timeline and +16.2 s censored (errs toward covering).

On Apple:
- **ANE is fp16-native and does not want an INT8 QDQ graph.** CoreML EP either dequantises it or refuses
  the partition.
- `ML:73-76` records that **the fp32 graph is kept alongside** (`nsfw_mnv2_140_f32.onnx`) precisely so the
  swap is one `assetName` + one `sha256`.
- fp32 **is** the reference the 99.20 % recall was measured *against*. Shipping it removes the recall debt
  entirely and is a strict quality improvement.
- MobileNetV2 1.4-224 is the archetypal ANE-friendly graph: pure depthwise-separable convolution, static
  `[1,3,224,224]`, ~4.4 M weights.

> **Decision: on Apple, ship `nsfw_mnv2_140_f32.onnx`, converted to Core ML fp16, `MLComputeUnits.all`.**
> Expected ~0.5–1.0 ms/inference on ANE vs Android's 7.91 ms/gate-frame (25 446 ÷ 3 215). Gate on
> **censored-timeline recall ≥ 99.20 % against the Android INT8 reference EDL** for the same clip — the bar
> `ML:59-61` already established, with `Models.kt`'s own control noting that ML Kit face counts differ
> run-to-run (4 786 vs 4 550) while the censored timeline is bit-stable, so **the timeline is the valid
> gate and the face count is not** (`ML:62-68`).

**Batching.** Android measured **batch 2 = 0.65×, batch 4 = 0.87×, batch 8 = 0.47×** per frame vs batch 1
and concluded "depthwise-separable convnets saturate at batch 1 on CPU" (`ML:290-292`). **That result is
about CPU thread saturation and does not transfer to ANE**, where a fixed per-prediction dispatch cost
amortises over a batch. Re-run the sweep with `MLBatchProvider` at N ∈ {1, 2, 4, 8, 16}. Cost is
`N × 150 528 × 4 B` = 0.6 MB → 9.6 MB, and the latency penalty (`N/5` seconds of EDL delay) is irrelevant
for an offline job. P2.

### 2.6 Frame-level fan-out mapped onto Swift structured concurrency

Android's B2 (`perf-plan-v4.md:137`) is "keep ONE extractor and ONE codec, fan `packNv21` +
`convertToTensor` across a small pool with N frames in flight," with the real constraint being "holding N
`Image`s open against the codec's output-buffer count."

The Swift shape, and **the one thing that makes it correct**:

> **CONTRACT C1 — the gate fans out freely; face detection does not.**
> - **Gate firings are order-independent.** `NsfwGate.intervals` starts with `val sorted = firingsMs.sorted()`
>   (`NG:58`) and the KDoc says "Input need not be sorted" (`NG:54`). Gate tasks may complete in any order.
> - **Face tracking is order-dependent and stateful.** `FaceTracker` keys a live map by tracking id, evicts
>   on `EVICT_AFTER_MS = 2_000` of **source** time (`FT:264`, `FT:309-310`), caps crops at `VOTE_CAP = 5`
>   (`FT:231`) and emits spans sorted by `startMs`. Detections **must** enter the tracker in
>   presentation-time order. Fan out the detection *request*, then re-serialise results through a small
>   reorder buffer keyed on `ptsMs` before `onFaces`.

```swift
// One reader, bounded in-flight frames, ordered face results, unordered gate results.
await withThrowingTaskGroup(of: FrameResult.self) { group in
    var inFlight = 0
    let maxInFlight = 4                       // == Android RING (FS:68). See §4.3 for why 4.
    var reorder = ReorderBuffer(nextPts: 0)   // detection results only

    while let sample = reader.copyNextSampleBuffer() {
        guard shouldSample(sample.pts) else { continue }
        if inFlight == maxInFlight { try await drainOne(&group, &reorder); inFlight -= 1 }
        let frame = Frame(sample)             // retains the CVPixelBuffer; ONE owner
        group.addTask(priority: branchQoS) { try await process(frame) }
        inFlight += 1
    }
    while inFlight > 0 { try await drainOne(&group, &reorder); inFlight -= 1 }
}
```

Also inherit the two **latent Android concurrency bugs** that only bite under fan-out
(`perf-plan-v4.md:147-154`) — on Apple they are not latent, because fan-out is the design:

1. `FW:955-997` shares **one 110 KB gender-crop `FloatBuffer` for the entire pass**, whose KDoc says "safe
   to share because pass 1 is single-threaded" (`FW:944-949`). Under concurrency this silently
   cross-contaminates gender votes — a **quality bug, not a crash**. Worse, `FW:600-601` used to claim the
   opposite. **On Apple: make every model input buffer a task-local, never an actor field.** Same for
   `gateInput` (`FW:560-563`).
2. `IN:42` is now a `ConcurrentHashMap` with `computeIfAbsent` (`IN:123-131`) precisely because
   `OrtSession.run` is thread-safe but the session *cache* was not. **On Apple: pre-warm every session
   before the first fan-out task, or use an actor-isolated lazy.**

### 2.7 The unexplained 2.3× per-frame film regression — designing the Apple catcher

`perf-plan-v4.md:54-58`: the 155-min film analyzed at **~40–43 ms/sampled-frame** against the short clip's
**17.8 ms**, on a *smaller* 1728×720 source. "Nobody knows why. That unexplained gap is worth more than any
single item below."

Android could never diagnose it because `JobStats` logs **wall clock only** (`JS:60`, `JS:67`). Wall alone
cannot distinguish "more work" from "slower work."

> **CONTRACT I1 — every stage, on every segment, logs `(wallNs, cpuNs, threadCount)`.** The ratio
> `wallNs / cpuNs` is the whole diagnosis: if CPU time per frame is flat and wall rises, it is
> scheduling/thermal/contention; if CPU time per frame itself rises, it is real work.

```swift
import Darwin

/// Total CPU time consumed by this process, user + system, in nanoseconds.
func processCPUNanos() -> UInt64 {
    var info = task_thread_times_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_thread_times_info_data_t>.size /
                                       MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_THREAD_TIMES_INFO), $0, &count)
        }
    }
    guard kr == KERN_SUCCESS else { return 0 }
    let u = UInt64(info.user_time.seconds) * 1_000_000_000 + UInt64(info.user_time.microseconds) * 1_000
    let s = UInt64(info.system_time.seconds) * 1_000_000_000 + UInt64(info.system_time.microseconds) * 1_000
    return u + s
}
```

Hypothesis matrix and the probe that discriminates each:

| hypothesis | Android evidence | Apple probe | discriminator |
|---|---|---|---|
| Thermal throttling over 70+ min | `maxThermal = 0` — but **on charger** (`long-film-plan.md:39`); off charger at 643 s `yield = 123 ms` (`perf-plan-v4.md:422`) | `ProcessInfo.thermalState` + `.thermalStateDidChangeNotification`, sampled per segment | `wall/cpu` rises, per-frame `cpuNs` flat |
| Memory pressure / compression | RSS 985 MB peak; ~500 MB of retained crops, freed at `finish()` (`long-film-plan.md:43`) | `phys_footprint` + `os_proc_available_memory()` per segment; `DispatchSource.makeMemoryPressureSource` | footprint climbs monotonically across segments |
| Tracker state growth | `tracks` never evicted pre-Phase-1; fixed to `peakLiveCrops=12, liveTracks=1` (`long-film-plan.md:65`) | port `FaceTracker.retention()` and log per segment | `liveTracks` trends up |
| Content difference (more faces ⇒ more Vision work) | never separated from the rest | per-segment `facesDetected`, `gateFirings`, `voteCrops`, `voteNanos` | per-frame `cpuNs` rises **and** face counts rise together |
| Decoder degrading with GOP depth / seek | film is H.264 with B-pyramids; segment boundaries snapped to sync samples (`CP:44-58`) | per-segment `framesDecoded` vs `framesSampled`, decode `cpuNs` | `framesDecoded / framesSampled` rises above 3.0 |
| QoS demotion after sustained load | not observable on Android | log the task's effective QoS + `wall/cpu` | Apple-only; `wall/cpu` steps at a segment boundary |

**Emit one greppable line per segment**, mirroring Android's `SOAK` prefix so the two logs diff directly:

```
SOAK seg=007 stage=analyze wall=142118ms cpu=498221ms thr=4 frames=3000 decoded=8991
     footprintMB=612 availMB=2410 thermal=1 pressure=0 detectNs/f=2481000 gateNs/f=983000
     liveTracks=2 peakLiveCrops=14 firings=88
```

---

## 3. The render wall

### 3.0 Android baseline

| metric | value | citation |
|---|---:|---|
| render wall, 643 s / 19 267 frames | **89 411 ms** | `perf-plan-v3.md:20` |
| per frame | **4.702 ms** = 212.7 fps | `perf-plan-v3.md:324-325` |
| reproduced to the millisecond as a control | 89 411 → 89 202 | `perf-plan-v4.md:374`, `:514` |
| AV1 hardware decode share | 1.123 ms/frame | `perf-plan-v3.md:324` |
| same clip re-encoded H.264 | 3.579 ms/frame | `perf-plan-v3.md:325` |
| **unattributed** | ~3.6 ms/frame (76 %) | `perf-plan-v4.md:140` |
| GPU busy | 55.4 % mean, `kgsl` **289 MHz against a 719 MHz ceiling = 22.3 %** | `perf-plan-v3.md:321-322` |
| blur ablation: 41× the fragment work | 22 003 → 22 078 ms = **+0.34 %** | `perf-plan-v3.md:313-318` |
| whole-frame vs rect vs span-floor spread | **0.20 %** over three runs | `perf-plan-v3.md:301-306` |

The GPU is not the wall and the blur is free. Render is **encoder-and-decoder paced.**

### 3.1 Is it still encoder-paced with VideoToolbox?

**Probably not, and that is the whole win — but it must be measured, and Android could never measure it.**

> **CONTRACT R1 — instrument the pacing directly.** Log `notReadyNs`: cumulative nanoseconds spent waiting
> on `AVAssetWriterInput.isReadyForMoreMediaData == false`. If `notReadyNs / renderWall > 0.5`, the encoder
> is the wall and only encoder settings matter. If it is < 0.1, the wall is decode or GL and §3.3 applies.
> This single counter answers in one run the question Android spent 76 % of its render budget unattributed on.

Settings, and the Android line each replaces:

| Android | Apple | note |
|---|---|---|
| media3 `Transformer` on the **Main Looper** (`RP:169-170`) | `AVAssetWriter` on any queue | **Apple win: no main-thread pin.** Android's render pipeline was tied to the UI thread. |
| `VideoEncoderSettings.setBitrate(...)` with the tier cap (`RP:151-166`, `RP:275-281`) | `AVVideoAverageBitRateKey` — port the tier table verbatim: ≤480p 4 Mbps, ≤720p 10, ≤1080p 16, ≤1440p 24, else 45; and `min(source × 1.3, cap)` (`RP:64`, `RP:266`) | one bitrate per job, resolved once (`FW:683`) |
| `setiFrameIntervalSeconds(2f)` (`RP:158`) | `AVVideoMaxKeyFrameIntervalDurationKey: 2.0` | |
| `setEncoderPerformanceParameters(operatingRate: 1000, priority: 1)` (`RP:164`) | `AVVideoExpectedSourceFrameRateKey` = source fps; **do not** set low-latency rate control | Android's value is a crash-avoidance pin for SM8550 (`RP:159-163`), not a perf setting |
| `HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL` (`RP:141`) | `AVVideoColorPropertiesKey` + a Metal tone-map pass, or `CIContext` with the working colour space | |
| n/a | **`input.expectsMediaDataInRealTime = false`** | **Mandatory.** `true` paces the writer to realtime and has been measured costing up to 3 000 ms of session init. |
| n/a | `input.requestMediaDataWhenReady(on:using:)` | Never sleep-poll `isReadyForMoreMediaData`. |
| `texturePoolCapacity = 3` — *untested*, ~16 MB at 1080p (`CE:78-86`, `CE:95`) | `AVAssetWriterInputPixelBufferAdaptor.pixelBufferPool` handles depth | **Do not port the constant.** It exists as an unmeasured media3 pipeline-depth experiment (`perf-plan-v4.md:101`). The adaptor's pool is the Apple equivalent and is already deep. |

### 3.2 The cheapest per-frame blur with zero CPU copies

```
AVAssetReaderTrackOutput (420v, IOSurface, MetalCompatible)
   → CVMetalTextureCacheCreateTextureFromImage  →  MTLTexture over the SAME IOSurface   [0 copies]
   → Metal compute: horizontal blur → scratch0 (downscaled), vertical → scratch1        [GPU only]
   → Metal compute: composite(input, scratch1, regions) → dst texture                   [GPU only]
   → dst texture is an MTLTexture over a CVPixelBuffer from adaptor.pixelBufferPool      [0 copies]
   → adaptor.append(pixelBuffer, withPresentationTime:)  → VideoToolbox reads the same IOSurface
```

Zero CPU copies end to end, and unified memory means the "GPU copy" is not a copy either.

Port the blur geometry **verbatim** — it is the thing measured as free, and changing it re-opens a
measurement:

| rule | value | citation |
|---|---|---|
| `sigmaPx` | `max(0.1, blurAmount/100 × 40 × (min(w,h)/1080))` | `CE:140` |
| downscale `d` | smallest of `{1,2,4,8}` with `sigmaPx/d ≤ 4`, else `8` | `CE:142` |
| scratch size | `max(1, w/d) × max(1, h/d)`, two of them | `CE:143-144` |
| `radius` | `min(10, ceil(2.5 × sigmaPx/d))`, floor 1 | `CE:146` |
| kernel | normalised 1-D Gaussian, `MAX_RADIUS+1 = 11` entries, tail zero | `CE:290-300` |
| `MAX_REGIONS` | **8**, largest kept, never silently — must match `FW:1358` | `CE:30`, `FW:1351-1358` |
| feather | **outward only**, `max(regionSize × 0.15, 0.002)`, `smoothstep` on all four edges | `CE:382-387` |
| grayscale | BT.**709** `dot(rgb, (0.2126, 0.7152, 0.0722))` — note: BT.601 for YUV, BT.709 for luma | `CE:400` |
| solid fill | wins outright over blur+grayscale | `CE:394-396`, `CE:101` |
| output size | **always** `Size(inputWidth, inputHeight)` — no scaling anywhere | `CE:162` |

Three draw paths (`CE:165-201`): copy / whole-frame / regions. **Keep the copy path** — it is the
`!full && regions.isEmpty()` fast path and on a typical film it is most frames.

**Core Image alternative for v1 speed-of-build:** `CIContext(mtlDevice:)` +
`ciContext.render(ciImage, to: pixelBuffer, bounds:, colorSpace:)` is also zero-copy and is 1 day instead of
5. Ship it if R1 says the encoder is the wall (then the shader's speed is irrelevant); replace with Metal if
R1 says otherwise.

### 3.3 The decode-side lever Apple has and Android does not

`perf-plan-v4.md:139` (item B4): media3's `FLAG_READ_WITHIN_GOP_SAMPLE_DEPENDENCIES` gives 30–50 %
droppable samples on the render path, but **"analyze uses raw `MediaExtractor` and has no clean
equivalent."**

Apple does have one. `CMSampleBuffer` carries `kCMSampleAttachmentKey_DependsOnOthers` and
`kCMSampleAttachmentKey_IsDependedOnByOthers` per sample, and `VTDecompressionSession` accepts
`kVTDecodeFrame_DoNotOutputFrame`. So for the **analyze** pass, where only 1 frame in 3 is sampled
(10 fps from 29.97 — `FS:121`), you can:

1. read compressed samples via `AVAssetReaderTrackOutput(outputSettings: nil)`,
2. feed a `VTDecompressionSession`,
3. pass `kVTDecodeFrame_DoNotOutputFrame` for any frame that is neither sampled nor depended on.

Saves the output-surface allocation and colour conversion on ~2 of every 3 frames. Ranked **P2 spike**:
the win is real but the plain `AVAssetReader` path may already be under the Vision wall, in which case it
buys zero. Gate on M-V1.

### 3.4 Render projection

| | Android | Apple projected | multiple |
|---|---:|---:|---:|
| 643 s clip, 19 267 frames | 89 202 ms (212.7 fps) | **30 000–45 000 ms** (430–640 fps) | **2.0–3.0×** |
| 155-min film (`long-film-plan.md:31`) | ~582 s | ~200–290 s | 2.0–3.0× |

**Ranked last.** Render is 44 % of shape A but **12 % of shape B** (`perf-plan-v4.md:48`) and **0 % of
shape C**. Every render millisecond is worth less than every analyze or audio millisecond on two of the
three shapes.

---

## 4. Memory

### 4.0 Android's measured peaks

| item | bytes | citation |
|---|---:|---|
| htdemucs at `SEG = 2.6 s` | **1.30 GB** | `DS:522-525` |
| htdemucs at 3.9 s / 7.8 s | 1.61 GB / 3.24 GB | `DS:522-525` |
| combined, concurrent branches | 1 294 MB (sequential: 1 287 MB — **the same**) | `FW:277-281` |
| segmented censor branch alone | ~0.53 GB | `video-performance-overhaul-plan.md:104` |
| film analyze, pre-Phase-1 (retained face crops) | 985 MB peak, ~500 MB of crops, freed at `finish()` | `long-film-plan.md:43` |
| film analyze, post-Phase-1 | **500 MB**, `peakLiveCrops = 12`, `liveTracks = 1` | `long-film-plan.md:65` |
| without arena-off / memory-pattern-off | **lmkd killed the app at 5.6 GB** | `DS:713-714` |
| `setOptimizedModelFilePath` (removed) | 1 267 532 → 1 401 700 KB (+10.6 %) | `DS:648-651` |
| concurrency floor | `CONCURRENT_MIN_TOTAL_MEM = 6 656 MiB` | `FW:1369` |

### 4.1 Where Apple differs — four ways, three of them worse

1. **jetsam is a hard, deterministic, per-process limit.** Android's lmkd kills by `oom_score` under
   *global* pressure — a well-behaved app can survive a spike, and the app's own peak of 1.3 GB coexisted
   with 985 MB of video branch. iOS kills the moment `phys_footprint` crosses the device's per-process
   limit, with only `didReceiveMemoryWarning` as notice. **Strictly less forgiving.**
2. **`phys_footprint` counts IOSurface.** It is `dirty + compressed + IOKit`. Every `CVPixelBuffer` you
   retain counts at full size even though VideoToolbox and Metal share the same pages. Android's `VmHWM`
   (`JS:76-80`) counts RSS and does **not** account gralloc the same way. **The identical pipeline will
   measure higher on iOS than on Android.** The pixel-buffer ring becomes a first-class budget line for
   the first time.
3. **Compressed memory counts against you.** Android's zram let the app survive (badly — `perf-plan-v3.md:382`
   records 3.75 GB of zram and chunk times going 19 s → 70 s). On iOS you cannot compress your way out of
   a jetsam kill; compression *is* your footprint.
4. **Unified memory is the one genuine improvement.** Metal textures over IOSurfaces are the same pages as
   the decoder's output — no duplicate GPU copy exists to budget for. Android's GL path had the same
   property via EGLImage, so this is parity rather than a win, but it means §3.2's zero-copy chain costs
   nothing in footprint.

### 4.2 Measuring it correctly

> **CONTRACT M1 — `phys_footprint`, never `resident_size`.** `resident_size` from `TASK_BASIC_INFO`
> excludes compressed pages and misattributes IOSurface. It is not what jetsam reads and it is not what
> Xcode's memory gauge shows.

```swift
import Darwin

/// The bytes jetsam counts. Matches Xcode's memory gauge. iOS 13+/macOS 10.15+.
func physFootprintBytes() -> UInt64? {
    var info = task_vm_info_data_t()
    var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size /
                                       MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) {
        $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        }
    }
    guard kr == KERN_SUCCESS, count >= TASK_VM_INFO_REV1_COUNT else { return nil }
    return info.phys_footprint
}

#if os(iOS)
import os
/// Bytes remaining before this process is jetsammed. iOS 13+. Returns 0 on macOS.
@inline(__always) func availableBeforeJetsam() -> UInt64 { UInt64(os_proc_available_memory()) }

/// Recover the device's actual per-process limit — not documented, but derivable.
func jetsamLimitBytes() -> UInt64? {
    guard let f = physFootprintBytes() else { return nil }
    return f + availableBeforeJetsam()
}
#endif
```

> **CONTRACT M2 — iOS has no `VmHWM`, so you must sample, and Android's harness deliberately did not
> have to.** `JS:16-19`: "Peak RSS is the kernel's own high-water mark (`/proc/self/status` VmHWM), not a
> sampled maximum, so no polling interval can miss a spike between ticks." That guarantee is **lost** on
> Apple. Compensate:
> - a dedicated `.utility` timer sampling `physFootprintBytes()` at **10 Hz** for the whole job;
> - **plus forced samples at every known allocation cliff**: before and after each ORT/Core ML session
>   create, before and after each htdemucs chunk, at every segment boundary, at `FaceTracker.finish()`;
> - **plus** `DispatchSource.makeMemoryPressureSource(eventMask: [.warning, .critical])`, logged, because a
>   pressure event is the only *event* signal iOS gives before the kill.

### 4.3 The Apple budget, itemised

| item | bytes | source |
|---|---:|---|
| htdemucs weights, fp16 file demoted to fp32 at load | ~176 MB | 87.9 MB file, `ML:104-109`; demotion `perf-plan-v3.md:281-283` |
| htdemucs per-chunk activations | ~1.10 GB | 1.30 GB total measured minus weights, `DS:524` |
| `inL`/`inR` (2 × 435 708 × 4) | 3.49 MB | `DS:97-98`, `DS:614` |
| `outL`/`outR`/`wsum` (3 × 217 854 × 4) | 2.61 MB | `DS:110-112`, `DS:615` |
| `Stft` scratch (`sigA` 117 760 + `sigB` 121 856 f32; `ola`+`env` 121 856 f64 ×2) | 2.91 MB | `DSP:248-254` |
| `specOut` (2 stems × 917 504 × 4) | 7.34 MB | `DS:636` |
| `timeOut` (2 stems × 2 × 114 660 × 4) | 1.83 MB | `DS:637` |
| direct input buffers (`wav` 2×114 660×4 + `spec` 917 504×4) | 4.59 MB | `DS:638-641` |
| `segL/segR/wav/sumCac/waveL/waveR/emitBuf/weight/gateMono` | ~5.4 MB | `DS:161-175` |
| **pixel-buffer ring, N in flight** | **N × 3.11 MB @1080p, N × 12.4 MB @4K** | 1920×1080×1.5; 3840×2160×1.5 |
| gate tensor (3 × 224² × 4) | 0.60 MB | `FW:560-563` |
| gathered gate bytes ring (4 × 150 528) | 0.60 MB | `FS:140-144` |
| crop tensor (3 × 96² × 4) | 0.11 MB | `FW:962-964` |
| NSFW fp32 session | ~17.3 MB | `ML:44` |
| YAMNet session (4.0 M weights) | ~16 MB | `ML:113-114` |
| genderage session | 1.3 MB | `ML:133` |
| render Metal scratch (2 × (w/d)×(h/d) RGBA8) | 0.26 MB at d=8, 16.6 MB at d=1 | `CE:143-155` |
| face-track live crops (post-Phase-1 bound) | ~2 MB (`peakLiveCrops = 12`) | `long-film-plan.md:65` |
| **PCM scratch — DISK, not RAM** | 176 400 B/s = **635 MB/hour**; 1.645 GB for 155 min | `AP:337-341`, `long-film-plan.md:125` |

**Steady-state audio branch ≈ 1.33 GB. Plus video branch ≈ 60–110 MB. Total ≈ 1.40–1.44 GB** — under the
1.5 GB PRD target, but with essentially **no headroom on a 4 GB iPhone** (jetsam ≈ 2 GB) once IOSurface
accounting lands.

### 4.4 Memory contracts

> **CONTRACT M3 — N is the knob and it must be bounded.** At N = 4 the ring is 12.4 MB at 1080p. An
> unbounded `AsyncStream` with a two-second lead at 30 fps is N = 60: **187 MB at 1080p, 746 MB at 4K.**
> That single mistake is the jetsam kill. Android bounded it structurally with `QUEUE = 2` and `RING = 4`
> (`FS:67-68`) and documented why: "`RING` must stay above the frames the consumer side can be holding —
> `QUEUE` queued plus the one it is reading — or the decoder would overwrite pixels still being read."
> **Port the number and the reason.**

> **CONTRACT M4 — replace `CONCURRENT_MIN_TOTAL_MEM` with a live measurement.** Android had to guess from
> device class (`FW:1361-1369`: 6.5 GiB, and the constant was *wrong* at 7 GiB and silently disabled
> concurrency on the very device it was sized for). Apple has `os_proc_available_memory()`. Gate the
> concurrent schedule on **`availableBeforeJetsam() ≥ 2.0 GB` measured immediately before `branches()`**,
> re-checked at each segment boundary, with a documented demotion to sequential. Strictly better than a
> device-class guess and it fixes Android's own bug class.

> **CONTRACT M5 — request the entitlement.** `com.apple.developer.kernel.increased-memory-limit` (and
> `increased-debugging-memory-limit` for the benchmark scheme). A 1.4 GB media pipeline is exactly the case
> it exists for.

---

## 5. Concurrency architecture (Swift 6, Sendable-clean)

### 5.1 Topology

```
JobActor                                  // job state, checkpoints, progress coalescing
├── AudioBranch : Task(priority: .userInitiated)          [shape C: always .userInitiated]
│    ├── AudioReader          → AsyncChannel<PCMBatch>            bound 2
│    ├── SeparatorActor        (owns inL/inR/outL/outR/wsum rings; strictly serial)
│    │     ├── MusicGateActor  (YAMNet session, thread-confined — MG:32-33)
│    │     ├── StftEngine      (one FFTSetup per worker; §1.2)
│    │     └── InferPool       (1 worker on iPhone, 2–3 on Mac; §1.6)
│    └── AacWriterActor       ← AsyncChannel<EmitBatch>           bound 1
└── VideoBranch : Task(priority: shape == .musicRemoval ? .utility : .userInitiated)
     ├── ReaderStage: AVAssetReader → AsyncChannel<Frame>          bound 4   ← CONTRACT M3
     ├── analyze TaskGroup (maxInFlight 4)
     │     ├── GateTask   (order-free)      → AsyncChannel<Firing>  unbounded is safe (8-byte Longs)
     │     └── DetectTask (order-bound)     → ReorderBuffer → FaceTrackerActor
     └── RenderStage: reader → Metal → AVAssetWriterInput
           backpressure = isReadyForMoreMediaData  (no channel; §5.3 B5)
```

### 5.2 Choosing the channel type

**`AsyncStream` is the wrong primitive here.** SE-0406 (backpressure for `AsyncStream`) was **returned for
revision in 2023 and has not landed**; the unbounded-buffer failure mode it exists to fix is exactly
CONTRACT M3's jetsam kill.

| use | primitive | why |
|---|---|---|
| Frame handoff (M3-critical) | **`AsyncChannel` from `swift-async-algorithms`**, or a hand-rolled bounded actor channel | `AsyncChannel` has **no internal buffer** — a producer suspends until its value is consumed. That is exactly Android's `Channel(QUEUE=2)` semantics, and it is unbounded-buffer-proof by construction. |
| PCM batches | `AsyncChannel` | Android is a plain synchronous callback (`AD:132`, `AP:206-211`) — zero buffering. A large channel here buffers minutes of PCM. |
| Emit batches | `AsyncChannel`, capacity 1 | Android reuses **one** `emitBuf` of `2 × STRIDE` floats = 825 KB (`DS:168`). |
| Firings | plain array behind the tracker actor | 8 bytes each; ~800 for a 643 s clip. |
| Progress | `AsyncStream(bufferingPolicy: .bufferingNewest(1))` | Drop-oldest is correct for progress. |

### 5.3 Where backpressure MUST exist — the enumerated contract

| # | boundary | bound | consequence of getting it wrong | Android precedent |
|---|---|---|---|---|
| **B1** | `AVAssetReader` → frame consumers | **4** in-flight `CVPixelBuffer`s | 187 MB @1080p / 746 MB @4K at a 2 s lead ⇒ jetsam | `QUEUE=2`, `RING=4` (`FS:67-68`) |
| **B2** | detect results → `FaceTrackerActor` | reorder window = 4 | out-of-order detections corrupt track association and gender votes | `FT` is stateful; `EVICT_AFTER_MS=2000` on **source** time (`FT:264`) |
| **B3** | audio decode → `Separator.feed` | 2 batches | buffers minutes of 44.1 kHz stereo f32 (353 KB/s) | Android is a synchronous callback: bound 0 (`AP:206`) |
| **B4** | separator emit → AAC writer / PCM scratch | 1 batch (825 KB) | doubles the emit buffer for nothing | one reused `emitBuf` (`DS:168`) |
| **B5** | render frames → `AVAssetWriterInput` | **`isReadyForMoreMediaData`** — no channel at all | queueing ahead of the encoder buffers full-res frames | media3's own `FinalShaderProgramWrapper` release (`CE:80-83`) |
| **B6** | progress → UI | coalesce to integer-percent changes only | Android measured **−12.2 %** from this exact fix | `AP:186-192`, `FW:592-593`, `FW:1101` |
| **B7** | ORT/Core ML input buffers → concurrent tasks | **one buffer per task, never shared** | silent cross-contamination of gender votes / gate tensors | the latent bug at `FW:944-949` and `FW:955-997` |

### 5.4 Sendable rules

| type | status | rule |
|---|---|---|
| `CVPixelBuffer`, `CMSampleBuffer` | **not `Sendable`** | Wrap in `struct Frame: @unchecked Sendable` that owns exactly one retain and documents single-ownership transfer. Never let two tasks touch one buffer. |
| `MLModel` | thread-safe for `prediction` | Share one per model; still pre-warm before fan-out. |
| `OrtSession` | `run` is thread-safe; the **cache** is not | Pre-warm every session before the first fan-out task. Android needed a `ConcurrentHashMap` + `computeIfAbsent` for exactly this (`IN:123-131`). |
| model input buffers | **not shareable** | B7. Task-local, always. `IN:27-31` states the contract: "safe to call concurrently **as long as each caller owns its own `input` buffer** … the buffer is viewed in place and sharing one across threads corrupts the tensor rather than failing." |
| `FFTSetup` | read-only after create | One per worker (~48 KB). |
| Separator rings | single-owner | Inside `SeparatorActor`; if you use `nonisolated(unsafe)` for the hot loops, the actor must be the only entry point. |
| `AVAssetWriter` / `AVAssetReader` | not `Sendable` | One actor each. |
| anything `@MainActor` | **forbidden in the pipeline** | Android had to run `Transformer` on the Main Looper (`RP:169-170`). Apple does not. Do not reintroduce the constraint. |

---

## 6. What to build in v1 vs defer — ranked by impact-on-the-wall ÷ engineering cost

Multiples are **per shape**, against the Android S23 numbers in §0–§3. They **do not multiply together**
— items 1, 5, 7 and 8 all draw on the same analyze pool, exactly as `perf-plan-v4.md:320-323` warns.
Re-measure after every item.

| # | item | wall it hits | cost | risk | A (censor short) | B (censor film) | C (music) | v1? |
|---|---|---|---|---|---|---|---|---|
| **1** | **Hand Vision the decoder's `CVPixelBuffer`; never build a 640-px NV21** (§2.2, V1/V2) | analyze producer | **0 d** (it is the natural API) | Vision recall re-tune | **1.6–2.0×** | 1.6–2.0× | 0 | **yes** |
| **2** | **Per-branch QoS: audio `.userInitiated`, video `.utility` on shape C** (§1.4) | audio wall | **0.1 d** | none | 0 | 0 | **1.1–1.5×** | **yes** |
| **3** | **ORT session options ported verbatim + intra-op = `hw.perflevel0.logicalcpu`** (§1.4, T1) | audio wall | 1 d | none | 0 | 0 | **1.1–1.3×** + prevents a jetsam class | **yes** |
| **4** | **AVFoundation audio decode replacing `MediaCodec` + Sonic** (§1.3) | 14.4 % of audio | 3 d | none (quality *improves*) | 0 | 0 | **1.13–1.17×** | **yes** |
| **5** | **NSFW gate: fp32 graph on Core ML/ANE, not INT8 on CPU** (§2.5) | analyze consumer | 2 d | recall gate ≥ 99.20 % | **1.15–1.3×** | 1.15–1.3× | 0 | **yes** |
| **6** | **Bounded frame channel (N=4) + `phys_footprint` harness + entitlement** (§4, M1–M5) | none | 2 d | none | — | — | — | **yes — it is the thing that stops 1–5 killing the app** |
| **7** | **`expectsMediaDataInRealTime=false` + `requestMediaDataWhenReady` + Core Image blur** (§3.1–3.2) | render | 2 d (CI) / 6 d (Metal) | shader parity | **1.5–2.5×** on render | 1.1× (render is 12 %) | 0 | **yes (CI first)** |
| **8** | **vDSP: gate fill, gather, STFT/iSTFT, OLA, spec sum** (§1.2, §2.4) | analyze consumer + audio | 4 d | F1/F2 goldens | **1.1–1.25×** | 1.1–1.25× | **1.02×** | **yes** |
| **9** | **`(wallNs, cpuNs, threadCount)` per stage per segment** (§2.7, I1) | none | 1 d | none | — | — | — | **yes — the only thing that can solve the 2.3× film gap** |
| **10** | Frame-level fan-out, `TaskGroup` maxInFlight 4, gate order-free / detect re-ordered (§2.6, C1) | analyze | 4 d | B2/B7 correctness | 1.1–1.4× **if** decode is not already the wall | same | 0 | **measure first (M-V1), then yes** |
| 11 | **htdemucs → Core ML MLProgram on ANE** (§1.5) | 70 % of audio | 10–20 d | **high** — 6-point gate | 0 | 0 | **1.0× or 2–4×** | **defer, spike in M0** |
| 12 | **N concurrent chunk workers (Mac only)** (§1.6) | audio | 4 d | RAM (Mac only) | 0 | 0 | **Mac 2.6–3×; iPhone 1.0×** | defer to v1.1 |
| 13 | `kVTDecodeFrame_DoNotOutputFrame` on non-sampled analyze frames (§3.3) | analyze decode | 5 d | frame-accuracy | 1.1–1.2× | 1.1–1.2× | 0 | defer |
| 14 | Batched NSFW on ANE, N ∈ {2,4,8,16} (§2.5) | analyze consumer | 2 d | none | 1.05× | 1.05× | 0 | defer |
| 15 | `SEG = 1.3 s` re-export ⇒ two iPhone chunk workers (§1.7) | audio | 8 d | re-export + `audio.json` invalidation | 0 | 0 | iPhone 1.6–1.7× | defer |
| 16 | SCNet-small replacement separator (`perf-plan-v4.md:262`) | audio | XL | licence unverified | 0 | 0 | 2.06× at +1.5 dB SDR | defer indefinitely |

### 6.1 Do not do — with the reason, so nobody re-derives it

| item | why dead |
|---|---|
| INT8 htdemucs (any variant) | **Measured 0.55× and 0.44×** — slower than fp32, spectral branch destroyed. `ConvInteger` has no fused requant and no fast ARM path, and Conv is 47.6 % of FLOPs. `perf-plan-v4.md:188-206` |
| Raising `SEG` above 2.6 s | +5.1 % at 3.9 s, **+20.6 % at 7.8 s**, and 3.24 GB. `perf-plan-v4.md:214-238` |
| Two full-RSS htdemucs sessions on iPhone | 2 × 1.30 GB, 2 P-cores, and chunk parallelism buys 1.5 % at equal cores. `perf-plan-v4.md:240-256` |
| `setOptimizedModelFilePath` / any graph pre-serialisation | 881 ms saved for 157.6 MB of disk and +10.6 % RSS. Implemented, measured, removed. `DS:646-657` |
| `gateEvery` 2 → 4 | Zero wall after the fill moves, full product risk (can miss a <400 ms NSFW event). `perf-plan-v4.md:103` |
| `maxDim` 640 → 480 | Fail-open that killed the 5-fps experiment: a fully visible face at 13.56 s, found only by rendered-pixel diff. `perf-plan-v4.md:102`, `perf-plan.md:198-223` |
| Building the gate tensor from an already-downscaled buffer (A4) | **91.24 % recall, 34.5 s under-censored.** Built, measured, reverted, marked do-not-retry. `FS:574-580` |
| Per-segment compressed passthrough | One format description per `AVAssetWriterInput`, same constraint as `MediaMuxer`'s single `stsd`. `RP:105-111` |
| Core Image on the per-frame analyze path | Per-render graph analysis; ~50 % CPU vs Metal's ~20 %. |
| `AVAssetImageGenerator` per sampled frame | Per-frame seek; Android's equivalent was rejected in M1. |
| `expectsMediaDataInRealTime = true` | Paces the writer to realtime; up to 3 000 ms of session init. |
| `ProcessInfo.activeProcessorCount` for intra-op threads | CONTRACT T1. The exact mistake `DS:745` made. |
| Unbounded `AsyncStream` for pixel buffers | CONTRACT M3. SE-0406 never landed. |

### 6.2 End-to-end projection

Against Android's shipped, matched-temperature S23 numbers.

| shape | asset | Android | Apple v1 (items 1–10) | multiple | realtime |
|---|---|---:|---:|---:|---|
| **A — censor-only** | `tv1.webm`, 643 s | analyze 101 387 + render 89 202 = **191 327 ms** | analyze 28 000 + render 35 000 = **~63 000 ms** | **~3.0×** | 3.36× → **10.2×** |
| **B — censor-only film** | 155 min, 1728×720 | ~70 min video branch | Mac **~18–23 min**; iPhone **~28–45 min** (thermal-dependent) | Mac 3–4×, iPhone 1.5–2.5× | — |
| **C — music, short** | `tv1.webm`, 643 s | `separate` **385 420 ms** (wall = audio) | iPhone **~120–130 s**; Mac **~65–75 s** | iPhone **3.0–3.2×**, Mac **5.1–5.9×** | 0.60× → 1.9× / 3.9× |
| **C — music, film** | 155 min | ~108 min audio branch | iPhone ~34–36 min; Mac ~18–21 min | 3.0×/5.5× | — |

**Two ranking inversions the design must survive:**

1. **On Mac with item 12, the video branch becomes the wall on shape C.** Audio ~25 s vs video ~60 s on the
   643 s clip. `branches()`'s `max(audio, video)` must not assume which side is longer, and the progress
   bands (`FW:896-927`) must handle either.
2. **On iPhone at film length, thermal may dominate everything.** Android's only film run was **on a
   charger** with `maxThermal = 0` (`long-film-plan.md:39`), and a passively-cooled iPhone at 100 % of 2
   P-cores for 35 minutes is a different physical problem. **M-A7 is the run that decides it and it has
   never been done on either platform.**

---

## 7. Benchmarking harness

### 7.1 What to measure

**Per stage, per segment, always:**

| field | source |
|---|---|
| `wallNs` | `ContinuousClock` / `mach_absolute_time` |
| `cpuNs` | `task_info(TASK_THREAD_TIMES_INFO)` delta — §2.7 |
| `threads` | `ProcessInfo.activeProcessorCount` + effective QoS |
| `footprintMB` | `physFootprintBytes()` — §4.2, sampled 10 Hz + at cliffs |
| `availMB` | `os_proc_available_memory()` (iOS) |
| `thermal` | `ProcessInfo.thermalState.rawValue`, max over the stage |
| `lowPower` | `ProcessInfo.isLowPowerModeEnabled` |
| `pressure` | count of `.warning` / `.critical` memory-pressure events |

**Per-unit counters — the Android discipline, ported verbatim.** `perf-plan-v3.md:388-390`: "Prefer in-run
counters to stage wall-clock — they are per-unit and survive a drifting device, which is exactly how §5's
render conclusion was reached while analyze was falling apart around it."

| stage | counters | Android source |
|---|---|---|
| audio | `chunksDone`, `skippedChunks`, `stftNs`, `inferNs`, `olaNs`, `gateNs`, `gatherNs`, `flushNs`, `encodeNs`, `decodeNs`, `yieldNs`, `sessionCreateNs`, `nonFinite`, `framesFed` | `DS:239-267`, `AP:81-100` |
| analyze | `framesDecoded`, `framesSampled`, `getBufNs`, `gatherNs`, `gateFillNs`, `gateNs`, `detectNs`, `voteNs`, `voteCrops`, `voteAbstained`, `voteFailures`, `liveTracks`, `peakLiveCrops`, `gateFirings`, `intervalCount`, `faceTracks` | `FS:305-309`, `FW:649-652`, `FW:1118-1121`, `FW:1056-1061` |
| render | `framesIn`, `framesOut`, `blurNs`, `compositeNs`, `appendNs`, **`notReadyNs`** | new — CONTRACT R1 |

**Quality gates re-run on every perf change** (a faster wrong answer is a regression —
`video-performance-overhaul-plan.md:138`):

| gate | bar | source |
|---|---|---|
| censored-timeline recall vs the Android reference EDL | **≥ 99.20 %** | `ML:59-61` |
| net censored time | **≥ 0** (errs toward covering) | `perf-plan-v4.md:466` |
| `gateFirings` / `intervalCount` | within the run-to-run noise floor (INT8 control: 100.00 %, +0.0 s) | `ML:62-64` |
| face **count** diff | **NOT a valid gate** — ML Kit gave 4 786 vs 4 550 on two identical runs | `ML:64-67` |
| separated `.m4a` | byte-identical where the pipeline is deterministic | `DS:506`, `perf-plan-v4.md:167` |
| htdemucs stem parity | spec ≥ 63.4 dB, wave ≥ 69.0 dB | `DS:527` |
| `nonFinite` | **0** | `DS:216-225` |
| music-gate skip rate | log it; a *change* needs a listening test on the worst newly-skipped chunk | `perf-plan-v4.md:426-442` |

### 7.2 How to log

- **`os_signpost`** intervals for every stage and every per-frame/per-chunk unit → Instruments timeline,
  Core ML template, Metal System Trace.
- **One greppable `OSLog .info` line per stage**, prefixed `SOAK`, with the same field names Android uses,
  so `grep SOAK` on both platforms produces diffable output. Format in §2.7.
- **A `--benchmark` scheme** that additionally writes one JSON object per run into the App Group container
  (`runId`, device, OS, thermal start/end, every counter above) so runs diff programmatically instead of by
  eye. This is what Android never had, and it is the reason `perf-plan-v3.md:303-308`'s 31 % drift had to
  be discovered by hand.

### 7.3 Protocol — non-negotiable, ported from `perf-plan-v3.md:380-390`

1. One configuration per run.
2. Cool between runs: wait until `ProcessInfo.thermalState == .nominal`; **log the start state in every
   run** — Android's A1 was mis-measured at −14.1 % purely because a 33.0 °C baseline was compared against
   a 28.9 °C build; the true figure was **−3.4 %** (`perf-plan-v4.md:479-487`).
3. Never compare a run to one taken more than two runs earlier in the same session.
4. **Interleave A/B configurations inside one cooled session and read per-unit counters, never stage wall**
   (`perf-plan-v4.md:71-74`).
5. Carry a **control** — a metric that must reproduce. Android's was render wall (89 411 → 89 202, 0.05 %).
   Apple's should be `inferNs / chunk` on a fixed 30 s audio clip.
6. Off-charger for every thermal claim. Android's only film run was on a charger and therefore says nothing
   about thermal (`long-film-plan.md:39`).

### 7.4 The comparison table to fill in

Android column is pre-filled and cited. Fill the rest on real hardware.

| # | metric | S23 measured | cite | Apple target | iPhone (___) | Mac (___) | ×  |
|---|---|---:|---|---:|---:|---:|---:|
| **Shape A — `tv1.webm`, 643 s, censor-only, rect blur, strictness default** |
| A.1 | total wall | 191 327 ms | `perf-plan-v4.md:514` | ≤ 65 000 | | | |
| A.2 | analyze wall | 101 387 ms | `perf-plan-v4.md:514` | ≤ 30 000 | | | |
| A.3 | render wall | 89 202 ms | `perf-plan-v4.md:514` | ≤ 40 000 | | | |
| A.4 | frames decoded / sampled / gated | 19 271 / 6 430 / 3 215 | `perf-plan-v4.md:88`, `:380` | same | | | |
| A.5 | producer µs / sampled frame | 12 670 | `perf-plan-v4.md:493` | ≤ 1 500 | | | |
| A.6 | gate fill µs / gate frame | 12 330 | `perf-plan-v4.md:494` | ≤ 200 | | | |
| A.7 | gate `session.run` µs / gate frame | 7 914 | `perf-plan-v4.md:494` | ≤ 1 200 | | | |
| A.8 | face detect µs / sampled frame | 1 550 | `perf-plan-v4.md:494` | ≤ 2 500 | | | |
| A.9 | render µs / frame | 4 702 | `perf-plan-v3.md:324` | ≤ 2 100 | | | |
| A.10 | `notReadyNs` / render wall | — | new | report | | | |
| A.11 | peak footprint | 530 MB (RSS) | `overhaul:104` | ≤ 700 MB | | | |
| A.12 | `gateFirings` / `intervalCount` | 781 / 76 | `perf-plan-v4.md:477` | ± noise | | | |
| A.13 | censored timeline | 386.9 s | `perf-plan-v4.md:477` | recall ≥ 99.20 % | | | |
| **Shape C — `tv1.webm`, 643 s, music-only, `vocals`** |
| C.1 | `separate` wall | 385 420 ms | `perf-plan-v4.md:432` | ≤ 130 000 | | | |
| C.2 | ORT `session.run` | 270 177 ms | `perf-plan-v4.md:413` | ≤ 105 000 | | | |
| C.3 | ms / separated chunk | 2 136 | `DS:737` | ≤ 800 | | | |
| C.4 | audio decode | 55 589 ms | `perf-plan-v4.md:414` | ≤ 10 000 | | | |
| C.5 | STFT + iSTFT/OLA | 8 701 ms | `perf-plan-v4.md:413` | ≤ 900 | | | |
| C.6 | YAMNet gate | 7 453 ms | `perf-plan-v4.md:413` | ≤ 2 500 | | | |
| C.7 | AAC encode | 26 584 ms | `perf-plan-v4.md:413` | ≤ 8 000 | | | |
| C.8 | flush (divide + softclip) | 7 953 ms | `perf-plan-v4.md:413` | ≤ 1 200 | | | |
| C.9 | chunks skipped / total | 158 / 276 (57 %) | `perf-plan-v4.md:430` | **identical** | | | |
| C.10 | peak footprint | 1 300 MB (RSS) | `DS:524` | ≤ 1 500 MB | | | |
| C.11 | `nonFinite` | 0 | `DS:225` | **0** | | | |
| C.12 | stem parity vs Android `.m4a` | — | `DS:527` | spec ≥ 63.4 dB, wave ≥ 69.0 dB | | | |
| **Shape B — 155 min film, 1728×720 @ 23.976, censor-only** |
| B.1 | analyze wall | ~4 230 s | `perf-plan-v4.md:48` | ≤ 1 400 s | | | |
| B.2 | render wall | ~582 s | `perf-plan-v4.md:48` | ≤ 290 s | | | |
| B.3 | **ms / sampled frame** | **40–43** vs the clip's 17.8 | `perf-plan-v4.md:56` | **≤ 1.5× the clip's** | | | |
| B.4 | **wall ÷ cpu per segment** | — | new (I1) | **flat across segments** | | | |
| B.5 | peak footprint | 500 MB (RSS) | `long-film-plan.md:65` | ≤ 700 MB | | | |
| B.6 | max thermal state, **off charger** | never measured | `long-film-plan.md:39` | report | | | |
| B.7 | `finish()` stall | 1 ms (post-Phase-1) | `long-film-plan.md:65` | ≤ 50 ms | | | |
| **Shape C — 155 min film, music removal (combined wall)** |
| D.1 | audio branch | ~108 min (projected) | `perf-plan-v4.md:49` | ≤ 36 min | | | |
| D.2 | video branch | ~70–80 min (projected) | `perf-plan-v4.md:49` | ≤ 25 min | | | |
| D.3 | **which branch is the wall** | audio | `perf-plan-v4.md:49` | **report — it may invert on Mac** | | | |
| D.4 | kill + resume at 50 %: work redone | ≤ 1 segment / 1 chunk | `long-film-plan.md:107` | same | | | |

---

## 8. Open questions, ranked by how much they move the plan

1. **Is `perf-plan-v4` §6.3's "host" an Apple Silicon Mac?** (M-A1, 20 minutes.) If yes, shape C is already
   5× better for free and items 11/12/15 are luxuries. If no, item 11 (ANE) moves to P0 and every shape-C
   projection in §6.2 drops.
2. **Does a passively-cooled iPhone hold its clocks for 35 minutes of 2-P-core ORT?** (M-A7.) Android's only
   film run was on a charger. This decides whether iPhone is a films device at all, and it is also the
   leading candidate for the unexplained 2.3× per-frame regression.
3. **What is Vision's face-detect cost per frame at native 1080p vs 640 px?** (M-V2.) It becomes the analyze
   consumer wall the moment item 1 lands, and there is no Android number that transfers — ML Kit's 1.59 ms
   is a different detector.
4. **How many CoreML partitions does the 1 531-node htdemucs graph produce?** (M-A5 gate 2.) The
   SwiftFormer-XS precedent (`perf-plan-v4.md:269-271`) says fragmentation, not FLOPs, decides this.
5. **Does ANE fp16 reproduce the NaN failure?** (`DS:216-224` observed it on Android at 10.5 minutes.) ANE
   is fp16 end-to-end with no fp32 fallback per-node.
6. **Is the jetsam limit on the target iPhone above 2.0 GB with the increased-memory entitlement?** Decides
   whether the 6 GB floor in the PRD's open question 1 can move down.
