# Voc_FT native Core ML feasibility — October 2, 2026

The community `UVR-MDX-NET-Voc_FT.mlpackage` ran successfully on the M3 Mac
in CPU, CPU+GPU, CPU+Neural Engine and ALL configurations. These are native
macOS **learned-model-only** measurements, not iPhone measurements or complete
source-separation times. No iPhone was connected.

The package is from the [`gyoom/UVR-MDX-CoreML` repository](https://huggingface.co/gyoom/UVR-MDX-CoreML/tree/aa27ab16896fdb298cdb73cceb76c950e7580c00) at commit
`aa27ab16896fdb298cdb73cceb76c950e7580c00`. The reference is the original
Voc_FT ONNX artifact identified in the audio candidate manifest. A real singing
mixture generated the shared `[1,4,3072,256]` input; it was quantized to fp16
then stored as float32. Both runtimes saw exactly the same quantized values.

Important conversion detail: the correct Voc_FT frontend uses **FFT 7680,
hop 1024 and 3072 retained bins**, not FFT 6144. The community model-card FFT
description would produce the wrong frequency mapping. Audio frontend parity
and learned-core parity are separate checks. This benchmark compares the
learned cores; the Python screening uses the corrected frontend.

## Configuration results

Each configuration ran in a fresh process, one first prediction and five
resident predictions. Prediction times include waiting for the output array.
Memory is the process kernel physical-footprint high-water mark measured after
prediction and output extraction, before compute-plan inspection. System
compiler/service process memory is not included.

| Configuration | Compile ms | Load ms | First prediction ms | Resident p50 ms | Kernel peak MiB | Core ML preferred device for costed ops |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| CPU | 60.4 | 103.0 | 468.5 | 451.9 | 335.7 | CPU |
| CPU+GPU | 59.4 | 149.3 | 1569.9 | 270.1 | 738.4 | GPU |
| CPU+Neural Engine | 57.9 | 1119.9 | 87.3 | 79.7 | 82.4 | ANE |
| ALL | 60.7 | 1134.2 | 90.3 | 79.3 | 85.0 | ANE |

`MLComputePlan` preferred the named device for 173 program operations and 100%
of the reported relative estimated operation cost in each row. Another 498
operations had no reported preferred device/cost. This is Apple's compute-plan
preference, not measured runtime rail activity, operation execution traces or
energy. The JSON deliberately leaves `placement_verified` false.

ALL and CPU+Neural Engine were equivalent on this M3 input and materially
better than CPU+GPU for model latency and process footprint. That supports
testing the native package on supported iPhones; it does not substitute for
complete-file Core ML output, phone thermal tests, or app export measurements.

## Numerical parity with original ONNX

| Configuration | Relative RMS spectral error | Signal-to-error dB | Maximum absolute spectral difference |
| --- | ---: | ---: | ---: |
| CPU | 1.389% | 37.14 | 5.176 |
| CPU+GPU | 0.112% | 59.00 | 0.436 |
| CPU+Neural Engine | 0.133% | 57.53 | 0.625 |
| ALL | 0.133% | 57.53 | 0.625 |

Outputs were finite, shape-correct and converted according to their actual
multi-array strides. ALL and CPU+Neural Engine produced identical results.
The CPU configuration had noticeably larger numerical error; it must be
validated independently if chosen as a fallback. Spectral differences are not
normalized audio sample differences and do not measure vocal damage directly.
This single input is a feasibility check, not a complete export-parity corpus.

## Reproduce

```sh
xcrun swiftc -O -parse-as-library -swift-version 6 \
  -target arm64-apple-macos15.0 scripts/modelbench/apple_coreml_audio.swift \
  -o build.noindex/modelbench-native/apple_coreml_audio
build.noindex/modelbench-native/apple_coreml_audio \
  /path/to/UVR-MDX-NET-Voc_FT.mlpackage /path/to/vocft-parity.input.f32.bin \
  ane /path/to/prediction.f32.bin docs/benchmarks/results/apple-coreml-vocft.jsonl
```

Use `cpu`, `gpu`, `ane` or `all`, in separate processes. Input/reference binaries
and frontend metadata live outside Git in `qa-assets/modelbench/audio`. Raw
results: [`timings and plans`](results/apple-coreml-vocft.jsonl),
[`ONNX parity`](results/apple-coreml-vocft-parity.jsonl),
[`package files and SHA-256 hashes`](results/apple-coreml-vocft-artifact.json).

Compute-plan API:
[Apple MLComputePlan](https://developer.apple.com/documentation/coreml/mlcomputeplan).
