import Accelerate
import Foundation

/// demucs' out-of-graph STFT / iSTFT: `torch.stft(center=True, normalized=True,
/// pad_mode="reflect")` with a periodic Hann window, wrapped in demucs' own
/// `_spec` pad-and-slice. The htdemucs export deliberately leaves both
/// transforms outside the graph, so these have to reproduce torch to ~1e-4 or
/// the model is fed a spectrogram it was never trained on (spec-audio §2).
///
/// Everything structural is double precision. The Kotlin reference refuses an
/// all-f32 FFT with numbers: f32 twiddles land ~1e-4 relative error against
/// ~1e-7, and four of its golden assertions sit exactly at 1e-4. Accelerate's
/// single-precision `vDSP_fft_zrip` would land on that same floor, so this uses
/// the `…D` entry points. At 224 forward + 224 inverse transforms per 2.6 s
/// chunk that costs a few ms against ~1 s of inference.
///
/// One instance is bound to one `(nfft, hop, length)` and owns all of its
/// scratch — production drives a single fixed length, so nothing is ever
/// reallocated mid-job. Thread-confined: one separator drives one instance.
final class STFT {
    let nfft: Int
    let hop: Int
    /// Input length, T.
    let length: Int
    /// Bins kept, `nfft/2`. The Nyquist row is dropped — model contract.
    let bins: Int
    /// Frames kept, `ceil(T/hop)`.
    let frames: Int

    private let log2n: vDSP_Length
    private let setup: FFTSetupD
    private let padL: Int
    private let paddedSeg: Int
    private let trimOffset: Int
    private let sigLen: Int

    private let win: UnsafeMutablePointer<Double>     // nfft
    private let envInv: UnsafeMutablePointer<Double>  // sigLen, 1/(Σw² + 1e-8)
    private let sigA: UnsafeMutablePointer<Double>    // paddedSeg
    private let sigB: UnsafeMutablePointer<Double>    // sigLen
    private let frame: UnsafeMutablePointer<Double>   // nfft
    private let ola: UnsafeMutablePointer<Double>     // sigLen
    private let re: UnsafeMutablePointer<Double>      // bins
    private let im: UnsafeMutablePointer<Double>      // bins
    private let binBuf: UnsafeMutablePointer<Double>  // bins

    init(nfft: Int = Models.Demucs.nFFT, hop: Int = Models.Demucs.hop, length T: Int) {
        precondition(nfft.nonzeroBitCount == 1 && nfft >= 8, "nfft must be a power of two ≥ 8")
        self.nfft = nfft
        self.hop = hop
        length = T
        bins = nfft / 2
        frames = (T + hop - 1) / hop
        log2n = vDSP_Length(nfft.trailingZeroBitCount)
        setup = vDSP_create_fftsetupD(log2n, FFTRadix(kFFTRadix2))!

        padL = hop / 2 * 3
        paddedSeg = T + padL + (padL + frames * hop - T)  // = (frames + 3) * hop
        trimOffset = nfft / 2 + padL
        sigLen = paddedSeg + nfft
        // Torch's reflect pad needs `srcLen > max(l, r)` on both stages, and
        // stage A's right pad is `padL + frames*hop − T`, up to `2.5*hop − 1`.
        // `T > 1.5*hop` is NOT enough: at nfft 4096 / hop 1024, T = 2049 gives
        // padR = 2559 and the mirror reads src[-511]. Guard the real bound.
        precondition(T > max(padL, paddedSeg - padL - T) && paddedSeg > nfft / 2,
                     "T=\(T) too short for the reflect pads at nfft=\(nfft) hop=\(hop)")

        win = .zeroed(nfft)
        for n in 0..<nfft { win[n] = 0.5 * (1 - cos(2 * .pi * Double(n) / Double(nfft))) }

        sigA = .zeroed(paddedSeg)
        sigB = .zeroed(sigLen)
        ola = .zeroed(sigLen)
        frame = .zeroed(nfft)
        re = .zeroed(bins)
        im = .zeroed(bins)
        binBuf = .zeroed(bins)

        // The window sum-of-squares runs over ALL `frames + 4` frames, including
        // the four boundary frames that are never transformed — they contribute
        // nothing to the overlap-add but everything to the envelope, and
        // computing it over the transformed frames only is wrong (spec §2.3.3).
        envInv = .zeroed(sigLen)
        for f in 0...(paddedSeg / hop) {
            let s = f * hop
            for i in 0..<nfft { envInv[s + i] += win[i] * win[i] }
        }
        for i in 0..<sigLen { envInv[i] = 1 / (envInv[i] + 1e-8) }
    }

    deinit {
        vDSP_destroy_fftsetupD(setup)
        for p in [win, envInv, sigA, sigB, frame, ola, re, im, binBuf] { p.deallocate() }
    }

    /// Planar `ch0`/`ch1` (each `length` samples) → CaC spec, flattened C-order
    /// `[4][bins][frames]`: channel-major, real plane before imag plane, bin
    /// major and frame minor. Getting that axis order transposed is the single
    /// most likely porting bug (spec §2.2).
    func forward(_ ch0: UnsafePointer<Float>, _ ch1: UnsafePointer<Float>,
                 into cac: UnsafeMutablePointer<Float>) {
        forward(ch0, plane: 0, into: cac)
        forward(ch1, plane: 2, into: cac)
    }

    /// Summed masked CaC spec → planar waveform, `length` samples per channel.
    func inverse(_ cac: UnsafePointer<Float>,
                 into out0: UnsafeMutablePointer<Float>, _ out1: UnsafeMutablePointer<Float>) {
        inverse(cac, plane: 0, into: out0)
        inverse(cac, plane: 2, into: out1)
    }

    private func forward(_ src: UnsafePointer<Float>, plane: Int, into cac: UnsafeMutablePointer<Float>) {
        reflectPad(src, length, padL, paddedSeg - length - padL, into: sigA)  // demucs' _spec re-pad
        reflectPad(sigA, paddedSeg, nfft / 2, nfft / 2, into: sigB)           // torch.stft(center: true)

        var split = DSPDoubleSplitComplex(realp: re, imagp: im)
        let reBase = plane * bins * frames
        let imBase = (plane + 1) * bins * frames
        // vDSP's real forward FFT returns twice the mathematical DFT, so torch's
        // 1/sqrt(nfft) normalization carries an extra 0.5.
        var scale = 0.5 / Double(nfft).squareRoot()
        let n = vDSP_Length(nfft), nb = vDSP_Length(bins), stride = vDSP_Stride(frames)

        for f in 2..<(2 + frames) {
            vDSP_vmulD(sigB + f * hop, 1, win, 1, frame, 1, n)
            frame.withMemoryRebound(to: DSPDoubleComplex.self, capacity: bins) {
                vDSP_ctozD($0, 2, &split, 1, nb)
            }
            vDSP_fft_zripD(setup, &split, 1, log2n, FFTDirection(kFFTDirection_Forward))
            let t = f - 2
            vDSP_vsmulD(re, 1, &scale, binBuf, 1, nb)
            vDSP_vdpsp(binBuf, 1, cac + reBase + t, stride, nb)
            vDSP_vsmulD(im, 1, &scale, binBuf, 1, nb)
            vDSP_vdpsp(binBuf, 1, cac + imBase + t, stride, nb)
            // imagp[0] is Re(Nyquist) in vDSP's packed real format, and Nyquist
            // is dropped; Im(DC) of a real signal is zero.
            cac[imBase + t] = 0
        }
    }

    private func inverse(_ cac: UnsafePointer<Float>, plane: Int, into out: UnsafeMutablePointer<Float>) {
        vDSP_vclrD(ola, 1, vDSP_Length(sigLen))
        var split = DSPDoubleSplitComplex(realp: re, imagp: im)
        let reBase = plane * bins * frames
        let imBase = (plane + 1) * bins * frames
        // sqrt(nfft) un-normalize, folded with the 1/nfft that vDSP's inverse
        // does not apply. Exactly one 1/N in the whole pipeline (spec §2.3.4).
        var scale = 1 / Double(nfft).squareRoot()
        let n = vDSP_Length(nfft), nb = vDSP_Length(bins), stride = vDSP_Stride(frames)

        for f in 2..<(2 + frames) {
            let t = f - 2
            vDSP_vspdp(cac + reBase + t, stride, re, 1, nb)
            vDSP_vspdp(cac + imBase + t, stride, im, 1, nb)
            vDSP_vsmulD(re, 1, &scale, re, 1, nb)
            vDSP_vsmulD(im, 1, &scale, im, 1, nb)
            // Nyquist stays zero. Im(DC) has no slot in the packed format, i.e.
            // it is discarded — which is exactly what numpy's irfft does too.
            im[0] = 0
            vDSP_fft_zripD(setup, &split, 1, log2n, FFTDirection(kFFTDirection_Inverse))
            frame.withMemoryRebound(to: DSPDoubleComplex.self, capacity: bins) {
                vDSP_ztocD(&split, 1, $0, 2, nb)
            }
            vDSP_vmaD(frame, 1, win, 1, ola + f * hop, 1, ola + f * hop, 1, n)
        }
        // sigA is dead by here (the forward's stage-A buffer) and is the only
        // double scratch long enough to hold the trimmed, normalized output.
        vDSP_vmulD(ola + trimOffset, 1, envInv + trimOffset, 1, sigA, 1, vDSP_Length(length))
        vDSP_vdpsp(sigA, 1, out, 1, vDSP_Length(length))
    }

    // Torch reflect pad — mirror EXCLUDING the edge sample, not the demucs.onnx
    // C++ off-by-one (spec §2.1). Precondition `n > max(l, r)`, which the
    // constructor checks, so torch's short-signal guard never applies.
    private func reflectPad(_ src: UnsafePointer<Float>, _ n: Int, _ l: Int, _ r: Int,
                            into dst: UnsafeMutablePointer<Double>) {
        vDSP_vspdp(src, 1, dst + l, 1, vDSP_Length(n))
        for j in 0..<l { dst[j] = Double(src[l - j]) }
        for k in 0..<r { dst[l + n + k] = Double(src[n - 2 - k]) }
    }

    private func reflectPad(_ src: UnsafePointer<Double>, _ n: Int, _ l: Int, _ r: Int,
                            into dst: UnsafeMutablePointer<Double>) {
        dst.advanced(by: l).update(from: src, count: n)
        for j in 0..<l { dst[j] = src[l - j] }
        for k in 0..<r { dst[l + n + k] = src[n - 2 - k] }
    }
}

extension UnsafeMutablePointer where Pointee: ExpressibleByIntegerLiteral {
    /// Zero-initialized scratch. The audio stage allocates every buffer it will
    /// ever need up front; nothing here is resized mid-job.
    static func zeroed(_ count: Int) -> UnsafeMutablePointer<Pointee> {
        let p = UnsafeMutablePointer<Pointee>.allocate(capacity: count)
        p.initialize(repeating: 0, count: count)
        return p
    }
}
