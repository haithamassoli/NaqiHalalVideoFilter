# More separator candidates — 2026-09-30 (owner request)

Owner rule (2026-09-30): "remove music" removes **instruments** and keeps **speech and singing**, i.e. the vocals stem.
Scripts: `scripts/bench/sep_light.py` (SEP-5 SCNet, SEP-6 DTTNet, SEP-9 Spleeter) and `scripts/bench/sep_roformer.py` (SEP-R3 Mel-Band RoFormer
Kim, SEP-R2 BS-RoFormer viperx `ep_317_sdr_12.9755`). Both export ONNX with STFT outside the graph, and ONNX-vs-torch parity is ≥ 90 dB for both RoFormers
(`roformer_parity.json`, `sep_light_parity.json`). Moises-Light (arXiv 2510.06785) has no public weights, so it was skipped. The anvuew BS-RoFormer
(21.8 s window) was too heavy to export on the Mac and was replaced by viperx (8 s).

## Quality: same 8 mixes for every arm

The agents' own `*_quality.json` compare a subset against the **full-set** SEP-0 mean. `every_4` is carefree-only, so those deltas are invalid.
This table re-scores every arm on the same 8 mixes (per SNR bin: the first `night_owl` instrumental mix and the first `battle_hymn` sung mix), using
`scripts/bench/sep_eval8.py` → `qa-assets/bench-out/sep/eval8_<arm>.json`.

Speech metrics use the **instrumental** mixes only. On sung mixes, the pseudo-stem lumps singing with instruments, so a model that keeps singing
(which the owner wants) is penalised. Sung clips are therefore judged by ear.

| arm | Δ speech SI-SDR vs SEP-0 | Δ SIR vs SEP-0 | SI-SDR @ −5/0/5/10 dB | Mac s per 10 s mix (median of 8) |
|---|---:|---:|---|---:|
| SEP-0 htdemucs | — | — | 22.2 / 19.0 / 16.1 / 13.2 | 2.14 |
| SEP-1 UVR MDX 3_9662 | +1.80 | +2.46 | 23.3 / 20.7 / 18.2 / 15.6 | 2.54 |
| SEP-5 SCNet-tran | +3.35 | +1.55 | 24.9 / 22.4 / 19.8 / 16.8 | 7.74 |
| SEP-6 DTTNet | +3.05 | +6.25 | 24.5 / 22.0 / 19.5 / 16.9 | 31.78 |
| SEP-9 Spleeter 2-stem | −4.85 | −4.06 | 17.1 / 11.3 / 12.4 / 10.5 | 0.16 |
| SEP-R3 Mel-Band RoFormer | +6.12 | +11.33 | 27.5 / 25.1 / 22.6 / 19.9 | 81.81 |
| SEP-R2 BS-RoFormer | +6.93 | +12.07 | 28.1 / 25.9 / 23.5 / 20.9 | 201.73 |

The Mac column is wall time with the host idle (all Grok agents stopped).

## S23 cost (T1 `bench_model`, screen on / top-app, Standard profile, CPU EP 6 threads, fp32 except htdemucs f16)

Raw: `qa-assets/bench-out/device/t1-sep-more/bench_lines.txt`. The phone warmed from 26 °C to 38 °C across the run.

| arm | p50 per window | new audio per window | **ms per audio-second** | hwm RSS |
|---|---:|---:|---:|---:|
| SEP-0 htdemucs | 1 574 ms | 2.34 s | 673 | 1.21 GB |
| SEP-1 UVR MDX | 1 857 ms | 4.44 s (0.25 overlap) | **418** | 0.79 GB |
| SEP-5 SCNet | 1 733 ms | 1.37 s (ref overlap 2) | 1 261 (630 with no overlap) | 1.32 GB |
| SEP-6 DTTNet | 4 524 ms | 1.48 s (ref overlap 4) | 3 056 (1 019 at SEP-1's 0.25 overlap) | 1.55 GB |
| SEP-9 Spleeter (2 graphs) | 136 + 137 ms | 11.89 s | **23** | 0.38 GB |
| SEP-R2 BS-RoFormer | — | 4.0 s | not measured: the phone dropped off USB while loading the 645 MB model | — |
| SEP-R3 Mel-Band RoFormer | — | 4.0 s | not measured | — |

## Reading

- **SEP-1 is still the only arm that is both cheaper than htdemucs on the S23 and better on the instrumental mixes.** It also keeps singing, as the owner wants.
- DTTNet and SCNet are about 3 dB better than htdemucs, but cost more on the S23, even with the overlap cut.
- The RoFormers are clearly the best (+6–7 dB), but cost 40–90× htdemucs on the Mac. On the phone they would process a film in days, not minutes.
  They are useful as a quality reference and as a teacher for distillation, not as the shipped model.
- Spleeter is about 29× cheaper than htdemucs but loses about 5 dB. It could only serve as a low-end "fast" mode.
- Only 8 mixes of 10 s each. Treat the deltas as ranking signals and let the owner's listening decide.
  Clips: `qa-assets/bench-out/sep/song/{vlog_music30,battle_hymn30}_<arm>.wav`.
