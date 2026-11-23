#!/usr/bin/env bash
# Stage the ONNX models into naqi/Resources/Models (gitignored, as on Android).
# Source of truth is the Android repo's assets; regeneration pipelines live in
# the Android docs/m0-spikes.md "Regen pipelines" section.
set -euo pipefail

ANDROID="${NAQI_ANDROID_REPO:-$HOME/AndroidStudioProjects/NaqiHalalVideoFilter}"
SRC="$ANDROID/app/src/main/assets/models"
DST="$(cd "$(dirname "$0")/.." && pwd)/naqi/Resources/Models"

# Source artifacts. The Apple build step converts/fixes them below, then keeps
# only the four shipping graphs in the bundle.
WANT=(htdemucs_s26_f16.onnx nsfw_mnv2_140_f32.onnx genderage.onnx yamnet.onnx)

[ -d "$SRC" ] || { echo "error: Android assets not found at $SRC" >&2
                   echo "       set NAQI_ANDROID_REPO to the checkout root" >&2; exit 1; }
for m in "${WANT[@]}"; do
  [ -f "$SRC/$m" ] || { echo "error: missing $SRC/$m" >&2; exit 1; }
done

python3 "$(dirname "$0")/prepare-apple-models.py" "$SRC" "$DST" || {
  echo "error: model preparation needs Python packages onnx, onnxruntime and numpy" >&2
  exit 1
}

# QA fixtures for the parity/round-trip tests (gitignored, same as the models).
QA_SRC="$ANDROID/qa-assets"
QA_DST="$(cd "$(dirname "$0")/.." && pwd)/naqiTests/Fixtures"
mkdir -p "$QA_DST"
for f in test-video.mp4; do
  [ -f "$QA_SRC/$f" ] || continue
  cmp -s "$QA_SRC/$f" "$QA_DST/$f" 2>/dev/null || cp "$QA_SRC/$f" "$QA_DST/$f"
  echo "qa   $f"
done

# The 90-minute soak asset for M5's exit criterion ("90-min film survives a
# forced kill + relaunch"). Synthesized rather than downloaded: the Android
# repo's 155-minute movie-test.mp4 is not in its qa-assets/, and this test
# needs duration and segment boundaries, not shot diversity — the gate and
# face-detection parity work already runs against the real clip.
#
# Deliberately 480x854 at ~750 kbps: EncodeSettings targets
# min(source x 1.3, tier cap), so a low-bitrate source keeps the 90-minute
# OUTPUT near 670 MB instead of the ~13 GB a 1080p source would produce. That
# is the difference between a soak that runs and one that fills the disk.
#
# NOT staged into naqiTests/Fixtures — a 584 MB file has no business in a test
# bundle. The soak reads it from qa-assets/ directly.
LONG="$(cd "$(dirname "$0")/.." && pwd)/qa-assets/long-film.mp4"
if [ -f "$LONG" ]; then
  echo "qa   long-film.mp4 (present, $(du -h "$LONG" | cut -f1))"
elif ! command -v ffmpeg >/dev/null; then
  echo "skip long-film.mp4 (needs ffmpeg; the long-film soak will skip)"
else
  UNIT="$(mktemp -d)/unit.mp4"; LIST="$(mktemp)"
  ffmpeg -y -loglevel error -i "$QA_DST/test-video.mp4" \
    -vf scale=480:854 -c:v libx264 -b:v 700k -preset veryfast -g 60 -c:a aac -b:a 96k "$UNIT"
  # 422 x 12.8 s = 90.0 min, past Checkpoint.longSourceThresholdMs (30 min).
  for _ in $(seq 422); do echo "file '$UNIT'"; done > "$LIST"
  ffmpeg -y -loglevel error -f concat -safe 0 -i "$LIST" -c copy "$LONG"
  rm -f "$LIST"
  echo "qa   long-film.mp4 (built, $(du -h "$LONG" | cut -f1))"
fi
