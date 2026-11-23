import Testing
import Accelerate
import AVFoundation
import CoreMedia
import CryptoKit
import Foundation
import os
@testable import naqi

/// M2 exit criteria. The DSP tests are the important ones: htdemucs' STFT lives
/// outside the graph, so a transposed axis or a missing 0.5 feeds the model a
/// spectrogram it was never trained on and nothing downstream notices.
/// Serialized — htdemucs is ~1.3 GB resident and two sessions do not fit.
@Suite("Audio", .serialized)
struct AudioTests {

    // MARK: STFT

    /// Golden: the forward transform against the direct DFT definition, at the
    /// small config the Android suite uses (T % hop != 0, so the odd-tail branch
    /// the production size never reaches is exercised).
    @Test("STFT forward matches the direct DFT definition")
    func stftGolden() {
        let nfft = 64, hop = 16, T = 4123
        let a = probe(T, freqs: [37, 311, 1490], rate: 8000, seed: 1)
        let b = probe(T, freqs: [113, 902], rate: 8000, seed: 2)
        let stft = STFT(nfft: nfft, hop: hop, length: T)
        #expect(stft.bins == 32)
        #expect(stft.frames == 258)

        var got = [Float](repeating: .nan, count: 4 * stft.bins * stft.frames)
        stft.forward(a, b, into: &got)
        let want = referenceCaC([a, b], nfft: nfft, hop: hop)

        var maxDelta: Float = 0, maxRef: Float = 0
        for i in 0..<want.count {
            maxDelta = max(maxDelta, abs(got[i] - want[i]))
            maxRef = max(maxRef, abs(want[i]))
        }
        print("[stft] max|Δ| = \(maxDelta) against peak \(maxRef)")
        // The Android reference asserts atol 1e-4 against a numpy f64 golden;
        // hold the same gate rather than fitting one to the measurement.
        #expect(maxDelta < 1e-4)
    }

    @Test("frame arithmetic")
    func frameArithmetic() {
        #expect(STFT(length: 343_980).frames == 336)
        #expect(STFT(length: 335 * 1024).frames == 335)
        #expect(STFT(length: 335 * 1024 + 1).frames == 336)
        #expect(STFT(length: 336 * 1024 + 1).frames == 337)
        #expect(STFT(length: Demucs.seg).frames == Models.Demucs.specFrames)
        #expect(STFT(length: Demucs.seg).bins == Models.Demucs.specBins)
        #expect(STFT(nfft: 64, hop: 16, length: 4123).frames == 258)
    }

    /// iSTFT(STFT(x)) at the production geometry. Band-limited on purpose: the
    /// Nyquist row is dropped by contract, so a full-band probe caps the round
    /// trip near 36 dB no matter how good the transform is.
    @Test("iSTFT round-trips the STFT to > 80 dB in the interior")
    func stftRoundTrip() {
        let T = Demucs.seg
        let x = probe(T, freqs: [110, 220, 554.37, 1000, 3000, 7500], rate: 44100, seed: 3)
        let y = probe(T, freqs: [147, 880, 2200, 5000], rate: 44100, seed: 4)
        let stft = STFT(length: T)

        var cac = [Float](repeating: 0, count: 4 * stft.bins * stft.frames)
        stft.forward(x, y, into: &cac)
        var rx = [Float](repeating: 0, count: T), ry = [Float](repeating: 0, count: T)
        stft.inverse(cac, into: &rx, &ry)

        let guardBand = 8192
        let l = snrDB(x[guardBand..<(T - guardBand)], rx[guardBand..<(T - guardBand)])
        let r = snrDB(y[guardBand..<(T - guardBand)], ry[guardBand..<(T - guardBand)])
        print("[stft] interior round-trip SNR L=\(Int(l))dB R=\(Int(r))dB")
        #expect(l > 80 && r > 80)
        #expect(rx.allSatisfy { $0.isFinite })
        #expect(ry.allSatisfy { $0.isFinite })
    }

    // MARK: Downmix

    /// A dropped centre channel is where a 5.1 film keeps its dialogue, so every
    /// channel gets a distinct constant and the expected sum is checked exactly.
    @Test("BS.775 fold keeps the centre at -3 dB and drops LFE")
    func bs775() {
        let frames = 8
        let ch: [Float] = [1, 2, 3, 4, 5, 6]  // L R C LFE Ls Rs
        var src = [Float]()
        for _ in 0..<frames { src += ch }
        var dst = [Float](repeating: .nan, count: frames * 2)
        AudioDecoder.fold(src, channels: 6, frames: frames, into: &dst)

        let hp = AudioDecoder.halfPower
        let wantL = 1 + hp * 3 + hp * 5
        let wantR = 2 + hp * 3 + hp * 6
        for f in 0..<frames {
            #expect(abs(dst[2 * f] - wantL) < 1e-6, "L fold at frame \(f)")
            #expect(abs(dst[2 * f + 1] - wantR) < 1e-6, "R fold at frame \(f)")
        }
        // Losing the centre would land on 1 + 0.707*5 = 4.54, losing the
        // surrounds on 1 + 0.707*3 = 3.12, and folding LFE in on +2.83.
        #expect(abs(wantL - 6.65685) < 1e-4)
        #expect(abs(wantR - 8.36396) < 1e-4)

        // Stereo and mono stay untouched.
        var st = [Float](repeating: .nan, count: 4)
        AudioDecoder.fold([0.25, -0.5, 0.75, -1], channels: 2, frames: 2, into: &st)
        #expect(st == [0.25, -0.5, 0.75, -1])
        var mo = [Float](repeating: .nan, count: 4)
        AudioDecoder.fold([0.25, -0.5], channels: 1, frames: 2, into: &mo)
        #expect(mo == [0.25, 0.25, -0.5, -0.5])
    }

    @Test("stats windows: full decode under 80 s, 20 windows over it")
    func statsWindows() {
        #expect(AudioStats.windows(duration: CMTime(seconds: 12.8, preferredTimescale: 600)).isEmpty)
        #expect(AudioStats.windows(duration: CMTime(seconds: 80, preferredTimescale: 600)).isEmpty)
        let w = AudioStats.windows(duration: CMTime(seconds: 600, preferredTimescale: 600))
        #expect(w.count == 20)
        #expect(w.first?.start.seconds == 0)
        // Last window is flush against the end, never past it.
        #expect(abs(w.last!.end.seconds - 600) < 1e-3)
        #expect(zip(w, w.dropFirst()).allSatisfy { $0.end <= $1.start })
    }

    /// The windows have to be *readable*, not just well-shaped. Handing all
    /// twenty to `reset(forReadingTimeRanges:)` up front raises
    /// `NSInternalInconsistencyException` — an ObjC exception, so not a failure
    /// any caller can catch: it terminates the app. Every music job on a source
    /// over 80 s died this way, and the pure-function test above passed the
    /// whole time. This one decodes them.
    @Test("a source past the 80 s threshold decodes all twenty sampled windows")
    func statsOverThreshold() async throws {
        let url = try Fixtures.audioClip("stats-100s.m4a", seconds: 100)
        let asset = AVURLAsset(url: url)
        let track = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        let duration = try await asset.load(.duration)
        #expect(AudioStats.windows(duration: duration).count == 20)

        let stats = try AudioStats.measure(track: track, duration: duration)
        // 20 windows x 2 s at 44.1 kHz, less whatever the decoder trims at each
        // seam — an order of magnitude under a full decode, and nowhere near a
        // single window.
        #expect(stats.frames > 20 * 44_100 / 2, "only \(stats.frames) frames: windows were skipped")
        #expect(stats.frames < 60 * 44_100, "\(stats.frames) frames: the whole track was decoded")
        // A 440 Hz tone at 0.25 amplitude: mean ~0, std ~0.25/sqrt(2).
        #expect(abs(stats.mean) < 0.02)
        #expect(abs(stats.std - 0.25 / Float(2).squareRoot()) < 0.02)
    }

    // MARK: Music gate

    @Test("YAMNet music class ranges are inclusive",
          arguments: [24, 32, 132, 276])
    func musicClassIncluded(index: Int) {
        var scores = [Float](repeating: 0, count: MusicGate.classes)
        scores[index] = 0.7
        let got = scores.withUnsafeBufferPointer { MusicGate.musicScore($0.baseAddress!) }
        #expect(got == 0.7)
    }

    @Test("classes beside the YAMNet music ranges stay excluded",
          arguments: [23, 33, 131, 277])
    func nonMusicClassExcluded(index: Int) {
        var scores = [Float](repeating: 0, count: MusicGate.classes)
        scores[index] = 0.7
        let got = scores.withUnsafeBufferPointer { MusicGate.musicScore($0.baseAddress!) }
        #expect(got == 0)
    }

    @Test("-60 dBFS silence bypasses YAMNet")
    func gateSilenceFloor() throws {
        let calls = Box(0)
        let gate = MusicGate { _ in calls.v += 1; return 1 }
        let mono = [Float](repeating: 0.000_999, count: Demucs.seg)
        let score = try mono.withUnsafeBufferPointer {
            try gate.score($0.baseAddress!, frames: $0.count)
        }
        #expect(score == 0)
        #expect(calls.v == 0)
    }

    @Test("YAMNet takes the max and flushes its last frame against the tail")
    func gateFrameTiling() throws {
        let mono = (0..<Demucs.seg).map { 0.01 + 0.49 * Float($0) / Float(Demucs.seg - 1) }
        let starts = Box<[Float]>([]), ends = Box<[Float]>([])
        let scores: [Float] = [0.01, 0.02, 0.14]
        let gate = MusicGate { frame in
            let i = starts.v.count
            starts.v.append(frame[0])
            ends.v.append(frame[MusicGate.frame - 1])
            return scores[i]
        }
        let got = try mono.withUnsafeBufferPointer {
            try gate.score($0.baseAddress!, frames: $0.count)
        }

        let n = MusicGate.out16kLength(mono.count)
        func expected(_ i: Int) -> Float {
            let x = Double(i) * 44_100 / 16_000
            let i0 = Int(x), f = Float(x - Double(i0))
            return mono[i0] + (mono[min(i0 + 1, mono.count - 1)] - mono[i0]) * f
        }
        #expect(got == 0.14, "the score must be MAX, not mean")
        #expect(starts.v.count == 3)
        #expect(abs(starts.v[2] - expected(n - MusicGate.frame)) < 1e-6)
        #expect(abs(ends.v[2] - expected(n - 1)) < 1e-6)
    }

    @Test("a score at 0.15 exits without scoring later frames")
    func gateThresholdEarlyExit() throws {
        let calls = Box(0)
        let scores: [Float] = [0.10, MusicGate.threshold, 0.90]
        let gate = MusicGate { _ in defer { calls.v += 1 }; return scores[calls.v] }
        let mono = [Float](repeating: 0.25, count: Demucs.seg)
        let got = try mono.withUnsafeBufferPointer {
            try gate.score($0.baseAddress!, frames: $0.count)
        }
        #expect(got == MusicGate.threshold)
        #expect(calls.v == 2)
    }

    @Test("the bundled YAMNet recognizes a harmonic chord")
    func bundledGateChord() throws {
        let gate = try #require(MusicGate.open())
        defer { ModelRegistry.evict(Models.YamNet.file) }
        var mono = [Float](repeating: 0, count: Demucs.seg)
        for i in mono.indices {
            let t = Double(i) / Double(Models.Demucs.sampleRate)
            for frequency in [220.0, 277.18, 329.63] {
                for harmonic in 1...4 {
                    mono[i] += Float(sin(2 * Double.pi * frequency * Double(harmonic) * t)
                                     / Double(harmonic))
                }
            }
        }
        var peak: Float = 0
        for sample in mono { peak = max(peak, abs(sample)) }
        for i in mono.indices { mono[i] *= 0.5 / peak }
        let score = try mono.withUnsafeBufferPointer {
            try gate.score($0.baseAddress!, frames: $0.count)
        }
        #expect(score > 0.8, "harmonic chord scored \(score)")
    }

    @Test("a gated-off chunk reconstructs the input without running htdemucs")
    func gatePassthrough() throws {
        let frames = 50_000
        let left = probe(frames, freqs: [110, 700, 2400], rate: 44_100, seed: 21)
        let right = probe(frames, freqs: [180, 1300, 4800], rate: 44_100, seed: 22)
        var input = [Float](repeating: 0, count: 2 * frames)
        for i in 0..<frames {
            input[2 * i] = left[i] * 0.4
            input[2 * i + 1] = right[i] * 0.4
        }
        let inferences = Box(0)
        var output = [Float]()
        let sep = Demucs(mean: 0, std: 1, estimatedFrames: frames,
                         infer: { _, _, _, _ in inferences.v += 1 },
                         musicScore: { _, _ in 0 },
                         emit: { p, n in
                             output.append(contentsOf: UnsafeBufferPointer(start: p, count: 2 * n))
                         })
        try input.withUnsafeBufferPointer { try sep.feed($0.baseAddress!, frames: frames) }
        try sep.finish()

        #expect(inferences.v == 0)
        #expect(sep.skippedChunks == 1)
        #expect(output.count == input.count)
        #expect(snrDB(input[...], output[...]) > 100)
    }

    @Test("the ±2 tier starts at an own score of 0.02",
          arguments: [Demucs.dilation2MinScore - 0.001, Demucs.dilation2MinScore])
    func farDilationBoundary(ownScore: Float) throws {
        let frames = 2 * Demucs.stride - Demucs.maxShift + 1
        let input = [Float](repeating: 0, count: 2 * frames)
        let nextScore = Box(0)
        let scripted = [ownScore, Float(0), MusicGate.threshold]
        let sep = Demucs(mean: 0, std: 1, estimatedFrames: frames,
                         infer: { _, _, _, _ in throw GateStop.stop },
                         musicScore: { _, _ in
                             defer { nextScore.v += 1 }
                             return scripted[nextScore.v]
                         }, emit: { _, _ in })
        try input.withUnsafeBufferPointer { try sep.feed($0.baseAddress!, frames: frames) }
        do {
            try sep.finish()
            Issue.record("expected the first separated chunk to stop the test")
        } catch GateStop.stop {
            // Expected: at 0.019 chunk 0 skips and chunk 1 stops; at 0.02 chunk 0 stops.
        }
        #expect(sep.skippedChunks == (ownScore < Demucs.dilation2MinScore ? 1 : 0))
    }

    // MARK: Overlap-add driver

    @Test("soft clip is transparent below 0.95 and bounded at 1.0")
    func softClip() {
        #expect(Demucs.softclip(0.5) == 0.5)
        #expect(Demucs.softclip(-0.95) == -0.95)
        #expect(Demucs.softclip(1.0) < 1)
        // The bound is 1.0 asymptotically; in Float the knee saturates there
        // once tanh rounds to 1, which is what the int16 quantizer needs.
        for x in stride(from: Float(0.96), through: 8, by: 0.05) {
            #expect(Demucs.softclip(x) > 0.95 && Demucs.softclip(x) <= 1)
            #expect(Demucs.softclip(-x) == -Demucs.softclip(x))
        }
    }

    /// The driver against an identity graph: the spec branch returns its input
    /// mask and the time branch is silent, so Σg·x/Σg must hand the input back.
    @Test("overlap-add driver reconstructs its input through an identity graph")
    func overlapAddIdentity() throws {
        let frames = 132_300  // 3 s — two chunks, the second a short tail
        let x = probe(frames, freqs: [110, 440, 1300, 4000], rate: 44100, seed: 5)
        let y = probe(frames, freqs: [220, 990, 2600], rate: 44100, seed: 6)
        var input = [Float](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            input[2 * i] = x[i] * 0.4
            input[2 * i + 1] = y[i] * 0.4
        }
        // Realistic scalars: the normalize/denormalize round trip is part of
        // what this test covers.
        var sum = 0.0, sumsq = 0.0
        for i in 0..<frames {
            let m = Double(input[2 * i] + input[2 * i + 1]) * 0.5
            sum += m
            sumsq += m * m
        }
        let mean = Float(sum / Double(frames))
        let std = Float(((sumsq - sum * sum / Double(frames)) / Double(frames - 1)).squareRoot())

        var got = [Float]()
        got.reserveCapacity(frames * 2)
        let progress = Box<[Int]>([])
        let sep = Demucs(mean: mean, std: std, estimatedFrames: frames,
                         infer: { _, spec, specSum, timeSum in
                             specSum.update(from: spec, count: Demucs.specSize)
                             vDSP_vclr(timeSum, 1, vDSP_Length(2 * Demucs.seg))
                         },
                         onChunk: { done, _ in progress.v.append(done) },
                         emit: { p, n in got.append(contentsOf: UnsafeBufferPointer(start: p, count: n * 2)) })

        var fed = 0
        while fed < frames {
            let n = min(8192, frames - fed)
            try input.withUnsafeBufferPointer { try sep.feed($0.baseAddress! + fed * 2, frames: n) }
            fed += n
        }
        try sep.finish()

        #expect(sep.emitted == frames, "emitted \(sep.emitted) != fed \(frames)")
        #expect(sep.framesFed == frames)
        #expect(got.count == frames * 2)
        #expect(sep.nonFinite == 0)
        #expect(got.allSatisfy { $0.isFinite }, "NaN/Inf in the emitted stream")
        #expect(progress.v == Array(1...sep.chunksDone), "onChunk must be monotonic from 1")

        let guardBand = 8192
        var refL = [Float](), gotL = [Float](), refR = [Float](), gotR = [Float]()
        for i in guardBand..<(frames - guardBand) {
            refL.append(input[2 * i]); gotL.append(got[2 * i])
            refR.append(input[2 * i + 1]); gotR.append(got[2 * i + 1])
        }
        let l = snrDB(refL[...], gotL[...]), r = snrDB(refR[...], gotR[...])
        print("[demucs] identity round-trip SNR L=\(Int(l))dB R=\(Int(r))dB over \(sep.chunksDone) chunks")
        #expect(l > 60 && r > 60)
    }

    /// The time branch on its own, on an input **shorter than one segment**.
    ///
    /// Two seams that nothing else covers. (a) The spec branch is zeroed, so the
    /// only thing reaching the output is `timeSum`, read at `[read + j]` for L
    /// and `[seg + read + j]` for R — swap those planes or drop the centre-trim
    /// and this test collapses while the existing identity test stays green,
    /// because that one zeroes the time branch entirely (spec-audio §3.6
    /// `:424-425`, §4.2/2). (b) One short chunk means `clen < seg`, so
    /// `delta = 42610` and `readStart = -21305`: the gather runs its
    /// before-the-stream and past-`writePos` zero-fill branches and the output
    /// is read back centre-trimmed, which is torch `TensorChunk.padded` and the
    /// thing the demucs.onnx C++ centring bug gets wrong (§3.6).
    @Test("time branch alone reconstructs a sub-segment input")
    func timeBranchShortInput() throws {
        let frames = 50_000  // < seg (114 660): one chunk, delta > 0, readStart < 0
        #expect(frames < Demucs.seg)
        let x = probe(frames, freqs: [130, 700, 2100], rate: 44100, seed: 7)
        let y = probe(frames, freqs: [90, 1500, 5200], rate: 44100, seed: 8)
        var input = [Float](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            input[2 * i] = x[i] * 0.3 + 0.02       // a DC offset so `mean` is not ~0
            input[2 * i + 1] = y[i] * 0.3 + 0.02
        }

        var got = [Float]()
        let sep = Demucs(mean: 0.02, std: 0.21, estimatedFrames: frames,
                         infer: { wav, _, specSum, timeSum in
                             // Spec branch silent; time branch hands the chunk's
                             // own planar input straight back.
                             vDSP_vclr(specSum, 1, vDSP_Length(Demucs.specSize))
                             timeSum.update(from: wav, count: 2 * Demucs.seg)
                         },
                         emit: { p, n in got.append(contentsOf: UnsafeBufferPointer(start: p, count: n * 2)) })
        try input.withUnsafeBufferPointer { try sep.feed($0.baseAddress!, frames: frames) }
        try sep.finish()

        #expect(sep.chunksDone == 1, "a sub-segment input must be exactly one chunk")
        #expect(sep.emitted == frames)          // I1
        #expect(sep.framesFed == frames)
        #expect(got.count == frames * 2)
        #expect(sep.nonFinite == 0)

        var refL = [Float](), gotL = [Float](), refR = [Float](), gotR = [Float]()
        for i in 0..<frames {
            refL.append(input[2 * i]); gotL.append(got[2 * i])
            refR.append(input[2 * i + 1]); gotR.append(got[2 * i + 1])
        }
        let l = snrDB(refL[...], gotL[...]), r = snrDB(refR[...], gotR[...])
        print("[demucs] time-branch short-input SNR L=\(Int(l))dB R=\(Int(r))dB")
        // Σg·x/Σg == x exactly in exact arithmetic, so this is float rounding
        // only — an L/R swap or a missing centre-trim lands near 0 dB, not 90.
        #expect(l > 90 && r > 90)
        // The channels carry different signals, so a swap has to fail the
        // cross-check too even if the SNR gate were ever loosened.
        #expect(snrDB(refL[...], gotR[...]) < 20)
    }

    /// I1 across the shapes the geometry branches on: nothing fed, a single
    /// sample, exactly one segment, one sample either side of a stride, and a
    /// three-chunk stream. `emitted == framesFed` exactly — nothing in the walk
    /// caps the output (spec-audio §3.9 I1, §3.4).
    @Test("emitted == framesFed at every stream length",
          arguments: [0, 1, 1000, Demucs.seg - 1, Demucs.seg, Demucs.stride + 1, 250_000])
    func emittedEqualsFed(frames: Int) throws {
        var input = [Float](repeating: 0, count: max(frames, 1) * 2)
        for i in 0..<frames {
            input[2 * i] = Float(sin(Double(i) * 0.001))
            input[2 * i + 1] = Float(cos(Double(i) * 0.0013))
        }
        var emitted = 0
        let sep = Demucs(mean: 0, std: 1, estimatedFrames: frames,
                         infer: { wav, _, specSum, timeSum in
                             vDSP_vclr(specSum, 1, vDSP_Length(Demucs.specSize))
                             timeSum.update(from: wav, count: 2 * Demucs.seg)
                         },
                         emit: { _, n in emitted += n })
        var fed = 0
        while fed < frames {
            let n = min(40_000, frames - fed)
            try input.withUnsafeBufferPointer { try sep.feed($0.baseAddress! + fed * 2, frames: n) }
            fed += n
        }
        try sep.finish()
        #expect(sep.framesFed == frames)
        #expect(sep.emitted == frames, "emitted \(sep.emitted) != fed \(frames)")
        #expect(emitted == frames)
        // The grid is every `stride` offset below `maxShift + framesFed`, and an
        // empty stream emits nothing at all rather than a chunk of silence.
        let grid = (Demucs.maxShift + frames + Demucs.stride - 1) / Demucs.stride
        #expect(sep.chunksDone == (frames == 0 ? 0 : grid))
    }

    // MARK: End to end

    @Test("music removal muxes a bit-identical video track and keeps A/V sync")
    func endToEnd() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("music-removed.mp4")
        let src = try await MediaSource.probe(inURL)

        let seen = Box<[Double]>([])
        let r = try await AudioPipeline.removeMusic(src, to: outURL, keepStems: .vocals,
                                                    progress: { seen.v.append($0) })

        #expect(FileManager.default.fileExists(atPath: outURL.path))
        #expect(r.nonFinite == 0, "\(r.nonFinite) non-finite samples out of the fp16 graph")
        #expect(!seen.v.isEmpty && seen.v == seen.v.sorted(), "progress must be monotonic")
        print("[demucs] separate \(Int(r.separateMs))ms = \(String(format: "%.2f", r.xRealtime))x realtime "
              + "(Android S23 baseline 0.55x)")

        // Video is copied compressed: the elementary stream must hash identically.
        let a = try await elementaryStreamDigest(inURL, .video)
        let b = try await elementaryStreamDigest(outURL, .video)
        #expect(a.hash == b.hash, "video elementary stream is not bit-identical")
        #expect(a.bytes == b.bytes)

        let out = try await MediaSource.probe(outURL)
        #expect(out.video != nil && out.audio != nil, "output is not playable as A/V")
        #expect(out.audio?.sampleRate == 44_100)
        let want = src.duration.seconds * 1000
        #expect(abs(r.audioDurationMs - want) < 50,
                "audio duration \(r.audioDurationMs)ms vs source \(want)ms")
        // Apple trims AAC priming with an edit list; Android left 42.67 ms in.
        #expect(abs(r.audioStartMs) < 50, "audio starts \(r.audioStartMs)ms in")
    }

    @Test("cancellation mid-job leaves no output file")
    func cancelLeavesNothing() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("cancelled-music.mp4")
        let src = try await MediaSource.probe(inURL)
        let stop = OSAllocatedUnfairLock(initialState: false)

        do {
            _ = try await AudioPipeline.removeMusic(src, to: outURL,
                                                    progress: { _ in stop.withLock { $0 = true } },
                                                    isCancelled: { stop.withLock { $0 } })
            Issue.record("expected cancellation to throw")
        } catch {
            // expected
        }
        #expect(!FileManager.default.fileExists(atPath: outURL.path), "partial file left behind")
    }
}

// MARK: - Helpers

/// Mutable capture for closures the production code calls back on its own queue.
private final class Box<T>: @unchecked Sendable {
    var v: T
    init(_ v: T) { self.v = v }
}

private enum GateStop: Error { case stop }

/// Deterministic band-limited probe: a sum of sinusoids with reproducible phases.
private func probe(_ n: Int, freqs: [Double], rate: Double, seed: UInt64) -> [Float] {
    var state = seed &* 6_364_136_223_846_793_005 &+ 1
    let phases = freqs.map { _ -> Double in
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return Double(state >> 11) / Double(1 << 53) * 2 * Double.pi
    }
    var out = [Float](repeating: 0, count: n)
    for i in 0..<n {
        var v = 0.0
        for (f, p) in zip(freqs, phases) { v += sin(2 * Double.pi * f * Double(i) / rate + p) }
        out[i] = Float(v / Double(freqs.count))
    }
    return out
}

private func snrDB(_ ref: ArraySlice<Float>, _ got: ArraySlice<Float>) -> Double {
    var signal = 0.0, noise = 0.0
    for (a, b) in zip(ref, got) {
        signal += Double(a) * Double(a)
        let d = Double(a) - Double(b)
        noise += d * d
    }
    return noise == 0 ? .infinity : 10 * log10(signal / noise)
}

/// The CaC packing straight from its definition: two reflect pads, a windowed
/// DFT per kept frame, `1/sqrt(nfft)`, Nyquist dropped, `[4][bins][frames]`.
private func referenceCaC(_ channels: [[Float]], nfft: Int, hop: Int) -> [Float] {
    let T = channels[0].count
    let le = (T + hop - 1) / hop
    let bins = nfft / 2
    let padL = hop / 2 * 3
    let padR = padL + le * hop - T
    let paddedSeg = T + padL + padR
    let half = nfft / 2
    let scale = 1 / Double(nfft).squareRoot()

    var win = [Double](repeating: 0, count: nfft)
    var cosT = [Double](repeating: 0, count: nfft)
    var sinT = [Double](repeating: 0, count: nfft)
    for n in 0..<nfft {
        win[n] = 0.5 * (1 - cos(2 * .pi * Double(n) / Double(nfft)))
        cosT[n] = cos(-2 * .pi * Double(n) / Double(nfft))
        sinT[n] = sin(-2 * .pi * Double(n) / Double(nfft))
    }

    var cac = [Float](repeating: 0, count: 4 * bins * le)
    for (c, x) in channels.enumerated() {
        var a = [Double](repeating: 0, count: paddedSeg)
        for i in 0..<T { a[padL + i] = Double(x[i]) }
        for j in 0..<padL { a[j] = Double(x[padL - j]) }
        for k in 0..<padR { a[padL + T + k] = Double(x[T - 2 - k]) }

        var b = [Double](repeating: 0, count: paddedSeg + nfft)
        for i in 0..<paddedSeg { b[half + i] = a[i] }
        for j in 0..<half { b[j] = a[half - j] }
        for k in 0..<half { b[half + paddedSeg + k] = a[paddedSeg - 2 - k] }

        let reBase = 2 * c * bins * le, imBase = (2 * c + 1) * bins * le
        for f in 2..<(2 + le) {
            let s = f * hop
            for bin in 0..<bins {
                var re = 0.0, im = 0.0
                for n in 0..<nfft {
                    let v = b[s + n] * win[n]
                    let k = (bin * n) % nfft
                    re += v * cosT[k]
                    im += v * sinT[k]
                }
                cac[reBase + bin * le + f - 2] = Float(re * scale)
                cac[imBase + bin * le + f - 2] = Float(im * scale)
            }
        }
    }
    return cac
}

private struct StreamDigest { let hash: String; let bytes: Int }

/// SHA-256 over the compressed payload, read with `outputSettings: nil` so it is
/// the elementary stream and not the container being compared.
private func elementaryStreamDigest(_ url: URL, _ type: AVMediaType) async throws -> StreamDigest {
    let asset = AVURLAsset(url: url)
    guard let track = try await asset.loadTracks(withMediaType: type).first else {
        return StreamDigest(hash: "", bytes: 0)
    }
    let r = try TrackReader.compressed(track: track)
    try r.start()
    var hasher = SHA256()
    var bytes = 0
    while let sb = r.next() {
        guard let bb = CMSampleBufferGetDataBuffer(sb) else { continue }
        var len = 0
        var ptr: UnsafeMutablePointer<CChar>?
        if CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil,
                                       totalLengthOut: &len, dataPointerOut: &ptr) == noErr, let ptr {
            hasher.update(bufferPointer: UnsafeRawBufferPointer(start: ptr, count: len))
            bytes += len
        }
    }
    try r.throwIfFailed()
    return StreamDigest(hash: hasher.finalize().map { String(format: "%02x", $0) }.joined(), bytes: bytes)
}
