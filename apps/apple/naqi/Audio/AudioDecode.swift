import AVFoundation
import Accelerate
import CoreMedia
import Foundation
import os

/// Decodes one audio track to 44 100 Hz interleaved stereo f32 — htdemucs' rate,
/// and the rate everything downstream of here runs at. Nothing resamples again.
///
/// AVFoundation does the sample-rate conversion; the *channel* fold is ours. The
/// reader is asked for LPCM at the source's own channel count so anything wider
/// than stereo can go through ITU-R BS.775 with the centre at −3 dB. Letting
/// AVFoundation downmix would be one line shorter and would silently drop the
/// centre channel of a 5.1 film, which is where the dialogue lives — on Android
/// that returned an empty vocals stem on exactly the content the feature exists
/// for (spec-audio §5.2).
///
/// Streamed: one sample buffer at a time, into a reused fold buffer.
/// Thread-confined.
final class AudioDecoder {
    /// −3 dB, the ITU-R BS.775 coefficient for folding centre/surrounds into a
    /// stereo pair.
    static let halfPower: Float = 0.70710678

    /// Held strongly for the life of the decode: `AVAssetTrack.asset` is weak,
    /// and every window below builds its reader from it.
    private let asset: AVAsset
    private let track: AVAssetTrack
    /// One reader per element. `nil` is the whole track, which is what an empty
    /// `timeRanges` collapses to, so there is one path and not two.
    private let windows: [CMTimeRange?]
    private var index = 0
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var out: UnsafeMutablePointer<Float>
    private var capacity = 0

    /// No `AVNumberOfChannelsKey` on purpose: that is what keeps the source
    /// layout intact for the fold below.
    private static var outputSettings: [String: Any] { [
        AVFormatIDKey: kAudioFormatLinearPCM,
        AVSampleRateKey: Models.Demucs.sampleRate,
        AVLinearPCMBitDepthKey: 32,
        AVLinearPCMIsFloatKey: true,
        AVLinearPCMIsBigEndianKey: false,
        AVLinearPCMIsNonInterleaved: false,
    ] }

    /// - Parameter timeRanges: empty decodes the whole track; otherwise only
    ///   these ranges are read, one reader each.
    init(track: AVAssetTrack, timeRanges: [CMTimeRange] = []) throws {
        guard let asset = track.asset else { throw MediaError.readerFailed("track has no asset") }
        self.asset = asset
        self.track = track
        windows = timeRanges.isEmpty ? [nil] : timeRanges.map { $0 }
        out = .zeroed(0)
    }

    deinit {
        reader?.cancelReading()
        out.deallocate()
    }

    func start() throws { _ = try open() }

    /// Opens the next window, or reports that there are none left.
    ///
    /// **One reader per window, not one reader reset onto each in turn.**
    /// `AVAssetReaderOutput.supportsRandomAccess` is the API for the latter and
    /// it is closed on both sides: `reset(forReadingTimeRanges:)` raises
    /// `NSInternalInconsistencyException` — an ObjC exception no Swift `catch`
    /// can see, so it terminates the app — unless the range being read has been
    /// drained, and with random access on, `copyNextSampleBuffer` *blocks* at
    /// the end of a range instead of returning the nil that would say so. There
    /// is no moment at which the call is both legal and reachable. Handing it
    /// the window list up front is what killed every music job on a source over
    /// 80 s; under that `AudioStats.windows` returns nothing and the whole path
    /// was unreachable, which is why the QA clip never showed it.
    ///
    /// ponytail: twenty `AVAssetReader` setups on a pass that decodes forty
    /// seconds of audio. Reader construction is not the cost here; the decode is.
    private func open() throws -> Bool {
        reader?.cancelReading()
        reader = nil
        output = nil
        guard index < windows.count else { return false }
        let window = windows[index]
        index += 1

        let r = try AVAssetReader(asset: asset)
        let o = AVAssetReaderTrackOutput(track: track, outputSettings: Self.outputSettings)
        o.alwaysCopiesSampleData = false
        r.add(o)
        if let window { r.timeRange = window }
        guard r.startReading() else {
            throw MediaError.readerFailed(r.error?.localizedDescription ?? "startReading")
        }
        reader = r
        output = o
        return true
    }

    /// The next block of interleaved stereo f32, or nil at end of stream. The
    /// buffer belongs to the decoder and is valid until the next call.
    func next() throws -> (samples: UnsafePointer<Float>, frames: Int)? {
        while true {
            guard let output else { return nil }
            guard let sb = output.copyNextSampleBuffer() else {
                if reader?.status == .failed {
                    throw MediaError.readerFailed(reader?.error?.localizedDescription ?? "unknown")
                }
                // End of *this* window, not of the pass, while windows remain.
                if try open() { continue }
                return nil
            }
            let frames = CMSampleBufferGetNumSamples(sb)
            if frames == 0 { continue }
            guard let fd = CMSampleBufferGetFormatDescription(sb),
                  let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(fd)?.pointee else {
                throw MediaError.readerFailed("audio sample buffer has no format description")
            }
            // Authoritative rate and channel count come from the decoded buffer,
            // never the track's declared format: HE-AAC SBR/PS and Opus rewrite
            // both (spec-audio §5.1).
            guard asbd.mBitsPerChannel == 32, asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0 else {
                throw MediaError.readerFailed("decoder returned \(asbd.mBitsPerChannel)-bit PCM, expected f32")
            }
            // Everything downstream is hard-wired to 44 100 Hz — the segment
            // geometry, the AAC encoder, and the `samplesOut / 44100` PTS clock
            // that carries A/V sync. A rate the converter silently refused to
            // honour would come out as a pitch shift plus progressive drift and
            // no error at all, so it is a hard failure here.
            guard Int(asbd.mSampleRate.rounded()) == Models.Demucs.sampleRate else {
                throw MediaError.readerFailed(
                    "decoder returned \(asbd.mSampleRate) Hz, expected \(Models.Demucs.sampleRate)")
            }
            let channels = Int(asbd.mChannelsPerFrame)

            var abl = AudioBufferList()
            var block: CMBlockBuffer?
            let st = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
                sb, bufferListSizeNeededOut: nil, bufferListOut: &abl,
                bufferListSize: MemoryLayout<AudioBufferList>.size,
                blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
                flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
                blockBufferOut: &block)
            guard st == noErr, let src = abl.mBuffers.mData?.assumingMemoryBound(to: Float.self) else {
                throw MediaError.readerFailed("CMSampleBufferGetAudioBufferList \(st)")
            }
            if capacity < frames {
                out.deallocate()
                out = .zeroed(frames * 2)
                capacity = frames
            }
            Self.fold(src, channels: channels, frames: frames, into: out)
            withExtendedLifetime(block) {}
            return (UnsafePointer(out), frames)
        }
    }

    /// Fold `channels`-wide interleaved f32 down to interleaved stereo.
    ///
    /// Decoder PCM is assumed WAV order `L R C LFE Ls Rs`. LFE is dropped on
    /// purpose, and the result is **deliberately un-normalized**: level is
    /// irrelevant to a separator that divides and re-multiplies by the same std,
    /// and the soft clip downstream catches the ~2.4 full-scale peak a 5.1 fold
    /// can reach.
    ///
    /// Carried defect (spec-audio §5.2, open risk 4): a 4-channel source takes
    /// this branch too and treats channel 2 as the centre, so quad `L R Ls Rs`
    /// is mis-folded. No 4-channel asset exists to test against; fixing it blind
    /// would diverge from the shipped Android behaviour.
    static func fold(_ src: UnsafePointer<Float>, channels: Int, frames: Int,
                     into dst: UnsafeMutablePointer<Float>) {
        let n = vDSP_Length(frames), ch = vDSP_Stride(channels)
        switch channels {
        case 1:
            for i in 0..<frames {
                dst[2 * i] = src[i]
                dst[2 * i + 1] = src[i]
            }
        case 2:
            dst.update(from: src, count: frames * 2)
        default:
            var hp = halfPower
            vDSP_vsma(src + 2, ch, &hp, src + 0, ch, dst + 0, 2, n)  // L + 0.707·C
            vDSP_vsma(src + 2, ch, &hp, src + 1, ch, dst + 1, 2, n)  // R + 0.707·C
            if channels > 4 { vDSP_vsma(src + 4, ch, &hp, dst + 0, 2, dst + 0, 2, n) }  // + 0.707·Ls
            if channels > 5 { vDSP_vsma(src + 5, ch, &hp, dst + 1, 2, dst + 1, 2, n) }  // + 0.707·Rs
        }
    }
}

/// Whole-track mean and standard deviation of the mono mix, the scalars the
/// separator normalizes by. Rate-agnostic, so a sampled pass is as good as a
/// full one — but the channel fold has to be the same one `stream` uses or the
/// fp16 graph is fed the wrong level.
struct AudioStats: Sendable {
    let mean: Float
    let std: Float
    let frames: Int

    /// 20 × 2 s windows spread across the track, the first at 0 and the last
    /// flush against the end. Empty means "decode the whole track": under 80 s
    /// the sampled pass would read most of the file anyway (spec-audio §5.4).
    static func windows(duration: CMTime) -> [CMTimeRange] {
        let count = 20
        let window = CMTime(value: 2_000_000, timescale: 1_000_000)
        guard duration.seconds > Double(count) * 2 * window.seconds else { return [] }
        let span = duration.seconds - window.seconds
        return (0..<count).map { k in
            let start = Double(k) * span / Double(count - 1)
            return CMTimeRange(start: CMTime(seconds: start, preferredTimescale: 1_000_000),
                               duration: window)
        }
    }

    /// One decode pass. `sum`/`sumsq` accumulate in Double and the variance uses
    /// Bessel's N−1, matching the python normalization the model was trained on.
    static func measure(track: AVAssetTrack, duration: CMTime,
                        isCancelled: () -> Bool = { false }) throws -> AudioStats {
        let started = ContinuousClock.now
        let stage = Stage("audio.stats")
        let ranges = windows(duration: duration)
        let decoder = try AudioDecoder(track: track, timeRanges: ranges)
        try decoder.start()

        var mono = [Float](), monoD = [Double]()
        var sum = 0.0, sumsq = 0.0, count = 0
        var half: Float = 0.5
        while let (p, frames) = try decoder.next() {
            if isCancelled() { throw MediaError.cancelled }
            if mono.count < frames {
                mono = [Float](repeating: 0, count: frames)
                monoD = [Double](repeating: 0, count: frames)
            }
            var blockSum = 0.0, blockSq = 0.0
            mono.withUnsafeMutableBufferPointer { m in
                monoD.withUnsafeMutableBufferPointer { d in
                    let n = vDSP_Length(frames)
                    vDSP_vadd(p, 2, p + 1, 2, m.baseAddress!, 1, n)
                    vDSP_vsmul(m.baseAddress!, 1, &half, m.baseAddress!, 1, n)
                    vDSP_vspdp(m.baseAddress!, 1, d.baseAddress!, 1, n)
                    vDSP_sveD(d.baseAddress!, 1, &blockSum, n)
                    vDSP_svesqD(d.baseAddress!, 1, &blockSq, n)
                }
            }
            sum += blockSum
            sumsq += blockSq
            count += frames
        }
        guard count > 0 else { throw MediaError.readerFailed("could not decode any audio") }

        let mean = sum / Double(count)
        let variance = count > 1 ? max(0, (sumsq - sum * sum / Double(count)) / Double(count - 1)) : 0
        let stats = AudioStats(mean: Float(mean), std: Float(variance.squareRoot()), frames: count)
        stage.stop("""
            wall=\(Int(msSince(started)))ms mean=\(stats.mean) std=\(stats.std) \
            frames=\(count) windows=\(ranges.count)
            """)
        return stats
    }
}
