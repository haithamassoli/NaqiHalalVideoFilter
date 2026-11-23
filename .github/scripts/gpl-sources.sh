#!/bin/bash -e
# The corresponding source of the GPL FFmpeg build that apps/windows bundles (org.bytedeco:ffmpeg:$ARTIFACT,
# the -gpl classifiers): each tarball pinned to a release or commit, and by sha256. apps/windows/NOTICE links
# the same URLs, and the Windows release attaches the tarballs (.github/workflows/release-windows.yml).
#
#   gpl-sources.sh check [NOTICE] [SETTINGS]   NOTICE's source section links every URL below and nothing that
#                                              moves (a branch), and SETTINGS still asks for $ARTIFACT
#   gpl-sources.sh fetch DIR                   downloads the tarballs into DIR and checks their sha256
#
# x264: JavaCPP Presets 1.5.14 downloads the moving x264 "stable" branch (ffmpeg/cppbuild.sh, X264=x264-stable).
# Its presets for FFmpeg 8.1.2 date from 2026-08-01 and the tag from 2026-08-10; stable has pointed at b35605a
# (2025-06-08) the whole time, so that is the commit in the build. When ARTIFACT changes, redo this list from
# ffmpeg/cppbuild.sh at the new tag (and the x264 stable commit at that date).
ARTIFACT=8.1.2-1.5.14
PRESETS=d57d8a28635211e174df378611cb1952f751c943 # javacpp-presets tag 1.5.14
X264=b35605ace3ddf7c1a5d67a2eb553f034aef41d55
# file  sha256  url
SOURCES="\
ffmpeg-8.1.2.tar.bz2 b4925bd4411e654ad3884bc8da1860b0d860bd64a95a17220de48cfcd5f0a859 https://ffmpeg.org/releases/ffmpeg-8.1.2.tar.bz2
x264-$X264.tar.gz cd71a7515b0e9a012e1ac9b1f8415bebcaf6fc97d4db32286642ac4c0fbe24f9 https://code.videolan.org/videolan/x264/-/archive/$X264/x264-$X264.tar.gz
x265-3.4.tar.gz 544d147bf146f8994a7bf8521ed878c93067ea1c7c6e93ab602389be3117eaaf https://github.com/videolan/x265/archive/3.4.tar.gz
javacpp-presets-$PRESETS.tar.gz c795c211313a73c78e112ed5226912180e0e2357b1174fb0791f19cb8ac6355d https://github.com/bytedeco/javacpp-presets/archive/$PRESETS.tar.gz"

ROOT=$(cd "$(dirname "$0")/../.." && pwd)

case "$1" in
check)
  NOTICE=${2:-$ROOT/apps/windows/NOTICE}
  SETTINGS=${3:-$ROOT/apps/windows/settings.gradle.kts}
  if ! grep -qF "version(\"ffmpeg\", \"$ARTIFACT\")" "$SETTINGS"; then
    echo "$SETTINGS no longer asks for FFmpeg $ARTIFACT: re-pin the sources in $0." >&2
    exit 1
  fi
  SECTION=$(sed -n '/^--- Corresponding source/,/Written offer/p' "$NOTICE")
  if [ -z "$SECTION" ]; then
    echo "$NOTICE has no \"--- Corresponding source\" section." >&2
    exit 1
  fi
  STATUS=0
  while read -r _ _ URL; do
    grep -qF "$URL" <<<"$SECTION" || { echo "$NOTICE does not link $URL" >&2; STATUS=1; }
  done <<<"$SOURCES"
  for URL in "https://github.com/bytedeco/javacpp-presets/tree/$PRESETS/ffmpeg" \
    "https://github.com/bytedeco/javacpp-presets/blob/$PRESETS/cppbuild.sh"; do
    grep -qF "$URL" <<<"$SECTION" || { echo "$NOTICE does not link $URL" >&2; STATUS=1; }
  done
  # A branch name in a source link: what it serves changes after the build.
  if MOVING=$(grep -nE '/(stable|master|main)(/|\.tar|$)|-stable\.tar' <<<"$SECTION"); then
    echo "$NOTICE links a moving branch, not the source that was built:" >&2
    echo "$MOVING" >&2
    STATUS=1
  fi
  [ "$STATUS" -eq 0 ] && echo "NOTICE links the pinned GPL sources of FFmpeg $ARTIFACT."
  exit "$STATUS"
  ;;
fetch)
  mkdir -p "$2"
  while read -r FILE SHA URL; do
    curl -fsSL --retry 3 -o "$2/$FILE" "$URL"
    # shasum, not sha256sum: the one both Git for Windows and macOS have.
    if ! echo "$SHA  $2/$FILE" | shasum -a 256 -c - >/dev/null; then
      echo "$URL is not the archive that was pinned (sha256 changed). Check it before releasing." >&2
      exit 1
    fi
    echo "$FILE: OK"
  done <<<"$SOURCES"
  ;;
*)
  echo "usage: $0 check [NOTICE] [SETTINGS] | fetch DIR" >&2
  exit 2
  ;;
esac
