// swift-tools-version: 5.9

// Stand-in for https://github.com/microsoft/onnxruntime-swift-package-manager, which (with
// microsoft/onnxruntime) no longer exists on GitHub, so the app could not resolve its packages.
// It is that package's 1.24.2 release (commit b7fb7f7dea8a2469e6335d95a61b8f36d0dc83b2) cut down
// to what the app uses: the same product name and the same OnnxRuntimeBindings module, so no Swift
// source changed.
//
// objectivec/ is Microsoft's Objective-C wrapper copied unchanged from that commit (MIT, see
// LICENSE), minus the tests, docs and training API the app does not build.
//
// The binary is Microsoft's onnxruntime-c 1.24.2 pod archive (iOS, iOS Simulator and a universal
// macOS slice). Its official host, download.onnxruntime.ai, now returns 404, so the URL is a GitHub
// mirror. The checksum is the one Microsoft's own Package.swift at that commit pins, and SwiftPM
// refuses any download that does not match it, so the mirror cannot change the bytes. Several
// other mirrors carry the same file (sha256 checked); if this one disappears, any of them works:
//   https://github.com/AlexHayton/onnxruntime-swift-package-manager/releases/download/1.24.2-mirror/pod-archive-onnxruntime-c-1.24.2.zip
//   https://github.com/FutureWorkshops/onnxruntime-swift-package-manager/releases/download/1.24.3/pod-archive-onnxruntime-c-1.24.2.zip
//   https://github.com/sackheads/onnxruntime-swift-package-manager/releases/download/1.24.2/pod-archive-onnxruntime-c-1.24.2.zip

import PackageDescription

let package = Package(
    name: "onnxruntime",
    platforms: [.iOS(.v15), .macOS(.v14)],
    products: [
        .library(name: "onnxruntime", type: .static, targets: ["OnnxRuntimeBindings"]),
    ],
    targets: [
        .target(name: "OnnxRuntimeBindings",
                dependencies: ["onnxruntime"],
                path: "objectivec",
                cxxSettings: [.define("SPM_BUILD")]),
        .binaryTarget(name: "onnxruntime",
                      url: "https://github.com/tryAGI/onnxruntime-swift-package-manager/releases/download/assets-1.24.2/pod-archive-onnxruntime-c-1.24.2.zip",
                      checksum: "f7100a992d2a8135168c8afd831e6a58b465349101982aa58b3e11d36e600b54"),
    ],
    cxxLanguageStandard: .cxx17
)
