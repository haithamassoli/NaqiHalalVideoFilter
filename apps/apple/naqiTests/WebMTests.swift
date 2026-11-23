import AVFoundation
import CryptoKit
import Foundation
import os
import Testing
@testable import naqi

@Suite("WebM import", .serialized)
struct WebMTests {
    // Fixtures contain 1.2 s of FFmpeg testsrc2 at 10 fps and a 440 Hz tone.
    private final class BundleToken {}

    private func fixture(_ name: String) throws -> URL {
        try #require(Bundle(for: BundleToken.self).url(forResource: name, withExtension: "webm"))
    }

    private func directory() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("webm-\(UUID())")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    @Test("Converted WebM decodes both tracks and preserves the original",
          arguments: ["vp9-opus", "vp8-vorbis", "opus-audio", "no-duration"])
    func conversion(_ name: String) async throws {
        let source = try fixture(name)
        let before = SHA256.hash(data: try Data(contentsOf: source))
        let info = try await WebM.metadata(source)
        let dir = try directory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let output = try await WebM.prepare(source, in: dir)
        let media = try await MediaSource.probe(output)
        #expect(output != source && output.pathExtension == "mp4")
        #expect((media.video != nil) == (name != "opus-audio"))
        #expect(media.hasAudio && media.duration.seconds > 1 && media.duration.seconds < 1.5)
        #expect(info.hasVideo == (media.video != nil))
        try await verifyDecoding(output, video: media.video != nil)
        #expect(SHA256.hash(data: try Data(contentsOf: source)) == before)

        // A completed conversion is reused on resume, rather than re-encoded.
        let stamp = try FileManager.default.attributesOfItem(atPath: output.path)[.modificationDate] as? Date
        #expect(try await WebM.prepare(source, in: dir) == output)
        #expect(try FileManager.default.attributesOfItem(atPath: output.path)[.modificationDate] as? Date == stamp)
        #expect(!FileManager.default.fileExists(atPath: dir.appendingPathComponent("webm-converting.mp4").path))
    }

    @Test("Cancelled and invalid imports leave no converted file")
    func failures() async throws {
        let dir = try directory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let source = try fixture("vp9-opus")
        let cancelled = OSAllocatedUnfairLock(initialState: false)
        await #expect(throws: CancellationError.self) {
            try await WebM.prepare(source, in: dir,
                                   onProgress: { _ in cancelled.withLock { $0 = true } },
                                   isCancelled: { cancelled.withLock { $0 } })
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path).isEmpty)
        let invalid = dir.appendingPathComponent("invalid.WEBM")
        try Data("not a WebM".utf8).write(to: invalid)
        await #expect(throws: PreflightFailure.sourceUnreadable) {
            try await WebM.prepare(invalid, in: dir)
        }
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path) == [invalid.lastPathComponent])
        #expect(try await WebM.prepare(dir.appendingPathComponent("unchanged.mp4"), in: dir)
                == dir.appendingPathComponent("unchanged.mp4"))
    }

    @Test("Job converts, renders and publishes WebM with the original filename")
    func job() async throws {
        let folder = try directory()
        defer { try? FileManager.default.removeItem(at: folder) }
        let original = try fixture("vp9-opus")
        let source = folder.appendingPathComponent("holiday.WEBM")
        try FileManager.default.copyItem(at: original, to: source)
        let before = SHA256.hash(data: try Data(contentsOf: source))
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        let job = Job.capture(source: source, ops: ops, destination: .userFolder, folder: folder)
        let opened = try job.openSource()
        let key = Checkpoint.key(source: opened.url, ops: ops)
        opened.close()
        defer { WorkDir.clear(key) }
        // Supply verified analysis so this exercises real conversion/render/export without ML models.
        try Checkpoint.writeEdl(Edl(censorIntervalsMs: [0...2_000]), dir: WorkDir.job(key))
        #expect(Checkpoint.readEdl(dir: WorkDir.job(key)) != nil)
        let stages = OSAllocatedUnfairLock<Set<Job.Stage>>(initialState: [])
        let result = try await JobRunner.run(job, progress: { p in
            if let stage = p.stage { stages.withLock { _ = $0.insert(stage) } }
        })
        let output = try #require(result.output.url)
        #expect(output.lastPathComponent.hasPrefix("holiday"))
        #expect(output.pathExtension == "mp4")
        let observed = stages.withLock { $0 }
        #expect(observed.contains(.convert))
        #expect(observed.contains(.publish))
        try await verifyDecoding(output, video: true)
        #expect(SHA256.hash(data: try Data(contentsOf: source)) == before)
        #expect(!FileManager.default.fileExists(atPath: WorkDir.root.appendingPathComponent(key).path))
    }

    @Test("AV1 WebM is copied into an MP4 the system can decode")
    func av1Copy() async throws {
        let source = try fixture("av1-opus")
        let info = try await WebM.metadata(source)
        #expect(info.videoCodec == "av1" && info.copiesVideo)
        let dir = try directory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let output = try await WebM.prepare(source, in: dir)
        let media = try await MediaSource.probe(output)
        #expect(media.video != nil && media.hasAudio)
        #expect(media.duration.seconds > 1 && media.duration.seconds < 1.5)
        try await verifyDecoding(output, video: true, minimumFrames: 12, exact: false)
    }

    /// The sample is a YouTube AV1 WebM. The test runs only when that file is present.
    private static let localAV1Sample: URL = {
        if let path = ProcessInfo.processInfo.environment["NAQI_WEBM"] {
            return URL(fileURLWithPath: path)
        }
        return URL.homeDirectory
            .appendingPathComponent("Downloads/Telegram Desktop")
            .appendingPathComponent("a week in my life vlog [kaPgGH5HC0w]-00.00.00.000-00.04.29.221.webm")
    }()

    @Test("a local AV1 WebM remuxes to a file the system can decode",
          .enabled(if: FileManager.default.fileExists(atPath: localAV1Sample.path)))
    func localAV1() async throws {
        let source = Self.localAV1Sample
        let before = SHA256.hash(data: try Data(contentsOf: source))
        let info = try await WebM.metadata(source)
        #expect(info.videoCodec == "av1" && info.copiesVideo && info.duration > 260)
        let dir = try directory()
        defer { try? FileManager.default.removeItem(at: dir) }
        let output = try await WebM.prepare(source, in: dir)
        let media = try await MediaSource.probe(output)
        #expect(media.video != nil && media.hasAudio)
        #expect(abs(media.duration.seconds - info.duration) < 1)
        try await verifyDecoding(output, video: true, minimumFrames: 30, limit: 30, exact: false)
        #expect(SHA256.hash(data: try Data(contentsOf: source)) == before)
    }

    private func verifyDecoding(_ url: URL, video: Bool, minimumFrames: Int = 12,
                                limit: Int? = nil, exact: Bool = true) async throws {
        let asset = AVURLAsset(url: url)
        if video {
            let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
            let reader = try TrackReader.decodedVideo(track: track)
            try reader.start()
            var frames = 0
            while reader.next() != nil {
                frames += 1
                if let limit, frames >= limit { break }
            }
            try reader.throwIfFailed()
            #expect(frames >= minimumFrames)
            if exact { #expect(frames == minimumFrames) }
        }
        let audio = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        let reader = try AudioDecoder(track: audio)
        try reader.start()
        var frames = 0
        while let samples = try reader.next() { frames += samples.frames }
        #expect(frames > 44_100)
    }
}
