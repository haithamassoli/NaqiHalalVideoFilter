#!/bin/bash -e
# Fetch the bundled ONNX models into app/src/main/assets/models/ (gitignored).
# NudeNet downloads directly; the NSFW gate and htdemucs are *converted* artifacts —
# exact regeneration pipelines (venvs, commands, parity checks) are in docs/m0-spikes.md
# and use the helper scripts next to this one.
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/models
mkdir -p "$DEST"

# NudeNet v3 320n — github.com/notAI-tech/NudeNet (AGPL-3.0; flagged for license review)
if [ ! -s "$DEST/nudenet_320n.onnx" ]; then
  gh release download v3.4-weights -R notAI-tech/NudeNet -p 320n.onnx -O "$DEST/nudenet_320n.onnx" --clobber
fi
echo "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f  $DEST/nudenet_320n.onnx" | shasum -a 256 -c

for f in nsfw_mnv2_140_f32.onnx htdemucs_s39_f16.onnx; do
  [ -s "$DEST/$f" ] || echo "MISSING $DEST/$f — regenerate per docs/m0-spikes.md (Models section)"
done
