import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import os

/// Pass 2: decode → censor → encode, driven by the EDL.
///
/// The loop is encoder-paced. `pump` only asks for a frame while the writer
/// wants one, so nothing buffers and the effect can never run ahead of
/// VideoToolbox — which is also why the effect must stay cheap enough not to
/// become the pacer itself.
enum RenderPass {

    /// Job-scoped render state. Segmented jobs reuse this so Core Image keeps
    /// its compiled kernels and Fast mode resolves geometry exactly once.
    final class Context: @unchecked Sendable {
        let video: MediaSource.VideoInfo
        let effect: CensorEffect

        init(source: MediaSource, ops: FilterOps) throws {
            guard let input = source.video else { throw MediaError.noVideoTrack }
            video = input.capped(shortSide: ops.processingMode.outputShortSideCap)
            effect = CensorEffect(ops: ops, transform: video.transform, tonemapHDR: input.isHDR)
        }
    }

    struct Result: Sendable {
        let frames: Int
        /// Frames that actually went through Core Image. The rest went to the
        /// encoder as the decoder handed them over.
        let censoredFrames: Int
        let wallMs: Double
        var framesPerSecond: Double { wallMs > 0 ? Double(frames) * 1000 / wallMs : 0 }
    }

    /// Renders `source` through the censor and writes one MP4 at `output`.
    ///
    /// - Parameter replacedAudio: **the both-ops seam.** A file the audio pass
    ///   has already finished writing, whose *first audio track* replaces the
    ///   source's. It is copied compressed, so it must already be in a
    ///   mux-compatible codec (AAC in `.m4a`/`.mp4`) — this pass never decodes
    ///   or re-encodes an audio sample. `nil` means censor-only: the source's
    ///   own audio track is copied instead, bit-identically.
    /// - Parameter edl: Consumed through `Edl.fullFrame(at:)` / `regions(at:)`,
    ///   so the whole-frame-beats-regions precedence rule is honoured by the
    ///   EDL itself, not re-derived here.
    /// - Parameter range: **the segmented seam.** Milliseconds on the SOURCE
    ///   timeline; `nil` renders the whole asset and is byte-for-byte the route
    ///   that shipped. When set, the output is a standalone file whose first
    ///   sample is at PTS 0 — see `renderVideo` for why the EDL is still read at
    ///   absolute time.
    ///
    ///   The upper bound behaves as **exclusive**: a frame landing exactly on
    ///   `upperBound` belongs to the next segment. That is what makes
    ///   `Checkpoint.plan`'s cuts — which share endpoints, `[a,b] [b,c] [c,d]` —
    ///   partition the film with no frame written twice and none lost.
    ///
    ///   A ranged render writes **no audio**, so `range` and `replacedAudio` are
    ///   mutually exclusive. Per-segment AAC cannot be concatenated: encoder
    ///   frames do not align with arbitrary clip boundaries, so the segmented
    ///   route renders picture alone and muxes one continuous track at the end
    ///   with `Remux.mux` (`spec-render.md` §4.2, Android `RenderPipeline.kt:66`).
    /// - Parameter endCard: appends `EndCard` after the last frame written,
    ///   picture only. Off by default so a caller has to ask for it: a segmented
    ///   job wants it on its last segment and nowhere else.
    static func run(source: MediaSource,
                    edl: Edl,
                    ops: FilterOps,
                    output: URL,
                    replacedAudio: URL? = nil,
                    range: ClosedRange<Int64>? = nil,
                    endCard: Bool = false,
                    context suppliedContext: Context? = nil,
                    progress: (@Sendable (Double) -> Void)? = nil,
                    isCancelled: @escaping @Sendable () -> Bool = { false }) async throws -> Result {
        let context = try suppliedContext ?? Context(source: source, ops: ops)
        let v = context.video
        let asset = AVURLAsset(url: source.url, options: [
            AVURLAssetPreferPreciseDurationAndTimingKey: true,
        ])
        // `AVAssetTrack.asset` is **weak** and `TrackReader` reads it back to
        // build its `AVAssetReader`. Nothing below this line touches `asset`
        // again, so an optimised build is free to release it the moment the
        // track loads — and then the reader fails with "track has no asset"
        // in Release only. Same guard as the replacement asset further down.
        defer { withExtendedLifetime(asset) {} }
        guard let videoTrack = try await asset.loadTracks(withMediaType: .video).first else {
            throw MediaError.noVideoTrack
        }

        let writer = try OutputWriter(url: output)
        writer.addEncodedVideo(v, bitrate: EncodeSettings.resolveBitrate(v))

        // `AVAssetTrack.asset` is weak and `TrackReader` reads it back, so the
        // replacement's asset has to outlive the pump — hence the local.
        var replacementAsset: AVURLAsset?
        defer { withExtendedLifetime(replacementAsset) {} }
        var audioTrack: AVAssetTrack?
        if range != nil {
            if replacedAudio != nil {
                Log.render.notice("segment render ignores replacedAudio: a segment is video-only")
            }
        } else if let replacedAudio {
            let replacement = try await MediaSource.probe(replacedAudio)
            let ra = AVURLAsset(url: replacedAudio)
            replacementAsset = ra
            guard let info = replacement.audio,
                  let t = try await ra.loadTracks(withMediaType: .audio).first
            else { throw MediaError.noAudioTrack }
            writer.addPassthroughAudio(info)
            audioTrack = t
        } else if let info = source.audio,
                  let t = try await asset.loadTracks(withMediaType: .audio).first {
            writer.addPassthroughAudio(info)
            audioTrack = t
        }
        try writer.start()

        let effect = context.effect
        Log.render.info("""
            render \(Int(v.naturalSize.width))x\(Int(v.naturalSize.height)) \
            rot=\(v.transform.rotationDegrees) hdr=\(v.isHDR) \
            blur=\(ops.blurAmount)(sigma \(effect.plan.sigmaPx, format: .fixed(precision: 1))px \
            /\(effect.plan.downscale)) gray=\(ops.grayscale) \
            audio=\(audioTrack == nil ? "none" : (replacedAudio == nil ? "passthrough" : "replaced"), privacy: .public) \
            range=\(range.map { "\($0.lowerBound)..<\($0.upperBound)ms" } ?? "whole", privacy: .public) \
            endCard=\(endCard)
            """)

        // AVFoundation objects are not Sendable but each is touched by exactly
        // one of the two child tasks below; the writer itself is thread-safe.
        nonisolated(unsafe) let vt = videoTrack
        nonisolated(unsafe) let at = audioTrack
        nonisolated(unsafe) let out = writer
        nonisolated(unsafe) let audioIn = writer.audioInput

        let stage = Stage("render")
        do {
            let card = endCard
                ? EndCard(transform: v.transform, sourceFrameRate: v.nominalFrameRate) : nil
            async let video = renderVideo(track: vt, writer: out, edl: edl, effect: effect,
                                          endCard: card,
                                          duration: source.duration, range: range,
                                          progress: progress, isCancelled: isCancelled)
            async let audio: Void = copyAudio(at, into: audioIn, isCancelled: isCancelled)
            let (counts, _) = try await (video, audio)
            try await out.finish()

            let r = Result(frames: counts.frames, censoredFrames: counts.censored,
                           wallMs: stage.elapsedMs)
            stage.stop("\(r.frames) frames (\(r.censoredFrames) censored) "
                + "\(String(format: "%.1f", r.framesPerSecond)) fps")
            return r
        } catch {
            // Cancel removes the partial file; the PRD requires that on every
            // failure path, not just user cancellation.
            out.cancel()
            throw error
        }
    }

    /// Slack the reader is given on **both** sides of the requested window, so
    /// that neither edge of the segment depends on how `AVAssetReader` chooses
    /// to trim. One second either way; the loop's own PTS tests are what
    /// actually define the window.
    ///
    /// **Tail** — Android's measurement. A B-frame stream's display order and
    /// decode order disagree, and a clip end that cut in decode order lost 1-3
    /// frames per seam (`spec-render.md` §6.5: 49 frames over 31 seams on a
    /// 2.6 h film). The loop breaks at the boundary frame, so the guard past it
    /// is never actually decoded and costs nothing.
    ///
    /// **Head** — measured here, and the reason this is not just a tail guard.
    /// `AVAssetReader` does not drop the sample that *straddles* `timeRange
    /// .start`; it emits it with its presentation timestamp **rewritten to the
    /// range start**. The pre-roll test below then reads exactly `lowerBound`
    /// for a frame that belongs to the previous segment, lets it through, and
    /// writes it into both — the join comes out one frame longer per seam, with
    /// everything after each seam shifted and the audio drifting against it.
    /// A frame-aligned cut hides this completely (the straddling frame *is* the
    /// boundary frame), which is why the seam fixture cuts at 3010 and not 3000
    /// — and at 29.97 or 23.976 fps no 5-minute cut is frame-aligned, so on that
    /// content every seam duplicated a frame. Starting a second early puts the
    /// rewritten sample somewhere the pre-roll test discards anyway.
    private static let readerGuardMs: Int64 = 1_000

    private static func renderVideo(track: AVAssetTrack,
                                    writer: OutputWriter,
                                    edl: Edl,
                                    effect: CensorEffect,
                                    endCard: EndCard?,
                                    duration: CMTime,
                                    range: ClosedRange<Int64>?,
                                    progress: (@Sendable (Double) -> Void)?,
                                    isCancelled: @escaping @Sendable () -> Bool)
    async throws -> (frames: Int, censored: Int) {
        guard let input = writer.videoInput, let adaptor = writer.pixelAdaptor else {
            throw MediaError.writerFailed("video input not configured")
        }
        let reader = try TrackReader.decodedVideo(track: track, size: effect.outputSize)
        if let range {
            let from = max(0, range.lowerBound - readerGuardMs)
            reader.reader.timeRange = CMTimeRange(
                start: CMTime(value: from, timescale: 1000),
                duration: CMTime(value: range.upperBound - from + readerGuardMs,
                                 timescale: 1000))
        }
        try reader.start()
        // A whole-asset pass reaches EOF and `AVAssetReader` tears its decoder
        // down on its own; a segment stops mid-stream at the boundary frame and
        // would otherwise hold a live decompression session and its 1080p buffer
        // pool until ARC happened to get to it — N of those in one job is real
        // memory. Cancelling a reader that already completed is a no-op.
        defer { reader.cancel() }

        nonisolated(unsafe) let r = reader
        nonisolated(unsafe) let sink = adaptor
        let counts = Confined((frames: 0, censored: 0))
        // Source time of the first frame this pass keeps. A segment is a
        // standalone file, so it is written from PTS 0 — but only the *write*
        // rebases. Resolved from the frame rather than from `range.lowerBound`
        // because a cut that is not on a sync sample leaves the first kept frame
        // some milliseconds late, and a segment starting at PTS 33 would make
        // `AVAssetWriter` open the file with a 33 ms hole.
        let base = Confined(CMTime.invalid)
        // Progress spans the window, not the film: the caller maps this pass's
        // 0...1 into its own band and does the per-segment arithmetic itself.
        let total = range.map { Double($0.upperBound - $0.lowerBound) } ?? duration.seconds * 1000
        let startMs = range?.lowerBound ?? 0
        // The last frame written and the instant it stops showing: the end
        // card dissolves in from the first and is timed from the second.
        let last = Confined((frame: CVPixelBuffer?.none, end: CMTime.zero))
        // Non-nil once the source is exhausted and the card is being written.
        let cardFrame = Confined<Int?>(nil)
        @Sendable func sourceDone() -> Bool {
            guard endCard != nil, counts.v.frames > 0 else { return false }
            cardFrame.v = 0
            return true
        }

        try await pump(input, label: "video") {
            if isCancelled() { throw MediaError.cancelled }
            if let endCard, let i = cardFrame.v {
                guard i < endCard.frameCount else { return false }
                guard let pool = sink.pixelBufferPool else {
                    throw MediaError.writerFailed("adaptor has no pixel buffer pool")
                }
                var dst: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &dst) == kCVReturnSuccess,
                      let dst else {
                    throw MediaError.writerFailed("pixel buffer pool exhausted at end card frame \(i)")
                }
                effect.render(endCard.image(frame: i, over: last.v.frame), to: dst, like: last.v.frame)
                guard sink.append(dst, withPresentationTime: endCard.time(of: i, after: last.v.end)) else {
                    throw MediaError.writerFailed("append end card frame \(i)")
                }
                cardFrame.v = i + 1
                return true
            }
            guard let sb = r.next() else {
                try r.throwIfFailed()
                return sourceDone()
            }
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            // The EDL is in whole milliseconds. Truncate rather than round, so a
            // frame lands in the same interval Android's integer `us / 1000`
            // put it in. This is the ABSOLUTE source time and stays absolute:
            // the EDL is whole-film even when the render is not.
            let ms = pts.value * 1000 / Int64(pts.timescale)
            if let range {
                // Pre-roll: a cut inside a GOP makes the reader decode from the
                // sync sample before it, and those frames belong to the previous
                // segment.
                if ms < range.lowerBound { return true }
                // Exclusive upper bound — see `run(range:)`. Decoded output
                // arrives in presentation order, so the first frame at or past
                // the boundary really is the end of this segment.
                if ms >= range.upperBound { return sourceDone() }
            }
            guard let src = CMSampleBufferGetImageBuffer(sb) else {
                throw MediaError.readerFailed("frame \(counts.v.frames) carries no image buffer")
            }
            if !base.v.isValid { base.v = pts }
            let outPts = range == nil ? pts : CMTimeSubtract(pts, base.v)
            let frameDuration = CMSampleBufferGetDuration(sb)

            let full = edl.fullFrame(at: ms)
            let (regions, masks) = full ? ([], []) : edl.coverage(at: ms)

            if effect.needsRender(wholeFrame: full, regions: regions, masks: masks) {
                guard let pool = sink.pixelBufferPool else {
                    throw MediaError.writerFailed("adaptor has no pixel buffer pool")
                }
                var dst: CVPixelBuffer?
                guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &dst) == kCVReturnSuccess,
                      let dst else {
                    throw MediaError.writerFailed("pixel buffer pool exhausted at frame \(counts.v.frames)")
                }
                effect.render(src, to: dst, wholeFrame: full, regions: regions, masks: masks)
                guard sink.append(dst, withPresentationTime: outPts) else {
                    throw MediaError.writerFailed("append censored frame \(counts.v.frames)")
                }
                if endCard != nil { last.v.frame = dst }
                counts.v.censored += 1
            } else {
                // Nothing to censor here: the decoder's own buffer goes straight
                // to the encoder. No Core Image, no pool allocation, no copy.
                guard sink.append(src, withPresentationTime: outPts) else {
                    throw MediaError.writerFailed("append frame \(counts.v.frames)")
                }
                if endCard != nil { last.v.frame = src }
            }
            if let endCard {
                last.v.end = CMTimeAdd(outPts, frameDuration.isValid && frameDuration > .zero
                    ? frameDuration : CMTime(seconds: 1 / endCard.fps, preferredTimescale: 90_000))
            }
            counts.v.frames += 1
            if let progress, total > 0, counts.v.frames % 30 == 0 {
                progress(min(1, Double(ms - startMs) / total))
            }
            return true
        }
        // An empty segment would finish as a file with a video track and no
        // samples: valid enough for `AVAssetWriter`, unreadable as a concat
        // input, and indistinguishable from a completed checkpoint once it is
        // renamed. This guard is what *caught* `Checkpoint.minSegmentMs`: a
        // 35:00.001 source planned a 1 ms trailing segment and every job on such
        // a film failed here. The plan no longer emits one, so this is back to
        // being the loud version of a silent failure.
        if let range, counts.v.frames == 0 {
            throw MediaError.readerFailed(
                "segment \(range.lowerBound)..<\(range.upperBound)ms decoded no frames")
        }
        return counts.v
    }

    private static func copyAudio(_ track: AVAssetTrack?,
                                  into input: AVAssetWriterInput?,
                                  isCancelled: @escaping @Sendable () -> Bool) async throws {
        guard let track, let input else { return }
        try await copyTrackPassthrough(track: track, into: input, label: "audio",
                                       isCancelled: isCancelled)
    }
}
