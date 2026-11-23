import Foundation
import os

/// One queued unit of work: a source, the options to apply to it, and where the
/// result goes. Semantics are Android's `work/Queue.Item` (`spec-jobs-ui.md`
/// §2.2) — the queue file is the authority on a job's outcome, not the runner.
struct Job: Identifiable, Codable, Sendable, Equatable {
    var id = UUID()
    /// The picked file, as it was at pick time. Only ever opened read-only.
    var source: URL
    /// A picked URL stops resolving once the app relaunches, so the queue also
    /// carries a bookmark. Without it every job that outlived a cold start
    /// would fail on its first read — which is exactly the case the persisted
    /// queue exists for.
    var bookmark: Data?
    var title: String
    var ops: FilterOps
    var destination: Destination
    /// Security-scoped folder for `.userFolder`.
    var folder: URL?
    /// The folder's bookmark, for the same reason `source` has one and one
    /// more: a queued job outlives the picker that produced the URL *and* it
    /// outlives the user changing their mind. `Flow.setFolder` closes the scope
    /// on the old folder, so a job still waiting in the queue for that folder
    /// would fail at publish — after the whole render — with no way back.
    var folderBookmark: Data?
    var state: State = .pending
    /// The two numbers behind a `.lowSpace` failure, when there is one.
    ///
    /// They cannot ride on the failure case itself: `JobFailure` is
    /// `String`-raw-valued and is what `naqi-queue.json` persists, so an
    /// associated value there would break both the raw-value conformance and
    /// the on-disk format — and widening `State.failed` would break every
    /// `case .failed(let f, _)` in the app. An optional field is purely
    /// additive instead: a queue file written before this existed decodes it
    /// as nil.
    var shortfall: Shortfall?
    var enqueuedAt = Date()
    /// http(s) page to fetch before filtering. Nil for a picked or shared file.
    /// The downloaded bytes land in quarantine and become the source the rest
    /// of the runner probes; this string is what `Checkpoint.key` hashes so a
    /// relaunch of the same link resumes rather than fetching twice.
    var remoteURL: String? = nil
    /// `DownloadQuality.rawValue`. Nil means BEST, matching Android `Quality.of`.
    var quality: String? = nil

    static func capture(source: URL, ops: FilterOps, destination: Destination,
                        folder: URL? = nil, title: String? = nil) -> Job {
        return Job(source: source, bookmark: source.scopedBookmark(),
                   title: title ?? source.deletingPathExtension().lastPathComponent,
                   ops: ops, destination: destination, folder: folder,
                   folderBookmark: folder?.scopedBookmark())
    }

    static func captureLink(_ url: String, quality: DownloadQuality, ops: FilterOps,
                            destination: Destination, folder: URL? = nil,
                            title: String? = nil) -> Job {
        let page = URL(string: url) ?? URL(fileURLWithPath: "/download")
        return Job(source: page,
                   title: title ?? page.host ?? "download",
                   ops: ops, destination: destination, folder: folder,
                   folderBookmark: folder?.scopedBookmark(),
                   remoteURL: url, quality: quality.rawValue)
    }

    /// Re-resolves the destination folder the same way `openSource` re-resolves
    /// the input, but does **not** open the scope: `Publish.saveToFolder` already
    /// brackets its own access, and two nested opens would just be two closes to
    /// keep balanced. Nil for every destination that is not `.userFolder`.
    var resolvedFolder: URL? {
        guard let folder else { return nil }
        return .resolvingScopedBookmark(folderBookmark) ?? folder
    }

    /// Re-resolves the bookmark and opens security-scoped access. The returned
    /// closure must run when the job is finished with the file.
    func openSource() throws -> (url: URL, close: @Sendable () -> Void) {
        let url = URL.resolvingScopedBookmark(bookmark) ?? source
        let scoped = url.startAccessingSecurityScopedResource()
        let opened = url
        let close: @Sendable () -> Void = { if scoped { opened.stopAccessingSecurityScopedResource() } }
        guard FileManager.default.isReadableFile(atPath: opened.path) else {
            close()
            throw JobFailure.sourceUnreadable
        }
        return (opened, close)
    }

    enum State: Codable, Sendable, Equatable {
        case pending
        case running
        /// Carries the whole publish record, not a URL: the Done screen names
        /// the file and Play/Share/Save read the local copy off it.
        case done(Published)
        /// `resumable` is what the Resume button reads: the work directory
        /// still holds finished work the next attempt will pick up.
        case failed(JobFailure, resumable: Bool)
        case cancelled

        /// What "Clear finished" clears.
        var isTerminal: Bool {
            switch self {
            case .pending, .running: false
            case .done, .failed, .cancelled: true
            }
        }
    }

    /// Bytes the preflight wanted against bytes the volume had. Recorded for
    /// `JobFailure.lowSpace`, the one failure whose sentence says nothing
    /// useful without the numbers that produced it.
    struct Shortfall: Codable, Sendable, Equatable {
        var requiredBytes: Int64
        var availableBytes: Int64
    }
}

// MARK: - Shapes and stages

extension Job {

    /// The five shapes the runner dispatches to (§2.4).
    enum Shape: String, Codable, Sendable, CaseIterable {
        case audioOnly, segmented, combined, musicOnly, censorOnly
    }

    /// Dispatch order is load-bearing and is the Android one verbatim.
    ///
    /// `audioOnly` is **detected, not flagged**: the source itself is the only
    /// trustworthy statement about which tracks it has.
    static func shape(ops: FilterOps, hasVideoTrack: Bool, segmented: Bool) -> Shape {
        if ops.removeMusic && !hasVideoTrack { return .audioOnly }
        if ops.censor && segmented { return .segmented }
        if ops.removeMusic && ops.censor { return .combined }
        if ops.removeMusic { return .musicOnly }
        return .censorOnly
    }

    /// The pass strip. `transcode` is deliberately absent: it exists on Android
    /// only because `MediaMuxer` cannot copy an AC-3/DTS/Opus track into the
    /// concat output. The segmented route here hands that track to
    /// `Remux.mux` untouched, and a source AVFoundation cannot passthrough is
    /// one it already refused at `Preflight`'s `isPlayable` check.
    enum Stage: String, Codable, Sendable, CaseIterable {
        case download, convert, analyze, render, separate, mux, concat, publish
    }

    /// Stage order per shape (§2.5). `separate` sits between `analyze` and
    /// `render` because that is its ordering *constraint*, not its schedule: it
    /// runs concurrently with analyze and must have finished before render,
    /// which is the pass that muxes the replaced audio in.
    ///
    /// `publish` closes every shape. On Android the MediaStore row *was* the
    /// output; here the copy into Photos or the chosen folder is a real
    /// full-size write and it is what carries the bar to 100.
    ///
    /// Segmented has no `mux` of its own even though it ends with one: joining
    /// the segments and giving them their audio track are two halves of the same
    /// hand-off, and splitting a nine-point band across two labels would flick
    /// the stage caption for the length of a passthrough copy.
    static func stages(_ shape: Shape, removeMusic: Bool) -> [Stage] {
        switch shape {
        case .censorOnly: [.analyze, .render, .publish]
        case .musicOnly: [.separate, .mux, .publish]
        case .combined: [.analyze, .separate, .render, .mux, .publish]
        case .segmented: removeMusic
            ? [.analyze, .separate, .render, .concat, .publish]
            : [.analyze, .render, .concat, .publish]
        case .audioOnly: [.separate, .publish]
        }
    }
}

// MARK: - Progress

/// The progress bar.
///
/// Two shares are summed while both branches run: neither branch may post an
/// absolute overall percent or one would stomp the other and the bar would jump
/// backwards (Android `FilterWorker.kt:890-900`). The tail stages — mux, concat,
/// publish — post *absolutely* over a base that already contains the finished
/// audio share, which is why the bar itself is `max`-clamped: without the clamp
/// the hand-off from the two-share phase to the absolute phase would step back.
///
/// Every band below is Android's, unchanged. The arithmetic closes: combined
/// video tops at 50 plus an audio share of 43 = 93, then mux runs 93→99;
/// segmented with music is 50 + 40 = 90, then concat 90→99.
struct JobProgress: Sendable, Equatable, Codable {
    let shape: Job.Shape
    let removeMusic: Bool
    private(set) var stage: Job.Stage?
    private(set) var pct: Double = 0
    private var videoPct: Double = 0
    private var audioPct: Double = 0
    /// Bytes, speed and ETA, only while `stage == .download`. Optional, so a
    /// progress encoded before it existed still decodes.
    private(set) var download: DownloadStats?

    init(shape: Job.Shape, removeMusic: Bool) {
        self.shape = shape
        self.removeMusic = removeMusic
    }

    /// 0…1, monotonic, exactly 1.0 once `publish` completes.
    var fraction: Double { pct / 100 }

    /// The concurrent audio branch's share of the bar. Zero on every shape
    /// where the audio branch *is* the bar.
    var audioShare: Double {
        switch shape {
        case .combined: 43
        case .segmented: removeMusic ? 40 : 0
        case .audioOnly, .musicOnly, .censorOnly: 0
        }
    }

    /// Exclusive download bar. Conversion also has its own bar; filter progress starts at 0
    /// via a fresh `JobProgress` — mixing them on one scale would make
    /// "Downloading 80 %" jump to "Pass 1 5 %".
    /// An unknown total leaves the bar where it is; the stats still move.
    mutating func postDownload(_ stats: DownloadStats) {
        stage = .download
        download = stats
        if let f = stats.fraction { pct = f * 100 }
    }

    /// - Parameter sub: 0…1 within `stage`.
    mutating func post(_ stage: Job.Stage, _ sub: Double) {
        let s = min(max(sub, 0), 1)
        self.stage = stage
        if stage != .download { download = nil }
        if stage == .download || stage == .convert {
            pct = max(pct, s * 100)
            return
        }

        if stage == .separate, audioShare > 0 {
            audioPct = max(audioPct, audioShare * s)
            pct = max(pct, videoPct + audioPct)
            return
        }
        guard let b = Self.band(stage, shape: shape, removeMusic: removeMusic) else { return }
        let v = b.lowerBound + (b.upperBound - b.lowerBound) * s
        switch stage {
        case .analyze, .render:
            videoPct = max(videoPct, v)
            pct = max(pct, videoPct + audioPct)
        default:
            pct = max(pct, v)
        }
    }

    /// nil for a stage that shape never runs, and for the concurrent
    /// `separate` share — which has a span but no base.
    static func band(_ stage: Job.Stage, shape: Job.Shape,
                     removeMusic: Bool) -> ClosedRange<Double>? {
        switch (shape, stage) {
        case (_, .download): 0...100
        case (.censorOnly, .analyze): 0...50
        case (.censorOnly, .render): 50...100
        case (.censorOnly, .publish): 100...100

        case (.musicOnly, .separate): 1...93
        case (.musicOnly, .mux): 93...99
        case (.musicOnly, .publish): 99...100

        case (.audioOnly, .separate): 1...99
        case (.audioOnly, .publish): 99...100

        case (.combined, .analyze): 0...25
        case (.combined, .render): 25...50
        case (.combined, .mux): 93...99
        case (.combined, .publish): 99...100

        case (.segmented, .analyze): removeMusic ? 0...25 : 0...40
        case (.segmented, .render): removeMusic ? 25...50 : 40...90
        case (.segmented, .concat): 90...99
        case (.segmented, .publish): 99...100

        default: nil
        }
    }
}

// MARK: - ETA

/// Two estimates coexist deliberately: one before there is any evidence, one as
/// soon as there is.
enum Eta {

    /// One notion of "long", with five consumers. `Checkpoint.plan` and the
    /// resumable-audio tests read the **source duration**; the confirm dialog
    /// and the share sheet read the **estimated wall clock**. That split is
    /// deliberate and must be preserved.
    static let confirmThresholdMs: Int64 = 30 * 60 * 1000

    /// Below this the live ETA is not shown at all — `0` means "too early to
    /// say" and every surface hides the line rather than printing a number.
    static let minPctForEta: Double = 3

    /// The up-front floor.
    ///
    /// The factors are S23 asymptotes and are deliberately **not** rescaled for
    /// Apple silicon yet: the only Apple numbers so far are simulator runs
    /// (`m0-results.md`), and a floor that over-quotes is the safe direction.
    /// Re-measure on device in M7 and change them here.
    ///
    /// Known error, carried over: fixed cost (loading an 88 MB htdemucs graph,
    /// standing up ORT sessions) is not modelled, so a clip under ~2 min is
    /// quoted low. Deliberately uncorrected.
    static func estimateMs(durationMs: Int64, ops: FilterOps) -> Int64 {
        guard durationMs > 0, ops.isValid else { return 0 }
        let factor: Double = switch ops.shape {
        case .censorOnly: 0.28
        case .musicOnly: 0.68
        case .both: 1.0
        }
        // Body targets add YOLO11 at 10 fps: the 53 s regression clip analyzed
        // in ~0.17x its length on an M3, against 0.06x for faces. ponytail: one
        // Mac-measured surcharge for both; re-measure on device alongside the S23 factors.
        let body: Double = ops.censor && ops.censorTarget != .face ? 0.12 : 0
        return Int64(Double(durationMs) * (factor + body))
    }

    /// Straight-line extrapolation over *overall* percent.
    ///
    /// Known ceiling, measured on Android: analyze spends 25 progress points on
    /// 73 min while render spends 25 on ~10 min, so this over-promises the
    /// moment render ends and htdemucs starts. Left as-is rather than re-guessed.
    static func liveMs(elapsedMs: Double, pct: Double) -> Int64 {
        guard pct >= minPctForEta else { return 0 }
        return Int64(elapsedMs * (100 - pct) / pct)
    }
}

// MARK: - Failures

/// Every failure the UI can show, as a case rather than a message.
///
/// Android learned this twice: a raw throwable message reached the screen
/// verbatim as untranslated developer text ("separator emitted 3 of 4 frames"),
/// and a case also re-localizes if the user changes language after the job
/// failed. The throwable is still logged in full by the caller.
enum JobFailure: String, Error, Codable, Sendable, Equatable {
    case nothingSelected
    case drmProtected
    case noVideoTrack
    case noAudioTrack
    case unsupportedContainer
    case unsupportedCodec
    case lowSpace
    case outOfSpace
    case sourceUnreadable
    /// Its own case and not `publishFailed`: it is the one publish failure the
    /// user can actually fix, and the only one worth naming.
    case photosDenied
    case publishFailed
    /// The OS took the app away — the work is paused, not broken. Separate
    /// from `.generic` so the screen can stop putting "Filtering failed." above
    /// a Resume button.
    case interrupted
    case downloadUnsupported
    case downloadNetwork
    /// Removed, private, members-only, age-gated: nothing a retry changes.
    case downloadUnavailable
    case downloadGeo
    case downloadRateLimited
    case downloadForbidden
    /// The site changed under the extractor; only a newer app or yt-dlp helps.
    case downloadExtractor
    case downloadGeneric
    case generic

    /// Concatenates the whole cause chain, lowercases, then matches in this
    /// order — specific cases shadow `generic`.
    static func of(_ error: any Error) -> JobFailure {
        if let f = error as? JobFailure { return f }
        if let d = error as? DownloadError {
            switch d {
            case .unsupported: return .downloadUnsupported
            case .network: return .downloadNetwork
            case .noSpace: return .lowSpace
            case .cancelled: return .generic
            case .unavailable: return .downloadUnavailable
            case .geo: return .downloadGeo
            case .rateLimited: return .downloadRateLimited
            case .forbidden: return .downloadForbidden
            case .extractor: return .downloadExtractor
            case .noFile, .generic: return .downloadGeneric
            }
        }
        if let p = error as? PreflightFailure {
            switch p {
            case .drmProtected: return .drmProtected
            case .noVideoTrack: return .noVideoTrack
            case .noAudioTrack: return .noAudioTrack
            case .unsupportedContainer: return .unsupportedContainer
            case .lowSpace: return .lowSpace
            case .sourceUnreadable: return .sourceUnreadable
            case .photosDenied: return .photosDenied
            }
        }
        if let p = error as? PublishError, case .photosDenied = p { return .photosDenied }
        if error is PublishError { return .publishFailed }

        let text = causeChain(error).lowercased()
        if text.contains("enospc") || text.contains("no space left") { return .outOfSpace }
        if text.contains("crypto") || text.contains("drm") { return .drmProtected }
        // "failed to initialize" is the decoder's own wording when the device
        // has no codec for a mime; it names neither "codec" nor "decoder", so
        // without it the most likely failure of an exotic audio track lands on
        // `generic`.
        if text.contains("codec") || text.contains("decoder") || text.contains("encoder")
            || text.contains("failed to initialize") { return .unsupportedCodec }
        if let c = error as? CocoaError, c.isFileError { return .sourceUnreadable }
        if error is POSIXError { return .sourceUnreadable }
        return .generic
    }

    private static func causeChain(_ error: any Error) -> String {
        var parts: [String] = []
        var queue: [any Error] = [error]
        while !queue.isEmpty {
            let e = queue.removeFirst()
            parts.append(String(describing: e))
            let ns = e as NSError
            parts.append(ns.localizedDescription)
            queue.append(contentsOf: ns.underlyingErrors)
        }
        return parts.joined(separator: " ")
    }
}

/// Thrown when a run stopped short of finishing. `.userCancelled` is a
/// cancellation and not a failure; `.interrupted` is the resumable one.
struct JobStopped: Error, Sendable, Equatable {
    var reason: JobRunner.Stop
    var resumable: Bool
}
