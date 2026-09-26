#!/bin/bash -e
# Restore the ONNX models from the last complete release into gitignored assets.
# Verify every artifact against the inference contract in ml/Models.kt.
cd "$(dirname "$0")/.."
DEST=app/src/main/assets/models
mkdir -p "$DEST"

MODELS=(genderage.onnx htdemucs_s26_f16.onnx nsfw_mnv2_140_int8.onnx yamnet.onnx)
for model in "${MODELS[@]}"; do
  if [ ! -s "$DEST/$model" ]; then
    APK=$(mktemp)
    trap 'rm -f "$APK"' EXIT
    curl -fL --retry 3 -o "$APK" \
      https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.4.4/naqi-1.4.4.apk
    for missing in "${MODELS[@]}"; do
      [ -s "$DEST/$missing" ] || unzip -p "$APK" "assets/models/$missing" > "$DEST/$missing"
    done
    break
  fi
done

shasum -a 256 -c <<EOF
4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb  $DEST/genderage.onnx
df8a2c2c8dd06ca279f58646dacc007b3e1e07436f31a3189408f0336c56eba5  $DEST/htdemucs_s26_f16.onnx
6070dd6da875025b4c8df960a3a46dff583fb2bf8a499e214f98a8984674bba9  $DEST/nsfw_mnv2_140_int8.onnx
afe82472f2f6250570b63d4f106e7a74b5232cfd17086d39076d80a4273d01f8  $DEST/yamnet.onnx
EOF
