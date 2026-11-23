import Accelerate
import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import ImageIO
import os

enum AnalyzeError: Error, CustomStringConvertible {
    case vImage(Int)
    case pool(CVReturn)
    case detectorUnusable(failed: Int, of: Int)

    var description: String {
        switch self {
        case .vImage(let e): "analyze: vImage scale failed (\(e))"
        case .pool(let e): "analyze: pixel-buffer pool failed (\(e))"
        case .detectorUnusable(let f, let n):
            "analyze: face detection failed on \(f) of \(n) sampled frames"
        }
    }
}

/// One sampled frame, handed to the analyze consumer.
///
/// **Buffer lifetime** (`spec-analyze.md` §1.5 — the rule a naive port breaks):
/// `detect` is a pool slot the sampler recycles as soon as `consume` returns.
/// Vision must be awaited *inside* that call; anything that outlives it copies.
struct SampledFrame: @unchecked Sendable {
    /// Absolute source milliseconds, `ptsUs / 1000` truncated exactly once.
    /// Every downstream time is this value or arithmetic on it.
    let ptsMs: Int64
    /// Detector input: decoder-native 4:2:0, long side scaled to
    /// `AnalyzeConstants.detectMaxDim`, and **unrotated** — Vision rotates
    /// internally off `orientation` for free, so rotating the pixels here would
    /// cost arithmetic for nothing (§10.4).
    let detect: CVPixelBuffer
    /// stored <-> upright for `detect`, not for the source. Every EDL `NRect` is
    /// normalised against `transform.uprightSize` and `minFacePx` is measured in
    /// it (§1.4), so the gender crop and the vote floor both live in this space.
    let transform: VideoTransform
    let orientation: CGImagePropertyOrientation
    /// `[3,224,224]` planar RGB, /255. Present on every `gateStride`-th emitted
    /// frame — 5 fps at the shipped 10/2.
    let gate: [Float]?
}

/// One sequential decode of the source, emitting upright-referenced frames at
/// `AnalyzeConstants.sampleFPS`.
///
/// The analyze pass is producer-bound — decode plus pixel convert, not
/// inference — so this is where the wall is. Two things keep it off the CPU:
/// the decoder's 4:2:0 buffers are never converted to RGB (Android measured
/// that detour at 38 % of the pass, §10.5), and the detector downscale is two
/// vImage calls on the two planes rather than a pixel loop.
final class FrameSampler: @unchecked Sendable {
    struct Stats: Sendable {
        var decoded = 0
        var emitted = 0
        var gated = 0
    }

    private let reader: TrackReader
    private let sourceTransform: CGAffineTransform
    private let gateEnabled: Bool
    private let gateStride: Int
    private let slotIntervalUs: Int64
    private let endUs: Int64
    private var nextSlotUs: Int64
    private var stats = Stats()

    /// Resolved on the first decoded frame, when the crop rect is known.
    private var pool: CVPixelBufferPool?
    private var detectTransform = VideoTransform.identity(size: .zero)
    private var orientation = CGImagePropertyOrientation.up
    private var rotation = 0
    /// Gate index maps over the **crop rect** — see `gateTensor`.
    private var gx: [Int] = []
    private var gy: [Int] = []

    /// Row scratch for the gate gather, allocated once for the pass the way
    /// Android's rings are (§8.2): one row of Y/U/V feeds the vectorised colour
    /// conversion without touching the allocator 224 times a frame.
    private let rowY = UnsafeMutablePointer<Int32>.allocate(capacity: Models.Nsfw.side)
    private let rowU = UnsafeMutablePointer<Int32>.allocate(capacity: Models.Nsfw.side)
    private let rowV = UnsafeMutablePointer<Int32>.allocate(capacity: Models.Nsfw.side)

    /// - Parameters:
    ///   - transform: the **source** transform; the detector-space one is
    ///     derived once the downscaled size is known.
    ///   - startMs/endMs: analyze window for the segmented route (§8.5). A
    ///     windowed pass anchors the sample grid to `startMs`, so segment N
    ///     samples the same timestamps after a resume.
    init(track: AVAssetTrack,
         transform: VideoTransform,
         fps: Double = AnalyzeConstants.sampleFPS,
         gateEvery: Int = AnalyzeConstants.gateStride,
         gateEnabled: Bool = true,
         startMs: Int64 = 0,
         endMs: Int64 = .max) throws {
        reader = try TrackReader.decodedVideo(track: track)
        sourceTransform = transform.toUpright
        self.gateEnabled = gateEnabled
        gateStride = max(1, gateEvery)
        slotIntervalUs = max(1, Int64(1_000_000 / fps))
        endUs = endMs == .max ? .max : endMs * 1000

        let windowed = startMs > 0 || endMs != .max
        nextSlotUs = windowed ? startMs * 1000 : .min
        if windowed {
            reader.reader.timeRange = CMTimeRange(
                start: CMTime(value: startMs, timescale: 1000),
                duration: endMs == .max ? .positiveInfinity
                                        : CMTime(value: endMs - startMs, timescale: 1000))
        }
    }

    deinit {
        rowY.deallocate()
        rowU.deallocate()
        rowV.deallocate()
    }

    /// Drives the decode. `consume` is awaited before the frame after next is
    /// handed over, which is the backpressure — nothing here buffers a file.
    func run(_ consume: (SampledFrame) async throws -> Void) async throws -> Stats {
        try reader.start()
        defer { reader.cancel() }

        // Producer/consumer inversion (§8.3): frame N+1 decodes and converts
        // while the consumer is still detecting on frame N, and a throw out of
        // `consume` cancels the decode instead of leaving it parked. Only one
        // producer task exists at a time, which is what keeps the decoder state
        // below single-threaded.
        var pending = Task { try self.produce() }
        while true {
            // The producer is unstructured, so it does not inherit this task's
            // cancellation — hand it over here and at every `consume` throw.
            if Task.isCancelled {
                pending.cancel()
                _ = try? await pending.value
                throw CancellationError()
            }
            guard let frame = try await pending.value else { break }
            pending = Task { try self.produce() }
            do {
                try await consume(frame)
            } catch {
                pending.cancel()
                _ = try? await pending.value
                throw error
            }
        }
        return stats
    }

    private func produce() throws -> SampledFrame? {
        while true {
            try Task.checkCancellation()
            guard let sb = reader.next() else {
                try reader.throwIfFailed()
                return nil
            }
            stats.decoded += 1

            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            guard pts.isNumeric else { continue }
            let ptsUs = pts.convertScale(1_000_000, method: .default).value
            // The grid anchors to the first decoded frame on a full pass.
            if nextSlotUs == .min { nextSlotUs = ptsUs }
            // Trust the sample's own pts, not EOS: decode order != display order.
            if ptsUs >= endUs { return nil }
            guard ptsUs >= nextSlotUs else { continue }
            // Advance before the image guard, and resync after a decode gap —
            // both exactly as Android (§1.2 step 9).
            nextSlotUs += slotIntervalUs
            if nextSlotUs <= ptsUs { nextSlotUs = ptsUs + slotIntervalUs }

            guard let src = CMSampleBufferGetImageBuffer(sb) else { continue }
            // Evaluated before the increment, so the gate fires on emitted
            // frames 0, 2, 4, … = exactly 5 fps at 10/2.
            let wantGate = gateEnabled && stats.emitted % gateStride == 0
            let frame = try convert(src, ptsMs: ptsUs / 1000, wantGate: wantGate)
            stats.emitted += 1
            if wantGate { stats.gated += 1 }
            return frame
        }
    }

    private func convert(_ src: CVPixelBuffer, ptsMs: Int64, wantGate: Bool) throws -> SampledFrame {
        // A CVPixelBuffer's width/height are already the clean aperture, so the
        // crop rect's origin is (0,0) and its extent is the buffer's own (§9.5).
        let cw = CVPixelBufferGetWidth(src), ch = CVPixelBufferGetHeight(src)
        if pool == nil { try prepare(cropW: cw, cropH: ch) }

        CVPixelBufferLockBaseAddress(src, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(src, .readOnly) }

        let detect = try allocate()
        try scale(src, into: detect)
        return SampledFrame(ptsMs: ptsMs,
                            detect: detect,
                            transform: detectTransform,
                            orientation: orientation,
                            gate: wantGate ? gateTensor(src) : nil)
    }

    /// Internal, not private, so `AnalyzeTests` can drive `gateTensor` over a
    /// synthetic source without a decoder — §10.2 requires that walk pinned
    /// bit-for-bit at all four rotations.
    func prepare(cropW: Int, cropH: Int) throws {
        // A rotation that is not a multiple of 90 degrades to 0 rather than
        // throwing — the detector rejects anything else (§1.2 step 1). The
        // *transform* has to degrade with it: `uprightSize` off a 45-degree
        // matrix is the diagonal bounding box, which would inflate `minFacePx`
        // and let the gender crop's edge clamp index past the detector buffer.
        let r = VideoTransform(preferredTransform: sourceTransform,
                               naturalSize: CGSize(width: cropW, height: cropH)).rotationDegrees
        let upright: CGAffineTransform = r % 90 == 0 ? sourceTransform : .identity
        rotation = r % 90 == 0 ? r : 0
        orientation = Self.orientation(rotation)

        let (dw, dh) = Self.displaySize(cropW: cropW, cropH: cropH, maxDim: AnalyzeConstants.detectMaxDim)
        detectTransform = VideoTransform(preferredTransform: upright,
                                         naturalSize: CGSize(width: dw, height: dh))

        if gateEnabled {
            let side = Models.Nsfw.side
            gx = (0..<side).map { $0 * cropW / side }
            gy = (0..<side).map { $0 * cropH / side }
        }

        var p: CVPixelBufferPool?
        let status = CVPixelBufferPoolCreate(
            nil,
            // Ring depth 4 > queue depth 2 + 1 (§0.6), so the decoder can never
            // recycle pixels a consumer is still reading. This is a floor on how
            // many slots the pool keeps warm, not a cap — with no
            // `AllocationThreshold` set, an over-deep queue would quietly grow
            // the pool rather than fail, so the depth invariant is enforced by
            // `run` holding exactly one frame ahead, not by this key.
            [kCVPixelBufferPoolMinimumBufferCountKey: 4] as CFDictionary,
            [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
             kCVPixelBufferWidthKey: dw,
             kCVPixelBufferHeightKey: dh,
             kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary,
            &p)
        guard status == kCVReturnSuccess, let p else { throw AnalyzeError.pool(status) }
        pool = p

        Log.analyze.info("""
            sampler \(cropW)x\(cropH) rot=\(self.rotation) -> detect \(dw)x\(dh) \
            upright=\(Int(self.detectTransform.uprightSize.width))x\(Int(self.detectTransform.uprightSize.height))
            """)
    }

    private func allocate() throws -> CVPixelBuffer {
        var buf: CVPixelBuffer?
        let status = CVPixelBufferPoolCreatePixelBuffer(nil, pool!, &buf)
        guard status == kCVReturnSuccess, let buf else { throw AnalyzeError.pool(status) }
        return buf
    }

    /// Downscale to the detector's 640 long side, plane for plane, in the
    /// decoder's own format. Detect cost scales with input area — Android
    /// measured ~8.6 ms/frame at 640 px and handing over native 1080p traded the
    /// whole saving for a slower detector (§10.4).
    private func scale(_ src: CVPixelBuffer, into dst: CVPixelBuffer) throws {
        CVBufferPropagateAttachments(src, dst)
        CVPixelBufferLockBaseAddress(dst, [])
        defer { CVPixelBufferUnlockBaseAddress(dst, []) }
        for plane in 0..<2 {
            var s = Self.buffer(src, plane)
            var d = Self.buffer(dst, plane)
            // Plane 1 is CbCr interleaved, so its width is in pairs and it needs
            // the CbCr variant — scaling it as Planar8 would blend Cb into Cr.
            let err = plane == 0
                ? vImageScale_Planar8(&s, &d, nil, vImage_Flags(kvImageNoFlags))
                : vImageScale_CbCr8(&s, &d, nil, vImage_Flags(kvImageNoFlags))
            guard err == kvImageNoError else { throw AnalyzeError.vImage(err) }
        }
    }

    private static func buffer(_ px: CVPixelBuffer, _ plane: Int) -> vImage_Buffer {
        vImage_Buffer(data: CVPixelBufferGetBaseAddressOfPlane(px, plane),
                      height: vImagePixelCount(CVPixelBufferGetHeightOfPlane(px, plane)),
                      width: vImagePixelCount(CVPixelBufferGetWidthOfPlane(px, plane)),
                      rowBytes: CVPixelBufferGetBytesPerRowOfPlane(px, plane))
    }

    // MARK: - Gate tensor

    /// The 224² gate tensor: a nearest-neighbour **stretch** of the whole crop
    /// rect, each axis with its own scale, gathered from the **source** planes.
    ///
    /// Building the maps over the already-downscaled detector buffer instead was
    /// tried on Android and measured 91.24 % censored-timeline recall against a
    /// >= 99.2 % bar — nearest-of-nearest lands on different source pixels and
    /// the chroma is subsampled at 640, so those pixels are simply gone and it
    /// cannot be tuned back (§10.1, §10.3). This walk re-reads full resolution
    /// deliberately.
    ///
    /// Internal rather than private so the §10.2 zero-delta test can compare it
    /// against a scalar transcription of §2.2 at all four rotations. The
    /// rotation fold and the colour conversion are both *rewritten* here (an
    /// affine map and a SIMD kernel), which is exactly where a transposition
    /// would hide.
    func gateTensor(_ src: CVPixelBuffer) -> [Float] {
        let side = Models.Nsfw.side
        let plane = side * side
        let yBase = CVPixelBufferGetBaseAddressOfPlane(src, 0)!.assumingMemoryBound(to: UInt8.self)
        let yRow = CVPixelBufferGetBytesPerRowOfPlane(src, 0)
        let cBase = CVPixelBufferGetBaseAddressOfPlane(src, 1)!.assumingMemoryBound(to: UInt8.self)
        let cRow = CVPixelBufferGetBytesPerRowOfPlane(src, 1)

        // §2.2's rotation table, folded into the walk as an affine map from the
        // upright output to the stored coordinate. dx and dy each depend on one
        // output axis only, so the row base is loop-invariant and the inner step
        // is two adds.
        let (xa, xb, xc, ya, yb, yc): (Int, Int, Int, Int, Int, Int) = switch rotation {
        case 90: (0, 1, 0, -1, 0, side - 1)
        case 180: (-1, 0, side - 1, 0, -1, side - 1)
        case 270: (0, -1, side - 1, 1, 0, 0)
        default: (1, 0, 0, 0, 1, 0)
        }

        return gx.withUnsafeBufferPointer { gxp in
            gy.withUnsafeBufferPointer { gyp in
                [Float](unsafeUninitializedCapacity: 3 * plane) { out, n in
                    n = 3 * plane
                    let r = out.baseAddress!, g = r + plane, b = g + plane
                    for oy in 0..<side {
                        var dx = xb * oy + xc
                        var dy = yb * oy + yc
                        for ox in 0..<side {
                            let sx = gxp[dx], sy = gyp[dy]
                            // 4:2:0 subsampling at SOURCE resolution.
                            let ci = (sy >> 1) * cRow + (sx >> 1) * 2
                            rowY[ox] = Int32(yBase[sy * yRow + sx])
                            rowU[ox] = Int32(cBase[ci]) - 128       // bi-planar: Cb first
                            rowV[ox] = Int32(cBase[ci + 1]) - 128   // then Cr
                            dx += xa
                            dy += ya
                        }
                        convertRow(r + oy * side, g + oy * side, b + oy * side)
                    }
                }
            }
        }
    }

    /// Integer BT.601 **full range**, arithmetic `>> 10`, clamped, then /255.
    /// The strictness table is QA-tuned against these exact numbers: `/1024`
    /// rounds the negative products toward zero where an arithmetic shift floors
    /// them, and that changes gate probabilities (§2.2). Sixteen pixels at a
    /// time; 224 is a whole multiple of 16, so there is no tail.
    private func convertRow(_ r: UnsafeMutablePointer<Float>,
                            _ g: UnsafeMutablePointer<Float>,
                            _ b: UnsafeMutablePointer<Float>) {
        let lo = SIMD16<Int32>(repeating: 0), hi = SIMD16<Int32>(repeating: 255)
        let scale = SIMD16<Float>(repeating: 255)
        var ox = 0
        while ox < Models.Nsfw.side {
            let y = Self.load(rowY, ox), u = Self.load(rowU, ox), v = Self.load(rowV, ox)
            let rr = pointwiseMin(pointwiseMax(y &+ ((1436 &* v) &>> 10), lo), hi)
            let gg = pointwiseMin(pointwiseMax(y &- (((352 &* u) &+ (731 &* v)) &>> 10), lo), hi)
            let bb = pointwiseMin(pointwiseMax(y &+ ((1815 &* u) &>> 10), lo), hi)
            Self.store(SIMD16<Float>(rr) / scale, r + ox)
            Self.store(SIMD16<Float>(gg) / scale, g + ox)
            Self.store(SIMD16<Float>(bb) / scale, b + ox)
            ox += 16
        }
    }

    @inline(__always)
    private static func load(_ p: UnsafeMutablePointer<Int32>, _ i: Int) -> SIMD16<Int32> {
        UnsafeRawPointer(p + i).loadUnaligned(as: SIMD16<Int32>.self)
    }

    @inline(__always)
    private static func store(_ v: SIMD16<Float>, _ p: UnsafeMutablePointer<Float>) {
        UnsafeMutableRawPointer(p).storeBytes(of: v, as: SIMD16<Float>.self)
    }

    // MARK: - Geometry

    /// Downscale only, never up; both axes rounded **down to even** with a floor
    /// of 2. Odd dimensions disagree with a 4:2:0 plane's own arithmetic (§10.6).
    static func displaySize(cropW: Int, cropH: Int, maxDim: Int) -> (w: Int, h: Int) {
        let longest = max(cropW, cropH)
        let scale = longest > maxDim ? Double(maxDim) / Double(longest) : 1
        return (max(2, Int((Double(cropW) * scale).rounded())) & ~1,
                max(2, Int((Double(cropH) * scale).rounded())) & ~1)
    }

    /// `rotationDegrees` means "rotate the stored buffer this far clockwise to
    /// display it upright", which is exactly what these four EXIF orientations
    /// say. Handing Vision unrotated pixels plus this preserves ML Kit's
    /// contract: upright boxes come back, so every EDL `NRect` keeps its space
    /// (§9.7).
    static func orientation(_ rotationDegrees: Int) -> CGImagePropertyOrientation {
        switch rotationDegrees {
        case 90: .right
        case 180: .down
        case 270: .left
        default: .up
        }
    }
}
