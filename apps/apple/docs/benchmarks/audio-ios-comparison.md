# iOS instrument-removal comparison — 2026-10-02

**Decision: retain the current FP32 HTDemucs as the iOS quality default, with
vocals-only output and the music gate disabled for the reference comparison.**
It best preserved the unaccompanied singing control and suppressed the
instrument-only control most strongly. Native Core ML MDX Voc_FT is a useful
speed/memory prototype, but its much larger instrumental residual prevents a
blanket replacement. SCNet Small is the strongest next conversion candidate;
its stock Core ML export did not succeed.

This is a measured M3 screening decision, not an iPhone performance claim.
Physical iPhones were unavailable, as confirmed by the user. No production
separator, model download, or export policy was changed by this comparison.

## Exact inputs and models

Both requested Shorts were processed in full from the shared stereo 44.1 kHz
float32 WAVs: `-dQJ3djthDc.wav` (54.213016 s) and `rX6wXhLqOIQ.wav`
(53.293016 s). Their sources and hashes are in
[`manifest.json`](../../scripts/modelbench/manifest.json).

The controls contain clean English speech (3.4 s), Arabic recitation (6.031111 s),
and singing (5 s), each mixed with aligned instrumental stems at 0 dB RMS ratio.
There are also unaccompanied singing, instrumental-only, exact silence and a
137-frame partial-input case. The instrumental reference combines the PyTorch
tutorial's original drums, bass and other stems; it is not a separator-generated
pseudo-reference. The clean Arabic source is one EveryAyah Al-Fatiha excerpt,
not a representative recitation corpus. Provenance, sample counts and hashes:
[`audio-manifest.json`](../../scripts/modelbench/audio-manifest.json).

| Candidate actually tested | Artifact | Execution |
| --- | --- | --- |
| Existing HTDemucs | Existing `htdemucs_s26_f32.onnx`, approximately 173 MB | Swift production separator/DSP; ORT 1.24.2 CPU and Core ML CPU+GPU |
| SCNet Small | Official 42,434,545-byte checkpoint; dims `[4,32,64,128]` | PyTorch 2.14.1 CPU/MPS; 20 s windows, 50% triangular overlap, random shifts disabled |
| Kim Vocal 2 | Pinned 66,759,214-byte ONNX checkpoint | ORT 1.30 CPU; approximately 5.92 s windows, 25% Hann overlap; denoise disabled |
| MDX Voc_FT | Pinned 66,762,490-byte ONNX and approximately 33 MB fp16 Core ML package | ONNX CPU; Core ML ALL through Python and a complete Swift/Accelerate adapter |
| Mel-Band RoFormer | Author's original 913,106,900-byte Kim checkpoint | PyTorch CPU/MPS; 8 s windows, two overlaps, 10% fades |
| Apple HighQualityVoice | Public `AUSoundIsolation` audio unit; no bundled model | Native Swift offline rendering; declared 5,847-frame latency trimmed |

This SCNet is the original official small checkpoint, not a differently trained
masked-SCNet checkpoint. This RoFormer is the original author checkpoint, not
the separate smaller unwa variant or a community Core AI conversion.

Exact hashes, source revisions, configuration and declared licenses are in
[`audio-models.json`](../../scripts/modelbench/audio-models.json).

## Quality on the aligned reference controls

SI-SDR measures vocal reconstruction; higher is better. The instrumental-only
column is `20 log10(output RMS / input RMS)`; a more negative result means less
residual energy. It measures one known instrumental bed, not universal removal.
Clean-singing SI-SDR checks the requirement to minimally change wanted voices
when no instrument is present.

| Model/runtime | English mix SI-SDR | Recitation mix SI-SDR | Singing mix SI-SDR | Clean singing SI-SDR | Instrument-only output/input RMS |
| --- | ---: | ---: | ---: | ---: | ---: |
| HTDemucs native CPU | 15.00 dB | 14.03 dB | 14.01 dB | **38.08 dB** | **−58.98 dB** |
| SCNet MPS | 15.54 dB | 15.17 dB | 14.66 dB | 28.66 dB | −47.85 dB |
| Kim2 ONNX CPU | 14.97 dB | 15.15 dB | 13.76 dB | 34.85 dB | −19.75 dB |
| Voc_FT native Swift Core ML ALL | 14.95 dB | 15.13 dB | 13.75 dB | 34.34 dB | −19.44 dB |
| Mel-Band RoFormer MPS | **17.02 dB** | **16.01 dB** | **14.98 dB** | 34.57 dB | −43.36 dB |
| Apple HighQualityVoice | 8.28 dB | 5.60 dB | 6.51 dB | 11.30 dB | −43.42 dB |

RoFormer reconstructed the three mixed vocals best, but it did not beat the
baseline's clean-singing preservation or instrument-only suppression. SCNet
improved the three mixture scores with much smaller weights, but changed the
clean vocal more and has no validated iOS artifact. Voc_FT's instrumental-only
output RMS was 0.010664 versus HTDemucs' 0.0001125: approximately 40 dB more
residual energy from the same 0.1-RMS input. Its speed does not erase this result.

The audio unit reduced wanted recitation and singing in the mixtures by
approximately 2.7 dB and 2.3 dB projected vocal gain. Its clean singing gain was
−1.33 dB. Clean-output cross-correlation peaked at zero lag after the declared
latency correction, so this poorer reconstruction was not a simple timing
offset. It is rejected for this all-human-vocals requirement.

All final native and Python outputs were finite and retained the exact input
sample count. With denoise disabled, MDX produced a low signal from exact
silence: RMS approximately `2.1e-4` (−73.5 dBFS). HTDemucs, SCNet, RoFormer and the
audio unit returned exact silence for the silence control. This noise floor is
reported rather than replaced with zeros.

Reference metrics, gain-aware distortion and source hashes:
[`audio-native-quality.jsonl`](results/audio-native-quality.jsonl) and
[`audio-python.jsonl`](results/audio-python.jsonl). These controls do not measure
Arabic WER, chanting/humming, overlapping voices, a listening panel, or arbitrary
musical genres. The Shorts have no isolated ground-truth stems; their files are
listening/review outputs, not SDR measurements.

## M3 costs and hardware use

Heavy runs were serialized on an Apple M3 MacBook Air, 24 GB unified memory,
macOS 27.0.1. Python screening uses fresh processes, four CPU threads and no
discarded warmups. Metal shader caches were not cleared. Initial SCNet MPS
20-second-window inference took 3.73 s; later comparable windows took around
1.05 s. This distinction matters for short inputs.

| Execution on the two full Shorts | First Short | Second Short | What the timer includes |
| --- | ---: | ---: | --- |
| HTDemucs native CPU | RTF 0.174 | RTF 0.172 | Complete native audio stage |
| HTDemucs native Core ML CPU+GPU, cached | RTF 0.074 | RTF 0.077 | Complete native audio stage; first observed compile was slower |
| Voc_FT native Core ML ALL | RTF 0.057 | RTF 0.058 | Complete native audio stage, including approximately 1.16 s compile/load |
| Voc_FT native Core ML ALL | RTF 0.035 | RTF 0.036 | Separator/DSP only, approximately 1.92/1.90 s |
| SCNet PyTorch MPS | RTF 0.096 | RTF 0.097 | Python separator only, approximately 5.18/5.18 s |
| RoFormer PyTorch MPS | RTF 1.398 | RTF 1.333 | Python separator only, approximately 75.81/71.03 s |
| Kim2 ONNX CPU | RTF 0.625 | RTF 0.675 | Python separator only |
| Voc_FT ONNX CPU | RTF 0.710 | RTF 0.717 | Python separator only |

These are audio-stage timers, not complete exported video jobs. Python separator
timers exclude model load and WAV writing. Native full-stage timers include
read/load/separation/write, but exclude video rendering and AAC/mux work. The
approximately 1.16 s native MDX load is much larger than one inference and must
not be omitted from short-file cost claims.

Native Voc_FT peaked at approximately 249 MB kernel physical footprint on the
Shorts, versus approximately 2.50–2.70 GB for the native HTDemucs GPU runs. The
native Core ML compute plan preferred the Neural Engine for the learned Voc_FT
core. That is plan evidence, not an Instruments/power trace proving every
runtime operation used the Neural Engine; host FFT and overlap-add run on CPU.

Python SCNet CPU peaked at 4.74 GB RSS for a padded 20-second window. Its MPS
process RSS was below 0.8 GB, but sampled Metal driver allocation was 6.68 GB.
RoFormer MPS sampled driver allocation was 3.29 GB and process RSS around
2.20 GB. Driver samples are not an instantaneous peak; they cannot be added to
RSS because unified-memory pages may overlap. Small weights do not establish a
small phone working set.

Native timings/footprints:
[`apple-mdx-native.jsonl`](results/apple-mdx-native.jsonl),
[`apple-audio-native.jsonl`](results/apple-audio-native.jsonl) and the
[`Apple baseline report`](apple-baseline-2026-10-02.md). No thermal endurance,
phone energy, older-phone memory ceiling or concurrent audio/video test was run.

## Conversion and parity findings

The community Voc_FT Core ML model card says `n_fft=6144`, apparently assuming
`dim_f=n_fft/2`. The official UVR metadata for the exact ONNX hash instead gives
**`n_fft=7680`, `dim_f=3072`, hop 1024, 256 frames, compensation 1.021**. Kim2 also
uses 7680/3072 but compensation 1.009. The host inverse pads to 3,841 frequency
bins. Both the Python and native Swift adapters use the verified configuration;
the learned Core ML core can be correct even when its card's DSP instructions
are incorrect. The app's existing radix-2-only Demucs STFT cannot simply be
reused with the MDX FFT size.

- SCNet and RoFormer CPU/MPS waveform parity on the 5-second singing mixture
  was approximately 121 dB SNR; this is one reference case, not a full-corpus
  conversion claim.
- Voc_FT ONNX FP32 versus Core ML fp16 ALL with the same Python host DSP had
  complete waveform SNR of 61–65 dB
  on both Shorts and vocal reference controls; mixture SI-SDR changed by no
  more than 0.0012 dB.
- Swift Accelerate/Core ML versus Python Torch-DSP/Core ML waveform SNR was
  65.58/68.10 dB on the two Shorts, and approximately 68 dB on vocal controls.
  The instrumental-only result was 53.40 dB. Exact silence has a very small
  model-generated output, so its relative-error SNR is a less useful diagnostic
  than absolute error; all raw errors are retained.
- Stock SCNet conversion attempt 1 (Torch 2.14.1 / NumPy 2.4.6 / coremltools 9)
  failed with `TypeError: only 0-dimensional arrays can be converted to Python scalars`.
- The final supported-toolchain attempt (Torch 2.7.0 / NumPy 2.2.6 / coremltools 9)
  failed with `NotImplementedError: PyTorch convert function for op 'view_as_complex' not implemented.`
  No custom FFT converter or architecture rewrite was attempted. SCNet is not
  claimed to be iOS-ready.

An initial native WAV decoding path dropped small tail sample counts on the
controls. That confounded the strict reference comparison. Final native control
runs use the exact shared RIFF float32 samples and assert original sample counts;
the quality table above uses these corrected runs.

Parity and unsuccessful exports:
[`audio-coreml-waveform-parity.jsonl`](results/audio-coreml-waveform-parity.jsonl),
[`audio-swift-waveform-parity.jsonl`](results/audio-swift-waveform-parity.jsonl),
[`audio-mps-parity.jsonl`](results/audio-mps-parity.jsonl), and
[`audio-export.jsonl`](results/audio-export.jsonl).

## Reproduction and review files

From the repository root, create the isolated screening environment and fetch
the exact source checkouts. Use `audio-models.json` for checkpoint URLs/hashes;
the original application already supplies the HTDemucs artifact.

```sh
uv venv --python 3.11 build.noindex/audio-modelbench/.venv
uv pip install --python build.noindex/audio-modelbench/.venv/bin/python -r scripts/modelbench/audio-requirements.txt
git clone https://github.com/starrytong/SCNet.git build.noindex/audio-modelbench/SCNet
git -C build.noindex/audio-modelbench/SCNet checkout 5d95bf96b19c3eede63248d171efeca8e3abb948
git clone https://github.com/KimberleyJensen/Mel-Band-Roformer-Vocal-Model.git build.noindex/audio-modelbench/Mel-Band-Roformer
git -C build.noindex/audio-modelbench/Mel-Band-Roformer checkout 25f44ffb55ee3c301281bba21b2d6d311cb69ae2
build.noindex/audio-modelbench/.venv/bin/python scripts/modelbench/audio_corpus.py --assets "$PWD/qa-assets/modelbench/audio" --manifest scripts/modelbench/audio-manifest.json
build.noindex/audio-modelbench/.venv/bin/python scripts/modelbench/audio_compare.py --self-check
```

Place weights under `qa-assets/modelbench/audio` at the relative `file` paths in
`audio-models.json`. Preserve the Core ML package directory structure from
`gyoom/UVR-MDX-CoreML` revision `aa27ab16896fdb298cdb73cceb76c950e7580c00` under
`qa-assets/modelbench/audio/UVR-MDX-CoreML`; fetch official UVR metadata as
`qa-assets/modelbench/audio/mdx_model_data.json`. Its measured SHA256 is
`1aca8f9bcc57233bc714029663a9ec2345d9c7721f91e5e08f46392a879c6a9a`.

One reproducible reference run:

```sh
build.noindex/audio-modelbench/.venv/bin/python scripts/modelbench/audio_compare.py \
  --candidate vocft --provider coreml-all \
  --assets "$PWD/qa-assets/modelbench/audio" \
  --sources "$PWD/build.noindex/audio-modelbench" \
  --input "$PWD/qa-assets/modelbench/audio/controls/singing-mix0db.wav" \
  --reference "$PWD/qa-assets/modelbench/audio/controls/singing-clean.wav" \
  --instrumental "$PWD/qa-assets/modelbench/audio/controls/singing-instrumental.wav" \
  --output "$PWD/qa-assets/modelbench/audio/outputs/vocft-coreml-all-singing-mix0db.wav" \
  --results docs/benchmarks/results/audio-python.jsonl
```

Other measured choices are `kim2/cpu`, `vocft/cpu`, `scnet/mps` and
`roformer/mps`; CPU parity choices are `scnet/cpu` and `roformer/cpu`. For a
Short, substitute its original WAV and omit reference/instrumental arguments.
The spectrum-export option performs an extra reference call and is explicitly
excluded from separator RTF comparisons. Its `.input.f32.bin` is fp16-quantized
then recast to float32; the `.reference.f32.bin` uses exactly those same inputs.

[`audio_score.py`](../../scripts/modelbench/audio_score.py) scores an existing
output with the same metrics. [`audio_export_scnet.py`](../../scripts/modelbench/audio_export_scnet.py)
reproduces a stock export attempt. For its supported-toolchain probe, use an
isolated Python 3.12 environment with Torch 2.7.0, NumPy 2.2.6, coremltools 9.0
and PyYAML 6.0.3. No soundfile or vision dependency is needed by that exporter.
The native runners and build commands are documented in the Apple baseline
report and repository benchmark README.

All local listening artifacts remain outside Git:

- Current-model Shorts: `qa-assets/modelbench/audio/apple-demucs/<id>-cpu.wav`
  and `<id>-coreMLGPU.wav`.
- Native MDX Shorts: `qa-assets/modelbench/audio/apple-mdx/<id>-all.wav`.
- Python comparisons: `qa-assets/modelbench/audio/outputs/<candidate>-<provider>-<id>.wav`.
- Native audio unit: `qa-assets/modelbench/audio/apple-voice-isolation/<id>-voice-isolation.wav`.
- Reference/control outputs use the same folders and control name prefixes.

## Primary sources

The [official SCNet implementation](https://github.com/starrytong/SCNet) defines
its own normalization/STFT and checkpoint; the [SCNet paper](https://arxiv.org/abs/2401.13276)
is architectural screening evidence, not phone timing.
[Official UVR metadata](https://github.com/TRvlvr/application_data/blob/main/mdx_model_data/model_data_new.json)
defines the pinned MDX preprocessing. The
[RoFormer checkpoint author's code](https://github.com/KimberleyJensen/Mel-Band-Roformer-Vocal-Model)
defines its window/overlap reconstruction. The
[PyTorch tutorial](https://docs.pytorch.org/audio/main/tutorials/hybrid_demucs_tutorial.html)
supplies the isolated reference stems. The
[Core ML provider documentation](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html)
explains requested compute units and profiling; provider enablement alone is
not proof of accelerator placement or a complete-job speedup.
