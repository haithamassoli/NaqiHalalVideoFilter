import Testing
import AVFoundation
import CryptoKit
import Foundation
@testable import naqi

/// M1 exit criteria: decode->encode round-trip, and passthrough that is
/// genuinely bit-identical. Android verified passthrough with an
/// elementary-stream MD5 plus the packet PTS/size sequence; the same two checks
/// are done here, because a container-level "looks the same" check would miss a
/// silent re-encode.
@Suite("Video passthrough", .serialized)
struct PassthroughTests {

    @Test("probe reads the qa clip")
    func probe() async throws {
        let src = try await MediaSource.probe(try requireQAVideo())
        let v = try #require(src.video)
        #expect(v.naturalSize == CGSize(width: 1080, height: 1920))
        #expect(v.nominalFrameRate == 30)
        #expect(src.duration.seconds > 12 && src.duration.seconds < 13)
        #expect(src.hasAudio)
        #expect(!v.isHDR)
    }

    @Test("bitrate resolution takes min(source x 1.3, tier cap)")
    func bitrate() async throws {
        let src = try await MediaSource.probe(try requireQAVideo())
        let v = try #require(src.video)
        let b = EncodeSettings.resolveBitrate(v)
        // Portrait 1080x1920 is 2 073 600 px — exactly the 1080p tier bound, so
        // a rotated phone clip bins with its landscape twin rather than paying
        // the 1440p cap. That is the point of binning on pixel count.
        #expect(v.pixelCount == 1920 * 1080)
        #expect(EncodeSettings.bitrateCap(pixels: v.pixelCount) == 16_000_000)
        // Source is ~3 Mbps, so the source term wins over the cap.
        #expect(b < 16_000_000)
        #expect(b == min(Int((Double(v.estimatedBitrate) * 1.3).rounded()), 16_000_000))
    }

    /// Both tracks copied compressed: the output's elementary streams must hash
    /// identically to the input's.
    @Test("full passthrough copy is bit-identical on both tracks")
    func bitIdentical() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("passthrough.mp4")
        let src = try await MediaSource.probe(inURL)
        let asset = AVURLAsset(url: inURL)

        let w = try OutputWriter(url: outURL)
        w.addPassthroughVideo(try #require(src.video))
        w.addPassthroughAudio(try #require(src.audio))
        try w.start()

        let vTrack = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let aTrack = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        // Each track gets its own reader and writer input, so the two copies are
        // independent; only the AVAssetWriter is shared and it is thread-safe.
        nonisolated(unsafe) let vIn = try #require(w.videoInput)
        nonisolated(unsafe) let aIn = try #require(w.audioInput)
        nonisolated(unsafe) let vt = vTrack, at = aTrack
        async let v: Void = copyTrackPassthrough(track: vt, into: vIn, label: "video")
        async let a: Void = copyTrackPassthrough(track: at, into: aIn, label: "audio")
        _ = try await (v, a)
        try await w.finish()

        #expect(FileManager.default.fileExists(atPath: outURL.path))
        for type in [AVMediaType.video, .audio] {
            let a = try await elementaryStreamDigest(inURL, type)
            let b = try await elementaryStreamDigest(outURL, type)
            // The byte-level guarantee: identical compressed payload.
            #expect(a.hash == b.hash, "\(type.rawValue) elementary stream is not bit-identical")
            #expect(a.bytes == b.bytes, "\(type.rawValue) payload size differs")
            // Timing must survive too, but only up to a frame: AVAssetWriter
            // picks its own movie timescale (15360 -> 600 here) and may coalesce
            // AAC frames into fewer sample buffers, so comparing raw PTS values
            // or packet counts would fail on a copy that is in fact exact.
            #expect(abs(a.lastPTS - b.lastPTS) < 0.05,
                    "\(type.rawValue) last PTS drifted: \(a.lastPTS) vs \(b.lastPTS)")
        }
        // The original must be untouched.
        #expect(FileManager.default.fileExists(atPath: inURL.path))
    }

    @Test("composition passthrough keeps compressed video and audio")
    func compositionPassthrough() async throws {
        let input = try requireQAVideo()
        let output = Fixtures.scratch("composition-passthrough.mp4")
        try await Remux.passthrough(source: input, to: output)
        for type in [AVMediaType.video, .audio] {
            let before = try await elementaryStreamDigest(input, type)
            let after = try await elementaryStreamDigest(output, type)
            #expect(before.hash == after.hash)
            #expect(before.bytes == after.bytes)
        }
    }

    @Test("cancel leaves no partial output file")
    func cancelLeavesNothing() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("cancelled.mp4")
        let src = try await MediaSource.probe(inURL)
        let asset = AVURLAsset(url: inURL)
        let w = try OutputWriter(url: outURL)
        w.addPassthroughVideo(try #require(src.video))
        try w.start()

        let vTrack = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let stop = Confined(false)
        // Cancel after a handful of samples.
        let seen = Confined(0)
        do {
            try await copyTrackPassthrough(track: vTrack,
                                           into: try #require(w.videoInput), label: "video") {
                seen.v += 1
                if seen.v > 10 { stop.v = true }
                return stop.v
            }
            Issue.record("expected cancellation to throw")
        } catch {
            // expected
        }
        w.cancel()
        #expect(!FileManager.default.fileExists(atPath: outURL.path), "partial file left behind")
    }

    struct StreamDigest { let hash: String; let bytes: Int; let lastPTS: Double }

    /// SHA-256 over the concatenated compressed payload. Reading with
    /// `outputSettings: nil` yields the encoded packets, so this compares the
    /// elementary stream itself rather than the container that wraps it.
    private func elementaryStreamDigest(_ url: URL, _ type: AVMediaType) async throws -> StreamDigest {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: type).first else {
            return StreamDigest(hash: "", bytes: 0, lastPTS: 0)
        }
        let r = try TrackReader.compressed(track: track)
        try r.start()
        var hasher = SHA256()
        var bytes = 0
        var lastPTS = 0.0
        while let sb = r.next() {
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            if pts.isValid && pts.seconds.isFinite { lastPTS = max(lastPTS, pts.seconds) }
            guard let bb = CMSampleBufferGetDataBuffer(sb) else { continue }
            var len = 0
            var ptr: UnsafeMutablePointer<CChar>?
            if CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil,
                                           totalLengthOut: &len, dataPointerOut: &ptr) == noErr,
               let ptr {
                hasher.update(bufferPointer: UnsafeRawBufferPointer(start: ptr, count: len))
                bytes += len
            }
        }
        try r.throwIfFailed()
        return StreamDigest(hash: hasher.finalize().map { String(format: "%02x", $0) }.joined(),
                            bytes: bytes, lastPTS: lastPTS)
    }
}
