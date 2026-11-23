import CoreTransferable
import Foundation
import Photos
import SwiftUI
import UniformTypeIdentifiers
import os

/// The picked video and the one handle that can delete it later.
struct PickedSource: Sendable, Equatable, Identifiable {
    let url: URL
    let name: String
    /// `PHAsset` local identifier when the video came from the photo library.
    /// Without it "Delete original" has nothing to delete — a Photos pick hands
    /// back a copy in our own container, and deleting that would be a lie.
    var assetID: String?
    /// A Files pick hands back a security-scoped URL. Access opens when the
    /// pick lands and closes only when it is replaced: the job holds this URL
    /// for hours, so nothing may `stopAccessing` in a `defer`.
    var securityScoped = false

    var id: URL { url }
}

/// The Photos transfer. There is no zero-copy path out of PhotosUI —
/// `loadTransferable` writes the asset into a temp file and the copy is the
/// price of not asking for full-library authorization just to read one video.
struct MovieFile: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            // `received.file` is deleted the moment the closure returns, so the
            // copy is mandatory rather than an optimisation we could skip.
            let dest = FileManager.default.temporaryDirectory
                .appendingPathComponent(received.file.lastPathComponent)
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: received.file, to: dest)
            return MovieFile(url: dest)
        }
    }
}

/// True for a file the pick screen will take from a drag.
///
/// `.dropDestination(for: URL.self)` has no `allowedContentTypes` — it hands
/// over whatever file URL was dropped, including a folder or a PDF — so the
/// filter `fileImporter` gets for free has to be applied by hand, against the
/// same list. Rejecting here rather than queueing matters: a dropped PDF would
/// otherwise become a job that dies at preflight as "the file could not be
/// read", which blames the pipeline for a mis-drop.
///
/// Type comes from the file when the file is there, and from the extension when
/// it is not. Alongside WebM, `public.movie` and `public.audio` are the roots the importer
/// lists — `.video`, `.mpeg4Movie`, `.mp3`, `.wav` and the rest all conform to
/// one of them. A bare audio file is accepted because `removeMusic` is a real
/// job shape for it (`Job.shape`, `audioOnly`); censoring is not, which is what
/// `Flow.isAudioOnly` turns off.
func isDroppableSource(_ url: URL) -> Bool {
    let type = (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType)
        ?? UTType(filenameExtension: url.pathExtension)
    guard let type else { return false }
    return MediaTypes.importable.contains { type.conforms(to: $0) }
}

/// Four steps in a straight line. There is no route DSL because there is no
/// graph: Options is a detour off Pick, and Progress ends the flow.
///
/// State lives here rather than in each screen because a filter job runs for
/// minutes — dropping the user back on Pick with no route to the running job is
/// exactly the failure the Android version had to fix (spec contract 7.1.1).
@MainActor @Observable final class Flow {

    /// `settings` sits with `about` and `diagnostics` as a leaf off the overflow
    /// menu, not in the line: it edits the same `ops` Options does, but it is
    /// reachable without a picked video — which is the only way to see what a
    /// shared-in file will inherit.
    enum Step: Hashable, Sendable {
        case options, progress, done, jobs, settings, about, licenses, diagnostics
    }

    var path: [Step] = []
    var ops: FilterOps = .loadLastUsed()
    private(set) var source: PickedSource?
    /// 0 means "no estimate" — still probing, or the probe threw. A failure is
    /// deliberately silent: an unreadable source is Preflight's story to tell,
    /// and a broken probe must never stand between the user and Start.
    private(set) var durationMs: Int64 = 0
    /// Photos or a folder, and the folder itself. Resolved once per launch —
    /// `loadLastUsed` opens a security scope it never closes.
    private(set) var export = ExportTarget.loadLastUsed()
    /// `nil` until the probe answers, and it never becomes `nil` again for a
    /// source that probed. Only `false` locks the destination.
    private(set) var sourceHasVideo: Bool?
    /// Jobs a relaunch left in the queue file. Read once and never started on
    /// their own — the Pick screen offers them and the user decides.
    private(set) var resumableJobs: [Job] = []

    let monitor: JobMonitor

    /// - Parameter monitor: the app always watches the shared queue; a test
    ///   hands in one pointed at a scratch store so asserting what `start`
    ///   enqueues does not write into the user's `naqi-queue.json`.
    init(monitor: JobMonitor = JobMonitor()) { self.monitor = monitor }

    var canContinue: Bool { source != nil && ops.isValid }
    var estimateMs: Int64 { Eta.estimateMs(durationMs: durationMs, ops: ops) }

    /// The picked file has no picture, so there is nothing to censor and
    /// removing music is the only thing that can be done to it. Two consequences
    /// hang off this: the censor row is not offered, and Photos is not reachable
    /// — `PHAssetCreationRequest` refuses a bare audio resource and the runner
    /// turns that into `publishFailed`, a failure with no sentence the user can
    /// act on. The folder is therefore **forced** rather than defaulted: a
    /// default can be overridden back into a job that is certain to fail.
    ///
    /// `nil` — still probing, or the probe threw — is not audio-only.
    var isAudioOnly: Bool { sourceHasVideo == false }

    var destination: Destination { isAudioOnly ? .userFolder : export.destination }

    /// Start, as opposed to Pick's Continue. A folder destination with no
    /// folder throws `destinationUnwritable("no folder chosen")` at the last
    /// stage of the job — hours in, on a film. Disabling the button before the
    /// fact is the only version of that the user can do anything about.
    ///
    /// Deliberately not folded into `canContinue`: Pick's Continue is the only
    /// route to the screen that picks the folder, so gating it on the folder
    /// would strand an audio-only source with no way forward.
    var canStart: Bool { canContinue && (destination == .photos || export.folder != nil) }

    func setSource(_ new: PickedSource) {
        if let old = source, old.securityScoped { old.url.stopAccessingSecurityScopedResource() }
        source = new
        durationMs = 0
        sourceHasVideo = nil
        Task { [url = new.url] in
            let probed = await Self.probe(url)
            guard source?.url == url else { return }
            adoptProbe(ms: probed.ms, hasVideo: probed.hasVideo)
        }
    }

    /// What the probe's answer changes. Split out so the screenshot harness
    /// poses the same state the probe would rather than a subset of it.
    private func adoptProbe(ms: Int64, hasVideo: Bool?) {
        durationMs = ms
        sourceHasVideo = hasVideo
        // Options carried over from the last run can only produce a job an
        // audio file is certain to fail. The row stays editable — turning music
        // removal off simply leaves nothing to do, and Continue greys out the
        // way it does for a video with both operations off.
        ops.fit(hasVideo: hasVideo)
    }

    func adoptFileImport(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        setSource(PickedSource(url: url, name: url.lastPathComponent, securityScoped: scoped))
    }

    func setDestination(_ new: Destination) {
        export.destination = new
        export.saveAsLastUsed()
    }

    /// Opens the folder's security scope and keeps it open. The job publishes
    /// into it minutes or hours from now, so nothing may `stopAccessing` in a
    /// `defer` — the same rule the picked source lives under.
    func setFolder(_ url: URL) {
        if let old = export.folder, old != url { old.stopAccessingSecurityScopedResource() }
        _ = url.startAccessingSecurityScopedResource()
        export.folder = url
        export.destination = .userFolder
        export.saveAsLastUsed()
    }

    func start() async {
        guard let source, canStart else { return }
        ops.saveAsLastUsed()
        path = [.progress]
        await monitor.start(source: source, ops: ops,
                            destination: destination, folder: export.folder)
    }

    /// Queue a pasted or shared link: fetch with yt-dlp, then filter (or just
    /// publish, when both toggles are off — a download is still work).
    func startLink(_ url: String, quality: DownloadQuality, ops: FilterOps) async {
        var ops = ops
        if quality == .audio { ops.fit(hasVideo: false) }
        ops.saveAsLastUsed()
        quality.saveAsLastUsed()
        path = [.progress]
        let dest: Destination = quality == .audio ? .userFolder : export.destination
        let folder = dest == .userFolder ? (export.folder ?? OutputLibrary.root) : export.folder
        await monitor.startLink(url, quality: quality, ops: ops,
                                destination: dest, folder: folder)
    }

    /// Enqueues whatever the share extension left in the App Group container,
    /// with the destination this launch resolved. Returns how many it took, so
    /// a test can assert the choice travelled rather than that the call exists.
    ///
    /// - Parameter queue: the app always drains into the shared one; a test
    ///   points it at a scratch store so it does not enqueue into the user's
    ///   real `naqi-queue.json`.
    @discardableResult
    func drainSharedIn(into queue: JobQueue = .shared) async -> Int {
        // `export`, not `destination`: `isAudioOnly` describes the *picked*
        // source, and a shared-in file is a different one.
        await ShareInbox.drain(into: queue,
                               destination: export.destination, folder: export.folder)
    }

    /// A share-in starts the job on the queue but does not bind Progress unless
    /// something adopts it. Pick is the only screen this steals: Options or
    /// Jobs already have the user's attention.
    func revealSharedIn() {
        guard path.isEmpty else { return }
        guard let id = monitor.runningID ?? monitor.activeJobs.first?.id else { return }
        monitor.adopt(id)
        path = [.progress]
    }

    func cancelJob() async {
        await monitor.cancel()
        path = []
    }

    // MARK: - Unfinished jobs

    /// What a relaunch left behind. Read **once, at launch**, and deliberately
    /// not refreshed on every activation: `resumable()` answers "pending", and a
    /// share-in of four files leaves three rows pending behind the one that is
    /// running. At launch nothing is live and the two sets are the same, which
    /// is the only moment "pending" means "died with the app".
    ///
    /// - Parameter queue: the app always reads the shared one; the parameter is
    ///   there for the same reason `drainSharedIn` has one.
    func loadResumable(from queue: JobQueue = .shared) async {
        resumableJobs = await queue.resumable()
    }

    /// Puts a survivor back in flight and points the screens at it.
    ///
    /// Two calls, because they do two different things. `resume` is what starts
    /// the row. `monitor.start` is the only way to bind `JobMonitor` to a job it
    /// did not enqueue itself: `JobQueue.enqueue` is KEEP-not-REPLACE, so the
    /// identical (source, options) matches the row we just revived and hands
    /// back *its* id instead of creating a second one.
    ///
    /// Order is load-bearing — `resume` after `start` would write the row that
    /// is now running back to `.pending`.
    ///
    /// The source is deliberately **not** adopted as the picked one: `setSource`
    /// probes the file and lets `ops.fit` rewrite `ops` from the answer, so
    /// resuming an audio-only job would turn the user's *saved* censoring
    /// default off as a side effect of tapping Resume. The job carries its own
    /// options anyway. The cost is that Progress cannot name the file.
    func resumeJob(_ job: Job, in queue: JobQueue = .shared) async {
        forget(job)
        // Revive first, then bind. `adopt` watches the row the queue already
        // holds instead of re-enqueuing a rebuilt copy of it — the rebuilt one
        // only ever matched because `enqueue` is a KEEP and the checkpoint key
        // happened to agree, which is a lot of coincidence to rely on.
        await queue.resume(job.id)
        monitor.adopt(job.id)
        path = [.progress]
    }

    /// Drops the row **and** the scratch it was holding — on a half-rendered
    /// film that is gigabytes the user has just said they do not want.
    func discardJob(_ job: Job, in queue: JobQueue = .shared) async {
        forget(job)
        await queue.discard(job.id)
    }

    /// Local, not a re-read: the queue is the authority on the row, but the card
    /// has to leave the moment it is acted on, and re-asking would either race
    /// the resume or pick up rows a share-in has since queued.
    private func forget(_ job: Job) { resumableJobs.removeAll { $0.id == job.id } }

    func finishAndPickAnother() async {
        await monitor.finish()
        path = []
    }

    #if DEBUG
    /// Screenshot harness only — sets the fields without the probe, whose async
    /// answer would otherwise land after the seed and reset the duration.
    func seed(source: PickedSource, durationMs: Int64, hasVideo: Bool = true) {
        self.source = source
        adoptProbe(ms: durationMs, hasVideo: hasVideo)
    }
    #endif

    /// `hasVideo` is `nil` when the probe threw — "unknown", not "no video".
    /// Locking the destination on a source we could not read would trade
    /// Preflight's honest error for a silently different one.
    private static func probe(_ url: URL) async -> (ms: Int64, hasVideo: Bool?) {
        if MediaTypes.isWebM(url) {
            guard let info = try? await WebM.metadata(url) else { return (0, nil) }
            return (Int64(info.duration * 1000), info.hasVideo)
        }
        guard let src = try? await MediaSource.probe(url) else { return (0, nil) }
        return (src.duration.isNumeric ? Int64(src.duration.seconds * 1000) : 0, src.video != nil)
    }

    // MARK: - Delete original

    /// Best-effort and it **always reports failure**. Silently keeping a file
    /// the user asked to delete is worse than saying we could not (spec §5.4).
    /// Two-step and opt-in: the caller has already shown the confirm dialog.
    func deleteOriginal() async -> Bool {
        guard let source else { return false }
        if let assetID = source.assetID {
            let assets = PHAsset.fetchAssets(withLocalIdentifiers: [assetID], options: nil)
            guard assets.count > 0 else { return false }
            do {
                // The system puts up its own confirmation on top of ours. That
                // is one prompt too many by design: this is unrecoverable.
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetChangeRequest.deleteAssets(assets)
                }
                return true
            } catch {
                Log.job.error("delete original failed: \(error.localizedDescription, privacy: .public)")
                return false
            }
        }
        do {
            try FileManager.default.removeItem(at: source.url)
            return true
        } catch {
            Log.job.error("delete original failed: \(error.localizedDescription, privacy: .public)")
            return false
        }
    }
}
