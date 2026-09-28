#!/bin/bash
# Re-render the social preview images from og.html with headless Chrome.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"

for lang in en ar; do
  "$CHROME" --headless --hide-scrollbars --force-device-scale-factor=1 \
    --virtual-time-budget=5000 --window-size=1200,630 \
    --screenshot="$HERE/../public/og-$lang.png" "file://$HERE/og.html?lang=$lang" >/dev/null 2>&1
done
ls -la "$HERE"/../public/og-*.png
