#!/bin/bash
# Stage the vision models into the gitignored public/models/, copied from the other apps' staged
# folders and checked against the same SHA-256 pins as apps/android and apps/windows. The music model
# comes from scripts/build-spleeter.sh instead. Writes public/models/SHA256SUMS.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=public/models
SOURCES=(../windows/models ../android/app/src/main/assets/models)

PINS="8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  face_detection_yunet_2023mar.onnx
4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb  genderage.onnx
6070dd6da875025b4c8df960a3a46dff583fb2bf8a499e214f98a8984674bba9  nsfw_mnv2_140_int8.onnx
a119f46d1eb44acefb071c9733b2f12fc9bd89cfc581f7df4151cdb6ba758c41  yolo11n-seg.onnx"

mkdir -p "$DEST"
missing=()
while read -r _ model; do
  [ -s "$DEST/$model" ] && continue
  for src in "${SOURCES[@]}"; do
    if [ -s "$src/$model" ]; then cp "$src/$model" "$DEST/$model"; break; fi
  done
  [ -s "$DEST/$model" ] || missing+=("$model")
done <<< "$PINS"

if [ ${#missing[@]} -gt 0 ]; then
  echo "Missing: ${missing[*]}" >&2
  echo "Stage them in apps/android or apps/windows first:" >&2
  echo "  - apps/android/README.md (scripts/fetch-models.sh; yolo11n-seg.onnx comes from there)" >&2
  echo "  - apps/windows/README.md (./gradlew :app:stageModels; face_detection_yunet_2023mar.onnx comes from there)" >&2
  exit 1
fi

# Keep a line build-spleeter.sh added; rewrite the four pinned here.
touch "$DEST/SHA256SUMS"
{ grep -v -e face_detection_yunet_2023mar.onnx -e genderage.onnx -e nsfw_mnv2_140_int8.onnx \
    -e yolo11n-seg.onnx "$DEST/SHA256SUMS" || true; echo "$PINS"; } > "$DEST/SHA256SUMS.new"
mv "$DEST/SHA256SUMS.new" "$DEST/SHA256SUMS"
(cd "$DEST" && shasum -a 256 -c SHA256SUMS)
