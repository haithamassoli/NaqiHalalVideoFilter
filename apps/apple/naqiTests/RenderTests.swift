import Testing
import AVFoundation
import CoreImage
import CoreVideo
import CryptoKit
import Foundation
@testable import naqi

/// M4 exit criteria. Three things can silently ruin the render pass and none of
/// them show up as a crash: a sigma that drifts from the Android table, a
/// grayscale matrix in the wrong colour space, and a rect mapped into the wrong
/// corner on a rotated source. All three are pinned here against real output
/// pixels, not against the code that produced them.
@Suite("Render", .serialized)
struct RenderTests {

    // MARK: - Sigma mapping

    /// `(width, height, blurAmount, sigmaPx, downscale, lowW, lowH, sigmaLow, radius)`
    /// straight off `spec-render.md` §1.2's worked table.
    static let sigmaCases: [(Int, Int, Int, Float, Int, Int, Int, Float, Int)] = [
        (1920, 1080,   0,  0.1,      1, 1920, 1080, 0.1,      1),
        // The four rows of the spec table that sit exactly ON the ladder's
        // `sigma / d <= 4` boundary. Each one picks the SMALLER d only because
        // the comparison is inclusive; a `<` would push all four onto the next
        // rung and halve the scratch, so these are the rows that catch it.
        (1920, 1080,  10,  4.0,      1, 1920, 1080, 4.0,     10),
        (1920, 1080,  20,  8.0,      2,  960,  540, 4.0,     10),
        (1920, 1080,  40, 16.0,      4,  480,  270, 4.0,     10),
        (1920, 1080,  80, 32.0,      8,  240,  135, 4.0,     10),
        (1920, 1080,  50, 20.0,      8,  240,  135, 2.5,      7),
        (1920, 1080, 100, 40.0,      8,  240,  135, 5.0,     10),
        (1920, 1080,  60, 24.0,      8,  240,  135, 3.0,      8),
        (1280,  720,   0,  0.1,      1, 1280,  720, 0.1,      1),
        (1280,  720,  50, 13.333333, 4,  320,  180, 3.333333, 9),
        (1280,  720, 100, 26.666666, 8,  160,   90, 3.333333, 9),
        (1280,  720,  60, 16.0,      4,  320,  180, 4.0,     10),
        // 854/4 truncates to 213, not 213.5 — the integer division is observable.
        ( 854,  480,  60, 10.666667, 4,  213,  120, 2.666667, 7),
        (3840, 2160,  60, 48.0,      8,  480,  270, 6.0,     10),
    ]

    @Test("blur amount maps to the Android sigma table", arguments: sigmaCases.indices)
    func sigmaTable(i: Int) {
        let (w, h, amount, sigma, d, lowW, lowH, sigmaLow, radius) = Self.sigmaCases[i]
        let p = BlurPlan(amount: amount, size: CGSize(width: w, height: h))
        let tag = "\(w)x\(h)@\(amount)"
        #expect(abs(p.sigmaPx - sigma) < 1e-4, "\(tag) sigmaPx \(p.sigmaPx)")
        #expect(p.downscale == d, "\(tag) downscale \(p.downscale)")
        #expect(p.lowSize == CGSize(width: lowW, height: lowH), "\(tag) lowSize \(p.lowSize)")
        #expect(abs(p.sigmaLow - sigmaLow) < 1e-4, "\(tag) sigmaLow \(p.sigmaLow)")
        #expect(p.radius == radius, "\(tag) radius \(p.radius)")
    }

    /// Keyed on the short side, so a portrait clip and its landscape twin blur
    /// by the same number of pixels. Only the scratch size swaps.
    @Test("sigma is orientation-invariant")
    func sigmaOrientation() {
        let land = BlurPlan(amount: 60, size: CGSize(width: 1920, height: 1080))
        let port = BlurPlan(amount: 60, size: CGSize(width: 1080, height: 1920))
        #expect(land.sigmaPx == port.sigmaPx)
        #expect(land.downscale == port.downscale)
        #expect(port.lowSize == CGSize(width: 135, height: 240))
    }

    // MARK: - Grayscale coefficients

    /// BT.709 on real output pixels. If Core Image were linearising on the way
    /// in, pure red would come out around 137 instead of 54 — this is the test
    /// that pins the working colour space, not just the matrix.
    @Test("grayscale uses BT.709 luma on the electrical values")
    func grayscaleCoefficients() throws {
        let colours: [(UInt8, UInt8, UInt8)] = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (200, 100, 50)]
        let w = 4 * 64, h = 64
        let src = try Self.bgra(w, h) { x, _ in colours[x / 64] }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }

        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        let effect = CensorEffect(ops: ops,
                                  transform: .identity(size: CGSize(width: w, height: h)),
                                  tonemapHDR: false)
        effect.render(src, to: dst, wholeFrame: true, regions: [])

        for (i, c) in colours.enumerated() {
            let luma = 0.2126 * Double(c.0) + 0.7152 * Double(c.1) + 0.0722 * Double(c.2)
            let got = Self.pixel(dst, i * 64 + 32, 32)
            for ch in [got.r, got.g, got.b] {
                #expect(abs(Double(ch) - luma) <= 2,
                        "rgb\(c): expected \(Int(luma.rounded())) got \(got)")
            }
        }
    }

    // MARK: - Whole frame

    @Test("whole-frame mode replaces every pixel")
    func wholeFrame() throws {
        let w = 320, h = 240
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: true, regions: [])

        // 0.2126*200 + 0.7152*40 + 0.0722*90 = 77.6
        for y in stride(from: 2, to: h, by: 17) {
            for x in stride(from: 2, to: w, by: 19) {
                let p = Self.pixel(dst, x, y)
                #expect(abs(p.r - 78) <= 2 && p.r == p.g && p.g == p.b,
                        "pixel \(x),\(y) is \(p), not greyed")
            }
        }
    }

    /// Whole-frame beats regions, absolutely (`spec-render.md` §5.4). The EDL
    /// already returns no regions inside a full-frame span, so passing some in
    /// here is the case the renderer must not be able to get wrong: a shader
    /// that unioned the whole-frame seed with the region mask instead of
    /// short-circuiting would leave the area *outside* the rect uncensored.
    @Test("whole-frame ignores any regions handed to it")
    func wholeFrameSuppressesRegions() throws {
        let w = 320, h = 240
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: true,
                    regions: [NRect(left: 0.6, top: 0.6, right: 0.7, bottom: 0.7)])
        // The far corner is nowhere near the rect or its feather.
        for (x, y) in [(2, 2), (w - 3, 2), (2, h - 3), (w / 2, h / 2)] {
            let p = Self.pixel(dst, x, y)
            #expect(abs(p.r - 78) <= 2 && p.r == p.g && p.g == p.b,
                    "pixel \(x),\(y) is \(p) — the region mask leaked into whole-frame mode")
        }
    }

    /// Guards the downscale → blur → upscale path itself: without it a bug that
    /// returned the source unblurred would still pass every mask test, because
    /// grayscale is what those assert on.
    @Test("blur actually softens a hard edge")
    func blurSoftensEdge() throws {
        let w = 320, h = 240
        let src = try Self.bgra(w, h) { x, _ in x < w / 2 ? (0, 0, 0) : (255, 255, 255) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 100
        ops.grayscale = false
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: true, regions: [])
        // sigma at short side 240 is 100/100*40*(240/1080) = 8.9 px, so the seam
        // is a wide ramp and both sides of it are mid-grey.
        let left = Self.pixel(dst, w / 2 - 2, h / 2).r
        let right = Self.pixel(dst, w / 2 + 2, h / 2).r
        #expect(left > 60 && left < 190, "left of the seam is \(left), not blurred")
        #expect(right > 60 && right < 190, "right of the seam is \(right), not blurred")
        // Far from the seam the frame is still black and white.
        #expect(Self.pixel(dst, 4, h / 2).r < 12)
        #expect(Self.pixel(dst, w - 5, h / 2).r > 243)
    }

    @Test("solid fill replaces blur and grayscale")
    func solidFill() throws {
        let w = 320, h = 240
        let src = try Self.bgra(w, h) { x, _ in x < w / 2 ? (0, 0, 0) : (255, 255, 255) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 100
        ops.grayscale = true
        ops.solidColor = .navy
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: true, regions: [])

        for (x, y) in [(0, 0), (w / 2, h / 2), (w - 1, h - 1)] {
            let p = Self.pixel(dst, x, y)
            #expect(abs(p.r - 44) <= 2 && abs(p.g - 62) <= 2 && abs(p.b - 80) <= 2,
                    "solid pixel \(x),\(y) is \(p), not navy")
        }
    }

    // MARK: - Region mapping, both rotations

    /// A 640x360 stored buffer with the SAME upright rect under two transforms.
    /// `censored` is a buffer pixel that must change; `clean` are pixels that
    /// must not — and each case's `clean` list deliberately contains the other
    /// case's `censored` point, so an identity mapping cannot pass both.
    /// The four `preferredTransform` values iOS capture actually writes, from
    /// `spec-avfoundation.md` §5.2 with `naturalSize = 640x360`. Each rotation
    /// sends the same upright rect to a different stored corner, and every
    /// case's `clean` list is the other three cases' `censored` points, so no
    /// single mapping — identity, a lone y-flip, or a transposed 90/270 pair —
    /// can pass more than one row.
    static let regionCases: [(name: String, t: CGAffineTransform,
                              censored: (Int, Int), clean: [(Int, Int)])] = [
        ("rot-0", .identity, (160, 45), [(500, 300), (80, 270), (480, 315), (560, 90)]),
        ("rot-90", CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 360, ty: 0),
         (80, 270), [(500, 50), (160, 45), (480, 315), (560, 90)]),
        ("rot-180", CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 640, ty: 360),
         (480, 315), [(160, 45), (80, 270), (560, 90)]),
        ("rot-270", CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 640),
         (560, 90), [(160, 45), (80, 270), (480, 315)]),
    ]

    @Test("a body mask censors only its grown cells, in every rotation", arguments: regionCases.indices)
    func bodyMaskMapping(i: Int) throws {
        let c = Self.regionCases[i]
        let w = 640, h = 360
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        let transform = VideoTransform(preferredTransform: c.t, naturalSize: CGSize(width: w, height: h))
        let effect = CensorEffect(ops: ops, transform: transform, tonemapHDR: false)
        // Upright 8x8 grid over the frame with only the top-left cell set; the
        // one-cell growth covers 0..0.25 on each axis and ramps out by 0.3125.
        let mask = BodyMask(rect: NRect(left: 0, top: 0, right: 1, bottom: 1), width: 8, height: 8,
                            bits: Data([1] + Array(repeating: 0, count: 7)))
        effect.render(src, to: dst, wholeFrame: false, regions: [], masks: [mask])
        func stored(_ u: CGFloat, _ v: CGFloat) -> (Int, Int) {
            let p = CGPoint(x: u * transform.uprightSize.width, y: v * transform.uprightSize.height)
                .applying(transform.toUpright.inverted())
            return (min(w - 1, Int(p.x)), min(h - 1, Int(p.y)))
        }
        let inside = Self.pixel(dst, stored(0.08, 0.08).0, stored(0.08, 0.08).1)
        #expect(inside.r == inside.g && inside.g == inside.b, "\(c.name): top-left should be greyed, is \(inside)")
        for (u, v) in [(0.8, 0.8), (0.8, 0.1), (0.1, 0.8), (0.5, 0.5)] as [(CGFloat, CGFloat)] {
            let got = Self.pixel(dst, stored(u, v).0, stored(u, v).1)
            #expect(got == (r: 200, g: 40, b: 90), "\(c.name): (\(u), \(v)) should be untouched, is \(got)")
        }
    }

    @Test("region censor lands in the right corner", arguments: regionCases.indices)
    func regionMapping(i: Int) throws {
        let c = Self.regionCases[i]
        let w = 640, h = 360
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }

        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        let effect = CensorEffect(
            ops: ops,
            transform: VideoTransform(preferredTransform: c.t, naturalSize: CGSize(width: w, height: h)),
            tonemapHDR: false)
        // Upright top-left quadrant-ish. Stored: rot-0 x 0..320 / y 0..90,
        // rot-90 x 0..160 / y 180..360, rot-180 x 320..640 / y 270..360,
        // rot-270 x 480..640 / y 0..180 — `spec-render.md` §2.3's table.
        let region = NRect(left: 0, top: 0, right: 0.5, bottom: 0.25)
        effect.render(src, to: dst, wholeFrame: false, regions: [region])

        let inside = Self.pixel(dst, c.censored.0, c.censored.1)
        #expect(abs(inside.r - 78) <= 2 && inside.r == inside.g && inside.g == inside.b,
                "\(c.name): \(c.censored) should be greyed, is \(inside)")
        for p in c.clean {
            let got = Self.pixel(dst, p.0, p.1)
            #expect(got == (r: 200, g: 40, b: 90),
                    "\(c.name): \(p) should be untouched, is \(got)")
        }
    }

    /// The feather is strictly outward, so the hard rect is fully covered right
    /// up to its own edge and the ramp lives outside it.
    @Test("feather ramps outward only")
    func featherIsOutward() throws {
        let w = 640, h = 360
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: false,
                    regions: [NRect(left: 0.25, top: 0.25, right: 0.75, bottom: 0.75)])
        // Stored rect is x 160..480, y 90..270. Just inside the top edge must be
        // fully greyed; a third of the way into the 27 px feather must be partial.
        let justInside = Self.pixel(dst, 320, 92)
        #expect(abs(justInside.r - 78) <= 2, "inside the hard rect is \(justInside), not fully greyed")
        let inFeather = Self.pixel(dst, 320, 80)
        #expect(inFeather.r > 78 && inFeather.r < 200, "feather at y=80 is \(inFeather), not a ramp")
        #expect(Self.pixel(dst, 320, 40) == (r: 200, g: 40, b: 90), "past the feather is not clean")
    }

    /// The feather's exact shape, not just "something ramps". The three sample
    /// points are chosen where a linear ramp and a 0.10 fraction both give
    /// visibly different answers from the spec's `smoothstep` at 0.15 — the
    /// looser test above passes under all three.
    ///
    /// Frame 640x360, hard rect x 160..480 / y 90..270, so `fy = 0.15 * 180 = 27`
    /// and the outward ramp occupies buffer rows 63..89. Core Image samples at
    /// pixel centres and counts y from the bottom, so buffer row `n` is
    /// `360 - n - 0.5` in the mask's own coordinates.
    /// (buffer row, mask, expected red). `mask = 1 - smoothstep(270, 297, yCI)`.
    static let featherCases: [(row: Int, mask: Double, expected: Double)] = [
        (69, 0.145947, 182.1),   // linear would give 170.5, a 0.10 feather 200.0
        (76, 0.500000, 138.8),   // the one point where linear agrees — pins the midpoint
        (83, 0.854028,  95.5),   // linear would give 107.1, a 0.10 feather 114.0
    ]

    @Test("feather is smoothstep over 0.15 of the rect", arguments: featherCases.indices)
    func featherIsSmoothstep(i: Int) throws {
        let (row, mask, expected) = Self.featherCases[i]
        let w = 640, h = 360
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: false,
                    regions: [NRect(left: 0.25, top: 0.25, right: 0.75, bottom: 0.75)])

        // BT.709 luma of (200, 40, 90), lerped toward the original by the mask.
        let grey = 0.2126 * 200 + 0.7152 * 40 + 0.0722 * 90
        let want = 200 + (grey - 200) * mask
        #expect(abs(want - expected) < 0.1, "table drift: computed \(want), table says \(expected)")
        let got = Self.pixel(dst, 320, row).r
        #expect(abs(Double(got) - want) <= 3,
                "row \(row): mask \(mask) wants \(String(format: "%.1f", want)), got \(got)")
    }

    /// `MAX_REGIONS = 8`, and the overflow **fails open**: it keeps the eight
    /// largest by area and drops the smallest faces, on exactly the frames with
    /// the most people in them (`spec-render.md` §2.7). Ten equal-width rects of
    /// descending height, so area order is unambiguous and the two that must go
    /// are the last two.
    @Test("nine or more regions keeps the eight largest")
    func regionOverflowKeepsLargest() throws {
        let w = 1000, h = 1000
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        // Column i: x (i*100+20)...(i*100+80), y 100...(100 + 100 - 8i).
        // 40 px of gutter between columns, more than the 9 px x-feather.
        let heights = (0..<10).map { 100 - 8 * $0 }
        let regions = heights.enumerated().map { i, ht in
            NRect(left: Float(i * 100 + 20) / 1000, top: 0.1,
                  right: Float(i * 100 + 80) / 1000, bottom: Float(100 + ht) / 1000)
        }
        CensorEffect(ops: { var o = FilterOps(); o.blurAmount = 0; o.grayscale = true; return o }(),
                     transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: false, regions: regions)

        for (i, ht) in heights.enumerated() {
            let p = Self.pixel(dst, i * 100 + 50, 100 + ht / 2)
            if i < 8 {
                #expect(abs(p.r - 78) <= 2, "region \(i) (area rank \(i + 1)) was dropped: \(p)")
            } else {
                #expect(p == (r: 200, g: 40, b: 90),
                        "region \(i) is the \(10 - i)th smallest and should have been dropped: \(p)")
            }
        }
    }

    /// The 0.002-per-axis floor under the feather (`spec-render.md` §2.5). A
    /// 1000x1000 frame with a 5 px-tall rect wants `0.15 x 5 = 0.75 px` of
    /// vertical ramp; the floor raises it to `0.002 x 1000 = 2 px`. Rows 399 and
    /// 398 sit 0.5 px and 1.5 px outside the hard rect — both inside the floored
    /// ramp, and 398 is past the unfloored one entirely. Drop the floor and 97
    /// becomes 168, 181 becomes a clean 200.
    @Test("feather floors at 0.002 of the frame on a thin rect")
    func featherFloor() throws {
        let w = 1000, h = 1000
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: false,
                    regions: [NRect(left: 0.4, top: 0.4, right: 0.6, bottom: 0.405)])

        let grey = 0.2126 * 200 + 0.7152 * 40 + 0.0722 * 90
        // Hard rect is buffer rows 400..405, ramp rows 398..400 and 405..407.
        #expect(abs(Double(Self.pixel(dst, 500, 402).r) - grey) <= 3, "hard rect is not solid")
        // mask = 1 - smoothstep(0, 2, d) for d px past the edge: 0.84375 at
        // d = 0.5, 0.15625 at d = 1.5.
        for (row, mask, unfloored) in [(399, 0.84375, 168.3), (398, 0.15625, 200.0)] {
            let want = 200 + (grey - 200) * mask
            let got = Self.pixel(dst, 500, row).r
            #expect(abs(Double(got) - want) <= 3,
                    "row \(row) is \(got), want \(String(format: "%.0f", want)) — unfloored: \(unfloored)")
        }
        #expect(Self.pixel(dst, 500, 396) == (r: 200, g: 40, b: 90), "ramp is wider than the floor")
    }

    /// `GL_CLAMP_TO_EDGE` parity (`spec-render.md` §1.6a): blur taps that fall
    /// outside the frame replicate the edge texel. Blurring a flat field must
    /// therefore be the identity everywhere *including the corners* — without
    /// the clamp Core Image reads transparent black past the extent and the
    /// border darkens. Both rungs of the ladder are covered: 40 stays at d=1,
    /// 100 downscales to d=4, and only the downscaled path has a second extent
    /// to get wrong.
    @Test("blurring a flat field leaves the borders alone", arguments: [40, 100])
    func flatFieldBlurHasNoEdgeDarkening(amount: Int) throws {
        let w = 320, h = 240
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = amount
        ops.grayscale = false
        let effect = CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                                  tonemapHDR: false)
        #expect(effect.plan.downscale == (amount == 40 ? 1 : 4), "ladder rung moved")
        effect.render(src, to: dst, wholeFrame: true, regions: [])

        for (x, y) in [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1), (w / 2, 0), (0, h / 2)] {
            let p = Self.pixel(dst, x, y)
            #expect(abs(p.r - 200) <= 2 && abs(p.g - 40) <= 2 && abs(p.b - 90) <= 2,
                    "amount \(amount): border pixel \(x),\(y) is \(p), not (200,40,90)")
        }
    }

    // MARK: - The production pixel format

    /// Every other pixel test here runs on BGRA, but the render path only ever
    /// sees `420v` — `TrackReader.decodedVideo` asks for it and the adaptor pool
    /// vends it. That round trip is where an unmanaged Core Image context can go
    /// wrong invisibly: video-range expansion, the YCbCr matrix, and the fact
    /// that `workingColorSpace: NSNull()` must still let CI do the *format*
    /// conversion it is not allowed to colour-manage.
    ///
    /// BT.709's luma coefficients are the same ones that define Y′, so greying a
    /// 709 frame must leave **Y′ byte-identical** and drive both chroma planes to
    /// neutral 128. Under BT.601 the decode matrix differs, so 709 luma lands
    /// elsewhere and the expected Y′ is a different, hard-computed number — which
    /// is what proves the matrix is read off the buffer instead of assumed.
    /// `CFString` is not `Sendable`, so the constants are picked inside the test
    /// rather than stored in the case table.
    static let yuvCases: [(name: String, expectedY: Double)] = [
        // Y' IS the 709 luma of the decoded RGB, so 709 round-trips exactly.
        ("bt709", 150.0),
        // 601 decodes to R 0.8747 / G 0.5210 / B 0.3904; 709 luma of that is
        // 0.586767, re-encoded as 16 + 219 * 0.586767 = 144.5.
        ("bt601", 144.5),
    ]

    @Test("grayscale on a 4:2:0 buffer preserves Y and neutralises chroma",
          arguments: yuvCases.indices)
    func yuv420RoundTrip(i: Int) throws {
        let (name, expectedY) = Self.yuvCases[i]
        let matrix = name == "bt709"
            ? kCVImageBufferYCbCrMatrix_ITU_R_709_2
            : kCVImageBufferYCbCrMatrix_ITU_R_601_4
        let w = 64, h = 64
        // Y 150 / Cb 100 / Cr 170: in gamut under both matrices, and far enough
        // off neutral that a dropped 16..235 expansion shifts Y by ~11.
        let src = try Self.yuv420(w, h, y: 150, cb: 100, cr: 170, matrix: matrix)
        let dst = try Self.yuv420(w, h, y: 0, cb: 0, cr: 0, matrix: matrix)
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: false)
            .render(src, to: dst, wholeFrame: true, regions: [])

        let got = Self.yuvPixel(dst, w / 2, h / 2)
        #expect(abs(Double(got.y) - expectedY) <= 2,
                "\(name): Y' \(got.y), expected \(expectedY) — colour management leaked in")
        #expect(abs(Int(got.cb) - 128) <= 2 && abs(Int(got.cr) - 128) <= 2,
                "\(name): chroma \(got.cb),\(got.cr) is not neutral after grayscale")
    }

    // MARK: - End to end

    @Test("censor-only: frames preserved, audio bit-identical")
    func censorOnlyEndToEnd() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("censor-only.mp4")
        let src = try await MediaSource.probe(inURL)

        // 2 s of whole-frame, then a moving face track — exercises both lookup
        // paths and leaves plenty of untouched frames for the fast path.
        let edl = Edl(censorIntervalsMs: [2_000...4_000],
                      faceTracks: [FaceTrackEdl(startMs: 6_000, endMs: 8_000, keyframes: [
                          .init(timeMs: 6_000, rect: NRect(left: 0.20, top: 0.20, right: 0.50, bottom: 0.45)),
                          .init(timeMs: 8_000, rect: NRect(left: 0.40, top: 0.30, right: 0.70, bottom: 0.55)),
                      ])])

        let r = try await RenderPass.run(source: src, edl: edl, ops: FilterOps(), output: outURL)
        print("[render] \(r.frames) frames, \(r.censoredFrames) censored, "
            + "\(String(format: "%.0f", r.wallMs)) ms, \(String(format: "%.1f", r.framesPerSecond)) fps")

        #expect(FileManager.default.fileExists(atPath: outURL.path))
        let inFrames = try await Self.sampleCount(inURL, .video)
        #expect(r.frames == inFrames, "rendered \(r.frames) of \(inFrames) source frames")
        let outFrames = try await Self.sampleCount(outURL, .video)
        #expect(outFrames == r.frames, "output has \(outFrames) samples, rendered \(r.frames)")
        // Both spans are inclusive at both ends (`spec-render.md` §5.2) and the
        // clip is 30.000 fps on a 15360 timescale, so `pts.value * 1000 /
        // timescale` lands exactly on 2000/4000/6000/8000: frames 60...120 and
        // 180...240, 61 each. An exclusive end would give 120, a rounded time
        // base would move the edges.
        #expect(r.censoredFrames == 122, "censored \(r.censoredFrames) frames, expected 61 + 61")

        let outDur = try await AVURLAsset(url: outURL).load(.duration).seconds
        #expect(abs(outDur - src.duration.seconds) < 0.05,
                "duration \(outDur) vs \(src.duration.seconds)")

        // The whole point of the censor-only shape: the audio bytes are never
        // decoded, so the elementary stream must hash identically.
        let a = try await Self.audioDigest(inURL)
        let b = try await Self.audioDigest(outURL)
        #expect(a.hash == b.hash, "audio was re-encoded")
        #expect(a.bytes == b.bytes, "audio payload size differs: \(a.bytes) vs \(b.bytes)")

        // ...and the video was NOT passed through — it is a fresh encode.
        let v0 = try await Self.videoDigest(inURL)
        let v1 = try await Self.videoDigest(outURL)
        #expect(v0 != v1, "video stream is byte-identical — the censor never ran")
    }

    @Test("Fast mode caps the output short side at 720 without changing cadence")
    func fastModeGeometry() async throws {
        let input = try requireQAVideo()
        let source = try await MediaSource.probe(input)
        var ops = FilterOps()
        ops.processingMode = .fast
        let context = try RenderPass.Context(source: source, ops: ops)
        #expect(min(context.video.naturalSize.width, context.video.naturalSize.height) == 720)
        #expect(Int(context.video.naturalSize.width) % 2 == 0)
        #expect(Int(context.video.naturalSize.height) % 2 == 0)

        let output = Fixtures.scratch("fast-720.mp4")
        let result = try await RenderPass.run(
            source: source, edl: Edl(censorIntervalsMs: [0...250]), ops: ops,
            output: output, context: context)
        let rendered = try await MediaSource.probe(output)
        let video = try #require(rendered.video)
        let inputFrames = try await Self.sampleCount(input, .video)
        #expect(video.naturalSize == context.video.naturalSize)
        #expect(result.frames == inputFrames)
    }

    /// The both-ops seam. M2 owns producing the replacement track; this pass
    /// only muxes it, so the check is that the output carries the replacement's
    /// bytes and not the source's, with the censor render intact alongside.
    @Test("both-ops: replacement audio is muxed in place of the source's")
    func bothOpsReplacedAudio() async throws {
        let inURL = try requireQAVideo()
        let replURL = Fixtures.scratch("replacement.m4a")
        let outURL = Fixtures.scratch("both-ops.mp4")
        let src = try await MediaSource.probe(inURL)

        // Stand-in for the audio pass's output: the source's own AAC, truncated,
        // so "the replacement was used" is provable from the byte count alone.
        let asset = AVURLAsset(url: inURL)
        defer { withExtendedLifetime(asset) {} }
        let aTrack = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        let w = try OutputWriter(url: replURL, fileType: .m4a)
        w.addPassthroughAudio(try #require(src.audio))
        try w.start()
        let reader = try TrackReader.compressed(track: aTrack)
        try reader.start()
        nonisolated(unsafe) let rd = reader
        nonisolated(unsafe) let sink = try #require(w.audioInput)
        let n = Confined(0)
        try await pump(sink, label: "truncate") {
            guard n.v < 100, let sb = rd.next() else { return false }
            _ = sink.append(sb)
            n.v += 1
            return true
        }
        try await w.finish()

        var ops = FilterOps()
        ops.removeMusic = true
        let r = try await RenderPass.run(source: src, edl: Edl(censorIntervalsMs: [0...1_000]),
                                         ops: ops, output: outURL, replacedAudio: replURL)
        #expect(r.frames == (try await Self.sampleCount(inURL, .video)))

        let replacement = try await Self.audioDigest(replURL)
        let got = try await Self.audioDigest(outURL)
        let original = try await Self.audioDigest(inURL)
        #expect(got.hash == replacement.hash, "output audio is not the replacement track")
        #expect(got.hash != original.hash, "output audio is still the source's")
        #expect(got.bytes < original.bytes)
    }

    /// No HDR fixture is staged, so this only proves the tone-map path builds a
    /// working colour-managed graph and stays inside SDR — an actual HLG/PQ clip
    /// still has to go through it before M4 can be called done.
    @Test("HDR tone-map path renders SDR")
    func hdrPathRuns() throws {
        let w = 128, h = 128
        let src = try Self.bgra(w, h) { _, _ in (200, 40, 90) }
        let dst = try Self.bgra(w, h) { _, _ in (0, 0, 0) }
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        CensorEffect(ops: ops, transform: .identity(size: CGSize(width: w, height: h)),
                     tonemapHDR: true)
            .render(src, to: dst, wholeFrame: true, regions: [])
        let p = Self.pixel(dst, 64, 64)
        #expect(p.r == p.g && p.g == p.b, "tone-mapped grayscale is not neutral: \(p)")
        #expect(p.r > 8 && p.r < 248, "tone-mapped pixel clipped to \(p)")
    }

    // MARK: - The segmented route (M5)

    /// **The** test for segment + concat. A 90-minute film killed at minute 80
    /// has to resume, and it resumes by rendering 5-minute slices that are
    /// joined without a re-encode. The joined file must be indistinguishable
    /// from the one monolithic pass it replaces, so it is compared against
    /// exactly that — not against a hand-computed expectation.
    ///
    /// Both interior cuts land mid-GOP, which is the case that matters: the
    /// reader decodes from the sync sample before the cut and throws those
    /// frames away, and the seam must lose nothing to a decode order that
    /// disagrees with display order (`spec-render.md` §6.5 measured 49 frames
    /// lost over 31 seams on Android). The fixture is asserted to have that
    /// shape rather than assumed to.
    ///
    /// The source is small and synthetic on purpose. Frame arithmetic at a seam
    /// does not care about resolution, and five 1080p transcodes in one process
    /// is what got this test's own process killed on a loaded machine while it
    /// was being written. `segmentCensorOffset` runs the real QA clip.
    /// The 29.97 fps seam, in seconds instead of the seven minutes a real soak
    /// costs.
    ///
    /// `segmentedConcatMatchesMonolithic` below is 30 fps on a 600 timescale, so
    /// its cuts land between frames but the segment *durations* stay exact
    /// multiples of the frame duration. At 30000/1001 they do not, and a
    /// 32-minute end-to-end soak found the consequence: **7 duplicated PTS,
    /// 9 extra frames, and a total 7 ms SHORTER than the source** — more frames
    /// in less time, which only overlapping segments can produce. Six of the
    /// seven sat just past a cut, at a growing offset (146, 211, 293, 358, 407,
    /// 472 ms), which is the accumulating-cursor shape hazard 11 warned about.
    ///
    /// Pre-existing, not a regression: M5's 90-minute soak found 0 duplicates
    /// across 162 049 frames — because that asset is 30/1 fps.
    @Test("29.97 fps segments concatenate without duplicating a frame")
    func segmentedConcat2997() async throws {
        let sourceURL = Fixtures.scratch("seg2997-source.mp4")
        // 30000/1001 is exactly 29.97. 900 frames = 30.03 s.
        try await Self.syntheticClip(sourceURL, size: CGSize(width: 320, height: 240),
                                     frames: 900, tickStride: 1_001, timescale: 30_000)
        let src = try await MediaSource.probe(sourceURL)
        let durationMs = src.duration.convertScale(1000, method: .default).value

        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        let edl = Edl(censorIntervalsMs: [])

        // Three 10 s segments. At 29.97 none of these is a frame time:
        // 10000 ms is frame 299.700, 20000 ms is frame 599.400.
        let cuts: [Int64] = [0, 10_000, 20_000, durationMs]
        #expect(cuts.dropFirst().dropLast().allSatisfy { $0 * 30_000 % (1_001 * 1_000) != 0 },
                "a cut landed exactly on a frame, which is the case that already works")

        var parts: [URL] = []
        var rendered = 0
        for (i, pair) in zip(cuts, cuts.dropFirst()).enumerated() {
            let url = Fixtures.scratch("seg2997-\(i).mp4")
            let r = try await RenderPass.run(source: src, edl: edl, ops: ops, output: url,
                                             range: pair.0...pair.1)
            parts.append(url)
            rendered += r.frames
        }

        // Per-segment geometry, printed because the join's failure mode depends
        // on whether the media range and the presentation duration agree.
        for (i, u) in parts.enumerated() {
            let a = AVURLAsset(url: u)
            let t = try #require(try await a.loadTracks(withMediaType: .video).first)
            let tr = try await t.load(.timeRange)
            let assetDur = try await a.load(.duration)
            let n = try await Self.sampleCount(u, .video)
            print(String(format: "seg %d: %d frames  trackRange %.4f..+%.4f  assetDur %.4f",
                         i, n, tr.start.seconds, tr.duration.seconds, assetDur.seconds))
        }

        let joined = Fixtures.scratch("seg2997-joined.mp4")
        try await Remux.concat(parts, to: joined)
        let (frames, duplicateMs) = try await BenchTests.scanPTS(joined)
        let sourceFrames = try await Self.sampleCount(sourceURL, .video)

        // The render side is CORRECT and is asserted live: the segments contain
        // exactly the source's frames, so nothing above this line is at fault.
        #expect(rendered == sourceFrames,
                "the segments rendered \(rendered) of \(sourceFrames) frames before the join")

        // KNOWN ISSUE — the join, and only at non-integer frame durations.
        //
        // Diagnosis so far, all of it printed above: each segment is 300 frames
        // with `trackRange 0..+10.0100` and `assetDur 10.0100`, so the
        // composition geometry is exact — segments land at 0, 10.01, 20.02 with
        // no overlap and no edit-list discrepancy. 900 frames go in and **904
        // come out**, with duplicates at [66, 10143, 20220, 30030] ms. The last
        // of those is past the source's final frame at 29996 ms, so the
        // exporter is emitting frames the composition does not contain rather
        // than segments overlapping.
        //
        // That points at `AVAssetExportPresetPassthrough` over a composition
        // whose frame duration (1001/30000) is not an integer number of
        // timescale ticks — not at `Remux`'s cursor, which the geometry above
        // clears.
        //
        // NOT a regression, and no worse than the app it replaces: M5's
        // 90-minute soak found 0 duplicates in 162 049 frames because that
        // asset is 30/1 fps, and Android's own join *loses* ~2 frames per seam
        // with a ~100 ms freeze at each (hazard 12). This gains ~1 frame per
        // seam — 7 across 32 minutes, 7 ms of duration.
        //
        // NEXT EXPERIMENT, before changing anything: read the composition
        // directly with `AVAssetReader` instead of exporting it. If the frame
        // count is right there, the fix is the export step (re-encode the join,
        // or write it with `AVAssetWriter` sample-by-sample); if it is already
        // wrong, it is `insertTimeRange`.
        withKnownIssue("passthrough export duplicates ~1 frame per seam at 29.97 fps") {
            #expect(duplicateMs.isEmpty, """
                \(duplicateMs.count) duplicated PTS at \(duplicateMs) ms
                """)
            #expect(frames == sourceFrames, "the join holds \(frames) of \(sourceFrames) frames")
        }

        for u in parts + [joined, sourceURL] { try? FileManager.default.removeItem(at: u) }
    }

    @Test("N segments concatenate back into the monolithic render")
    func segmentedConcatMatchesMonolithic() async throws {
        let sourceURL = Fixtures.scratch("seg-source.mp4")
        try await Self.syntheticClip(sourceURL, size: CGSize(width: 320, height: 240), frames: 300)
        let src = try await MediaSource.probe(sourceURL)
        let durationMs = src.duration.convertScale(1000, method: .default).value
        #expect(durationMs > 9_966, "fixture is \(durationMs) ms, expected ~10000")

        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        // Both intervals straddle a cut, so a seam that dropped or duplicated a
        // frame would move the censored count as well as the frame count.
        // 30 fps on a 600 timescale puts frames exactly on these millisecond
        // boundaries: 2900...3100 is frames 87...93 and 6900...7100 is frames
        // 207...213, seven each.
        let edl = Edl(censorIntervalsMs: [2_900...3_100, 6_900...7_100])

        let wholeURL = Fixtures.scratch("seg-monolithic.mp4")
        let mono = try await RenderPass.run(source: src, edl: edl, ops: ops, output: wholeURL)
        #expect(mono.frames == 300, "monolithic rendered \(mono.frames) of 300 frames")
        #expect(mono.censoredFrames == 14, "censored \(mono.censoredFrames), expected 7 + 7")

        let cuts: [Int64] = [0, 3_010, 7_010, durationMs]
        // A fixture that came out all-keyframes would make every cut free and
        // the pre-roll path would never run.
        let syncs = Set(try await Self.syncSampleTimesMs(sourceURL))
        #expect(syncs.count > 1, "fixture is one GOP: \(syncs.sorted())")
        #expect(syncs.isDisjoint(with: cuts.dropFirst().dropLast()),
                "cuts landed on sync samples: \(syncs.sorted())")
        // And **between two frames**, not merely inside a GOP. Frame `i` sits at
        // `i * 1000 / 30` ms, so 3010 falls between frames 90 (3000) and 91
        // (3033) and the segment's first kept frame is 23 ms past its own cut.
        // That is the only shape in which rebasing to the cut and rebasing to
        // the frame differ — and it is what every 29.97 fps cut looks like, so a
        // frame-aligned fixture leaves `renderVideo`'s whole `base` argument
        // untested and the 23 ms hole it prevents unmeasured.
        #expect(cuts.dropFirst().dropLast().allSatisfy { $0 * 30 % 1_000 != 0 },
                "a cut landed exactly on a frame: \(cuts)")
        var parts: [URL] = []
        var frames = 0, censored = 0
        // What "starts at PTS 0" looks like out of this encoder, read off the
        // whole-film render rather than assumed: VideoToolbox bakes its reorder
        // delay into the media and cancels it with an edit list.
        let monoStart = try await Self.minVideoPTS(wholeURL)
        for (i, pair) in zip(cuts, cuts.dropFirst()).enumerated() {
            let url = Fixtures.scratch("seg-\(i).mp4")
            let r = try await RenderPass.run(source: src, edl: edl, ops: ops, output: url,
                                             range: pair.0...pair.1)
            parts.append(url)
            frames += r.frames
            censored += r.censoredFrames
            // A segment is video-only: per-segment AAC cannot be concatenated,
            // so the audio is muxed once at the end (`spec-render.md` §4.2).
            let audioSamples = try await Self.sampleCount(url, .audio)
            #expect(audioSamples == 0,
                    "segment \(i) carries \(audioSamples) audio samples — concat would splice AAC at a seam")
            // PTS 0, or the concat inherits a gap at every join — and a segment
            // that forgot to rebase would read its own cut time here, not 0.
            let info = try await Self.videoTrackInfo(url)
            #expect(info.start == 0, "segment \(i) presents from \(info.start)s, not 0")
            let low = try await Self.minVideoPTS(url)
            #expect(low == monoStart,
                    "segment \(i)'s earliest sample is \(low) ms; a rebased segment reads \(monoStart)")
        }
        // 90 + 120 + 90. Off by one either way means a seam dropped a frame or
        // wrote it into both neighbours.
        #expect(frames == mono.frames, "segments rendered \(frames) of \(mono.frames) frames")
        #expect(censored == mono.censoredFrames,
                "segments censored \(censored), monolithic \(mono.censoredFrames)")

        let joined = Fixtures.scratch("seg-concat.mp4")
        try await Remux.concat(parts, to: joined)

        let a = try await Self.videoTrackInfo(wholeURL)
        let b = try await Self.videoTrackInfo(joined)
        let joinedFrames = try await Self.sampleCount(joined, .video)
        #expect(joinedFrames == mono.frames,
                "concat holds \(joinedFrames) of \(mono.frames) frames")
        #expect(a.size == b.size, "concat is \(b.size), monolithic \(a.size)")
        // The QA clip is upright, so this only says "no matrix was invented".
        // `concatKeepsRotation` is where a dropped matrix actually fails.
        #expect(a.transform == b.transform, "concat changed the rotation matrix")
        // Each segment's last sample gets a duration the writer infers rather
        // than reads, so three joins can differ from one continuous track by a
        // few frame intervals. Anything larger is a dropped segment.
        #expect(abs(a.duration - b.duration) < 0.1,
                "concat is \(b.duration)s, monolithic \(a.duration)s")

        // *Where* the censor landed, not just how many frames carry it. Both
        // counts above and the duration survive a seam that slid the join by a
        // frame; the greyed timestamps do not — and a censor that moved off the
        // thing it was covering is the only version of this bug a user sees.
        let monoGrey = try await Self.chromaByFrame(wholeURL).filter { $0.chroma < 6 }.map(\.ms)
        let joinGrey = try await Self.chromaByFrame(joined).filter { $0.chroma < 6 }.map(\.ms)
        #expect(joinGrey == monoGrey,
                "greyed at \(joinGrey.first ?? -1)…\(joinGrey.last ?? -1) in the join, \(monoGrey.first ?? -1)…\(monoGrey.last ?? -1) monolithic")
    }

    /// The offset bug, isolated. The EDL is whole-film and absolute; a segment
    /// is written from PTS 0. Looking the EDL up at segment-relative time finds
    /// nothing (this window's relative times only reach 5000), and writing at
    /// absolute time opens the file with a 4-second hole — so the two halves of
    /// the mapping are asserted separately.
    @Test("a censor lands at the right time in a segment that does not start at 0")
    func segmentCensorOffset() async throws {
        let inURL = try requireQAVideo()
        let src = try await MediaSource.probe(inURL)
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true

        let out = Fixtures.scratch("seg-offset.mp4")
        // Absolute 6000...6500 sits 2 s into the 4000...9000 window: frames
        // 180...195, which land at segment-relative 2000...2500 ms.
        let r = try await RenderPass.run(source: src, edl: Edl(censorIntervalsMs: [6_000...6_500]),
                                         ops: ops, output: out, range: 4_000...9_000)
        #expect(r.frames == 150, "window holds \(r.frames) frames, expected 150")
        #expect(r.censoredFrames == 16,
                "censored \(r.censoredFrames), expected 16 — the EDL was not read at absolute time")

        // Chroma proves it landed where it was written, not just that something
        // was censored: greying drives Cb/Cr to neutral, and this clip's own
        // frames sit ~15 off neutral.
        let frames = try await Self.chromaByFrame(out)
        #expect(frames.count == 150)
        let grey = frames.filter { $0.chroma < 6 }.map(\.ms)
        let colour = frames.filter { $0.chroma > 10 }
        #expect(grey.count + colour.count == frames.count,
                "\(frames.count - grey.count - colour.count) frames are neither grey nor coloured")
        #expect(grey.first == 2_000 && grey.last == 2_500,
                "greyed span is \(grey.first ?? -1)...\(grey.last ?? -1) ms, expected 2000...2500")
        #expect(grey.count == 16, "\(grey.count) greyed frames, expected 16")
    }

    /// Two things the 1080p test above cannot check, on twelve frames instead of
    /// 384: that the joined duration really is the **sum** of the inputs — a
    /// concat that wrote only its first segment reports success and is the
    /// classic silent failure — and that a non-identity rotation matrix survives
    /// the join. The QA clip is upright, so only a synthetic source can fail the
    /// second one.
    @Test("concat sums the inputs and carries the rotation matrix")
    func concatKeepsRotation() async throws {
        let size = CGSize(width: 128, height: 64)
        // Stored 128x64 displayed as 64x128: `spec-avfoundation.md` §5.2's rot-90.
        let rot = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 64, ty: 0)
        let first = Fixtures.scratch("rot-a.mp4")
        let second = Fixtures.scratch("rot-b.mp4")
        try await Self.syntheticClip(first, size: size, frames: 12, rotation: rot)
        try await Self.syntheticClip(second, size: size, frames: 12, rotation: rot)

        let out = Fixtures.scratch("rot-concat.mp4")
        try await Remux.concat([first, second], to: out)

        let joined = try await Self.videoTrackInfo(out)
        let one = try await Self.videoTrackInfo(first)
        #expect(joined.transform == rot, "concat wrote \(joined.transform), source \(rot)")
        #expect(joined.size == size, "concat is \(joined.size), source \(size)")
        let samples = try await Self.sampleCount(out, .video)
        #expect(samples == 24, "concat holds \(samples) samples, expected 12 + 12")
        #expect(abs(joined.duration - one.duration * 2) < 0.05,
                "concat is \(joined.duration)s, two \(one.duration)s inputs")
    }

    /// `Remux.mux` is what makes a music-only job resumable: the source's own
    /// picture and the separated `.m4a` are two files on disk, joined without an
    /// encode. Both halves are checked by digest — a re-encode of either would
    /// change its bytes even where it looks identical.
    @Test("mux joins one file's video to another's audio without re-encoding")
    func remuxMuxPassthrough() async throws {
        let inURL = try requireQAVideo()
        let src = try await MediaSource.probe(inURL)
        let audioURL = Fixtures.scratch("mux-audio.m4a")
        try await Self.truncatedAudio(from: inURL, info: try #require(src.audio),
                                      to: audioURL, samples: 100)
        let out = Fixtures.scratch("muxed.mp4")
        try await Remux.mux(video: inURL, audio: audioURL, to: out)

        let sourceVideo = try await Self.digest(inURL, .video)
        let muxedVideo = try await Self.digest(out, .video)
        #expect(muxedVideo.hash == sourceVideo.hash, "the video track was re-encoded")
        #expect(muxedVideo.bytes == sourceVideo.bytes,
                "video payload is \(muxedVideo.bytes) bytes, source \(sourceVideo.bytes)")

        let replacement = try await Self.digest(audioURL, .audio)
        let muxedAudio = try await Self.digest(out, .audio)
        let sourceAudio = try await Self.digest(inURL, .audio)
        #expect(muxedAudio.hash == replacement.hash, "output audio is not the file that was passed in")
        #expect(muxedAudio.hash != sourceAudio.hash, "output kept the video file's own audio")

        // Geometry and rotation ride in the format description, not the samples.
        let a = try await Self.videoTrackInfo(inURL)
        let b = try await Self.videoTrackInfo(out)
        #expect(a.size == b.size && a.transform == b.transform)
        #expect(abs(a.duration - b.duration) < 0.05,
                "muxed video is \(b.duration)s, source \(a.duration)s")
    }

    @Test("cancel mid-render leaves no output file")
    func cancelLeavesNothing() async throws {
        let inURL = try requireQAVideo()
        let outURL = Fixtures.scratch("censor-cancelled.mp4")
        let src = try await MediaSource.probe(inURL)
        let seen = Confined(0)
        do {
            _ = try await RenderPass.run(source: src, edl: Edl(), ops: FilterOps(), output: outURL,
                                         isCancelled: { seen.v += 1; return seen.v > 20 })
            Issue.record("expected cancellation to throw")
        } catch {
            // expected
        }
        #expect(!FileManager.default.fileExists(atPath: outURL.path), "partial file left behind")
    }

    // MARK: - End card

    /// The mockup's timeline (`docs/endcard/endcard-mockup.html`): blank at 0,
    /// settled well before 2 s, and the drop overshooting its resting place on
    /// the way in.
    @Test("end card timing follows the mockup")
    func endCardTiming() {
        let start = EndCard.Pose(t: 0)
        #expect(start.fade == 0 && start.arc == 0 && start.drop == 0 && start.glow == 0)
        #expect(start.arabic == 0 && start.english == 0)
        #expect(abs(EndCard.Pose(t: 0.075).fade - 0.5) < 1e-9)
        let settled = EndCard.Pose(t: 1.3)
        #expect(settled.fade == 1 && settled.arc == 1 && settled.glow == 1)
        #expect(settled.arabic == 1 && settled.english == 1 && settled.dropAlpha == 1)
        #expect(abs(settled.dropOffset) < 1e-9)
        let fall = (0...100).map { EndCard.Pose(t: 0.35 + 0.35 * Double($0) / 100).dropOffset }
        #expect(fall.first == -18 && (fall.max() ?? 0) > 0, "the drop never overshot: \(fall)")

        #expect(EndCard.frameRate(source: 1) == 30)
        #expect(EndCard.frameRate(source: 120) == 30)
        #expect(EndCard(transform: .identity(size: CGSize(width: 64, height: 64)),
                        sourceFrameRate: 25).frameCount == 50)
    }

    /// The card is drawn upright and stored the way the picture is, so turning
    /// a rotated card's stored pixels by the track's own matrix has to give the
    /// upright card back — and the upright card has its bowl under its drop.
    @Test("the end card plays upright under every rotation", arguments: regionCases.indices)
    func endCardUpright(i: Int) throws {
        let c = Self.regionCases[i]
        let stored = CGSize(width: 640, height: 360)
        let transform = VideoTransform(preferredTransform: c.t, naturalSize: stored)
        let up = transform.uprightSize
        let ctx = CIContext(options: [.workingColorSpace: NSNull()])
        func bitmap(_ image: CIImage) throws -> CVPixelBuffer {
            let b = try Self.bgra(Int(image.extent.width), Int(image.extent.height)) { _, _ in (0, 0, 0) }
            ctx.render(image, to: b, bounds: image.extent, colorSpace: nil)
            return b
        }
        let card = EndCard(transform: transform, sourceFrameRate: 30)
        let shown = card.image(frame: card.frameCount - 1, over: nil)
        #expect(shown.extent.size == stored)
        // Stored, y-up -> upright, y-up: the same trip a player makes.
        let flipStored = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: stored.height)
        let flipUp = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: up.height)
        let played = try bitmap(shown.transformed(by: flipStored.concatenating(c.t).concatenating(flipUp)))
        let reference = EndCard(transform: .identity(size: up), sourceFrameRate: 30)
        let expected = try bitmap(reference.image(frame: reference.frameCount - 1, over: nil))

        var off = 0
        for y in stride(from: 0, to: Int(up.height), by: 6) {
            for x in stride(from: 0, to: Int(up.width), by: 6) {
                let a = Self.pixel(played, x, y), b = Self.pixel(expected, x, y)
                if abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b) > 12 { off += 1 }
            }
        }
        #expect(off == 0, "\(c.name): \(off) sampled pixels differ from the upright card")

        // Mark units -> upright pixels, exactly as `EndCard.draw` lays it out.
        let s = min(up.width, up.height), unit = s * 0.26 / 45.7
        func at(_ mx: CGFloat, _ my: CGFloat) -> (r: Int, g: Int, b: Int) {
            Self.pixel(expected, Int(up.width / 2 + (mx - 54) * unit),
                       Int(up.height / 2 - s * 0.09 + (my - 53.55) * unit))
        }
        #expect(at(54, 70.45).g > 150, "\(c.name): no bowl at the bottom of the mark")
        #expect(at(56, 40.64).g > 150, "\(c.name): no drop inside the bowl")
        #expect(at(54, 26).g < 80, "\(c.name): ink above the drop")
    }

    /// Picture only, after the last frame, at the source's cadence: the output
    /// grows by exactly the card and keeps its rotation matrix.
    @Test("render appends the end card after the last frame")
    func renderAppendsEndCard() async throws {
        let rot = CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 96, ty: 0)
        let inURL = Fixtures.scratch("endcard-in.mp4")
        try await Self.syntheticClip(inURL, size: CGSize(width: 160, height: 96), frames: 30, rotation: rot)
        let src = try await MediaSource.probe(inURL)
        var ops = FilterOps()
        ops.blurAmount = 0
        ops.grayscale = true
        let outURL = Fixtures.scratch("endcard-out.mp4")
        let r = try await RenderPass.run(source: src, edl: Edl(censorIntervalsMs: [0...100]), ops: ops,
                                         output: outURL, endCard: true)
        #expect(r.frames == 30, "the card is not a source frame")
        let frames = try await Self.sampleCount(outURL, .video)
        #expect(frames == 30 + 60, "output holds \(frames) frames, expected 30 + a 60-frame card")
        let a = try await Self.videoTrackInfo(inURL)
        let b = try await Self.videoTrackInfo(outURL)
        #expect(b.transform == rot)
        #expect(abs(b.duration - (a.duration + EndCard.duration)) < 0.05,
                "output is \(b.duration)s from a \(a.duration)s source")
    }

    // MARK: - Helpers

    static func bgra(_ w: Int, _ h: Int,
                     _ colour: (Int, Int) -> (UInt8, UInt8, UInt8)) throws -> CVPixelBuffer {
        var out: CVPixelBuffer?
        CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_32BGRA,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary,
                            &out)
        let b = try #require(out)
        CVPixelBufferLockBaseAddress(b, [])
        defer { CVPixelBufferUnlockBaseAddress(b, []) }
        let base = try #require(CVPixelBufferGetBaseAddress(b)).assumingMemoryBound(to: UInt8.self)
        let stride = CVPixelBufferGetBytesPerRow(b)
        for y in 0..<h {
            for x in 0..<w {
                let (r, g, bl) = colour(x, y)
                let p = base + y * stride + x * 4
                p[0] = bl; p[1] = g; p[2] = r; p[3] = 255
            }
        }
        return b
    }

    /// A flat `420v` frame with the colour tags the decoder would have attached.
    static func yuv420(_ w: Int, _ h: Int, y: UInt8, cb: UInt8, cr: UInt8,
                       matrix: CFString) throws -> CVPixelBuffer {
        var out: CVPixelBuffer?
        CVPixelBufferCreate(nil, w, h, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary,
                            &out)
        let b = try #require(out)
        CVBufferSetAttachment(b, kCVImageBufferYCbCrMatrixKey, matrix, .shouldPropagate)
        CVBufferSetAttachment(b, kCVImageBufferColorPrimariesKey,
                              kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
        CVBufferSetAttachment(b, kCVImageBufferTransferFunctionKey,
                              kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
        CVPixelBufferLockBaseAddress(b, [])
        defer { CVPixelBufferUnlockBaseAddress(b, []) }
        let luma = try #require(CVPixelBufferGetBaseAddressOfPlane(b, 0))
            .assumingMemoryBound(to: UInt8.self)
        let lumaStride = CVPixelBufferGetBytesPerRowOfPlane(b, 0)
        for row in 0..<h { (luma + row * lumaStride).update(repeating: y, count: w) }
        let chroma = try #require(CVPixelBufferGetBaseAddressOfPlane(b, 1))
            .assumingMemoryBound(to: UInt8.self)
        let chromaStride = CVPixelBufferGetBytesPerRowOfPlane(b, 1)
        for row in 0..<(h / 2) {
            let p = chroma + row * chromaStride
            for col in 0..<(w / 2) { p[col * 2] = cb; p[col * 2 + 1] = cr }
        }
        return b
    }

    static func yuvPixel(_ b: CVPixelBuffer, _ x: Int, _ y: Int) -> (y: UInt8, cb: UInt8, cr: UInt8) {
        CVPixelBufferLockBaseAddress(b, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(b, .readOnly) }
        guard let luma = CVPixelBufferGetBaseAddressOfPlane(b, 0)?.assumingMemoryBound(to: UInt8.self),
              let chroma = CVPixelBufferGetBaseAddressOfPlane(b, 1)?.assumingMemoryBound(to: UInt8.self)
        else { return (0, 0, 0) }
        let l = luma + y * CVPixelBufferGetBytesPerRowOfPlane(b, 0) + x
        let c = chroma + (y / 2) * CVPixelBufferGetBytesPerRowOfPlane(b, 1) + (x / 2) * 2
        return (l[0], c[0], c[1])
    }

    static func pixel(_ b: CVPixelBuffer, _ x: Int, _ y: Int) -> (r: Int, g: Int, b: Int) {
        CVPixelBufferLockBaseAddress(b, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(b, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(b)?.assumingMemoryBound(to: UInt8.self) else {
            return (-1, -1, -1)
        }
        let p = base + y * CVPixelBufferGetBytesPerRow(b) + x * 4
        return (Int(p[2]), Int(p[1]), Int(p[0]))
    }

    /// Frames, summed over `CMSampleBufferGetNumSamples` rather than counted per
    /// buffer: a compressed read of the qa clip vends 388 buffers for 384
    /// frames, four of them empty. `AVAssetTrack.asset` is weak and
    /// `TrackReader` reads it back out, hence the held asset.
    static func sampleCount(_ url: URL, _ type: AVMediaType) async throws -> Int {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: type).first else { return 0 }
        let r = try TrackReader.compressed(track: t)
        try r.start()
        var n = 0
        while let sb = r.next() { n += CMSampleBufferGetNumSamples(sb) }
        try r.throwIfFailed()
        return n
    }

    /// SHA-256 over the concatenated compressed payload, exactly as
    /// `PassthroughTests` does it: the container is irrelevant, the bytes are not.
    static func digest(_ url: URL, _ type: AVMediaType) async throws -> (hash: String, bytes: Int) {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: type).first else { return ("", 0) }
        let r = try TrackReader.compressed(track: t)
        try r.start()
        var hasher = SHA256()
        var bytes = 0
        while let sb = r.next() {
            guard let bb = CMSampleBufferGetDataBuffer(sb) else { continue }
            var len = 0
            var ptr: UnsafeMutablePointer<CChar>?
            if CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil,
                                           totalLengthOut: &len, dataPointerOut: &ptr) == noErr,
               let ptr {
                hasher.update(bufferPointer: UnsafeRawBufferPointer(start: ptr, count: len))
                bytes += len
            }
        }
        try r.throwIfFailed()
        return (hasher.finalize().map { String(format: "%02x", $0) }.joined(), bytes)
    }

    static func audioDigest(_ url: URL) async throws -> (hash: String, bytes: Int) {
        try await digest(url, .audio)
    }

    /// What a concat has to preserve, read off the track rather than the movie:
    /// the movie's own duration is the longest track's, so an audio track that
    /// runs 17 ms past the picture would mask a dropped video segment.
    static func videoTrackInfo(_ url: URL) async throws
    -> (start: Double, duration: Double, size: CGSize, transform: CGAffineTransform) {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: .video).first else {
            return (0, 0, .zero, .identity)
        }
        let (range, size, transform) = try await (t.load(.timeRange), t.load(.naturalSize),
                                                  t.load(.preferredTransform))
        return (range.start.seconds, range.duration.seconds, size, transform)
    }

    /// Earliest **presentation** time in the track's own media, in ms.
    /// `copyNextSampleBuffer` walks a compressed track in decode order and a
    /// reordered stream's first decoded sample is not its first displayed one,
    /// so this scans rather than peeking. VideoToolbox writes the reorder delay
    /// into the media and cancels it with an edit list, so a healthy file from
    /// this encoder reads a small non-zero number here and 0 for
    /// `videoTrackInfo().start` — which is why both are checked.
    static func minVideoPTS(_ url: URL) async throws -> Int64 {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: .video).first else { return -1 }
        let r = try TrackReader.compressed(track: t)
        try r.start()
        var lowest = Int64.max
        while let sb = r.next() {
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            guard pts.isNumeric else { continue }
            lowest = min(lowest, pts.value * 1000 / Int64(pts.timescale))
        }
        try r.throwIfFailed()
        return lowest
    }

    /// `(ptsMs, chroma)` per decoded frame. `chroma` is the mean distance of Cb
    /// and Cr from neutral 128 over a subsampled grid — the cheapest measure of
    /// "was this frame greyed", and one that survives an H.264 round trip:
    /// the QA clip's own frames read ~15, a greyed frame reads under 2.
    static func chromaByFrame(_ url: URL) async throws -> [(ms: Int64, chroma: Double)] {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: .video).first else { return [] }
        let r = try TrackReader.decodedVideo(track: t)
        try r.start()
        var out: [(ms: Int64, chroma: Double)] = []
        while let sb = r.next() {
            guard let px = CMSampleBufferGetImageBuffer(sb) else { continue }
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            out.append((pts.value * 1000 / Int64(pts.timescale), chromaDeviation(px)))
        }
        try r.throwIfFailed()
        return out
    }

    static func chromaDeviation(_ b: CVPixelBuffer) -> Double {
        CVPixelBufferLockBaseAddress(b, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(b, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddressOfPlane(b, 1)?
            .assumingMemoryBound(to: UInt8.self) else { return -1 }
        let rowBytes = CVPixelBufferGetBytesPerRowOfPlane(b, 1)
        let w = CVPixelBufferGetWidthOfPlane(b, 1), h = CVPixelBufferGetHeightOfPlane(b, 1)
        var sum = 0.0, n = 0
        for y in stride(from: 0, to: h, by: 8) {
            let row = base + y * rowBytes
            for x in stride(from: 0, to: w, by: 8) {
                sum += abs(Double(row[x * 2]) - 128) + abs(Double(row[x * 2 + 1]) - 128)
                n += 1
            }
        }
        return n > 0 ? sum / Double(n) : -1
    }

    /// A synthetic H.264 source written through `OutputWriter`, so it carries
    /// the same encoder settings the render pass produces — reordered frames and
    /// a 2 s keyframe interval — which is what makes it a real seam fixture and
    /// not merely a file. 30 fps on a 600 timescale, so frame `i` lands exactly
    /// on `i * 1000 / 30` ms and the cut arithmetic is exact.
    ///
    /// Flat and saturated: luma walks per frame so consecutive frames differ and
    /// the encoder has something to predict, chroma stays fixed so `censored`
    /// versus `untouched` is one subtraction away.
    ///
    /// - Parameter tickStride: 600-timescale ticks between frames, i.e. `600 /
    ///   fps`. The default 20 is the 30 fps every seam test wants. A larger
    ///   value buys **duration without frames**, which is the only affordable
    ///   way to build a source past `Checkpoint.longSourceThresholdMs`: 31
    ///   minutes at 30 fps is 55 800 frames to encode and then decode again.
    /// - Parameter timescale: 600 with `tickStride` 20 is the 30 fps default.
    ///   Pass **30000 / 1001** for true 29.97, which 600 cannot express
    ///   (600/29.97 = 20.02) — and which is the only frame rate where a
    ///   5-minute segment cut never lands on a frame.
    static func syntheticClip(_ url: URL, size: CGSize, frames: Int,
                              rotation: CGAffineTransform = .identity,
                              tickStride: Int64 = 20,
                              timescale: CMTimeScale = 600) async throws {
        let info = MediaSource.VideoInfo(
            naturalSize: size,
            transform: VideoTransform(preferredTransform: rotation, naturalSize: size),
            nominalFrameRate: Float(Double(timescale) / Double(tickStride)),
            estimatedBitrate: 2_000_000,
            codec: kCMVideoCodecType_H264, isHDR: false,
            naturalTimeScale: timescale, formatDescription: nil)
        let w = try OutputWriter(url: url)
        w.addEncodedVideo(info, bitrate: 2_000_000)
        try w.start()
        nonisolated(unsafe) let sink = try #require(w.pixelAdaptor)
        let input = try #require(w.videoInput)
        let n = Confined(0)
        try await pump(input, label: "synthetic") {
            guard n.v < frames else { return false }
            guard let pool = sink.pixelBufferPool else {
                throw MediaError.writerFailed("adaptor has no pixel buffer pool")
            }
            var px: CVPixelBuffer?
            guard CVPixelBufferPoolCreatePixelBuffer(nil, pool, &px) == kCVReturnSuccess,
                  let px else { throw MediaError.writerFailed("pool exhausted") }
            fill(px, y: UInt8(60 + (n.v * 7) % 140), cb: 100, cr: 170)
            guard sink.append(px, withPresentationTime: CMTime(value: Int64(n.v) * tickStride,
                                                              timescale: timescale))
            else { throw MediaError.writerFailed("append frame \(n.v)") }
            n.v += 1
            return true
        }
        try await w.finish()
    }

    static func fill(_ b: CVPixelBuffer, y: UInt8, cb: UInt8, cr: UInt8) {
        CVPixelBufferLockBaseAddress(b, [])
        defer { CVPixelBufferUnlockBaseAddress(b, []) }
        guard let luma = CVPixelBufferGetBaseAddressOfPlane(b, 0)?
                .assumingMemoryBound(to: UInt8.self),
              let chroma = CVPixelBufferGetBaseAddressOfPlane(b, 1)?
                .assumingMemoryBound(to: UInt8.self) else { return }
        let w = CVPixelBufferGetWidthOfPlane(b, 0), h = CVPixelBufferGetHeightOfPlane(b, 0)
        let lumaRow = CVPixelBufferGetBytesPerRowOfPlane(b, 0)
        for row in 0..<h { (luma + row * lumaRow).update(repeating: y, count: w) }
        let chromaRow = CVPixelBufferGetBytesPerRowOfPlane(b, 1)
        for row in 0..<(h / 2) {
            let p = chroma + row * chromaRow
            for col in 0..<(w / 2) { p[col * 2] = cb; p[col * 2 + 1] = cr }
        }
    }

    /// Presentation times of the samples a decoder can start from. A cut that
    /// lands on one of these never exercises the pre-roll, so the seam test
    /// checks its own fixture with this rather than trusting the encoder.
    static func syncSampleTimesMs(_ url: URL) async throws -> [Int64] {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let t = try await asset.loadTracks(withMediaType: .video).first else { return [] }
        let r = try TrackReader.compressed(track: t)
        try r.start()
        var out: [Int64] = []
        while let sb = r.next() {
            // The attachment is present only on samples that are NOT sync
            // points, so its absence is the positive answer.
            let attachments = CMSampleBufferGetSampleAttachmentsArray(sb, createIfNecessary: false)
                as? [[CFString: Any]]
            let notSync = attachments?.first?[kCMSampleAttachmentKey_NotSync] as? Bool ?? false
            let pts = CMSampleBufferGetPresentationTimeStamp(sb)
            if !notSync, pts.isNumeric { out.append(pts.value * 1000 / Int64(pts.timescale)) }
        }
        try r.throwIfFailed()
        return out
    }

    /// A short `.m4a` cut from `url`'s own AAC. Standing in for the audio pass's
    /// output: same codec so it muxes, provably fewer bytes so "the replacement
    /// was used" needs no trust.
    static func truncatedAudio(from url: URL, info: MediaSource.AudioInfo,
                               to out: URL, samples: Int) async throws {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        let track = try #require(try await asset.loadTracks(withMediaType: .audio).first)
        let w = try OutputWriter(url: out, fileType: .m4a)
        w.addPassthroughAudio(info)
        try w.start()
        let reader = try TrackReader.compressed(track: track)
        try reader.start()
        nonisolated(unsafe) let rd = reader
        nonisolated(unsafe) let sink = try #require(w.audioInput)
        let n = Confined(0)
        try await pump(sink, label: "truncate") {
            guard n.v < samples, let sb = rd.next() else { return false }
            _ = sink.append(sb)
            n.v += 1
            return true
        }
        try await w.finish()
    }

    static func videoDigest(_ url: URL) async throws -> String {
        try await digest(url, .video).hash
    }
}
