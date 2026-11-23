import Foundation

/// `Preflight`'s disk rule, shared so the share sheet can refuse a link before
/// it is queued instead of the job failing after the download.
enum SpaceBudget {
    /// 2 GiB of headroom on top of the computed requirement.
    static let slackBytes: Int64 = 2 * 1024 * 1024 * 1024
    /// AAC transcode scratch: 192 kbit/s stereo.
    static let aacBytesPerSecond: Int64 = 24_000

    static func requiredBytes(sourceBytes: Int64, tempCopies: Int64, extraScratch: Int64) -> Int64 {
        (tempCopies + 1) * sourceBytes + extraScratch + slackBytes
    }

    /// The sheet's estimate for a link of `downloadBytes`, mirroring
    /// `Preflight.tempCopies`/`extraScratchBytes` unsegmented. A plain download
    /// (no filter) skips the render copies.
    static func requiredBytes(downloadBytes: Int64, options: ShareOptions,
                              durationSec: Double?) -> Int64 {
        let copies: Int64 = options.censor && options.removeMusic ? 2
            : options.censor || options.removeMusic ? 1 : 0
        let aac = options.removeMusic ? Int64(durationSec ?? 0) * aacBytesPerSecond : 0
        return requiredBytes(sourceBytes: downloadBytes, tempCopies: copies, extraScratch: aac)
    }

    /// Space the app may actually use, which on iOS is the "important" volume
    /// capacity, not the raw free-space number.
    static func availableBytes(at dir: URL) -> Int64 {
        if let v = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
           let c = v.volumeAvailableCapacityForImportantUsage {
            return Int64(c)
        }
        let attrs = try? FileManager.default.attributesOfFileSystem(forPath: dir.path)
        return (attrs?[.systemFreeSize] as? NSNumber)?.int64Value ?? 0
    }
}

extension ShareOptions {
    /// `JobRunner.processing(_:)` for the sheet's choices.
    var processing: Processing {
        censor ? .visual : removeMusic ? .music : .none
    }
}

extension ExtractedMedia {
    /// Bytes the job will transfer for these choices: the same `resolved(fast:)`
    /// and `select` as `JobRunner`. Nil when a chosen stream has no size.
    func downloadBytes(_ quality: DownloadQuality, options: ShareOptions,
                       hw: DeviceCodecs = .current) -> Int64? {
        let chosen = quality.resolved(fast: options.processingMode == "fast")
            .select(formats, processing: options.processing, hw: hw)
        let sizes = chosen.compactMap(\.filesize)
        guard !chosen.isEmpty, sizes.count == chosen.count else { return nil }
        return sizes.reduce(0, +)
    }
}
