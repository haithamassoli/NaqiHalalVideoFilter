#!/bin/bash
set -euo pipefail

# One native bundle gives unchanged production Bundle.main lookups their models.
# Optional fourth argument builds an older checkout with this same CLI source.
root="$(cd "$(dirname "$0")/../.." && pwd)"
deps="${1:?Supply DerivedData from a macOS build of Naqi}"
models="${2:?Supply the model-assets directory}"
out="${3:-$root/build.noindex/integration-native}"
source_root="${4:-$root}"
frameworks="$deps/SourcePackages/artifacts/onnxruntime-swift-package-manager/onnxruntime/onnxruntime.xcframework/macos-arm64_x86_64"
modulemap="$deps/Build/Intermediates.noindex/GeneratedModuleMaps/OnnxRuntimeBindings.modulemap"
bindings="$deps/Build/Products/Debug/OnnxRuntimeBindings.o"
if [[ ! -f "$bindings" ]]; then bindings="$deps/Build/Products/Release/OnnxRuntimeBindings.o"; fi
bundle="$out/naqi-integration.app"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources/Models"
for model in "$models"/*.onnx "$models"/*.mlmodelc; do
  if [[ -e "$model" ]]; then ln -sfn "$model" "$bundle/Contents/Resources/Models/$(basename "$model")"; fi
done
cat > "$bundle/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>naqi-integration</string>
<key>CFBundleIdentifier</key><string>com.haithamassoli.naqi.integrationbench</string>
<key>CFBundlePackageType</key><string>APPL</string>
</dict></plist>
PLIST
flags=(-D NAQI_LEGACY)
if [[ -f "$source_root/naqi/Analyze/PersonTracker.swift" ]]; then flags=(-D NAQI_PERSON); fi
python3 - "$source_root" "$bundle/Contents/Resources/integration-build.json" <<'PY'
import hashlib,json,sys
from pathlib import Path
source=Path(sys.argv[1])
files=[]
for directory in ('Audio','Analyze','Media','Render'):
    files.extend((source/'naqi'/directory).glob('*.swift'))
for name in ('ML/Ort.swift','ML/Models.swift','Core/FilterOps.swift','Core/Confined.swift','Core/Log.swift','Core/MemoryFootprint.swift'):
    files.append(source/'naqi'/name)
files.append(source/'NaqiShared/AppGroup.swift')
record={'source_root':str(source),'files':{str(p.relative_to(source)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}}
Path(sys.argv[2]).write_text(json.dumps(record,sort_keys=True)+'\n')
PY
xcrun clang++ -std=c++17 -fobjc-arc -F "$frameworks" -c "$source_root/naqi/ML/NaqiOrtArena.mm" -o "$out/NaqiOrtArena.o"
xcrun swiftc -O -parse-as-library -swift-version 6 "${flags[@]}" \
  -Xcc "-fmodule-map-file=$modulemap" -Xcc "-I$source_root/naqi/ML" -F "$frameworks" \
  -import-objc-header "$source_root/naqi/naqi-Bridging-Header.h" \
  "$root/scripts/modelbench/apple_integration.swift" \
  "$source_root"/naqi/Audio/*.swift "$source_root"/naqi/Analyze/*.swift \
  "$source_root"/naqi/Media/*.swift "$source_root"/naqi/Render/*.swift \
  "$source_root/naqi/ML/Ort.swift" "$source_root/naqi/ML/Models.swift" \
  "$source_root/naqi/Core/FilterOps.swift" "$source_root/naqi/Core/Confined.swift" \
  "$source_root/naqi/Core/Log.swift" "$source_root/naqi/Core/MemoryFootprint.swift" \
  "$source_root/NaqiShared/AppGroup.swift" "$bindings" "$out/NaqiOrtArena.o" \
  -framework onnxruntime -framework CoreML -lc++ \
  -Xlinker -rpath -Xlinker "$frameworks" -o "$bundle/Contents/MacOS/naqi-integration"
printf '%s\n' "$bundle/Contents/MacOS/naqi-integration"
