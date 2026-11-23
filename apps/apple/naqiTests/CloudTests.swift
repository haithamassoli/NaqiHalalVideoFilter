import Testing
import AVFoundation
import Foundation
@testable import naqi

@Suite("Cloud")
struct CloudTests {

    /// The server answers in Android's checkpoint shape; every piece has to land where the renderer
    /// reads it, or a long video comes back unblurred with nothing in the log to say why.
    @Test("server analysis maps onto person tracks, gate intervals and fallback intervals")
    func mapsAnalysis() throws {
        // One 2×2 mask: cells 0 and 3 set → 0b1001.
        let bits = Data([0b1001]).base64EncodedString()
        let json = """
        {"firingsMs": [10000],
         "edl": {"censorIntervalsMs": [[50000, 51000]],
                 "faceTracks": [{"startMs": 1700, "endMs": 3100, "union": true,
                                 "keyframes": [[2000, -0.1, 0.2, 0.5, 0.9], [3000, 0.2, 0.2, 0.6, 1.2]],
                                 "masks": [[0.0, 0.25, 0.45, 0.85, 2, 2, "\(bits)"], null]},
                                {"startMs": 9, "endMs": 5, "keyframes": [[9, 0.1, 0.1, 0.2, 0.2]]}]}}
        """
        var ops = FilterOps()
        ops.censor = true
        ops.censorTarget = .bodyPrecise
        let edl = try Cloud.edl(from: Data(json.utf8), ops: ops, durationMs: 600_000)

        #expect(edl.personTracks.count == 1, "the inverted track is dropped, not trusted")
        let tr = try #require(edl.personTracks.first)
        #expect(tr.geometry.startMs == 1700 && tr.geometry.endMs == 3100)
        #expect(tr.geometry.keyframes.map(\.rect.left) == [0, 0.2], "rects clamp into the frame")
        #expect(tr.geometry.keyframes[1].rect.bottom == 1)
        let mask = try #require(tr.geometry.keyframes[0].mask)
        #expect(mask[0, 0] && !mask[1, 0] && !mask[0, 1] && mask[1, 1])
        #expect(tr.geometry.keyframes[1].mask == nil)
        #expect(tr.shouldCensor(edl.personWho), "the server already applied the gender vote")
        #expect(edl.personMasks && !edl.personWholeFrame)

        #expect(edl.censorIntervalsMs.contains { $0.contains(10_000) }, "gate firing")
        #expect(edl.censorIntervalsMs.contains { $0.contains(50_500) }, "face outside every body")
        #expect(edl.fullFrame(at: 50_500) && !edl.fullFrame(at: 2_500))
        #expect(edl.coverage(at: 2_000).masks == [mask])
    }

    @Test("a malformed answer throws, so the caller falls back to the phone")
    func rejectsMalformed() {
        #expect(throws: (any Error).self) {
            try Cloud.edl(from: Data(#"{"edl": {}}"#.utf8), ops: FilterOps(), durationMs: 1)
        }
    }

    /// Live, against the deployed server, and only when asked:
    /// `TEST_RUNNER_NAQI_CLOUD_VIDEO=/path/to/10min.mp4 xcodebuild test -only-testing:naqiTests/CloudTests`.
    /// Writes `cloud-edl.json` and `cloud-result.m4a` next to the video for comparison with Android.
    @Test("a long video round-trips through the server",
          .enabled(if: ProcessInfo.processInfo.environment["NAQI_CLOUD_VIDEO"] != nil),
          .timeLimit(.minutes(30)))
    func live() async throws {
        let video = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["NAQI_CLOUD_VIDEO"]))
        let src = try await MediaSource.probe(video)
        var ops = FilterOps()
        ops.censor = true
        ops.who = .women
        ops.censorTarget = .bodyPrecise
        let audio = video.deletingLastPathComponent().appendingPathComponent("cloud-result.m4a")

        async let music = Cloud.removeMusic(src, keepStems: .vocals, to: audio,
                                            progress: { _ in }, isCancelled: { false })
        async let analysis = Cloud.analyze(src, ops: ops, progress: { _ in }, isCancelled: { false })
        #expect(try await music, "music came back from the server")
        let edl = try #require(try await analysis, "analysis came back from the server")
        try edl.toJSONData().write(to: video.deletingLastPathComponent().appendingPathComponent("cloud-edl.json"))

        let out = try await AVURLAsset(url: audio).load(.duration).seconds
        #expect(abs(out - src.duration.seconds) < 1)
        #expect(edl.personTracks.count > 100)
    }
}
