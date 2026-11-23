import CoreImage
import CoreImage.CIFilterBuiltins
import CoreVideo
import Foundation
import Metal
import os

/// Blur geometry for one frame size. σ is in **full-res pixels**, keyed on the
/// SHORT side referenced to 1080, so a rotated-portrait clip blurs identically
/// to its landscape twin (`spec-render.md` §1.2).
struct BlurPlan: Equatable, Sendable {
    let sigmaPx: Float
    /// Smallest of 1/2/4/8 that keeps σ at or under 4 low-res pixels. Blurring
    /// at `lowSize` and magnifying back is what makes a whole-frame blur nearly
    /// free — Android measured 0.20 % render delta for whole-frame vs regions.
    let downscale: Int
    let lowSize: CGSize
    let sigmaLow: Float
    /// Android's kernel half-width, capped at 10 taps per side. Recorded for
    /// parity checks, not applied: `CIGaussianBlur` picks its own support, so
    /// above σ_low 4 (blurAmount > 80 at 1080p) Apple's kernel is the wider,
    /// untruncated one and the blur is marginally softer at max amount.
    let radius: Int

    init(amount: Int, size: CGSize) {
        let shortSide = Float(min(size.width, size.height))
        // Float division throughout — integer-dividing collapses every amount
        // below 100 to zero.
        let sigma = max(0.1 as Float, Float(amount) / 100 * 40 * (shortSide / 1080))
        let d = [1, 2, 4, 8].first { sigma / Float($0) <= 4 } ?? 8
        sigmaPx = sigma
        downscale = d
        // Integer division then a floor of 1: 854/4 is 213, not 213.5, and the
        // truncation is visible in the texel step.
        lowSize = CGSize(width: max(1, Int(size.width) / d), height: max(1, Int(size.height) / d))
        let low = sigma / Float(d)
        sigmaLow = low
        radius = max(1, min(10, Int(ceil(2.5 * low))))
    }
}

/// The censor look: a whole-frame Gaussian blur, optionally greyed, composited
/// back either everywhere or only inside the EDL's rects.
///
/// The blur is deliberately geometry-blind — it runs over the whole frame
/// *before* any rect is considered. That is why whole-frame mode costs nothing
/// extra, and why a region's blur bleeds content in from outside it, which is
/// the shipped Android look and not a bug to fix.
///
/// Every stored property is immutable and `CIContext` is documented thread-safe,
/// so this rides into the writer's serial pump queue unchecked.
final class CensorEffect: @unchecked Sendable {
    let plan: BlurPlan
    let outputSize: CGSize
    /// False when the options add up to a visual no-op (no solid fill,
    /// `blurAmount == 0`, and no grayscale), which lets the render pass hand
    /// every frame to the encoder untouched instead of paying a Core Image
    /// round trip that changes nothing.
    let isActive: Bool

    private let ctx: CIContext
    private let transform: VideoTransform
    private let blurEnabled: Bool
    private let grayscale: Bool
    private let solidColor: CIColor?
    private let tonemap: Bool
    private let outputColorSpace: CGColorSpace?

    init(ops: FilterOps, transform: VideoTransform, tonemapHDR: Bool) {
        self.transform = transform
        self.outputSize = transform.storedSize
        self.plan = BlurPlan(amount: ops.blurAmount, size: transform.storedSize)
        self.blurEnabled = !ops.solidColor.isSolid && ops.blurAmount > 0
        self.grayscale = !ops.solidColor.isSolid && ops.grayscale
        self.solidColor = ops.solidColor.isSolid ? {
            let rgb = ops.solidColor.rgb
            return CIColor(red: rgb.red, green: rgb.green, blue: rgb.blue, alpha: 1)
        }() : nil
        self.isActive = ops.solidColor.isSolid || ops.blurAmount > 0 || ops.grayscale
        self.tonemap = tonemapHDR

        var opts: [CIContextOption: Any] = [
            .cacheIntermediates: false,
            // Names the context in Instruments' Core Image track.
            .name: "naqi-render",
            // The downscale is meant to alias exactly as Android's bilinear
            // point-sample does; a high-quality resampler would change the look
            // and cost more.
            .highQualityDownsample: false,
        ]
        if tonemapHDR {
            // Tone mapping is the one thing here that genuinely needs colour
            // management: half-float extended-linear working space in, SDR
            // BT.709 out.
            if let working = CGColorSpace(name: CGColorSpace.extendedLinearSRGB) {
                opts[.workingColorSpace] = working
            }
            opts[.workingFormat] = CIFormat.RGBAh
            // BT.709, not sRGB: `render()` tags the destination buffer
            // `kCVImageBufferTransferFunction_ITU_R_709_2`, and the two curves
            // disagree in the shadows, so rendering through the sRGB EOTF and
            // labelling it 709 mis-levels the bottom of an HDR tone-map's range
            // (`spec-avfoundation.md` §7.2, §4.4).
            outputColorSpace = CGColorSpace(name: CGColorSpace.itur_709)
        } else {
            // No colour management at all. Android's pipeline is electrical/sRGB
            // end to end with no linearisation anywhere (`spec-render.md` §1.5),
            // and skipping the conversion kernels is also the fastest path.
            opts[.workingColorSpace] = NSNull()
            outputColorSpace = nil
        }
        // From a *command queue*, not a device: `CIContext(mtlDevice:)` makes Core
        // Image spin up a queue of its own, which is the one thing the header
        // tells you to avoid (`CIContext.h:426`, `spec-avfoundation.md` §7.2).
        // One context per job — a CIContext caches compiled kernels.
        ctx = MTLCreateSystemDefaultDevice()
            .flatMap { $0.makeCommandQueue() }
            .map { CIContext(mtlCommandQueue: $0, options: opts) }
            ?? CIContext(options: opts)
    }

    /// True when this frame has to go through Core Image. An HDR source answers
    /// yes for *every* frame: a passed-through frame would stay HDR while its
    /// tone-mapped neighbours went SDR, and the track carries one transfer
    /// function for all of them.
    func needsRender(wholeFrame: Bool, regions: [NRect], masks: [BodyMask] = []) -> Bool {
        tonemap || (isActive && (wholeFrame || !regions.isEmpty || !masks.isEmpty))
    }

    /// Applies the censor to `src` and writes the result into `dst` (same size).
    /// `dst` comes from the writer's pixel-buffer pool, so this is the only copy
    /// on the render path.
    func render(_ src: CVPixelBuffer, to dst: CVPixelBuffer, wholeFrame: Bool, regions: [NRect],
                masks: [BodyMask] = []) {
        tag(dst, like: src)
        let input = CIImage(cvPixelBuffer: src)
        ctx.render(image(input, wholeFrame: wholeFrame, regions: regions, masks: masks),
                   to: dst, bounds: input.extent, colorSpace: outputColorSpace)
    }

    /// Writes a frame that is already a finished image — the end card —
    /// through the same context and with the same tags as the frames before
    /// it. `reference` is the last frame the video wrote.
    func render(_ image: CIImage, to dst: CVPixelBuffer, like reference: CVPixelBuffer?) {
        tag(dst, like: reference)
        ctx.render(image, to: dst, bounds: CGRect(origin: .zero, size: outputSize),
                   colorSpace: outputColorSpace)
    }

    private func tag(_ dst: CVPixelBuffer, like src: CVPixelBuffer?) {
        if tonemap {
            // The source's HLG/PQ tags must not ride along to an SDR output.
            CVBufferSetAttachment(dst, kCVImageBufferColorPrimariesKey,
                                  kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
            CVBufferSetAttachment(dst, kCVImageBufferTransferFunctionKey,
                                  kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
            CVBufferSetAttachment(dst, kCVImageBufferYCbCrMatrixKey,
                                  kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
        } else {
            // Carries the source's primaries/transfer/matrix onto the pooled
            // buffer so the RGB -> YCbCr write back uses the same matrix the
            // decode used.
            if let src { CVBufferPropagateAttachments(src, dst) }
        }
    }

    /// The filter graph for one frame. Public so tests can inspect it without a
    /// writer attached.
    func image(_ input: CIImage, wholeFrame: Bool, regions: [NRect], masks: [BodyMask] = []) -> CIImage {
        var source = input
        if tonemap {
            let f = CIFilter.toneMapHeadroom()
            f.inputImage = source
            f.targetHeadroom = 1
            source = f.outputImage ?? source
        }
        guard isActive, wholeFrame || !regions.isEmpty || !masks.isEmpty else { return source }

        var base: CIImage
        if let solidColor {
            base = CIImage(color: solidColor).cropped(to: source.extent)
        } else {
            base = blurEnabled ? blurred(source) : source
            if grayscale { base = greyed(base) }
        }
        if wholeFrame { return base }
        guard let mask = mask(for: regions, masks: masks, extent: source.extent) else { return source }

        let blend = CIFilter.blendWithMask()
        blend.inputImage = base
        blend.backgroundImage = source
        blend.maskImage = mask
        return (blend.outputImage ?? source).cropped(to: source.extent)
    }

    // MARK: - Blur

    private func blurred(_ image: CIImage) -> CIImage {
        let e = image.extent
        guard plan.downscale > 1 else {
            // clampedToExtent replicates the edge texel outward, which is
            // `GL_CLAMP_TO_EDGE` — without it the frame border darkens.
            return image.clampedToExtent()
                .applyingGaussianBlur(sigma: Double(plan.sigmaLow))
                .cropped(to: e)
        }
        // Scale by the *integer* scratch size rather than 1/d, so 854 at d=4
        // lands on 213 px exactly as Android's `inputWidth / d` does.
        let down = CGAffineTransform(scaleX: plan.lowSize.width / e.width,
                                     y: plan.lowSize.height / e.height)
        let low = image.transformed(by: down)
        let blurredLow = low.clampedToExtent()
            .applyingGaussianBlur(sigma: Double(plan.sigmaLow))
            .cropped(to: low.extent)
            // Forces the convolution to be evaluated once at `lowSize`. Without
            // it Core Image is free to inline the blur into the full-res
            // composite kernel and the downscale buys nothing.
            .insertingIntermediate()
        return blurredLow.transformed(by: down.inverted()).cropped(to: e)
    }

    /// BT.709 luma, applied to the already-blurred base — never before it.
    private func greyed(_ image: CIImage) -> CIImage {
        let f = CIFilter.colorMatrix()
        f.inputImage = image
        let luma = CIVector(x: 0.2126, y: 0.7152, z: 0.0722, w: 0)
        f.rVector = luma
        f.gVector = luma
        f.bVector = luma
        f.aVector = CIVector(x: 0, y: 0, z: 0, w: 1)
        f.biasVector = CIVector(x: 0, y: 0, z: 0, w: 0)
        return f.outputImage ?? image
    }

    // MARK: - Region mask

    /// Coverage mask: alpha 1 across every hard rect, smoothstep-ramping to 0
    /// over a feather that lives strictly *outside* it, so softening can never
    /// uncover a pixel the hard rect covered. Feather is 15 % of the rect's own
    /// size per axis with a floor of 0.002 of the frame (`spec-render.md` §2.5).
    /// Rects are unioned with `max()`, so overlapping faces do not double-darken.
    private func mask(for regions: [NRect], masks: [BodyMask] = [], extent: CGRect) -> CIImage? {
        var out: CIImage?
        for body in masks {
            guard let m = bodyMask(body, extent: extent) else { continue }
            guard let prev = out else { out = m; continue }
            let union = CIFilter.maximumCompositing()
            union.inputImage = m
            union.backgroundImage = prev
            out = union.outputImage ?? prev
        }
        for n in Self.limited(regions) {
            let r = ciRect(n, in: extent)
            let fx = max(r.width * 0.15, 0.002 * extent.width)
            let fy = max(r.height * 0.15, 0.002 * extent.height)
            var m = Self.ramp(from: CGPoint(x: r.minX - fx, y: 0), to: CGPoint(x: r.minX, y: 0))
            for edge in [Self.ramp(from: CGPoint(x: r.maxX + fx, y: 0), to: CGPoint(x: r.maxX, y: 0)),
                         Self.ramp(from: CGPoint(x: 0, y: r.minY - fy), to: CGPoint(x: 0, y: r.minY)),
                         Self.ramp(from: CGPoint(x: 0, y: r.maxY + fy), to: CGPoint(x: 0, y: r.maxY))] {
                let mul = CIFilter.multiplyCompositing()
                mul.inputImage = edge
                mul.backgroundImage = m
                m = mul.outputImage ?? m
            }
            guard let prev = out else { out = m; continue }
            let union = CIFilter.maximumCompositing()
            union.inputImage = m
            union.backgroundImage = prev
            out = union.outputImage ?? prev
        }
        // Gradients are infinite; bounding the mask keeps the blend's ROI finite.
        return out?.cropped(to: extent)
    }

    /// A body mask grown by one cell, plus a one-cell empty border, so the
    /// bilinear edge ramps outward from the model's outline and never inside it.
    /// Mapped upright cell space -> upright pixels -> stored -> Core Image y-up.
    private func bodyMask(_ body: BodyMask, extent: CGRect) -> CIImage? {
        guard body.isValid else { return nil }
        let w = body.width + 2, h = body.height + 2
        var bytes = [UInt8](repeating: 0, count: w * h)
        for y in 0..<body.height {
            for x in 0..<body.width where body[x, y] {
                for dy in 0...2 { for dx in 0...2 { bytes[(y + dy) * w + x + dx] = 255 } }
            }
        }
        let r = body.rect.rect(in: transform.uprightSize)
        let cx = r.width / CGFloat(body.width), cy = r.height / CGFloat(body.height)
        // Bitmap rows run top-down; Core Image puts row 0 at the top as well.
        let toUpright = CGAffineTransform(a: cx, b: 0, c: 0, d: -cy, tx: r.minX - cx, ty: r.maxY + cy)
        let toCore = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: extent.height)
        let image = CIImage(bitmapData: Data(bytes), bytesPerRow: w, size: CGSize(width: w, height: h),
                            format: .L8, colorSpace: nil).samplingLinear()
        return image.transformed(by: toUpright.concatenating(transform.toUpright.inverted()).concatenating(toCore))
            .cropped(to: extent)
    }

    /// `smoothstep(a, b, ·)` as an alpha ramp along the a→b axis: clear at `a`,
    /// opaque at `b`, clamped outside. `CISmoothLinearGradient` interpolates with
    /// GLSL's own `t·t·(3−2t)`, so this is the shader's mask term exactly — and
    /// it stays on the GPU, with no per-frame CPU mask buffer.
    private static func ramp(from a: CGPoint, to b: CGPoint) -> CIImage {
        let g = CIFilter.smoothLinearGradient()
        g.point0 = a
        g.color0 = .clear
        g.point1 = b
        g.color1 = .white
        return g.outputImage ?? CIImage.empty()
    }

    /// At most 8 rects per frame, largest first. The EDL promotes busier
    /// instants to whole-frame upstream so this should never fire; when it does
    /// it fails open exactly as Android's shader did, dropping the smallest
    /// faces rather than the composite.
    private static func limited(_ r: [NRect]) -> [NRect] {
        let live = r.filter { !$0.isEmpty }
        guard live.count > Edl.maxRegionsPerFrame else { return live }
        // Never silently — Android warns on every overflowing frame
        // (`CensorEffect.kt:183`) because reaching here means the EDL's
        // whole-frame promotion missed an instant, which is an analyze-pass bug.
        Log.render.warning("""
            region overflow: \(live.count, privacy: .public) regions on one frame, \
            keeping the \(Edl.maxRegionsPerFrame, privacy: .public) largest
            """)
        return Array(live.sorted { $0.width * $0.height > $1.width * $1.height }
            .prefix(Edl.maxRegionsPerFrame))
    }

    /// EDL rect (upright, normalised, y-down) → Core Image rect in the stored
    /// buffer. Two flips happen here and nowhere else: `preferredTransform`
    /// turns upright into stored, then y is inverted because CoreVideo counts
    /// rows from the top and Core Image counts them from the bottom. Getting
    /// either backwards blurs the wrong corner of a rot-90 clip.
    private func ciRect(_ n: NRect, in extent: CGRect) -> CGRect {
        let stored = transform.storedRect(fromUpright: n.rect(in: transform.uprightSize)).standardized
        return CGRect(x: stored.minX, y: extent.height - stored.maxY,
                      width: stored.width, height: stored.height)
    }
}
