#!/bin/bash -e
# Stage the ONNX models the Windows app bundles into the gitignored apps/windows/models/.
# The four shared with Android come from Android's assets (fetched there first if missing); YuNet replaces
# ML Kit's face detector and is downloaded here. Every file is checked against a pinned sha256.
# Not staged: yolo11n-seg.onnx, which only body modes use, and those are not on Windows yet.
cd "$(dirname "$0")/.."
SRC=../android/app/src/main/assets/models
DEST=models
mkdir -p "$DEST"

SHARED=(genderage.onnx htdemucs_s26_f16.onnx nsfw_mnv2_140_int8.onnx yamnet.onnx)
for model in "${SHARED[@]}"; do
  if [ ! -s "$SRC/$model" ]; then ../android/scripts/fetch-models.sh; break; fi
done
for model in "${SHARED[@]}"; do
  [ -s "$DEST/$model" ] || cp "$SRC/$model" "$DEST/$model"
done

# opencv_zoo, pinned to the commit that added the 2023mar weights (MIT).
YUNET=face_detection_yunet_2023mar.onnx
if [ ! -s "$DEST/$YUNET" ]; then
  curl -fL --retry 3 -o "$DEST/$YUNET.part" \
    https://github.com/opencv/opencv_zoo/raw/f12e12798e8314f7c074a6656816c048dcc95b7a/models/face_detection_yunet/$YUNET
  mv "$DEST/$YUNET.part" "$DEST/$YUNET"
fi

# The first four are the same pins as apps/android/scripts/fetch-models.sh.
shasum -a 256 -c <<EOF
4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb  $DEST/genderage.onnx
df8a2c2c8dd06ca279f58646dacc007b3e1e07436f31a3189408f0336c56eba5  $DEST/htdemucs_s26_f16.onnx
6070dd6da875025b4c8df960a3a46dff583fb2bf8a499e214f98a8984674bba9  $DEST/nsfw_mnv2_140_int8.onnx
afe82472f2f6250570b63d4f106e7a74b5232cfd17086d39076d80a4273d01f8  $DEST/yamnet.onnx
8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  $DEST/$YUNET
EOF
