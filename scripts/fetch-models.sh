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
