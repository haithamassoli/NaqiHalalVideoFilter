import Foundation

/// Picks **one of three whole sentences** and never glues a number to a unit.
/// Arabic keeps its own digits, word order and unit words, which no
/// `"\(n) min"` can produce (spec contract 6.7.4.1).
func durationText(ms: Int64) -> LocalizedStringResource {
    let minutes = ms / 60_000
    if minutes < 1 { return .durUnderMin }
    if minutes < 60 { return .durMin(Int32(minutes)) }
    return .durHMin(Int32(minutes / 60), Int32(minutes % 60))
}

/// The low-space sentence with the figures that produced it, and the wordier
/// fallback when a job has none — a queue file written before `Job.Shortfall`
/// existed decodes it as nil, and so does any other failure routed here.
///
/// GB and decimal, matching `fileSizeText`: the number the user goes and checks
/// is the one Settings shows them, and Settings counts in decimal.
func lowSpaceText(_ shortfall: Job.Shortfall?) -> LocalizedStringResource {
    guard let shortfall else { return .errLowSpace }
    return .errLowSpaceGb(Float(shortfall.requiredBytes) / 1e9,
                          Float(shortfall.availableBytes) / 1e9)
}

/// `66 MB of 331 MB · 8.1 MB/s · ~under a minute remaining`, dropping each
/// part that is not known yet. `ByteCountFormatter` brings the digits and
/// unit words; only the two joins need keys of their own.
func downloadStatsText(_ stats: DownloadStats) -> String {
    let bytes = { ByteCountFormatter.string(fromByteCount: $0, countStyle: .file) }
    let done = bytes(stats.done)
    let amount = stats.total.map { total in
        String(localized: "jobs_download_of", defaultValue: "\(done) of \(bytes(total))")
    } ?? done
    let speed = stats.bytesPerSec > 0
        ? String(localized: "jobs_download_speed", defaultValue: "\(bytes(Int64(stats.bytesPerSec)))/s")
        : nil
    let eta = stats.etaSec.flatMap { $0 <= 0 ? nil : String(localized: .jobsEtaRemaining(String(localized: durationText(ms: Int64($0 * 1000))))) }
    return [amount, speed, eta].compactMap(\.self).joined(separator: " · ")
}

/// Decimal, not binary — the same rule the Android library rows use.
func fileSizeText(bytes: Int64) -> LocalizedStringResource {
    bytes >= 1_000_000_000
        ? .jobsSizeGb(Float(bytes) / 1e9)
        : .jobsSizeMb(Float(bytes) / 1e6)
}

extension FilterOps.Who {
    var label: LocalizedStringResource {
        switch self {
        case .women: .optWhoWomen
        case .men: .optWhoMen
        case .everyone, .none: .optWhoEveryone
        }
    }
}

extension FilterOps.SolidColor {
    var label: LocalizedStringResource {
        switch self {
        case .blur: .optStyleBlur
        case .gray: .optSolidGray
        case .black: .optSolidBlack
        case .white: .optSolidWhite
        case .navy: .optSolidNavy
        case .green: .optSolidGreen
        }
    }
}

extension Job.Stage {
    /// `concat` and `publish` are both "Finishing up". Naming the copy into Photos would
    /// mean explaining why a file that is already filtered is still moving.
    var label: LocalizedStringResource {
        switch self {
        case .download: .stageDownloading
        case .convert: .stageConverting
        case .analyze: .stageAnalyzing
        case .render: .stageRendering
        case .separate: .stageSeparating
        case .mux, .concat, .publish: .stageMuxing
        }
    }
}

extension JobFailure {
    /// One sentence per case. An unrecognised cause has already resolved to
    /// `.generic` in `JobFailure.of` — the throwable's own message never
    /// reaches the screen as untranslated developer text.
    var sentence: LocalizedStringResource {
        switch self {
        case .drmProtected: .errDrm
        case .noVideoTrack: .errNoVideo
        case .noAudioTrack: .errNoAudio
        case .unsupportedContainer, .sourceUnreadable: .errUnreadable
        case .unsupportedCodec: .errUnsupportedCodec
        // The fallback wording, without numbers. A job that carries a
        // `Job.Shortfall` has the real figures and `lowSpaceText` says them
        // instead; this line covers the one that does not.
        case .lowSpace: .errLowSpace
        case .outOfSpace: .errOutOfSpace
        case .photosDenied: .errPhotosDenied
        case .interrupted: .errInterrupted
        // `publishFailed` keeps the generic line: the one publish failure the
        // user can actually act on — a refused photo library — is now
        // `photosDenied`, and what is left is a write that failed for a reason
        // no copy can turn into an action.
        //
        // `nothingSelected` is unreachable from the UI's own guards: Start is
        // disabled without an operation.
        case .downloadUnsupported: .errDownloadUnsupported
        case .downloadNetwork: .errDownloadNetwork
        case .downloadUnavailable: .errDownloadUnavailable
        case .downloadGeo: .errDownloadGeo
        case .downloadRateLimited: .errDownloadRateLimited
        case .downloadForbidden: .errDownloadForbidden
        case .downloadExtractor: .errDownloadExtractor
        case .downloadGeneric: .errDownloadGeneric
        case .nothingSelected, .publishFailed, .generic: .errGeneric
        }
    }
}
