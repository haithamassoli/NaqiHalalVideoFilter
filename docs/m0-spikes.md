# M0 — Spike decisions & foundation status

Status of the M0 milestone (`tasks.md`). Split into **code** (built + testable here) and **ML/hardware** (blocked on model export tooling, multi-GB downloads, reference datasets, and a mid-range target device).

## Decision 1 — Media3 Transformer vs raw MediaCodec → **Transformer (GO)**
Both de-risk questions resolve in Transformer's favour (recon verified against media3 **1.10.1** source):

1. **Custom per-frame GL effect** — `GlEffect` → `BaseGlShaderProgram` built from `GlProgram(vertexGlsl, fragmentGlsl)`. Working grayscale shader in `spike/GrayscaleTransformerSpike.kt` (`GrayscaleGlEffect`). Production grayscale can use the built-in `RgbFilter.createGrayscaleFilter()`.
2. **Audio replacement** — a multi-sequence `Composition`: a video-only `EditedMediaItemSequence` with `setRemoveAudio(true)` + a separate audio-only sequence. Overlapping items across sequences are **mixed**; because the video sequence emits no audio, the separate track effectively **replaces** the source. `setIsLooping(true)` on the audio sequence fits a shorter track to a longer video.

**Consequence for M1/M2:** the EDL-driven render is a `GlEffect` that varies blur/grayscale per frame; music-removed audio is muxed via the audio sequence. The raw `MediaCodec → GLES → MediaCodec → MediaMuxer` fallback stays documented but **unselected**.

**Caveats:** every Transformer/effect symbol is `@UnstableApi`. Any video effect forces a full decode+re-encode (no transmux) — the censor-only / music-only fast paths must apply **zero** video effects to keep transmux. HDR via `Composition.Builder.setHdrMode(...)`. H.265 encode is device-dependent → fall back to H.264.

## Decision 2 — ONNX Runtime → **1.27.0, XNNPACK the default EP**
`com.microsoft.onnxruntime:onnxruntime-android:1.27.0`. XNNPACK is **not** on by default — registered explicitly via `addXnnpack(map)` with intra-op threads pinned to 1 and spinning disabled (`ml/Models.kt`). `ModelSmoke.run()` force-loads the native lib, enumerates providers, and runs a zero-tensor inference through **every bundled model** on-device (surfaced in the app's *DEVICE RUNTIME* readout). NNAPI stays behind `ModelSmoke.useNnapi` **and** API ≥ 27 (absent on API 26; OS-deprecated at API 35).
- arm64-v8a-only ABI filter is **mandatory**: the universal AAR ships 4 ABIs (~108 MB of `.so`).
- 16 KB page alignment (Play requirement, Android 15+) is fixed in 1.27.0 — do **not** pin below.

## Decision 3 — Styles API (`/styles`) → **deferred (experimental + unmet prereq)**
The Compose Styles API (`androidx.compose.foundation.style`) is **experimental** and hard-requires **compileSdk 37** (only android-36/36.1 are installed; android-37 is not in this SDK manager's list), plus alpha/beta Compose foundation and jvmTarget 17. Adopting it now would risk the stable M0 build. Custom components (`OperationCard`, `TrustSeal`, `PickVideoCard`) are written with centralized, single-responsibility styling so the migration is mechanical.
**Flip steps when compileSdk 37 is available:** bump compileSdk 37 + Compose BOM ≥ 2026.06.01 + jvmTarget 17 + `-opt-in=androidx.compose.foundation.style.ExperimentalFoundationStyleApi`, then move component visuals into `Style {}` blocks in `ui/theme/ComponentStyles.kt`.

## Models — sourced, converted, installed (2026-07-22)
All three models live in `app/src/main/assets/models/` (**gitignored**; `scripts/fetch-models.sh` fetches NudeNet and reports what must be regenerated). `ml/Models.kt` holds the locked contracts; `ModelSmoke` copies each asset once to `filesDir/models/` (ORT wants a real path — M3's downloader replaces this), creates an XNNPACK session, and runs a zero-tensor inference. On-device (Galaxy S23, API 36): `NSFW_GATE: 84 ms → [1,5] · NUDENET: 76 ms → [1,22,2100] · HTDEMUCS: 409 ms load → [1,4,4,2048,112]` (htdemucs is load-only in the smoke since M2 owns executing it).

| file | size | sha256 (prefix) | source | license |
|---|---|---|---|---|
| `nsfw_mnv2_140_f32.onnx` | 17.3 MB | `049ce7c5` | GantMan/nsfw_model 1.2.0 (MobileNetV2 1.4-224) → tf2onnx | NOASSERTION — review |
| `nudenet_320n.onnx` | 12.2 MB | `c15d8273` | notAI-tech/NudeNet `v3.4-weights` release, as-is | **AGPL-3.0 — review before shipping** |
| `htdemucs_s26_f16.onnx` | 87.9 MB | `df8a2c2c` | facebookresearch/demucs weights via sevagh/demucs.onnx export, **re-exported at a 2.6 s segment (M3)** | MIT |

**NSFW gate** — input `input` [N,3,224,224] f32 NCHW RGB **/255, no mean/std**; output `prediction` [N,5] softmax; class order locked (alphabetical): `drawings, hentai, neutral, porn, sexy`. Converted with two-pass tf2onnx (`--inputs-as-nchw`), opset 17. Parity vs the TF SavedModel reference: max|Δ| = 3.6e-7, argmax 8/8. **f16 was tried and rejected**: XNNPACK's fp16 depthwise-conv path fails on-device (`xnn_create_convolution2d_nhwc_fp16` error 2) — keep f32 weights. Labeled-image spot-check carried to M1 gate QA (no labeled set available here).

**NudeNet 320n** — upstream ONNX used unmodified (reference *is* this artifact). Input `images` [1,3,320,320] f32 RGB /255; preprocessing locked from upstream source: pad right/bottom to square, resize 320, `blobFromImage(swapRB=true)`; output `output0` [1,22,2100] = 4 box rows (cx,cy,w,h in 320-space) + 18 class rows; upstream thresholds 0.2 keep / 0.25 NMS score / 0.45 IoU; `FACE_FEMALE`=1, `FACE_MALE`=12. Face-crop validation **done in M1 QA (2026-07-22)**: `FACE_FEMALE` fires ≥ 0.65 on portrait crops of *both* sexes (Einstein/Obama/Trump 0.69–0.83) while `FACE_MALE` stays ≤ 0.07 — reproduced with the upstream nudenet pip package, so the contract above is correct and the bias is the model's. Consequence: the M1 gender vote censors ~every face (over-censoring males — safe direction, PRD-accepted). Evaluate `640m` during M3 tuning.

**htdemucs f16** — exported with torch 2.13 (dynamo exporter, opset 18) from sevagh/demucs.onnx's fork (STFT/iSTFT outside the graph; one-line patch making its `diffq` import optional). Stems = istft(spec) + time branch, outside the graph (M2 driver).

**Segment re-export (M3, 2026-07-26).** The upstream converter bakes the checkpoint's 7.8 s training segment into the graph, and peak working set scales with it: 7.8 s measured **3.24 GB RSS** on the S23 during a real 5-min job — 2× over the PRD's 1.5 GB budget, and an OOM-kill risk on a 6 GB SD 778G. `scripts/htdemucs_export.py` re-exports at any segment (demucs' own documented `--segment` memory knob); `scripts/htdemucs_post.py <dir> <segment>` does f16 + parity. Measured on a 30 s clip (peak RSS / wall-clock / deviation of the shipped vocals stem from the trained 7.8 s output, torch `apply_model`):

| segment | ONNX IO | peak RSS | wall-clock | vocals vs 7.8 s | f16 parity spec/wave |
|---|---|---|---|---|---|
| 7.8 s | [1,2,343980] + [1,4,2048,336] | 3.24 GB | 1.4–4.2× realtime | reference | 61.5 / 65.9 dB |
| 3.9 s | [1,2,171990] + [1,4,2048,168] | 1.61 GB | 1.37× realtime | 26.1 dB | 64.4 / 68.2 dB |
| **2.6 s** | **[1,2,114660] + [1,4,2048,112]** | **1.30 GB** | **1.33× realtime** | **24.2 dB** | **63.4 / 69.0 dB** |

2.6 s ships: it is the only one that clears 1.5 GB with margin, and it costs ~2 dB of agreement with the trained configuration for no speed penalty. Conversion parity is unaffected by segment length (it measures ONNX-vs-torch at the *same* segment, so it cannot see context loss — that is what the vocals-deviation column is for). Geometry constants live in `audio/DemucsSeparator` and must change together with the export.

### Regen pipelines (macOS; venvs are throwaway, `W` = any workdir)
- **NSFW gate** (needs TF — system python3.9 works; `tensorflow-macos` is dead, use plain `tensorflow`):
  `python3 -m venv W/tf && W/tf/bin/pip install "tensorflow==2.15.*" tf2onnx onnx onnxruntime "numpy<2"` → `gh release download 1.2.0 -R GantMan/nsfw_model -p "*.zip"` + unzip into `W/g` → `W/tf/bin/python scripts/gantman_tf_convert.py W/g` (2-pass convert + TF-vs-ORT parity) → copy `W/g/nsfw_f32.onnx` to assets as `nsfw_mnv2_140_f32.onnx`.
- **htdemucs**: `python3 -m venv W/pt && W/pt/bin/pip install torch torchaudio onnx onnxruntime onnxscript onnxconverter-common einops julius openunmix dora-search pyyaml tqdm` → `git clone --depth 1 https://github.com/sevagh/demucs.onnx W/d` → make the `diffq` import in `W/d/demucs-for-onnx/demucs/states.py` a try/except → `cd W/d && PYTHONPATH=demucs-for-onnx W/pt/bin/python scripts/htdemucs_export.py out 2.6` → `PYTHONPATH=W/d/demucs-for-onnx W/pt/bin/python scripts/htdemucs_post.py out 2.6` (IO dump + f16 + parity) → copy `out/htdemucs_f16.onnx` to assets as `htdemucs_s26_f16.onnx`.

## Blocked — device benchmark
- [ ] **SPIKE** htdemucs device benchmark — still needs an SD 778G-class device; only the S23 (SD 8 Gen 2) is here, which would read optimistically fast. Go/no-go vs the ≤ 25 min budget stays open. S23 datapoint from the smoke (not the acceptance number): 7.8 s segment in ~9 s including session creation, single-thread session config, zero input.

## Blocked — test assets (need real video files)
Gather for M1 QA: 5-min 1080p30 main clip · HDR clip · rotated/portrait clip · MKV+Opus clip · no-audio clip · beach/gym/lingerie gate set · cartoon/illustration set · face set incl. a profile-only track.

## Note — test device ≠ acceptance device
Connected: **Samsung Galaxy S23 (SM-S911U1, SD 8 Gen 2)**. PRD acceptance target is **SD 778G-class**. Use the S23 for functional/visual verification only; final perf/thermal/RAM acceptance must run on a 778G-class device.
