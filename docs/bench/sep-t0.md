# SEP T0 — host screen (plan §5.1–5.2)

**Date:** 2026-09-30. **Host:** Mac arm64, ORT 1.27.0 CPU EP, 8 cores, intra-op 6 (htdemucs `HtdemucsSession` cap), spinning off, arena off. Host python ORT has no XNNPACK provider (`CPUExecutionProvider` only for these runs; CoreML was available and unused).
**Harness:** `scripts/bench/score_sep.py`. Raw JSON: `qa-assets/bench-out/sep/{quality,vlog,cost,verdicts}.json`. Sources: `qa-assets/bench-out/sep/SOURCES.md`.
**Cost numbers are contended, provisional** (other CPU-heavy agents on the same Mac). Re-run `score_sep.py cost` on an idle machine before trusting ratios.

## Commands

```
.venv-bench/bin/python scripts/bench/score_sep.py prepare
.venv-bench/bin/python scripts/bench/score_sep.py quality
.venv-bench/bin/python scripts/bench/score_sep.py vlog
.venv-bench/bin/python scripts/bench/score_sep.py cost
```

`prepare` wrote 96 mixes (24 per SNR bin) under `qa-assets/bench-out/sep/mixes/`.
`cost` spawned one subprocess per arm:

```
.venv-bench/bin/python scripts/bench/score_sep.py cost-worker --arm <sep0|sep1|sep2|sep7> \
  --wav qa-assets/bench-out/sep/cost_60s.wav --runs 3 --warmup 1
```

Unseparated-vlog YAMNet baseline (same judge as `vlog`):

```
.venv-bench/bin/python -c "import sys; sys.path.insert(0,'scripts/bench'); import score_sep as s, json
print(json.dumps(s.Yamnet().residual_music_seconds(s.load_wav(s.VLOG_44K), s.SR)))"
```

→ `{"frames": 276, "hits": 90, "seconds": 87.75, "peak": 0.9988793134689331, "duration_s": 269.1}`

Quantization (`score_sep.py quant`) was **not run**: SEP-1 failed the §5.2 T0→T1 rule.

## Arms

| ID | Weights | Geometry (from the thing that owns it) | Vocals path |
|---|---|---|---|
| **SEP-0** | `htdemucs_s26_f16.onnx` 87.851483 MB | App: `SEG=114660` (2.6 s), `STRIDE=103194` (10 % overlap), `MAX_SHIFT=22050`, STFT nfft 4096 hop 1024 **outside** the graph (`stft_golden.py` / `Dsp.kt`). Inputs `input [1,2,114660] f32` + `x [1,4,2048,112] f32`. Stem 3 (vocals) only. Whole-track mono-mix mean/std (Bessel N−1), triangle OLA, PRD tanh softclip. Ungated: every chunk inferred. | 4-stem graph, keep vocals |
| **SEP-1** | `UVR_MDXNET_3_9662.onnx` 29.704436 MB | UVR hash `d7bff498db9324db933d913388cba6be` → `model_data_new.json`: n_fft **6144**, dim_f **2048**, dim_t **256** (`2**8`), compensate **1.035**, primary **Vocals**, hop 1024. Segment `chunk_size=261120` = 5.921 s. Overlap 0.25, Hanning OLA, first 3 bins zeroed, denoise **off**. | 2-stem, primary vocals × compensate |
| **SEP-2** | `UVR_MDXNET_9482.onnx` 29.704436 MB | UVR hash `0ddfc0eb5792638ad5dc27850236c246` → same geometry as SEP-1 (twin). | same |
| **SEP-7** | `gtcrn_simple.onnx` 0.535638 MB | sherpa-onnx metadata: 16 kHz, n_fft 512, hop 256, `hann_sqrt`, streaming caches. Resample 44.1k stereo → 16 k mono → enhance → back. | speech enhancer, not a stem separator |

MDX hash is MD5 of the last `10000*1024` bytes (UVR / python-audio-separator). Geometry is from `qa-assets/models/model_data_new.json`.

## Pseudo-A1 mixes

Speech (10 s, 44.1 kHz stereo):

- Mini LibriSpeech `dev-clean-2` (OpenSLR 31, CC BY 4.0 subset of LibriSpeech / OpenSLR 12, tarball 126 MB): utterances `8842-304647-0002`, `3000-15664-0041`, `174-168635-0018`.
- In-domain vlog, YAMNet Speech high and music-block max `< 0.02`: **253.5–263.5 s** (speech 0.998, music 0.0007), **110.175–120.175 s** (0.993, 0.0018), **123.825–133.825 s** (0.990, 0.0079).

Music (10 s clips):

| id | kind | licence |
|---|---|---|
| carefree | instrumental | Kevin MacLeod, CC BY 3.0, incompetech `Carefree.mp3`, start 20 s |
| night_owl | instrumental | Broke For Free — Night Owl, CC BY, FMA, start 30 s |
| auld_lang_syne | **sung** | Frank C. Stanley 1910, public domain, Wikimedia, start 20 s |
| battle_hymn | **sung** | Stanley & Stevenson, public domain, Wikimedia, start 10 s |

Mix recipe: music-to-speech SNR \(10\log_{10}(P_\text{music}/P_\text{speech})\) in {−5, 0, 5, 10} dB, then peak-scale the triple to 0.99 if needed. **6 speech × 4 music = 24 mixes per bin, 96 total.** 48 of those use a sung track.

These are **pseudo-stems**: clean speech and a superimposed music/singing track, not real multi-mic stems. No listening test yet — §5.4 is the replacement gate.

## Quality (fast_bss_eval, mono mix of stereo)

`si_sdr(speech, vocals_out)`; SIR/SAR from `si_bss_eval_sources` on `[speech, music]` vs `[vocals_out, mix−vocals_out]`, `compute_permutation=False`, `clamp_db=50`. Mean and median over 24 mixes per bin.

### Speech SI-SDR (dB)

| SNR | SEP-0 mean | med | SEP-1 mean | med | SEP-2 mean | med | SEP-7 mean | med |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| −5 | 12.5778 | 12.6053 | 13.1700 | 12.4264 | 13.2994 | 12.3196 | 10.3915 | 10.7531 |
| 0 | 8.8703 | 9.0483 | 9.3556 | 9.2187 | 8.8111 | 8.7568 | 5.9618 | 6.3554 |
| 5 | 4.9314 | 5.5091 | 4.6442 | 4.5475 | 4.0637 | 3.4044 | 1.0658 | 0.7627 |
| 10 | 0.7019 | 1.7281 | 0.1092 | 0.3817 | −0.5871 | −0.9079 | −4.4515 | −4.3139 |

SEP-1 − SEP-0 SI-SDR: **+0.592 / +0.485 / −0.287 / −0.593** dB. The +10 dB bin is 0.093 dB below the −0.5 dB floor.

### SIR vs music (dB)

| SNR | SEP-0 mean | med | SEP-1 mean | med | SEP-2 mean | med | SEP-7 mean | med |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| −5 | 21.5754 | 21.8786 | 22.9157 | 23.3541 | 22.7734 | 22.8475 | 17.0638 | 16.6314 |
| 0 | 18.6827 | 19.0315 | 19.6843 | 20.6118 | 18.1818 | 19.6436 | 13.0821 | 13.2714 |
| 5 | 15.6089 | 16.2139 | 14.9970 | 15.2316 | 13.7885 | 12.3254 | 7.7048 | 9.2622 |
| 10 | 12.2014 | 12.8775 | 11.0197 | 10.8444 | 10.1351 | 8.4689 | 1.4469 | 3.0577 |

SEP-1 SIR at 0 dB is **+1.002 dB** vs SEP-0; at 5 dB **−0.612 dB** (needs ≥ 0).

### SAR (dB)

| SNR | SEP-0 mean | med | SEP-1 mean | med | SEP-2 mean | med | SEP-7 mean | med |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| −5 | 19.7778 | 16.8665 | 17.1683 | 16.6827 | 15.4800 | 13.5868 | 11.7801 | 12.4487 |
| 0 | 17.1606 | 14.4580 | 15.4708 | 15.5189 | 14.6229 | 14.1538 | 7.5356 | 7.7874 |
| 5 | 13.7775 | 11.7741 | 13.3521 | 12.8550 | 14.4415 | 13.2348 | 4.2246 | 4.5733 |
| 10 | 10.4368 | 9.0823 | 12.9375 | 12.5976 | 13.2655 | 12.7238 | 2.7826 | 1.4533 |

### Singing energy retained (48 sung-track mixes)

Projection of vocals_out onto the singing stem, plus raw energy ratio \(E[\hat s^2]/E[s_\text{sing}^2]\).

| arm | energy_ratio mean | med | proj_fraction mean | med |
|---|---:|---:|---:|---:|
| SEP-0 | 1.6564 | 1.1928 | 0.3468 | 0.2047 |
| SEP-1 | 1.7993 | 1.2209 | 0.4563 | 0.4940 |
| SEP-2 | 1.7413 | 1.2592 | 0.5026 | 0.5188 |
| SEP-7 | 0.6417 | 0.2497 | 0.2038 | 0.0687 |

MDX keeps more of the singing stem than htdemucs. GTCRN suppresses it (speech enhancer). The product *wants* singing removed; higher retained singing is a cost, not a win.

NaN/Inf: **0** on every quality mix and every vlog/cost output.

## Real vlog, reference-free

Full `qa-assets/vlog/vlog_44k.wav` (269.2135 s, 44.1 kHz stereo). Judge = bundled `yamnet.onnx`, max over classes **132..276 and 24..32**, threshold **0.15**, non-overlapping **0.975 s** frames. YAMNet is the judge here, not a candidate.

Outputs: `qa-assets/bench-out/sep/{sep0,sep1,sep2,sep7}_vlog.wav`.

| arm | residual-music seconds | hits / 276 frames | peak score | wall_s (contended) |
|---|---:|---:|---:|---:|
| unseparated | 87.75 | 90 | 0.9989 | — |
| SEP-0 | **5.85** | 6 | 0.8392 | 92.098 |
| SEP-1 | 32.175 | 33 | 0.9969 | 86.990 |
| SEP-2 | 32.175 | 33 | 0.9989 | 87.563 |
| SEP-7 | 29.25 | 30 | 0.9704 | 8.850 |

YAMNet’s music block includes Singing/Choir/Chant (24–32), so a model that keeps singing is scored as residual music. That lines up with the singing-energy table. It is still the metric the task asked for.

## Cost (contended, provisional)

60 s clip = first 60 s of `vlog_44k.wav`. Warmup 1 + median of 3, one arm at a time in a subprocess (`ru_maxrss`). Historical htdemucs anchor on this host: **152.3 ms/s** (`perf-plan-v4` §6.4).

| arm | ms/s (3 runs) | **median ms/s** | vs SEP-0 | vs 152.3 | peak RSS MB | file MB | NaN/Inf |
|---|---|---:|---:|---:|---:|---:|---:|
| SEP-0 | 390.22, **384.15**, 299.36 | **384.15** | 1.00× | 2.52× | 1216.08 | 87.851 | 0 |
| SEP-1 | 341.07, 306.69, 552.52 | **341.07** | **0.888×** | 2.24× | 884.42 | 29.704 | 0 |
| SEP-2 | 373.44, 329.50, 350.23 | **350.23** | **0.912×** | 2.30× | 840.77 | 29.704 | 0 |
| SEP-7 | 36.99, **35.77**, 33.30 | **35.77** | **0.093×** | 0.235× | 324.80 | 0.536 | 0 |

SEP-0 RSS 1.22 GB is in the same band as the app’s 1.30 GB 2.6 s figure. The 2.5× gap vs 152.3 ms/s is the contended machine (run spread on SEP-0 is 299–390 ms/s in one trio). Re-run `score_sep.py cost` idle before locking a ratio.

Denoise (MDX pos+neg spectrum) was left off. It would run the ONNX graph twice per window; no timed denoise pass in this T0.

## Quantization

Skipped. SEP-1 failed the T0 advance rule, so the static QDQ / dynamic MatMul-only pass was not started.

## §5.2 verdicts

Default vocals-mode advance: SI-SDR ≥ SEP-0 − 0.5 dB **at every bin**; SIR ≥ SEP-0 at **0 and 5 dB**; host cost ≤ **0.7×** SEP-0.
HQ: SI-SDR ≥ SEP-0 + 1 dB every bin and cost ≤ 1.2×.
Fast-mode (SEP-7/8): cost ≤ 0.1× SEP-0 **and** residual-music seconds ≤ 2× SEP-0.

| arm | SI-SDR every bin | SIR at 0 & 5 | cost ≤ 0.7× | **default T1** | HQ | fast-mode |
|---|---|---|---|---|---|---|
| SEP-1 | fail (+10 dB: −0.593 dB vs −0.5 floor) | fail (5 dB SIR −0.612 dB) | fail (0.888×) | **no** | no | — |
| SEP-2 | fail (+5 and +10) | fail (0 and 5 dB) | fail (0.912×) | **no** | no | — |
| SEP-7 | fail (all bins) | fail | 0.093× (this clause only) | **no** | no | **no** (cost 0.093× ≤ 0.1×; residual-music 29.25 s > 2×5.85 = 11.70 s) |

SEP-1 is the closest MDX: it **beats** SEP-0 SI-SDR and SIR at −5 and 0 dB, then loses at louder music, and the contended cost ratio is 0.89×. Idle re-cost could move the 0.7× clause; it cannot move the +10 dB SI-SDR or +5 dB SIR misses.

## Device T1 input (SEP-1, if the owner still wants a microbench)

Nothing advances. If T1 is run on SEP-1 anyway, the graph input is:

- name: `input`
- dtype: `float32`
- ONNX shape: `['batch_size', 4, 2048, 256]`
- **`bench_shape 1,4,2048,256`**
- STFT lives **outside** the graph (n_fft 6144, hop 1024, dim_f 2048, Hann periodic, center, first 3 bins zero). A random-tensor `bench_model` run times the conv graph only.

SEP-2 is the same tensor contract.

SEP-0 T1 already exists in-app: `input [1,2,114660]` + `x [1,4,2048,112]`.

SEP-7 T1 would be streaming `mix [1,257,1,2]` plus three caches; that is a different harness.

## Caveats

- Pseudo-stems, English-only speech, two 1910 sung cylinders plus two CC-BY instrumentals. Domain gap vs film/vlog scoring is large.
- §5.4 listening test is the replacement gate. No dB number here overrides it.
- YAMNet as residual-music judge shares the app gate’s singing classes; MDX’s higher singing-retained shows up as leftover “music” on the vlog.
- Host cost is contended. SEP-0 median 384.15 ms/s vs the 152.3 ms/s idle anchor.
- `vocals_other` (“keep sound effects”) has no 2-stem equivalent; owner decision §12.
- Arabic CER / DNSMOS / A2 (DnR, CineAudioDB) were out of this fast-lane T0.
