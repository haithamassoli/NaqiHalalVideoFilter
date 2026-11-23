#!/bin/bash -e
# Tests `gpl-sources.sh check` against apps/windows/NOTICE and broken copies of it.
cd "$(dirname "$0")"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
NOTICE=../../apps/windows/NOTICE
SETTINGS=../../apps/windows/settings.gradle.kts

ok() { ./gpl-sources.sh check "$@" >/dev/null || { echo "FAIL: expected pass: $*"; ./gpl-sources.sh check "$@"; exit 1; }; }
fails() { if ./gpl-sources.sh check "$@" >/dev/null 2>&1; then echo "FAIL: expected failure: $*"; exit 1; fi; }

ok "$NOTICE" "$SETTINGS"

# The moving x264 branch link this replaced.
sed 's#^    https://code.videolan.org/videolan/x264/.*#    https://code.videolan.org/videolan/x264/-/archive/stable/x264-stable.tar.gz#' \
  "$NOTICE" >"$TMP/stable"
fails "$TMP/stable" "$SETTINGS"

# Build scripts at a tag name instead of the commit.
sed 's#javacpp-presets/tree/[0-9a-f]*/ffmpeg#javacpp-presets/tree/1.5.14/ffmpeg#' "$NOTICE" >"$TMP/tag"
fails "$TMP/tag" "$SETTINGS"

# A pinned tarball missing.
grep -v 'x265/archive' "$NOTICE" >"$TMP/missing"
fails "$TMP/missing" "$SETTINGS"

# FFmpeg bumped in the build without re-pinning the sources.
sed 's/version("ffmpeg", "[^"]*")/version("ffmpeg", "8.2.0-1.5.15")/' "$SETTINGS" >"$TMP/settings"
fails "$NOTICE" "$TMP/settings"

echo "gpl-sources.sh: all tests passed"
