import Foundation
import os

/// The screens' view of the one job this flow started.
///
/// `JobQueue` is an actor that publishes whole snapshots; this pulls the row
/// the flow owns out of each snapshot and exposes the four things the progress
/// and done screens actually read. Nothing here decides anything — the queue
/// file is the authority on a job's outcome, and a stale UI copy of it is the
/// bug that authority exists to prevent.
@MainActor @Observable final class JobMonitor {

    private(set) var job: Job?
    private(set) var progress: JobProgress?
    private(set) var activeJobs: [Job] = []
    private(set) var finishedJobs: [Job] = []
    private(set) var runningID: Job.ID?
    private(set) var queueProgress: JobProgress?

    /// How many other jobs the queue is still holding.
    ///
    /// A multi-file share-in enqueues N jobs and this flow watches exactly one
    /// of them; without this the other N−1 are invisible and the user's only
    /// evidence they exist is the app starting another job on its own.
    ///
    /// ponytail: a count, not a queue screen. The ceiling is that the other
    /// rows cannot be named, reordered or cancelled individually. Q2 — whether
    /// Mac batch is a first-class feature — is unanswered, and a list with
    /// per-row progress and cancel is the wrong thing to guess at before it is.
    /// If Q2 lands, `JobQueue.Snapshot.jobs` is already the entire model such a
    /// screen needs and this becomes the badge that opens it.
    private(set) var othersQueued = 0

    /// The app always watches the shared queue; a test points it at a scratch
    /// store so a run does not write into the user's `naqi-queue.json`.
    private let queue: JobQueue

    init(queue: JobQueue = .shared) {
        self.queue = queue
        observe()
    }

    private var jobID: Job.ID?
    private var observer: Task<Void, Never>?
    /// Wall clock for the live ETA. Not the job's `enqueuedAt`: time spent
    /// waiting behind another job is not time this one has spent working.
    private var startedAt: Date?

    // MARK: Derived state

    var stage: Job.Stage? { progress?.stage }
    var percent: Int { Int(((progress?.pct ?? 0)).rounded()) }

    var isRunning: Bool {
        if case .running = job?.state { return true }
        if case .pending = job?.state { return true }
        return false
    }

    /// 0 means "too early to say" and the line is hidden rather than showing a
    /// number.
    var etaMs: Int64 {
        guard let startedAt, let progress else { return 0 }
        return Eta.liveMs(elapsedMs: Date.now.timeIntervalSince(startedAt) * 1000, pct: progress.pct)
    }

    /// The publish record, once there is one.
    var published: Published? {
        if case .done(let p) = job?.state { return p }
        return nil
    }

    /// The name the filtered copy was saved under. Known on **both**
    /// destinations, so the Done screen can always name what it made.
    var outputName: String? { published?.name }

    /// The published file, but only if it is still there to open. A Photos
    /// publish keeps a copy in `OutputLibrary`; a folder publish writes into
    /// the chosen folder. Either way this is what Play, Share and Save use.
    var output: URL? {
        guard let url = published?.url else { return nil }
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// The library asset a Photos publish created. Kept as a fallback for
    /// queue rows written before the local copy existed.
    var assetID: String? { published?.assetID }

    var isDone: Bool { if case .done = job?.state { true } else { false } }

    /// Read back off the job rather than off the flow's current setting: the
    /// Done screen names where *this* copy went, and the picker is free to have
    /// moved on to something else by then.
    var destination: Destination? { job?.destination }
    var folderName: String? { job?.folder?.lastPathComponent }

    var failure: JobFailure? {
        if case .failed(let f, _) = job?.state { return f }
        return nil
    }

    /// The work directory still holds finished work the next attempt picks up.
    var isResumable: Bool {
        if case .failed(_, let resumable) = job?.state { return resumable }
        return false
    }

    /// What the preflight wanted against what the volume had, for the one
    /// failure whose sentence says nothing useful without them. `nil` on every
    /// other failure, and on a queue file written before the field existed.
    var shortfall: Job.Shortfall? { job?.shortfall }

    // MARK: Commands

    func start(source: PickedSource, ops: FilterOps,
               destination: Destination = .photos, folder: URL? = nil) async {
        let candidate = Job.capture(source: source.url, ops: ops,
                                    destination: destination, folder: folder,
                                    title: source.name)
        job = candidate
        progress = nil
        startedAt = .now
        // `enqueue` is a KEEP, not a REPLACE: an identical (source, options)
        // already in flight wins and its id comes back instead. Watching the id
        // we made up would bind the screen to a row the queue never created,
        // and the progress card would sit on "Starting…" forever.
        jobID = await queue.enqueue(candidate)
    }

    func startLink(_ url: String, quality: DownloadQuality, ops: FilterOps,
                   destination: Destination = .photos, folder: URL? = nil) async {
        let candidate = Job.captureLink(url, quality: quality, ops: ops,
                                        destination: destination, folder: folder)
        job = candidate
        progress = nil
        startedAt = .now
        jobID = await queue.enqueue(candidate)
    }

    /// Binds to a row the queue already holds, rather than one this flow just
    /// enqueued — a job that outlived the app and is being resumed from the pick
    /// screen. `observe` resolves the row out of the next snapshot, so there is
    /// nothing to hand in but the id.
    ///
    /// The caller revives the row *before* calling this: adopting a row that is
    /// still `.pending` is correct, but writing one back to `.pending` after the
    /// queue has started it is not.
    func adopt(_ id: Job.ID) {
        jobID = id
        job = nil
        progress = nil
        startedAt = .now
    }

    func cancel() async {
        guard let jobID else { return }
        await queue.cancel(jobID)
        detach()
    }

    func cancel(_ id: Job.ID) async { await queue.cancel(id) }

    func clearFinished() async { await queue.clearFinished() }

    func storageUse() async -> JobQueue.StorageUse { await queue.storageUse() }
    func canClearTemporary() async -> Bool { await queue.canClearTemporary }
    func clearCopies() async { await queue.clearCopies() }
    func clearTemporary() async { await queue.clearTemporary() }

    func discard(_ id: Job.ID) async { await queue.discard(id) }

    /// Not a special code path: the same (source, options) lands on the same job
    /// key and finds whatever the last attempt checkpointed.
    func resume() async {
        guard let jobID else { return }
        startedAt = .now
        await queue.retry(jobID)
    }

    /// Stops presenting the finished row. It remains in the queue as the
    /// in-app history until the user clears finished jobs from Activity.
    func finish() async {
        detach()
    }

    #if DEBUG
    /// Screenshot harness only — see `ScreenshotSeed.swift`.
    func seed(state: Job.State, progress: JobProgress?, othersQueued: Int = 0) {
        var j = Job(source: FileManager.default.temporaryDirectory
                        .appendingPathComponent("holiday-in-tabuk.mp4"),
                    title: "holiday-in-tabuk-naqi-1754320000000.mp4",
                    ops: .loadLastUsed(), destination: .photos)
        j.state = state
        job = j
        self.progress = progress
        self.othersQueued = othersQueued
        startedAt = Date().addingTimeInterval(-11 * 60)
    }
    #endif

    /// Every row that is neither this flow's nor finished.
    ///
    /// Terminal rows stay in `naqi-queue.json` until something clears them, so
    /// filtering them out is what makes the line disappear at zero instead of
    /// at never. A running row that is not ours counts too: the queue is
    /// strictly serial, so a job ahead of ours is as much "still to come" as a
    /// pending one, and it is the case a share-in of four videos produces.
    nonisolated static func othersQueued(in snapshot: JobQueue.Snapshot, besides id: Job.ID?) -> Int {
        snapshot.jobs.filter { $0.id != id && !$0.state.isTerminal }.count
    }

    private func detach() {
        jobID = nil
        job = nil
        progress = nil
        startedAt = nil
        othersQueued = 0
    }

    private func observe() {
        observer = Task { [weak self, queue] in
            for await snapshot in await queue.observe() {
                guard let self else { return }
                self.activeJobs = snapshot.jobs.filter {
                    if case .failed(_, let resumable) = $0.state { return resumable }
                    return !$0.state.isTerminal
                }
                self.finishedJobs = Array(snapshot.jobs.filter {
                    if case .failed(_, let resumable) = $0.state { return !resumable }
                    return $0.state.isTerminal
                }.reversed())
                self.runningID = snapshot.running
                self.queueProgress = snapshot.progress
                guard let id = self.jobID else { continue }
                self.job = snapshot.jobs.first { $0.id == id }
                self.progress = snapshot.running == id ? snapshot.progress : self.progress
                self.othersQueued = Self.othersQueued(in: snapshot, besides: id)
            }
        }
    }
}
