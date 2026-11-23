# spec-audio.md — Naqi music-removal pipeline, exact porting spec (Android → Apple)

Source of truth: the shipped Kotlin at
`/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter/app/src/main/java/com/haithamassoli/naqi/audio/`
plus `ml/Models.kt`, `scripts/htdemucs_export.py`, `scripts/htdemucs_post.py`, `scripts/stft_golden.py`,
and the measured findings in `docs/`. Every constant below is quoted with `file:line`. Paths are
repo-relative to the Android root unless absolute.

Graph IO in §1 was re-read directly out of the shipped artifact
(`app/src/main/assets/models/htdemucs_s26_f16.onnx`, 1531 nodes, opset `ai.onnx:18`) — not from docs.

---

## 0. Pipeline shape (one page)

```
AVAsset audio track
  → decode to PCM16 @ source rate/channels        [AudioDecoder.decode]
  → channel fold to stereo (BS.775 for >2ch)      [AudioDecoder.kt:203-229]
  → resample to 44100 Hz f32 interleaved          [AudioDecoder.kt:165-176]
  → (pass 1, sampled) mean/std of the mono mix    [AudioDecoder.kt:71-88]
  → (pass 2, streaming) DemucsSeparator.feed      [DemucsSeparator.kt:185-203]
        normalize (x-mean)/std → ring buffer
        per 103194-sample chunk:
          MusicGate score (YAMNet) + ±2 dilation  [DemucsSeparator.kt:337-358]
            music? → STFT → htdemucs ONNX → Σ kept masked specs → 1 iSTFT
                     + Σ kept time branches
            no music? → raw input passthrough (same OLA weights)
          triangular-weighted overlap-add into a 217854-cell ring
        flush: /wsum → ×std + mean → softclip → emit
  → AAC-LC 44.1 kHz stereo 192 kbps .m4a          [AacWriter.kt]
  → mux with the untouched source video track     [Remux.mux]
```

Everything downstream of the decoder is **44 100 Hz, stereo, interleaved f32**. Nothing resamples on
egress (A6; `AacWriter.kt:14-19`).

Two driver entry points, same separator:
| function | when | sink | file:line |
|---|---|---|---|
| `AudioPipeline.removeMusic` | short sources (`jobDir == null`) | straight into `AacWriter` | `AudioPipeline.kt:141-231` |
| `AudioPipeline.removeMusicResumable` | `durationMs ≥ Eta.CONFIRM_THRESHOLD_MS` or forced segments | int16 LE scratch `audio.pcm`, one AAC encode at the end | `AudioPipeline.kt:352-472` |
| `AudioPipeline.transcodeToAac` | separator deleted; makes a muxable AAC copy of a non-AAC source | `AacWriter` | `AudioPipeline.kt:264-294` |

---

## 1. htdemucs graph IO

Artifact: `htdemucs_s26_f16.onnx`, 87.9 MB, sha256 `df8a2c2c8dd06ca279f58646dacc007b3e1e07436f31a3189408f0336c56eba5`
(`ml/Models.kt:104-109`). fp16 **weights**, f32 **IO** (`keep_io_types=True`, `scripts/htdemucs_post.py:32`).
Exported torch 2.13 dynamo exporter, opset 18, from sevagh/demucs.onnx's fork with STFT/iSTFT outside the
graph (`docs/m0-spikes.md:37`, `scripts/htdemucs_export.py:38-47`).

### 1.1 Tensors

| role | name | shape | dtype | meaning |
|---|---|---|---|---|
| input 0 | `input` | `[1, 2, 114660]` | f32 | normalized mix waveform, **planar** (ch0 block then ch1 block) |
| input 1 | `x` | `[1, 4, 2048, 112]` | f32 | CaC spectrogram, channel-major `[ch0.re, ch0.im, ch1.re, ch1.im]` |
| output 0 | `out_spec` | `[1, 4, 4, 2048, 112]` | f32 | masked CaC spec, `[batch, stem, cac_ch, bin, frame]` |
| output 1 | `out_wave` | `[1, 4, 2, 114660]` | f32 | time branch, `[batch, stem, channel, sample]` |

**Do not bind by name.** The shipped Kotlin matches inputs and outputs **by tensor rank**
(`DemucsSeparator.kt:671-678` for inputs — rank 3 = wav, rank 4 = spec; `:688-698` for outputs — rank 5 =
spec, rank 4 = time). Reproduce the rank dispatch; the names above are what the export script sets
(`scripts/htdemucs_export.py:45-46`) and are correct for this artifact, but the driver never reads them.

### 1.2 Geometry constants (all in `DemucsSeparator.kt` companion)

| symbol | value | derivation | line |
|---|---:|---|---|
| `SEG` | `114_660` | `int(2.6 s × 44_100)` | `:529` |
| `STRIDE` | `103_194` | `int(0.90 × 114_660)` — 10 % overlap | `:563` |
| overlap | `11_466` samples (0.26 s) | `SEG − STRIDE` | derived |
| `MAX_SHIFT` | `22_050` | 0.5 s zero pre-pad, deterministic `shift_offset = 0` | `:564` |
| `NFFT` | `4096` | | `:568` |
| `HOP` | `1024` | | `:569` |
| `BINS` | `2048` | `NFFT/2`, Nyquist bin dropped | `:565` |
| `LE` | `112` | `ceil(SEG / HOP)` = `ceil(114660/1024)` | `:566` |
| `STEM_SPEC` | `917_504` floats | `4 × BINS × LE` — one stem's CaC block | `:567` |
| `LOOKAHEAD` | `206_388` | `DILATE × STRIDE` | `:584` |
| `IN_CAP` | `435_708` | `2×SEG + LOOKAHEAD` | `:614` |
| `OUT_CAP` | `217_854` | `SEG + STRIDE` — span of the two live chunks | `:615` |
| sample rate | `44100` | `AudioDecoder.kt:30`, `AacWriter.kt:36` | |
| channels | 2 (stereo) | fixed everywhere | |

`SEG` and `STRIDE` are locked together by an assertion that runs in the constructor:
`check(STRIDE < SEG && SEG <= 2*STRIDE)` — i.e. `ceil(SEG/STRIDE) == 2` (`DemucsSeparator.kt:142`).
Three things depend on it (`:122-141`): `skipChunks` never over-skips, `OUT_CAP` never aliases, and
`IN_CAP` covers the worst-case lookback. Overlap **above 50 %** breaks all three.

### 1.3 Why 2.6 s — do not "improve" it

Measured on an S23, 30 s clip (`docs/m0-spikes.md:41-47`, corroborated `docs/perf-plan-v4.md:236-247`):

| segment | ONNX IO | peak RSS | wall-clock | vocals vs 7.8 s | f16 parity spec/wave | GFLOP/s-of-audio |
|---|---|---:|---:|---:|---:|---:|
| 7.8 s | `[1,2,343980]` + `[1,4,2048,336]` | 3.24 GB | 1.4–4.2× RT | reference | 61.5 / 65.9 dB | 42.64 (+20.6 %) |
| 3.9 s | `[1,2,171990]` + `[1,4,2048,168]` | 1.61 GB | 1.37× RT | 26.1 dB | 64.4 / 68.2 dB | 37.19 (+5.1 %) |
| **2.6 s (shipped)** | **`[1,2,114660]` + `[1,4,2048,112]`** | **1.30 GB** | **1.33× RT** | **24.2 dB** | **63.4 / 69.0 dB** | **35.37** |
| 1.3 s | — | — | — | — | — | 33.55 (−5.1 %) |

2.6 s is the **compute optimum**, not a RAM compromise: the cross-domain transformer's attention is
O(T²) (10 `Softmax` at seq len 896 / 448, `docs/perf-plan-v4.md:216-218`). More RAM does not reopen this
dial. Changing `SEG` requires a matching re-export (`scripts/htdemucs_export.py`).

### 1.4 Per-chunk data volumes (sizing the Swift buffers)

| buffer | floats | bytes | line |
|---|---:|---:|---|
| `input` tensor (wav) | 229 320 | 917 280 | `DemucsSeparator.kt:638` |
| `x` tensor (spec) | 917 504 | 3 670 016 | `:640` |
| `out_spec` (all 4 stems) | 3 670 016 | 14 680 064 | graph |
| `out_wave` (all 4 stems) | 917 280 | 3 669 120 | graph |
| `specOut` (kept only) | `nKeep × 917 504` | 3.67 / 7.34 MB | `:636` |
| `timeOut` (kept only) | `nKeep × 229 320` | 0.92 / 1.83 MB | `:637` |
| input ring `inL`+`inR` | 871 416 | 3.49 MB | `:97-98` |
| output ring `outL`+`outR`+`wsum` | 653 562 | 2.61 MB | `:110-112` |
| `emitBuf` | 206 388 (`2×STRIDE`) | 825 552 | `:168` |

The graph **always computes all four stems**; only the kept ones are copied out of the runtime
(`DemucsSeparator.kt:689-696`). Output-trimming re-export is an open, un-costed idea (`docs/perf-plan-v4.md:170`).

---

## 2. STFT / iSTFT contract (outside the graph)

Implemented in `Dsp.kt`; independently reproduced in numpy by `scripts/stft_golden.py`. Both must agree
with your Swift port to **atol 1e-4** (`DspTest.kt:20`, `:84`, `:93`).

### 2.1 Forward — `Stft.forward(ch0, ch1, T) -> FloatArray[4*bins*le]`

| item | value | line |
|---|---|---|
| n_fft | 4096 | `DemucsSeparator.kt:568` |
| hop | 1024 | `:569` |
| window | **periodic** Hann, `w[n] = 0.5*(1 − cos(2πn/nfft))`, denominator `nfft` NOT `nfft−1`, computed in **Double** | `Dsp.kt:132` |
| forward scale | `1/sqrt(nfft)` = `1/64` = `0.015625` exactly, applied to re and im | `Dsp.kt:133`, `:193-194` |
| inverse scale | `sqrt(nfft)` = `64` | `Dsp.kt:134`, `:213-214` |
| centering | `center=True`, reflect pad `nfft/2 = 2048` each side | `Dsp.kt:177` |
| pad mode | **torch reflect** — mirror EXCLUDING the edge sample. NOT the demucs.onnx C++ off-by-one | `Dsp.kt:235-239` |
| bins kept | `0 .. bins-1` = `0..2047`; **Nyquist bin 2048 is dropped** | `Dsp.kt:192-195` |
| frames kept | slice `[2 : 2+le]` of the padded frame grid | `Dsp.kt:184` |

Two-stage padding. Stage A is demucs' own `_spec` re-pad; stage B is `torch.stft(center=True)`.

| quantity | formula | value at `T = SEG = 114660` | line |
|---|---|---:|---|
| `le` | `ceil(T/hop)` | 112 | `Dsp.kt:129` |
| `padL` | `hop/2*3` | 1536 | `Dsp.kt:244` |
| `padR` | `padL + le*hop − T` | 1564 | `Dsp.kt:245` |
| `paddedSeg` | `T + padL + padR` = `(le+3)*hop` | 117 760 | `Dsp.kt:246` |
| `nframes` | `paddedSeg/hop + 1` = `le + 4` | 116 | `Dsp.kt:247` |
| `sigA` len | `paddedSeg` | 117 760 | `Dsp.kt:248` |
| `sigB` len | `paddedSeg + nfft` | 121 856 | `Dsp.kt:249` |
| `ola`/`env` len | `paddedSeg + nfft` (Double) | 121 856 | `Dsp.kt:252-253` |
| iSTFT trim offset | `nfft/2 + padL` | 3 584 | `Dsp.kt:227` |

`reflectPad(src, srcLen, l, r, dst)` exactly (`Dsp.kt:235-239`):
```
for j in 0..<l:  dst[j]              = src[l - j]        // src[l], src[l-1], …, src[1]
                 dst[l ..< l+srcLen] = src[0 ..< srcLen]
for k in 0..<r:  dst[l+srcLen+k]     = src[srcLen-2-k]
```
Precondition `srcLen > max(l, r)`; holds for both stages at production sizes, so the torch `pad1d`
short-signal guard never fires (`Dsp.kt:233-234`).

Forward loop (`Dsp.kt:184-196`), per channel `ci ∈ {0,1}`:
```
reBase = (2*ci)     * bins * le
imBase = (2*ci + 1) * bins * le
for f in 2 ..< 2+le:
    start = f*hop
    re[i] = Float(sigB[start+i] * win[i]); im[i] = 0     for i in 0..<nfft
    FFT_forward(re, im)                                   // size 4096, complex-in
    t = f - 2
    for b in 0..<bins:
        cac[reBase + b*le + t] = re[b] * (1/64)
        cac[imBase + b*le + t] = im[b] * (1/64)
```

### 2.2 CaC packing — the exact 4-D layout

The flat array is **C-order `[4][bins][le]`**, i.e. `index = c*bins*le + b*le + t`:

| `c` | content |
|---|---|
| 0 | ch0 real |
| 1 | ch0 imag |
| 2 | ch1 real |
| 3 | ch1 imag |

So real and imag are **not interleaved per bin** — they are separate planes, channel-major, real before
imag. This is exactly `[1,4,2048,112]` when reshaped, and matches torch's
`view_as_real(z).permute(0,1,4,2,3).reshape(B, C*2, Fr, T)` in demucs' `_magnitude`. Within `out_spec`
each stem's `[4,2048,112]` block is contiguous and uses the same layout
(`DemucsSeparator.kt:411-415`, `Dsp.kt:199-215`).

Axis order inside a plane: **bin-major, frame-minor** (`b*le + t`). Getting this transposed is the single
most likely porting bug.

### 2.3 Inverse — `Stft.inverse(cac, T, out0, out1)`

Per channel (`Dsp.kt:199-231`):
```
ola[0 ..< paddedSeg+nfft] = 0.0                      // Double accumulator
for f in 2 ..< 2+le:
    re[] = 0; im[] = 0                                // clears Nyquist + previous frame
    t = f - 2
    for b in 0..<bins:                                // 0..2047
        re[b] = cac[reBase + b*le + t] * 64
        im[b] = cac[imBase + b*le + t] * 64
    for b in 1..<half:                                // half = 2048; b = 1..2047
        re[nfft-b] =  re[b]
        im[nfft-b] = -im[b]
    FFT_inverse(re, im)                               // includes 1/nfft exactly ONCE
    start = f*hop
    for i in 0..<nfft: ola[start+i] += Double(re[i]) * win[i]

offset = nfft/2 + padL                                // 3584
for k in 0..<T: out[k] = Float( ola[offset+k] / (env[offset+k] + 1e-8) )
```

Contract points that bite:
1. **Bin 2048 (Nyquist) stays zero** — never reconstructed (`Dsp.kt:209`, `:216-219` skips it).
2. **DC bin keeps a non-zero imaginary part** in `im[0]`. A full complex inverse FFT is run and only
   `re[]` is used, which is numerically identical to discarding `Im(DC)` — numpy's `irfft` in the golden
   does the same (`scripts/stft_golden.py:90-92`). If you use vDSP's real-inverse FFT, drop `Im(DC)`
   deliberately.
3. **Boundary frames 0, 1, and `2+le .. nframes-1` contribute nothing** to `ola` but **DO contribute to
   `env`** — the window sum-of-squares runs over ALL `nframes = le+4` frames (`Dsp.kt:256-261`,
   `scripts/stft_golden.py:81-82`). Computing `env` only over the transformed frames is wrong.
4. **The `1/N` lives in the inverse FFT and nowhere else** (`Dsp.kt:33-34`, `:80-86`). Adding a second
   `1/N` is dsp-spec §8c gotcha 1.
5. Division is by `env + 1e-8` (`Dsp.kt:229`) — the epsilon is inside the parenthesis.
6. `env` depends only on window + frame count, so it is precomputed once per `T` and shared by both
   channels (`Dsp.kt:241`, `:256-261`).
7. `win`, `env`, and `ola` are **Double**; sample/spectrum data is **f32** (`Dsp.kt:119-121`).

### 2.4 FFT numerics — a deliberate non-negotiable

`Fft` (`Dsp.kt:29-108`) is an in-place iterative radix-2 DIT Cooley-Tukey. Twiddles
`W[k] = exp(-2πik/n)` and the bit-reversal permutation are **Double**, cached per size; every butterfly
**promotes f32 operands to Double** and rounds back (`Dsp.kt:66-73`). The inverse conjugates by flipping
the imaginary sign of the twiddle (`Dsp.kt:62`) and folds `1/n` at the end (`:80-86`).

`Dsp.kt:14-23` refuses the all-f32 variant with numbers: f32 tables land ~1e-4 relative error vs ~1e-7,
and **four** independent assertions sit exactly at 1e-4 (`DspTest.kt:73-74`, `:84`, `:93`, `:112-113` —
80 dB *is* 1e-4). Only sizes 64 and 4096 ever occur.

**Apple guidance:** vDSP/Accelerate's `vDSP_DFT` is single-precision and will land near the f32 error
floor this file explicitly rejected. Either (a) use `vDSP_DFT_zop_CreateSetupD` / the **double-precision**
Accelerate entry points, or (b) use single precision and re-derive the golden tolerances with a measured
diff — never by loosening the tolerance. Validate against `app/src/test/resources/dsp/stft_small.json`
(nfft 64, hop 16, T 4123 — chosen because `T % hop != 0` exercises the odd-tail branch that the production
config never hits; `scripts/stft_golden.py:10-13`).

---

## 3. Chunked overlap-add driver

Class: `DemucsSeparator` (`DemucsSeparator.kt:45-617`). Single-threaded; one worker drives
`feed`/`finish`. Deliberately Android-free so it is unit-testable — port it as a plain Swift struct/class
with no AVFoundation dependency and reuse `DemucsSeparatorTest.kt` as the acceptance suite.

### 3.1 Construction

| parameter | meaning | line |
|---|---|---|
| `keepOther: Bool` | `keepStems == "vocals_other"` | `AudioPipeline.kt:161`, `:386` |
| `mean, std: Float` | whole-track mono-mix stats; `std` clamped `max(std, 1e-8)` and used for both directions | `:91` |
| `estimatedFrames: Int64` | **progress denominator only**. Never bounds the grid, never caps output | `:49-57` |
| `infer(wav, spec) -> (specOut, timeOut)` | compacted to kept stems | `:58-63` |
| `onChunk(done, total)` | progress + thermal yield hook | `:64` |
| `resumeFrames: Int64 = 0` | frames an interrupted run already wrote | `:65-72` |
| `musicScore(mono, frames) -> Float?` | nil ⇒ separate every chunk | `:73-83` |
| `emit(interleaved, frames)` | interleaved stereo out | `:84` |

Derived: `nKeep = keptStems(keepOther).count` (`:89`);
`totalChunks = (estimatedFrames + MAX_SHIFT + STRIDE − 1) / STRIDE` (`:93`);
`skipChunks = max(0, (MAX_SHIFT + resumeFrames)/STRIDE − 1)` (`:158`).

### 3.2 State

| field | type/size | init | line |
|---|---|---|---|
| `inL`, `inR` | `Float[435708]` (ring, virtual-position addressed) | zeros | `:97-98` |
| `writePos` | `Int64` | `MAX_SHIFT` (= 22050) — positions `[0, 22050)` are the zero pre-pad | `:99` |
| `endPos` | `Int64` | `Int64.max`, resolved to `writePos` in `finish()` | `:107` |
| `outL`, `outR`, `wsum` | `Float[217854]` | zeros | `:110-112` |
| `flushPos`, `emitted` | `Int64` | 0 | `:113-114` |
| `nextChunkOff` | `Int64` | 0 | `:120` |
| `chunksDone`, `skippedChunks`, `nonFinite` | counters | 0 | `:117`, `:181`, `:225` |
| `weight` | `Float[114660]` | see below | `:169-172` |
| `gateMono` | `Float[114660]` | | `:175` |
| `gateScore` | `Float[8]`, `gateFrom = Int.max`, `gateTo = -1` | | `:176-178` |

**Overlap-add window** (`DemucsSeparator.kt:169-172`), replicating `model_apply.cpp:96-101` with
`TRANSITION_POWER = 1.0` (a no-op):
```
weight[i] = Float(min(i + 1, SEG - i)) / Float(SEG / 2)      // SEG/2 = 57330
```
Triangle rising `1/57330 … 1.0` and back to `1/57330`; max is exactly 1.0 at `i = 57329` and `i = 57330`.

### 3.3 `feed(interleaved, frames)` — `:185-203`

```
src = 0; remaining = frames
while remaining > 0:
    n = min(remaining, SEG/2)                     // 57330 — slice cap, :189
    for i in 0..<n:
        cell = (writePos + i) % IN_CAP
        inL[cell] = (interleaved[2*(src+i)]     - mean) / std      // :192
        inR[cell] = (interleaved[2*(src+i) + 1] - mean) / std      // :193
    writePos += n; src += n; remaining -= n
    while nextChunkOff + SEG + LOOKAHEAD <= writePos: processChunk()   // :201
```
The fire condition holds a **`LOOKAHEAD = 206388`-sample (4.68 s) lag** behind the stream so the ±2 gate
dilation can see forward. It is unconditional even with no gate installed (`:576-582`).

Slice cap + fire condition together guarantee at most one chunk fires per slice (`STRIDE > SEG/2`), which
is what bounds `IN_CAP` (`:132-137`).

### 3.4 `finish()` — `:284-288`

```
endPos = writePos
if framesFed <= 0 { return }                       // emit nothing rather than a chunk of silence
while nextChunkOff < endPos { processChunk() }
```
`framesFed = writePos − MAX_SHIFT` (`:211`).

### 3.5 `processChunk()` — `:297-312`

```
if chunksDone >= skipChunks:
    if separateChunk(chunksDone) { inferChunk(nextChunkOff) }
    else { skippedChunks += 1; passthroughChunk(nextChunkOff) }
nextChunkOff += STRIDE
chunksDone   += 1
flush(min(nextChunkOff, endPos))
if chunksDone >= skipChunks: onChunk(chunksDone, max(totalChunks, chunksDone))
```
Bookkeeping (`nextChunkOff`, `chunksDone`, `flush`) runs even for skipped-on-resume chunks; only the DSP
is skipped. `max(totalChunks, chunksDone)` guards a track that outruns the container estimate (`:309-310`).
Progress must **not** fire during the resume skip phase (`:305-308`; asserted `DemucsSeparatorTest.kt:449-456`).

### 3.6 `inferChunk(off)` — `:379-437` (the tail geometry)

```
clen      = min(SEG, endPos - off)          // < SEG only for tail chunks
delta     = SEG - clen
readStart = off - delta/2                   // torch TensorChunk.padded: real LEFT context, zeros out of range

for j in 0..<SEG:                                              // gather, :385-395
    p = readStart + j
    segL[j] = (p < 0 || p >= writePos) ? 0 : inL[p % IN_CAP]
    segR[j] = (p < 0 || p >= writePos) ? 0 : inR[p % IN_CAP]
wav[0..<SEG]      = segL                    // PLANAR, :396
wav[SEG..<2*SEG]  = segR                    // :397

spec = stft.forward(segL, segR, SEG)                            // :403
(specOut, timeOut) = infer(wav, spec)                           // :405

sumCac[0..<STEM_SPEC] = specOut[0..<STEM_SPEC]                  // :411
for k in 1..<nKeep:                                             // :412-415
    for i in 0..<STEM_SPEC: sumCac[i] += specOut[k*STEM_SPEC + i]
stft.inverse(sumCac, SEG, waveL, waveR)                         // ONE iSTFT, :416

read = delta/2                                                  // center_trim, :419
for j in 0..<clen:
    tl = Σ_k timeOut[(2*k)     * SEG + read + j]                // :424
    tr = Σ_k timeOut[(2*k + 1) * SEG + read + j]                // :425
    g    = weight[j]
    cell = (off + j) % OUT_CAP
    outL[cell] += g * (waveL[read+j] + tl)                      // :429
    outR[cell] += g * (waveR[read+j] + tr)                      // :430
    wsum[cell] += g                                             // :431
```
This is torch's `TensorChunk.padded` (real left context, zeros past the end, output read back
center-trimmed) — explicitly **not** the demucs.onnx C++ centering bug (`DemucsSeparator.kt:29-31`).

### 3.7 `passthroughChunk(off)` — `:451-466`

Identical OLA bookkeeping, model output replaced by the chunk's own normalized input:
```
clen = min(SEG, endPos - off)
for j in 0..<clen:
    p = off + j; g = weight[j]; cell = p % OUT_CAP
    if p < writePos:
        outL[cell] += g * inL[p % IN_CAP]
        outR[cell] += g * inR[p % IN_CAP]
    wsum[cell] += g                          // incremented even past the fed stream
```
Two reasons it is not a bypass copy (`:441-449`): `Σ g·x / Σ g == x` so a fully-skipped run reconstructs
the input exactly (`DemucsSeparatorTest.kt:244-253`), and at a skipped/separated boundary the triangle
becomes a natural crossfade instead of a hard seam.

### 3.8 `flush(limit)` — `:469-497` (soft clip, denormalize, emit)

```
n = 0
while flushPos < limit:
    cell = flushPos % OUT_CAP
    if flushPos >= MAX_SHIFT:
        if emitted >= resumeFrames:
            w = wsum[cell]                                   // > 0: every emitted pos covered by ≥1 chunk
            emitBuf[2*n]     = finite(softclip((outL[cell]/w) * std + mean))
            emitBuf[2*n + 1] = finite(softclip((outR[cell]/w) * std + mean))
            n += 1
        emitted += 1
    outL[cell] = 0; outR[cell] = 0; wsum[cell] = 0            // zero as we flush
    flushPos += 1
if n > 0 { emit(emitBuf, n) }
```
`limit` is always `min(nextChunkOff, endPos)`, so `n ≤ STRIDE` and `emitBuf` (`2*STRIDE`) never overflows.

**Order matters:** divide by `wsum` → multiply by `std` → add `mean` → soft clip → NaN guard.

### 3.9 Invariants (port these as assertions)

| # | invariant | evidence |
|---|---|---|
| I1 | `emitted == framesFed` after `finish()`; nothing caps the walk | `:276-283`; `DemucsSeparatorTest.kt:111-112` |
| I2 | Emitted sample count handed to `emit` = `framesFed − resumeFrames` | `:481-486`; `DemucsSeparatorTest.kt:381` |
| I3 | A resumed run is **bit-exact** (compared via raw bits) against the tail of an uninterrupted run | `DemucsSeparatorTest.kt:390-422`, `:426-440` |
| I4 | A resume re-runs **exactly one** chunk before the resume point | `DemucsSeparatorTest.kt:418-419` |
| I5 | `ceil(SEG/STRIDE) == 2` | `:142` |
| I6 | The pipeline is deterministic — identical config re-run is **bit-exact** (∞ dB) | `:548` |
| I7 | `onChunk` fires exactly `totalChunks` times with monotonic `done = 1..totalChunks` | `DemucsSeparatorTest.kt:478-490` |
| I8 | Identity-model round trip (`vocals` mask = input, zero time branch) ≥ 60 dB SNR | `DemucsSeparatorTest.kt:138-159` |

### 3.10 Soft-clip guard — exact formula

`internal fun softclip(x: Float): Float` — file level, **not** a separator member, because it is the
encoder's precondition and `transcodeToAac` needs the identical function (`DemucsSeparator.kt:750-762`):

```kotlin
val a = abs(x)
if (a <= 0.95f) return x
return sign(x) * (0.95f + tanh((a - 0.95) / 0.05).toFloat() * 0.05f)
```

| item | value |
|---|---|
| threshold (transparent below) | `0.95f` |
| knee width | `0.05` |
| bound | `|y| < 1.0` strictly |
| arithmetic | `(a − 0.95)` and `/0.05` and `tanh` are **Double** (Kotlin promotes on the untyped literals), the result is narrowed to Float, then `0.95f + …*0.05f` in Float |

Reproduce the Double intermediate in Swift (`Float(tanh((Double(a) - 0.95) / 0.05)) * 0.05`) or I6
bit-exactness against the Android reference is lost.

---

## 4. Stem semantics

| index | stem | kept for `vocals` | kept for `vocals_other` |
|---:|---|:---:|:---:|
| 0 | drums | no | no |
| 1 | bass | no | no |
| 2 | other | no | **yes** |
| 3 | vocals | **yes** | **yes** |

`DemucsSeparator.kt:570-571` fixes the order; `:509-510`:
```kotlin
fun keptStems(keepOther: Boolean): IntArray =
    if (keepOther) intArrayOf(OTHER, VOCALS) else intArrayOf(VOCALS)
```
**Ascending is load-bearing** (`:503-508`): the session's buffer reads only ever seek forward, and
compacted index `k` means the same stem in both the spec and time arrays.

### 4.1 Compaction contract (A4)

The runtime session copies **only** the kept stems out and packs them contiguously
(`DemucsSeparator.kt:689-696`):
```
out_spec (rank 5): for k in keep.indices: seek keep[k]*STEM_SPEC,      read STEM_SPEC     into specOut[k*STEM_SPEC]
out_wave (rank 4): for k in keep.indices: seek keep[k]*2*SEG,          read 2*SEG         into timeOut[k*2*SEG]
```
Downstream, index `k` is the **compacted** index, never the absolute stem id. Drums+bass are 14.7 MB spec
+ 3.7 MB time per chunk that would otherwise be pure memcpy for the bin (`:628-631`).

### 4.2 Combining the two branches

Per chunk, per kept stem set (`DemucsSeparator.kt:408-431`):
1. **Spec branch:** sum the kept stems' masked CaC spectrograms **first** (linearity), then run **one**
   iSTFT for the whole chunk. Never one iSTFT per stem.
2. **Time branch:** sum the kept stems' waveforms per sample.
3. Output sample = `waveL[read+j] + tl` (spec-branch iSTFT + time-branch sum), then weighted into the OLA
   accumulator.

This is `stems = istft(spec_out) + wave_out` from the export contract (`docs/m0-spikes.md:37`,
`ml/Models.kt:95-96`).

Product semantics (`docs/prd-video-filter-android.md:80`): `vocals` keeps dialogue + any singing and drops
everything else (SFX lost); `vocals_other` keeps SFX/ambience at the cost of melodic-music leakage. Known
and accepted. The music gate (§7) partially mitigates the SFX loss by passing dialogue-only stretches
through untouched (`DemucsSeparator.kt:36-40`).

---

## 5. Decode / downmix / resample

`AudioDecoder` (`AudioDecoder.kt`). One private `decode()` drives both passes (`:127-341`).

### 5.1 Targets

| item | value | line |
|---|---|---|
| output sample rate | 44 100 Hz | `:30` |
| output channels | 2, interleaved | fixed |
| output sample format | f32 | `:172` (`ENCODING_PCM_FLOAT`) |
| decoder output format | PCM **int16 LE**, converted `/32768f` | `:198`, `:205`, `:209` |
| authoritative rate/channels | read from the decoder's output format change, **not** the track format (HE-AAC SBR/PS and Opus rewrite them) | `:292-296` |
| provisional rate/channels before that | track format, else 44100 / 2 | `:152-153` |

**Apple note:** `/32768f` on ingest vs `*32767f` on egress (`AacWriter.kt:93`) is deliberately asymmetric
in the shipped code. Keep both as-is for parity.

### 5.2 Channel fold — `:203-229`

| source channels | rule | line |
|---|---|---|
| 1 | duplicate: `L = R = s/32768` | `:204-208` |
| 2 | straight `/32768` | `:209` |
| > 2 | ITU-R BS.775 fold, below | `:216-228` |

`HALF_POWER = 0.70710678f` — "−3 dB, the ITU-R BS.775 coefficient for folding center/surrounds into a
stereo pair" (`:32-33`).

Decoder PCM is assumed **WAV order**: `L R C LFE Ls Rs` (`:213`).

| WAV index | channel | condition to be used | coefficient into L | coefficient into R |
|---:|---|---|---:|---:|
| 0 | L | always | 1.0 | — |
| 1 | R | always | — | 1.0 |
| 2 | C | `channels > 2` | 0.70710678 | 0.70710678 |
| 3 | LFE | never | **dropped on purpose** | **dropped on purpose** |
| 4 | Ls | `channels > 4` | 0.70710678 | — |
| 5 | Rs | `channels > 5` | — | 0.70710678 |

Exact expression (int16 domain, divided once at the end — `:225-226`):
```
L_out[f] = (s[b+0] + 0.70710678*s[b+2] + 0.70710678*s[b+4]) / 32768
R_out[f] = (s[b+1] + 0.70710678*s[b+2] + 0.70710678*s[b+5]) / 32768        b = f * channels
```
**Deliberately un-normalized** (`:214-215`): level is irrelevant to the separator, which divides and
re-multiplies by the same `std`. Consequence — a 5.1 source arrives at `|x|` up to ~2.414 full scale
(measured +10.7 dBFS on a synthetic 5.1 asset, `AudioPipeline.kt:250-256`). This is why
`transcodeToAac` soft-clips in its sink (`AudioPipeline.kt:281`) and why `AacWriter` clamps.

Why the fold exists at all: taking ch0/ch1 verbatim drops the center channel, where a 5.1 film mixes
nearly all dialogue — the vocals stem came back **empty** on exactly the content the feature exists for
(`AudioDecoder.kt:210-213`, `docs/tasks.md:46`).

Known simplification (**carry or fix explicitly**): a 4-channel source takes the `else` branch with
`c = 2`, `ls = rs = −1`, i.e. it treats channel 2 as center. Quad `L R Ls Rs` would be mis-folded. No
4-channel asset exists in `qa-assets/`.

### 5.3 Resampling

| item | Android | line |
|---|---|---|
| engine | media3 `SonicAudioProcessor`, one session per stream | `:171-175` |
| format | `AudioFormat(pcmRate, 2, ENCODING_PCM_FLOAT)`, `setOutputSampleRateHz(44100)` **before** `configure` | `:171-172` |
| bypass | `useSonic = (windows == null && pcmRate != 44100)` — a 44.1 kHz source and every sampled-stats pass skip it entirely | `:168` |
| tail | `queueEndOfStream()` then drain until `isEnded()` | `:314-317` |
| quality | 2-tap linear interpolation, **no anti-imaging filter** — ~−27 dB in-band distortion at 8 kHz on a 48 kHz source | `docs/tasks.md:57` |

**This is a known defect, not a spec.** `docs/tasks.md:57` names the fix as a polyphase FIR (L=160 /
M=147). On Apple, use `AVAudioConverter` or `AudioConverter` with
`kAudioConverterSampleRateConverterQuality = kAudioConverterQuality_High` (or a vDSP polyphase FIR).
**Consequence:** for any source that is not already 44.1 kHz, the Swift output will **not** be
bit-identical to Android — it will be *better*. Do not gate the port on byte-equality for those sources;
gate on 44.1 kHz sources only (`movie-test.mp4` is AAC 44.1 kHz stereo — `docs/long-film-followups.md:70`).

### 5.4 Stats pass (mean / std) — `:71-88`, `:352-359`

| item | value | line |
|---|---|---|
| windows | 20 | `:44` |
| window length | 2 000 000 µs (2 s) → 40 s total | `:45` |
| window starts | `k * (durationUs − WINDOW_US) / 19` for `k` in `0..19` — first at 0, last flush against the end | `:357-358` |
| fall back to full decode when | `STATS_WINDOWS <= 1`, or no container duration, or `durationUs <= 2*20*2e6 = 80 s` | `:353-356` |
| mono mix | `m = (L + R) * 0.5` in **Double** | `:77` |
| mean | `sum / count` | `:84` |
| variance | `(sumsq − sum²/count) / (count − 1)` — **Bessel N−1**, clamped `>= 0`; 0 when `count <= 1` | `:86` |
| std | `sqrt(variance)`, cast to Float; separator clamps `max(std, 1e-8)` | `:87`, `DemucsSeparator.kt:91` |
| resampling during stats | **none** — mean/std are rate-agnostic and a Sonic session cannot survive the per-window `flush()` | `:66-69`, `:166-168` |
| channel fold during stats | **the same fold as `stream`, mandatory** — a differently folded 5.1 would feed the fp16 graph the wrong level | `:67-69` |
| one codec across all 20 seeks | `seekTo(w, SEEK_TO_CLOSEST_SYNC)` + `flush()`, never a new decoder | `:321-324` |
| window end | `max(w, actualSeekLandingTime) + WINDOW_US` — a sparse-sync track can land past the target | `:327-329` |
| empty result | throws "Could not decode any audio from this video." | `:83` |

`estimateFrames = durationUs * 44100 / 1_000_000`, 0 if the container will not say (`:96-108`).
Used **only** as a progress denominator.

### 5.5 First-PTS anchor

`firstPtsUs` = the extractor's `sampleTime` on the **first sample actually queued** (`:282`), returned by
`decode`. Clamped `max(0)` before `AacWriter` (`AudioPipeline.kt:174`, `:318`).

**Do not treat a negative PTS as EOS.** Measured **−21333 µs** on `qa-assets/test-video.mp4`, i.e. most
AAC-in-MP4 carries an encoder-priming edit. Testing `pts < 0` read that first sample as EOS and skipped
the entire track (`AudioDecoder.kt:264-271`). The only unambiguous exhaustion signal is
`readSampleData` returning `-1` (`:277-280`).

---

## 6. AAC encode + mux

### 6.1 Encoder config — `AacWriter.kt:49-54`

| key | value |
|---|---|
| MIME | `audio/mp4a-latm` (AAC) |
| profile | AAC-LC (`AACObjectLC`) |
| sample rate | **44 100** |
| channels | 2 |
| bitrate | **192 000** bps |
| input PCM | int16 |
| max input size | 16 384 bytes |
| container | MPEG-4 `.m4a` |

Quantizer (`:86-96`): `s = clamp(round(v * 32767), -32768, 32767)`, written little-endian; **`v` that is
not finite becomes 0** (`:93`). `roundToInt` throws on NaN rather than saturating, and one corrupt sample
out of the separator lost a multi-minute job (`:88-92`).

### 6.2 Timestamps and A/V sync

| mechanism | detail | line |
|---|---|---|
| PTS clock | `ptsUs = firstPtsUs + samplesOut * 1_000_000 / 44100` — a monotonic **sample counter**, never a wall clock | `AacWriter.kt:117` |
| epoch | source's first audio-track PTS, clamped `≥ 0`, so the transcoded track lands where the source's did and keeps any inter-track offset | `AudioPipeline.kt:174`, `:310-322` |
| `samplesOut` advance | `+= n/4` per queued input buffer (stereo int16 = 4 bytes/frame); the buffer size is truncated to a whole frame with `n − n % 4` | `AacWriter.kt:137-141` |
| video side | samples **copied verbatim, zero re-encode**, PTS passed through with no rebase | `Remux.kt:96-98`, `:317-327` |
| rotation | `setOrientationHint(deg)` **before** `start()`; track `KEY_ROTATION` else MediaMetadataRetriever; normalized `((deg % 360) + 360) % 360` | `Remux.kt:136`, `:352-368` |
| track add | **only** from the encoder's output-format-changed event (that format carries the AudioSpecificConfig); the standalone CODEC_CONFIG buffer is dropped by forcing `info.size = 0` | `AacWriter.kt:159-170` |

**Priming / the 42.67 ms constant.** The encoder's ~2048-sample priming is **left uncompensated by
convention** (`AacWriter.kt:24-25`). Measured by cross-correlation at the start, middle and end of a
5-min clip: **A/V lag a constant 2048 samples = 42.67 ms**, no progressive drift, entirely encoder
priming; PRD budget is < 50 ms (`docs/tasks.md:43`, `:59`, `:73`).

**Risk flag — the 42.67 ms number is stale.** 2048 / 48000 = 42.67 ms. That measurement was taken at
commit `6bd549e` (M2+M3), when `AacWriter` still resampled 44.1 → 48 kHz. A6 deleted that leg at
`b0d8792` (`docs/video-performance-plan-v2.md:455`, `:793`; `AacWriter.kt:14-19`) and the encoder now runs
at 44 100 Hz. **The same 2048-sample priming at 44 100 Hz is 46.44 ms**, leaving ~3.6 ms of margin
against the 50 ms budget instead of ~7 ms. This has not been re-measured on device.

**Apple guidance.** `AVAssetWriter` writing AAC into `.m4a` normally emits an `elst` edit list / uses
`kAudioConverterPrimeInfo`, i.e. Apple compensates priming for you. That is the *correct* behaviour and
will make the Swift output land ~43–46 ms **earlier** than the Android output. Treat that as a fix, not a
regression — but assert it explicitly in QA, because it means an A/B cross-correlation against the Android
`.m4a` will show a constant offset by design.

### 6.3 Drain discipline (both counted, both measured)

| call site | timeout | why | line |
|---|---|---|---|
| `drainEncoder(endOfStream = false)` | **0** (non-blocking) | called after every input buffer; a blocking 10 ms wait here cost **135 s to transcode a 193 s track on an S23 (1.43× realtime), ~129 s of it sleep** | `AacWriter.kt:147-157` |
| `drainEncoder(endOfStream = true)` | 10 000 µs | the tail is exactly what we are waiting for | `:157` |
| `feedEncoder` input dequeue | ask with 0 first; on failure **drain, then** wait 10 000 µs | ~26 waits per htdemucs chunk × 276 chunks ≈ 7 000 per 643 s job, on the separator's own thread. The blocking wait must stay — nothing else applies backpressure on this side | `:122-134` |

### 6.4 Mux / remux (`Remux.kt`)

| item | value | line |
|---|---|---|
| muxable audio MIMEs | `audio/mp4a-latm`, `audio/3gpp`, `audio/amr-wb` | `:52` |
| audio plan for a segmented censor-only job | `COPY` / `TRANSCODE` / `NONE`; an **unreadable** source answers `TRANSCODE`, never `NONE` | `:79-90` |
| interleave rule | write whichever track has the smaller `sampleTime`; the drained track's time becomes `Int64.max` | `:143-156` |
| concat offsets | the **intended** segment starts (`startMs*1000`), never accumulated measured durations — accumulating folds sub-frame rounding into every later segment (~1 s of drift over ~31 joins vs a 50 ms budget) | `:172-182`, `:225-244` |
| PTS ordering | sample PTS arrive in **decode** order and are **not** monotonic with B-frames; only a uniform shift is legal | `:313-316` |
| segment format check | `KEY_MIME`, `KEY_WIDTH`, `KEY_HEIGHT`, `csd-0`, `csd-1` must match across parts | `:282-291` |
| read buffer | `KEY_MAX_INPUT_SIZE` clamped `≥ 65536`, else 1 MiB; grown ×2 on the too-small exception (the failed read does not advance the extractor) | `:329-349` |
| failure | any failure deletes a half-written `.m4a`/`.mp4` (no `moov` until `stop()`); the file-descriptor variant deliberately cannot | `:44-49`, `:165` |

---

## 7. MusicGate (A1)

`MusicGate.kt`. Answers "is there music in this 2.6 s?" so the separator can pass a music-free chunk
through instead of spending ~2.1 s separating it. Stateless; the dilation lives in the separator.

### 7.1 Is it live in the shipped app?

**Yes, live and on the critical path — with a fail-open.** `MusicGate.open(context)` is called by both
audio entry points (`AudioPipeline.kt:165`, `:391`) and its `score` is wired as `musicScore`
(`:198`, `:423`). It returns **null** if `yamnet.onnx` is not installed or ORT refuses it, and null means
**every chunk is separated** — the pre-A1 behaviour (`MusicGate.kt:171-190`).

`yamnet.onnx` is a **gitignored asset** with `downloadUrl = null` (`ml/Models.kt:125-130`) — a fresh clone
has no model until `scripts/fetch-models.sh` runs, so the gate silently degrades to "off" there. Its
measured effect on the shipped build is large: **158/276 chunks skipped (57 %)**, ORT time
323 151 → 270 177 ms (−16.4 %), `separate` stage 447 875 → 385 420 ms (−13.9 %)
(`docs/perf-plan-v4.md:426-434`). Gate cost is ~1 % of the separator (`MusicGate.kt:22-25`); measured
`gate = 7 453 ms` against `ort = 270 177 ms` on the same run (`docs/perf-plan-v4.md:413`).

### 7.2 Model

| item | value | line |
|---|---|---|
| artifact | `yamnet.onnx`, sha256 `afe82472f2f6250570b63d4f106e7a74b5232cfd17086d39076d80a4273d01f8`, opset 15 | `ml/Models.kt:125-130` |
| input | name from `session.inputNames.first()` (`waveform`), shape **`[15600]` — RANK 1, not `[1,15600]`** | `MusicGate.kt:46`, `:118`; `ml/Models.kt:116` |
| output | `output_0` `[1, 521]` f32, AudioSet class-map order | `ml/Models.kt:117-118` |
| session options | `imageSessionOptions()`: intra-op 1, `session.intra_op.allow_spinning = "0"`, XNNPACK EP with `intra_op_num_threads = 4` | `MusicGate.kt:187`; `ml/Models.kt:294`, `:302-306` |

### 7.3 Scoring — `score(mono44k, frames) -> Float`, `:65-84`

| step | detail | line |
|---|---|---|
| input | **denormalized** mono 44.1 kHz (real amplitudes) — a classifier and a silence floor need the signal the user would hear | `DemucsSeparator.kt:78-83` |
| resample | 44 100 → 16 000 by **naive linear interpolation**, ratio `RATIO = 44100/16000 = 2.75625`; output length `((frames-1)/RATIO).toInt() + 1` | `:96-108`, `:139`, `:193` |
| resample validation | vs ffmpeg soxr over 74 frames of real qa-asset audio: max score delta **0.13**, one frame flips at a 0.5 threshold; **nothing flips at 0.15**. YAMNet's mel filterbank stops at 7500 Hz | `:86-94` |
| short window | `frames <= 1` ⇒ score 0 | `:97` |
| silence floor | peak `< SILENCE_PEAK = 0.001f` (−60 dBFS) ⇒ score 0, no model run | `:73-74`, `:167` |
| frame length | `FRAME = 15_600` (0.975 s @ 16 kHz) | `:137` |
| frame tiling | `start = 0`, then `start = min(start + FRAME, n - FRAME)` — **last frame flush against the end** so every sample is covered | `:82` |
| aggregation | **max** over frames (biased toward "music"), with an early exit once `best >= THRESHOLD` | `:76-83` |
| zero pad | a frame short of `FRAME` samples is zero-padded | `:112-115` |
| per-frame score | `max` over two contiguous class ranges | `:125` |

For a full `SEG` window: `n = 41 600` 16 kHz samples, frames start at **0, 15600, 26000** — exactly the
"three inferences per chunk" the KDoc cites (`:24-25`). `mono16k` is sized `out16kLength(SEG) + 1 = 41601`
(`:37`).

Music classes — **inclusive at both ends**, verified against Google's own class map (`:141-150`):

| range | meaning |
|---|---|
| `132...276` | the AudioSet music block, `Music` … `Scary music` (131 = Whale vocalization, 277 = Wind) |
| `24...32` | vocal music: Singing, Choir, Yodeling, Chant, Mantra, Child singing, Synthetic singing, Rapping, Humming — **not optional**, it is the only thing that catches a-cappella |

### 7.4 Thresholds

| constant | value | measured basis | line |
|---|---:|---|---|
| `THRESHOLD` | **0.15f** | silence 0.0000, white noise 0.0240, synthesized chord 0.9880; real content bimodal (median 0.006 speech-heavy vlog, 0.50–0.92 scored clip). ~6× above the loudest non-music and ~6× below the quietest music measured | `MusicGate.kt:152-164` |
| `SILENCE_PEAK` | 0.001f (−60 dBFS) | room tone on a film soundtrack sits ~−50 dBFS | `:166-167` |
| `DILATE` | 2 chunks (±4.68 s) | | `DemucsSeparator.kt:583` |
| `DILATE2_MIN_SCORE` | **0.02f** | C1. The chunks this tier newly drops all scored **≤ 0.0139**, below white noise (0.0240) on this exact artifact | `DemucsSeparator.kt:586-609` |
| `GATE_RING` | 8 (needs ≥ `2*DILATE+1` = 5) | | `:612` |

### 7.5 Two-tier dilation — `separateChunk(c)`, `DemucsSeparator.kt:337-358`

```
gate = musicScore ?? return true                       // no gate ⇒ separate everything

// forward-only scoring, one new chunk per processed chunk
var i = max(gateTo + 1, c)
while i <= c + DILATE:
    if gateFrom == Int.max { gateFrom = i }
    gateScore[i % GATE_RING] = scoreChunk(i, gate)
    gateTo = i; i += 1

for k in (c - DILATE)...(c + DILATE):
    if k < 0        { continue }                       // before the film starts
    if k < gateFrom { return true }                    // never scored (fresh run start / resume) ⇒ SEPARATE
    if gateScore[k % GATE_RING] < 0.15 { continue }
    if (c-1...c+1).contains(k) || gateScore[c % GATE_RING] >= 0.02 { return true }
return false
```
- **±1 is hard, unconditional.** A music chunk one away forces separation whatever `c` scored — fades and
  mid-sting boundaries land here.
- **±2 only rescues a chunk with something in it** (own score `>= 0.02`). Two chunks away is 4.7 s.
- **Everything fails toward separating.** A never-scored chunk counts as music.
- Scoring only moves **forward** — the ring holds `2*SEG + LOOKAHEAD` which at the moment chunk `c` fires
  covers `c+DILATE`'s window and **not** `c−DILATE`'s. The backward half is served from *remembered
  decisions*. After a resume the first processed chunk has no history, costing `DILATE = 2` chunks of
  unnecessary separation once per resume (`:328-336`; asserted `DemucsSeparatorTest.kt:469-476`).

`scoreChunk(c, gate)` (`:365-377`) hands over **only the real samples**:
`n = clamp(min(SEG, writePos − c*STRIDE), 0, …)`; `n == 0` ⇒ score 0. Denormalize-and-fold in one step:
`gateMono[j] = 0.5*(inL[cell] + inR[cell]) * std + mean` (`:374`).

`DILATE2_MIN_SCORE` is the **miss-rate dial of the product**. Set it to `0f` to get the one-tier dilation
back — that is the whole revert (`:604-607`). Moving it up requires a listening test on the worst chunk it
stops separating, never a chunk count (`docs/perf-plan-v4.md:437-438`).

---

## 8. Every NaN / fp16 / numerical-stability workaround

Losing any one of these has cost this codebase a job, a stage, or a stem. None are theoretical.

| # | guard | exact code / value | why | line |
|---:|---|---|---|---|
| 1 | **Non-finite model output → silence, counted** | `if x.isFinite() return x; nonFinite += 1; return 0f` | The shipped graph is fp16. Input is normalized by *whole-track* std, so a passage far louder than the track average pushes activations toward fp16's ~65504 ceiling and a chunk returns non-finite. **Observed 2026-07-29 on a 10.5-minute source: died at the very end after 6.5 min of separation with `IllegalArgumentException: Cannot round NaN value`.** | `DemucsSeparator.kt:213-225`, `:269-274` |
| 2 | **Encoder-boundary NaN clamp** | `s = v.isFinite() ? clamp(round(v*32767), -32768, 32767) : 0` | Second line of defence at the float→int16 boundary; every other producer of this buffer hits the same edge | `AacWriter.kt:88-93` |
| 3 | **`std` floor** | `std.coerceAtLeast(1e-8f)`, same scalar for normalize and denormalize | Digital-silence track ⇒ std 0 ⇒ divide-by-zero on every sample | `DemucsSeparator.kt:91` |
| 4 | **iSTFT envelope epsilon** | `ola[i] / (env[i] + 1e-8)` | Window sum-of-squares is ~0 in the pad regions | `Dsp.kt:229` |
| 5 | **Resume-span divide skipped, not clamped** | `if emitted >= resumeFrames { … }` around the whole divide | Over a skipped span nothing was accumulated, so `wsum == 0` and the divide would be `0f/0f = NaN`. Also avoids millions of pointless divide+softclip pairs | `DemucsSeparator.kt:476-486` |
| 6 | **Soft clip before the quantizer** | tanh knee, §3.10 | `AacWriter.write`'s contract is "already soft-clipped upstream". `transcodeToAac` has no separator, so it applies the identical function in its sink — a 5.1 source arrives at \|x\| ≈ 2.4 and the int16 quantizer would hard-clip every peak into a square wave | `DemucsSeparator.kt:750-762`, `AudioPipeline.kt:274-282` |
| 7 | **fp16 sample-variance in Double** | `sumsq`, `sum` as Double; Bessel `N−1` | Matches the python normalization the model was trained on | `AudioDecoder.kt:77`, `:86` |
| 8 | **FFT twiddles + butterflies in Double** | see §2.4 | f32 tables land ~1e-4 relative error; four independent assertions sit exactly at 1e-4 | `Dsp.kt:14-23`, `:66-73` |
| 9 | **iSTFT window / envelope / OLA accumulator in Double** | `win`, `env`, `ola` are `DoubleArray` | Only the sample and spectrum data is f32 | `Dsp.kt:119-121`, `:248-253` |
| 10 | **XNNPACK is BANNED on the htdemucs session** | CPU EP, multi-threaded | XNNPACK's **fp16 kernels corrupt this f16 graph's spectral branch on-device** — broadband-noise stems; the time branch survives. Same family of fp16 defects that disqualified XNNPACK for the NSFW model in M0 (`xnn_create_convolution2d_nhwc_fp16` error 2) | `DemucsSeparator.kt:711-720`, `docs/m0-spikes.md:33` |
| 11 | **CPU arena OFF + memory-pattern planning OFF** | `setCPUArenaAllocator(false)`, `setMemoryPatternOptimization(false)` | With the arena, each run's high-water stays resident across every chunk and Samsung's global memory watchdog kills the app. **lmkd killed the app at 5.6 GB RSS without these two.** Not dials | `DemucsSeparator.kt:721-729` |
| 12 | **Direct (non-heap) input buffers, allocated once** | `ByteBuffer.allocateDirect` ×2, reused every chunk | Passing heap arrays makes ORT `allocateDirect` ~14 MB **per call**; that non-movable churn OOMs the 256 MB ART heap mid-job | `DemucsSeparator.kt:622-625`, `:638-641` |
| 13 | **`setOptimizedModelFilePath` deliberately REMOVED** | — | Measured: sessionCreate 881 ms = 0.23 % of a 385 420 ms stage; serialized graph **157.6 MB** vs the 87.9 MB `.onnx` (ORT demotes fp16 initializers to fp32 at load and serializes the demoted form); peak RSS 1 267 532 → 1 401 700 KB. Do not re-add without a measurement that beats these | `DemucsSeparator.kt:646-657` |
| 14 | **INT8 quantization is rejected** | — | Measured: dynamic INT8 Conv+MatMul **0.55×** speed / 2.8 dB spec SNR; Conv-only **0.44×** / 2.2 dB. `ConvInteger` is the poison (Conv/ConvTranspose = 47.6 % of the 91.96 GFLOP/segment). MatMul-only is 1.19× at 43.5 dB wave — **parked, not dead** | `docs/perf-plan-v4.md:188-212` |
| 15 | **Torch reflect padding, not the C++ off-by-one** | mirror EXCLUDING the edge sample | dsp-spec §3 records the demucs.onnx C++ centering bug; this port does not reproduce it | `Dsp.kt:112-115`, `:233-239`, `DemucsSeparator.kt:29-31` |
| 16 | **Nyquist bin dropped, boundary frames zero** | bins `0..2047`; frames `[2:2+le]` | Model contract. A full-band white-noise identity round trip is therefore capped near `10·log10(nfft) ≈ 36 dB` **by design**; test probes must be band-limited | `DemucsSeparatorTest.kt:116-121`, `DspTest.kt:108-113` |
| 17 | **Per-segment normalization is INSIDE the graph — do not repeat it** | only whole-track mean/std applies outside | `apply.py`'s "ref" norm is the outer one; the per-segment one is baked in | `DemucsSeparator.kt:24-26` |
| 18 | **Negative first PTS is not EOS** | test `readSampleData() < 0`, never `pts < 0` | −21333 µs measured on `qa-assets/test-video.mp4`; the wrong test skipped the entire track on most AAC-in-MP4 | `AudioDecoder.kt:264-280` |
| 19 | **Negative PTS clamped to 0 before the writer** | `firstPtsUs.coerceAtLeast(0L)` | `MediaMuxer` rejects negative sample times, and the re-encode introduces its own priming anyway | `AudioPipeline.kt:172-174`, `:315-318` |

---

## 9. Threading, thermal, and progress (behavioural, port as-is)

| item | Android | line |
|---|---|---|
| htdemucs intra-op threads | `min(availableProcessors, 6)`. Swept on an S23, median ms/chunk over chunks 3–7: **8 → 2244, 6 → 2136, 4 → 2155** — 6 wins ~5 % because every intra-op barrier waits on the little cores | `DemucsSeparator.kt:732-745` |
| spinning | `session.intra_op.allow_spinning = "0"` (0 → 2244 ms, 1 → 2305 ms) | `:727`, `:746` |
| concurrency | 1 session, serial chunks. Measured: 1×8 threads 699.1 ms/chunk, 1×4 405.3, 4 sessions×1 398.6, 2×4 533.9 — chunk-level parallelism buys **1.5 %** and doubles RSS | `docs/perf-plan-v4.md:250-260` |
| thermal yield | between chunks only (one ONNX run is uninterruptible). `NONE`/`LIGHT` → 0 ms; `MODERATE` → **500 ms**; `SEVERE`+ → **2000 ms**; at `SEVERE`+ with a sibling video branch live, block in 500 ms sleeps until the sibling finishes | `AudioPipeline.kt:53-67` |
| measured yield cost | 123 ms on a 643 s job — not a factor at that length off charger | `docs/perf-plan-v4.md:414`, `:423` |
| cancellation | polled once per decode dequeue and in the stream sink; throws | `AudioDecoder.kt:260`, `AudioPipeline.kt:207` |
| progress, `removeMusic` | 2 after stats; `2 + 96*done/total` per chunk (→ 2..98); 100 at end. **Post only when the integer percent moves** — ~4800 chunks map onto 96 values and the un-guarded version measured −12.2 % | `AudioPipeline.kt:159`, `:191-192`, `:223` |
| progress, resumable | seed `clamp(2 + 88*written/max(estFrames,1), 2, 90)`; per chunk `2 + 88*done/t`; `encodePcm` `min(90 + 10*done/total, 99)`; 100 at end | `AudioPipeline.kt:381`, `:410`, `:500` |

### 9.1 Resumable path specifics — `AudioPipeline.kt:352-472`

| item | value | line |
|---|---|---|
| scratch format | **int16 LE stereo 44 100 Hz**, append-only `audio.pcm`; 1 scratch frame == 1 separator frame, so `framesEmitted * 4` is an exact byte offset | `:330-341`, `:424-433` |
| cost | 635 MB per hour of source; measured **87.3 dB** round-trip SNR (~50 dB below what AAC-LC 192 kbps discards) | `:342-343` |
| write batch | one flush batch, one `write` — `ByteArray(4 * STRIDE)` = 412 776 bytes, unbuffered | `:397`, `:431` |
| truncate-on-resume | `written = min(checkpoint.framesEmitted, pcm.length/4)`; if `pcm.length != written*4`, `setLength(written*4)` | `:369-372` |
| stats on resume | **never re-derived** — re-sampling different windows would step the level mid-film | `:344-346` |
| completion marker | `stats.frames` — 0 means "the separator still owes work"; the true total is written only after a complete run | `:347-350`, `:456-457` |
| final invariant | `check(pcm.length() == total * 4)` | `:461` |
| read-back | int16 → f32 as `s / 32767f` (unity-gain inverse of the quantizer) | `:496` |

---

## 10. Android-platform-bound surfaces → Apple equivalents

| Android | purpose | Apple equivalent | notes |
|---|---|---|---|
| `MediaExtractor` + `MediaCodec` decoder | demux + decode any audio codec to PCM16 | `AVAssetReader` + `AVAssetReaderTrackOutput` with `AVLinearPCMBitDepthKey`/`IsFloat`, or `AVAudioFile`/`ExtAudioFile` | Apple can hand you **f32 non-interleaved at 44.1 kHz directly** via `AVAssetReaderAudioMixOutput`, collapsing decode+fold+resample. Do **not** — the BS.775 fold and the un-normalized level are load-bearing (§5.2, §8/6). Read PCM at the **source** layout and fold yourself. |
| `INFO_OUTPUT_FORMAT_CHANGED` authoritative rate/channels | HE-AAC SBR/PS, Opus rewrite the format | `CMFormatDescription` from the reader output's `sampleBuffer` | Same trap: the track's declared rate can be half the decoded rate. |
| media3 `SonicAudioProcessor` | 44.1 kHz resample | `AVAudioConverter` / `AudioConverterRef` with `kAudioConverterQuality_High`, or a vDSP polyphase FIR (L=160/M=147) | **Quality upgrade, parity break** for non-44.1 kHz sources. See §5.3. |
| ONNX Runtime Android 1.27.0, CPU EP | htdemucs + YAMNet | `onnxruntime-objc` / `onnxruntime-c` CocoaPod, CPU EP | **Do not enable CoreML EP for htdemucs without re-validating the spectral branch** — the exact failure mode XNNPACK produced (§8/10) is a vendor fp16-kernel defect and CoreML is another fp16 path. Reproduce guard #11 with `arena_extend_strategy`/allocator settings. |
| `Runtime.availableProcessors().coerceAtMost(6)` | intra-op threads | `ProcessInfo.processInfo.activeProcessorCount`, capped — re-sweep on Apple silicon (P/E-core split is a *different* straggler problem than the S23's) | The 6-vs-8 result does not transfer; the *mechanism* does. |
| `MediaCodec` AAC encoder + `MediaMuxer` | AAC-LC 192 kbps `.m4a` | `AVAssetWriter` + `AVAssetWriterInput(mediaType: .audio, outputSettings: [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 44100, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 192000])` | Apple writes the CSD and the edit list itself; there is no CODEC_CONFIG buffer to drop. See the priming note in §6.2. |
| `MediaMuxer` sample-copy remux | zero-re-encode video passthrough | `AVAssetReaderTrackOutput(outputSettings: nil)` + `AVAssetWriterInput(outputSettings: nil)` | Bit-identity of the video track is a **PRD acceptance criterion** and was proven on Android (elementary-stream MD5 + packet PTS/size sequence). Prove it again. |
| `setOrientationHint` before `start()` | rotation | `AVAssetWriterInput.transform` | Must be set before the first append. |
| `PowerManager.currentThermalStatus` | thermal yield tiers | `ProcessInfo.processInfo.thermalState` → `.nominal`/`.fair` → 0 ms, `.serious` → 500 ms, `.critical` → 2000 ms | Map `MODERATE`→`.serious`, `SEVERE+`→`.critical`. |
| `WorkManager` + foreground service | long job survival | `BGProcessingTaskRequest` + `beginBackgroundTask`; there is no 6 h FGS analogue — iOS is far more aggressive | The resumable path (§9.1) becomes **more** important, not less. |
| `MediaStore` publish to `Movies/<App>/` | output | `PHPhotoLibrary.performChanges` / `AVAssetExportSession` to a shared container | |
| gitignored model assets + `ModelDownloader` | 87.9 MB htdemucs, `downloadUrl = null` | on-demand resources or a hosted fetch | No public host exists for htdemucs or the NSFW gate today (`ml/Models.kt:107`, `docs/prd-download-share.md:172`). |

---

## 11. Test artifacts to port

| Android test | what it locks | port target |
|---|---|---|
| `app/src/test/resources/dsp/stft_small.json` (nfft 64, hop 16, T 4123) | forward CaC + iSTFT round trip vs a numpy f64 reference, atol 1e-4 | Swift STFT unit test. Regenerate with `scripts/stft_golden.py` if needed. |
| `DspTest.fullSizeInteriorRoundTripHighSnr` | `Stft(4096,1024)` on T = 343 980, interior (guard 8192) SNR > 80 dB on a band-limited probe | Swift STFT unit test |
| `DspTest.frameArithmetic` | `bins == 2048`; `le(343980) == 336`; `le(335*1024) == 335`; `le(335*1024+1) == 336`; `le(336*1024+1) == 337`; `Stft(64,16).le(4123) == 258`, `.bins == 32` | trivial, port verbatim |
| `DemucsSeparatorTest` (13 tests, fake infer lambdas, no runtime) | every driver seam: identity round trip, normalization round trip, keepOther summing, single-chunk short input, time-branch passthrough, short/long stream length, full-skip passthrough, ±2 dilation width, C1 two-tier, bit-exact resume ×2, skipped-chunk progress, progress monotonicity | **Highest-value thing in this spec.** Port it first, before any AVFoundation code exists. |

---

## 12. Open risks a Swift engineer must decide

1. **42.67 ms is stale** — re-measure A/V lag at 44.1 kHz (expected 46.44 ms) and decide whether Apple's
   automatic priming compensation is kept (it makes output land ~46 ms earlier than Android's; correct,
   but a visible A/B difference). §6.2.
2. **Resampler is a known defect, not a spec** — fixing it breaks byte-parity with Android for every
   non-44.1 kHz source. Choose the QA gate accordingly. §5.3.
3. **FFT precision** — the Kotlin explicitly refuses f32 twiddles. Decide double-precision Accelerate vs
   a re-derived tolerance. §2.4.
4. **4-channel fold is wrong** (`c = 2` treated as center on quad). Unexercised. §5.2.
5. **Audio start anchor** — `firstPtsUs` clamped to ≥ 0 with no edit list, so a source whose audio
   genuinely starts *late* has that offset collapsed to 0. Untested, not known-broken (`docs/tasks.md:60`).
6. **`DILATE2_MIN_SCORE = 0.02`** is the product's miss-rate dial and has never had a listening test —
   only chunk counts (`docs/perf-plan-v4.md:437-441`). Do not tune it in the port.
7. **CoreML EP for htdemucs is unvalidated** and sits in the same fp16-kernel risk class that already
   corrupted the spectral branch under XNNPACK. §8/10.
