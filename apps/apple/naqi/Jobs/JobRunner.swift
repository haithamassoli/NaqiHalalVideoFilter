import AVFoundation
import Foundation
import os

/// Runs ONE job end to end.
///
/// Everything below is orchestration: the analyze, separate and render passes
/// are called, never reimplemented. What lives here is the shape dispatch, the
/// concurrency contract between the two branches, the checkpoint/resume ledger,
/// and the cancellation rules.
enum JobRunner {

    /// Why a run stopped short.
    ///
    /// **A stop is not automatically a cancel.** An expired iOS background
    /// grace, a jetsam kill and a reboot all arrive the same way, and those are
    /// exactly the cases resume exists to survive. Only `.userCancelled`
    /// destroys the work directory.
    enum Stop: String, Codable, Sendable, Equatable {
        case userCancelled, interrupted
    }

    struct Completion: Sendable {
        let output: Published
        let shape: Job.Shape
        /// Stages a checkpoint let this run skip entirely.
        let resumed: Set<Job.Stage>
        let wallMs: Double
    }

    /// - Parameter stop: polled from the pipelines' own queues, so it must be
    ///   cheap and thread-safe. Non-nil means "wind up now".
    /// - Parameter forcedSegmentMs: Android's `segment_ms` debug key. Overrides
    ///   the 5-minute segment length *and* the 30-minute gate, which is the only
    ///   way to drive the segmented route from a clip short enough to test. It
    ///   is part of `Checkpoint.key`, so a forced run cannot resume a normal
    ///   run's segments.
    static func run(_ job: Job,
                    progress: @escaping @Sendable (JobProgress) -> Void = { _ in },
                    stop: @escaping @Sendable () -> Stop? = { nil },
                    forcedSegmentMs: Int64 = 0) async throws -> Completion {
        let overallStarted = ContinuousClock.now
        // The head of a run is the only place that always executes, including
        // after a relaunch that went straight into a resumed job, so the
        // age-based sweep hangs off it.
        Checkpoint.sweepStale()
        Downloader.sweep()

        var job = job
        var downloaded: URL?
        var discardDownloaded = false
        var resolvedQuality: DownloadQuality?
        if let remote = job.remoteURL {
            let (quality, processing) = downloadPlan(for: job)
            resolvedQuality = quality
            if quality == .audio { job.ops.fit(hasVideo: false) }
            let file: URL
            do {
                file = try await Downloader.download(
                    url: remote, quality: quality, processing: processing,
                    onProgress: { stats in
                        var b = JobProgress(shape: .censorOnly, removeMusic: false)
                        b.postDownload(stats)
                        progress(b)
                    },
                    isCancelled: { stop() != nil })
            } catch DownloadError.cancelled {
                if stop() == .userCancelled { Downloader.discard(remoteURL: remote) }
                throw JobStopped(reason: stop() ?? .userCancelled, resumable: true)
            }
            downloaded = file
            job.title = file.deletingPathExtension().lastPathComponent
            if !job.ops.isValid {
                let ext = file.pathExtension.isEmpty ? (quality == .audio ? "m4a" : "mp4") : file.pathExtension
                let name = "\(job.title)-naqi-\(Int(Date.now.timeIntervalSince1970)).\(ext)"
                let dest: Destination = quality == .audio ? .userFolder : job.destination
                let folder = dest == .userFolder
                    ? (job.resolvedFolder ?? OutputLibrary.root) : job.resolvedFolder
                let published = try await Publish.save(file, named: name, to: dest, folder: folder)
                Downloader.discard(file)
                return Completion(output: published, shape: quality == .audio ? .audioOnly : .censorOnly,
                                  resumed: [], wallMs: msSince(overallStarted))
            }
        }

        guard job.ops.isValid else { throw JobFailure.nothingSelected }
        let opened: (url: URL, close: @Sendable () -> Void)
        if let downloaded {
            opened = (downloaded, {})
        } else {
            opened = try job.openSource()
        }
        let originalURL = opened.url
        let close = opened.close
        defer {
            close()
            if discardDownloaded, let downloaded { Downloader.discard(downloaded) }
        }

        let keyURL = job.remoteURL != nil ? job.source : originalURL
        let sourceIdentity = downloaded.flatMap(Downloader.sourceIdentity)
        let key = Checkpoint.key(source: keyURL, ops: job.ops, forcedSegmentMs: forcedSegmentMs,
                                 quality: resolvedQuality?.rawValue, sourceIdentity: sourceIdentity)
        let dir = WorkDir.job(key)
        defer {
            if !hasResumableWork(dir: dir) { WorkDir.clear(key) }
        }
        let url: URL
        do {
            url = try await WebM.prepare(originalURL, in: dir, onProgress: { fraction in
                var p = JobProgress(shape: .censorOnly, removeMusic: false)
                p.post(.convert, fraction)
                progress(p)
            }, isCancelled: { stop() != nil })
        } catch is CancellationError {
            if stop() == .userCancelled { WorkDir.clear(key); discardDownloaded = true }
            throw JobStopped(reason: stop() ?? .userCancelled, resumable: hasResumableWork(dir: dir))
        } catch {
            throw JobFailure.of(error)
        }

        // A file AVFoundation cannot parse (a half-written download) used
        // to surface as `.generic` — "Filtering failed." — when the truth is
        // that the file was never readable. Both cases share `errUnreadable`.
        let src: MediaSource
        do { src = try await MediaSource.probe(url) } catch {
            Log.job.error("probe failed: \(String(describing: error), privacy: .public)")
            throw JobFailure.unsupportedContainer
        }
        let durationMs = src.duration.isNumeric ? Int64(src.duration.seconds * 1000) : 0

        // **Only the render is segmented.** `Checkpoint.plan` carries the
        // 30-minute gate itself and returns empty below it, so an empty plan
        // *is* the unsegmented route — the one that stays byte-for-byte
        // unchanged for ordinary clips. `Job.shape` then ANDs it with
        // `ops.censor`, which is what "long source with a render stage" reduces
        // to: a music-only film has no render to slice.
        //
        // Analyze stays whole-film either way. `AnalyzePass`'s own doc block has
        // the reason: a face track split by a cut can take opposite gender
        // verdicts on the two halves, and the hysteresis and whole-frame floor
        // both span seams.
        let plan = Checkpoint.plan(durationMs: durationMs, forcedSegmentMs: forcedSegmentMs)
        let shape = Job.shape(ops: job.ops, hasVideoTrack: src.video != nil,
                              segmented: !plan.isEmpty)

        if let failure = await Preflight.check(
            source: src, ops: job.ops, segmented: shape == .segmented,
            extraCopies: job.destination == .photos ? 1 : 0) {
            throw JobFailure.of(failure)
        }

        // An `.m4a` in the photo library is invisible to every music player,
        // which is the only app that would want it (§5.1); Photos will not take
        // it at all. An audio-only job bound for Photos keeps its file in the
        // app's Documents instead of failing after the whole render.
        let publishTo: Destination = shape == .audioOnly && job.destination == .photos
            ? .userFolder : job.destination

        // Photos add-only is asked here rather than in the publish stage so the
        // permission sheet comes up while the user is still looking, not an
        // hour later. A refusal no longer stops the job: `Publish` keeps the
        // file in the app instead (the iPad build on a Mac is always refused).
        //
        // *After* the checks above, not before them. A permission sheet is the
        // wrong first answer to an unreadable file, which fails no matter what
        // the user taps.
        if await Preflight.photosAccess(for: publishTo) != nil {
            Log.job.notice("photo library refused; output will stay in the app")
        }

        let ext = shape == .audioOnly ? "m4a" : "mp4"
        let out = dir.appendingPathComponent("out.\(ext)")
        // A leftover from an attempt that died between the writer finishing and
        // the publish is not a checkpoint — nothing marks it complete.
        try? FileManager.default.removeItem(at: out)

        let bar = OSAllocatedUnfairLock(initialState: JobProgress(shape: shape,
                                                                  removeMusic: job.ops.removeMusic))
        // Sampling on the stage *change*, not on every sub-step: `post` is
        // called per frame, and a `task_info` trap per frame would be
        // instrumentation that changes what it measures.
        let lastStage = OSAllocatedUnfairLock<Job.Stage?>(initialState: nil)
        @Sendable func post(_ stage: Job.Stage, _ sub: Double) {
            let changed = lastStage.withLock { s -> Bool in
                guard s != stage else { return false }
                s = stage
                return true
            }
            if changed { MemoryFootprint.note(stage.rawValue) }
            progress(bar.withLock { p in p.post(stage, sub); return p })
        }
        @Sendable func stopping() -> Bool { stop() != nil }

        let resumed = OSAllocatedUnfairLock<Set<Job.Stage>>(initialState: [])
        let started = ContinuousClock.now
        let stage = Stage("job")
        // Per job, not per process: the number M7 needs is what ONE job peaks
        // at, and a stale high-water mark from a previous run would answer a
        // different question.
        MemoryFootprint.resetPeak()
        Log.job.info("""
            start \(shape.rawValue, privacy: .public) key=\(key, privacy: .public) \
            dur=\(durationMs)ms music=\(job.ops.removeMusic) censor=\(job.ops.censor) \
            mode=\(job.ops.processingMode.rawValue, privacy: .public) \
            thermal=\(ProcessInfo.processInfo.thermalState.rawValue) \
            lowPower=\(ProcessInfo.processInfo.isLowPowerModeEnabled)
            """)

        // Set where a render actually ran with the card, so the Done screen
        // never mentions a card a passthrough job does not have.
        var cardAdded = false
        do {
            switch shape {
            case .censorOnly:
                let edl = try await resolveEdl(src, ops: job.ops, dir: dir, resumed: resumed,
                                               post: post, isCancelled: stopping)
                if try await passthroughIfEligible(src, edl: edl, ops: job.ops,
                                                   output: out, isCancelled: stopping) {
                    post(.render, 1)
                } else {
                    _ = try await RenderPass.run(source: src, edl: edl, ops: job.ops, output: out,
                                                 endCard: job.ops.endCard,
                                                 progress: { post(.render, $0) },
                                                 isCancelled: stopping)
                    cardAdded = job.ops.endCard
                }

            case .musicOnly:
                // Separate into the checkpoint and mux, rather than one
                // `includeVideo: true` pass that writes the finished file
                // directly. htdemucs is the entire cost of this shape, and a
                // single-pass write meant an interruption at 95 % lost all of
                // it — the opposite of the guarantee every other shape gives.
                // The price is one compressed video passthrough copy, which is
                // container surgery: no re-encode, no quality change.
                let audio = dir.appendingPathComponent(Checkpoint.audioTrackName)
                try await separateOnce(src, ops: job.ops, to: audio,
                                       resumed: resumed, post: post, isCancelled: stopping)
                // `Remux` has no cancel hook, so this is the last stop point
                // before a full-size passthrough copy runs to completion.
                if stopping() { throw MediaError.cancelled }
                post(.mux, 0)
                try await Remux.mux(video: url, audio: audio, to: out, isCancelled: stopping)
                post(.mux, 1)

            case .audioOnly:
                let audio = dir.appendingPathComponent(Checkpoint.audioTrackName)
                try await separateOnce(src, ops: job.ops, to: audio,
                                       resumed: resumed, post: post, isCancelled: stopping)
                // A hard link gives Publish its movable output name without a
                // second physical audio copy. The checkpoint remains if
                // publishing fails; filesystems without hard links fall back.
                try linkOrCopy(audio, to: out)

            case .combined:
                let audio = dir.appendingPathComponent(Checkpoint.audioTrackName)
                let edl = try await bothBranches(src, ops: job.ops, dir: dir, audio: audio,
                                                 resumed: resumed, post: post, stop: stop)
                // Render *is* the mux here: the separated track is copied in
                // compressed while the picture is encoded, so there is no
                // second full-size pass the way Android's `mux.mp4` was.
                if try await passthroughIfEligible(src, edl: edl, ops: job.ops,
                                                   output: out, replacedAudio: audio,
                                                   isCancelled: stopping) {
                    post(.render, 1)
                } else {
                    _ = try await RenderPass.run(source: src, edl: edl, ops: job.ops, output: out,
                                                 replacedAudio: audio,
                                                 endCard: job.ops.endCard,
                                                 progress: { post(.render, $0) },
                                                 isCancelled: stopping)
                    cardAdded = job.ops.endCard
                }
                post(.mux, 1)

            case .segmented:
                let audio = dir.appendingPathComponent(Checkpoint.audioTrackName)
                let edl = job.ops.removeMusic
                    ? try await bothBranches(src, ops: job.ops, dir: dir, audio: audio,
                                             resumed: resumed, post: post, stop: stop)
                    : try await resolveEdl(src, ops: job.ops, dir: dir, resumed: resumed,
                                           post: post, isCancelled: stopping)
                // A segment cannot carry its own audio — per-segment AAC does
                // not concatenate — so the whole picture is joined first and
                // given one continuous track: the separated one when music was
                // removed, the source's own when it was not.
                let replacement = job.ops.removeMusic ? audio : nil
                if try await passthroughIfEligible(src, edl: edl, ops: job.ops,
                                                   output: out, replacedAudio: replacement,
                                                   isCancelled: stopping) {
                    post(.render, 1)
                    post(.concat, 1)
                } else {
                    try await renderSegments(src, ops: job.ops, edl: edl, plan: plan,
                                             dir: dir, output: out,
                                             audio: job.ops.removeMusic ? audio
                                                 : (src.audio != nil ? url : nil),
                                             resumed: resumed, post: post, isCancelled: stopping)
                    cardAdded = job.ops.endCard
                }
            }

            if stopping() { throw MediaError.cancelled }
            post(.publish, 0)
            // Through the bookmark, not `job.folder` directly: the plain URL
            // stops being writable once the app relaunches or the user picks a
            // different folder, and this is the last step of a job that may
            // have been rendering for an hour.
            let folder = publishTo == .userFolder
                ? (job.resolvedFolder ?? OutputLibrary.root) : job.resolvedFolder
            var published = try await Publish.save(out, named: outputName(for: originalURL, ext: ext),
                                                   to: publishTo, folder: folder)
            published.endCard = cardAdded
            post(.publish, 1)
            // Success takes the whole directory: the checkpoints only exist to
            // survive an interruption, and this run had none.
            WorkDir.clear(key)
            discardDownloaded = true

            let processingWall = msSince(started)
            let wall = msSince(overallStarted)
            MemoryFootprint.logPeak(shape.rawValue)
            stage.stop("\(shape.rawValue) processing=\(Int(processingWall))ms overall=\(Int(wall))ms resumed=\(resumed.withLock { $0.count })")
            return Completion(output: published, shape: shape,
                              resumed: resumed.withLock { $0 }, wallMs: wall)

        } catch {
            // No partial output file survives any failure path, not just a
            // user cancel.
            try? FileManager.default.removeItem(at: out)
            let reason = stop()
            if reason == .userCancelled { discardDownloaded = true }
            let resumable = reason != .userCancelled && hasResumableWork(dir: dir)
            if !resumable { WorkDir.clear(key) }
            stage.stop("\(shape.rawValue) stopped")
            if let reason {
                Log.job.notice("""
                    job \(shape.rawValue, privacy: .public) \(reason.rawValue, privacy: .public) \
                    resumable=\(resumable)
                    """)
                throw JobStopped(reason: reason, resumable: resumable)
            }
            // The cause is logged in full here and nowhere else; what reaches
            // the screen is a case, never this text.
            Log.job.error("""
                job \(shape.rawValue, privacy: .public) failed: \
                \(String(describing: error), privacy: .public) resumable=\(resumable)
                """)
            throw JobFailure.of(error)
        }
    }

    /// What a link job asks `Downloader.download` for. `JobQueue`'s look-ahead
    /// asks the same, so the job finds that download already complete; a plan
    /// that drifted would fetch one file and then download another.
    static func downloadPlan(for job: Job) -> (DownloadQuality, Processing) {
        let quality = DownloadQuality.of(job.quality).resolved(fast: job.ops.processingMode == .fast)
        var ops = job.ops
        if quality == .audio { ops.fit(hasVideo: false) }
        return (quality, processing(ops))
    }

    /// The format policy's view of the job: any visual filter wins, since it
    /// re-encodes and caps what is worth fetching; music-only keeps the
    /// picture untouched. No filter at all is a plain download.
    static func processing(_ ops: FilterOps) -> Processing {
        guard ops.isValid else { return .none }
        if ops.censor { return .visual }
        return ops.removeMusic ? .music : .none
    }

    /// Anything short of a user cancel keeps the work directory when it holds
    /// something the next attempt can reuse. Android keyed this off the shape
    /// and the 30-minute threshold (§2.10) because below that nothing was ever
    /// checkpointed; the unsegmented Apple route checkpoints the finished EDL
    /// and the separated audio track at any length, so the test is "is there
    /// anything there", not "is it long".
    static func hasResumableWork(dir: URL) -> Bool {
        let fm = FileManager.default
        for name in [Checkpoint.analysisName, Checkpoint.audioTrackName, Checkpoint.concatName, WebM.convertedName]
        where fm.fileExists(atPath: dir.appendingPathComponent(name).path) { return true }
        return Checkpoint.hasRenderedSegments(dir: dir)
    }

    /// Empty finalized EDLs and explicit visual no-ops keep compressed video.
    /// Fast may use this too when the source is already at or below its cap.
    /// No end card here: adding one would mean re-encoding the whole picture.
    private static func passthroughIfEligible(_ src: MediaSource, edl: Edl, ops: FilterOps,
                                              output: URL, replacedAudio: URL? = nil,
                                              isCancelled: @escaping @Sendable () -> Bool) async throws -> Bool {
        guard let video = src.video, video.isHDR == false,
              edl.isEmpty || ops.visualEffectIsNoop,
              video.capped(shortSide: ops.processingMode.outputShortSideCap).naturalSize == video.naturalSize
        else { return false }
        do {
            if let replacedAudio {
                try await Remux.mux(video: src.url, audio: replacedAudio, to: output,
                                    isCancelled: isCancelled)
            } else {
                try await Remux.passthrough(source: src.url, to: output,
                                            isCancelled: isCancelled)
            }
            Log.render.info("compressed video passthrough: finalized EDL has no visual effect")
            return true
        } catch MediaError.cancelled {
            throw MediaError.cancelled
        } catch {
            Log.render.notice("passthrough unavailable; encoding instead: \(error.localizedDescription, privacy: .public)")
            try? FileManager.default.removeItem(at: output)
            return false
        }
    }

    // MARK: - Branches

    /// analyze ‖ separate, then hand the EDL to the render that muxes them.
    ///
    /// Failure semantics come from the structure: whichever branch throws first
    /// wins, the shared abort flag releases the other one (its pump loop polls
    /// a flag, not task cancellation), and the group rethrows the original
    /// cause so the failure taxonomy sees the real error.
    private static func bothBranches(_ src: MediaSource, ops: FilterOps, dir: URL, audio: URL,
                                     resumed: OSAllocatedUnfairLock<Set<Job.Stage>>,
                                     post: @escaping @Sendable (Job.Stage, Double) -> Void,
                                     stop: @escaping @Sendable () -> Stop?) async throws -> Edl {
        let fm = FileManager.default
        let haveAudio = fm.fileExists(atPath: audio.path)
        let cached = Checkpoint.readEdl(dir: dir)
        if haveAudio { resumed.withLock { _ = $0.insert(.separate) }; post(.separate, 1) }
        if cached != nil { resumed.withLock { _ = $0.insert(.analyze) }; post(.analyze, 1) }
        if haveAudio, let cached { return cached }

        let edl = OSAllocatedUnfairLock<Edl?>(initialState: cached)
        let failed = OSAllocatedUnfairLock(initialState: false)
        @Sendable func aborted() -> Bool { stop() != nil || failed.withLock { $0 } }

        try await withThrowingTaskGroup(of: Void.self) { group in
            if !haveAudio {
                // QoS is the Apple-only dial Android could not reach, and the
                // highest impact-per-line item in `spec-performance.md` §1.4:
                // htdemucs is the wall on this shape with the video branch
                // sitting on hundreds of seconds of slack, so the audio branch
                // takes `.userInitiated` …
                group.addTask(priority: .userInitiated) {
                    try await separate(src, ops: ops, to: audio, includeVideo: false,
                                       progress: { post(.separate, $0) }, isCancelled: aborted)
                }
            }
            if cached == nil {
                // … and the video branch takes `.utility`. Priority is only a
                // scheduling hint, so the combined workload still needs device
                // traces; it does not promise a particular core assignment.
                group.addTask(priority: .utility) {
                    post(.analyze, 0)
                    let r = try await analyze(src, ops: ops, progress: { post(.analyze, $0) },
                                              isCancelled: aborted)
                    try Checkpoint.writeEdl(r, dir: dir)
                    edl.withLock { $0 = r }
                    post(.analyze, 1)
                }
            }
            // The pump loops do not observe task cancellation, so the abort
            // flag has to be raised before waiting on the sibling or a failure
            // on one side hangs the group.
            var first: (any Error)?
            while !group.isEmpty {
                do { try await group.next() }
                catch {
                    if first == nil { first = error }
                    failed.withLock { $0 = true }
                }
            }
            if let first { throw first }
        }

        guard let result = edl.withLock({ $0 }) else { throw MediaError.cancelled }
        // The separator's own last post is `100 * done / estimatedFrames`, which
        // lands on 99 whenever the estimate ran one chunk long — so the audio
        // share is closed here rather than left to arithmetic, and a fresh run's
        // bar matches a resumed one's exactly.
        post(.separate, 1)
        return result
    }

    private static func linkOrCopy(_ source: URL, to output: URL) throws {
        try? FileManager.default.removeItem(at: output)
        do { try FileManager.default.linkItem(at: source, to: output) }
        catch { try FileManager.default.copyItem(at: source, to: output) }
    }

    private static func resolveEdl(_ src: MediaSource, ops: FilterOps, dir: URL,
                                   resumed: OSAllocatedUnfairLock<Set<Job.Stage>>,
                                   post: @escaping @Sendable (Job.Stage, Double) -> Void,
                                   isCancelled: @escaping @Sendable () -> Bool) async throws -> Edl {
        if let cached = Checkpoint.readEdl(dir: dir) {
            resumed.withLock { _ = $0.insert(.analyze) }
            post(.analyze, 1)
            return cached
        }
        post(.analyze, 0)
        // `AnalyzePass` polls `isCancelled` itself, once per sampled frame.
        // This used to be a 200 ms sibling task that raced the pass and threw
        // to cancel it; the pass taking the closure directly deletes the race
        // along with the task.
        let edl = try await analyze(src, ops: ops, progress: { post(.analyze, $0) }, isCancelled: isCancelled)
        try Checkpoint.writeEdl(edl, dir: dir)
        post(.analyze, 1)
        return edl
    }

    /// Body mode's pass 1 on the server for a long source, else here.
    private static func analyze(_ src: MediaSource, ops: FilterOps,
                                progress: @escaping @Sendable (Double) -> Void,
                                isCancelled: @escaping @Sendable () -> Bool) async throws -> Edl {
        if let edl = try await Cloud.analyze(src, ops: ops, progress: progress, isCancelled: isCancelled) {
            return edl
        }
        return try await AnalyzePass.run(src, ops: ops, progress: progress, isCancelled: isCancelled).edl
    }

    // MARK: - Segmented render

    /// N standalone picture-only segments, each its own checkpoint, joined at
    /// the end. The analysis this consumes is whole-film; only the render is
    /// sliced.
    ///
    /// Cuts come straight from `Checkpoint.plan`, which shares endpoints —
    /// `[a,b] [b,c] [c,d]` — and `RenderPass`'s upper bound is exclusive, so the
    /// plan partitions the film with no frame written twice and none lost.
    /// Handing it disjoint ranges (`0...4999, 5000...9999`) would drop the frame
    /// at 4999.
    ///
    /// - Parameter audio: the one continuous track to give the joined picture:
    ///   the separated `audio.m4a` when music was removed, the source itself
    ///   when it was not, `nil` when the source is silent.
    private static func renderSegments(_ src: MediaSource, ops: FilterOps, edl: Edl,
                                       plan: [RenderSegment], dir: URL, output: URL,
                                       audio: URL?,
                                       resumed: OSAllocatedUnfairLock<Set<Job.Stage>>,
                                       post: @escaping @Sendable (Job.Stage, Double) -> Void,
                                       isCancelled: @escaping @Sendable () -> Bool) async throws {
        let fm = FileManager.default
        let joined = dir.appendingPathComponent(Checkpoint.concatName)
        let count = Double(plan.count)
        let renderContext = try RenderPass.Context(source: src, ops: ops)

        if fm.fileExists(atPath: joined.path) {
            // The concat supersedes the segments it was built from, so finding
            // it means the whole render is already done.
            resumed.withLock { _ = $0.insert(.render) }
            post(.render, 1)
        } else {
            let done = Checkpoint.completedSegments(dir: dir, of: plan)
            // Only a run that rendered nothing skipped the *stage*; a partial
            // ledger is a resumed segment list, not a resumed stage.
            if done.count == plan.count { resumed.withLock { _ = $0.insert(.render) } }
            Log.job.info("segments \(done.count)/\(plan.count) already rendered")

            for seg in plan {
                if isCancelled() { throw MediaError.cancelled }
                let url = Checkpoint.segmentURL(dir, segment: seg.index)
                if done.contains(seg.index) {
                    post(.render, Double(seg.index + 1) / count)
                    continue
                }
                // `.part` then rename, so a file under its final name *means*
                // it is complete. The suffix also keeps a half-written segment
                // out of `Checkpoint.hasRenderedSegments`, which matches on
                // `seg-*.mp4`.
                let part = url.appendingPathExtension("part")
                try? fm.removeItem(at: part)
                _ = try await RenderPass.run(
                    source: src, edl: edl, ops: ops, output: part,
                    range: seg.startMs...seg.endMs,
                    // Once, at the end of the film: the concat joins it in
                    // like any other picture, and the mux lets the audio end
                    // before it.
                    endCard: ops.endCard && seg.index == plan.last?.index,
                    context: renderContext,
                    progress: { post(.render, (Double(seg.index) + $0) / count) },
                    isCancelled: isCancelled)
                try? fm.removeItem(at: url)
                try fm.moveItem(at: part, to: url)
                post(.render, Double(seg.index + 1) / count)
            }

            if isCancelled() { throw MediaError.cancelled }
            post(.concat, 0)
            let part = dir.appendingPathComponent(Checkpoint.concatPartName)
            try? fm.removeItem(at: part)
            try await Remux.concat(plan.map { Checkpoint.segmentURL(dir, segment: $0.index) },
                                   to: part, isCancelled: isCancelled)
            try? fm.removeItem(at: joined)
            try fm.moveItem(at: part, to: joined)
            // Dead weight the moment the concat exists, and dropping them is
            // what keeps the peak at the two full-size temps `Preflight`
            // charges for instead of three.
            for seg in plan { try? fm.removeItem(at: Checkpoint.segmentURL(dir, segment: seg.index)) }
        }

        post(.concat, 0.5)
        // Keep the checkpoint boundary explicit even though Remux now observes
        // cancellation while exporting.
        if isCancelled() { throw MediaError.cancelled }
        if let audio {
            try await Remux.mux(video: joined, audio: audio, to: output,
                                isCancelled: isCancelled)
            // `joined` is deliberately left in place: it is the checkpoint a
            // failed publish resumes from, and it and `output` are the same two
            // temps the mux just had open.
        } else {
            // Nothing to add, so the join *is* the output. Moved and not copied
            // — a second full-size copy here would put the peak over budget.
            // The join therefore stops being a checkpoint on a silent source, so
            // a publish that fails after this point re-renders. That is exactly
            // what the unsegmented route does with its own temp, and the case is
            // a silent film over 30 minutes long whose publish failed.
            try? fm.removeItem(at: output)
            try fm.moveItem(at: joined, to: output)
        }
        post(.concat, 1)
    }

    // MARK: - Audio

    /// The separated track as a checkpoint: present means finished, so a resumed
    /// run skips htdemucs entirely. Posting `separate` at 1 either way is what
    /// keeps the bar identical between a fresh run and a resumed one.
    private static func separateOnce(_ src: MediaSource, ops: FilterOps, to url: URL,
                                     resumed: OSAllocatedUnfairLock<Set<Job.Stage>>,
                                     post: @escaping @Sendable (Job.Stage, Double) -> Void,
                                     isCancelled: @escaping @Sendable () -> Bool) async throws {
        if FileManager.default.fileExists(atPath: url.path) {
            resumed.withLock { _ = $0.insert(.separate) }
        } else {
            try await separate(src, ops: ops, to: url, includeVideo: false,
                               progress: { post(.separate, $0) }, isCancelled: isCancelled)
        }
        post(.separate, 1)
    }

    /// Written to `<name>.part` and renamed: a file existing under its final
    /// name *means* it is complete, which is the whole atomicity story the
    /// checkpoint layer rests on — no manifest, and no way to reference a
    /// half-written track.
    private static func separate(_ src: MediaSource, ops: FilterOps, to url: URL,
                                 includeVideo: Bool,
                                 progress: @escaping @Sendable (Double) -> Void,
                                 isCancelled: @escaping @Sendable () -> Bool) async throws {
        let part = url.appendingPathExtension("part")
        try? FileManager.default.removeItem(at: part)
        // YAMNet gate on sources of 10 min or more, where its skips save minutes;
        // shorter clips keep separating every window. Same policy as Android.
        let long = src.duration.isNumeric && src.duration.seconds >= 10 * 60
        // The server returns the audio track only; a pass that writes video too stays here.
        let onServer = includeVideo ? false
            : try await Cloud.removeMusic(src, keepStems: ops.keepStems, to: part,
                                          progress: progress, isCancelled: isCancelled)
        if !onServer {
            _ = try await AudioPipeline.removeMusic(src, to: part, keepStems: ops.keepStems,
                                                    includeVideo: includeVideo, useMusicGate: long,
                                                    progress: progress, isCancelled: isCancelled)
        }
        try? FileManager.default.removeItem(at: url)
        try FileManager.default.moveItem(at: part, to: url)
    }
}
