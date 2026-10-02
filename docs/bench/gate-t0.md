# GATE T0 — music-gate bake-off (plan §6)

Host screen, 2026-09-30. Fast lane only: GATE-0, GATE-0b, GATE-1 (`frame_mn06`).

## Method

Replica of `MusicGate.score` + `DemucsSeparator.separateChunk`:

- 44.1 kHz mono downmix `(L+R)/2`
- chunk grid `SEG=114660`, `STRIDE=103194`, `MAX_SHIFT=22050` (0.5 s pre-pad of track mean, matching the app)
- 441:160 linear-interp resample, last YAMNet frame flush against the end, silence floor 0.001 peak
- score = max over class ranges; `THRESHOLD=0.15`
- two-tier dilation: ±1 unconditional, ±2 only if own score ≥ 0.02
- host ORT **1.27.0 CPU EP**, intra-op 1, spinning 0. The app’s YAMNet session is XNNPACK×4 + intra-op 1; python ORT wheels have no XNNPACK.
- Judge is never the gate under test (§5.2): PANNs CNN14 32 kHz (`Cnn14_mAP=0.431.pth` from zenodo 3987831), 1 s hops, music = max over AudioSet 527-class vocal-music 27–37 and music block 137–282. A second is music at score ≥ 0.5. A second is “separated” if its owning chunk `floor((t + MAX_SHIFT) / STRIDE)` was separated.
- Projected SEP time = separated chunks × 2297 ms (S23 per-chunk, plan §1), also as c/min.
- GATE-1 threshold fitted on this vlog so music recall equals GATE-0’s (highest such threshold, least wasted-sep). Same clip is the only data — not a held-out test.

Self-check covers resample length (`SEG` → 41600), last-frame flush starts `[0, 15600, 26000]`, `FRAME+1` flush at start 1, and the Kotlin dilation unit test (`[0, 0.05, 0, 0.9, 0, 0.001, 0]` → separate `[1, 2, 3, 4]`).

## Commands

```
.venv-bench/bin/python scripts/bench/score_gate.py --self-check
.venv-bench/bin/python scripts/bench/score_gate.py
.venv-bench/bin/python scripts/bench/score_gate.py --cost   # re-run on an idle machine
```

Command that produced these numbers:

```
.venv-bench/bin/python scripts/bench/score_gate.py
```

## Class-map asserts

YAMNet class map from Appendix A
(`https://raw.githubusercontent.com/tensorflow/models/master/research/audioset/yamnet/yamnet_class_map.csv`),
521 rows, asserted in-script:

| index | name | role |
|---:|---|---|
| 24–32 | Singing, Choir, Yodeling, **Chant**, **Mantra**, Child singing, Synthetic singing, Rapping, Humming | vocal-music block (`MusicGate` KDoc) |
| 27 | Chant | GATE-0b drops this |
| 28 | Mantra | GATE-0b drops this |
| 131 | Whale vocalization | immediately before the music block |
| 132–276 | Music … Scary music | music block |
| 277 | Wind | immediately after |

## Vlog results

Clip: `qa-assets/vlog/vlog_44k.wav`, 269.214 s, 116 chunks. PANNs judge: 92 of 269 one-second bins ≥ 0.5.

| arm | sep / total | passed | missed-music s | wasted-sep s | music recall | proj SEP s | c/min | host ms/chunk | verdict |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| GATE-0 | 60/116 | 56 | 2 | 50 | 0.978 | 137.82 | 30716 | 46.03 * | control |
| GATE-0b | 60/116 | 56 | 2 | 50 | 0.978 | 137.82 | 30716 | 47.79 * | KEEP GATE-0 |
| GATE-1 | 66/116 | 50 | 2 | 65 | 0.978 | 151.60 | 33788 | 55.04 * | KEEP GATE-0 |

\* host ms/chunk is **contended, provisional** (other agents on this Mac). Median of 21 chunks after 3 warmup, one arm per subprocess. Re-run `--cost` idle.

Contended cost extras:

| arm | min ms | p90 ms | peak RSS MB |
|---|---:|---:|---:|
| GATE-0 | 13.12 | 256.41 | 301.0 |
| GATE-0b | 13.81 | 112.05 | 304.0 |
| GATE-1 | 20.45 | 98.34 | 293.1 |

GATE-0b is bit-identical to GATE-0 on this English vlog: Chant/Mantra never decide a chunk. The −10 % projected-SEP bar is not met (delta is 0). GATE-1, at a matched recall of 0.978 (fitted threshold 0.585), separates *more* chunks (66 vs 60) and wastes 15 extra seconds.

## GATE-1 export

- Checkpoint `frame_mn06_strong_1.pt` (PretrainedSED v0.0.1). `frame_mn10` not exported — mn06 already ran.
- `seq_len=65` (2.6 s × 40 ms). For a 41600-sample 16 kHz window the backbone already emits 65 frames, so the interpolate/pool is a no-op.
- Waveform-in ONNX failed: `torch.onnx` opset 17, `STFT does not currently support complex types`. Exported the CNN+head on mel `[1,1,128,260]`; numpy STFT+kaldi banks replicate `AugmentMelSTFT` eval (center-padded Hann 400 in 512, fmin=0, fmax=7000, fast_norm).
- ONNX `qa-assets/models/frame_mn06_seq65.onnx`, 5 463 352 bytes.
- numpy-mel vs torch max abs **6.11e-5**; ONNX vs torch max abs **9.02e-7**.
- Music score = max over AudioSet-Strong: Chant, Child singing, Choir, Female singing, Humming, Male singing, Mantra, Music, Rapping, Singing, Synthetic singing, Yodeling.
- Fitted threshold: **0.5849516987800598**.
- Licence: PretrainedSED `LICENSE` is MIT, Copyright 2024 Florian Schmid. Release assets (`frame_mn06_strong_1.pt`) are published in that repo with no separate weights licence; treated as MIT. Training data is AudioSet Strong (YouTube).

## Recitation / adhan (J3, eval only)

Sources: EveryAyah Al-Fatiha 001001–001007 from `Alafasy_128kbps`, `Husary_128kbps`, `Minshawy_Murattal_128kbps` (21 ayat). Adhan: Aaqib Azeez, Wikimedia Commons CC BY-SA 4.0,
https://commons.wikimedia.org/wiki/File:The_Adhan_-_Muslim_Call_to_Prayer_-_Aaqib_Azeez.mp3

Concatenated duration 224.273 s, 97 chunks. No recitation labels for the PANNs judge (forced zeros); **pass-through is the J3 metric**. “Wasted-sep s” here is just seconds sent to SEP under that zero-music judge.

| set | arm | chunks | passed | pass-through | wasted-sep s |
|---|---|---:|---:|---:|---:|
| all-recite+adhan | GATE-0 | 97 | 0 | **0.0%** | 224 |
| all-recite+adhan | GATE-0b | 97 | 36 | **37.1%** | 139 |
| all-recite+adhan | GATE-1 | 97 | 71 | **73.2%** | 61 |

### Per-file GATE-0 trip classes

Plan §6.1 hypothesis: AudioSet Chant/Mantra fire on recitation and adhan. Confirmed. GATE-0 passed **zero** chunks on every file. Top class is Chant on 19/22 files, Mantra on 1 (Alafasy 001004), Music on 2 Minshawy ayat.

| file | chunks | GATE-0 passed | top music class | Chant | Mantra |
|---|---:|---:|---|---:|---:|
| Alafasy_128kbps/001001.mp3 | 3 | 0 | Chant (0.366) | 0.366 | 0.359 |
| Alafasy_128kbps/001002.mp3 | 3 | 0 | Chant (0.923) | 0.923 | 0.901 |
| Alafasy_128kbps/001003.mp3 | 3 | 0 | Chant (0.182) | 0.182 | 0.153 |
| Alafasy_128kbps/001004.mp3 | 3 | 0 | Mantra (0.592) | 0.587 | 0.592 |
| Alafasy_128kbps/001005.mp3 | 4 | 0 | Chant (0.808) | 0.808 | 0.759 |
| Alafasy_128kbps/001006.mp3 | 3 | 0 | Chant (0.917) | 0.917 | 0.881 |
| Alafasy_128kbps/001007.mp3 | 6 | 0 | Chant (0.935) | 0.935 | 0.893 |
| Husary_128kbps/001001.mp3 | 3 | 0 | Chant (0.976) | 0.976 | 0.930 |
| Husary_128kbps/001002.mp3 | 3 | 0 | Chant (0.992) | 0.992 | 0.985 |
| Husary_128kbps/001003.mp3 | 3 | 0 | Chant (0.969) | 0.969 | 0.946 |
| Husary_128kbps/001004.mp3 | 3 | 0 | Chant (0.938) | 0.938 | 0.896 |
| Husary_128kbps/001005.mp3 | 4 | 0 | Chant (0.903) | 0.903 | 0.844 |
| Husary_128kbps/001006.mp3 | 3 | 0 | Chant (0.980) | 0.980 | 0.927 |
| Husary_128kbps/001007.mp3 | 7 | 0 | Chant (0.992) | 0.992 | 0.987 |
| Minshawy_Murattal_128kbps/001001.mp3 | 3 | 0 | Music (0.383) | 0.248 | 0.188 |
| Minshawy_Murattal_128kbps/001002.mp3 | 3 | 0 | Chant (0.853) | 0.853 | 0.838 |
| Minshawy_Murattal_128kbps/001003.mp3 | 3 | 0 | Chant (0.501) | 0.501 | 0.437 |
| Minshawy_Murattal_128kbps/001004.mp3 | 2 | 0 | Chant (0.951) | 0.951 | 0.918 |
| Minshawy_Murattal_128kbps/001005.mp3 | 3 | 0 | Chant (0.939) | 0.939 | 0.897 |
| Minshawy_Murattal_128kbps/001006.mp3 | 3 | 0 | Music (0.179) | 0.110 | 0.092 |
| Minshawy_Murattal_128kbps/001007.mp3 | 7 | 0 | Chant (0.992) | 0.992 | 0.970 |
| adhan_aaqib_azeez.mp3 | 38 | 0 | Chant (0.995) | 0.995 | 0.994 |

Adhan also scores Music 0.452, so GATE-0b (which still includes 132–276) does not pass the adhan through. Two Minshawy ayat are the same: the parent `Music` class is above 0.15 without Chant/Mantra.

## Decision rule (§6.2)

Adopt a new gate (or GATE-0b) if missed-music ≤ GATE-0, false-mute = 0 s, and projected SEP time ≤ GATE-0 − 10 %. J2 mute is not in these arms (false-mute = 0 by construction).

- **GATE-0:** control. 60/116 chunks separated on the vlog; 0 % pass-through on recitation+adhan.
- **GATE-0b:** **KEEP GATE-0 on the §6.2 vlog rule** (identical 137.82 s projected SEP). On J3 it is the cheap win the plan hoped for: recitation pass-through 0 % → 37.1 %, vlog metrics unchanged. It does not clear adhan, because `Music` still fires (0.452). Worth shipping as a one-line range change *for J3*, not because it cuts film SEP.
- **GATE-1:** **KEEP GATE-0 on §6.2.** Matched recall 0.978, but 66 vs 60 chunks separated (wasted-sep 65 s vs 50 s; proj SEP 151.60 s, +10 % vs GATE-0). Recitation pass-through 73.2 % at the vlog-fitted threshold. Licence: MIT (amber on AudioSet training data, same as YAMNet).

## Caveats

- No hand labels. PANNs at 0.5 is the music timeline; 92 s of a 269 s vlog is “music” under that rule.
- Single English vlog, 4:29. GATE-1 threshold was fit on this same clip.
- Recitation/adhan are eval-only (EveryAyah per-recording licence undocumented; adhan CC BY-SA 4.0).
- Host timings are contended and CPU-EP; device XNNPACK numbers will differ. GATE-0 p90 256 ms vs median 46 ms is chunk-mix (1 vs 3 YAMNet frames + early-exit) plus contention.
- Chunk 0 includes 0.5 s of track-mean pre-pad, matching the app.

## How to rerun

```
.venv-bench/bin/python scripts/bench/score_gate.py
.venv-bench/bin/python scripts/bench/score_gate.py --cost
```

Outputs under `qa-assets/bench-out/gate/`: per-chunk CSVs, `judge_timeline.csv`, `results.json`.
