import Foundation
import VideoToolbox

/// One stream yt-dlp (or the native extractor) can fetch. `url` is a direct
/// HTTP(S) media URL, not the page the user pasted.
struct MediaFormat: Sendable, Equatable {
    var id: String
    var url: URL
    var ext: String
    var height: Int?
    var vcodec: String?
    var acodec: String?
    var filesize: Int64?
    var tbr: Double?
    var httpHeaders: [String: String]
    var fps: Int? = nil
    /// InnerTube `lastModified`: with `filesize`, the identity a resumed
    /// `.part` must still match.
    var lastModified: String? = nil
    /// PQ/HLG stream; the visual filter prefers SDR.
    var hdr: Bool = false

    var hasVideo: Bool {
        guard let vcodec, !vcodec.isEmpty, vcodec != "none" else { return false }
        return true
    }
    var hasAudio: Bool {
        guard let acodec, !acodec.isEmpty, acodec != "none" else { return false }
        return true
    }
}

struct ExtractedMedia: Sendable {
    var title: String
    var webpageURL: String
    var formats: [MediaFormat]
    var durationSec: Double? = nil
    /// Kept for the deferred HLS path; nothing reads it yet.
    var hlsManifestURL: URL? = nil
    /// InnerTube client that answered (`VISIONOS`, …), nil off YouTube.
    var client: String? = nil
}

/// Live transfer numbers, summed over every stream of one download.
struct DownloadStats: Sendable, Equatable, Codable {
    var done: Int64
    var total: Int64?
    var bytesPerSec: Double
    var etaSec: Double?

    var fraction: Double? {
        guard let total, total > 0 else { return nil }
        return min(1, Double(done) / Double(total))
    }
}

/// What the job will do to the file, which decides the format policy.
enum Processing: Sendable, Equatable {
    case none, music, visual
}

/// Hardware decoders the format policy may rely on. A value, not a probe, so
/// tests can pass any device.
struct DeviceCodecs: Sendable, Equatable {
    var av1: Bool
    var hevc: Bool
}

enum DownloadError: Error, Sendable {
    case unsupported
    case network(String)
    case noFile
    case noSpace
    case cancelled
    case generic(String)
    /// Removed, private, members-only, age-gated: retrying cannot help.
    case unavailable(String)
    case geo(String)
    case rateLimited
    /// A format URL answered 403 even after one re-extraction.
    case forbidden
    /// Every client returned nothing usable: YouTube changed.
    case extractor(String)

    var localizedDescription: String {
        switch self {
        case .unsupported: "unsupported url"
        case .network(let s): "network: \(s)"
        case .noFile: "download reported success but produced no file"
        case .noSpace: "no space left"
        case .cancelled: "cancelled"
        case .generic(let s): s
        case .unavailable(let s): "unavailable: \(s)"
        case .geo(let s): "geo: \(s)"
        case .rateLimited: "rate limited"
        case .forbidden: "forbidden"
        case .extractor(let s): "extractor: \(s)"
        }
    }
}

extension DeviceCodecs {
    static let current = DeviceCodecs(av1: VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1),
                                      hevc: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC))
}

extension DownloadQuality {
    /// Pick 1–2 formats for this quality, what the job will do with them, and
    /// what this device can decode (plan Phase 4).
    ///
    /// AVFoundation cannot demux WebM, so VP8/VP9 and Opus/Vorbis are never
    /// candidates; AV1 and HEVC only with a hardware decoder. Then:
    /// - none: tallest; at equal height AV1 > HEVC > H.264 (fewer bytes)
    /// - music: tallest; at equal height H.264 > HEVC > AV1 (passthrough-safe)
    /// - visual: ≤ 1080p, then ≤ 30 fps, SDR, AV1 > HEVC > H.264. fps and SDR
    ///   are preferences, so a 60 fps-only source still yields a format.
    func select(_ formats: [MediaFormat], processing: Processing,
                hw: DeviceCodecs = .current) -> [MediaFormat] {
        let usable = formats.filter {
            ($0.url.scheme == "http" || $0.url.scheme == "https" || $0.url.isFileURL)
                && Self.decodable($0, hw)
        }
        if self == .audio {
            if let audio = Self.bestAudioOnly(usable) { return [audio] }
            if let combined = Self.best(usable, audio: true, cap: nil, processing) { return [combined] }
            return []
        }
        let cap = processing == .visual ? min(heightCap ?? 1080, 1080) : heightCap
        let video = Self.best(usable, audio: false, cap: cap, processing)
        let audio = Self.bestAudioOnly(usable)
        let combined = Self.best(usable, audio: true, cap: cap, processing)
        // A combined stream at the best available resolution avoids a second
        // transfer and mux. Keep separate streams only when they buy pixels.
        if let combined,
           video == nil || (combined.height ?? 0) >= (video?.height ?? 0) { return [combined] }
        if let video, let audio { return [video, audio] }
        if let combined { return [combined] }
        if let video { return [video] }
        if let audio { return [audio] }
        return []
    }

    /// No processing, on this device.
    func select(_ formats: [MediaFormat]) -> [MediaFormat] {
        select(formats, processing: .none)
    }

    private enum Codec { case h264, hevc, av1, other }

    private static func codec(_ f: MediaFormat) -> Codec {
        let v = (f.vcodec ?? "").lowercased()
        if v.hasPrefix("av01") || v == "av1" { return .av1 }
        if v.hasPrefix("hvc1") || v.hasPrefix("hev1") || v == "hevc" || v == "h265" { return .hevc }
        if v.hasPrefix("avc") || v == "h264" { return .h264 }
        return .other
    }

    private static func decodable(_ f: MediaFormat, _ hw: DeviceCodecs) -> Bool {
        let v = (f.vcodec ?? "").lowercased()
        let a = (f.acodec ?? "").lowercased()
        if f.ext == "webm" || v.hasPrefix("vp") || a.hasPrefix("opus") || a.hasPrefix("vorbis") {
            return false
        }
        switch codec(f) {
        case .av1: return hw.av1
        case .hevc: return hw.hevc
        case .h264, .other: return true
        }
    }

    private static func best(_ formats: [MediaFormat], audio: Bool, cap: Int?,
                             _ processing: Processing) -> MediaFormat? {
        formats
            .filter { $0.hasVideo && $0.hasAudio == audio && (cap == nil || ($0.height ?? 0) <= cap!) }
            .max { rank($0, processing).lexicographicallyPrecedes(rank($1, processing)) }
    }

    /// Sort key, larger is better. mp4 first keeps yt-dlp's odd containers
    /// (flv, 3gp) behind anything AVFoundation handles best.
    private static func rank(_ f: MediaFormat, _ processing: Processing) -> [Double] {
        let mp4: Double = f.ext == "mp4" || f.ext == "m4v" ? 1 : 0
        let height = Double(f.height ?? 0)
        let tbr = f.tbr ?? 0
        let efficient: Double = switch codec(f) {
        case .av1: 3
        case .hevc: 2
        case .h264: 1
        case .other: 0
        }
        switch processing {
        case .none:
            return [mp4, height, efficient, tbr]
        case .music:
            let passthrough: Double = switch codec(f) {
            case .h264: 3
            case .hevc: 2
            case .av1: 1
            case .other: 0
            }
            return [mp4, height, passthrough, tbr]
        case .visual:
            let lowFps: Double = (f.fps ?? 30) <= 30 ? 1 : 0
            return [mp4, height, lowFps, f.hdr ? 0 : 1, efficient, tbr]
        }
    }

    private static func bestAudioOnly(_ formats: [MediaFormat]) -> MediaFormat? {
        formats.filter { $0.hasAudio && !$0.hasVideo }.max(by: Self.audioRank)
    }

    /// Prefer m4a/mp4 (AAC) so the pipeline can probe it.
    private static func audioRank(_ a: MediaFormat, _ b: MediaFormat) -> Bool {
        let aM4 = a.ext == "m4a" || a.ext == "mp4"
        let bM4 = b.ext == "m4a" || b.ext == "mp4"
        if aM4 != bM4 { return !aM4 && bM4 }
        return (a.tbr ?? 0) < (b.tbr ?? 0)
    }
}
