import AVFoundation
import Foundation
import Testing
@testable import naqi

@Suite("Face blur regression", .serialized)
struct FaceBlurRegressionTests {
    // Stage https://youtube.com/shorts/-dQJ3djthDc as
    // naqiTests/Fixtures/face-blur-regression.mp4 (media stays gitignored).
    private static var clip: URL? {
        Bundle(for: BundleToken.self).url(forResource: "face-blur-regression", withExtension: "mp4")
    }
    private final class BundleToken {}

    @Test("enabling face censoring overrides the legacy off selection", .enabled(if: clip != nil))
    func legacyOffSelection() async throws {
        let source = try #require(Self.clip)
        var ops = FilterOps()
        ops.censorTarget = .face
        ops.censor = true
        ops.who = .none
        ops.censorNsfw = false
        let folder = Fixtures.scratch("face-blur-legacy")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let job = Job.capture(source: source, ops: ops, destination: .userFolder, folder: folder)
        defer { WorkDir.clear(Checkpoint.key(source: source, ops: ops)) }
        let completed = try await JobRunner.run(job)
        let output = try #require(completed.output.url)
        let face = CGRect(x: 0.32, y: 0.28, width: 0.32, height: 0.23)
        let before = try await faceDetail(source, rect: face)
        let after = try await faceDetail(output, rect: face)
        #expect(before > 0)
        #expect(after * 10 < before * 6, "enabled face filter preserved the original face: \(before) → \(after)")
    }

    @Test("the reported portrait clip covers the face in the exported video",
          .enabled(if: clip != nil), arguments: [FilterOps.Who.women, .everyone])
    func portraitClip(who: FilterOps.Who) async throws {
        let source = try await MediaSource.probe(try #require(Self.clip))
        var ops = FilterOps()
        ops.censorTarget = .face
        ops.who = who
        ops.censorNsfw = false
        let analyzed = try await AnalyzePass.run(source, ops: ops)
        print("[face-blur \(who)] samples=\(analyzed.sampledFrames) tracks=\(analyzed.edl.faceTracks.count)")
        let artifacts = URL.documentsDirectory.appendingPathComponent("face-blur-regression", isDirectory: true)
        try FileManager.default.createDirectory(at: artifacts, withIntermediateDirectories: true)
        try analyzed.edl.toJSONData().write(to: artifacts.appendingPathComponent("\(who).json"))
        for time: Int64 in [10_000, 20_000, 30_000, 40_000, 50_000] {
            #expect(analyzed.edl.regions(at: time).isEmpty == false, "face uncovered at \(time) ms in \(who) mode")
        }
        let output = artifacts.appendingPathComponent("\(who).mp4")
        try? FileManager.default.removeItem(at: output)
        let rendered = try await RenderPass.run(source: source, edl: analyzed.edl, ops: ops, output: output)
        #expect(rendered.censoredFrames > rendered.frames * 9 / 10)
        // Inspect the girl's eyes/nose at 10 s, independently of the detected
        // boxes. Counting "censored" frames alone also passes a misplaced mask.
        let face = CGRect(x: 0.32, y: 0.28, width: 0.32, height: 0.23)
        let before = try await faceDetail(source.url, rect: face)
        let after = try await faceDetail(output, rect: face)
        #expect(before > 0)
        #expect(after * 10 < before * 6, "face detail unchanged: \(before) → \(after)")
        print("[face-blur \(who)] rendered=\(rendered.frames) censored=\(rendered.censoredFrames) output=\(output.path)")
    }

    private func faceDetail(_ url: URL, rect: CGRect) async throws -> Int {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let (image, _) = try await generator.image(at: CMTime(seconds: 10, preferredTimescale: 600))
        let crop = try #require(image.cropping(to: CGRect(
            x: rect.minX * CGFloat(image.width), y: rect.minY * CGFloat(image.height),
            width: rect.width * CGFloat(image.width), height: rect.height * CGFloat(image.height))))
        let side = 128
        var pixels = [UInt8](repeating: 0, count: side * side * 4)
        try pixels.withUnsafeMutableBytes { bytes in
            let context = try #require(CGContext(
                data: bytes.baseAddress, width: side, height: side, bitsPerComponent: 8,
                bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(crop, in: CGRect(x: 0, y: 0, width: side, height: side))
        }
        var detail = 0
        for y in 0..<(side - 1) {
            for x in 0..<(side - 1) {
                let i = (y * side + x) * 4
                for channel in 0..<3 {
                    detail += abs(Int(pixels[i + channel]) - Int(pixels[i + 4 + channel]))
                    detail += abs(Int(pixels[i + channel]) - Int(pixels[i + side * 4 + channel]))
                }
            }
        }
        return detail
    }
}
