// Compile: swiftc -O -parse-as-library scripts/modelbench/vision_native.swift -o /tmp/naqi-vision
// Run: /tmp/naqi-vision <frames-root> <output-dir> <default|cpu> [frame-limit]
import CoreGraphics
import CoreImage
import CoreML
import Foundation
import ImageIO
import Vision

func topLeft(_ box: CGRect) -> [Double] {
    [box.minX, 1 - box.maxY, box.maxX, 1 - box.minY]
}

func png(_ image: CGImage, at url: URL) throws {
    guard let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)
    else { throw NSError(domain: "PNG", code: 1) }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw NSError(domain: "PNG", code: 2) }
}

func checkMaskPNG() throws {
    var allocated: CVPixelBuffer?
    precondition(CVPixelBufferCreate(nil, 8, 8, kCVPixelFormatType_OneComponent32Float, nil, &allocated) == kCVReturnSuccess)
    let buffer = allocated!
    CVPixelBufferLockBaseAddress(buffer, [])
    let samples = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: Float.self)
    let stride = CVPixelBufferGetBytesPerRow(buffer) / MemoryLayout<Float>.size
    for y in 0..<8 { for x in 0..<8 { samples[y * stride + x] = 0.5 } }
    CVPixelBufferUnlockBaseAddress(buffer, [])
    // The check uses CPU rendering so it can run beside setup without occupying the GPU.
    let context = CIContext(options: [.useSoftwareRenderer: true])
    let image = CIImage(cvPixelBuffer: buffer)
    guard let cg = context.createCGImage(image, from: image.extent) else { throw NSError(domain: "mask check", code: 1) }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("naqi-mask-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: url) }
    try png(cg, at: url)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let decoded = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw NSError(domain: "mask check", code: 2) }
    var rgba = [UInt8](repeating: 0, count: 4)
    rgba.withUnsafeMutableBytes { bytes in
        let bitmap = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                               space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        bitmap.draw(decoded, in: CGRect(x: 0, y: 0, width: 1, height: 1))
    }
    precondition(rgba.prefix(3).allSatisfy { abs(Int($0) - 128) <= 1 }, "float mask PNG changed the 0.5 threshold")
}

@main struct NativeVisionBench {
    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 2 && args[1] == "--self-check" {
            precondition(topLeft(CGRect(x: 0.1, y: 0.2, width: 0.3, height: 0.4)).enumerated().allSatisfy {
                abs($0.element - [0.1, 0.4, 0.4, 0.8][$0.offset]) < 1e-10
            })
            try checkMaskPNG()
            print("Vision coordinates and float-mask PNG checks passed")
            return
        }
        guard args.count >= 4, ["default", "cpu"].contains(args[3]) else {
            fatalError("usage: vision_native <frames-root> <output-dir> <default|cpu> [frame-limit]")
        }
        let root = URL(fileURLWithPath: args[1]), output = URL(fileURLWithPath: args[2])
        try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
        let log = output.appendingPathComponent("native-\(args[3]).jsonl")
        FileManager.default.createFile(atPath: log.path, contents: nil)
        let file = try FileHandle(forWritingTo: log)
        defer { try? file.close() }
        let cpu = MLComputeDevice.allComputeDevices.first { if case .cpu = $0 { true } else { false } }
        guard args[3] != "cpu" || cpu != nil else { fatalError("CPU unavailable") }
        let context = CIContext()
        let limit = args.count > 4 ? Int(args[4])! : Int.max
        let videos = try FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).sorted { $0.path < $1.path }
        // Each candidate has its own persistent request; semantic segmentation can retain temporal state.
        for video in videos {
            let frames = try FileManager.default.contentsOfDirectory(at: video, includingPropertiesForKeys: nil)
                .filter { $0.pathExtension == "png" }.sorted { $0.path < $1.path }
            for candidate in ["face-r3", "face-r4", "human-r2", "human-r3", "person-instance", "semantic-fast", "semantic-balanced", "semantic-accurate"] {
                var face = DetectFaceRectanglesRequest(candidate == "face-r3" ? .revision3 : .revision4)
                var human = DetectHumanRectanglesRequest(candidate == "human-r2" ? .revision2 : .revision3)
                human.upperBodyOnly = false
                var instance = GeneratePersonInstanceMaskRequest()
                let semantic = GeneratePersonSegmentationRequest()
                semantic.outputPixelFormatType = kCVPixelFormatType_OneComponent8
                semantic.qualityLevel = candidate == "semantic-fast" ? .fast : candidate == "semantic-balanced" ? .balanced : .accurate
                if args[3] == "cpu" {
                    face.setComputeDevice(cpu, for: .main)
                    human.setComputeDevice(cpu, for: .main)
                    instance.setComputeDevice(cpu, for: .main)
                    semantic.setComputeDevice(cpu, for: .main)
                }
                for (index, frame) in frames.prefix(limit).enumerated() {
                    let handler = ImageRequestHandler(frame)
                    var row: [String: Any] = ["video": video.lastPathComponent, "frame": frame.lastPathComponent,
                        "timestamp_s": Double(Int(frame.deletingPathExtension().lastPathComponent)! - 1) + 0.5,
                        "candidate": candidate, "runtime": "Apple Vision", "compute_requested": args[3],
                        "compute_assignment_stage": args[3] == "cpu" ? "main only; postProcessing default" : "framework selected",
                        "placement_verified": false,
                        "host": "Apple M3 24GB", "os": ProcessInfo.processInfo.operatingSystemVersionString,
                        "thermal_state": ProcessInfo.processInfo.thermalState.rawValue, "warmup": index < 3,
                        "input_long_side": 640, "boxes": [[Double]](), "masks": [String]()]
                    let started = ContinuousClock.now
                    var pngExportMs = 0.0
                    do {
                        if candidate.hasPrefix("face") {
                            let observations = try await handler.perform(face)
                            row["inference_ms"] = milliseconds(started)
                            row["boxes"] = observations.map { topLeft($0.boundingBox.cgRect) }
                            row["confidence"] = observations.map { $0.confidence }
                        } else if candidate.hasPrefix("human") {
                            let observations = try await handler.perform(human)
                            row["inference_ms"] = milliseconds(started)
                            row["boxes"] = observations.map { topLeft($0.boundingBox.cgRect) }
                            row["confidence"] = observations.map { $0.confidence }
                        } else if candidate == "person-instance" {
                            let observation = try await handler.perform(instance)
                            row["inference_ms"] = milliseconds(started)
                            if let observation {
                                row["instance_ids"] = Array(observation.allInstances)
                                var masks: [String] = []
                                for id in observation.allInstances {
                                    let buffer = try observation.generateScaledMask(for: IndexSet(integer: id), scaledToImageFrom: handler)
                                    let image = CIImage(cvPixelBuffer: buffer)
                                    guard let cg = context.createCGImage(image, from: image.extent) else { throw NSError(domain: "mask", code: 1) }
                                    let name = "\(video.lastPathComponent)_\(candidate)_\(frame.deletingPathExtension().lastPathComponent)_\(id)_\(args[3]).png"
                                    let pngStarted = ContinuousClock.now
                                    try png(cg, at: output.appendingPathComponent(name))
                                    pngExportMs += milliseconds(pngStarted)
                                    masks.append(name)
                                }
                                row["masks"] = masks
                            }
                        } else {
                            let observation = try await handler.perform(semantic)
                            row["inference_ms"] = milliseconds(started)
                            let name = "\(video.lastPathComponent)_\(candidate)_\(frame.deletingPathExtension().lastPathComponent)_\(args[3]).png"
                            let cg = try observation.cgImage
                            let pngStarted = ContinuousClock.now
                            try png(cg, at: output.appendingPathComponent(name))
                            pngExportMs += milliseconds(pngStarted)
                            row["masks"] = [name]
                        }
                        row["infer_mask_export_ms"] = milliseconds(started)
                        row["png_export_ms"] = pngExportMs
                        row["processing_ms"] = milliseconds(started) - pngExportMs
                    } catch {
                        row["error"] = String(describing: error)
                        row["infer_mask_export_ms"] = milliseconds(started)
                    }
                    try file.write(contentsOf: JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]))
                    try file.write(contentsOf: Data([10]))
                }
                print("\(video.lastPathComponent) \(candidate) \(args[3]) done")
            }
        }
    }

    static func milliseconds(_ started: ContinuousClock.Instant) -> Double {
        let parts = started.duration(to: .now).components
        return Double(parts.seconds) * 1000 + Double(parts.attoseconds) / 1e15
    }
}
