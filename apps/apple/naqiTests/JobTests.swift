import Testing
import AVFoundation
import Foundation
import os
@testable import naqi

/// M5 exit criteria. The three things that can silently ruin the job layer all
/// fail quietly rather than crashing: a progress bar that walks backwards, a
/// job key that moves and orphans hours of rendered work, and a cancel that
/// leaves a half-written file in the user's library. Each is pinned here
/// against the spec's own numbers rather than against the code that produced
/// them.
@Suite("Jobs", .serialized)
struct JobTests {

    // MARK: - Progress weighting

    static let shapes: [(Job.Shape, Bool)] = [
        (.censorOnly, false), (.musicOnly, true), (.combined, true),
        (.segmented, true), (.segmented, false), (.audioOnly, true),
    ]

    @Test("progress is monotonic, starts at 0 and ends at exactly 1.0",
          arguments: shapes.indices)
    func progressPerShape(i: Int) {
        let (shape, music) = Self.shapes[i]
        var p = JobProgress(shape: shape, removeMusic: music)
        #expect(p.fraction == 0, "\(shape) starts at 0")

        var last = 0.0
        for stage in Job.stages(shape, removeMusic: music) {
            for step in stride(from: 0.0, through: 1.0, by: 0.05) {
                p.post(stage, step)
                #expect(p.fraction >= last, "\(shape)/\(stage)@\(step) went backwards")
                #expect(p.fraction <= 1.0, "\(shape)/\(stage)@\(step) overshot")
                last = p.fraction
            }
        }
        // Exactly, not approximately: a bar that stops at 0.99 reads as a hang
        // on the one screen the user is watching.
        #expect(p.fraction == 1.0, "\(shape) music=\(music) ended at \(p.fraction)")
    }

    /// The arithmetic the spec calls out: the video branch tops out where the
    /// audio share ends, so the tail band starts exactly on their sum.
    @Test("the two shares meet the tail band exactly")
    func shareArithmetic() {
        var combined = JobProgress(shape: .combined, removeMusic: true)
        combined.post(.analyze, 1)
        combined.post(.separate, 1)
        combined.post(.render, 1)
        #expect(combined.pct == 93)

        var segmented = JobProgress(shape: .segmented, removeMusic: true)
        segmented.post(.analyze, 1)
        segmented.post(.separate, 1)
        segmented.post(.render, 1)
        #expect(segmented.pct == 90)

        var censorSegments = JobProgress(shape: .segmented, removeMusic: false)
        censorSegments.post(.analyze, 1)
        censorSegments.post(.render, 1)
        #expect(censorSegments.pct == 90)
    }

    /// A branch that reports out of order — the audio share arriving after the
    /// tail band has started, a resumed stage posting 1 then a live one posting
    /// 0 — must not move the bar down.
    @Test("a late or backwards post cannot lower the bar")
    func progressNeverRegresses() {
        var p = JobProgress(shape: .combined, removeMusic: true)
        p.post(.analyze, 1)
        p.post(.separate, 1)
        p.post(.render, 1)
        p.post(.mux, 1)
        let peak = p.fraction
        p.post(.analyze, 0)
        p.post(.separate, 0)
        p.post(.render, 0.2)
        #expect(p.fraction == peak)
    }

    @Test("shape dispatch order")
    func shapeDispatch() {
        var music = FilterOps(); music.removeMusic = true; music.censor = false
        var censor = FilterOps(); censor.removeMusic = false; censor.censor = true
        var both = FilterOps(); both.removeMusic = true; both.censor = true

        // Detected, not flagged: the source is the only trustworthy statement
        // about which tracks it has.
        #expect(Job.shape(ops: music, hasVideoTrack: false, segmented: false) == .audioOnly)
        #expect(Job.shape(ops: both, hasVideoTrack: false, segmented: false) == .audioOnly)
        #expect(Job.shape(ops: censor, hasVideoTrack: true, segmented: true) == .segmented)
        #expect(Job.shape(ops: both, hasVideoTrack: true, segmented: true) == .segmented)
        #expect(Job.shape(ops: both, hasVideoTrack: true, segmented: false) == .combined)
        #expect(Job.shape(ops: music, hasVideoTrack: true, segmented: false) == .musicOnly)
        #expect(Job.shape(ops: censor, hasVideoTrack: true, segmented: false) == .censorOnly)
    }

    // MARK: - Segment plan

    @Test("the 30-minute gate decides whether a source is segmented at all")
    func planThreshold() {
        #expect(Checkpoint.plan(durationMs: 29 * 60 * 1000).isEmpty)
        #expect(Checkpoint.plan(durationMs: 30 * 60 * 1000 - 1).isEmpty)

        let duration: Int64 = 31 * 60 * 1000
        let long = Checkpoint.plan(durationMs: duration)
        // 31 min / 5 min = 6.2, so 7 segments with a 60 s tail.
        #expect(long.count == 7)
        #expect(long.first?.startMs == 0)
        #expect(long.last?.endMs == duration)
        #expect(long.last?.durationMs == 60_000)

        // A forced length overrides the gate — the debug hook that makes a
        // short clip exercise the segmented route.
        #expect(!Checkpoint.plan(durationMs: 60_000, forcedSegmentMs: 10_000).isEmpty)
        #expect(Checkpoint.plan(durationMs: 0).isEmpty)
        #expect(Checkpoint.plan(durationMs: -1).isEmpty)
    }

    /// A sparse-keyframe source snaps several proposed cuts onto the same
    /// sample. Collapsing them gives fewer, longer segments; not collapsing
    /// them gives a zero-length or inverted clip, which makes the clipper throw.
    @Test("duplicate cuts collapse without producing a zero-length or inverted segment")
    func planCollapsesDuplicateCuts() {
        let duration: Int64 = 31 * 60 * 1000
        let cases: [(String, (Int64) -> Int64)] = [
            // Sync samples 15 minutes apart: three proposed cuts land on each.
            ("coarse-keyframes", { ($0 / 900_000) * 900_000 }),
            // "No sync sample at or after ms" — collapses this and every later cut.
            ("no-sync-sample", { _ in duration }),
            // A source that cannot be seeked by time at all.
            ("unseekable", { _ in 0 }),
            ("identity", { $0 }),
        ]

        for (name, cutAt) in cases {
            let plan = Checkpoint.plan(durationMs: duration, cutAt: cutAt)
            #expect(!plan.isEmpty, "\(name) produced no plan")
            #expect(plan.first?.startMs == 0, "\(name) does not start at 0")
            #expect(plan.last?.endMs == duration, "\(name) does not end at the duration")
            for (i, seg) in plan.enumerated() {
                #expect(seg.index == i, "\(name) index \(seg.index) != \(i)")
                #expect(seg.endMs > seg.startMs,
                        "\(name) segment \(i) is \(seg.startMs)…\(seg.endMs)")
                if i > 0 { #expect(seg.startMs == plan[i - 1].endMs, "\(name) has a gap at \(i)") }
            }
        }
    }

    /// The 1 ms segment, found by running the plan over durations the threshold
    /// tests never reach. 35:00.001 planned `2100000...2100001`; no cut is
    /// duplicated there so the `distinct` collapse never saw it, and
    /// `RenderPass` refuses to write a segment that decoded no frames — so every
    /// job on such a film died with "decoded no frames" and its work directory
    /// died the same way on every resume. Android carries the same gap.
    @Test("no plan emits a segment too short to hold a frame")
    func planNeverEmitsAnUnrenderableSegment() {
        // A tail under `minSegmentMs` is absorbed by the segment before it; at
        // exactly `minSegmentMs` it stands on its own.
        for (tail, expected): (Int64, Int) in [(1, 7), (33, 7), (999, 7), (1_000, 8)] {
            let duration: Int64 = 35 * 60 * 1000 + tail
            let plan = Checkpoint.plan(durationMs: duration)
            #expect(plan.count == expected, "tail \(tail) planned \(plan.count) segments")
            // The film's own end is never the cut that gets dropped.
            #expect(plan.last?.endMs == duration, "tail \(tail) ends at \(plan.last?.endMs ?? -1)")
            #expect(plan.first?.startMs == 0)
            for seg in plan {
                #expect(seg.durationMs >= Checkpoint.minSegmentMs,
                        "tail \(tail) segment \(seg.index) is \(seg.durationMs) ms")
            }
        }

        // The same collapse through the debug hook, where it degenerates all the
        // way to a single segment — which `Remux.concat` accepts, unlike an
        // empty one.
        let forced = Checkpoint.plan(durationMs: 10_001, forcedSegmentMs: 10_000)
        #expect(forced == [RenderSegment(index: 0, startMs: 0, endMs: 10_001)],
                "forced plan is \(forced)")
    }

    @Test("resume skips the segments already on disk")
    func completedSegmentsAreSkipped() throws {
        let dir = Fixtures.scratch("jobs-segments")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let plan = Checkpoint.plan(durationMs: 31 * 60 * 1000)
        #expect(Checkpoint.completedSegments(dir: dir, of: plan).isEmpty)
        #expect(!Checkpoint.hasRenderedSegments(dir: dir))

        for i in [0, 1, 4] {
            try Data("x".utf8).write(to: Checkpoint.segmentURL(dir, segment: i))
        }
        #expect(Checkpoint.completedSegments(dir: dir, of: plan) == [0, 1, 4])
        #expect(Checkpoint.hasRenderedSegments(dir: dir))
        #expect(JobRunner.hasResumableWork(dir: dir))
    }

    // MARK: - Job key

    @Test("an enabled legacy off selection shares the women's checkpoint identity")
    func legacyFaceSelectionKey() {
        let source = URL(fileURLWithPath: "/tmp/legacy-face.mp4")
        var legacy = FilterOps()
        legacy.who = .none
        #expect(legacy.resolvedWho == .women)
        #expect(Checkpoint.key(source: source, ops: legacy)
                == Checkpoint.key(source: source, ops: FilterOps()))
        legacy.censor = false
        #expect(legacy.resolvedWho == .none)
    }

    @Test("the job key is stable for identical inputs and moves when any option does")
    func jobKeyIdentity() {
        let url = URL(fileURLWithPath: "/tmp/a movie.mp4")
        let base = FilterOps()
        let key = Checkpoint.key(source: url, ops: base)
        #expect(key.count == 16)
        #expect(key == Checkpoint.key(source: url, ops: base))
        #expect(key == Checkpoint.key(source: URL(fileURLWithPath: "/tmp/a movie.mp4"), ops: base))
        #expect(key != Checkpoint.key(source: URL(fileURLWithPath: "/tmp/b movie.mp4"), ops: base))

        var variants: [FilterOps] = []
        var v = base; v.removeMusic = !base.removeMusic; variants.append(v)
        v = base; v.censor = !base.censor; variants.append(v)
        v = base; v.who = .men; variants.append(v)
        v = base; v.censorMode = .wholeFrame; variants.append(v)
        v = base; v.censorTarget = .body; variants.append(v)
        v = base; v.censorTarget = .bodyPrecise; variants.append(v)
        v = base; v.censorNsfw = false; variants.append(v)
        v = base; v.strictness += 1; variants.append(v)
        v = base; v.blurAmount += 1; variants.append(v)
        v = base; v.grayscale = !base.grayscale; variants.append(v)
        v = base; v.solidColor = .black; variants.append(v)
        v = base; v.keepStems = .vocalsAndOther; variants.append(v)
        v = base; v.endCard = false; variants.append(v)

        var seen: Set<String> = [key]
        for variant in variants {
            let k = Checkpoint.key(source: url, ops: variant)
            #expect(seen.insert(k).inserted, "\(variant) collided with an earlier key")
        }
    }

    /// Length-delimited, so ("a","bc") and ("ab","c") cannot hash alike. A
    /// collision here resumes the wrong job's segments into a user's video.
    @Test("key parts are length-delimited")
    func jobKeyDelimiting() {
        #expect(Checkpoint.key(["a", "bc"]) != Checkpoint.key(["ab", "c"]))
        #expect(Checkpoint.key(["", "abc"]) != Checkpoint.key(["abc", ""]))
    }

    /// The debug segment override changes which source window each
    /// `seg-NNN.mp4` holds, so it has to change the directory they live in —
    /// resuming a 5-second plan's segments into a 5-minute plan would splice the
    /// wrong picture together with nothing to notice it. Zero must leave the
    /// shipping key untouched, or every work directory already on disk is
    /// orphaned the day this lands.
    @Test("a forced segment length moves the job key, and zero does not")
    func forcedSegmentMovesTheKey() {
        let url = URL(fileURLWithPath: "/tmp/a movie.mp4")
        let ops = FilterOps()
        let plain = Checkpoint.key(source: url, ops: ops)
        #expect(Checkpoint.key(source: url, ops: ops, forcedSegmentMs: 0) == plain)
        #expect(Checkpoint.key(source: url, ops: ops, forcedSegmentMs: 4_000) != plain)
        #expect(Checkpoint.key(source: url, ops: ops, forcedSegmentMs: 4_000)
                != Checkpoint.key(source: url, ops: ops, forcedSegmentMs: 5_000))
    }

    @Test("quality, processing mode and downloaded bytes move checkpoint identity")
    func sourceIdentityMovesTheKey() {
        let url = URL(string: "https://example.com/watch/1")!
        let current = FilterOps()
        var fast = current
        fast.processingMode = .fast
        let base = Checkpoint.key(source: url, ops: current, quality: "BEST")
        #expect(Checkpoint.key(source: url, ops: current, quality: "P720") != base)
        #expect(Checkpoint.key(source: url, ops: fast, quality: "BEST") != base)
        #expect(Checkpoint.key(source: url, ops: current, quality: "BEST",
                               sourceIdentity: "abc") != base)
    }

    // MARK: - Preflight free space

    /// Hand-computed from spec §4.1:
    /// `(tempCopies + 1) * source + extraScratch + 2 GiB`.
    @Test("free-space maths per shape")
    func preflightBudget() {
        let gib: Int64 = 1_073_741_824
        let slack: Int64 = 2 * gib
        #expect(Preflight.slackBytes == slack)

        var censor = FilterOps(); censor.removeMusic = false; censor.censor = true
        var music = FilterOps(); music.removeMusic = true; music.censor = false
        var both = FilterOps(); both.removeMusic = true; both.censor = true

        func required(_ ops: FilterOps, seconds: Int64, segmented: Bool) -> Int64 {
            Preflight.requiredBytes(
                sourceBytes: gib,
                tempCopies: Preflight.tempCopies(for: ops, segmented: segmented),
                extraScratch: Preflight.extraScratchBytes(for: ops, durationSeconds: seconds,
                                                          segmented: segmented))
        }

        // censor-only: one temp + the published copy, no scratch.
        #expect(required(censor, seconds: 600, segmented: false) == 2 * gib + slack)
        // music-only under 30 min: the separator is not resumable, so no PCM —
        // but the separated track is now a standalone `audio.m4a` that coexists
        // with the muxed output, which is what buys music-only its resume.
        #expect(required(music, seconds: 600, segmented: false)
                == 2 * gib + 600 * 24_000 + slack)
        // Long jobs still stream directly to AAC; there is no duration-sized PCM file.
        #expect(required(music, seconds: 1800, segmented: false)
                == 2 * gib + 1800 * 24_000 + slack)
        // combined: render temp AND published output coexist; under 30 min by
        // construction, so no PCM scratch — the separated track is still a file.
        #expect(required(both, seconds: 600, segmented: false)
                == 3 * gib + 600 * 24_000 + slack)
        // segmented censor-only: the rendered segments and the concat output.
        // The source's own audio is passed through, so nothing is encoded.
        #expect(required(censor, seconds: 3600, segmented: true) == 3 * gib + slack)
        // Segmented music keeps one continuous AAC checkpoint.
        #expect(required(both, seconds: 3600, segmented: true)
                == 3 * gib + 3600 * 24_000 + slack)

        // Photos keeps a local copy for Play/Share/Save, so the budget carries
        // one more full-size file than a folder publish of the same shape.
        #expect(Preflight.requiredBytes(
            sourceBytes: gib,
            tempCopies: Preflight.tempCopies(for: censor, segmented: false) + 1,
            extraScratch: 0) == 3 * gib + slack)

        // 192 kbit/s AAC on a 155-minute film is ~223 MB.
        #expect(Preflight.extraScratchBytes(for: music, durationSeconds: 155 * 60,
                                            segmented: false) == 223_200_000)
        // A censor-only job encodes no audio at all, so nothing is charged for
        // one — the term has to be tied to the shape, not added everywhere.
        #expect(Preflight.extraScratchBytes(for: censor, durationSeconds: 155 * 60,
                                            segmented: true) == 0)
    }

    // MARK: - Failure taxonomy

    @Test("mid-pipeline failures resolve to a case, never to a message")
    func failureTaxonomy() {
        func failure(_ message: String) -> JobFailure {
            JobFailure.of(NSError(domain: "test", code: 1,
                                  userInfo: [NSLocalizedDescriptionKey: message]))
        }
        #expect(failure("write failed: ENOSPC") == .outOfSpace)
        #expect(failure("No space left on device") == .outOfSpace)
        #expect(failure("crypto error 0x1") == .drmProtected)
        // The decoder's own wording when the device has no codec for a mime.
        // It names neither "codec" nor "decoder".
        #expect(failure("Failed to initialize audio/ac3, error 0x80001001") == .unsupportedCodec)
        #expect(failure("no decoder for hvc1") == .unsupportedCodec)
        // Specific cases shadow one another in order: space beats codec.
        #expect(failure("encoder failed: no space left on device") == .outOfSpace)
        // An unrecognised cause is never the throwable's own message.
        #expect(failure("separator emitted 3 of 4 frames") == .generic)

        #expect(JobFailure.of(CocoaError(.fileNoSuchFile)) == .sourceUnreadable)
        #expect(JobFailure.of(PreflightFailure.lowSpace(requiredBytes: 1, availableBytes: 0)) == .lowSpace)
        #expect(JobFailure.of(PreflightFailure.drmProtected) == .drmProtected)
        #expect(JobFailure.of(PublishError.photosDenied) == .photosDenied)
    }

    @Test("the up-front ETA is a floor, and a no-op job estimates nothing")
    func etaFloor() {
        var censor = FilterOps(); censor.removeMusic = false; censor.censor = true
        var music = FilterOps(); music.removeMusic = true; music.censor = false
        var both = FilterOps(); both.removeMusic = true; both.censor = true
        var none = FilterOps(); none.removeMusic = false; none.censor = false

        #expect(Eta.estimateMs(durationMs: 100_000, ops: censor) == 28_000)
        #expect(Eta.estimateMs(durationMs: 100_000, ops: music) == 68_000)
        #expect(Eta.estimateMs(durationMs: 100_000, ops: both) == 100_000)
        var body = censor
        for target in [FilterOps.CensorTarget.body, .bodyPrecise] {
            body.censorTarget = target
            #expect(Eta.estimateMs(durationMs: 100_000, ops: body) == 40_000)
        }
        #expect(Eta.estimateMs(durationMs: 100_000, ops: none) == 0)
        #expect(Eta.estimateMs(durationMs: 0, ops: both) == 0)

        // 0 means "too early to say"; the surface hides the line entirely.
        #expect(Eta.liveMs(elapsedMs: 10_000, pct: 2) == 0)
        #expect(Eta.liveMs(elapsedMs: 10_000, pct: 25) == 30_000)
        #expect(Eta.confirmThresholdMs == 1_800_000)
    }

    // MARK: - End to end

    /// Kill a job mid-way, re-run it, and prove the expensive pass did not run
    /// twice. Simulated as a system stop rather than a user cancel: those are
    /// the cases resume exists to survive, and only a user cancel destroys the
    /// work directory.
    @Test("an interrupted run keeps its analysis and the next run skips it")
    func resumeSkipsFinishedWork() async throws {
        let url = try requireQAVideo()
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        // `everyone` skips the gender vote outright, which keeps the test to
        // the resume behaviour rather than to genderage's runtime.
        ops.who = .everyone

        let folder = Fixtures.scratch("jobs-resume-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: url, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: url, ops: ops)
        WorkDir.clear(key)
        let dir = WorkDir.root.appendingPathComponent(key, isDirectory: true)

        // First attempt: stop the moment the analysis checkpoint lands.
        let interrupt = OSAllocatedUnfairLock(initialState: false)
        let analyzeBar = OSAllocatedUnfairLock<[Double]>(initialState: [])
        var thrown: (any Error)?
        do {
            _ = try await JobRunner.run(
                job,
                progress: { p in
                    if p.stage == .analyze { analyzeBar.withLock { $0.append(p.fraction) } }
                    if p.stage == .analyze, p.fraction >= 0.5 { interrupt.withLock { $0 = true } }
                },
                stop: { interrupt.withLock { $0 } ? .interrupted : nil })
        } catch { thrown = error }

        let stopped = try #require(thrown as? JobStopped, "the run should have been interrupted")
        #expect(stopped.reason == .interrupted)
        #expect(stopped.resumable)

        // Analyze is the longest stage of a censor-only job, so a bar that only
        // knows 0 and 1 sits frozen through most of it. `AnalyzePass` reports
        // one value per second of source (every `sampleFPS`-th sampled frame),
        // and 0…50 is the censor-only analyze band — the pass reports 0…1 of
        // itself and `JobProgress` does the mapping, so a stage that leaked an
        // absolute percent would land outside that range.
        let bar = analyzeBar.withLock { $0 }
        let interior = Set(bar.filter { $0 > 0 && $0 < 0.5 })
        #expect(interior.count >= 3, "analyze posted \(bar.count) values: \(bar)")
        #expect(bar.allSatisfy { $0 >= 0 && $0 <= 0.5 }, "outside the 0…50 band: \(bar)")
        #expect(zip(bar, bar.dropFirst()).allSatisfy { $0 <= $1 }, "analyze went backwards: \(bar)")
        #expect(bar.last == 0.5, "analyze ended at \(String(describing: bar.last)), not the band top")
        #expect(FileManager.default.fileExists(
            atPath: dir.appendingPathComponent(Checkpoint.analysisName).path),
            "the analysis checkpoint should have survived the stop")
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty,
                "an interrupted run must not publish anything")

        // Second attempt: same (source, options) ⇒ same key ⇒ finds the checkpoint.
        let done = try await JobRunner.run(job)
        #expect(done.resumed.contains(.analyze), "analyze ran a second time")
        #expect(done.shape == .censorOnly)

        // A `.userFolder` publish is the one that leaves a file behind, so the
        // URL is non-nil here; a Photos publish would legitimately have none.
        let out = try await MediaSource.probe(try #require(done.output.url))
        #expect(out.video != nil, "the output has no video track")
        #expect(done.output.name == out.url.lastPathComponent)
        let source = try await MediaSource.probe(url)
        // Re-encoded, so the end card rides on the end.
        #expect(abs(out.duration.seconds - source.duration.seconds - EndCard.duration) < 0.5)
        #expect(!FileManager.default.fileExists(atPath: dir.path),
                "a completed job leaves no work directory")
        try? FileManager.default.removeItem(at: folder)
    }

    /// The flagship shape, and the only one with two concurrent branches: the
    /// audio wall at `.userInitiated` and the analyze branch demoted to
    /// `.utility` so it cannot take a P-core off ORT. Interrupting once both
    /// branches have landed proves the expensive half survives — a resumed
    /// both-ops job must not separate the audio a second time.
    @Test("both-ops resumes without re-separating the audio")
    func combinedResumesTheAudioBranch() async throws {
        let url = try requireQAVideo()
        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = true
        ops.who = .everyone

        let folder = Fixtures.scratch("jobs-combined-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: url, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: url, ops: ops)
        WorkDir.clear(key)
        let dir = WorkDir.root.appendingPathComponent(key, isDirectory: true)

        // Render only starts once analyze and separate have both finished, so
        // its first progress post is the signal that both checkpoints exist.
        let interrupt = OSAllocatedUnfairLock(initialState: false)
        var thrown: (any Error)?
        do {
            _ = try await JobRunner.run(
                job,
                progress: { p in if p.stage == .render { interrupt.withLock { $0 = true } } },
                stop: { interrupt.withLock { $0 } ? .interrupted : nil })
        } catch { thrown = error }

        #expect(thrown is JobStopped)
        #expect(FileManager.default.fileExists(
            atPath: dir.appendingPathComponent(Checkpoint.audioTrackName).path),
            "the separated track should have survived the stop")

        let done = try await JobRunner.run(job)
        #expect(done.shape == .combined)
        #expect(done.resumed == [.analyze, .separate])

        let out = try await MediaSource.probe(try #require(done.output.url))
        #expect(out.video != nil)
        #expect(out.audio != nil, "the separated track was not muxed in")
        let source = try await MediaSource.probe(url)
        #expect(abs(out.duration.seconds - source.duration.seconds - EndCard.duration) < 0.5)
        try? FileManager.default.removeItem(at: folder)
    }

    /// **The segmented resume.** Render a clip as four segments, kill the run
    /// once some of them have landed, build a fresh runner from what survived on
    /// disk, and prove two things: the finished segments are not rendered again,
    /// and the joined output is the same film an unsegmented render produces.
    ///
    /// "Not rendered again" is read off the file mtimes and not off
    /// `Completion.resumed`, which only reports whole stages — a run that
    /// quietly re-rendered every segment would leave `resumed` exactly as empty
    /// as a correct one and look right from the outside. The two runs are
    /// separated by a second so a re-render cannot land on the same timestamp.
    @Test("a killed segmented job resumes without re-rendering finished segments")
    func segmentedResumeSkipsRenderedSegments() async throws {
        // 13 s of 320x240 picture with the QA clip's real AAC track bolted on.
        // Synthetic because frame arithmetic at a seam does not care about
        // resolution and five 1080p transcodes in one process is what got
        // `RenderTests` jetsammed; with audio because the segmented route has to
        // produce a track its picture-only segments never carried.
        let picture = Fixtures.scratch("seg-job-picture.mp4")
        try await RenderTests.syntheticClip(picture, size: CGSize(width: 320, height: 240),
                                            frames: 390)
        let sourceURL = Fixtures.scratch("seg-job-source.mp4")
        try await Remux.mux(video: picture, audio: try requireQAVideo(), to: sourceURL)

        let src = try await MediaSource.probe(sourceURL)
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        ops.blurAmount = 0
        ops.grayscale = true
        // Both spans straddle a cut, so a seam that dropped or doubled a frame
        // moves the picture as well as the count.
        let edl = Edl(censorIntervalsMs: [3_900...4_100, 7_900...8_100])

        let segmentMs: Int64 = 4_000
        let durationMs = Int64(src.duration.seconds * 1000)
        let plan = Checkpoint.plan(durationMs: durationMs, forcedSegmentMs: segmentMs)
        #expect(plan.count == 4, "plan is \(plan.count) segments over \(durationMs) ms")

        // The reference: the same EDL, one unsegmented pass, no ledger — and
        // the one end card the join must carry on its last segment only.
        let refURL = Fixtures.scratch("seg-job-reference.mp4")
        _ = try await RenderPass.run(source: src, edl: edl, ops: ops, output: refURL, endCard: true)
        let referenceFrames = try await RenderTests.sampleCount(refURL, .video)

        let folder = Fixtures.scratch("seg-job-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: sourceURL, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: sourceURL, ops: ops, forcedSegmentMs: segmentMs)
        WorkDir.clear(key)
        let dir = WorkDir.job(key)
        // Seeding the analysis keeps this test on the ledger rather than on
        // ORT's runtime. Analyze is whole-film on this shape either way, so the
        // seeded EDL is exactly what a real first pass would have written.
        try Checkpoint.writeEdl(edl, dir: dir)

        // Stop once two of the four segments have landed: the render band is
        // 40…90 on segmented-without-music, so 2/4 of it is exactly 65.
        let interrupt = OSAllocatedUnfairLock(initialState: false)
        var thrown: (any Error)?
        do {
            _ = try await JobRunner.run(
                job,
                progress: { p in
                    if p.stage == .render, p.pct >= 65 { interrupt.withLock { $0 = true } }
                },
                stop: { interrupt.withLock { $0 } ? .interrupted : nil },
                forcedSegmentMs: segmentMs)
        } catch { thrown = error }

        let stopped = try #require(thrown as? JobStopped, "the run should have been interrupted")
        #expect(stopped.reason == .interrupted)
        #expect(stopped.resumable)

        let survivors = Checkpoint.completedSegments(dir: dir, of: plan)
        #expect(survivors.count >= 2 && survivors.count < plan.count,
                "stopped holding \(survivors.count) of \(plan.count) segments")
        #expect(!FileManager.default.fileExists(
            atPath: dir.appendingPathComponent(Checkpoint.concatName).path),
            "the concat cannot exist before every segment does")
        // The in-flight segment's `.part` is the one temp a stop can strand.
        #expect(try FileManager.default.contentsOfDirectory(atPath: dir.path)
            .allSatisfy { !$0.hasSuffix(".part") }, "a partial segment survived the stop")
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty,
                "an interrupted run must not publish anything")

        let before = try Self.segmentMtimes(Set(plan.map(\.index)), in: dir)
        // Coarse enough that a re-render cannot reuse a timestamp.
        try await Task.sleep(for: .milliseconds(1_100))
        let boundary = Date()

        // The segments are deleted the moment the concat lands, so the ledger
        // has to be read while the second run is still holding it — the first
        // `concat` post is the last instant all four exist.
        let atConcat = OSAllocatedUnfairLock<[Int: Date]>(initialState: [:])
        let indices = Set(plan.map(\.index))
        let done = try await JobRunner.run(
            job,
            progress: { p in
                guard p.stage == .concat, atConcat.withLock({ $0.isEmpty }) else { return }
                let now = (try? Self.segmentMtimes(indices, in: dir)) ?? [:]
                atConcat.withLock { $0 = now }
            },
            forcedSegmentMs: segmentMs)

        #expect(done.shape == .segmented)
        #expect(done.resumed.contains(.analyze), "the seeded analysis was thrown away")

        let after = atConcat.withLock { $0 }
        #expect(after.count == plan.count,
                "the concat ran with \(after.count) of \(plan.count) segments present")
        for i in survivors.sorted() {
            #expect(after[i] == before[i], "segment \(i) was rendered a second time")
        }
        // The other half of the same signal: the missing segments DID get
        // written in the second run, so "the mtime never moves" is not what is
        // being measured.
        for seg in plan where !survivors.contains(seg.index) {
            let m = try #require(after[seg.index], "segment \(seg.index) never appeared")
            #expect(m > boundary, "segment \(seg.index) predates the second run")
        }

        let out = try await MediaSource.probe(try #require(done.output.url))
        #expect(out.video != nil)
        #expect(out.audio != nil, "the joined picture never got its audio track")
        let frames = try await RenderTests.sampleCount(out.url, .video)
        #expect(frames == referenceFrames,
                "the join holds \(frames) frames, the unsegmented render \(referenceFrames)")
        let ref = try await MediaSource.probe(refURL)
        #expect(abs(out.duration.seconds - ref.duration.seconds) < 0.2,
                "the join is \(out.duration.seconds)s, unsegmented \(ref.duration.seconds)s")
        #expect(!FileManager.default.fileExists(atPath: dir.path),
                "a completed job leaves no work directory")
        try? FileManager.default.removeItem(at: folder)
    }

    /// The other segmented arm: with music removed the joined picture gets the
    /// *separated* track, not the source's. The two are told apart by sample
    /// rate — htdemucs works at 44.1 kHz end to end and the fixture's own audio
    /// is 48 kHz — so muxing the wrong URL fails here instead of shipping a film
    /// with its music still in it.
    @Test("a segmented job with music on joins the separated track, not the source's")
    func segmentedWithMusicMuxesTheSeparatedTrack() async throws {
        let picture = Fixtures.scratch("seg-music-picture.mp4")
        try await RenderTests.syntheticClip(picture, size: CGSize(width: 320, height: 240),
                                            frames: 390)
        let sourceURL = Fixtures.scratch("seg-music-source.mp4")
        try await Remux.mux(video: picture, audio: try requireQAVideo(), to: sourceURL)
        let src = try await MediaSource.probe(sourceURL)
        #expect(src.audio?.sampleRate == 48_000, "fixture audio is \(src.audio?.sampleRate ?? 0) Hz")

        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = true
        ops.blurAmount = 0
        ops.grayscale = true

        let segmentMs: Int64 = 4_000
        let folder = Fixtures.scratch("seg-music-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: sourceURL, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: sourceURL, ops: ops, forcedSegmentMs: segmentMs)
        WorkDir.clear(key)
        // Seeded for the same reason as the resume test: this one is about which
        // track reaches the output, not about ORT.
        try Checkpoint.writeEdl(Edl(censorIntervalsMs: [3_900...4_100]), dir: WorkDir.job(key))

        let done = try await JobRunner.run(job, forcedSegmentMs: segmentMs)
        #expect(done.shape == .segmented)
        #expect(done.resumed == [.analyze])

        let out = try await MediaSource.probe(try #require(done.output.url))
        #expect(out.video != nil)
        #expect(out.audio?.sampleRate == 44_100,
                "output audio is \(out.audio?.sampleRate ?? 0) Hz — the source's track was muxed in")
        #expect(abs(out.duration.seconds - src.duration.seconds - EndCard.duration) < 0.2)
        #expect(!FileManager.default.fileExists(
            atPath: WorkDir.root.appendingPathComponent(key, isDirectory: true).path))
        try? FileManager.default.removeItem(at: folder)
    }

    /// Music-only used to write its finished file in one pass, so an
    /// interruption at 95 % threw away every second of htdemucs — the opposite
    /// of the guarantee every other shape gives. It now separates into the
    /// `audio.m4a` checkpoint and muxes that against the untouched source.
    ///
    /// The resume is proved by counting `separate` posts, not by timing: a
    /// resumed run posts the band's top exactly once, and the only way to
    /// produce an intermediate value is to run the separator again. htdemucs is
    /// ~1.3 GB resident and ~700 ms per 2.6 s of audio, so a regression here is
    /// slow as well as wrong.
    @Test("music-only interrupted after separation resumes without re-running htdemucs")
    func musicOnlyResumesAfterSeparation() async throws {
        let url = try requireQAVideo()
        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = false

        let folder = Fixtures.scratch("jobs-music-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: url, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: url, ops: ops)
        WorkDir.clear(key)
        let dir = WorkDir.job(key)
        let audio = dir.appendingPathComponent(Checkpoint.audioTrackName)

        // Stopping on the checkpoint's own existence rather than on a progress
        // number: the chunk loop posts its last percentage *before* the AAC
        // encode and the rename, so a stop keyed to that would abort the very
        // thing this test needs to survive.
        var thrown: (any Error)?
        do {
            _ = try await JobRunner.run(job, stop: {
                FileManager.default.fileExists(atPath: audio.path) ? .interrupted : nil
            })
        } catch { thrown = error }

        let stopped = try #require(thrown as? JobStopped, "the run should have been interrupted")
        #expect(stopped.reason == .interrupted)
        #expect(stopped.resumable)
        #expect(FileManager.default.fileExists(atPath: audio.path),
                "the separated track should have survived the stop")
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty,
                "an interrupted run must not publish anything")

        let posts = OSAllocatedUnfairLock<[Double]>(initialState: [])
        let done = try await JobRunner.run(job, progress: { p in
            if p.stage == .separate { posts.withLock { $0.append(p.fraction) } }
        })
        #expect(done.shape == .musicOnly)
        #expect(done.resumed.contains(.separate))
        // 1…93 is the music-only separate band, so one post at its top.
        #expect(posts.withLock { $0 } == [0.93],
                "htdemucs ran again: \(posts.withLock { $0.count }) separate posts")

        let out = try await MediaSource.probe(try #require(done.output.url))
        let source = try await MediaSource.probe(url)
        #expect(out.video != nil, "the mux lost the picture")
        #expect(out.audio != nil, "the mux lost the separated track")
        // Compressed passthrough, not a re-encode: the picture comes out the
        // size it went in.
        #expect(out.video?.naturalSize == source.video?.naturalSize)
        #expect(abs(out.duration.seconds - source.duration.seconds) < 0.5)
        #expect(!FileManager.default.fileExists(atPath: dir.path),
                "a completed job leaves no work directory")
        try? FileManager.default.removeItem(at: folder)
    }

    /// The shape with no picture anywhere in it: no analyze, no render, no mux —
    /// the separated track *is* the output, and `publish` closes it.
    ///
    /// Nothing ran it end to end before. `shapeDispatch` proves `Job.shape`
    /// *returns* `.audioOnly`, which is not the same claim: the `.m4a` extension
    /// swap, the `separate → publish` two-stage band and the Photos refusal all
    /// live past that call, and until the picker took audio the only source that
    /// could reach them was an audio-only `.mp4` nobody had.
    @Test("an audio-only source runs the audioOnly shape end to end")
    func audioOnlySourceRuns() async throws {
        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = false

        let song = try Fixtures.audioClip("jobs-audio-only.m4a")
        let folder = Fixtures.scratch("jobs-audio-only-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let job = Job.capture(source: song, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: song, ops: ops)
        WorkDir.clear(key)

        let posts = OSAllocatedUnfairLock<[Job.Stage]>(initialState: [])
        let done = try await JobRunner.run(job, progress: { p in
            if let s = p.stage { posts.withLock { if $0.last != s { $0.append(s) } } }
        })

        #expect(done.shape == .audioOnly)
        // The whole strip: no analyze, no render, no mux, no concat.
        #expect(Set(posts.withLock { $0 }) == [.separate, .publish])

        let out = try #require(done.output.url)
        #expect(out.pathExtension == "m4a", "an audio-only job published .\(out.pathExtension)")
        let probed = try await MediaSource.probe(out)
        #expect(probed.video == nil, "a source with no picture produced one")
        #expect(probed.audio != nil, "the published file has no audio track")
        let source = try await MediaSource.probe(song)
        #expect(abs(probed.duration.seconds - source.duration.seconds) < 0.5,
                "published \(probed.duration.seconds)s from a \(source.duration.seconds)s source")
        #expect(!FileManager.default.fileExists(atPath: WorkDir.root.appendingPathComponent(key).path),
                "a completed job leaves no work directory")
    }

    /// Photos cannot take a bare audio resource — `PHAssetCreationRequest`
    /// refuses it — so an audio-only job bound for Photos keeps its `.m4a` in
    /// the app's Documents instead of failing after the separation. `Flow`
    /// already forces the folder in the UI; this is the route for the queued
    /// and shared-in jobs that never touch `Flow`.
    @Test("an audio-only job bound for Photos keeps its file in the app")
    func audioOnlyBoundForPhotosStaysInApp() async throws {
        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = false

        let song = try Fixtures.audioClip("jobs-audio-photos.m4a", seconds: 1)
        let job = Job.capture(source: song, ops: ops, destination: .photos)

        let done = try await JobRunner.run(job)
        let url = try #require(done.output.url)
        defer { try? FileManager.default.removeItem(at: url) }
        #expect(done.shape == .audioOnly)
        #expect(url.pathExtension == "m4a")
        #expect(OutputLibrary.owns(url))
        #expect(done.output.assetID == nil)
        #expect(FileManager.default.fileExists(atPath: url.path))
    }

    /// The route question the debug hook cannot answer: `forcedSegmentMs` drives
    /// every other segmented test, so all of them would still pass if the
    /// production gate were wired to `segmented: false`. This one runs a source
    /// over the real 30-minute threshold with no override at all, through
    /// `JobRunner` exactly as the UI runs one, and reads the shape off the
    /// completion.
    ///
    /// 1 fps, so 31 minutes is 1860 frames rather than 55 800 — the plan only
    /// reads the duration, and the seam arithmetic is `segmentedConcatMatches‑
    /// Monolithic`'s job at 30 fps.
    @Test("a source past the real 30-minute gate is segmented with no debug hook")
    func longSourceTakesTheSegmentedRoute() async throws {
        let sourceURL = Fixtures.scratch("jobs-long-source.mp4")
        try await RenderTests.syntheticClip(sourceURL, size: CGSize(width: 128, height: 96),
                                            frames: 1_860, tickStride: 600)
        let src = try await MediaSource.probe(sourceURL)
        let durationMs = Int64(src.duration.seconds * 1000)
        #expect(durationMs >= Checkpoint.longSourceThresholdMs,
                "fixture is \(durationMs) ms, under the \(Checkpoint.longSourceThresholdMs) ms gate")

        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        ops.blurAmount = 0
        ops.grayscale = true

        let folder = Fixtures.scratch("jobs-long-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: sourceURL, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: sourceURL, ops: ops)
        WorkDir.clear(key)
        // Seeded for the same reason the other segmented tests seed it: this is
        // about the route, not about ORT over 18 600 sampled frames. The span
        // straddles the first cut, so a seam that lost the frames at 300 000 ms
        // shows up in the count below.
        try Checkpoint.writeEdl(Edl(censorIntervalsMs: [299_000...301_000]), dir: WorkDir.job(key))

        let stages = OSAllocatedUnfairLock<Set<Job.Stage>>(initialState: [])
        let done = try await JobRunner.run(job, progress: { p in
            if let s = p.stage { stages.withLock { _ = $0.insert(s) } }
        })

        #expect(done.shape == .segmented, "a \(durationMs) ms source ran as \(done.shape)")
        #expect(stages.withLock { $0.contains(.concat) },
                "the concat stage never posted: \(stages.withLock { $0 })")
        let out = try await MediaSource.probe(try #require(done.output.url))
        let frames = try await RenderTests.sampleCount(out.url, .video)
        // 1 fps is no cadence for an animation, so the card runs at 30: 60 frames.
        #expect(frames == 1_860 + 60, "the join holds \(frames) of 1860 + 60 frames")
        #expect(abs(out.duration.seconds - src.duration.seconds - EndCard.duration) < 1.0,
                "the join is \(out.duration.seconds)s, the source \(src.duration.seconds)s")
        try? FileManager.default.removeItem(at: folder)
    }

    /// Stop after the concat has become an atomic checkpoint but before it is
    /// moved or muxed into the output. On a silent source the join is **moved** into
    /// the output rather than copied, so a mux that ran anyway after a cancel
    /// consumed the one checkpoint the resume had — the failure path then
    /// deletes the output, `hasResumableWork` finds nothing (the segments went
    /// the moment the concat landed) and the whole render is thrown away.
    @Test("a cancel between the concat and the mux keeps the join")
    func cancelAfterConcatKeepsTheCheckpoint() async throws {
        // No audio track at all: that is what selects the move-not-copy branch.
        let sourceURL = Fixtures.scratch("jobs-silent-source.mp4")
        try await RenderTests.syntheticClip(sourceURL, size: CGSize(width: 320, height: 240),
                                            frames: 390)
        let src = try await MediaSource.probe(sourceURL)
        #expect(src.audio == nil, "fixture grew an audio track")

        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        ops.blurAmount = 0
        ops.grayscale = true

        let segmentMs: Int64 = 4_000
        let folder = Fixtures.scratch("jobs-silent-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: sourceURL, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: sourceURL, ops: ops, forcedSegmentMs: segmentMs)
        WorkDir.clear(key)
        let dir = WorkDir.job(key)
        try Checkpoint.writeEdl(Edl(censorIntervalsMs: [0...100]), dir: dir)

        let joined = dir.appendingPathComponent(Checkpoint.concatName)
        var thrown: (any Error)?
        do {
            _ = try await JobRunner.run(
                job,
                stop: { FileManager.default.fileExists(atPath: joined.path) ? .interrupted : nil },
                forcedSegmentMs: segmentMs)
        } catch { thrown = error }

        let stopped = try #require(thrown as? JobStopped, "the run should have been interrupted")
        #expect(stopped.reason == .interrupted)
        #expect(FileManager.default.fileExists(
            atPath: joined.path),
            "the join was consumed by a mux that ran after the cancel")
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty,
                "a cancelled run must not publish anything")

        // And the resume it protects: the join is picked up whole, no segment
        // is rendered again, and the film comes out the length it went in.
        let done = try await JobRunner.run(job, forcedSegmentMs: segmentMs)
        #expect(done.resumed.contains(.render), "the join was not treated as a finished render")
        let out = try await MediaSource.probe(try #require(done.output.url))
        #expect(abs(out.duration.seconds - src.duration.seconds - EndCard.duration) < 0.2,
                "the resumed join is \(out.duration.seconds)s, the source \(src.duration.seconds)s")
        try? FileManager.default.removeItem(at: folder)
    }

    /// Segment mtimes, which is how "was this rendered again" is read.
    /// `Completion.resumed` cannot answer it: it reports whole stages, and a
    /// half-resumed render is not a skipped stage.
    private static func segmentMtimes(_ indices: Set<Int>, in dir: URL) throws -> [Int: Date] {
        var out: [Int: Date] = [:]
        for i in indices.sorted() {
            let u = Checkpoint.segmentURL(dir, segment: i)
            guard FileManager.default.fileExists(atPath: u.path) else { continue }
            out[i] = try u.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate
        }
        return out
    }

    @Test("a user cancel leaves no output file and no temp directory")
    func cancelLeavesNothing() async throws {
        let url = try requireQAVideo()
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true
        ops.who = .everyone

        let folder = Fixtures.scratch("jobs-cancel-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: url, ops: ops, destination: .userFolder, folder: folder)
        let key = Checkpoint.key(source: url, ops: ops)
        WorkDir.clear(key)

        var thrown: (any Error)?
        do { _ = try await JobRunner.run(job, stop: { .userCancelled }) } catch { thrown = error }

        let stopped = try #require(thrown as? JobStopped)
        #expect(stopped.reason == .userCancelled)
        #expect(!stopped.resumable)
        #expect(try FileManager.default.contentsOfDirectory(atPath: folder.path).isEmpty)
        #expect(!FileManager.default.fileExists(
            atPath: WorkDir.root.appendingPathComponent(key, isDirectory: true).path),
            "a user cancel takes the whole work directory")
        try? FileManager.default.removeItem(at: folder)
    }

    /// `sweepStale` is the only code in the app that deletes work the user
    /// cannot get back, and it had no test. It seeded `newest` from
    /// `.distantPast`, so a work directory with no files in it yet — the state
    /// every job passes through between `WorkDir.make` and its first
    /// checkpoint — measured as infinitely stale and was deleted on sight.
    /// Harmless only because `JobQueue` is strictly serial, which is not a
    /// property this function should have to depend on.
    @Test("the 7-day sweep spares an empty directory and a fresh one")
    func sweepSparesWhatIsNotStale() throws {
        let fm = FileManager.default
        let tag = UUID().uuidString.prefix(8)
        let empty = WorkDir.root.appendingPathComponent("sweep-empty-\(tag)")
        let fresh = WorkDir.root.appendingPathComponent("sweep-fresh-\(tag)")
        let old = WorkDir.root.appendingPathComponent("sweep-old-\(tag)")
        for d in [empty, fresh, old] {
            try fm.createDirectory(at: d, withIntermediateDirectories: true)
        }
        try Data("x".utf8).write(to: fresh.appendingPathComponent("analysis.json"))
        try Data("x".utf8).write(to: old.appendingPathComponent("seg-000.mp4"))

        // Backdate the stale one past the interval — both the file and the
        // directory, since the directory's own mtime is now the floor.
        let past = Date().addingTimeInterval(-Checkpoint.staleInterval - 3600)
        try fm.setAttributes([.modificationDate: past],
                             ofItemAtPath: old.appendingPathComponent("seg-000.mp4").path)
        try fm.setAttributes([.modificationDate: past], ofItemAtPath: old.path)

        Checkpoint.sweepStale()

        #expect(fm.fileExists(atPath: empty.path),
                "an empty work dir was swept — a job that had not checkpointed yet just lost its scratch")
        #expect(fm.fileExists(atPath: fresh.path), "a fresh work dir was swept")
        #expect(!fm.fileExists(atPath: old.path), "a 7-day-old work dir was not swept")

        for d in [empty, fresh, old] { try? fm.removeItem(at: d) }
    }

    /// The destination folder has to survive the same relaunch the source does.
    /// It did not: `capture` bookmarked `source` and stored `folder` as a bare
    /// URL, so a job queued for a folder and resumed after a cold start — or
    /// after the user picked a different folder, which closes the old scope —
    /// reached `Publish` with a URL it could no longer write to. At the end of
    /// an hour-long render, with no way back.
    @Test("a queued job's destination folder survives a relaunch")
    func folderSurvivesRelaunch() throws {
        let folder = Fixtures.scratch("job-folder")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var ops = FilterOps()
        ops.censor = true

        let job = Job.capture(source: URL(fileURLWithPath: "/tmp/naqi-x.mp4"), ops: ops,
                              destination: .userFolder, folder: folder)
        #expect(job.folderBookmark != nil, "no bookmark taken for the destination folder")

        // Through the queue file, which is the only path that matters: an
        // in-memory Job still holds a live URL and would pass regardless.
        let round = try JSONDecoder().decode(Job.self, from: try JSONEncoder().encode(job))
        // `.path`, not the URL: URL's Codable round-trip drops the directory
        // flag, so an otherwise identical decoded URL loses its trailing
        // slash and compares unequal. The path is what `Publish` appends to.
        #expect(round.resolvedFolder?.path == folder.path)

        // A row written before this field existed must still decode — the queue
        // file on a user's device predates it.
        var legacy = try #require(try JSONSerialization.jsonObject(
            with: try JSONEncoder().encode(job)) as? [String: Any])
        legacy["folderBookmark"] = nil
        let old = try JSONDecoder().decode(
            Job.self, from: try JSONSerialization.data(withJSONObject: legacy))
        #expect(old.folderBookmark == nil)
        #expect(old.resolvedFolder?.path == folder.path,
                "a bookmarkless row must fall back to the stored URL, not to nil")

        try? FileManager.default.removeItem(at: folder)
    }

    // MARK: - Queue

    @Test("the queue runs in order, records each outcome, and survives a relaunch")
    func queueOrderAndPersistence() async throws {
        let store = Fixtures.scratch("jobs-queue.json")
        let queue = JobQueue(storeURL: store)
        var ops = FilterOps()
        ops.censor = true

        // Sources that cannot be opened: the point here is the queue's
        // bookkeeping, and a queued run must never take the rest of the queue
        // down with it.
        var enqueued: [Job.ID] = []
        for i in 0..<3 {
            let missing = URL(fileURLWithPath: "/tmp/naqi-missing-\(i)-\(UUID().uuidString).mp4")
            enqueued.append(await queue.enqueue(
                Job.capture(source: missing, ops: ops, destination: .userFolder)))
        }

        try await settle { await queue.jobs.allSatisfy(\.state.isTerminal) }
        var jobs = await queue.jobs
        #expect(jobs.map(\.id) == enqueued, "the queue reordered itself")
        for job in jobs {
            #expect(job.state == .failed(.sourceUnreadable, resumable: false))
        }

        // Retry is not a special code path: the same (source, options) is
        // re-run, and it lands on the same job key.
        await queue.retry(enqueued[0])
        try await settle { await queue.jobs.allSatisfy(\.state.isTerminal) }
        jobs = await queue.jobs
        #expect(jobs.count == 3, "retry duplicated a row")

        // Relaunch.
        let reloaded = JobQueue(storeURL: store)
        #expect(await reloaded.jobs.map(\.id) == enqueued)

        await queue.clearFinished()
        #expect(await queue.jobs.isEmpty)
    }

    /// Drives the share-in seam from the *extension's* side: write exactly what
    /// `ShareViewController.copy` writes — media first, manifest second — then
    /// drain. This is the only test that can fail if the App Group entitlement
    /// regresses, because everything else on this path degrades to a silent
    /// no-op rather than an error.
    /// Share-in is iOS-only, and "is it available here" is a **write probe**,
    /// not a nil check.
    ///
    /// On macOS `ShareInbox.container` returns a perfectly good-looking URL
    /// under `~/Library/Group Containers/` even though the build carries no App
    /// Group entitlement — `CODE_SIGN_ENTITLEMENTS` is scoped to
    /// `[sdk=iphone*]`, because requiring it on macOS would force a
    /// provisioning profile for a container nothing on that platform reads, and
    /// the extension itself embeds `platformFilters = (ios, )`. Only the
    /// *write* fails, with EPERM. A nil check therefore passes straight through
    /// and the test dies on `createDirectory`.
    static func usableInbox() -> URL? {
        guard let dir = ShareInbox.container else { return nil }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            return dir
        } catch {
            return nil
        }
    }

    @Test("share inbox drains what the extension writes")
    func shareInboxDrains() async throws {
        guard let dir = Self.usableInbox() else {
            #if os(iOS)
            Issue.record("App Group container unusable — the entitlement or the group id regressed")
            #endif
            return
        }

        let id = UUID()
        let media = ShareManifest.mediaURL(dir, id: id, ext: "mp4")
        try Data("not really a movie".utf8).write(to: media)
        let shared = ShareOptions(removeMusic: true, censor: false, who: "everyone")
        let manifest = ShareManifest(id: id, fileName: "clip.mp4", receivedAt: Date(), options: shared)
        try JSONEncoder().encode(manifest)
            .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)

        let queue = JobQueue(storeURL: Fixtures.scratch("jobs-inbox.json"))
        #expect(await ShareInbox.drain(into: queue) == 1)
        let jobs = await queue.jobs
        #expect(jobs.count == 1)
        #expect(jobs.first?.title == "clip")
        #expect(jobs.first?.ops.removeMusic == true)
        #expect(jobs.first?.ops.censor == false)
        #expect(jobs.first?.ops.who == .everyone)
        // Both container entries are consumed: a manifest left behind would be
        // re-enqueued on the next foreground, and the app foregrounds a lot.
        #expect(!FileManager.default.fileExists(
            atPath: ShareManifest.manifestURL(dir, id: id).path))
        #expect(!FileManager.default.fileExists(atPath: media.path))

        // The "movie" is 18 bytes of text, so the job fails at preflight; stop
        // it rather than leaving it to race the next test's scratch dir.
        for job in jobs { await queue.cancel(job.id) }
        try? FileManager.default.removeItem(at: jobs[0].source)
    }

    /// A manifest whose media never arrived is an extension that died between
    /// its two writes. It must be dropped, not retried forever.
    @Test("share inbox drops a manifest with no media")
    func shareInboxDropsOrphanManifest() async throws {
        guard let dir = Self.usableInbox() else { return }
        let id = UUID()
        let orphan = ShareManifest.manifestURL(dir, id: id)
        try JSONEncoder().encode(ShareManifest(id: id, fileName: "gone.mp4", receivedAt: Date()))
            .write(to: orphan, options: .atomic)

        let queue = JobQueue(storeURL: Fixtures.scratch("jobs-orphan.json"))
        #expect(await ShareInbox.drain(into: queue) == 0)
        #expect(await queue.jobs.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: orphan.path))
    }

    /// Polls rather than sleeping a fixed time: the queue's own hops through
    /// the main actor make any single sleep either flaky or slow.
    private func settle(_ condition: @Sendable () async -> Bool,
                        timeout: Duration = .seconds(10)) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        Issue.record("timed out waiting for the queue to settle")
    }
}
