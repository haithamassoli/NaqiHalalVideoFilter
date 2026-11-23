import AVFoundation
import CoreMedia
import Foundation
import Testing
@testable import naqi

/// Ported from Android `CutPointsTest.kt`, plus the iOS-only share batching.
@Suite("Publish presets")
struct PublishPresetTests {
    private func s(_ seconds: Int64) -> CMTime { CMTime(value: seconds, timescale: 1) }
    private func every(_ step: Int64, until: Int64) -> [CMTime] {
        stride(from: 0, through: until, by: Int(step)).map { s(Int64($0)) }
    }

    @Test func shortVideoIsOnePart() {
        #expect(cutPoints(keyframes: every(2, until: 80), duration: s(80), max: s(90)) == [s(0)])
    }

    @Test func cutsOnLatestKeyframeWithinLimit() {
        // Keyframes every 4 s: 88 s is the last one inside 90 s, then 176 s.
        #expect(cutPoints(keyframes: every(4, until: 200), duration: s(200), max: s(90))
                == [s(0), s(88), s(176)])
    }

    @Test func exactMultipleNeedsNoExtraPart() {
        #expect(cutPoints(keyframes: every(10, until: 180), duration: s(180), max: s(90)) == [s(0), s(90)])
    }

    @Test func keyframeGapLongerThanLimitRunsLongInsteadOfLooping() {
        #expect(cutPoints(keyframes: [s(0), s(120)], duration: s(200), max: s(90)) == [s(0), s(120)])
    }

    @Test func noKeyframesAfterStartStops() {
        #expect(cutPoints(keyframes: [s(0)], duration: s(500), max: s(90)) == [s(0)])
    }

    @Test func requiresSplittingFollowsTheLimit() throws {
        let whatsapp = try #require(PublishPreset.all.first { $0.id == "whatsapp-status" })
        #expect(!whatsapp.requiresSplitting(.seconds(90)))
        #expect(whatsapp.requiresSplitting(.milliseconds(90_001)))
        let tiktok = try #require(PublishPreset.all.first { $0.id == "tiktok" })
        #expect(!tiktok.requiresSplitting(.seconds(3600)))
    }

    @Test func batchesRespectTheShareCap() {
        let batches = shareBatches(Array(1...35), max: 30)
        #expect(batches.map(Array.init) == [Array(1...30), Array(31...35)])
        #expect(shareBatches(Array(1...5), max: nil).count == 1)
    }
}

/// On the QA clip, whose only keyframes are 0 s and 8.33 s of 12.5 s.
@Suite("Splitter", .serialized)
struct SplitterTests {
    private func seconds(_ url: URL) async throws -> (asset: Double, video: Double) {
        let asset = AVURLAsset(url: url)
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        return (try await asset.load(.duration).seconds, try await track.load(.timeRange).duration.seconds)
    }

    @Test func keyframesAreInPresentationTime() async throws {
        let track = try #require(try await AVURLAsset(url: try requireQAVideo())
            .loadTracks(withMediaType: .video).first)
        let times = try await Splitter.keyframes(track).map(\.seconds)
        #expect(times.count == 2)
        #expect(abs(times[0]) < 0.001)
        #expect(abs(times[1] - 8.333) < 0.01)
    }

    @Test func partsAreContiguousAndWithinTheLimit() async throws {
        let source = try requireQAVideo()
        let stem = "splitter-\(UUID().uuidString)"
        let preset = PublishPreset.custom(seconds: 10)
        let parts = try await Splitter.split(source, stem: stem, preset: preset)
        defer { Splitter.delete(parts) }

        #expect(parts.count == 2)
        #expect(Splitter.existingParts(stem: stem, preset: preset) == parts)
        var total = 0.0
        for part in parts {
            let (asset, video) = try await seconds(part)
            #expect(asset <= 10)
            #expect(video <= 10)
            total += video
        }
        let first = try await seconds(parts[0]).video
        #expect(abs(first - 8.333) < 0.05)
        #expect(abs(total - (try await seconds(source).video)) < 0.05)

        Splitter.delete(parts)
        #expect(Splitter.existingParts(stem: stem, preset: preset).isEmpty)
    }

    @Test func cancelLeavesNoParts() async throws {
        let source = try requireQAVideo()
        let stem = "splitter-\(UUID().uuidString)"
        let preset = PublishPreset.custom(seconds: 10)
        let task = Task { try await Splitter.split(source, stem: stem, preset: preset) }
        task.cancel()
        _ = try? await task.value
        #expect(Splitter.existingParts(stem: stem, preset: preset).isEmpty)
    }

    @Test func passThroughPresetWritesNothing() async throws {
        let stem = "splitter-\(UUID().uuidString)"
        let telegram = try #require(PublishPreset.all.first { $0.id == "telegram" })
        #expect(try await Splitter.split(try requireQAVideo(), stem: stem, preset: telegram).isEmpty)
        #expect(Splitter.existingParts(stem: stem, preset: telegram).isEmpty)
    }
}
