import AVFoundation
import Foundation
import Photos
import os

/// Reasons a job may not start. Each maps to one user-facing string.
enum PreflightFailure: Error, Equatable, Sendable {
    case drmProtected
    case noVideoTrack
    case noAudioTrack
    case unsupportedContainer(String)
    case lowSpace(requiredBytes: Int64, availableBytes: Int64)
    case sourceUnreadable
    case photosDenied
}

/// Guards run before any decoding starts. Order matters: DRM is checked before
/// codec lookup, because a protected track otherwise fails much later with an
/// opaque crypto error (Android `work/Preflight.kt:91-93`).
enum Preflight {

    /// 2 GiB of headroom; the rule lives in `SpaceBudget` so the share sheet applies it too.
    static let slackBytes = SpaceBudget.slackBytes

    /// The numbers behind the most recent `.lowSpace` verdict.
    ///
    /// A side channel because the failure cannot carry them itself: `JobRunner`
    /// collapses a `PreflightFailure` into a raw-valued `JobFailure` before it
    /// leaves the run, and that enum is what `naqi-queue.json` stores. Written
    /// here, read once by `JobQueue` on the failure path of the job that just
    /// set it — exact because the queue runs one job at a time.
    static let lastShortfall = OSAllocatedUnfairLock<Job.Shortfall?>(initialState: nil)

    /// Photos add-only, asked before the render instead of after it.
    ///
    /// `Publish.saveToPhotos` still asks — this is the early exit, not a
    /// guarantee that survives the hour in between — but asking *only* there
    /// meant a user who declines paid for the whole job first and then got a
    /// sentence that did not even say why.
    static func photosAccess(for destination: Destination) async -> PreflightFailure? {
        guard destination == .photos else { return nil }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        return status == .authorized || status == .limited ? nil : .photosDenied
    }

    /// AAC transcode scratch: 192 kbit/s stereo.
    static let aacBytesPerSecond = SpaceBudget.aacBytesPerSecond

    /// Full-size temporary copies that coexist with the published output.
    /// Measured: the mux temp is a full-size copy of the source, so a 2 h movie
    /// exceeds any fixed "2 GB temp" budget by construction. The preflight
    /// sizes for that rather than asserting it (`docs/tasks.md:50` on Android).
    static func tempCopies(for ops: FilterOps, segmented: Bool) -> Int64 {
        // Segmented never holds three: the segments are deleted the moment the
        // concat lands, so the pairs that coexist are (segments, concat) and
        // then (concat, muxed output).
        if segmented { return 2 }
        if ops.shape == .both { return 2 } // render temp + published output
        return 1                           // one temp + the published copy
    }

    /// Every `removeMusic` shape lands the separated track as a standalone
    /// `audio.m4a` **before** it is muxed in, so the encoded track is a real
    /// file that coexists with the output rather than bytes inside it. That is
    /// what makes music-only resumable (`JobRunner`), and it is a term the
    /// budget has to carry: 192 kbit/s over a 155-minute film is ~223 MB.
    ///
    /// Separation streams directly to that AAC file. There is no duration-sized
    /// PCM file, so preflight charges only bytes that can actually coexist.
    static func extraScratchBytes(for ops: FilterOps, durationSeconds: Int64,
                                  segmented: Bool) -> Int64 {
        let aac = ops.removeMusic ? durationSeconds * aacBytesPerSecond : 0
        return aac
    }

    static func requiredBytes(sourceBytes: Int64, tempCopies: Int64, extraScratch: Int64) -> Int64 {
        SpaceBudget.requiredBytes(sourceBytes: sourceBytes, tempCopies: tempCopies,
                                  extraScratch: extraScratch)
    }

    /// Runs every guard. Returns nil when the job may proceed.
    /// - Parameter extraCopies: full-size files that coexist with the published
    ///   output beyond `tempCopies`. A Photos publish now keeps a local copy
    ///   so Play/Share/Save still have a file; that copy is one extra.
    static func check(source: MediaSource, ops: FilterOps, segmented: Bool = false,
                      extraCopies: Int64 = 0) async -> PreflightFailure? {
        let asset = AVURLAsset(url: source.url)

        if (try? await asset.load(.hasProtectedContent)) == true { return .drmProtected }
        if ops.censor && source.video == nil { return .noVideoTrack }
        if ops.removeMusic && source.audio == nil { return .noAudioTrack }
        if (try? await asset.load(.isPlayable)) != true {
            return .unsupportedContainer(source.url.pathExtension)
        }

        // The Apple equivalent of Android's `file://`-has-no-size trap: a
        // security-scoped Photos URL may not answer `.fileSize`, and a nil there
        // would silently degrade the whole check to the bare 2 GiB slack.
        guard let sourceBytes = fileSize(source.url) else { return .sourceUnreadable }

        let required = requiredBytes(
            sourceBytes: sourceBytes,
            tempCopies: tempCopies(for: ops, segmented: segmented) + extraCopies,
            extraScratch: extraScratchBytes(for: ops,
                                            durationSeconds: Int64(source.duration.seconds),
                                            segmented: segmented))
        let available = availableBytes()
        Log.job.info("preflight need=\(required / 1_048_576)MiB have=\(available / 1_048_576)MiB")
        if available < required {
            lastShortfall.withLock { $0 = Job.Shortfall(requiredBytes: required,
                                                        availableBytes: available) }
            return .lowSpace(requiredBytes: required, availableBytes: available)
        }
        return nil
    }

    static func fileSize(_ url: URL) -> Int64? {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let v = try? url.resourceValues(forKeys: [.fileSizeKey, .totalFileSizeKey]) else { return nil }
        if let s = v.totalFileSize ?? v.fileSize, s > 0 { return Int64(s) }
        return nil
    }

    static func availableBytes() -> Int64 {
        SpaceBudget.availableBytes(at: WorkDir.root)
    }
}

/// Scratch lives in a no-backup directory, never next to the source — part of
/// the original-untouched guarantee.
enum WorkDir {
    static let root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("naqi-work", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var b = base
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? b.setResourceValues(rv)
        return base
    }()

    static func job(_ key: String) -> URL {
        let d = root.appendingPathComponent(key, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func clear(_ key: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(key, isDirectory: true))
    }
}

/// Finished copies the app still owns, so Play, Share and Save have a file
/// after a Photos publish — and the only copy when Photos refused it, or when
/// the output is audio. A folder publish writes into the user's folder instead
/// and never lands here.
///
/// The app's Documents directory, so the files are real and reachable outside
/// the app: "On My iPhone › Naqi" in Files (`UIFileSharingEnabled` +
/// `LSSupportsOpeningDocumentsInPlace` in `naqi/Info.plist`), and the sandbox
/// container's Documents folder on Mac (Show in Finder on the Done screen).
///
/// Excluded from backup the same way `WorkDir` is: a 90-minute film is hundreds
/// of megabytes the user already has in Photos or Files, and iCloud would
/// otherwise charge them for a third copy.
enum OutputLibrary {
    static let root: URL = {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var b = base
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? b.setResourceValues(rv)
        return base
    }()

    static func owns(_ url: URL) -> Bool {
        let path = url.standardizedFileURL.path
        let rootPath = root.standardizedFileURL.path
        // Strictly inside: the root is Documents itself and is never ours to delete.
        return path.hasPrefix(rootPath + "/")
    }

    /// Moves `temp` into the library under `name`. Cross-volume falls back to
    /// copy-then-delete, matching `Publish.saveToFolder`.
    static func adopt(_ temp: URL, named name: String) throws -> URL {
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let dest = root.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: temp, to: dest)
        } catch {
            try FileManager.default.copyItem(at: temp, to: dest)
            try? FileManager.default.removeItem(at: temp)
        }
        return dest
    }

    static func remove(_ url: URL) {
        guard owns(url) else { return }
        try? FileManager.default.removeItem(at: url)
    }
}

/// `<sourceNameNoExt>-naqi-<epochMillis>.<ext>`. The epoch suffix is the only
/// collision defence; keeping the source's stem is what stops a downloaded
/// video losing its title one step before the user sees it.
func outputName(for source: URL, ext: String = "mp4") -> String {
    let stem = source.deletingPathExtension().lastPathComponent
    let name = stem.isEmpty ? "video" : stem
    return "\(name)-naqi-\(Int64(Date().timeIntervalSince1970 * 1000)).\(ext)"
}
