import AVFoundation
import CoreMedia
import Foundation
import os

enum MediaError: Error, CustomStringConvertible {
    case noVideoTrack
    case noAudioTrack
    case readerFailed(String)
    case writerFailed(String)
    case cancelled

    var description: String {
        switch self {
        case .noVideoTrack: "input has no video track"
        case .noAudioTrack: "input has no audio track"
        case .readerFailed(let s): "reader failed: \(s)"
        case .writerFailed(let s): "writer failed: \(s)"
        case .cancelled: "cancelled"
        }
    }
}

/// Bridges `AVAssetWriterInput.requestMediaDataWhenReady` to async/await.
///
/// `produce` is invoked repeatedly on a private serial queue while the input
/// wants data, and returns `false` when the track is finished. It is the only
/// place backpressure lives on the write side: AVFoundation stops calling it
/// while the encoder is saturated, which is what keeps a feature-length job from
/// buffering the whole file into RAM.
func pump(_ input: AVAssetWriterInput,
          label: String,
          produce: @escaping @Sendable () throws -> Bool) async throws {
    let queue = DispatchQueue(label: "naqi.pump.\(label)")
    nonisolated(unsafe) let input = input
    try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, any Error>) in
        // `requestMediaDataWhenReady` can re-enter, so the continuation is
        // guarded: it must be resumed exactly once.
        nonisolated(unsafe) var done = false
        input.requestMediaDataWhenReady(on: queue) {
            guard !done else { return }
            do {
                while input.isReadyForMoreMediaData {
                    if try !produce() {
                        done = true
                        input.markAsFinished()
                        k.resume()
                        return
                    }
                }
            } catch {
                done = true
                input.markAsFinished()
                k.resume(throwing: error)
            }
        }
    }
}

/// Owns the output container. Video and audio tracks are configured
/// independently so each can be passthrough or re-encoded, which is what makes
/// the three job shapes cheap: censor-only never touches the audio bytes,
/// music-only never touches the video bytes.
final class OutputWriter {
    let writer: AVAssetWriter
    private(set) var videoInput: AVAssetWriterInput?
    private(set) var audioInput: AVAssetWriterInput?
    private(set) var pixelAdaptor: AVAssetWriterInputPixelBufferAdaptor?

    init(url: URL, fileType: AVFileType = .mp4) throws {
        try? FileManager.default.removeItem(at: url)
        writer = try AVAssetWriter(outputURL: url, fileType: fileType)
        // AVAssetWriter writes co64 atoms itself once the movie exceeds 4 GiB,
        // so feature-length output needs no special handling here.
        writer.shouldOptimizeForNetworkUse = false
    }

    /// Compressed passthrough: samples are appended exactly as read, so the
    /// elementary stream is bit-identical to the source.
    func addPassthroughVideo(_ v: MediaSource.VideoInfo) {
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: nil,
                                       sourceFormatHint: v.formatDescription)
        input.expectsMediaDataInRealTime = false
        input.transform = v.transform.toUpright
        writer.add(input)
        videoInput = input
    }

    func addEncodedVideo(_ v: MediaSource.VideoInfo, bitrate: Int) {
        let input = AVAssetWriterInput(mediaType: .video,
                                       outputSettings: EncodeSettings.videoSettings(for: v, bitrate: bitrate))
        input.expectsMediaDataInRealTime = false
        // Carry the source's timescale. The writer otherwise rescales every PTS
        // to its own default, which is lossless at exactly 30 fps and lossy for
        // 30000/1001 content. Must be set before startWriting() — it cannot be
        // changed afterwards.
        if v.naturalTimeScale > 0 { input.mediaTimeScale = v.naturalTimeScale }
        // Rotation is carried as track metadata, exactly as the source did:
        // the pixels stay in stored orientation, so no rotate-blit is needed.
        input.transform = v.transform.toUpright
        writer.add(input)
        videoInput = input
        pixelAdaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: input,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: Int(v.naturalSize.width),
                kCVPixelBufferHeightKey as String: Int(v.naturalSize.height),
                kCVPixelBufferIOSurfacePropertiesKey as String: [:],
            ])
    }

    func addPassthroughAudio(_ a: MediaSource.AudioInfo) {
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil,
                                       sourceFormatHint: a.formatDescription)
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        audioInput = input
    }

    func addEncodedAudio(sampleRate: Double, channels: Int, sourceBitrate: Float) {
        let input = AVAssetWriterInput(
            mediaType: .audio,
            outputSettings: EncodeSettings.audioSettings(sampleRate: sampleRate, channels: channels,
                                                  sourceBitrate: sourceBitrate))
        input.expectsMediaDataInRealTime = false
        writer.add(input)
        audioInput = input
    }

    func start() throws {
        guard writer.startWriting() else {
            throw MediaError.writerFailed(writer.error?.localizedDescription ?? "startWriting")
        }
        writer.startSession(atSourceTime: .zero)
    }

    func finish() async throws {
        await writer.finishWriting()
        if writer.status != .completed {
            throw MediaError.writerFailed(writer.error?.localizedDescription ?? "\(writer.status.rawValue)")
        }
    }

    /// Cancel leaves no partial file behind — the PRD requires it on every
    /// cancel path.
    func cancel() {
        writer.cancelWriting()
        try? FileManager.default.removeItem(at: writer.outputURL)
    }
}

/// Reads one track's samples. `compressed: true` yields untouched
/// `CMSampleBuffer`s for passthrough; `false` yields decoded pixel buffers.
final class TrackReader {
    let reader: AVAssetReader
    let output: AVAssetReaderOutput

    /// The asset is taken from the track, never passed in: `AVAssetReader`
    /// raises an ObjC exception (uncatchable from Swift) if the output's track
    /// belongs to a different `AVAsset` instance than the reader's.
    init(track: AVAssetTrack, settings: [String: Any]?) throws {
        guard let asset = track.asset else { throw MediaError.readerFailed("track has no asset") }
        reader = try AVAssetReader(asset: asset)
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: settings)
        // We drain in order and never seek backwards, so copying buffers out of
        // the decoder's pool is pure overhead.
        out.alwaysCopiesSampleData = false
        reader.add(out)
        output = out
    }

    /// Passthrough: compressed samples, no decode.
    static func compressed(track: AVAssetTrack) throws -> TrackReader {
        try TrackReader(track: track, settings: nil)
    }

    /// Decoder-native 4:2:0 — no CPU format hop, IOSurface-backed so Core Image
    /// and VideoToolbox can share the buffer (`perf-plan-v4` analyze wall).
    static func decodedVideo(track: AVAssetTrack, size: CGSize? = nil) throws -> TrackReader {
        var settings: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:],
        ]
        if let size {
            settings[kCVPixelBufferWidthKey as String] = Int(size.width)
            settings[kCVPixelBufferHeightKey as String] = Int(size.height)
        }
        return try TrackReader(track: track, settings: settings)
    }

    func start() throws {
        guard reader.startReading() else {
            throw MediaError.readerFailed(reader.error?.localizedDescription ?? "startReading")
        }
    }

    func next() -> CMSampleBuffer? { output.copyNextSampleBuffer() }

    func cancel() { reader.cancelReading() }

    /// Surfaces a mid-stream decode failure that `copyNextSampleBuffer`
    /// signals only by returning nil.
    func throwIfFailed() throws {
        if reader.status == .failed {
            throw MediaError.readerFailed(reader.error?.localizedDescription ?? "unknown")
        }
    }
}

// MARK: - Whole-track copy

/// Copies one track compressed, sample for sample. Used for the passthrough
/// fast paths on both job shapes that have one.
func copyTrackPassthrough(track: AVAssetTrack,
                          into input: AVAssetWriterInput,
                          label: String,
                          isCancelled: @escaping @Sendable () -> Bool = { false }) async throws {
    let reader = try TrackReader.compressed(track: track)
    try reader.start()
    nonisolated(unsafe) let r = reader
    nonisolated(unsafe) let sink = input
    let count = Confined(0)
    try await pump(input, label: label) {
        if isCancelled() { throw MediaError.cancelled }
        guard let sb = r.next() else {
            try r.throwIfFailed()
            return false
        }
        guard sink.append(sb) else {
            throw MediaError.writerFailed("append \(label) at sample \(count.v)")
        }
        count.v += 1
        return true
    }
    Log.media.info("passthrough \(label, privacy: .public): \(count.v) samples")
}
