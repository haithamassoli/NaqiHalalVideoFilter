#!/bin/sh
# Capture the six store screens, EN and AR, on one simulator.
#
#   scripts/capture-screenshots.sh 6.9 "iPhone 17 Pro Max"      # 1320x2868
#   scripts/capture-screenshots.sh 6.5 "naqi-6.5"               # 1284x2778
#   scripts/capture-screenshots.sh 13  "iPad Pro 13-inch (M5)"  # 2064x2752
#   python3 scripts/caption-screenshots.py <set>                # then plate them
#
# There is no 6.5" device in Xcode 26's default set; create one once with
#   xcrun simctl create naqi-6.5 \
#     com.apple.CoreSimulator.SimDeviceType.iPhone-14-Plus \
#     com.apple.CoreSimulator.SimRuntime.iOS-26-5
# 1284x2778 is accepted for both the 6.5" and 6.7" slots.
#
# ponytail: screens 2 and 3 are the same capture under two captions, which is
# what the listing asks for — a real strictness shot needs a scroll, and a
# scroll needs XCUITest driving the run.
set -e
SET=${1:?usage: capture-screenshots.sh <6.9|6.5|13> <simulator name>}
DEV=${2:?usage: capture-screenshots.sh <6.9|6.5|13> <simulator name>}
BUNDLE=com.haithamassoli.naqi
OUT="$(cd "$(dirname "$0")/.." && pwd)/docs/apple-port/screenshots/$SET"
DD=build.noindex/DD

# DEBUG: -naqiScreen and the ScreenshotSeed extension are compiled out of Release.
xcodebuild -project naqi.xcodeproj -scheme naqi -configuration Debug \
  -destination "platform=iOS Simulator,name=$DEV" -derivedDataPath "$DD" build >/dev/null

xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl install "$DEV" "$DD/Build/Products/Debug-iphonesimulator/naqi.app"
xcrun simctl status_bar "$DEV" override --time 9:41 --batteryState charged \
  --batteryLevel 100 --wifiBars 3 --cellularMode notSupported

# The progress screen names its source file, so stage one: ScreenshotSeed
# prefers Documents/screenshot-source.mp4 over the posed holiday-in-tabuk name.
: > "$(xcrun simctl get_app_container "$DEV" $BUNDLE data)/Documents/screenshot-source.mp4"

mkdir -p "$OUT"
for lang in en ar; do
  # 3-strictness is deliberately the same screen as 2-options; see above.
  for pair in 1-pick:pick 2-options:options 3-strictness:options \
              4-progress:progress 5-done:done 6-about:about; do
    stem=${pair%%:*}
    xcrun simctl terminate "$DEV" $BUNDLE 2>/dev/null || true
    if [ "$lang" = ar ]; then
      xcrun simctl launch "$DEV" $BUNDLE -naqi.onboarded YES -naqiScreen "${pair#*:}" \
        -AppleLanguages "(ar)" -AppleLocale ar_SA >/dev/null
    else
      xcrun simctl launch "$DEV" $BUNDLE -naqi.onboarded YES -naqiScreen "${pair#*:}" >/dev/null
    fi
    sleep 4   # SwiftUI settles; a shot taken sooner catches the launch fade
    xcrun simctl io "$DEV" screenshot "$OUT/$stem-$lang.png" 2>/dev/null
    echo "  $stem-$lang.png  $(sips -g pixelWidth -g pixelHeight "$OUT/$stem-$lang.png" |
                               awk '/pixel/{printf "%s ", $2}')"
  done
done
xcrun simctl shutdown "$DEV"
