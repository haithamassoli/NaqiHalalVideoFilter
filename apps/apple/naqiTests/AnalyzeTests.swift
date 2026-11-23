import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import ImageIO
import os
import Testing
@testable import naqi

/// Pass 1. The threshold table, the fire predicate, the hysteresis merge and the
/// whole-frame promotion are the numbers Android's QA was run against, so they
/// are asserted against the spec table rather than against this port's output.
/// Serialized: the end-to-end case loads ORT graphs.
@Suite("Analyze", .serialized)
struct AnalyzeTests {

    // MARK: - Strictness -> threshold (spec-analyze.md §2.3)

    @Test("strictness interpolates to the pinned table")
    func thresholds() {
        // class index -> (s=0, s=40 default, s=50, s=100)
        let table: [(Models.Nsfw.Class, Float, Float, Float, Float)] = [
            (.drawings, 0.50, 0.500, 0.500, 0.50),
            (.hentai, 1.00, 0.800, 0.750, 0.50),
            (.neutral, 0.30, 0.580, 0.650, 1.00),
            (.porn, 0.75, 0.490, 0.425, 0.10),
            (.sexy, 0.90, 0.580, 0.500, 0.10),
        ]
        for (c, s0, s40, s50, s100) in table {
            let i = c.rawValue
            #expect(abs(NsfwGate.threshold(i, strictness: 0) - s0) < 1e-6, "\(c) @0")
            #expect(abs(NsfwGate.threshold(i, strictness: 40) - s40) < 1e-6, "\(c) @40")
            #expect(abs(NsfwGate.threshold(i, strictness: 50) - s50) < 1e-6, "\(c) @50")
            #expect(abs(NsfwGate.threshold(i, strictness: 100) - s100) < 1e-6, "\(c) @100")
            // Out of range returns the endpoint, never an extrapolation.
            #expect(NsfwGate.threshold(i, strictness: -10) == NsfwGate.threshold(i, strictness: 0))
            #expect(NsfwGate.threshold(i, strictness: 250) == NsfwGate.threshold(i, strictness: 100))
        }
    }

    // MARK: - Fire predicate (§2.4)

    @Test("fire predicate, including the boundaries")
    func firePredicate() {
        func probs(drawings: Float = 0, hentai: Float = 0, neutral: Float = 0,
                   porn: Float = 0, sexy: Float = 0) -> [Float] {
            [drawings, hentai, neutral, porn, sexy]
        }

        #expect(NsfwGate.fires(probs(neutral: 0.05, porn: 0.90, sexy: 0.05), strictness: 50))
        #expect(!NsfwGate.fires(probs(drawings: 0.05, neutral: 0.90, porn: 0.03, sexy: 0.02), strictness: 50))

        // Margin exactly zero clears the threshold and fires.
        let pornThr = NsfwGate.threshold(Models.Nsfw.Class.porn.rawValue, strictness: 50)
        #expect(NsfwGate.fires(probs(porn: pornThr), strictness: 50))
        #expect(!NsfwGate.fires(probs(porn: pornThr.nextDown), strictness: 50))

        // The SFW veto is strict, so an equal margin does NOT fire. drawings'
        // threshold is 0.50 at every strictness, which makes the tie exact.
        #expect(!NsfwGate.fires(probs(drawings: 0.60, porn: pornThr + 0.10), strictness: 50))
        #expect(NsfwGate.fires(probs(drawings: 0.59, porn: pornThr + 0.10), strictness: 50))

        // Strong neutral vetoes at low strictness; at s=100 neutral's threshold
        // reaches 1.00 so its margin can never win and the veto disables itself.
        let vetoed = probs(neutral: 0.55, porn: 0.80)
        #expect(!NsfwGate.fires(vetoed, strictness: 0))
        #expect(NsfwGate.fires(vetoed, strictness: 100))
    }

    // MARK: - Hysteresis + merge (§3)

    @Test("hysteresis expands, clamps and merges")
    func hysteresis() {
        #expect(NsfwGate.intervals([], durationMs: 10_000).isEmpty)

        // Gap-free adjacency merges; one more millisecond splits it.
        #expect(NsfwGate.intervals([1000, 3001], durationMs: 100_000) == [500...4501])
        #expect(NsfwGate.intervals([1000, 3002], durationMs: 100_000) == [500...2500, 2502...4502])

        // Both ends clamp into [0, duration], per firing, before merging.
        #expect(NsfwGate.intervals([200], durationMs: 10_000) == [0...1700])
        #expect(NsfwGate.intervals([9800], durationMs: 10_000) == [9300...10_000])

        // Input need not be sorted, and duplicates collapse.
        #expect(NsfwGate.intervals([3002, 1000], durationMs: 100_000) == [500...2500, 2502...4502])
        #expect(NsfwGate.intervals([1000, 1000, 1000], durationMs: 100_000) == [500...2500])

        // Far apart stays apart.
        #expect(NsfwGate.intervals([1000, 10_000], durationMs: 100_000) == [500...2500, 9500...11_500])

        // Unknown duration must not collapse everything to [0,1].
        #expect(NsfwGate.intervals([5000], durationMs: 0) == [4500...6500])
    }

    // MARK: - Whole-frame promotion (§7)

    @Test("whole-frame bridges, floors and overflows")
    func wholeFrame() {
        func track(_ a: Int64, _ b: Int64) -> FaceTrackEdl {
            FaceTrackEdl(startMs: a, endMs: b,
                         keyframes: [.init(timeMs: a, rect: NRect(left: 0.4, top: 0.4, right: 0.6, bottom: 0.6))])
        }

        // A 400 ms gap bridges; 401 ms does not.
        #expect(AnalyzePass.promoteToWholeFrame([], tracks: [track(0, 600), track(1000, 1600)])
                == [0...1600])
        #expect(AnalyzePass.promoteToWholeFrame([], tracks: [track(0, 600), track(1001, 1601)])
                == [0...600, 1001...1601])

        // The 500 ms floor kills an isolated short span…
        #expect(AnalyzePass.promoteToWholeFrame([], tracks: [track(0, 300)]).isEmpty)
        // …but runs after the merge, so two 300 ms blips 300 ms apart survive together.
        #expect(AnalyzePass.promoteToWholeFrame([], tracks: [track(0, 300), track(600, 900)])
                == [0...900])

        // Nine simultaneous regions overflow the renderer's eight; eight do not.
        let nine = (0..<9).map { _ in track(1000, 2000) }
        #expect(AnalyzePass.overflowSpans(nine) == [1000...2000])
        #expect(AnalyzePass.overflowSpans(Array(nine.dropLast())).isEmpty)

        // A single range passes straight through the merge, unsorted.
        #expect(AnalyzePass.mergeRanges([5000...6000]) == [5000...6000])
    }

    // MARK: - EDL query contracts (§6.3, §4.5)

    @Test("a whole-frame interval suppresses every region at that time")
    func precedence() {
        let rect = NRect(left: 0.3, top: 0.3, right: 0.5, bottom: 0.5)
        let edl = Edl(censorIntervalsMs: [1200...1500],
                      faceTracks: [FaceTrackEdl(startMs: 1000, endMs: 2000,
                                                keyframes: [.init(timeMs: 1000, rect: rect)])])
        #expect(edl.regions(at: 1100) == [rect])
        #expect(edl.regions(at: 1300).isEmpty)
        // Inclusive at both ends.
        #expect(edl.fullFrame(at: 1200) && edl.fullFrame(at: 1500))
        #expect(!edl.fullFrame(at: 1501))
    }

    @Test("keyframes interpolate between samples and hold at the ends")
    func interpolation() {
        let a = NRect(left: 0, top: 0, right: 0.2, bottom: 0.2)
        let b = NRect(left: 0.4, top: 0.4, right: 0.6, bottom: 0.6)
        let tr = FaceTrackEdl(startMs: 950, endMs: 1150,
                              keyframes: [.init(timeMs: 1000, rect: a), .init(timeMs: 1100, rect: b)])
        #expect(tr.rect(at: 950) == a)          // held before the first keyframe
        #expect(tr.rect(at: 1150) == b)         // held after the last
        let mid = try! #require(tr.rect(at: 1050))
        #expect(abs(mid.left - 0.2) < 1e-6 && abs(mid.bottom - 0.4) < 1e-6)
        #expect(tr.rect(at: 949) == nil && tr.rect(at: 1151) == nil)
    }

    @Test("keyframe pad grows each axis 25 % and clamps after")
    func keyframePad() {
        let p = FaceTracker.pad(NRect(left: 0.4, top: 0.4, right: 0.6, bottom: 0.6))
        #expect(abs(p.left - 0.35) < 1e-6 && abs(p.right - 0.65) < 1e-6)
        #expect(FaceTracker.pad(NRect(left: 0, top: 0, right: 1, bottom: 1))
                == NRect(left: 0, top: 0, right: 1, bottom: 1))
    }

    // MARK: - Track lifetime (§4.4, §4.6)

    @Test("span pad, and a 2 s gap starts a fresh track")
    func trackLifetime() {
        let size = CGSize(width: 640, height: 640)
        let box = CGRect(x: 200, y: 200, width: 240, height: 240)

        let one = FaceTracker(who: .women)
        one.onFaces([box], uprightSize: size, ptsMs: 1000)
        let spans = one.finish()
        #expect(spans.count == 1)
        // A one-sample track spans 100 ms: half the sample gap on each side.
        #expect(spans.first?.startMs == 950 && spans.first?.endMs == 1050)

        let cont = FaceTracker(who: .women)
        cont.onFaces([box], uprightSize: size, ptsMs: 1000)
        cont.onFaces([box], uprightSize: size, ptsMs: 1100)
        #expect(cont.finish().count == 1)

        let split = FaceTracker(who: .women)
        split.onFaces([box], uprightSize: size, ptsMs: 1000)
        split.onFaces([box], uprightSize: size, ptsMs: 3500)
        #expect(split.finish().count == 2, "a 2.5 s gap must not be one track")
    }

    @Test("no vote cast censors in both Women and Men")
    func failSafeVerdict() {
        // The fail-safe applies to the two the picker offers. `.everyone` and
        // `.none` are unconditional by definition and never take a vote at all.
        for who in FilterOps.Who.userSelectable {
            #expect(GenderVote.shouldCensor(female: 0, male: 0, who: who), "0/0 must censor in \(who)")
            #expect(GenderVote.shouldCensor(female: 2, male: 2, who: who), "a tie must censor in \(who)")
        }
        #expect(GenderVote.shouldCensor(female: 0, male: 9, who: .everyone))
        #expect(!GenderVote.shouldCensor(female: 9, male: 0, who: .none))
        #expect(FilterOps.Who.everyone.skipsGenderVote && FilterOps.Who.none.skipsGenderVote)
        #expect(!FilterOps.Who.women.skipsGenderVote && !FilterOps.Who.men.skipsGenderVote)
        #expect(GenderVote.shouldCensor(female: 3, male: 1, who: .women))
        #expect(!GenderVote.shouldCensor(female: 1, male: 3, who: .women))
        #expect(GenderVote.shouldCensor(female: 1, male: 3, who: .men))
        #expect(!GenderVote.shouldCensor(female: 3, male: 1, who: .men))
    }

    @Test("everyone never invokes the gender voter")
    func everyoneSkipsVoter() {
        let box = CGRect(x: 100, y: 100, width: 200, height: 200)
        let size = CGSize(width: 640, height: 640)
        var everyoneCalls = 0
        let everyone = FaceTracker(who: .everyone)
        everyone.onFaces([box], uprightSize: size, ptsMs: 1000) { _ in everyoneCalls += 1; return -1 }
        everyone.onFaces([box], uprightSize: size, ptsMs: 1100) { _ in everyoneCalls += 1; return -1 }
        #expect(everyoneCalls == 0)

        var womenCalls = 0
        let women = FaceTracker(who: .women)
        women.onFaces([box], uprightSize: size, ptsMs: 1000) { _ in womenCalls += 1; return -1 }
        women.onFaces([box], uprightSize: size, ptsMs: 1100) { _ in womenCalls += 1; return -1 }
        #expect(womenCalls == 1, "the control path did not reach the voter")
    }

    @Test("disabling scene censoring emits no gate tensors")
    func disabledGateSkipsTensorLane() async throws {
        let asset = AVURLAsset(url: try requireQAVideo())
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        let naturalSize = try await track.load(.naturalSize)
        let sampler = try FrameSampler(track: track,
                                       transform: .identity(size: naturalSize),
                                       gateEnabled: false,
                                       endMs: 1000)
        var tensors = 0
        let stats = try await sampler.run { frame in
            if frame.gate != nil { tensors += 1 }
        }
        #expect(stats.emitted > 0)
        #expect(stats.gated == 0)
        #expect(tensors == 0)
    }

    // MARK: - End to end

    @Test("analyze the QA clip end to end")
    func endToEnd() async throws {
        let url = try requireQAVideo()
        let source = try await MediaSource.probe(url)
        var ops = FilterOps()
        ops.censorTarget = .face
        ops.censor = true
        ops.strictness = 40

        let r = try await AnalyzePass.run(source, ops: ops)

        let durationMs = source.duration.convertScale(1000, method: .default).value
        let expectedSamples = Int(Double(durationMs) / 1000 * AnalyzeConstants.sampleFPS)
        #expect(r.sampledFrames > expectedSamples * 8 / 10, "sampled \(r.sampledFrames), expected ~\(expectedSamples)")
        #expect(r.gateFrames == (r.sampledFrames + 1) / 2, "the gate must consume every 2nd emitted frame")

        for i in r.edl.censorIntervalsMs {
            #expect(i.lowerBound >= 0 && i.upperBound <= durationMs)
        }
        for t in r.edl.faceTracks {
            #expect(t.startMs <= t.endMs && !t.keyframes.isEmpty)
            #expect(t.keyframes == t.keyframes.sorted { $0.timeMs < $1.timeMs })
            for k in t.keyframes {
                #expect(k.rect.left >= 0 && k.rect.right <= 1 && k.rect.top >= 0 && k.rect.bottom <= 1)
                #expect(!k.rect.isEmpty)
            }
        }
        #expect(r.edl.faceTracks == r.edl.faceTracks.sorted { $0.startMs < $1.startMs })
        // The EDL is the only thing crossing into pass 2, so it must round-trip.
        #expect(try Edl.fromJSONData(r.edl.toJSONData()) == r.edl)

        print("""
            [analyze] \(Int(durationMs))ms clip: \(r.decodedFrames) decoded / \(r.sampledFrames) sampled \
            / \(r.gateFrames) gated in \(Int(r.wallMs))ms
              \(String(format: "%.1f", r.decodedFramesPerSecond)) decoded fps, \
            \(String(format: "%.2f", r.msPerSampledFrame)) ms/sampled frame, \
            \(String(format: "%.1f", Double(durationMs) / r.wallMs))x realtime
              firings=\(r.firings) intervals=\(r.edl.censorIntervalsMs.count) tracks=\(r.edl.faceTracks.count)
            """)
    }

    @Test("whole-frame mode promotes the same clip's tracks")
    func endToEndWholeFrame() async throws {
        let url = try requireQAVideo()
        let source = try await MediaSource.probe(url)
        var ops = FilterOps()
        ops.censorTarget = .face
        ops.censorMode = .wholeFrame

        let r = try await AnalyzePass.run(source, ops: ops)
        for i in r.edl.censorIntervalsMs {
            #expect(i.upperBound - i.lowerBound >= AnalyzeConstants.minFullFrameMs,
                    "\(i) is under the 500 ms floor")
        }
        // Every region is suppressed wherever a promoted interval is active.
        for i in r.edl.censorIntervalsMs {
            #expect(r.edl.regions(at: i.lowerBound).isEmpty)
        }
        print("[analyze whole-frame] \(r.edl.censorIntervalsMs.count) spans from \(r.edl.faceTracks.count) tracks")
    }

    // MARK: - Progress and cancellation

    /// The analyze pass is the longest stage of a censor-only job and the bar
    /// used to sit frozen for all of it.
    ///
    /// What is pinned: the band is monotonic and inside 0...1, it starts at the
    /// head of the film, it **closes at exactly 1.0** — a stage that stops at
    /// 0.94 is a stage the UI will never mark done — and report *k* equals the
    /// decode position `k * 1000 / durationMs`, one report per second of source.
    /// That last one catches a wrong time scale, a missing clamp, and a throttle
    /// that drifted off `sampleFPS`; the report count pins the throttle directly.
    ///
    /// What it deliberately does **not** claim: that the value comes from the
    /// decode position rather than from a count of sampled frames. The sampler
    /// is uniform, so on any healthy source those are the same number — swapping
    /// one for the other was tried here and passed. They diverge only where a
    /// decode gap makes sampling non-uniform, and no fixture produces one.
    @Test("analyze progress is monotonic, positional, and closes at 1.0")
    func progressReporting() async throws {
        let url = try requireQAVideo()
        let source = try await MediaSource.probe(url)
        let seen = OSAllocatedUnfairLock<[Double]>(initialState: [])

        var ops = FilterOps()
        ops.censorTarget = .face
        let result = try await AnalyzePass.run(source, ops: ops,
                                               progress: { p in seen.withLock { $0.append(p) } })

        let reports = seen.withLock { $0 }
        #expect(reports.allSatisfy { $0 >= 0 && $0 <= 1 }, "out of band: \(reports)")
        #expect(reports == reports.sorted(), "not monotonic: \(reports)")
        #expect(reports.first == 0, "first report is \(reports.first ?? -1), not the head of the film")
        #expect(reports.last == 1, "last report is \(reports.last ?? -1) — the band never closes")

        // One report per `sampleFPS` sampled frames, i.e. per second of source,
        // plus the terminal 1.0. Thirteen and one on this clip.
        let every = Int(AnalyzeConstants.sampleFPS.rounded())
        let expected = (result.sampledFrames + every - 1) / every + 1
        #expect(reports.count == expected,
                "\(reports.count) reports for \(result.sampledFrames) sampled frames, expected \(expected)")

        let durationMs = Double(source.duration.convertScale(1000, method: .default).value)
        for (k, p) in reports.dropLast().enumerated() {
            let want = Double(k) * 1000 / durationMs
            #expect(abs(p - want) < 1e-6,
                    "report \(k) is \(p); the decode position at that point is \(want)")
        }
    }

    /// A polled cancel has to stop the pass *now*, not at the next stage
    /// boundary — the poll is what the Jobs layer bridges a user tap onto. Two
    /// things are asserted that a `return` instead of a `throw` would pass:
    /// the flag is never read again after it answers true (so no further frame
    /// was sampled), and the terminal `progress(1)` did not fire, because a
    /// cancelled stage that reports 100 % is a stage the UI will mark done.
    @Test("analyze cancellation throws CancellationError and stops immediately")
    func cancellation() async throws {
        let url = try requireQAVideo()
        let source = try await MediaSource.probe(url)
        let polls = OSAllocatedUnfairLock(initialState: 0)
        let seen = OSAllocatedUnfairLock<[Double]>(initialState: [])

        await #expect(throws: CancellationError.self) {
            _ = try await AnalyzePass.run(
                source, ops: FilterOps(),
                progress: { p in seen.withLock { $0.append(p) } },
                isCancelled: { polls.withLock { $0 += 1; return $0 >= 5 } })
        }

        // The clip samples 128 frames; five polls means it stopped on the fifth.
        #expect(polls.withLock { $0 } == 5,
                "polled \(polls.withLock { $0 }) times — the pass ran on past the cancel")
        let reports = seen.withLock { $0 }
        #expect(!reports.contains(1), "a cancelled pass reported 100 %: \(reports)")
        #expect(reports.allSatisfy { $0 < 0.05 }, "reported \(reports) before stopping at frame 5")
    }

    /// A 1080p clip died 133 s into analyze on one `VTPixelTransferSession`
    /// failure, taking a ten-minute job with it. Skipping the frame is right;
    /// skipping *every* frame is how an uncensored video gets published, so
    /// both bounds are pinned here rather than left to the caller.
    @Test("isolated detect failures are survivable, sustained ones are not")
    func detectFailureTolerance() {
        var f = DetectFailures()

        // The shape tv1 actually produced: a burst of four consecutive, twice
        // isolated. The cap must clear an observed burst with room to spare,
        // or a bad second of content kills a 90-minute job.
        for _ in 0..<4 {
            let giveUp = f.failed()
            #expect(giveUp == false, "a 4-frame burst tripped the cap")
        }
        f.succeeded()
        f = DetectFailures()

        // The real shape: one bad frame between good ones, over and over.
        // `failed()` mutates, so it cannot be called inside the `#expect`
        // macro's captured expression.
        for _ in 0..<50 {
            let giveUp = f.failed()
            #expect(giveUp == false)
            f.succeeded()
        }
        #expect(f.total == 50)
        #expect(f.exceededRate(sampled: 6_430) == false, "0.8 % of a 10-min pass must survive")
        // ...but the same 50 in a short pass is the detector, not the content.
        #expect(f.exceededRate(sampled: 200))

        // A broken detector fails consecutively and must stop the pass.
        var g = DetectFailures()
        let cap = AnalyzeConstants.detectFailStreakCap
        for i in 1..<cap {
            let giveUp = g.failed()
            #expect(giveUp == false, "gave up at \(i), cap is \(cap)")
        }
        let giveUp = g.failed()
        #expect(giveUp, "ran past \(cap) consecutive failures")
    }

    // MARK: - Pixel-math equivalence (§2.2, §5.2, §10.2)

    /// §10.2: the gate fill must stay bit-identical to the reference walk, and
    /// Android pins that with a zero-delta test at every rotation. Both halves
    /// of the Swift walk are *rewritten* — the rotation table is folded into an
    /// affine map and the colour conversion is a `SIMD16<Int32>` kernel — so a
    /// transposed axis or a `/1024`-instead-of-`>>10` would run silently and
    /// only show up as a shifted censored timeline.
    ///
    /// `reference` below is transcribed straight from `spec-analyze.md` §2.2,
    /// not from `FrameSampler`.
    @Test("gate tensor is bit-identical to the §2.2 reference at every rotation",
          arguments: [0, 90, 180, 270])
    func gateTensorEquivalence(rotation: Int) async throws {
        // `AVAssetTrack.asset` is weak, so the asset has to outlive the sampler.
        let asset = AVURLAsset(url: try requireQAVideo())
        let track = try #require(try await asset.loadTracks(withMediaType: .video).first)
        // Deliberately non-square, so a swapped axis cannot pass by accident.
        let (cw, ch) = (32, 20)
        let src = try Self.synthetic(width: cw, height: ch)

        let sampler = try FrameSampler(
            track: track,
            transform: VideoTransform(preferredTransform: Self.rotate(rotation, w: cw, h: ch),
                                      naturalSize: CGSize(width: cw, height: ch)))
        try sampler.prepare(cropW: cw, cropH: ch)

        // `gateTensor` reads an already-locked source, exactly as `convert` does.
        CVPixelBufferLockBaseAddress(src, .readOnly)
        let got = sampler.gateTensor(src)
        CVPixelBufferUnlockBaseAddress(src, .readOnly)
        let want = Self.gateReference(src, cropW: cw, cropH: ch, rotation: rotation)

        #expect(got.count == 3 * 224 * 224)
        let bad = zip(got, want).enumerated().first { $0.element.0 != $0.element.1 }
        #expect(bad == nil, "rot \(rotation): first delta at index \(bad?.offset ?? -1), got \(bad?.element.0 ?? 0) want \(bad?.element.1 ?? 0)")
        // /255 happened exactly once, and the clamps really clamp.
        #expect(got.allSatisfy { $0 >= 0 && $0 <= 1 })
        #expect(got.contains { $0 > 0.9 } && got.contains { $0 < 0.1 })
        // A rotation that did nothing would make every case identical.
        if rotation != 0 {
            #expect(want != Self.gateReference(src, cropW: cw, cropH: ch, rotation: 0))
        }
    }

    /// §5.2 + §10.14: InsightFace's square crop, edge-replicated, **0..255
    /// unscaled**. The gate is `/255` on the identical layout, so the one
    /// mistake this test exists to catch is a copy-pasted fill.
    @Test("gender crop matches the §5.2 reference and stays 0..255",
          arguments: [0, 90, 180, 270])
    func genderCropEquivalence(rotation: Int) throws {
        let (dw, dh) = (32, 20)
        let px = try Self.synthetic(width: dw, height: dh)
        let vt = VideoTransform(preferredTransform: Self.rotate(rotation, w: dw, h: dh),
                                naturalSize: CGSize(width: dw, height: dh))
        let frame = SampledFrame(ptsMs: 0, detect: px, transform: vt,
                                 orientation: FrameSampler.orientation(rotation), gate: nil)
        // Near a corner and small, so most of the 1.5x square falls outside the
        // frame — Android measured the median crop 23.7 % outside, so edge
        // replication is the common path, not a corner case.
        let rect = NRect(left: 0.02, top: 0.03, right: 0.18, bottom: 0.27)

        let got = GenderVote.crop(frame, rect)
        let want = Self.cropReference(frame, rect)

        #expect(got.count == 3 * 96 * 96)
        let bad = zip(got, want).enumerated().first { $0.element.0 != $0.element.1 }
        #expect(bad == nil, "rot \(rotation): first delta at index \(bad?.offset ?? -1), got \(bad?.element.0 ?? 0) want \(bad?.element.1 ?? 0)")
        // The trap: these are raw 0..255 floats. A /255 fill would put every
        // value under 1.0 and still run.
        #expect(got.allSatisfy { $0 >= 0 && $0 <= 255 })
        #expect(got.contains { $0 > 1 }, "crop looks scaled to 0..1 — that is §10.14's trap")
    }

    // MARK: - Fixtures for the pixel-math tests

    /// The four transforms, matching `TransformTests.cases`.
    private static func rotate(_ deg: Int, w: Int, h: Int) -> CGAffineTransform {
        switch deg {
        case 90: CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: CGFloat(h), ty: 0)
        case 180: CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: CGFloat(w), ty: CGFloat(h))
        case 270: CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: CGFloat(w))
        default: .identity
        }
    }

    /// Decoder-native bi-planar 4:2:0, filled so that Y, Cb and Cr are all
    /// distinguishable and the BT.601 result goes out of gamut in both
    /// directions.
    private static func synthetic(width: Int, height: Int) throws -> CVPixelBuffer {
        var out: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            nil, width, height, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &out)
        let px = try #require(out, "CVPixelBufferCreate failed (\(status))")

        CVPixelBufferLockBaseAddress(px, [])
        defer { CVPixelBufferUnlockBaseAddress(px, []) }
        let y = CVPixelBufferGetBaseAddressOfPlane(px, 0)!.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(px, 0)
        for j in 0..<height {
            for i in 0..<width { y[j * yRow + i] = UInt8((i * 7 + j * 13) & 0xFF) }
        }
        let c = CVPixelBufferGetBaseAddressOfPlane(px, 1)!.assumingMemoryBound(to: UInt8.self)
        let cRow = CVPixelBufferGetBytesPerRowOfPlane(px, 1)
        for j in 0..<(height / 2) {
            for i in 0..<(width / 2) {
                c[j * cRow + i * 2] = UInt8((i * 29 + j * 3) & 0xFF)          // Cb
                c[j * cRow + i * 2 + 1] = UInt8((i * 5 + j * 37 + 91) & 0xFF) // Cr
            }
        }
        return px
    }

    private static func bt601(_ y: Int32, _ u: Int32, _ v: Int32) -> (Int32, Int32, Int32) {
        func c(_ x: Int32) -> Int32 { min(max(x, 0), 255) }
        return (c(y + ((1436 * v) >> 10)),
                c(y - (((352 * u) + (731 * v)) >> 10)),
                c(y + ((1815 * u) >> 10)))
    }

    /// `spec-analyze.md` §2.2, transcribed.
    private static func gateReference(_ src: CVPixelBuffer,
                                      cropW: Int, cropH: Int, rotation: Int) -> [Float] {
        let side = 224, plane = side * side
        let gx = (0..<side).map { $0 * cropW / side }
        let gy = (0..<side).map { $0 * cropH / side }
        CVPixelBufferLockBaseAddress(src, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(src, .readOnly) }
        let yBase = CVPixelBufferGetBaseAddressOfPlane(src, 0)!.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(src, 0)
        let cBase = CVPixelBufferGetBaseAddressOfPlane(src, 1)!.assumingMemoryBound(to: UInt8.self)
        let cRow = CVPixelBufferGetBytesPerRowOfPlane(src, 1)

        var out = [Float](repeating: 0, count: 3 * plane)
        for oy in 0..<side {
            for ox in 0..<side {
                let dx: Int, dy: Int
                switch rotation {
                case 90: dx = oy; dy = side - 1 - ox
                case 180: dx = side - 1 - ox; dy = side - 1 - oy
                case 270: dx = side - 1 - oy; dy = ox
                default: dx = ox; dy = oy
                }
                let sx = gx[dx], sy = gy[dy]
                let ci = (sy >> 1) * cRow + (sx >> 1) * 2
                let (r, g, b) = bt601(Int32(yBase[sy * yRow + sx]),
                                      Int32(cBase[ci]) - 128,
                                      Int32(cBase[ci + 1]) - 128)
                let i = oy * side + ox
                out[i] = Float(r) / 255
                out[plane + i] = Float(g) / 255
                out[2 * plane + i] = Float(b) / 255
            }
        }
        return out
    }

    /// `spec-analyze.md` §5.2, transcribed. No `/255` — that is the point.
    private static func cropReference(_ frame: SampledFrame, _ rect: NRect) -> [Float] {
        let side = 96, plane = side * side
        let uw = Float(frame.transform.uprightSize.width)
        let uh = Float(frame.transform.uprightSize.height)
        let uprightW = Int(uw), uprightH = Int(uh)

        let half = max(rect.width * uw, rect.height * uh) * 1.5 / 2
        let x0 = (rect.left + rect.right) / 2 * uw - half
        let y0 = (rect.top + rect.bottom) / 2 * uh - half
        let step = half * 2 / Float(side)
        let ux = (0..<side).map { min(max(Int(x0 + Float($0) * step), 0), uprightW - 1) }
        let uy = (0..<side).map { min(max(Int(y0 + Float($0) * step), 0), uprightH - 1) }

        let px = frame.detect
        CVPixelBufferLockBaseAddress(px, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(px, .readOnly) }
        let yBase = CVPixelBufferGetBaseAddressOfPlane(px, 0)!.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(px, 0)
        let cBase = CVPixelBufferGetBaseAddressOfPlane(px, 1)!.assumingMemoryBound(to: UInt8.self)
        let cRow = CVPixelBufferGetBytesPerRowOfPlane(px, 1)

        var out = [Float](repeating: 0, count: 3 * plane)
        for j in 0..<side {
            for i in 0..<side {
                let dx: Int, dy: Int
                switch frame.transform.rotationDegrees {
                case 90: dx = uy[j]; dy = uprightW - 1 - ux[i]
                case 180: dx = uprightW - 1 - ux[i]; dy = uprightH - 1 - uy[j]
                case 270: dx = uprightH - 1 - uy[j]; dy = ux[i]
                default: dx = ux[i]; dy = uy[j]
                }
                let ci = (dy >> 1) * cRow + (dx >> 1) * 2
                let (r, g, b) = bt601(Int32(yBase[dy * yRow + dx]),
                                      Int32(cBase[ci]) - 128,
                                      Int32(cBase[ci + 1]) - 128)
                let idx = j * side + i
                out[idx] = Float(r)
                out[plane + idx] = Float(g)
                out[2 * plane + idx] = Float(b)
            }
        }
        return out
    }
}
