#!/bin/bash
# Build public/models/spleeter-2stems.onnx (gitignored) the way Musicless does: download sherpa-onnx's
# Spleeter 2-stem export (Deezer's MIT weights), merge its two networks into one mask model with the
# vendored scripts/build_model.py, and check the result against Musicless' tested hash. Needs curl,
# tar and uv. The onnx/numpy pins produce that exact hash: do not float them.
set -euo pipefail
cd "$(dirname "$0")/.."
DEST=public/models
OUT=$DEST/spleeter-2stems.onnx
URL=https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2
ARCHIVE_SHA256=e26401d9c1801f43c0229731d78d32c2e80085e3aceedeb25f29d2de5fa68ca2
MODEL_SHA256=3dc5eaf764fc3c12daa4b69a295be426eb0870dbd400afe09c18d0f66e3a50de

sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }

mkdir -p "$DEST"
if [ ! -s "$OUT" ] || [ "$(sha256 "$OUT")" != "$MODEL_SHA256" ]; then
  work=$(mktemp -d)
  trap 'rm -rf "$work"' EXIT
  echo "Downloading Spleeter 2-stem (sherpa-onnx export)..."
  curl -fsSL --retry 3 -o "$work/spleeter.tar.bz2" "$URL"
  if [ "$(sha256 "$work/spleeter.tar.bz2")" != "$ARCHIVE_SHA256" ]; then
    echo "Checksum mismatch for $URL" >&2
    exit 1
  fi
  mkdir "$work/spleeter"
  tar -xjf "$work/spleeter.tar.bz2" -C "$work/spleeter" --strip-components=1
  uv run --no-project --python 3.13 --with onnx==1.23.2 --with numpy==2.5.4 \
    python -I scripts/build_model.py "$work/spleeter" "$work/spleeter-2stems.onnx"
  if [ "$(sha256 "$work/spleeter-2stems.onnx")" != "$MODEL_SHA256" ]; then
    echo "The built model differs from the tested one (expected sha256 $MODEL_SHA256)." >&2
    exit 1
  fi
  mv "$work/spleeter-2stems.onnx" "$OUT"
fi

touch "$DEST/SHA256SUMS"
{ grep -v spleeter-2stems.onnx "$DEST/SHA256SUMS" || true; echo "$MODEL_SHA256  spleeter-2stems.onnx"; } \
  > "$DEST/SHA256SUMS.new"
mv "$DEST/SHA256SUMS.new" "$DEST/SHA256SUMS"
(cd "$DEST" && shasum -a 256 -c SHA256SUMS)
