import Testing
import AVFoundation
import CoreGraphics
@testable import naqi

/// The rotation landmine, pinned. A rect that survives upright -> stored ->
/// upright unchanged, for every rotation, is the whole contract: Vision reports
/// in upright space, Core Image blurs in stored space, and getting it backwards
/// blurs the wrong corner of a portrait video.
@Suite("Video transform")
struct TransformTests {

    /// The four transforms AVFoundation actually produces for camera video.
    /// `naturalSize` is always the stored (landscape-ish) size; a rot-90 source
    /// displays with width and height swapped.
    static let cases: [(name: String, t: CGAffineTransform, stored: CGSize, upright: CGSize)] = [
        ("rot-0",   .identity,                                    CGSize(width: 1920, height: 1080), CGSize(width: 1920, height: 1080)),
        ("rot-90",  CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: 1080, ty: 0),
                                                                  CGSize(width: 1920, height: 1080), CGSize(width: 1080, height: 1920)),
        ("rot-180", CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: 1920, ty: 1080),
                                                                  CGSize(width: 1920, height: 1080), CGSize(width: 1920, height: 1080)),
        ("rot-270", CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: 0, ty: 1920),
                                                                  CGSize(width: 1920, height: 1080), CGSize(width: 1080, height: 1920)),
    ]

    @Test("upright size and declared rotation", arguments: cases.indices)
    func uprightSize(i: Int) {
        let c = Self.cases[i]
        let vt = VideoTransform(preferredTransform: c.t, naturalSize: c.stored)
        #expect(vt.uprightSize == c.upright, "\(c.name)")
        #expect(vt.storedSize == c.stored, "\(c.name)")
    }

    @Test("declared rotation degrees", arguments: zip(cases.indices, [0, 90, 180, 270]))
    func rotationDegrees(i: Int, expected: Int) {
        let c = Self.cases[i]
        let vt = VideoTransform(preferredTransform: c.t, naturalSize: c.stored)
        #expect(vt.rotationDegrees == expected, "\(c.name)")
    }

    @Test("upright -> stored -> upright is identity", arguments: cases.indices)
    func roundTrip(i: Int) {
        let c = Self.cases[i]
        let vt = VideoTransform(preferredTransform: c.t, naturalSize: c.stored)
        // A face box in the top-left quadrant of the upright frame.
        let upright = CGRect(x: 100, y: 60, width: 240, height: 300)
        let stored = vt.storedRect(fromUpright: upright)
        let back = vt.uprightRect(fromStored: stored)
        #expect(abs(back.minX - upright.minX) < 0.001, "\(c.name) x")
        #expect(abs(back.minY - upright.minY) < 0.001, "\(c.name) y")
        #expect(abs(back.width - upright.width) < 0.001, "\(c.name) w")
        #expect(abs(back.height - upright.height) < 0.001, "\(c.name) h")
    }

    @Test("resized transforms preserve orientation and fit the new buffer", arguments: cases.indices)
    func resized(i: Int) {
        let c = Self.cases[i]
        let source = VideoTransform(preferredTransform: c.t, naturalSize: c.stored)
        let resized = source.resized(to: CGSize(width: 1280, height: 720))
        #expect(resized.rotationDegrees == source.rotationDegrees)
        #expect(resized.storedSize == CGSize(width: 1280, height: 720))
        let expectedUpright = source.rotationDegrees == 90 || source.rotationDegrees == 270
            ? CGSize(width: 720, height: 1280) : CGSize(width: 1280, height: 720)
        #expect(resized.uprightSize == expectedUpright)
    }

    /// A rect inside the upright frame must land inside the stored frame — if
    /// it does not, the blur is being drawn off-canvas.
    @Test("mapped rect stays inside the stored buffer", arguments: cases.indices)
    func staysInBounds(i: Int) {
        let c = Self.cases[i]
        let vt = VideoTransform(preferredTransform: c.t, naturalSize: c.stored)
        let storedBounds = CGRect(origin: .zero, size: c.stored)
        for r in [CGRect(x: 0, y: 0, width: 50, height: 50),
                  CGRect(x: c.upright.width - 50, y: c.upright.height - 50, width: 50, height: 50),
                  CGRect(x: c.upright.width / 2 - 25, y: c.upright.height / 2 - 25, width: 50, height: 50)] {
            let s = vt.storedRect(fromUpright: r).standardized
            #expect(storedBounds.insetBy(dx: -0.5, dy: -0.5).contains(s),
                    "\(c.name): upright \(r) -> stored \(s) outside \(storedBounds)")
        }
    }

    /// A rot-90 source maps the upright top-left corner to a *different* stored
    /// corner than rot-0 does. Without this, an identity mapping would pass
    /// every test above by accident.
    @Test("rotation actually moves the rect")
    func rotationIsNotIdentity() {
        let stored = CGSize(width: 1920, height: 1080)
        let corner = CGRect(x: 0, y: 0, width: 100, height: 100)
        let r0 = VideoTransform(preferredTransform: Self.cases[0].t, naturalSize: stored)
            .storedRect(fromUpright: corner).standardized
        let r90 = VideoTransform(preferredTransform: Self.cases[1].t, naturalSize: stored)
            .storedRect(fromUpright: corner).standardized
        #expect(r0 != r90, "rot-90 mapped the same as rot-0 — the transform is being ignored")
        #expect(r0.origin == .zero)
        // Upright top-left is the stored bottom-left under a 90 degree turn.
        #expect(abs(r90.minY - (stored.height - 100)) < 0.001, "got \(r90)")
    }

    @Test("Vision bottom-left normalised rect flips to top-left pixels")
    func visionFlip() {
        let vt = VideoTransform.identity(size: CGSize(width: 1000, height: 500))
        // Vision box occupying the TOP half of the image reports maxY = 1.0.
        let top = vt.uprightRectFromVision(CGRect(x: 0.1, y: 0.5, width: 0.2, height: 0.5))
        #expect(top.minY == 0, "top-of-image should map to y=0, got \(top)")
        #expect(top.minX == 100)
        #expect(top.height == 250)
        // ...and a box at the bottom reports minY = 0.
        let bottom = vt.uprightRectFromVision(CGRect(x: 0, y: 0, width: 0.2, height: 0.5))
        #expect(bottom.minY == 250, "bottom-of-image should map to y=h/2, got \(bottom)")
    }

    @Test("25% padding grows the box and clamps to frame")
    func padding() {
        let frame = CGSize(width: 1000, height: 1000)
        let mid = CGRect(x: 400, y: 400, width: 100, height: 100).padded(by: 0.25, clampedTo: frame)
        #expect(mid == CGRect(x: 375, y: 375, width: 150, height: 150))

        // A box at the edge keeps its full inward pad, clipped only outward.
        let edge = CGRect(x: 0, y: 0, width: 100, height: 100).padded(by: 0.25, clampedTo: frame)
        #expect(edge == CGRect(x: 0, y: 0, width: 125, height: 125))
    }
}
