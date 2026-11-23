import Testing
import AVFoundation
import CoreMedia
import Foundation
import OnnxRuntimeBindings
import os
@testable import naqi

/// Records the numbers the PRD asks for next to the Android S23 baselines.
/// These are measurements, not pass/fail gates — the only assertion is that
/// each path produces finite output, because a fast NaN is worse than a slow
/// correct answer. Read the printed table.
///
/// **Every timing is a min-of-N.** A wall-clock mean on a developer machine
/// measures the machine, not the code: the same htdemucs segment read 617 ms
/// idle and 4231 ms with parallel builds running, and a 1-minute load average
/// lags far too much to gate on. Contention can only ever make a sample slower,
/// so the minimum is the closest thing to the true cost that a shared machine
/// can report.
///
/// ⚠ SIMULATOR CAVEATS: no Apple Neural Engine (the CoreML rows exercise
/// CPU/GPU only), no hardware H.264 decoder, and Vision falls back to CPU.
/// These bound the problem and prove the code paths; M7's acceptance numbers
/// must be taken on a device.
@Suite("Bench", .serialized)
struct BenchTests {

    static let baselineS23 = "0.55x realtime (S23, ORT-Android CPU + XNNPACK)"

    /// The staged tv1 clip, inside the test host's own container.
    ///
    /// **Not a repo path.** A simulator test process is sandboxed and cannot
    /// read `/Users/...`; a `#filePath`-derived path silently reported "0 tests
    /// in 1 suite passed", which looks exactly like success. Staging into the
    /// container is what the shell harness already did, and it is the only
    /// location proven readable from inside.
    static let tv1 = URL.documentsDirectory.appending(path: "bench-tv1.mp4")

    /// 32 min of 29.97 fps — past the real 30-minute gate, and no segment cut
    /// lands on a frame boundary. See `longSoak2997`.
    static let soak2997 = URL.documentsDirectory.appending(path: "soak-2997.mp4")

    /// Swift's String(format:) has no %s; pad explicitly.
    private func row(_ cols: String...) -> String {
        cols.map { $0.padding(toLength: max(13, $0.count + 1), withPad: " ", startingAt: 0) }.joined()
    }

    /// Typical latency. Minimum remains useful in traces, but it hides thermal
    /// and scheduling behavior when presented as the provider's speed.
    private func median(_ n: Int, _ body: () throws -> Void) rethrows -> Double {
        var samples: [Double] = []
        for _ in 0..<n {
            let t = ContinuousClock.now
            try body()
            samples.append(t.duration(to: .now).milliseconds)
        }
        samples.sort()
        return samples[samples.count / 2]
    }

    @Test("htdemucs: CPU vs CoreML execution provider")
    func demucsProviders() throws {
        let D = Models.Demucs.self
        let audioSeconds = Double(D.segmentFrames) / Double(D.sampleRate)
        let wave = try ORTValue.zeros(shape: [1, D.channels, D.segmentFrames])
        let spec = try ORTValue.zeros(shape: [1, 4, D.specBins, D.specFrames])

        print("\n=== htdemucs, one 2.6 s segment · Android baseline \(Self.baselineS23) ===")
        print(row("provider", "session ms", "median infer", "x-realtime", "finite"))

        for unit in [ComputeUnit.cpu, .coreMLGPU] {
            // Session creation is reported separately: the CoreML EP compiles
            // the graph to an MLModel here, which is a one-off cost on device
            // (it caches) but dominates a cold measurement.
            let t0 = ContinuousClock.now
            let m: OrtModel
            do { m = try ModelRegistry.model(D.file, compute: unit, threads: Ort.computeThreads) }
            catch { print(row("\(unit)", "FAILED", "\(error)")); continue }
            let sessionMs = t0.duration(to: .now).milliseconds

            var out: [String: ORTValue] = [:]
            let inferMs = try median(5) {
                out = try m.run([D.waveInput: wave, D.specInput: spec])
            }
            let finite = try (out[D.waveOutput]?.floats() ?? []).allSatisfy { $0.isFinite }
            print(row("\(unit)→\(m.executionCompute)",
                      String(format: "%.0f", sessionMs),
                      String(format: "%.0f", inferMs),
                      String(format: "%.2fx", audioSeconds / (inferMs / 1000)),
                      finite ? "yes" : "NO"))
            #expect(finite, "\(unit) produced non-finite output")
        }
        // Leave nothing resident: the next suite should not inherit 1.3 GB.
        ModelRegistry.evict(D.file)
    }

    /// The 1.5 GB jetsam budget. `phys_footprint` is the number iOS actually
    /// kills on — `resident_size` understates it and is the usual mistake.
    ///
    /// ⚠ On the SIMULATOR the absolute **bounds** the device figure rather than
    /// predicting it — different framework set, different allocator behaviour.
    /// It is NOT, as this comment used to claim, the host process's footprint:
    /// `mach_task_self_` is this app's own task, and the same 12.8 s clip
    /// measured 471 MB censor-only against 1629 MB music-only, which only a
    /// per-process counter can do. See the CORRECTION in `m0-results.md`.
    @Test("htdemucs resident cost")
    func footprint() throws {
        ModelRegistry.evict(Models.Demucs.file)
        let before = MemoryFootprint.current()
        _ = try ModelRegistry.model(Models.Demucs.file, threads: Ort.computeThreads)
        _ = ModelSmoke.demucs(.cpu)
        let loaded = MemoryFootprint.current()
        ModelRegistry.evict(Models.Demucs.file)

        print("\n=== htdemucs resident cost (delta is the only usable figure here) ===")
        print(String(format: "before %.0f MB → loaded %.0f MB → delta %+.0f MB   (device budget 1536 MB)",
                     Double(before) / 1_048_576, Double(loaded) / 1_048_576,
                     (Double(loaded) - Double(before)) / 1_048_576))
        #expect(loaded > 0, "phys_footprint unavailable")
    }

    /// The PRD's 1.5 GB ceiling, guarded at the one place it actually breaks.
    ///
    /// htdemucs is the whole budget: a music-only job on this 12.8 s clip
    /// measured **1629 MB** against 1536 MB, and it was invisible for a whole
    /// milestone because M0 recorded the simulator's `phys_footprint` as
    /// unreadable (it is not — see the CORRECTION in `m0-results.md`).
    ///
    /// Two numbers, because they fail differently. The **peak** is the working
    /// set; if that is over budget, no amount of cleanup helps and the fix has
    /// to be the chunk size or the graph. What is **retained afterwards** is
    /// ORT's CPU arena, which cannot be disabled through the ObjC API (hazard
    /// 9) and so has to be dropped with the session — `AudioPipeline` does that
    /// at its shared lifetime boundary.
    @Test("music separation stays inside the memory budget")
    func demucsFootprint() async throws {
        let src = try await MediaSource.probe(try requireQAVideo())
        let out = Fixtures.scratch("bench-music.m4a")
        defer { try? FileManager.default.removeItem(at: out) }

        ModelRegistry.evictAll()
        let baseline = MemoryFootprint.currentMB
        MemoryFootprint.resetPeak()
        _ = try await AudioPipeline.removeMusic(src, to: out, includeVideo: false)
        let peak = Double(MemoryFootprint.peakBytes) / 1_048_576
        let held = MemoryFootprint.currentMB
        // Redundant after a real pipeline run, but explicit for this diagnostic.
        ModelRegistry.evict(Models.Demucs.file)
        let released = MemoryFootprint.currentMB
        let budget = Double(MemoryFootprint.budgetBytes) / 1_048_576

        // A clean process starts here at ~46 MB. Anything far above that means
        // this is the shared full-suite process, where the reading is not
        // htdemucs: measured 4865 MB "peak" (cumulative across 120 other tests)
        // and a 0 MB evict delta, because the allocator does not hand pages
        // back to the OS under that pressure. Report, do not assert — the
        // alternative is a test that fails for a reason it cannot see.
        let isolated = baseline < 400

        print("""

            === htdemucs memory, 12.8 s clip (budget \(Int(budget)) MB) ===
            \(String(format: "baseline %.0f MB → peak during separation %.0f MB", baseline, peak))
            \(String(format: "still held after separation %.0f MB → after evict %.0f MB (gave back %.0f MB)",
                     held, released, held - released))
            \(isolated ? "" : """
                ⚠ shared test process (baseline \(Int(baseline)) MB, clean is ~46 MB) — \
                figures are cumulative and the assertions below are skipped. \
                Run alone: -only-testing:naqiTests/BenchTests/demucsFootprint()
                """)
            """)

        guard isolated else { return }

        // This was a `withKnownIssue` at 1721–1881 MB. `NaqiOrtArena` —
        // DisableCpuMemArena + DisableMemPattern through the C++ API the ObjC
        // wrapper does not expose — brought the peak to **1115 MB**, and the
        // wrapper was removed because it had done its job: it fails when the
        // expectation starts passing, which is how the fix announced itself.
        //
        // Now a live gate. If it goes red, the arena is back on: check that
        // `ORTSessionOptions` still answers `CXXAPIOrtSessionOptions` (the log
        // says so explicitly) before looking anywhere else.
        #expect(peak < budget, """
            peak \(Int(peak)) MB is over the \(Int(budget)) MB budget during separation itself, \
            so evicting the session afterwards cannot fix it
            """)
        // Live, and guarding the fix that DID land. Asserted as a **delta**,
        // never against an absolute: this is one process shared with every
        // other suite, so by the time the full run reaches here the baseline is
        // already ~1.5 GB of other tests' allocations and `released` reads
        // ~3 GB. That does not contradict the `m0-results.md` correction —
        // the counter is still this process's own, the process is just doing
        // more. The clean absolutes come from running this test alone.
        // 500, not 1000: the threshold was calibrated when the session held a
        // 1580 MB arena. With the arena disabled there is simply less to give
        // back (934 MB of 1006 MB), so the old number started failing *because*
        // the memory fix worked.
        #expect(held - released > 500, """
            evicting htdemucs gave back only \(Int(held - released)) MB of \(Int(held)) MB — \
            the session is still held
            """)
    }

    /// Does cutting intra-op threads buy back the memory htdemucs is over by?
    ///
    /// The cheap lever to try before the `DisableCpuMemArena` C-API shim, which
    /// costs a bridging header and a private-API dependency. ORT's
    /// memory-pattern planner allocates per intra-op thread, so this is a real
    /// hypothesis and not a guess — but which way it lands on Apple's memory
    /// hierarchy is not knowable from the docs, hence a sweep.
    ///
    /// Prints time *and* peak together deliberately: fewer threads is only a
    /// fix if what it costs in wall time is worth what it buys in headroom.
    @Test("htdemucs: does thread count move the memory peak?", arguments: [1, 2, 4])
    func demucsThreadSweep(threads: Int) async throws {
        let src = try await MediaSource.probe(try requireQAVideo())
        let out = Fixtures.scratch("bench-threads-\(threads).m4a")
        defer { try? FileManager.default.removeItem(at: out) }

        ModelRegistry.evictAll()
        Ort.threadOverride = threads
        defer { Ort.threadOverride = nil }

        let baseline = MemoryFootprint.currentMB
        MemoryFootprint.resetPeak()
        let t = ContinuousClock.now
        _ = try await AudioPipeline.removeMusic(src, to: out, includeVideo: false)
        let ms = t.duration(to: .now).milliseconds
        let peak = Double(MemoryFootprint.peakBytes) / 1_048_576
        ModelRegistry.evict(Models.Demucs.file)

        let budget = Double(MemoryFootprint.budgetBytes) / 1_048_576
        print(String(format: "threads %d → %7.0f ms, peak %6.0f MB (budget %.0f)%@%@",
                     threads, ms, peak, budget,
                     peak < budget ? "  ✅ UNDER" : "  ❌ over",
                     baseline < 400 ? "" : "  ⚠ shared process, peak is cumulative"))

        #expect(peak > 0)
    }

    /// The soak M5 could not run: **29.97 fps, past the real 30-minute gate.**
    ///
    /// `m5-soak-results.md` proved resume and no per-segment leak on a 90-minute
    /// asset, and said plainly what it did *not* prove: that asset is 30/1 fps,
    /// so every 300 000 ms cut is an exact frame time and the run never touched
    /// the path the reader head-guard exists for. At 29.97 fps **no** cut is
    /// frame-aligned — 8991.009, 17982.018, 26973.027 — which is the case where
    /// `AVAssetReader` hands back the straddling sample with its PTS rewritten
    /// to the range start, and the pre-roll test then writes that frame into
    /// *both* neighbouring segments.
    ///
    /// The symptom of that bug is duplicate PTS and nothing else, so that is
    /// what this counts, over every frame of the output. Covered at unit level
    /// by `RenderTests` cutting at 3010/7010 ms; this is the end-to-end proof.
    ///
    ///     ffmpeg -f concat -safe 0 -i <(printf "file '$PWD/qa-assets/tv1-h264.mp4'\n%.0s" 1 2 3) \
    ///            -c copy qa-assets/long-2997.mp4
    ///     D=$(xcrun simctl get_app_container <udid> com.haithamassoli.naqi data)  # or the Mac container
    ///     cp qa-assets/long-2997.mp4 "$D/Documents/soak-2997.mp4"
    @Test("29.97 fps past the 30-minute gate: segmented, and no duplicated seam frames",
          .enabled(if: FileManager.default.fileExists(atPath: BenchTests.soak2997.path)),
          .timeLimit(.minutes(60)))
    func longSoak2997() async throws {
        defer { try? FileManager.default.removeItem(at: Self.soak2997) }
        let src = try await MediaSource.probe(Self.soak2997)
        let durationMs = Int64(src.duration.seconds * 1000)
        #expect(durationMs >= Checkpoint.longSourceThresholdMs,
                "\(durationMs) ms is under the \(Checkpoint.longSourceThresholdMs) ms gate — not segmented")

        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true

        // Deliberately NOT cleaned up: when this fails, the output is the
        // evidence, and re-running to get it back costs seven minutes.
        let folder = Fixtures.scratch("soak-2997-out")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let job = Job.capture(source: Self.soak2997, ops: ops, destination: .userFolder, folder: folder)
        WorkDir.clear(Checkpoint.key(source: Self.soak2997, ops: ops))

        let stages = OSAllocatedUnfairLock<Set<Job.Stage>>(initialState: [])
        MemoryFootprint.resetPeak()
        let t = ContinuousClock.now
        let done = try await JobRunner.run(job, progress: { p in
            if let s = p.stage { stages.withLock { _ = $0.insert(s) } }
        })
        let ms = t.duration(to: .now).milliseconds

        #expect(done.shape == .segmented, "a \(durationMs) ms source ran as \(done.shape)")
        #expect(stages.withLock { $0.contains(.concat) }, "the concat stage never posted")

        let out = try await MediaSource.probe(try #require(done.output.url))
        let (frames, duplicateMs) = try await Self.scanPTS(out.url)
        let duplicates = duplicateMs.count
        let inFrames = try await RenderTests.sampleCount(src.url, .video)
        // Where they land relative to the 5-minute cuts is the whole diagnosis:
        // at a seam it is the head guard, anywhere else it is not.
        let seams = stride(from: Checkpoint.segmentMs, to: durationMs, by: Int(Checkpoint.segmentMs))
            .map { Int64($0) }
        let nearSeam = duplicateMs.filter { d in seams.contains { abs($0 - d) < 2_000 } }.count

        print("""

            === 29.97 fps soak (\(String(format: "%.1f", src.duration.seconds)) s, \
            \(durationMs / Checkpoint.segmentMs + 1) segments) ===
            \(String(format: "wall %.0f s, peak %.0f MB", ms / 1000,
                     Double(MemoryFootprint.peakBytes) / 1_048_576))
            frames in \(inFrames) → out \(frames)   duplicate PTS: \(duplicates) \
            (\(nearSeam) within 2 s of a cut, \(duplicates - nearSeam) elsewhere)
            duplicate times (ms): \(duplicateMs.prefix(20).map(String.init).joined(separator: ", "))
            segment cuts (ms):    \(seams.map(String.init).joined(separator: ", "))
            duration in \(String(format: "%.3f", src.duration.seconds)) s → \
            out \(String(format: "%.3f", out.duration.seconds)) s
            """)

        // KNOWN ISSUE, diagnosed down to a 1.2-second reproduction in
        // `RenderTests.segmentedConcat2997` — read that comment, not this one.
        // Short version: the render side is correct, the composition geometry
        // is exact, and `AVAssetExportPresetPassthrough` emits ~1 frame per
        // seam that the composition does not contain. Only at non-integer
        // frame durations, which is why M5's 30/1 fps soak saw none.
        withKnownIssue("passthrough export duplicates ~1 frame per seam at 29.97 fps") {
            #expect(duplicates == 0, """
                \(duplicates) duplicated frames at \(duplicateMs.prefix(10)) ms
                """)
            #expect(frames == inFrames, "the join holds \(frames) of \(inFrames) frames")
        }
        #expect(abs(out.duration.seconds - src.duration.seconds) < 1.0,
                "the join is \(out.duration.seconds)s against the source's \(src.duration.seconds)s")
    }

    /// Frame count and every duplicated presentation time, without decoding —
    /// `outputSettings: nil` is passthrough, so this walks 57 000 samples in
    /// seconds rather than re-running the whole decoder.
    ///
    /// Returns the duplicates **sorted by time**, not in the order they were
    /// met: `copyNextSampleBuffer` yields decode order, so with B-frames the
    /// first duplicate encountered says nothing about where in the movie the
    /// problem is.
    static func scanPTS(_ url: URL) async throws -> (frames: Int, duplicateMs: [Int64]) {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { return (0, []) }
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
        reader.add(output)
        reader.startReading()

        var frames = 0
        var seen = Set<Int64>()
        var dups: [Int64] = []
        while let sb = output.copyNextSampleBuffer() {
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            guard pts.isNumeric else { continue }
            frames += 1
            let us = pts.convertScale(1_000_000, method: .default).value
            if !seen.insert(us).inserted { dups.append(us / 1000) }
        }
        reader.cancelReading()
        return (frames, dups.sorted())
    }

    /// The like-for-like end-to-end number the PRD's performance goal is judged
    /// on: the **same clip** Android published its baseline against.
    ///
    /// **This lives in the test bundle, not the app, because it has to run in
    /// the RELEASE configuration.** The app's `-naqiScreen run` harness is
    /// `#if DEBUG`, so driving the app binary can only ever measure `-Onone` —
    /// and a first attempt did exactly that, reporting 5.8x slower than
    /// Android when most of what it measured was the optimisation flag. The
    /// test target compiles the app sources at `-O` under
    /// `-configuration Release`, which is the only honest way to get this
    /// number without shipping a debug hook in a release build.
    ///
    ///     D=$(xcrun simctl get_app_container <udid> com.haithamassoli.naqi data)
    ///     cp qa-assets/tv1-h264.mp4 "$D/Documents/bench-tv1.mp4"
    ///     xcodebuild test-without-building -scheme naqi -configuration Release \
    ///       -destination 'platform=iOS Simulator,id=<udid>' -derivedDataPath build.noindex \
    ///       -only-testing:naqiTests/BenchTests/tv1EndToEnd
    ///
    /// Not min-of-N: one pass is ~10 minutes of 1080p. The machine's quietness
    /// is therefore part of the reading and must be recorded next to it.
    /// Skips silently when the clip is not staged, which is the right default
    /// for a ~10-minute benchmark — but the skip is **indistinguishable from
    /// success** in xcodebuild's output ("Test run with 0 tests in 1 suite
    /// passed"). The recipe below therefore checks that 1 test ran.
    ///
    /// The trap that cost several runs: `simctl get_app_container` returns a
    /// **new UUID after every reinstall**, so a clip staged before a `test`
    /// action (which builds and installs) lands in the previous container and
    /// the test correctly sees nothing. Stage it immediately before
    /// `test-without-building`.
    @Test("tv1 end-to-end vs the S23 baseline",
          .enabled(if: FileManager.default.fileExists(atPath: BenchTests.tv1.path)),
          .timeLimit(.minutes(60)))
    func tv1EndToEnd() async throws {
        // Consume the opt-in. Leaving it staged makes EVERY later full-suite
        // run take 25 minutes instead of 2.5, and the cause is invisible from
        // the test output — it just looks like the suite got slow. Re-staging
        // is a 379 MB copy and about two seconds.
        defer { try? FileManager.default.removeItem(at: Self.tv1) }
        let source = try await MediaSource.probe(Self.tv1)
        let srcSeconds = source.duration.seconds
        // Censor-only, matching the Android baseline's configuration.
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = true

        MemoryFootprint.resetPeak()
        let analyzed = try await AnalyzePass.run(source, ops: ops)
        MemoryFootprint.note("analyze")

        let out = Fixtures.scratch("tv1-bench.mp4")
        defer { try? FileManager.default.removeItem(at: out) }
        let t = ContinuousClock.now
        let rendered = try await RenderPass.run(source: source, edl: analyzed.edl, ops: ops, output: out)
        let renderMs = t.duration(to: .now).milliseconds
        MemoryFootprint.note("render")

        // Android, censor-only on this clip, cooled S23 (docs/perf-plan-v3.md §0).
        let s23 = (analyze: 114_648.0, render: 89_411.0, total: 204_752.0)
        let appleTotal = analyzed.wallMs + renderMs

        func line(_ name: String, _ apple: Double, _ android: Double) -> String {
            String(format: "%-9s %10.0f %8.2fx %10.0f %8.2fx %8.2fx",
                   (name as NSString).utf8String!, apple, srcSeconds / (apple / 1000),
                   android, srcSeconds / (android / 1000), android / apple)
        }
        print("""

            === tv1 end-to-end, RELEASE, censor-only (\(String(format: "%.1f", srcSeconds)) s, 1920x1080, 29.97 fps) ===
            stage       Apple ms   x-real  S23 ms     x-real  speedup
            \(line("analyze", analyzed.wallMs, s23.analyze))
            \(line("render", renderMs, s23.render))
            \(line("total", appleTotal, s23.total))

            peak footprint \(String(format: "%.0f", Double(MemoryFootprint.peakBytes) / 1_048_576)) MB \
            of \(MemoryFootprint.budgetBytes / 1_048_576) MB budget
            frames \(rendered.frames), sampled \(analyzed.sampledFrames), tracks \(analyzed.edl.faceTracks.count)
            """)

        // The only assertion: it produced a real output. The numbers are a
        // measurement to read, not a gate — see the suite comment.
        #expect(rendered.frames > 0)
        #expect(FileManager.default.fileExists(atPath: out.path))
    }
}
