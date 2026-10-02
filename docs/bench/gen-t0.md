# GEN-0 vs GEN-1 — host T0 (plan §7.1)

Harness: `scripts/bench/score_gen.py`. Crop geometry and the per-track vote copy `FrameSampler.cropToTensor`, `FaceTracker` (`VOTE_CAP=5`, strictly-larger, `EVICT_AFTER_MS=2000`, `shouldCensor` for Women mode) and `FilterWorker` (`p_male = sigmoid(male−female)`, `CONF_FLOOR` on `max(p,1−p)`). Detector is OpenCV YuNet, not ML Kit. Face cadence is **5 fps** (app is 10 fps) on `maxDim=640` frames.

Host ORT **1.27.0**, providers `CoreML / Azure / CPU`. Sessions use **CPU EP**, `intra_op=1`, `session.intra_op.allow_spinning=0`. The app’s `imageSessionOptions` add XNNPACK ×4; host Python ORT has no XNNPACK.

GEN-0 always sees the saved InsightFace **1.5×** square (app crop). GEN-1 is centre-cropped from that square at `--gen1-scale` × the detector box (default **1.0**, the OMZ tight box). The saved 1.5× window is edge-clamped, so a centre crop is exact only when that window sat inside the frame; when it clamped, edge pixels repeat and the face can sit off-centre.

## Commands

```
.venv-bench/bin/python scripts/bench/score_gen.py extract
.venv-bench/bin/python scripts/bench/score_gen.py cost --n 100 --warmup 20
.venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.0
.venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.2
```

`extract` writes crops / contact sheets / context thumbs / empty `labels.csv`, converts GEN-1, and parity-tests. Fill `label` with `m` / `f` / `junk` / `skip`, then `score`. Re-run `cost` on an idle machine; numbers below are **contended, provisional**.

## Extract (this run)

```
.venv-bench/bin/python scripts/bench/score_gen.py extract
```

Source `qa-assets/vlog/vlog.webm` 1920×1080 → analyze 640×360, 5 fps, YuNet score 0.5, NMS 0.3, min face 0.1 × shorter side = **36.0 px**. YuNet: `qa-assets/models/face_detection_yunet_2023mar.onnx` (Hugging Face `opencv/face_detection_yunet`, sha256 `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4`). OpenCV 5 prints `setPreferableTarget Targets are not supported by the new graph engine for now`; detect still returned boxes.

| | |
|---|---:|
| frames | 1346 |
| detections | 1140 |
| tracks with crops | 82 |
| crops | 171 |
| contact sheets | 7 (`sheets/sheet_00.png` … `sheet_06.png`, 12 tracks/sheet, 128 px cells) |
| context thumbs | 82 |
| wall | 45.1 s |

Crop px (max side of the raw box): min 43, median 143, max 294. Size bands: **<40: 0**, **40–80: 29**, **≥80: 142**. Detector floor 36 px, so the <40 band is empty on this clip.

`labels.csv` columns `track,label,n_crops,min_px,max_px`. Extract wrote empty labels; the orchestrator filled them (see Labels). Sheets plus `context/<id>.jpg` (green = YuNet box, yellow = 1.5× InsightFace square).

`MIN_FACE_PX=80` is applied at **score** (a crop below 80 px does not vote). Extract keeps the streaming strictly-larger `VOTE_CAP=5` sequence with no size floor, so 40–80 crops exist to label.

## GEN-1 conversion + parity

IR from OMZ 2023.0:

- `https://storage.openvinotoolkit.org/repositories/open_model_zoo/2023.0/models_bin/1/age-gender-recognition-retail-0013/FP32/age-gender-recognition-retail-0013.xml`
- `…/age-gender-recognition-retail-0013.bin`

Graph is Parameter / Const / Convolution / Add / MaxPool (`rounding_type=ceil` → ONNX `ceil_mode=1`) / ReLU / SoftMax. Converted with `onnx.helper` opset 12 (no PINTO). Input `data` `[1,3,62,62]` NCHW **BGR 0..255**. Outputs `prob` `[1,2,1,1]` (female, male) and `fc3_a` `[1,1,1,1]` (age/100).

```
qa-assets/models/gen1_agegender_0013.onnx
  8 555 017 bytes  sha256 29c2c8578278bce367227ddb1e5386a9f5d44c8e57662a02e8758484c7f59b84
qa-assets/models/gen1_agegender_0013_int8dyn.onnx   (ORT quantize_dynamic QInt8)
  2 155 295 bytes  sha256 d0f18183c0d456d71e58ad9d5aa029c56f543189496089a5c8fdae68642cc995
```

Parity vs OpenVINO 2026.4 CPU on **200** tensors (**171** real crops + 29 random 0..255), same 0..255 BGR NCHW feed:

```
parity n=200 real=171 max|Δ|prob=0.0185344 max|Δ|age=0.00534248 argmax=200/200
```

Saved in `qa-assets/bench-out/gen/parity.json`. Max abs on `prob` is 1.85e-2 (OpenVINO vs ORT numerics, not a class flip). Argmax agreement **200/200**.

Spot check, track 1 crop `c00_174px.png` (clear woman on the sheet):

| | female | male | age | p(male) |
|---|---:|---:|---:|---:|
| GEN-0 logits | 2.587 | −2.587 | 32.2 | 0.00563 |
| GEN-1 ONNX `prob` | 0.711 | 0.289 | 26.4 | 0.289 |
| GEN-1 OpenVINO `prob` | 0.706 | 0.295 | 26.4 | 0.295 |

## Cost (contended, provisional)

```
.venv-bench/bin/python scripts/bench/score_gen.py cost --n 100 --warmup 20
```

Warmup 20 + median of 100 `session.run` calls, one arm per subprocess, CPU EP as above. Other agents were running CPU jobs on this Mac. Re-run serially on an idle machine before any cost ranking.

| arm | file | median ms/crop | p90 | peak RSS |
|---|---:|---:|---:|---:|
| GEN-0 `genderage.onnx` | 1 322 532 B | **4.740** | 19.471 | 105.8 MB |
| GEN-1 FP32 ONNX | 8 555 017 B | **13.316** | 26.855 | 122.3 MB |
| GEN-1 dynamic INT8 | 2 155 295 B | **33.508** | 101.510 | 100.7 MB |

S23 incumbent is 4.87–5.01 ms/crop with XNNPACK (includes crop fill). These host numbers are run-only. Dynamic INT8 is slower on this CPU EP; not a candidate until a static QDQ / device run says otherwise. GEN-1 FP32 is 6.5× the file and ~2.8× the median ms of GEN-0 on this pass.

## Labels

Orchestrator hand-label of `qa-assets/bench-out/gen/labels.csv` from contact sheets + context thumbs. **Do not change this file.** Track 11 is a back-of-head in a cap (`skip`; gender unknowable).

| label | tracks | crops | max_px range |
|---|---:|---:|---|
| f | 67 | 148 | 51–274 |
| m | 2 | 3 | 43–49 (tracks 19 and 21) |
| junk | 12 | 19 | 55–294 |
| skip | 1 | 1 | 88 (track 11) |
| total | 82 | 171 | |

Both male tracks are **below** `MIN_FACE_PX=80`, so at the app vote floor they never vote. “Men visible ≥ 70 %” is unmeasurable here.

## Labelled accuracy

App operating point: `CONF_FLOOR=0.60`, `MIN_FACE_PX=80`. Balanced / per-class accuracy is over tracks (or crops) that **vote**; abstain is out of the denom. Women-exposed and men-visible use every labelled f/m track (abstain censors in Women mode). JSON: `qa-assets/bench-out/gen/score_s1.0.json`, `score_s1.2.json`.

```
.venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.0
.venv-bench/bin/python scripts/bench/score_gen.py score qa-assets/bench-out/gen/labels.csv --gen1-scale 1.2
```

### Per-track, floor 0.60, min_px=80 (app)

| | n vote (f/m) | female acc | male acc | balanced | abstain | women exposed | men visible | bar |
|---|---|---:|---:|---:|---:|---:|---:|---|
| GEN-0 (1.5×) | 58 (58/0) | 94.8% (55/58) | nan (0/0) | 94.8% | 15.9% (11/69) | **4.5% (3/67)** | 0.0% (0/2) | bal≥90 yes; men≥70 no; class collapse (no male votes) |
| GEN-1 scale 1.0 | 50 (50/0) | 62.0% (31/50) | nan (0/0) | 62.0% | 27.5% (19/69) | 28.4% (19/67) | 0.0% (0/2) | no |
| GEN-1 scale 1.2 | 53 (53/0) | 60.4% (32/53) | nan (0/0) | 60.4% | 23.2% (16/69) | 31.3% (21/67) | 0.0% (0/2) | no |

GEN-0’s women-exposed on this set is 4.5 %, next to the plan’s 4.8 % S23 citation. GEN-1 at the tight 1.0× box still exposes **19/67** female tracks (6.3× GEN-0).

### Per-crop, floor 0.60, min_px=80

Per-track n is tiny (and male n is zero). Per-crop is the usable comparison.

| | n (f/m) | voting f | female acc | male acc | abstain | junk m/f/abs |
|---|---|---:|---:|---:|---:|---|
| GEN-0 (1.5×) | 131 (131/0) | 127 | **95.3% (121/127)** | nan | 3.1% (4/131) | 8/2/0 (n=10) |
| GEN-1 scale 1.0 | 131 (131/0) | 120 | **68.3% (82/120)** | nan | 8.4% (11/131) | 9/0/1 (n=10) |
| GEN-1 scale 1.2 | 131 (131/0) | 116 | **68.1% (79/116)** | nan | 11.5% (15/131) | 6/2/2 (n=10) |

Tightening GEN-1 from 1.2× to 1.0× moves female-crop acc 68.1 % → 68.3 % and women-exposed 31.3 % → 28.4 %. Both sit far below GEN-0.

### Same floor, min_px=0 (40–80 px allowed to vote)

Drops the app’s 80 px vote floor so the two male tracks can vote. The app still uses `MIN_FACE_PX=80`.

| | n vote (f/m) | female acc | male acc | balanced | women exposed | men visible |
|---|---|---:|---:|---:|---:|---:|
| GEN-0 | 68 (66/2) | 93.9% (62/66) | 100% (2/2) | 97.0% | 6.0% (4/67) | 100% (2/2) |
| GEN-1 scale 1.0 | 60 (58/2) | 58.6% (34/58) | 100% (2/2) | 79.3% | 35.8% (24/67) | 100% (2/2) |
| GEN-1 scale 1.2 | 64 (62/2) | 56.5% (35/62) | 100% (2/2) | 78.2% | 40.3% (27/67) | 100% (2/2) |

Per-crop, min_px=0:

| | n (f/m) | female acc | male acc | balanced | junk m/f/abs |
|---|---|---:|---:|---:|---|
| GEN-0 | 151 (148/3) | 95.1% (135/142) | 100% (3/3) | 97.5% | 12/6/1 (n=19) |
| GEN-1 scale 1.0 | 151 (148/3) | 67.2% (90/134) | 100% (3/3) | 83.6% | 17/1/1 (n=19) |
| GEN-1 scale 1.2 | 151 (148/3) | 66.4% (87/131) | 100% (3/3) | 83.2% | 14/2/3 (n=19) |

Male 100 % is three crops on two sub-80 px tracks, so the app-floor men-visible bar stays unmeasured.

### CONF_FLOOR curve — per-track, min_px=80

Men visible is 0.0 % at every floor (no male track ≥ 80 px). `g0 vis` / `g1 vis` omitted.

| floor | g0 bal | g0 exp | g0 n_vote | g1@1.0 bal | g1@1.0 exp | g1@1.0 n | g1@1.2 bal | g1@1.2 exp | g1@1.2 n |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | 94.7% | 4.5% (3/67) | 57 | 58.9% | 34.3% (23/67) | 56 | 59.6% | 34.3% (23/67) | 57 |
| 0.55 | 94.7% | 4.5% (3/67) | 57 | 59.3% | 32.8% (22/67) | 54 | 58.5% | 32.8% (22/67) | 53 |
| 0.60 | 94.8% | 4.5% (3/67) | 58 | 62.0% | 28.4% (19/67) | 50 | 60.4% | 31.3% (21/67) | 53 |
| 0.65 | 96.5% | 3.0% (2/67) | 57 | 60.0% | 29.9% (20/67) | 50 | 63.3% | 26.9% (18/67) | 49 |
| 0.70 | 100.0% | 0.0% (0/67) | 55 | 63.3% | 26.9% (18/67) | 49 | 63.8% | 25.4% (17/67) | 47 |
| 0.75 | 100.0% | 0.0% (0/67) | 55 | 63.8% | 25.4% (17/67) | 47 | 69.8% | 19.4% (13/67) | 43 |
| 0.80 | 100.0% | 0.0% (0/67) | 53 | 63.8% | 25.4% (17/67) | 47 | 75.6% | 14.9% (10/67) | 41 |
| 0.85 | 100.0% | 0.0% (0/67) | 50 | 66.7% | 20.9% (14/67) | 42 | 78.9% | 11.9% (8/67) | 38 |
| 0.90 | 100.0% | 0.0% (0/67) | 48 | 75.0% | 14.9% (10/67) | 40 | 88.6% | 6.0% (4/67) | 35 |

Raising GEN-1’s floor toward 0.90 cuts women-exposed by abstaining (n_vote 53 → 35 at 1.2×). At 1.2× / 0.90, bal is 88.6 % and exposed is 6.0 % (4/67), with 34/69 mf tracks abstaining. That still misses the 90 % bal bar and the 4.5 % exposed cap.

### CONF_FLOOR curve — per-crop female acc, min_px=80

No male crop ≥ 80 px. Acc among voting female crops.

| floor | g0 female | g0 n_vote | g1@1.0 female | g1@1.0 n | g1@1.2 female | g1@1.2 n |
|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | 93.9% (123/131) | 131 | 64.1% (84/131) | 131 | 64.9% (85/131) | 131 |
| 0.55 | 94.5% (121/128) | 128 | 66.4% (83/125) | 125 | 65.6% (80/122) | 122 |
| 0.60 | 95.3% (121/127) | 127 | 68.3% (82/120) | 120 | 68.1% (79/116) | 116 |
| 0.65 | 96.0% (119/124) | 124 | 67.5% (79/117) | 117 | 69.4% (75/108) | 108 |
| 0.70 | 99.2% (119/120) | 120 | 68.8% (77/112) | 112 | 69.5% (73/105) | 105 |
| 0.75 | 99.2% (117/118) | 118 | 70.2% (73/104) | 104 | 74.2% (72/97) | 97 |
| 0.80 | 99.1% (114/115) | 115 | 71.3% (72/101) | 101 | 78.0% (71/91) | 91 |
| 0.85 | 99.1% (108/109) | 109 | 76.1% (67/88) | 88 | 80.5% (66/82) | 82 |
| 0.90 | 100.0% (105/105) | 105 | 80.8% (63/78) | 78 | 90.1% (64/71) | 71 |

### Size bands, floor 0.60

Track band uses the track’s max crop px. At min_px=80 the 40–80 band cannot vote, so track acc there is 0 (all abstain). Crop bands below are the useful split. Detector floor was 36 px, so `<40` is empty.

Track, min_px=80:

| band | n (f/m) | GEN-0 acc | GEN-1@1.0 acc | GEN-1@1.2 acc | notes |
|---|---|---:|---:|---:|---|
| 40–80 | 11 (9/2) | 0.0% (0/11) | 0.0% (0/11) | 0.0% (0/11) | no votes; includes both male tracks |
| ≥80 | 58 (58/0) | 94.8% (55/58) | 53.4% (31/58) | 55.2% (32/58) | GEN-1 acc here counts abstain as miss |

Track, min_px=0:

| band | n (f/m) | GEN-0 acc | GEN-1@1.0 acc | GEN-1@1.2 acc | GEN-0 exposed f | GEN-1@1.0 exposed f |
|---|---|---:|---:|---:|---:|---:|
| 40–80 | 11 (9/2) | 81.8% (9/11) | 45.5% (5/11) | 45.5% (5/11) | 1/9 | 5/9 |
| ≥80 | 58 (58/0) | 94.8% (55/58) | 53.4% (31/58) | 55.2% (32/58) | 3/58 | 19/58 |

Crop, floor 0.60 (acc among voting):

| band | n (f/m) | GEN-0 | GEN-1@1.0 | GEN-1@1.2 |
|---|---|---|---|---|
| 40–80 | 20 (17/3) | 94.4% (17/18 vote) | 64.7% (11/17 vote) | 61.1% (11/18 vote) |
| ≥80 | 131 (131/0) | 95.3% (121/127) | 68.3% (82/120) | 68.1% (79/116) |

### Junk (Women mode: a male-majority vote spares the track)

12 junk tracks, 19 junk crops. Skip track 11 is dropped.

| | min_px | vote m/f/abs | spared in Women |
|---|---|---|---:|
| GEN-0 | 80 | 4/2/6 | 4 |
| GEN-1 scale 1.0 | 80 | 6/0/6 | 6 |
| GEN-1 scale 1.2 | 80 | 4/1/7 | 4 |
| GEN-0 | 0 | 6/4/2 | 6 |
| GEN-1 scale 1.0 | 0 | 11/1/0 | 11 |
| GEN-1 scale 1.2 | 0 | 9/1/2 | 9 |

Per-crop junk, min_px=80: GEN-0 8/2/0, GEN-1@1.0 9/0/1, GEN-1@1.2 6/2/2 (n=10 crops ≥80). GEN-1 at the tight box is the most male-happy on non-faces: at min_px=0 it spares 11/12 junk tracks.

## Caveats (this is smoke, not V1)

- **One vlog.** `qa-assets/vlog/vlog.webm`, a 4:29 English “week in my life” clip.
- **Essentially one woman on camera.** The 67 female tracks are nearly all the same person, re-associated across cuts. Female acc is that person’s faces, not a population.
- **Two male tracks, both < 80 px.** Tracks 19 (43 px) and 21 (47–49 px). “Men visible ≥ 70 %” at the app’s `MIN_FACE_PX` cannot be measured. Male 100 % at min_px=0 is n=2 tracks / 3 crops.
- **One labeller.** Labels are the orchestrator’s from contact sheets + context thumbs, not a second pass.
- **YuNet stand-in for ML Kit**, 5 fps (app faces are 10 fps), `maxDim=640`.
- **GEN-1 crop is a centre crop of the saved 1.5× square.** Exact when the 1.5× window was in-frame; approximate when edge-clamped.
- Plan V1 asks for **≥300 tracks per class**. This set is 67 f / 2 m. Numbers here are a smoke-level signal.

## Decision (plan §7.1)

Bar: cheapest candidate that clears balanced acc ≥ 90 % over voting tracks with neither class collapsed, men visible ≥ 70 %, at women-exposed ≤ GEN-0’s rate on this set (**4.5 %**, 3/67). GEN-0 is 🔴 and cannot ship.

On this label set, **neither model clears**. GEN-0 misses men-visible and has no male voting tracks at `MIN_FACE_PX=80`. GEN-1 at both 1.0× and 1.2× misses balanced acc (62.0 % / 60.4 %), misses men-visible, and women-exposed is 28.4 % / 31.3 % — well above GEN-0’s 4.5 %. Raising `CONF_FLOOR` does not close the gap without abstaining a large fraction of tracks.

Provisional path, same as the plan when the bar is missed: **GEN-T**, or **Everyone-only**, until a licence-clean model clears a real V1 set. GEN-1 is not a drop-in on this smoke pass.
