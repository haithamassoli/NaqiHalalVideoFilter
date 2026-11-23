import Accelerate
import Foundation
import OnnxRuntimeBindings
import os

/// The chunked overlap-add driver around htdemucs.
///
/// Audio is pushed in with `feed` and comes back out through `emit`, one flush
/// batch per 2.34 s chunk — nothing buffers a whole track. Positions are
/// absolute "virtual" sample indices into two rings: an input ring holding the
/// normalized mix and an output ring holding the weighted overlap-add
/// accumulator. `[0, maxShift)` is a zero pre-pad, so emitted frame `e` lives at
/// virtual position `maxShift + e`.
///
/// Deliberately free of AVFoundation and ORT: `infer` is injected, which is what
/// makes every seam here unit-testable against a fake graph (spec-audio §3).
/// Thread-confined — one worker drives `feed`/`finish`.
final class Demucs {
    /// Runs one chunk. Reads planar `wav` (`2*seg`) and the CaC spec
    /// (`4*bins*specFrames`), and writes the **sum of the kept stems** into
    /// `specSum` and `timeSum`. Summing inside the callee is what keeps four
    /// full stems — 18.4 MB per chunk — from ever being resident at once.
    typealias Infer = (_ wav: UnsafePointer<Float>, _ spec: UnsafePointer<Float>,
                       _ specSum: UnsafeMutablePointer<Float>,
                       _ timeSum: UnsafeMutablePointer<Float>) throws -> Void
    typealias MusicScore = (_ mono: UnsafePointer<Float>, _ frames: Int) throws -> Float

    // MARK: Geometry — fixed by the exported graph (spec-audio §1.2)

    static let seg = Models.Demucs.segmentFrames      // 114_660 = 2.6 s
    /// 10 % overlap. `ceil(seg/stride) == 2` is load-bearing: it is what bounds
    /// `outCap` (never aliases) and `inCap` (covers the worst-case lookback).
    static let stride = 103_194
    /// 0.5 s zero pre-pad, demucs' deterministic `shift_offset = 0` draw.
    static let maxShift = 22_050
    static let dilation = 2
    static let dilation2MinScore: Float = 0.02
    private static let gateRing = 8
    /// The gate's ±2-chunk dilation window.
    static let lookahead = dilation * stride          // 206_388
    static let inCap = 2 * seg + lookahead            // 435_708
    static let outCap = seg + stride                  // 217_854
    static let specSize = 4 * Models.Demucs.specBins * Models.Demucs.specFrames  // 917_504

    /// Soft clip with a tanh knee above 0.95, asymptotic to 1.0 (in Float it
    /// saturates at exactly 1.0 once tanh rounds up). The quantizer downstream
    /// would turn a 5.1 fold's ~2.4 full-scale peaks into a square wave without
    /// it. The `(a − 0.95)/0.05` and the `tanh` run in Double because Kotlin's
    /// untyped literals promote — reproduce that or bit-exactness against the
    /// Android reference is lost (spec-audio §3.10).
    static func softclip(_ x: Float) -> Float {
        let a = abs(x)
        if a <= 0.95 { return x }
        let knee = 0.95 + Float(tanh((Double(a) - 0.95) / 0.05)) * 0.05
        return x < 0 ? -knee : knee
    }

    // MARK: Configuration

    private let mean: Float
    private let std: Float
    private let infer: Infer
    private let musicScore: MusicScore?
    private let onChunk: (Int, Int) -> Void
    private let emit: (UnsafePointer<Float>, Int) throws -> Void
    private let totalChunks: Int
    private let stft: STFT

    // MARK: State

    private let inL: UnsafeMutablePointer<Float>    // inCap, ring, virtual-position addressed
    private let inR: UnsafeMutablePointer<Float>
    private var writePos = Demucs.maxShift
    /// One past the last real input position. `Int.max` while input is still
    /// arriving, resolved by `finish()` — the length is learned from the stream,
    /// so a decode that stops early yields a shorter output, not silence.
    private var endPos = Int.max

    private let outL: UnsafeMutablePointer<Float>   // outCap
    private let outR: UnsafeMutablePointer<Float>
    private let wsum: UnsafeMutablePointer<Float>
    private var flushPos = 0
    private var nextChunkOff = 0

    private let weight: UnsafeMutablePointer<Float>  // seg
    private let segL: UnsafeMutablePointer<Float>    // seg
    private let segR: UnsafeMutablePointer<Float>
    private let wav: UnsafeMutablePointer<Float>     // 2*seg, planar
    private let specIn: UnsafeMutablePointer<Float>  // specSize
    private let specSum: UnsafeMutablePointer<Float>
    private let timeSum: UnsafeMutablePointer<Float> // 2*seg, planar
    private let waveL: UnsafeMutablePointer<Float>   // seg
    private let waveR: UnsafeMutablePointer<Float>
    private let emitBuf: UnsafeMutablePointer<Float> // 2*stride, interleaved

    // Music gate: one denormalized mono chunk and the five live dilation scores.
    private let gateMono: UnsafeMutablePointer<Float>
    private var gateScores = [Float](repeating: 0, count: Demucs.gateRing)
    private var gateFrom = Int.max
    private var gateTo = -1

    /// Frames the stream actually delivered — the authoritative output length.
    private(set) var framesFed = 0
    /// Frames handed to `emit`. `emitted == framesFed` after `finish()`.
    private(set) var emitted = 0
    private(set) var chunksDone = 0
    private(set) var skippedChunks = 0
    /// Model samples that came back NaN or ±Inf and were replaced with silence.
    /// The fp32 artifact removes the old fp16 ceiling, but this guard still
    /// protects the encoder if a provider returns a non-finite activation.
    private(set) var nonFinite = 0

    private(set) var stftMs = 0.0
    private(set) var inferMs = 0.0
    private(set) var olaMs = 0.0
    private(set) var gateMs = 0.0

    /// - Parameters:
    ///   - estimatedFrames: progress denominator only. It never bounds the chunk
    ///     grid and never caps the output.
    init(mean: Float, std: Float, estimatedFrames: Int,
         infer: @escaping Infer,
         musicScore: MusicScore? = nil,
         onChunk: @escaping (Int, Int) -> Void = { _, _ in },
         emit: @escaping (UnsafePointer<Float>, Int) throws -> Void) {
        precondition(Self.stride < Self.seg && Self.seg <= 2 * Self.stride,
                     "ceil(seg/stride) must be 2: seg=\(Self.seg) stride=\(Self.stride)")
        self.mean = mean
        self.std = max(std, 1e-8)  // one scalar for both directions; a silent track would divide by zero
        self.infer = infer
        self.musicScore = musicScore
        self.onChunk = onChunk
        self.emit = emit
        totalChunks = (estimatedFrames + Self.maxShift + Self.stride - 1) / Self.stride
        stft = STFT(length: Self.seg)

        inL = .zeroed(Self.inCap)
        inR = .zeroed(Self.inCap)
        outL = .zeroed(Self.outCap)
        outR = .zeroed(Self.outCap)
        wsum = .zeroed(Self.outCap)
        segL = .zeroed(Self.seg)
        segR = .zeroed(Self.seg)
        wav = .zeroed(2 * Self.seg)
        specIn = .zeroed(Self.specSize)
        specSum = .zeroed(Self.specSize)
        timeSum = .zeroed(2 * Self.seg)
        waveL = .zeroed(Self.seg)
        waveR = .zeroed(Self.seg)
        emitBuf = .zeroed(2 * Self.stride)
        gateMono = .zeroed(Self.seg)

        // Triangle rising 1/57330 … 1.0 and back, replicating demucs.cpp's
        // transition window at TRANSITION_POWER = 1. Σg·x / Σg == x, so the
        // weights cancel exactly wherever the model is an identity.
        weight = .zeroed(Self.seg)
        for i in 0..<Self.seg {
            weight[i] = Float(min(i + 1, Self.seg - i)) / Float(Self.seg / 2)
        }
    }

    deinit {
        for p in [inL, inR, outL, outR, wsum, weight, segL, segR, wav,
                  specIn, specSum, timeSum, waveL, waveR, emitBuf, gateMono] { p.deallocate() }
    }

    /// Feed the next `frames` interleaved stereo samples. May synchronously run
    /// inference and emit.
    func feed(_ interleaved: UnsafePointer<Float>, frames: Int) throws {
        var a = 1 / std, b = -mean / std
        var src = 0, remaining = frames
        while remaining > 0 {
            // Slice cap + the fire condition below guarantee at most one chunk
            // per slice (stride > seg/2), which is what bounds `inCap`.
            let n = min(remaining, Self.seg / 2)
            var done = 0
            while done < n {
                let cell = (writePos + done) % Self.inCap
                let run = min(n - done, Self.inCap - cell)
                let head = interleaved + 2 * (src + done)
                vDSP_vsmsa(head, 2, &a, &b, inL + cell, 1, vDSP_Length(run))
                vDSP_vsmsa(head + 1, 2, &a, &b, inR + cell, 1, vDSP_Length(run))
                done += run
            }
            writePos += n
            src += n
            remaining -= n
            framesFed += n
            while nextChunkOff + Self.seg + Self.lookahead <= writePos { try processChunk() }
        }
    }

    /// Resolve the true length, process the remaining (short) chunks, flush the
    /// tail. After this `emitted == framesFed` by construction.
    func finish() throws {
        endPos = writePos
        if framesFed <= 0 { return }  // emit nothing rather than a chunk of silence
        while nextChunkOff < endPos { try processChunk() }
    }

    private func processChunk() throws {
        if try shouldSeparate(chunksDone) {
            try inferChunk(nextChunkOff)
        } else {
            skippedChunks += 1
            passthroughChunk(nextChunkOff)
        }
        nextChunkOff += Self.stride
        chunksDone += 1
        // Per chunk, not per stage: `JobRunner` samples the footprint only when
        // the stage changes, so separation — the single largest consumer in the
        // app — was a black box between `separate` and `mux`, and its 1629 MB
        // was only ever caught on the way out. One `task_info` per ~7.8 s of
        // audio is not instrumentation that changes what it measures.
        MemoryFootprint.note("separate.chunk")
        try flush(min(nextChunkOff, endPos))  // everything below the next chunk's start is final
        // max(): totalChunks is a container-duration estimate, so a track that
        // outruns it must not hand the caller done > total.
        onChunk(chunksDone, max(totalChunks, chunksDone))
    }

    /// Two-tier ±2 dilation from Android C1. Music within ±1 always wins;
    /// music at ±2 wins only when this chunk itself scores at least 0.02.
    private func shouldSeparate(_ chunk: Int) throws -> Bool {
        guard let musicScore else { return true }
        var i = max(gateTo + 1, chunk)
        while i <= chunk + Self.dilation {
            if gateFrom == Int.max { gateFrom = i }
            let started = ContinuousClock.now
            gateScores[i % gateScores.count] = try scoreChunk(i, using: musicScore)
            gateMs += msSince(started)
            gateTo = i
            i += 1
        }
        for k in (chunk - Self.dilation)...(chunk + Self.dilation) {
            if k < 0 { continue }
            if k < gateFrom { return true }
            if gateScores[k % gateScores.count] < MusicGate.threshold { continue }
            if (chunk - 1)...(chunk + 1) ~= k
                || gateScores[chunk % gateScores.count] >= Self.dilation2MinScore { return true }
        }
        return false
    }

    /// Denormalize the input-ring window, fold it to mono, and score only the
    /// real samples. A window wholly past the stream is silence.
    private func scoreChunk(_ chunk: Int, using score: MusicScore) throws -> Float {
        let off = chunk * Self.stride
        let n = max(0, min(Self.seg, writePos - off))
        guard n > 0 else { return 0 }
        for j in 0..<n {
            let cell = (off + j) % Self.inCap
            gateMono[j] = 0.5 * (inL[cell] + inR[cell]) * std + mean
        }
        return try score(gateMono, n)
    }

    /// A skipped chunk takes the normal overlap-add path, which reconstructs a
    /// skipped run exactly and crossfades at skipped/separated boundaries.
    private func passthroughChunk(_ off: Int) {
        let clen = min(Self.seg, endPos - off)
        let started = ContinuousClock.now
        for j in 0..<clen {
            let p = off + j
            let g = weight[j]
            let cell = p % Self.outCap
            if p < writePos {
                let src = p % Self.inCap
                outL[cell] += g * inL[src]
                outR[cell] += g * inR[src]
            }
            wsum[cell] += g
        }
        olaMs += msSince(started)
    }

    private func inferChunk(_ off: Int) throws {
        let clen = min(Self.seg, endPos - off)
        let delta = Self.seg - clen              // > 0 only for the tail chunks
        let readStart = off - delta / 2          // torch TensorChunk.padded: real left context
        let read = delta / 2                     // …read back center-trimmed

        var t = ContinuousClock.now
        gather(from: readStart)
        wav.update(from: segL, count: Self.seg)
        (wav + Self.seg).update(from: segR, count: Self.seg)
        stft.forward(segL, segR, into: specIn)
        stftMs += msSince(t); t = .now

        try infer(wav, specIn, specSum, timeSum)
        inferMs += msSince(t); t = .now

        // One iSTFT for the whole chunk: the kept stems' masked specs were
        // summed inside `infer`, and the transform is linear. Never one iSTFT
        // per stem (spec-audio §4.2).
        stft.inverse(specSum, into: waveL, waveR)

        var j = 0
        while j < clen {
            let cell = (off + j) % Self.outCap
            let n = vDSP_Length(min(clen - j, Self.outCap - cell))
            // out += weight * (spec branch + time branch), accumulated in place.
            vDSP_vma(weight + j, 1, waveL + read + j, 1, outL + cell, 1, outL + cell, 1, n)
            vDSP_vma(weight + j, 1, timeSum + read + j, 1, outL + cell, 1, outL + cell, 1, n)
            vDSP_vma(weight + j, 1, waveR + read + j, 1, outR + cell, 1, outR + cell, 1, n)
            vDSP_vma(weight + j, 1, timeSum + Self.seg + read + j, 1, outR + cell, 1, outR + cell, 1, n)
            vDSP_vadd(weight + j, 1, wsum + cell, 1, wsum + cell, 1, n)
            j += Int(n)
        }
        olaMs += msSince(t)
    }

    /// Copy `seg` samples starting at virtual position `start` out of the input
    /// ring. Positions before 0 or at/after `writePos` read as zero — that is
    /// `TensorChunk.padded`'s out-of-range rule, not a wrap.
    private func gather(from start: Int) {
        var j = 0
        while j < Self.seg {
            let p = start + j
            if p < 0 {
                let n = min(Self.seg - j, -p)
                vDSP_vclr(segL + j, 1, vDSP_Length(n))
                vDSP_vclr(segR + j, 1, vDSP_Length(n))
                j += n
            } else if p >= writePos {
                let n = Self.seg - j
                vDSP_vclr(segL + j, 1, vDSP_Length(n))
                vDSP_vclr(segR + j, 1, vDSP_Length(n))
                j += n
            } else {
                let cell = p % Self.inCap
                let n = min(min(Self.seg - j, writePos - p), Self.inCap - cell)
                (segL + j).update(from: inL + cell, count: n)
                (segR + j).update(from: inR + cell, count: n)
                j += n
            }
        }
    }

    /// Emit finalized virtual positions `[flushPos, limit) ∩ [maxShift, endPos)`,
    /// zeroing ring cells as they go. `limit` is always `min(nextChunkOff,
    /// endPos)`, so `n ≤ stride` and `emitBuf` never overflows.
    ///
    /// Order matters: divide by wsum → × std → + mean → soft clip → NaN guard.
    private func flush(_ limit: Int) throws {
        var n = 0
        while flushPos < limit {
            let cell = flushPos % Self.outCap
            if flushPos >= Self.maxShift {
                let w = wsum[cell]  // > 0: every emitted position is covered by ≥ 1 chunk
                emitBuf[2 * n] = finite(Self.softclip((outL[cell] / w) * std + mean))
                emitBuf[2 * n + 1] = finite(Self.softclip((outR[cell] / w) * std + mean))
                n += 1
            }
            outL[cell] = 0
            outR[cell] = 0
            wsum[cell] = 0
            flushPos += 1
        }
        if n > 0 {
            emitted += n
            try emit(emitBuf, n)
        }
    }

    private func finite(_ x: Float) -> Float {
        if x.isFinite { return x }
        nonFinite += 1
        return 0
    }
}

/// The ORT side of the driver: one `Run` per chunk with the kept stems summed on
/// the way out.
///
/// The two input tensors are built once over `NSMutableData` the driver keeps
/// writing into. Android measured that handing ORT a fresh buffer per chunk made
/// it allocate ~14 MB per call; the same churn here would be 4.6 MB of
/// non-movable allocation every 2.3 s for the whole job.
final class DemucsSession {
    private let model: OrtModel
    private let keep: [Int]
    private let wavData: NSMutableData
    private let specData: NSMutableData
    private let wavValue: ORTValue
    private let specValue: ORTValue

    /// - Parameter keepStems: drums and bass are never kept. Ascending order is
    ///   load-bearing — the buffer reads only ever seek forward.
    init(keepStems: [Models.Demucs.Stem]) throws {
        // The fp32 graph runs through CoreML's CPU+GPU MLProgram path on device.
        // Simulator policy normalizes that request to the CPU reference. Never
        // use XNNPACK here; its fp16 kernels corrupted the spectral branch.
        // `Ort.computeThreads` and not a local constant: the registry keys its
        // cache on the thread count, so a second value would mean a second
        // 1.3 GB session resident alongside the first.
        model = try ModelRegistry.model(Models.Demucs.file,
                                        compute: .coreMLGPU, threads: Ort.computeThreads)
        keep = keepStems.map(\.rawValue).sorted()
        precondition(!keep.isEmpty, "at least one stem must be kept")

        let seg = Models.Demucs.segmentFrames
        wavData = NSMutableData(length: 2 * seg * 4)!
        specData = NSMutableData(length: Demucs.specSize * 4)!
        wavValue = try ORTValue(tensorData: wavData, elementType: .float,
                                shape: [1, 2, seg].map(NSNumber.init(value:)))
        specValue = try ORTValue(tensorData: specData, elementType: .float,
                                 shape: [1, 4, Models.Demucs.specBins, Models.Demucs.specFrames]
                                     .map(NSNumber.init(value:)))
    }

    func run(_ wav: UnsafePointer<Float>, _ spec: UnsafePointer<Float>,
             _ specSum: UnsafeMutablePointer<Float>, _ timeSum: UnsafeMutablePointer<Float>) throws {
        let seg = Models.Demucs.segmentFrames
        wavData.mutableBytes.copyMemory(from: wav, byteCount: 2 * seg * 4)
        specData.mutableBytes.copyMemory(from: spec, byteCount: Demucs.specSize * 4)

        let outs = try model.run([Models.Demucs.waveInput: wavValue,
                                  Models.Demucs.specInput: specValue])
        // Matched by rank, not by name: rank 5 is the masked spec, rank 4 the
        // time branch. The shipped Kotlin never reads the output names.
        var specOut: ORTValue?, waveOut: ORTValue?
        for v in outs.values {
            switch v.shape.count {
            case 5: specOut = v
            case 4: waveOut = v
            default: break
            }
        }
        guard let specOut, let waveOut else { throw OrtError.outputMissing("htdemucs out_spec/out_wave") }

        // `tensorData` is a no-copy view of ORT's own buffer, so the kept stems
        // are summed straight out of it — 18.4 MB of stems never becomes a
        // Swift array.
        let sd = try specOut.tensorData(), td = try waveOut.tensorData()
        let sp = sd.bytes.assumingMemoryBound(to: Float.self)
        let tp = td.bytes.assumingMemoryBound(to: Float.self)
        for (k, stem) in keep.enumerated() {
            let s = sp + stem * Demucs.specSize, t = tp + stem * 2 * seg
            if k == 0 {
                specSum.update(from: s, count: Demucs.specSize)
                timeSum.update(from: t, count: 2 * seg)
            } else {
                vDSP_vadd(specSum, 1, s, 1, specSum, 1, vDSP_Length(Demucs.specSize))
                vDSP_vadd(timeSum, 1, t, 1, timeSum, 1, vDSP_Length(2 * seg))
            }
        }
    }
}
