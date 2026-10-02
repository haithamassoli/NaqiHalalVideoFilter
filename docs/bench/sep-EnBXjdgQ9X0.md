# Audio comparison — EnBXjdgQ9X0, 2026-10-02

**Result:** no measured speed advantage for MDX on this Mac. Both models separated the full
191.460 s clip in about 37 s. MDX used about 18% less peak process memory. This is a listening
comparison without reference stems; equal audio quality has not been established.

Source: [Amr Diab — Maak Alby, official lyric video](https://youtu.be/EnBXjdgQ9X0).
The requested operation keeps speech and singing and removes instruments. No music gate was used.
The owner requested Mac-only testing for this round; no Android or GPU timings were taken.

## Timing

Apple M3, macOS 27.0.1, ONNX Runtime 1.27.0 CPU EP, six intra-op threads, one inter-op thread,
spinning off, memory arena/pattern off. Each pass ran in a fresh process with no explicit warmup.
Timing includes normalization, STFT/ISTFT, inference and overlap-add. It excludes source decoding,
session creation, writing WAV files and encoding listening copies.

| Model | Full-track runs (s) | Median (s) | ms/audio-s | Median peak RSS (MiB) |
|---|---|---:|---:|---:|
| Shipped htdemucs, 2.6 s, f16 | 35.29 / 38.54 | 36.92 | 192.81 | 1,468 |
| UVR_MDXNET_3_9662, fp32, 25% overlap, denoise off | 39.18 / 35.71 | 37.45 | 195.58 | 1,205 |

The four recorded passes used ABBA order. One additional MDX pass completed during a repeatability
check but its timing was not retained. The median difference is 1.4%, while the within-model
run spread is 8.8% / 9.3%. With only two recorded runs per model, this does not resolve a speed
difference. Mac ratios do not predict Android ratios, as the earlier device measurements showed.

## Listening files and validation

Full-track copies: [original](../../qa-assets/bench-out/EnBXjdgQ9X0/original.m4a),
[Demucs](../../qa-assets/bench-out/EnBXjdgQ9X0/demucs.m4a),
[MDX](../../qa-assets/bench-out/EnBXjdgQ9X0/mdx.m4a).

Matched 30 s samples, **00:45–01:15**: [original](../../qa-assets/bench-out/EnBXjdgQ9X0/original_30s.m4a),
[Demucs](../../qa-assets/bench-out/EnBXjdgQ9X0/demucs_30s.m4a),
[MDX](../../qa-assets/bench-out/EnBXjdgQ9X0/mdx_30s.m4a).

- Lossless float WAVs retain the separator output. All have 8,443,393 frames at 44.1 kHz, stereo,
  with zero NaN/Inf samples.
- Listening copies use AAC 256 kbps and the **same 0.877538832 gain** on all three versions.
  The decoded original peaks above 1.0; the shared attenuation leaves headroom. Outputs were not
  normalized individually. All six listening files decoded successfully.
- Repeated PCM samples matched exactly for both models (maximum absolute difference 0).
  Whole-file hashes differed because libsndfile's float WAV `PEAK` chunk stores a timestamp.
  An initial byte-equality assertion was replaced by comparison of the actual audio samples.
- The song has no available reference vocals/instrumentals. No SI-SDR/SIR or automatic music
  score is used to claim quality: singing must be retained, and generic music scores penalize it.
  Listen for remaining instruments, damaged consonants, missing backing vocals and chunk seams.

Raw measurements, commands, source checksum and playback parameters:
[`results.json`](../../qa-assets/bench-out/EnBXjdgQ9X0/results.json).
Audio and raw logs stay under gitignored `qa-assets/`.

## Reproduce

```sh
yt-dlp --no-playlist -f bestaudio --write-info-json -o 'qa-assets/bench-out/EnBXjdgQ9X0/source.%(ext)s' 'https://youtu.be/EnBXjdgQ9X0'
ffmpeg -nostdin -y -i qa-assets/bench-out/EnBXjdgQ9X0/source.webm -map 0:a:0 -ar 44100 -ac 2 -c:a pcm_f32le qa-assets/bench-out/EnBXjdgQ9X0/original.wav

for bench_arm in sep0 sep1 sep1 sep0; do
  .venv-bench/bin/python scripts/bench/score_sep.py cost-worker \
    --arm "$bench_arm" --wav qa-assets/bench-out/EnBXjdgQ9X0/original.wav \
    --runs 1 --warmup 0 --output "qa-assets/bench-out/EnBXjdgQ9X0/${bench_arm}_vocals.wav"
done
```

The worker asserts unchanged audio length/channels and finite output, and rejects invalid run
counts or overwriting the source. Existing benchmark commands still work without `--output`.
