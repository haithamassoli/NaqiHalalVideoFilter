import AVFoundation
import CoreMedia
import VideoToolbox
import Foundation
import os

/// Everything the pipeline needs to know about an input file, probed once.
/// Android re-opened a `MediaExtractor` per segment and paid 35–70 container
/// opens on a feature film (`video-performance-plan-v2.md` 5.9 S3); here the
/// probe happens once per job and is passed down.
struct MediaSource: Sendable {
    let url: URL
    let duration: CMTime
    /// nil when the file has no video track (audio-only input).
    let video: VideoInfo?
    let audio: AudioInfo?

    struct VideoInfo: Sendable {
        let naturalSize: CGSize
        let transform: VideoTransform
        let nominalFrameRate: Float
        let estimatedBitrate: Float
        let codec: CMVideoCodecType
        let isHDR: Bool
        /// The source track's timescale, carried into the writer so PTS are not rescaled.
        let naturalTimeScale: CMTimeScale
        var formatDescription: CMFormatDescription?

        var pixelCount: Int { Int(naturalSize.width * naturalSize.height) }

        func capped(shortSide: Int?) -> VideoInfo {
            guard let shortSide, shortSide > 0 else { return self }
            let current = min(naturalSize.width, naturalSize.height)
            guard current > CGFloat(shortSide) else { return self }
            let scale = CGFloat(shortSide) / current
            func even(_ value: CGFloat) -> CGFloat {
                CGFloat(max(2, Int((value * scale).rounded()) / 2 * 2))
            }
            let size = CGSize(width: even(naturalSize.width), height: even(naturalSize.height))
            return VideoInfo(naturalSize: size,
                             transform: transform.resized(to: size),
                             nominalFrameRate: nominalFrameRate,
                             estimatedBitrate: estimatedBitrate,
                             codec: codec,
                             isHDR: isHDR,
                             naturalTimeScale: naturalTimeScale,
                             formatDescription: formatDescription)
        }
    }

    struct AudioInfo: Sendable {
        let sampleRate: Double
        let channelCount: Int
        let estimatedBitrate: Float
        var formatDescription: CMFormatDescription?
    }

    var hasAudio: Bool { audio != nil }

    static func probe(_ url: URL) async throws -> MediaSource {
        let asset = AVURLAsset(url: url, options: [
            AVURLAssetPreferPreciseDurationAndTimingKey: true,
        ])
        let duration = try await asset.load(.duration)

        var videoInfo: MediaSource.VideoInfo?
        if let t = try await asset.loadTracks(withMediaType: .video).first {
            let (size, transform, fps, bitrate, formats, timeScale) = try await (
                t.load(.naturalSize), t.load(.preferredTransform),
                t.load(.nominalFrameRate), t.load(.estimatedDataRate),
                t.load(.formatDescriptions), t.load(.naturalTimeScale)
            )
            let fd = formats.first
            videoInfo = VideoInfo(
                naturalSize: size,
                transform: VideoTransform(preferredTransform: transform, naturalSize: size),
                nominalFrameRate: fps > 0 ? fps : 30,
                estimatedBitrate: bitrate,
                codec: fd.map { CMFormatDescriptionGetMediaSubType($0) } ?? 0,
                isHDR: fd.map(Self.isHDR) ?? false,
                naturalTimeScale: timeScale,
                formatDescription: fd)
        }

        var audioInfo: MediaSource.AudioInfo?
        if let t = try await asset.loadTracks(withMediaType: .audio).first {
            let (bitrate, formats) = try await (t.load(.estimatedDataRate), t.load(.formatDescriptions))
            let fd = formats.first
            let asbd = fd.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
            audioInfo = AudioInfo(
                sampleRate: asbd?.mSampleRate ?? 44_100,
                channelCount: Int(asbd?.mChannelsPerFrame ?? 2),
                estimatedBitrate: bitrate,
                formatDescription: fd)
        }

        let src = MediaSource(url: url, duration: duration, video: videoInfo, audio: audioInfo)
        Log.media.info("""
            probe \(url.lastPathComponent, privacy: .public) \
            dur=\(duration.seconds, format: .fixed(precision: 2))s \
            video=\(videoInfo.map { "\(Int($0.naturalSize.width))x\(Int($0.naturalSize.height))@\($0.nominalFrameRate) rot=\($0.transform.rotationDegrees) hdr=\($0.isHDR)" } ?? "none", privacy: .public) \
            audio=\(audioInfo.map { "\(Int($0.sampleRate))Hz x\($0.channelCount)" } ?? "none", privacy: .public)
            """)
        return src
    }

    /// HLG or PQ transfer function means the source is HDR and must be tone-mapped.
    private static func isHDR(_ fd: CMFormatDescription) -> Bool {
        let tf = CMFormatDescriptionGetExtension(fd, extensionKey: kCMFormatDescriptionExtension_TransferFunction)
        guard let s = tf as? String else { return false }
        return s == (kCVImageBufferTransferFunction_ITU_R_2100_HLG as String)
            || s == (kCVImageBufferTransferFunction_SMPTE_ST_2084_PQ as String)
    }
}

// MARK: - Encoder settings

enum EncodeSettings {
    /// Second-generation encode has to spend bits reproducing the source
    /// encoder's artifacts as well as the picture, so it gets headroom over the
    /// source rate. Same constant as Android's `GEN2_HEADROOM`.
    static let gen2Headroom: Float = 1.3

    /// Cap by output pixel count, set near what phone cameras actually record so
    /// a camera original is not halved on the way through. A ceiling, not a
    /// target — `resolveBitrate` takes the min with the source. Copied verbatim
    /// from Android `render/RenderPipeline.kt:275`.
    static func bitrateCap(pixels: Int) -> Int {
        switch pixels {
        case ...(854 * 480): 4_000_000
        case ...(1280 * 720): 10_000_000
        case ...(1920 * 1080): 16_000_000
        case ...(2560 * 1440): 24_000_000
        default: 45_000_000
        }
    }

    /// min(source x headroom, tier cap). Resolved once per job — identical
    /// encoder settings per segment is a precondition for segment concat.
    static func resolveBitrate(_ v: MediaSource.VideoInfo) -> Int {
        let cap = bitrateCap(pixels: v.pixelCount)
        guard v.estimatedBitrate > 0 else { return cap }
        let scaled = Double(v.estimatedBitrate) * Double(gen2Headroom)
        return min(Int(scaled.rounded()), cap)
    }

    /// Writer settings for a re-encode. HEVC where the source was HEVC and the
    /// hardware supports it, else H.264.
    static func videoSettings(for v: MediaSource.VideoInfo, bitrate: Int) -> [String: Any] {
        let useHEVC = v.codec == kCMVideoCodecType_HEVC
            && hasHardwareEncoder(kCMVideoCodecType_HEVC)
        var props: [String: Any] = [
            AVVideoAverageBitRateKey: bitrate,
            // 2 s GOP: short enough that a resumed segment re-syncs quickly,
            // long enough not to cost meaningful bitrate.
            AVVideoMaxKeyFrameIntervalDurationKey: 2.0,
            AVVideoAllowFrameReorderingKey: true,
            // Required, not optional, whenever the profile is an AutoLevel one:
            // VideoToolbox picks the level from the frame rate, and without this
            // it guesses.
            AVVideoExpectedSourceFrameRateKey: Int(v.nominalFrameRate.rounded()),
        ]
        props[AVVideoProfileLevelKey] = useHEVC
            ? kVTProfileLevel_HEVC_Main_AutoLevel as String
            : AVVideoProfileLevelH264HighAutoLevel

        return [
            AVVideoCodecKey: useHEVC ? AVVideoCodecType.hevc : AVVideoCodecType.h264,
            AVVideoWidthKey: Int(v.naturalSize.width),
            AVVideoHeightKey: Int(v.naturalSize.height),
            AVVideoCompressionPropertiesKey: props,
            // Output is always SDR: HDR sources are tone-mapped on the way through.
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
        ]
    }

    private static func hasHardwareEncoder(_ codec: CMVideoCodecType) -> Bool {
        var raw: CFArray?
        guard VTCopyVideoEncoderList(nil, &raw) == noErr,
              let encoders = raw as? [[CFString: Any]] else { return false }
        return encoders.contains { encoder in
            (encoder[kVTVideoEncoderList_CodecType] as? NSNumber)?.uint32Value == codec
                && (encoder[kVTVideoEncoderList_IsHardwareAccelerated] as? Bool) == true
        }
    }

    /// AAC-LC at a rate that never upsamples a thin source.
    static func audioSettings(sampleRate: Double, channels: Int, sourceBitrate: Float) -> [String: Any] {
        let cap = channels > 2 ? 256_000 : 192_000
        let bitrate = sourceBitrate > 0 ? min(Int(sourceBitrate), cap) : cap
        return [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: min(channels, 2),
            AVEncoderBitRateKey: bitrate,
        ]
    }
}
