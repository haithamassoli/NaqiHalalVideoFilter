import AVFoundation
import CoreImage
import Foundation
import Testing
@testable import naqi

struct WholeBodyRegressionTests {
    // Stage https://youtube.com/shorts/rX6wXhLqOIQ as
    // naqiTests/Fixtures/whole-body-regression.mp4 (gitignored).
    private static var clip: URL? {
        Bundle(for: BundleToken.self).url(forResource: "whole-body-regression", withExtension: "mp4")
    }
    private final class BundleToken {}

    @Test("body filtering covers small people on a camera display in the exported video",
          .enabled(if: clip != nil), arguments: [FilterOps.CensorTarget.body, .bodyPrecise])
    func cameraDisplay(target: FilterOps.CensorTarget) async throws {
        let source = try await MediaSource.probe(try #require(Self.clip))
        var ops = FilterOps()
        ops.censorTarget = target
        ops.who = .women
        ops.censorNsfw = false
        ops.solidColor = .black
        let analyzed = try await AnalyzePass.run(source, ops: ops)
        // The original pass left this entire shot uncovered. Check every
        // source frame, including the brief delay in detection after the cut.
        for frame in 220...292 {
            let time = Int64(frame * 1_000 / 30)
            #expect(analyzed.edl.fullFrame(at: time) || analyzed.edl.regions(at: time).isEmpty == false,
                    "camera display uncovered at \(time) ms")
        }
        for time: Int64 in [0, 2_000, 12_000, 17_000, 18_500, 22_000, 27_000, 32_000, 42_000, 44_000, 48_000, 52_000] {
            #expect(analyzed.edl.fullFrame(at: time) || analyzed.edl.regions(at: time).isEmpty == false)
        }
        // An independently marked face and torso at 9 s, rather than merely
        // checking that some unrelated rectangle was censored.
        let person = CGRect(x: 0.50, y: 0.59, width: 0.055, height: 0.085)
        #expect(analyzed.edl.fullFrame(at: 9_000) || analyzed.edl.regions(at: 9_000).contains {
            $0.rect(in: CGSize(width: 1, height: 1)).contains(person)
        })
        #expect(analyzed.edl.personMasks == (target == .bodyPrecise))
        let output = Fixtures.scratch("whole-body-camera-display-\(target.rawValue).mp4")
        defer { try? FileManager.default.removeItem(at: output) }
        let rendered = try await RenderPass.run(source: source, edl: analyzed.edl, ops: ops, output: output,
                                                range: 7_000...10_000)
        #expect(rendered.frames == 90)
        #expect(try await brightness(source.url, seconds: 9, rect: person) > 20)
        // A mask follows the outline, so it is checked on the body itself, and
        // must leave the bright screen beside the head visible.
        let body = target == .bodyPrecise ? CGRect(x: 0.51, y: 0.60, width: 0.035, height: 0.07) : person
        #expect(try await brightness(output, seconds: 2, rect: body) < 5,
                "the exported person must be covered by the selected opaque color")
        if target == .bodyPrecise {
            #expect(try await brightness(output, seconds: 2, rect: CGRect(x: 0.455, y: 0.565, width: 0.015, height: 0.015)) > 100)
        }
    }

    private func brightness(_ url: URL, seconds: Double, rect: CGRect) async throws -> Double {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let (image, _) = try await generator.image(at: CMTime(seconds: seconds, preferredTimescale: 600))
        let crop = CGRect(x: rect.minX * CGFloat(image.width), y: (1 - rect.maxY) * CGFloat(image.height),
                          width: rect.width * CGFloat(image.width), height: rect.height * CGFloat(image.height))
        let average = CIImage(cgImage: image).applyingFilter("CIAreaAverage", parameters: [
            kCIInputExtentKey: CIVector(cgRect: crop)
        ])
        var pixel = [UInt8](repeating: 0, count: 4)
        pixel.withUnsafeMutableBytes { bytes in
            CIContext().render(average, toBitmap: bytes.baseAddress!, rowBytes: 4,
                               bounds: CGRect(x: 0, y: 0, width: 1, height: 1),
                               format: .RGBA8, colorSpace: CGColorSpaceCreateDeviceRGB())
        }
        return pixel.prefix(3).reduce(0.0) { $0 + Double($1) } / 3
    }
}
