# Native Voc_FT complete WAV stage — October 2, 2026

The native benchmark now runs the entire Voc_FT audio frontend and reconstruction
in Swift/Accelerate, with native Core ML ALL inference. Measurements are on the
M3 Mac, not an iPhone. They include PCM WAV reading, model compile/load,
separation and PCM WAV writing; they exclude compressed source-media decoding,
video rendering, AAC encoding and muxing. No production separator was replaced.

## DSP and source-integrity checks

`apple_mdx_audio.swift` matches the reference Python MDX adapter: FFT 7680,
hop 1024, periodic Hann STFT/ISTFT, reflected center padding, `[1,4,3072,256]`
real/imag packing, zero first three bins, inverse Hermitian spectrum and `1/N`
scale, Hann-squared ISTFT normalization, chunk 261120, outer stride 195840,
outer symmetric Hann overlap-add, exact 3840-sample trim and compensation 1.021.
The native Accelerate DFT supports this mixed-radix length; no substitute FFT
size or custom FFT algorithm is used.

The runnable `--self-check` checks DFT inversion, STFT reconstruction, nonzero
outer overlap-add, silence and short/final windows. Maximum observed sample
errors were 2.98e-7, 3.73e-8 and 4.62e-7 for the nonzero checks. The shared
PCM reader's check covers a complete float32 WAV and rejection of a truncated
payload. It bounds chunks by the RIFF length, validates the full IEEE-float
extensible GUID and rejects duplicate format/data chunks.
This reader is only for staged stereo float32 44.1 kHz benchmark WAVs. The
production application's AVFoundation media-decoding pipeline was not modified.

Before timing the batch, a real singing mixture established:

- Native raw spectrum versus CPU Torch STFT: **135.11 dB** signal-to-error,
  relative RMS difference 1.76e-7.
- Native complete WAV versus Python DSP using the same Core ML ALL model:
  **67.92 dB** signal-to-error, relative RMS difference 0.0402%.
- Native/Python/source lengths all exactly **220500 frames**.

The spectrum-export run is flagged and excluded from the timing table. All
nine timed native outputs and ten corrected HTDemucs control outputs were
independently read with libsndfile: sample rates, frame counts, channel counts
and finite samples matched their inputs.

## Native stage measurements

Each row ran in its own process. Separation excludes model loading and output
WAV writing. Complete stage includes them. Peak memory is the kernel process
physical-footprint high-water mark, including this benchmark's later output
integrity-read allocation; it is not an iPhone memory forecast. Model/service
process memory outside the benchmark process is not included.

| Input | Duration s | Compile/load s | Separation s | Complete WAV stage s | Separation RTF | Kernel peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `-dQJ3djthDc` | 54.213 | 1.163 | 1.920 | 3.105 | 0.035 | 237.6 |
| `rX6wXhLqOIQ` | 53.293 | 1.159 | 1.904 | 3.083 | 0.036 | 236.4 |
| English + instruments, 0 dB | 3.400 | 1.158 | 0.309 | 1.473 | 0.091 | 85.5 |
| Recitation + instruments, 0 dB | 6.031 | 1.169 | 0.452 | 1.628 | 0.075 | 97.0 |
| Singing + instruments, 0 dB | 5.000 | 1.163 | 0.303 | 1.472 | 0.061 | 86.2 |
| Singing without instruments | 5.000 | 1.157 | 0.305 | 1.468 | 0.061 | 86.2 |
| Instruments without voices | 5.000 | 1.154 | 0.305 | 1.466 | 0.061 | 86.3 |

Silence and a 137-frame partial window also passed integrity checks. Their
RTFs reflect fixed-window startup work and are not long-file throughput estimates.
No cache was cleared, no sustained thermal run was performed, and these are
single short-file runs rather than a phone performance distribution.

## Quality limits affect the choice

The native prototype is faster and uses substantially less process memory
than the existing HTDemucs GPU baseline on the two Shorts. Its quality does
not justify replacing the existing separator solely on that evidence:

| Control | Existing HTDemucs CPU | Native Voc_FT ALL |
| --- | ---: | ---: |
| Instrument-only input RMS 0.1: output RMS | 0.00011249 | 0.01066407 |
| Relative instrument attenuation | about −59.0 dB | about −19.4 dB |
| Silent input: output RMS | 0 | 0.00021116 |

Voc_FT left about 40 dB more energy on this instrument-only control. This is
one supplied-reference segment, not a universal leakage rate, but it is a
material failure case for the user's instrument-removal priority. Vocal
preservation on the aligned speech/recitation/singing controls must also enter
the final model decision. Native acceleration and complete-DSP parity are
demonstrated; a universal quality advantage is not.

## Reproduce

```sh
xcrun swiftc -O -parse-as-library -swift-version 6 \
  -target arm64-apple-macos15.0 scripts/modelbench/apple_mdx_audio.swift \
  scripts/modelbench/apple_audio_io.swift \
  -o build.noindex/modelbench-native/apple_mdx_audio
build.noindex/modelbench-native/apple_mdx_audio --self-check
build.noindex/modelbench-native/apple_mdx_audio \
  /path/to/UVR-MDX-NET-Voc_FT.mlpackage /path/to/stereo-float32-44100.wav \
  all /path/to/output.wav docs/benchmarks/results/apple-mdx-native.jsonl
```

Raw evidence: [`native stages`](results/apple-mdx-native.jsonl),
[`DSP/WAV parity`](results/apple-mdx-dsp-parity.json),
[`external output integrity`](results/apple-native-wave-integrity.jsonl).
Model provenance and model-only Core ML compute plans are in
[`apple-baseline-vocft-2026-10-02.md`](apple-baseline-vocft-2026-10-02.md).
Media/binaries remain outside Git in `qa-assets/modelbench/audio/apple-mdx`.
