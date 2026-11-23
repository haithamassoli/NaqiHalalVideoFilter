#!/bin/bash
set -euo pipefail

# Reuse the bindings built by the project. No alternate ORT/runtime download.
# Usage: build_apple_audio.sh /path/to/macOS-DerivedData [output-directory]
root="$(cd "$(dirname "$0")/../.." && pwd)"
deps="${1:?Supply DerivedData from a macOS build of Naqi}"
out="${2:-$root/build.noindex/modelbench-native}"
frameworks="$deps/SourcePackages/artifacts/onnxruntime-swift-package-manager/onnxruntime/onnxruntime.xcframework/macos-arm64_x86_64"
modulemap="$deps/Build/Intermediates.noindex/GeneratedModuleMaps/OnnxRuntimeBindings.modulemap"
bindings="$deps/Build/Products/Debug/OnnxRuntimeBindings.o"
if [[ ! -f "$bindings" ]]; then bindings="$deps/Build/Products/Release/OnnxRuntimeBindings.o"; fi
mkdir -p "$out"
xcrun clang++ -std=c++17 -fobjc-arc -F "$frameworks" -c "$root/naqi/ML/NaqiOrtArena.mm" -o "$out/NaqiOrtArena.o"
xcrun swiftc -O -parse-as-library -swift-version 6 \
  -Xcc "-fmodule-map-file=$modulemap" -Xcc "-I$root/naqi/ML" -F "$frameworks" \
  -import-objc-header "$root/naqi/naqi-Bridging-Header.h" \
  "$root/scripts/modelbench/apple_audio.swift" \
  "$root/scripts/modelbench/apple_audio_io.swift" \
  "$root/naqi/Audio/Demucs.swift" "$root/naqi/Audio/STFT.swift" "$root/naqi/Audio/MusicGate.swift" \
  "$root/naqi/ML/Ort.swift" "$root/naqi/ML/Models.swift" \
  "$root/naqi/Core/Log.swift" "$root/naqi/Core/MemoryFootprint.swift" \
  "$bindings" "$out/NaqiOrtArena.o" -framework onnxruntime -framework CoreML -lc++ \
  -Xlinker -rpath -Xlinker "$frameworks" -o "$out/apple_audio"
printf '%s\n' "$out/apple_audio"
