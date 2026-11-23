# iOS model comparison

Start with [the decision](../../docs/benchmarks/decision-ios.md), then the
[audio report](../../docs/benchmarks/audio-ios-comparison.md) and
[vision report](../../docs/benchmarks/vision-ios-comparison.md). These contain
the measured settings, exact model revisions, rejected candidates and limits.
All timing evidence is from an M3 Mac. No physical iPhone was available.

Run from the repository root. Media/models live under ignored `qa-assets/`;
Python environments and compiled tools live under ignored `build.noindex/`.
Use new results filenames for reruns so historical evidence is preserved.

## Supplied videos

Requires `yt-dlp` and `ffmpeg` on PATH. Originals were AV1/Opus MP4; the optional
H.264/AAC derivative is for Apple media API compatibility, not model scoring.
YouTube may deliver different bytes later: check against `manifest.json` or
record the new representation/hash rather than claiming identical input.

```sh
mkdir -p qa-assets/modelbench
yt-dlp --no-playlist --write-info-json --merge-output-format mp4 \
  -f 'bv*[height<=1080]+ba/b[height<=1080]' \
  -o 'qa-assets/modelbench/%(id)s.%(ext)s' \
  'https://youtube.com/shorts/-dQJ3djthDc' \
  'https://youtube.com/shorts/rX6wXhLqOIQ'
for clip in -dQJ3djthDc rX6wXhLqOIQ; do
  ffmpeg -v error -i "qa-assets/modelbench/$clip.mp4" -vn \
    -ar 44100 -ac 2 -c:a pcm_f32le "qa-assets/modelbench/$clip.wav"
  ffmpeg -v error -i "qa-assets/modelbench/$clip.mp4" \
    -c:v h264_videotoolbox -b:v 4000k -pix_fmt yuv420p \
    -c:a aac -b:a 192k -movflags +faststart "qa-assets/modelbench/$clip.ios.mp4"
done
```

## Audio screening

Use the audio report's pinned checkout and corpus setup commands. Its
`audio-models.json` lists checkpoint paths/URLs/hashes; the locally staged
HTDemucs artifact must match the baseline hash, not just its filename.

The screening environment uses Python 3.11/Torch 2.14.1. The final stock SCNet
conversion probe uses Python 3.12/Torch 2.7.0/NumPy 2.2.6/coremltools 9.0;
keep these environments separate. Both conversion failures are recorded.

```sh
uv venv --python 3.11 build.noindex/audio-modelbench/.venv
uv pip install --python build.noindex/audio-modelbench/.venv/bin/python \
  -r scripts/modelbench/audio-requirements.txt
build.noindex/audio-modelbench/.venv/bin/python scripts/modelbench/audio_compare.py --self-check
```

For the Core ML package, preserve the directory structure from the pinned
`gyoom/UVR-MDX-CoreML` revision. Three files are needed; their hashes are in
`docs/benchmarks/results/apple-coreml-vocft-artifact.json`:

```sh
mkdir -p qa-assets/modelbench/audio/UVR-MDX-CoreML/UVR-MDX-NET-Voc_FT.mlpackage/Data/com.apple.CoreML/weights
for file in Manifest.json Data/com.apple.CoreML/model.mlmodel Data/com.apple.CoreML/weights/weight.bin; do
  curl --fail --location \
    "https://huggingface.co/gyoom/UVR-MDX-CoreML/resolve/aa27ab16896fdb298cdb73cceb76c950e7580c00/UVR-MDX-NET-Voc_FT.mlpackage/$file" \
    --output "qa-assets/modelbench/audio/UVR-MDX-CoreML/UVR-MDX-NET-Voc_FT.mlpackage/$file"
done
```

The learned package does **not** include STFT/ISTFT. The correct MDX FFT is 7680,
not the 6144 described in its community card. The native runner and Python
adapter implement the verified checkpoint configuration independently.

## Native Apple audio

For HTDemucs, build Naqi for macOS once to obtain its ORT 1.24.2 bindings, then
use `build_apple_audio.sh` as documented in the baseline report. No runtime
upgrade or production separator replacement is necessary.

```sh
mkdir -p build.noindex/modelbench-native
xcrun swiftc -O -parse-as-library -swift-version 6 \
  -target arm64-apple-macos15.0 scripts/modelbench/apple_mdx_audio.swift \
  scripts/modelbench/apple_audio_io.swift \
  -o build.noindex/modelbench-native/apple_mdx_audio
build.noindex/modelbench-native/apple_mdx_audio --self-check
build.noindex/modelbench-native/apple_mdx_audio \
  qa-assets/modelbench/audio/UVR-MDX-CoreML/UVR-MDX-NET-Voc_FT.mlpackage \
  qa-assets/modelbench/-dQJ3djthDc.wav all \
  qa-assets/modelbench/audio/mdx-rerun.wav \
  docs/benchmarks/results/mdx-rerun.jsonl

xcrun swiftc -O -parse-as-library -swift-version 6 \
  -target arm64-apple-macos15.0 scripts/modelbench/apple_voice_isolation.swift \
  scripts/modelbench/apple_audio_io.swift \
  -o build.noindex/modelbench-native/apple_voice_isolation
build.noindex/modelbench-native/apple_voice_isolation --self-check
build.noindex/modelbench-native/apple_voice_isolation \
  qa-assets/modelbench/-dQJ3djthDc.wav \
  qa-assets/modelbench/audio/voice-isolation-rerun.wav
```

These WAV runners accept complete, little-endian, stereo float32 RIFF WAV at
44.1 kHz, at most 120 seconds. The shared reader validates the declared payload;
it is a benchmark frontend, not the application's compressed-media decoder.
The native MDX runner retains the short clip in memory; it is not a long-file
streaming implementation. Model-only compute-unit/plan commands are documented
in `apple-baseline-vocft-2026-10-02.md`.

## Vision

The vision report provides exact downloads, frame extraction, both native
Vision runs, YOLO conversion/compute choices and MiVOLO setup. Its environment
uses Python 3.12/Torch 2.7.0, independently of audio screening.

```sh
uv venv --python 3.12 build.noindex/vision-modelbench
uv pip install --python build.noindex/vision-modelbench/bin/python \
  -r scripts/modelbench/vision-requirements.txt
xcrun swiftc -O -parse-as-library scripts/modelbench/vision_native.swift \
  -o build.noindex/vision-modelbench/vision_native
build.noindex/vision-modelbench/vision_native --self-check
build.noindex/vision-modelbench/bin/python scripts/modelbench/vision_yolo.py --self-check
build.noindex/vision-modelbench/bin/python scripts/modelbench/vision_gender.py --self-check
```

The native Vision comparison includes OS27-only revisions, so that runner needs
an OS27 host. The audio API runners typecheck for iOS18. SDK typechecking is not
device execution or a phone benchmark. YOLO timings use a Python host of native
Core ML; their scope differs from Swift Vision request timings.

Raw JSON/JSONL, artifact hashes and sparse annotations are committed. Audio,
frames, masks, models, binaries and visual review sheets remain outside Git.
The branch implements comparison tools; it does not integrate selective body
tracking/rendering into the app.
