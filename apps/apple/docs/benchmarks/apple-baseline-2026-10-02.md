# Native Apple audio baseline — October 2, 2026

These are measurements on the available M3 Mac with 24 GB unified memory. No
physical iPhone was connected. They establish numerical correctness and Apple
Silicon feasibility, not iPhone latency, memory allowance, battery cost or heat.

The benchmark compiles the production `Demucs`, `STFT`, `MusicGate`, `Ort` and
`Models` sources into a small native macOS command. It uses the bundled
`htdemucs_s26_f32.onnx`, ONNX Runtime 1.24.2, four CPU threads and vocals only.
Inputs are the original YouTube audio decoded once to stereo float32 WAV at
44.1 kHz. Normalization matches `AudioStats` for these clips under 80 s.

Each row ran in its own process, with no competing agent inference/build.
The kernel `ledger_phys_footprint_peak` includes model loading and transient
allocations. It is not the sampled `MemoryFootprint` counter. All reference
runs disabled gating, emitted the exact input frame count and rejected any
non-finite retained-stem samples before encoding could conceal them.

## Observed timings

“Complete audio stage” includes WAV decoding, normalization, model loading,
separation and PCM WAV writing. It excludes video decode/render, AAC encoding
and muxing. Separation includes STFT, inference, overlap-add and PCM writing.
RTF is separation seconds divided by source-audio duration.

| Clip | Configuration | Load s | Separation s | Complete audio stage s | RTF | ORT run p50 ms | Kernel peak MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `-dQJ3djthDc`, 54.213 s | CPU | 0.187 | 9.225 | 9.449 | 0.170 | 367.4 | 1587.5 |
| `-dQJ3djthDc` | Core ML CPU+GPU, first observed compile | 25.926 | 10.571 | 36.516 | 0.195 | 135.6 | 2385.0 |
| `-dQJ3djthDc` | Core ML CPU+GPU, cache reused | 0.263 | 3.727 | 4.011 | 0.069 | 134.9 | 2575.5 |
| `rX6wXhLqOIQ`, 53.293 s | CPU | 0.164 | 8.993 | 9.177 | 0.169 | 373.7 | 1557.0 |
| `rX6wXhLqOIQ` | Core ML CPU+GPU, cache reused | 0.486 | 3.614 | 4.116 | 0.068 | 137.0 | 2559.7 |

Core ML cached separation was about 2.5 times faster than CPU on these inputs.
The first observed Core ML execution also paid a 7.207 s first inference,
in addition to its 25.926 s model load/compile. The production cache was not
deleted: “first observed compile” is not a controlled cold-cache experiment.
Rows are single complete runs with 23–24 inference samples, not a 50-sample,
three-launch phone benchmark or a sustained thermal test.

The Core ML provider reported **19 partitions, with 1452 of 1491 graph nodes
supported**. Requested and resolved configurations were CPU+GPU. This does not
measure how much runtime cost actually ran on GPU. CPU fallback nodes remained;
the benchmark did not measure the Apple Neural Engine for this artifact.

The current graph's substantial footprint remains a reason to screen smaller
separators. A Mac footprint above the project's 1536 MiB phone target neither
establishes nor rules out a phone jetsam failure; actual phone measurement is
still required.

CPU and Core ML vocals matched closely: relative RMS difference was
8.65e-6 (101.26 dB signal-to-error) for `-dQJ3djthDc` and 1.71e-5
(95.34 dB) for `rX6wXhLqOIQ`; maximum sample differences were 4.18e-5 and
5.71e-5 respectively. The first and cached Core ML outputs for the first clip
were numerically identical. These compare execution providers for the same
artifact; they do not measure instrument-removal quality against clean stems.
[`Provider parity results`](results/apple-audio-provider-parity.jsonl) include
the output-file hashes and frame counts.

## Existing gate audit

The optional audit used the production YAMNet preprocessing/scoring and Demucs
dilation rules, with XNNPACK only for YAMNet. It recorded raw scores and every
chunk decision in the results log.

| Clip | Skipped/total chunks | Skipped input-window bounds | CPU separation RTF |
| --- | ---: | --- | ---: |
| `-dQJ3djthDc` | 4/24 | 0–2.10, 1.84–4.44, 4.18–6.78, 6.52–9.12 s | 0.144 |
| `rX6wXhLqOIQ` | 1/23 | 0–2.10 s | 0.160 |

These bounds describe the overlapping chunk input windows, not independent
fully bypassed output intervals. Neighboring separated chunks can affect the
overlap. The timings show the gate saves some work; without annotated
instrument activity they do not establish the gate's recall. Gate-disabled
output remains the quality reference.

Three additional known 0 dB mixtures (English speech, Arabic recitation and
singing, each with an instrumental reference) skipped **zero** chunks: 0/2,
0/3 and 0/3. This small control set did not demonstrate a gate false negative.
Ungated controls also covered singing without accompaniment, instruments without
voices, silence and a partial window; all seven emitted exact frame counts and
finite outputs, independently verified by libsndfile. Quality
against the aligned clean/instrument stems is evaluated in the audio comparison,
not inferred from these contract checks. Raw control runs:
[`results/apple-audio-controls.jsonl`](results/apple-audio-controls.jsonl).

The initial control batch was discarded: one `AVAudioFile.read` returned fewer
frames than the FLOAT-WAV files declared (447 fewer for English, 255 for
recitation and 351 for singing). Matching output to that shortened buffer did
not prove source integrity. The corrected benchmark reads the exact validated
RIFF float32 PCM payload and all controls were rerun. Superseded rows are
explicitly marked invalid in
[`apple-audio-controls-invalid-single-read.jsonl`](results/apple-audio-controls-invalid-single-read.jsonl).
The seven original Shorts rows were separately checked against source frame
counts and were complete, so their timings remain valid.
The narrow raw-PCM reader belongs to the benchmark harness; production imported
media still uses its existing AVFoundation decoder.

## Existing iOS behavior and scope

- `FilterOps.keepStems` and `AudioPipeline.removeMusic` already default to
  vocals only. `vocalsAndOther` intentionally adds a stem that can contain
  instruments; it is not an instrument-free ambience mode.
- All job audio paths converge at `AudioPipeline.removeMusic`. Its shared
  `separate` function opens YAMNet and can bypass chunks. Removing that gate
  would affect every caller; nil scoring already means separate every chunk.
- Core ML CPU+GPU, model-hash caching, provider/session CPU fallback and arena
  disabling already exist. The simulator normalizes Core ML requests to CPU
  and therefore cannot establish accelerator performance.
- Visual analysis detects/tracks faces, votes perceived gender and discards
  spared tracks before writing the EDL. The EDL/render contract has face
  rectangles and full-frame intervals, not person identities, body boxes or
  masks. Benchmarking segmentation alone does not add selective body filtering;
  face-to-person association, person tracking and rendering remain needed.

No production code was changed as part of this baseline.

## Reproduce

Build the Naqi project for macOS once to populate ONNX Runtime SwiftPM bindings,
then compile the standalone benchmark against that DerivedData:

```sh
bash scripts/modelbench/build_apple_audio.sh /path/to/macOS-DerivedData
build.noindex/modelbench-native/apple_audio \
  /path/to/Models /path/to/stereo-44100.wav cpu \
  /path/to/output.wav docs/benchmarks/results/apple-audio-native.jsonl
```

Replace `cpu` with `coreMLGPU` for the accelerated configuration; append `gate`
for the gate audit. Use a separate process per row and at most 120 s per input.
Long-file timing belongs in the bounded production `AudioPipeline`.

Exact input/model hashes, per-chunk samples and output filenames are in
[`results/apple-audio-native.jsonl`](results/apple-audio-native.jsonl).
Media files stay outside Git in `qa-assets/modelbench/audio/apple-demucs`.

Provider-option definitions and graph-partitioning caveats:
[ONNX Runtime Core ML execution provider](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html).
