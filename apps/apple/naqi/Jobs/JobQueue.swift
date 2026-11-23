import Foundation
import os

/// A strictly serial queue: one job at a time, in enqueue order.
///
/// Two jobs at once would both want the P-cores ORT needs and the ~1.3 GB
/// htdemucs is resident for, and the second would only make the first slower —
/// there is nothing to overlap that `JobRunner`'s two branches are not already
/// overlapping inside a single job.
///
/// The queue file is the authority on a job's outcome, not the runner's return
/// value: a queued run that fails must not take the rest of the queue with it,
/// which is the whole reason Android kept a `queue.json` alongside WorkManager.
actor JobQueue {
    static let shared = JobQueue()

    struct Snapshot: Sendable {
        var jobs: [Job]
        var running: Job.ID?
        /// Never persisted. On relaunch the process is gone and the checkpoint,
        /// not a stored percent, decides how much of the job survived.
        var progress: JobProgress?
    }

    private let storeURL: URL
    private(set) var jobs: [Job] = []
    private var runningID: Job.ID?
    /// When the running job actually started working. Not its `enqueuedAt`:
    /// time spent waiting behind another job is not time this one has spent,
    /// and the live ETA divides by it.
    private var runningSince: Date?
    private var progress: JobProgress?
    private var running: Task<Void, Never>?
    /// A BGProcessing grant owns one checkpointed job, never the whole queue.
    private var stopAfterCurrent = false
    private var observers: [UUID: AsyncStream<Snapshot>.Continuation] = [:]

    /// Polled by the running job from AVFoundation's own queues, so it lives in
    /// a lock rather than in actor state.
    private let stopFlag = OSAllocatedUnfairLock<JobRunner.Stop?>(initialState: nil)

    typealias Runner = @Sendable (Job, _ progress: @escaping @Sendable (JobProgress) -> Void,
                                  _ stop: @escaping @Sendable () -> JobRunner.Stop?) async throws -> JobRunner.Completion
    typealias Prefetch = @Sendable (_ url: String, DownloadQuality, Processing,
                                    _ isCancelled: @escaping @Sendable () -> Bool) async throws -> Void

    private let run: Runner
    private let prefetch: Prefetch
    /// Low Power Mode and a hot device: a download nobody is waiting for yet
    /// is the first thing to give up.
    private let mayPrefetch: @Sendable () -> Bool
    /// The queued job being downloaded ahead, and the flag that stops it.
    ///
    /// ponytail: one look-ahead only. Filtering is the long pole, so one link
    /// ahead already hides every download but the first; two would only split
    /// the bandwidth with nothing more to hide.
    private var lookAhead: (id: Job.ID, stop: OSAllocatedUnfairLock<Bool>)?
    /// Every look-ahead and quarantine discard, chained. A job waits for it
    /// before its own download, so two writers never share a `.part`.
    private var lookAheadTail: Task<Void, Never>?

    static var defaultStore: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        return base.appendingPathComponent("naqi-queue.json")
    }

    init(storeURL: URL = JobQueue.defaultStore,
         run: @escaping Runner = { try await JobRunner.run($0, progress: $1, stop: $2) },
         prefetch: @escaping Prefetch = {
             _ = try await Downloader.download(url: $0, quality: $1, processing: $2, isCancelled: $3)
         },
         mayPrefetch: @escaping @Sendable () -> Bool = {
             !ProcessInfo.processInfo.isLowPowerModeEnabled
                 && ![.serious, .critical].contains(ProcessInfo.processInfo.thermalState)
         }) {
        self.storeURL = storeURL
        self.run = run
        self.prefetch = prefetch
        self.mayPrefetch = mayPrefetch
        jobs = Self.load(storeURL)
    }

    // MARK: Mutations

    /// Adding the same (source, options) twice is a no-op while the first copy
    /// is still live.
    ///
    /// Android learned this as KEEP-not-REPLACE: replacing meant one stray tap
    /// on Start cancelled a job that could be four hours in, and the UI's
    /// disabled button is the half that can be raced.
    @discardableResult
    func enqueue(_ job: Job) -> Job.ID {
        let key = Checkpoint.key(source: job.source, ops: job.ops, quality: job.quality)
        if let existing = jobs.first(where: {
            !$0.state.isTerminal
                && Checkpoint.key(source: $0.source, ops: $0.ops, quality: $0.quality) == key
        }) {
            Log.job.notice("enqueue ignored, already queued")
            // Still drain. `load()` resets a `.running` row to `.pending` on
            // relaunch and nothing runs at init, so the row this call matched
            // may be a survivor that no longer has a task behind it. Returning
            // early left it pending forever and the progress screen sat on
            // "Starting… 0 %". `drain()` is a no-op when a job is already live.
            drain()
            return existing.id
        }
        jobs.append(job)
        commit()
        drain()
        return job.id
    }

    /// Cancelling the running job stops it within one chunk; cancelling a
    /// queued one just marks it. Android had to re-append every survivor here
    /// because WorkManager cascades a cancellation down the chain — a serial
    /// actor has no chain, so there is nothing to repair.
    func cancel(_ id: Job.ID) {
        if id == runningID {
            stopFlag.withLock { $0 = .userCancelled }
            running?.cancel()
            return
        }
        update(id) { $0.state = .cancelled }
        if let job = jobs.first(where: { $0.id == id }) { dropDownload(of: job) }
    }

    /// Not a special code path: the same (source, options) lands on the same
    /// job key, finds whatever the last attempt checkpointed, and resumes.
    func retry(_ id: Job.ID) {
        update(id) { $0.state = .pending }
        drain()
    }

    func remove(_ id: Job.ID) {
        if id == runningID {
            cancel(id)
        } else if let job = jobs.first(where: { $0.id == id }) {
            dropDownload(of: job)
        }
        jobs.removeAll { $0.id == id }
        commit()
    }

    func clearFinished() {
        // Only a copy that also went into Photos. A Photos refusal or an audio
        // output leaves the in-app file as the user's *only* copy, and clearing
        // the list must not take it with it.
        for job in jobs {
            if case .done(let published) = job.state, published.assetID != nil, let url = published.url {
                OutputLibrary.remove(url)
            }
        }
        jobs.removeAll {
            switch $0.state {
            case .done, .cancelled: true
            case .failed(_, let resumable): !resumable
            case .pending, .running: false
            }
        }
        commit()
    }

    // MARK: Survivors

    /// Jobs a relaunch left behind. `load()` resets a `.running` row to
    /// `.pending` and nothing drains at init, so without this they sit in
    /// `naqi-queue.json` forever and the bookmark and checkpoint machinery
    /// built to survive a relaunch has no trigger.
    ///
    /// Read-only on purpose: a job that resumes itself while the user is
    /// looking at the picker — burning an hour of battery they did not ask
    /// for — is worse than one that waits to be asked.
    ///
    /// ponytail: "pending" and not "left over", so a row merely queued behind a
    /// live job is in here too. At launch, which is the only time anything asks,
    /// nothing is live and the two sets are the same.
    func resumable() -> [Job] {
        jobs.filter { if case .pending = $0.state { true } else { false } }
    }

    /// Puts one back in flight. The same path `retry` takes, because it is the
    /// same thing: the row is already `.pending` and `drain()` is what starts
    /// it.
    func resume(_ id: Job.ID) { retry(id) }

    /// Starts the first checkpointed survivor when iOS grants a processing
    /// window. A fresh process loads an interrupted `.running` row as pending;
    /// a process that survived suspension has already recorded `.failed` with
    /// `resumable = true`, so both routes meet here.
    func startResumableHead() -> Job.ID? {
        guard runningID == nil else { return nil }
        guard let job = jobs.first(where: {
            switch $0.state {
            case .pending: true
            case .failed(_, let resumable): resumable
            default: false
            }
        }) else { return nil }
        if job.state.isTerminal { update(job.id) { $0.state = .pending } }
        stopAfterCurrent = true
        drain()
        return runningID
    }

    nonisolated func signalBackgroundExpiration() {
        stopFlag.withLock { $0 = .interrupted }
    }

    func hasResumableJob() -> Bool {
        jobs.contains {
            switch $0.state {
            case .pending: true
            case .failed(_, let resumable): resumable
            default: false
            }
        }
    }

    /// A foreground launch owns the serial queue again. If the processing
    /// grant is still running, its current job continues and may drain the next
    /// row when it finishes. If it already ended, drain now.
    func continueInForeground() {
        stopAfterCurrent = false
        drain()
    }

    /// Drops the row *and* the scratch it was holding. `remove` alone leaves a
    /// work directory behind for the 7-day sweep, which on a half-rendered film
    /// is gigabytes the user just said they did not want.
    ///
    /// ponytail: keyed off `job.source`, the same way `enqueue` dedupes. A
    /// bookmark that re-resolves to a different URL than the one stored took a
    /// different key in `JobRunner`, and that directory is left to the sweep.
    func discard(_ id: Job.ID) {
        if let job = jobs.first(where: { $0.id == id }) {
            WorkDir.clear(Checkpoint.key(source: job.source, ops: job.ops))
            if case .done(let published) = job.state, published.assetID != nil, let url = published.url {
                OutputLibrary.remove(url)
            }
        }
        remove(id)
    }

    // MARK: Storage

    /// How long a finished video's in-app copy outlives its twin in Photos.
    static let copyLifetime: TimeInterval = 7 * 24 * 60 * 60

    /// What Settings › Storage shows.
    struct StorageUse: Sendable, Equatable {
        /// In-app copies of videos that are also in Photos. Safe to delete.
        var copies: Int64 = 0
        /// Checkpoints, downloads and shared-in inputs.
        var temporary: Int64 = 0
        /// Everything else in `OutputLibrary`: audio, Photos refusals, files
        /// from before this existed. The user's only copy, so never swept.
        var onlyCopies: Int64 = 0
    }

    /// In-app files that are a second copy of a video already in Photos.
    private var spareCopies: [URL] {
        jobs.compactMap {
            guard case .done(let p) = $0.state, p.assetID != nil, let url = p.url,
                  OutputLibrary.owns(url) else { return nil }
            return url
        }
    }

    /// Inputs a row may still run on. Cancelled and failed rows count, since
    /// both can be retried.
    private var neededInputs: Set<String> {
        Set(jobs.filter { if case .done = $0.state { false } else { true } }
            .map { $0.source.resolvingSymlinksInPath().path })
    }

    /// A shared-in file or a Photos pick's transfer copy: bytes the app made
    /// for itself. A file picked in place is the user's and never matches.
    private static func isOwnCopy(_ url: URL) -> Bool {
        let path = url.resolvingSymlinksInPath().path
        return [ShareInbox.adoptedRoot, FileManager.default.temporaryDirectory].contains {
            path.hasPrefix($0.resolvingSymlinksInPath().path + "/")
        }
    }

    private func discardInput(_ url: URL) {
        guard Self.isOwnCopy(url), !neededInputs.contains(url.resolvingSymlinksInPath().path) else { return }
        try? FileManager.default.removeItem(at: url)
    }

    /// Once per launch, before the first share drain. Drops Photos twins past
    /// `copyLifetime`, and shared-in inputs no row needs — which also collects
    /// the ones builds before `discardInput` left behind. Never mid-drain: an
    /// adopted share is on disk a moment before its row is enqueued.
    func sweepStorage() {
        dropExpiredCopies()
        let needed = neededInputs
        for f in (try? FileManager.default.contentsOfDirectory(
            at: ShareInbox.adoptedRoot, includingPropertiesForKeys: nil)) ?? []
        where !needed.contains(f.resolvingSymlinksInPath().path) {
            try? FileManager.default.removeItem(at: f)
        }
    }

    func dropExpiredCopies(now: Date = .now) {
        for url in spareCopies {
            let age = (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate.map { now.timeIntervalSince($0) } ?? 0
            if age > Self.copyLifetime { OutputLibrary.remove(url) }
        }
    }

    func storageUse() -> StorageUse {
        let copies = spareCopies.reduce(0) { $0 + Self.bytes($1) }
        return StorageUse(
            copies: copies,
            temporary: [WorkDir.root, Downloader.root, ShareInbox.adoptedRoot].reduce(0) { $0 + Self.bytes($1) },
            onlyCopies: max(0, Self.bytes(OutputLibrary.root) - copies))
    }

    func clearCopies() {
        spareCopies.forEach(OutputLibrary.remove)
    }

    /// Refused while a job is queued or running: its checkpoints are in here.
    /// A failed row restarts from zero.
    var canClearTemporary: Bool { !jobs.contains { !$0.state.isTerminal } }

    /// Checkpoints and downloads only. Shared-in inputs are left to
    /// `sweepStorage`, which cannot race a drain.
    func clearTemporary() {
        guard canClearTemporary else { return }
        for root in [WorkDir.root, Downloader.root] {
            for f in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? [] {
                try? FileManager.default.removeItem(at: f)
            }
        }
    }

    /// Allocated bytes, file or tree.
    private static func bytes(_ url: URL) -> Int64 {
        let keys: Set<URLResourceKey> = [.totalFileAllocatedSizeKey, .fileAllocatedSizeKey]
        func size(_ u: URL) -> Int64 {
            // A row's URL is the same instance every call, and URL caches
            // resource values: without this a deleted copy still weighs in.
            var u = u
            u.removeAllCachedResourceValues()
            let v = try? u.resourceValues(forKeys: keys)
            return Int64(v?.totalFileAllocatedSize ?? v?.fileAllocatedSize ?? 0)
        }
        guard let walker = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys))
        else { return size(url) }
        var total = size(url)
        while let f = walker.nextObject() as? URL { total += size(f) }
        return total
    }

    // MARK: Observation

    func observe() -> AsyncStream<Snapshot> {
        let (stream, continuation) = AsyncStream<Snapshot>.makeStream(bufferingPolicy: .bufferingNewest(1))
        let id = UUID()
        observers[id] = continuation
        continuation.onTermination = { [weak self] _ in
            Task { await self?.stopObserving(id) }
        }
        continuation.yield(snapshot())
        return stream
    }

    private func stopObserving(_ id: UUID) { observers[id] = nil }

    private func snapshot() -> Snapshot {
        Snapshot(jobs: jobs, running: runningID, progress: progress)
    }

    private func notify() {
        let s = snapshot()
        for c in observers.values { c.yield(s) }
    }

    // MARK: Draining

    private func drain() {
        guard running == nil,
              let next = jobs.first(where: { if case .pending = $0.state { true } else { false } })
        else {
            // A link queued behind a job that is already filtering.
            startLookAhead()
            return
        }
        runningID = next.id
        runningSince = .now
        progress = nil
        stopFlag.withLock { $0 = nil }
        update(next.id) { $0.state = .running }
        // Starting a job ends any look-ahead, its own included. Stopped, it
        // lets go of the `.part`, and the job's own download resumes from that
        // length with the job's progress on screen instead of a silent wait.
        stopLookAhead()
        let settled = lookAheadTail
        running = Task {
            await settled?.value
            await self.execute(next)
        }
        startLookAhead()
    }

    private func execute(_ job: Job) async {
        await Lifecycle.shared.jobStarted(title: job.title) { [weak self] in
            Task { await self?.flush() }
        }
        await LiveActivity.start(title: job.title)
        await Notify.requestAuthorization()
        let flag = stopFlag
        // What the lock-screen card ends on. Every exit path sets it; the
        // default is the one path that says nothing.
        var outcome = LiveActivity.Outcome.cancelled

        do {
            let done = try await run(
                job,
                { [weak self] p in Task { await self?.report(job.id, p) } },
                { flag.withLock { $0 } ?? (Lifecycle.shared.isInterrupted ? .interrupted : nil) })
            update(job.id) { $0.state = .done(done.output) }
            discardInput(job.source)
            Log.job.info("""
                done \(done.shape.rawValue, privacy: .public) in \(Int(done.wallMs))ms \
                resumed=\(done.resumed.map(\.rawValue).sorted().joined(separator: ","), privacy: .public)
                """)
            outcome = .done(name: done.output.name)
            await Notify.done(name: done.output.name)
        } catch let stopped as JobStopped {
            // An interruption is not a failure of the work — the OS took the
            // app away and the checkpoint survived. Saying so is what lets the
            // screen stop printing "Filtering failed." above a Resume button.
            update(job.id) {
                $0.state = stopped.reason == .userCancelled
                    ? .cancelled
                    : .failed(.interrupted, resumable: stopped.resumable)
            }
            // A deliberate cancel needs no announcement; an interruption is by
            // definition something that happened while the user was elsewhere.
            if stopped.reason != .userCancelled {
                // Only a stop that kept its checkpoint can honestly say
                // "open to resume".
                outcome = stopped.resumable ? .paused : .failed(.interrupted)
                await Notify.failed()
            }
        } catch {
            let failure = JobFailure.of(error)
            // The byte counts cannot ride on the failure case — see
            // `Job.shortfall` — so they are picked up from the preflight that
            // just computed them, and only for the failure they belong to.
            let shortfall = failure == .lowSpace ? Preflight.lastShortfall.withLock({ $0 }) : nil
            update(job.id) {
                $0.shortfall = shortfall
                $0.state = .failed(failure, resumable: false)
            }
            outcome = .failed(failure)
            await Notify.failed()
        }

        // Awaited here, not fired from a `defer` Task: `drain()` below starts
        // the next job, and a teardown that lost that race would switch off
        // the next job's keep-awake and leave it without a card.
        await Lifecycle.shared.jobFinished()
        await LiveActivity.end(outcome)

        runningID = nil
        runningSince = nil
        progress = nil
        running = nil
        notify()
        if stopAfterCurrent {
            stopAfterCurrent = false
        } else {
            drain()
        }
    }

    private func report(_ id: Job.ID, _ p: JobProgress) {
        guard id == runningID else { return }
        progress = p
        notify()
        startLookAhead()
        // The same straight-line extrapolation the Progress screen shows, off
        // the same clock — the lock screen is where a 90-minute job actually
        // lives, and `Eta.liveMs` returning 0 early on is what hides the line
        // rather than printing a number that will be wrong.
        let eta = Eta.liveMs(elapsedMs: Date.now.timeIntervalSince(runningSince ?? .now) * 1000,
                             pct: p.pct)
        let queued = jobs.count { if case .pending = $0.state { true } else { false } }
        Task { await LiveActivity.update(p, etaMs: eta, queued: queued) }
    }

    // MARK: Look-ahead

    /// Downloads the next queued link while the running job filters, so that
    /// job's own `Downloader.download` finds a completed record and goes
    /// straight to processing. Space is `Downloader`'s per-chunk check, as for
    /// any download. A failure is only logged: the job's own run meets it
    /// again and reports it properly.
    private func startLookAhead() {
        guard lookAhead == nil, !stopAfterCurrent,
              let current = jobs.first(where: { $0.id == runningID }),
              current.remoteURL == nil || (progress?.stage).map({ $0 != .download }) == true,
              let next = jobs.first(where: { $0.state == .pending }),
              let url = next.remoteURL, url != current.remoteURL,
              mayPrefetch()
        else { return }
        let (quality, processing) = JobRunner.downloadPlan(for: next)
        let stop = OSAllocatedUnfairLock(initialState: false)
        lookAhead = (next.id, stop)
        let flag = stopFlag, prior = lookAheadTail, prefetch = prefetch
        lookAheadTail = Task {
            await prior?.value
            do {
                // Backgrounding stops it with the running job. A user cancel
                // of the running job does not: it is not this job's cancel.
                try await prefetch(url, quality, processing) {
                    stop.withLock { $0 } || flag.withLock { $0 } == .interrupted || Lifecycle.shared.isInterrupted
                }
                Log.job.info("look-ahead download done")
            } catch {
                Log.job.notice("look-ahead download stopped: \(String(describing: error), privacy: .public)")
            }
        }
    }

    /// Resumable: the `.part` stays for whoever downloads that link next.
    private func stopLookAhead() {
        lookAhead?.stop.withLock { $0 = true }
        lookAheadTail?.cancel()
        lookAhead = nil
    }

    /// A queued link the user dropped takes its quarantine with it, once any
    /// look-ahead on it has let go. Kept while another live row shares the URL.
    private func dropDownload(of job: Job) {
        if lookAhead?.id == job.id { stopLookAhead() }
        guard let url = job.remoteURL,
              !jobs.contains(where: { $0.id != job.id && !$0.state.isTerminal && $0.remoteURL == url })
        else { return }
        let prior = lookAheadTail
        lookAheadTail = Task {
            await prior?.value
            Downloader.discard(remoteURL: url)
        }
    }

    private func update(_ id: Job.ID, _ transform: (inout Job) -> Void) {
        guard let i = jobs.firstIndex(where: { $0.id == id }) else { return }
        transform(&jobs[i])
        commit()
    }

    // MARK: Persistence

    /// Re-persists on demand. The queue already commits on every state change,
    /// so this only matters when the app is about to lose the foreground with a
    /// job mid-flight.
    func flush() { commit() }

    /// Whole-file rewrite on every change, committed temp+rename. The file is
    /// small (a handful of rows) and the alternative — a partially updated
    /// queue — is the one state the screen cannot recover from.
    private func commit() {
        if let data = try? JSONEncoder().encode(jobs) {
            try? Checkpoint.writeAtomically(data, to: storeURL)
        }
        notify()
    }

    /// A truncated or unparseable file starts the queue empty rather than
    /// bricking the screen. A job left `.running` is reset: the process died,
    /// the job did not, and the checkpoint decides how much it re-does.
    private static func load(_ url: URL) -> [Job] {
        guard let data = try? Data(contentsOf: url),
              var loaded = try? JSONDecoder().decode([Job].self, from: data)
        else { return [] }
        for i in loaded.indices where loaded[i].state == .running {
            loaded[i].state = .pending
        }
        return loaded
    }
}
