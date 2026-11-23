import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import CoreMedia
import CoreText
import CoreVideo
import Foundation

/// The two-second outro appended after the last frame of a re-encoded video:
/// the bowl draws itself, the drop lands in it, the line rises underneath.
///
/// A pure function of `t`, ported from `drawFrame` in
/// `docs/endcard/endcard-mockup.html` — every number below is that file's, so a
/// change to the look starts there. The card is drawn **upright** with
/// CoreGraphics and CoreText (both platforms, and CoreText is what shapes the
/// Arabic line), then turned into stored orientation in Core Image: the output
/// keeps its rotation as track metadata like every other frame, so a card drawn
/// straight into the stored buffer would end a portrait film sideways.
///
/// Silent: the audio track simply ends before the picture does.
///
/// One instance per render, used from the writer's serial pump queue only.
final class EndCard: @unchecked Sendable {
    static let duration = 2.0

    /// The source's own rate when it is an ordinary one; 30 otherwise. A 1 fps
    /// timelapse would show the animation as two stills, and 120 fps slow-mo
    /// would pay four times the frames for nothing a viewer sees.
    static func frameRate(source: Float) -> Double {
        (23...60).contains(source) ? Double(source) : 30
    }

    let fps: Double
    var frameCount: Int { Int((Self.duration * fps).rounded()) }

    private let size: CGSize
    private let storedExtent: CGRect
    /// Core Image upright → Core Image stored. Both ends are y-up, the
    /// rotation in between is the track's (top-left) one.
    private let toStored: CGAffineTransform
    private let bitmap: CGContext
    private let arabic: CTLine
    private let english: CTLine

    init(transform: VideoTransform, sourceFrameRate: Float) {
        fps = Self.frameRate(source: sourceFrameRate)
        size = transform.uprightSize
        storedExtent = CGRect(origin: .zero, size: transform.storedSize)
        let flipUpright = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0, ty: size.height)
        let flipStored = CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: 0,
                                           ty: transform.storedSize.height)
        toStored = flipUpright.concatenating(transform.toUpright.inverted()).concatenating(flipStored)

        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        // `CGContext` only fails on a nonsensical size, and a video track has
        // already been through the writer's own size checks by now.
        bitmap = CGContext(data: nil, width: max(1, Int(size.width)), height: max(1, Int(size.height)),
                           bitsPerComponent: 8, bytesPerRow: 0, space: space,
                           bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!

        let s = min(size.width, size.height)
        arabic = Self.line("نُقّي بتطبيق نقي", font: "thmanyahsans-Bold", size: s * 0.082)
        english = Self.line("Filtered with Naqi", font: "thmanyahsans-Medium", size: s * 0.046,
                            kern: s * 0.004)
    }

    /// Frame `index` of the card, in stored orientation, dissolving in from
    /// `last` — the final frame of the video — over its first 0.15 s.
    func image(frame index: Int, over last: CVPixelBuffer?) -> CIImage {
        let pose = Pose(t: Double(index) / fps)
        draw(pose)
        guard let cg = bitmap.makeImage() else { return CIImage(color: .black).cropped(to: storedExtent) }
        let card = CIImage(cgImage: cg).transformed(by: toStored).cropped(to: storedExtent)
        guard pose.fade < 1, let last else { return card }
        let dissolve = CIFilter.dissolveTransition()
        dissolve.inputImage = CIImage(cvPixelBuffer: last)
        dissolve.targetImage = card
        dissolve.time = Float(pose.fade)
        return (dissolve.outputImage ?? card).cropped(to: storedExtent)
    }

    /// Presentation time of frame `index`, for a card that starts at `start`.
    func time(of index: Int, after start: CMTime) -> CMTime {
        CMTimeAdd(start, CMTime(seconds: Double(index) / fps, preferredTimescale: 90_000))
    }

    // MARK: - Timeline

    /// Every animated quantity at one instant. Kept apart from the drawing so
    /// the timing is testable without a bitmap.
    struct Pose: Equatable {
        /// Fraction of the bowl drawn, 0…1.
        let arc: Double
        /// Raw glow progress; the radius and alpha ease from it.
        let glow: Double
        /// Raw drop progress; alpha and the overshooting fall ease from it.
        let drop: Double
        let arabic: Double
        let english: Double
        /// 0 is the video's last frame, 1 is the card.
        let fade: Double

        init(t: Double) {
            arc = outCubic(seg(t, 0.05, 0.55))
            glow = seg(t, 0.6, 1.3)
            drop = seg(t, 0.35, 0.7)
            arabic = outCubic(seg(t, 0.7, 1.1))
            english = outCubic(seg(t, 0.82, 1.22))
            fade = seg(t, 0, 0.15)
        }

        var dropAlpha: Double { clamp(drop * 3) }
        /// Mark units, y-down: negative is above the resting place.
        var dropOffset: Double { -18 * (1 - outBack(drop)) }
    }

    static func clamp(_ x: Double) -> Double { min(1, max(0, x)) }
    static func seg(_ t: Double, _ a: Double, _ b: Double) -> Double { clamp((t - a) / (b - a)) }
    static func outCubic(_ x: Double) -> Double { 1 - pow(1 - x, 3) }
    static func outBack(_ x: Double) -> Double {
        let c = 1.9
        return 1 + (c + 1) * pow(x - 1, 3) + c * pow(x - 1, 2)
    }

    // MARK: - Drawing

    private static let background = CGColor(srgbRed: 12 / 255, green: 21 / 255, blue: 18 / 255, alpha: 1)
    private static let jade = CGColor(srgbRed: 85 / 255, green: 195 / 255, blue: 161 / 255, alpha: 1)
    private static let paper = CGColor(srgbRed: 245 / 255, green: 247 / 255, blue: 243 / 255, alpha: 1)

    /// The mark in the mockup's units — `NaqiMark`'s geometry, rescaled: the
    /// bowl is the lower half of this circle, the drop is the triangle.
    private static let bowlCenter = CGPoint(x: 54, y: 57.09)
    private static let bowlRadius: CGFloat = 13.36
    private static let markCenter = CGPoint(x: 54, y: 53.55)
    /// Rebuilt per frame: three points, and `CGPath` is not `Sendable`.
    private static var drop: CGPath {
        let p = CGMutablePath()
        p.move(to: CGPoint(x: 49.36, y: 34.31))
        p.addLine(to: CGPoint(x: 49.36, y: 46.97))
        p.addLine(to: CGPoint(x: 61.17, y: 40.64))
        p.closeSubpath()
        return p
    }

    private func draw(_ pose: Pose) {
        let c = bitmap
        let w = size.width, h = size.height, s = min(w, h)
        c.saveGState()
        defer { c.restoreGState() }
        // y-down, like the canvas the design was drawn on.
        c.translateBy(x: 0, y: h)
        c.scaleBy(x: 1, y: -1)

        c.setFillColor(Self.background)
        c.fill(CGRect(x: 0, y: 0, width: w, height: h))

        let unit = s * 0.26 / 45.7
        let center = CGPoint(x: w / 2, y: h / 2 - s * 0.09)

        // Glow pulse when the drop lands.
        if pose.glow > 0 {
            let radius = s * (0.18 + 0.12 * Self.outCubic(pose.glow))
            let alpha = 0.22 * (1 - pose.glow * 0.55)
            if let gradient = CGGradient(colorsSpace: CGColorSpace(name: CGColorSpace.sRGB),
                                         colors: [Self.jade.copy(alpha: alpha) ?? Self.jade,
                                                  Self.jade.copy(alpha: 0) ?? Self.jade] as CFArray,
                                         locations: [0, 1]) {
                c.drawRadialGradient(gradient, startCenter: center, startRadius: 0,
                                     endCenter: center, endRadius: radius, options: [])
            }
        }

        c.saveGState()
        c.translateBy(x: center.x, y: center.y)
        c.scaleBy(x: unit, y: unit)
        c.translateBy(x: -Self.markCenter.x, y: -Self.markCenter.y)
        c.setStrokeColor(Self.jade)
        c.setFillColor(Self.jade)
        c.setLineCap(.round)
        c.setLineJoin(.round)

        // The bowl, from its left tip down through the bottom to the right.
        // Decreasing angle in y-down space is exactly that sweep.
        if pose.arc > 0 {
            c.addArc(center: Self.bowlCenter, radius: Self.bowlRadius,
                     startAngle: .pi, endAngle: .pi * (1 - pose.arc), clockwise: true)
            c.setLineWidth(7.88)
            c.strokePath()
        }

        // The drop: filled and stroked as two paints, as the canvas does it —
        // the stroke is what rounds its corners.
        if pose.drop > 0 {
            c.saveGState()
            c.setAlpha(pose.dropAlpha)
            c.translateBy(x: 0, y: pose.dropOffset)
            c.addPath(Self.drop)
            c.fillPath()
            c.addPath(Self.drop)
            c.setLineWidth(3.23)
            c.strokePath()
            c.restoreGState()
        }
        c.restoreGState()

        if pose.arabic > 0 {
            text(arabic, alpha: pose.arabic, centerX: center.x,
                 baseline: center.y + s * 0.27 + s * 0.04 * (1 - pose.arabic))
        }
        if pose.english > 0 {
            text(english, alpha: 0.62 * pose.english, centerX: center.x,
                 baseline: center.y + s * 0.355 + s * 0.04 * (1 - pose.english))
        }
    }

    private func text(_ line: CTLine, alpha: Double, centerX: CGFloat, baseline: CGFloat) {
        let c = bitmap
        let width = CTLineGetTypographicBounds(line, nil, nil, nil)
        c.saveGState()
        c.setAlpha(alpha)
        // The context is flipped, so the glyphs have to be flipped back.
        c.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        c.textPosition = CGPoint(x: centerX - width / 2, y: baseline)
        CTLineDraw(line, c)
        c.restoreGState()
    }

    /// The bundled Thmanyah faces are registered process-wide by `UIAppFonts`
    /// / `ATSApplicationFontsPath`, so CoreText finds them by name. CoreText
    /// also does the bidi run and the Arabic joining and mark placement.
    private static func line(_ string: String, font name: String, size: CGFloat,
                             kern: CGFloat = 0) -> CTLine {
        var attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): CTFontCreateWithName(name as CFString, size, nil),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): paper,
        ]
        if kern != 0 { attributes[NSAttributedString.Key(kCTKernAttributeName as String)] = kern }
        return CTLineCreateWithAttributedString(NSAttributedString(string: string, attributes: attributes))
    }
}
