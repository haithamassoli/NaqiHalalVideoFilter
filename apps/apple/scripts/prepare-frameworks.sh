#!/bin/sh
set -eu

frameworks="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}"

# ORT links statically on iOS; its unused stub has mismatched minimum OS metadata.
rm -rf "$frameworks/onnxruntime.framework"

[ "${PLATFORM_NAME:-}" = iphoneos ] || exit 0

# The pinned FFmpeg binaries contain arm64e built with SDK 17.2, which upload
# validation rejects. Naqi uses arm64; keep that slice and its original SDK metadata.
for name in ffmpegkit libavcodec libavdevice libavfilter libavformat libavutil libswresample libswscale; do
    framework="$frameworks/$name.framework"
    binary="$framework/$name"
    [ -f "$binary" ] || continue
    case " $(xcrun lipo -archs "$binary") " in
        *" arm64e "*)
            xcrun lipo "$binary" -verify_arch arm64
            xcrun lipo "$binary" -remove arm64e -output "$binary.tmp"
            mv "$binary.tmp" "$binary"
            echo "Removed legacy arm64e slice from $name.framework"
            # Xcode may have signed the copied framework before this phase.
            if [ "${CODE_SIGNING_ALLOWED:-YES}" != NO ] && [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ]; then
                /usr/bin/codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" \
                    --preserve-metadata=identifier,entitlements "$framework"
            fi
            ;;
    esac
done
