import AVFoundation
import CoreMedia
import Foundation
import os

/// The music-removal job: decode → htdemucs → AAC-LC, muxed alongside the
/// source's video track copied **compressed**, sample for sample. On this job
/// shape the audio is the entire wall, so the video side must cost nothing.
///
/// Both tracks run concurrently against one `AVAssetWriter`. Backpressure is the
/// encoder's: `pump` stops asking for audio while the AAC converter is
/// saturated, so a feature-length job never buffers more than one flush batch.
enum AudioPipeline {

    struct Result: Sendable {
        let frames: Int
        /// Model samples replaced with silence. Non-zero means the fp16 graph
        /// produced NaN somewhere and the output has holes in it.
        let nonFinite: Int
        let separateMs: Double
        /// Separation speed as a multiple of realtime. The Android S23 baseline
        /// on this stage is 0.55×.
        let xRealtime: Double
        /// Where the written audio track actually starts, and how long it is.
        /// AAC priming is ~2048 samples = 46.4 ms at 44.1 kHz; Apple compensates
        /// it with an edit list, Android left it in. The PRD budget is 50 ms.
        let audioStartMs: Double
        let audioDurationMs: Double
    }

    /// - Parameter progress: 0…1 over the separation stage, called from the
    ///   writer's private queue and only when the integer percent moves.
    /// - Parameter includeVideo: copy the source's video track through into
    ///   `output`. A music-only job wants that — it *is* the finished file. A
    ///   both-ops job does not: it only needs this output's audio track, which
    ///   it hands to `RenderPass.run(replacedAudio:)`, so passing `true` there
    ///   would transmux the whole video into a temp that is then discarded —
    ///   a full-size wasted copy on a feature film (Android hit the same thing,
    ///   `video-performance-plan-v2.md` 5.9 S2).
    static func removeMusic(_ src: MediaSource,
                            to output: URL,
                            keepStems: FilterOps.KeepStems = .vocals,
                            includeVideo: Bool = true,
                            useMusicGate: Bool = false,
                            progress: @escaping @Sendable (Double) -> Void = { _ in },
                            isCancelled: @escaping @Sendable () -> Bool = { false }) async throws -> Result {
        // Shared lifetime boundary: every caller and every exit path releases
        // the heavy graph, including audio-only jobs that bypass JobRunner's
        // checkpoint helper.
        defer { ModelRegistry.evict(Models.Demucs.file) }
        let started = ContinuousClock.now
        let job = Stage("audio.job")
        let asset = AVURLAsset(url: src.url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        guard let audioTrack = try await asset.loadTracks(withMediaType: .audio).first else {
            throw MediaError.noAudioTrack
        }
        nonisolated(unsafe) let aTrack = audioTrack
        nonisolated(unsafe) let vTrack = includeVideo
            ? try await asset.loadTracks(withMediaType: .video).first : nil

        // Pass 1: the whole-track scalars the separator normalizes by. Blocking
        // decode, so it stays off the cooperative pool.
        let duration = src.duration
        let stats = try await Task.detached(priority: .userInitiated) {
            try AudioStats.measure(track: aTrack, duration: duration, isCancelled: isCancelled)
        }.value

        // The transcoded track lands where the source's did, so any inter-track
        // offset the container carried survives.
        let anchor = max(.zero, try await audioTrack.load(.timeRange).start)

        let writer = try OutputWriter(url: output, fileType: .mp4)
        if let v = src.video, vTrack != nil { writer.addPassthroughVideo(v) }
        writer.addEncodedAudio(sampleRate: Double(Models.Demucs.sampleRate), channels: 2,
                               sourceBitrate: 192_000)
        try writer.start()

        let stop = OSAllocatedUnfairLock(initialState: false)
        @Sendable func aborted() -> Bool { isCancelled() || stop.withLock { $0 } }

        let sep = Confined<Sep?>(nil)
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                if vTrack != nil, writer.videoInput != nil {
                    nonisolated(unsafe) let vt = vTrack!
                    nonisolated(unsafe) let vIn = writer.videoInput!
                    group.addTask {
                        try await copyTrackPassthrough(track: vt, into: vIn,
                                                       label: "video", isCancelled: aborted)
                    }
                }
                nonisolated(unsafe) let aIn = writer.audioInput!
                let frames = Int(duration.seconds * Double(Models.Demucs.sampleRate))
                group.addTask {
                    sep.v = try await separate(track: aTrack, into: aIn, stats: stats,
                                               keepStems: keepStems.stems, estimatedFrames: frames,
                                               anchor: anchor, useMusicGate: useMusicGate,
                                               progress: progress, isCancelled: aborted)
                }
                // The other branch's pump loop does not observe task
                // cancellation, so the flag has to be raised before waiting on
                // it or a failure on one side hangs the group.
                var first: Error?
                while !group.isEmpty {
                    do { try await group.next() }
                    catch {
                        if first == nil { first = error }
                        stop.withLock { $0 = true }
                    }
                }
                if let first { throw first }
            }
            try await writer.finish()
        } catch {
            writer.cancel()
            throw error
        }

        guard let s = sep.v else { throw MediaError.writerFailed("separator produced no result") }
        let (startMs, durMs) = await trackTiming(output)
        let x = s.ms > 0 ? Double(s.frames) / Double(Models.Demucs.sampleRate) / (s.ms / 1000) : 0
        // Apple writes an edit list that trims AAC priming, so this should land
        // on the anchor. Android's output landed a constant 42.67 ms late and
        // called it convention; a drift here is a real A/V sync bug.
        let drift = startMs - anchor.seconds * 1000
        if abs(drift) > 10 {
            Log.audio.warning("audio track starts \(drift, format: .fixed(precision: 2))ms off the source anchor")
        }
        job.stop("""
            wall=\(Int(msSince(started)))ms frames=\(s.frames) nonFinite=\(s.nonFinite) \
            \(String(format: "%.2f", x))x-realtime
            """)
        Log.audio.info("""
            separate \(s.ms / 1000, format: .fixed(precision: 2))s for \
            \(Double(s.frames) / Double(Models.Demucs.sampleRate), format: .fixed(precision: 2))s audio \
            = \(x, format: .fixed(precision: 2))x realtime; \
            out start=\(startMs, format: .fixed(precision: 2))ms dur=\(durMs, format: .fixed(precision: 1))ms
            """)
        return Result(frames: s.frames, nonFinite: s.nonFinite, separateMs: s.ms, xRealtime: x,
                      audioStartMs: startMs, audioDurationMs: durMs)
    }

    private struct Sep { let frames: Int; let nonFinite: Int; let ms: Double }

    /// Decode → separator → AAC input, all on the writer input's own serial
    /// queue. Cancellation is polled once per decoded buffer, i.e. far inside
    /// the one-chunk latency the PRD allows.
    private static func separate(track: AVAssetTrack, into input: AVAssetWriterInput,
                                 stats: AudioStats, keepStems: [Models.Demucs.Stem],
                                 estimatedFrames: Int, anchor: CMTime,
                                 useMusicGate: Bool,
                                 progress: @escaping @Sendable (Double) -> Void,
                                 isCancelled: @escaping @Sendable () -> Bool) async throws -> Sep {
        nonisolated(unsafe) let decoder = try AudioDecoder(track: track)
        try decoder.start()
        // On only for sources of 10 min or more (JobRunner.separate): YAMNet can
        // miss quiet music under speech, a risk worth taking only where the skips
        // save minutes.
        let gate = useMusicGate ? MusicGate.open() : nil
        defer { if gate != nil { ModelRegistry.evict(Models.YamNet.file) } }
        let session = Confined<DemucsSession?>(nil)
        let format = try lpcmFormat()
        nonisolated(unsafe) let sink = input

        let pending = Confined<[CMSampleBuffer]>([])
        let samplesOut = Confined(0)
        let lastPercent = Confined(-1)
        let score: Demucs.MusicScore? = gate.map { gate in
            { try gate.score($0, frames: $1) }
        }
        let infer: Demucs.Infer = { wav, spec, specSum, timeSum in
            if session.v == nil { session.v = try DemucsSession(keepStems: keepStems) }
            try session.v!.run(wav, spec, specSum, timeSum)
        }
        nonisolated(unsafe) let separator = Demucs(
            mean: stats.mean, std: stats.std, estimatedFrames: estimatedFrames,
            infer: infer,
            musicScore: score,
            onChunk: { done, total in
                // ~4800 chunks map onto 100 values; posting every one measured
                // −12.2 % on Android.
                let pct = 100 * done / max(total, 1)
                guard pct != lastPercent.v else { return }
                lastPercent.v = pct
                progress(Double(pct) / 100)
            },
            emit: { pcm, frames in
                let pts = CMTimeAdd(anchor, CMTime(value: CMTimeValue(samplesOut.v),
                                                   timescale: CMTimeScale(Models.Demucs.sampleRate)))
                pending.v.append(try sampleBuffer(pcm, frames: frames, pts: pts, format: format))
                samplesOut.v += frames
            })

        let drained = Confined(false)
        // Own clock because `stop()` consumes the `Stage`, and the wall is part
        // of this function's return value, not just of the log line.
        let started = ContinuousClock.now
        let stage = Stage("audio.separate")
        try await pump(input, label: "audio") {
            if isCancelled() { throw MediaError.cancelled }
            if !pending.v.isEmpty {
                guard sink.append(pending.v.removeFirst()) else {
                    throw MediaError.writerFailed("append audio")
                }
                return true
            }
            if drained.v { return false }
            if let (pcm, frames) = try decoder.next() {
                try separator.feed(pcm, frames: frames)
            } else {
                try separator.finish()
                drained.v = true
            }
            return true
        }
        let ms = msSince(started)
        stage.stop("""
            wall=\(Int(ms))ms chunks=\(separator.chunksDone) stft=\(Int(separator.stftMs))ms \
            ort=\(Int(separator.inferMs))ms gate=\(Int(separator.gateMs))ms \
            skipped=\(separator.skippedChunks)/\(separator.chunksDone) ola=\(Int(separator.olaMs))ms
            """)
        precondition(separator.emitted == separator.framesFed,
                     "emitted \(separator.emitted) != fed \(separator.framesFed)")
        return Sep(frames: separator.emitted, nonFinite: separator.nonFinite, ms: ms)
    }

    /// Interleaved f32 stereo at 44.1 kHz — what the separator emits, and what
    /// the writer's AAC converter takes as input.
    private static func lpcmFormat() throws -> CMAudioFormatDescription {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: Double(Models.Demucs.sampleRate),
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 8, mFramesPerPacket: 1, mBytesPerFrame: 8,
            mChannelsPerFrame: 2, mBitsPerChannel: 32, mReserved: 0)
        var fmt: CMAudioFormatDescription?
        let st = CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: &asbd,
                                                layoutSize: 0, layout: nil, magicCookieSize: 0,
                                                magicCookie: nil, extensions: nil,
                                                formatDescriptionOut: &fmt)
        guard st == noErr, let fmt else { throw MediaError.writerFailed("CMAudioFormatDescription \(st)") }
        return fmt
    }

    private static func sampleBuffer(_ pcm: UnsafePointer<Float>, frames: Int, pts: CMTime,
                                     format: CMAudioFormatDescription) throws -> CMSampleBuffer {
        let bytes = frames * 2 * MemoryLayout<Float>.size
        var block: CMBlockBuffer?
        var st = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: bytes,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
            offsetToData: 0, dataLength: bytes, flags: kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut: &block)
        guard st == noErr, let block else { throw MediaError.writerFailed("CMBlockBuffer \(st)") }
        st = CMBlockBufferReplaceDataBytes(with: pcm, blockBuffer: block,
                                           offsetIntoDestination: 0, dataLength: bytes)
        guard st == noErr else { throw MediaError.writerFailed("CMBlockBufferReplaceDataBytes \(st)") }

        var sb: CMSampleBuffer?
        st = CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format,
            sampleCount: frames, presentationTimeStamp: pts,
            packetDescriptions: nil, sampleBufferOut: &sb)
        guard st == noErr, let sb else { throw MediaError.writerFailed("CMSampleBuffer \(st)") }
        return sb
    }

    private static func trackTiming(_ url: URL) async -> (startMs: Double, durationMs: Double) {
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
        guard let track = try? await asset.loadTracks(withMediaType: .audio).first,
              let range = try? await track.load(.timeRange) else { return (0, 0) }
        return (range.start.seconds * 1000, range.duration.seconds * 1000)
    }
}
