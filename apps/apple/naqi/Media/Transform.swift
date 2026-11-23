import AVFoundation
import CoreGraphics

/// Mapping between the two coordinate spaces every censor rect lives in.
///
/// * **stored** — the pixel buffer as the decoder hands it over: `naturalSize`,
///   origin top-left. This is what Core Image filters and the encoder see.
/// * **upright** — what the viewer sees after `preferredTransform` is applied.
///   This is what Vision and the NSFW gate reason about, because a face is only
///   a face the right way up.
///
/// Android lost frames to getting this backwards on rot-90/270 sources
/// (`prd-video-filter-apple.md` calls it "the rotation landmine"), so the two
/// directions are named, not inferred at call sites, and every rotation is
/// covered by `TransformTests`.
struct VideoTransform: Sendable, Equatable {
    /// stored -> upright.
    let toUpright: CGAffineTransform
    /// Size of the stored buffer.
    let storedSize: CGSize
    /// Size the viewer sees.
    let uprightSize: CGSize

    init(preferredTransform t: CGAffineTransform, naturalSize: CGSize) {
        self.toUpright = t
        self.storedSize = naturalSize
        let mapped = CGRect(origin: .zero, size: naturalSize).applying(t)
        self.uprightSize = CGSize(width: abs(mapped.width), height: abs(mapped.height))
    }

    /// Identity — a source that is already upright.
    static func identity(size: CGSize) -> VideoTransform {
        VideoTransform(preferredTransform: .identity, naturalSize: size)
    }

    /// Same orientation at a different stored size. Normalizing the linear
    /// transform's bounds recalculates the translation used by 90/180/270°
    /// tracks instead of carrying the source dimensions into the output.
    func resized(to size: CGSize) -> VideoTransform {
        let linear = CGAffineTransform(a: toUpright.a, b: toUpright.b,
                                       c: toUpright.c, d: toUpright.d,
                                       tx: 0, ty: 0)
        let bounds = CGRect(origin: .zero, size: size).applying(linear).standardized
        let normalized = CGAffineTransform(a: linear.a, b: linear.b,
                                           c: linear.c, d: linear.d,
                                           tx: -bounds.minX, ty: -bounds.minY)
        return VideoTransform(preferredTransform: normalized, naturalSize: size)
    }

    /// Rotation the source declares, normalised to 0/90/180/270 degrees.
    var rotationDegrees: Int {
        let deg = atan2(toUpright.b, toUpright.a) * 180 / .pi
        return ((Int(deg.rounded()) % 360) + 360) % 360
    }

    /// Upright rect (Vision / gate space) -> stored rect (Core Image / encoder space).
    func storedRect(fromUpright r: CGRect) -> CGRect {
        r.applying(toUpright.inverted())
    }

    /// Stored rect -> upright rect.
    func uprightRect(fromStored r: CGRect) -> CGRect {
        r.applying(toUpright)
    }

    /// Vision reports normalised rects with a **bottom-left** origin over the
    /// image it was handed. Everything downstream is top-left pixels, so the
    /// flip happens exactly here and nowhere else.
    func uprightRectFromVision(_ normalised: CGRect) -> CGRect {
        let w = uprightSize.width, h = uprightSize.height
        return CGRect(x: normalised.minX * w,
                      y: (1 - normalised.maxY) * h,
                      width: normalised.width * w,
                      height: normalised.height * h)
    }
}

extension CGRect {
    /// Grows the rect by `fraction` of its own size on every side, then clamps
    /// to `bounds`. Android pads face boxes 25 % before clamping, so a face at
    /// the frame edge still gets the full pad on its inward sides.
    func padded(by fraction: CGFloat, clampedTo bounds: CGSize) -> CGRect {
        let dx = width * fraction, dy = height * fraction
        return insetBy(dx: -dx, dy: -dy)
            .intersection(CGRect(origin: .zero, size: bounds))
    }
}
