#!/bin/bash -e
# Fetch the bundled ONNX models into app/src/main/assets/models/ (gitignored).
# The NSFW gate and htdemucs are *converted* artifacts — exact regeneration pipelines
# (venvs, commands, parity checks) are in docs/m0-spikes.md and use the helper scripts
# next to this one.
#
# NudeNet v3 320n used to be fetched here. It is GONE (plan-v2 §5.4): AGPL-3.0 in a
# closed-source APK, and the gender vote it powered censored ~every face anyway, so face
# censoring no longer classifies. Delete any leftover app/src/main/assets/models/nudenet_320n.onnx
# by hand — this script no longer knows about it.
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/models
mkdir -p "$DEST"

# htdemucs_s26, not s39: M3 re-exported at a 2.6 s segment (the 3.9 s one peaked over the RSS budget).
# This line said s39 and so could never fire correctly — the check was for a file that is not shipped.
# The fp32 gate is still listed although NaqiModel.NSFW_GATE now ships the INT8 one: it is the input to
# the quantizer below, and it is what a recall regression is A/B'd against.
for f in nsfw_mnv2_140_f32.onnx htdemucs_s26_f16.onnx; do
  [ -s "$DEST/$f" ] || echo "MISSING $DEST/$f — regenerate per docs/m0-spikes.md (Models section)"
done

# The two self-contained artifacts, so they just run rather than telling you to go read a doc.
# INT8 gate (V3) — needs the fp32 graph above, so it is a no-op when that one is missing.
[ -s "$DEST/nsfw_mnv2_140_int8.onnx" ] || [ ! -s "$DEST/nsfw_mnv2_140_f32.onnx" ] || \
  python3 scripts/nsfw_int8_quantize.py
# YAMNet music gate (A1).
[ -s "$DEST/yamnet.onnx" ] || python3 scripts/yamnet_export.py

# Face gender vote (plan-censor-who §3.1). Shipped verbatim — no conversion step, but InsightFace
# publishes it only inside buffalo_l.zip (289 MB) so we pull the pack, extract the one 1.3 MB file
# and throw the rest away. `unzip -j` drops the buffalo_l/ prefix.
GENDERAGE_SHA=4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb
if [ ! -s "$DEST/genderage.onnx" ]; then
  ZIP="$DEST/buffalo_l.zip.tmp"
  curl -fL -o "$ZIP" \
    https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip
  unzip -o -j "$ZIP" buffalo_l/genderage.onnx -d "$DEST"
  rm -f "$ZIP"
fi
# Verified even when the file was already present: the sha is the contract NaqiModel.GENDERAGE
# pins, and a silently different graph is worse than a missing one.
echo "$GENDERAGE_SHA  $DEST/genderage.onnx" | shasum -a 256 -c - || {
  echo "genderage.onnx sha256 MISMATCH — refusing to ship it" >&2
  exit 1
}
