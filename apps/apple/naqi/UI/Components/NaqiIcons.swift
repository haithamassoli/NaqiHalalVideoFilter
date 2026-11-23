import SwiftUI

/// The hand-built icon set from Android `ui/NaqiIcons.kt`, ported path for path
/// in the same 24×24 viewport. SF Symbols would cover all four, but they sit
/// beside `NaqiMark` — the brand droplet, and genuinely custom — and a set that
/// mixes one hand-drawn path with three system glyphs stops reading as a
/// family. System chrome (back, close, overflow) still uses SF Symbols.
///
/// The standalone `droplet` glyph the Android set carried is gone: `NaqiMark`
/// is the only droplet any screen draws, and the two outlines were never the
/// same shape anyway.
struct NaqiIcon: Shape {
    let glyph: Glyph

    init(_ glyph: Glyph) { self.glyph = glyph }

    enum Glyph: Sendable { case video, musicOff, shield, check }

    /// None of these four is directional, and SwiftUI mirrors a `Shape` in a
    /// right-to-left layout by default — which turned the tick into a backwards
    /// tick and flipped the slash on the music-off mark in Arabic. Only the back
    /// arrow should mirror, and that one is a system symbol.
    var layoutDirectionBehavior: LayoutDirectionBehavior { .fixed }

    func path(in rect: CGRect) -> Path {
        var p = Path()
        switch glyph {
        case .video:
            p.move(to: CGPoint(x: 5, y: 6.5))
            p.addLine(to: CGPoint(x: 13, y: 6.5))
            p.addQuadCurve(to: CGPoint(x: 15, y: 8.5), control: CGPoint(x: 15, y: 6.5))
            p.addLine(to: CGPoint(x: 15, y: 15.5))
            p.addQuadCurve(to: CGPoint(x: 13, y: 17.5), control: CGPoint(x: 15, y: 17.5))
            p.addLine(to: CGPoint(x: 5, y: 17.5))
            p.addQuadCurve(to: CGPoint(x: 3, y: 15.5), control: CGPoint(x: 3, y: 17.5))
            p.addLine(to: CGPoint(x: 3, y: 8.5))
            p.addQuadCurve(to: CGPoint(x: 5, y: 6.5), control: CGPoint(x: 3, y: 6.5))
            p.closeSubpath()
            p.move(to: CGPoint(x: 15, y: 10))
            p.addLine(to: CGPoint(x: 20.5, y: 7))
            p.addQuadCurve(to: CGPoint(x: 21, y: 7.6), control: CGPoint(x: 21, y: 6.8))
            p.addLine(to: CGPoint(x: 21, y: 16.4))
            p.addQuadCurve(to: CGPoint(x: 20.5, y: 17), control: CGPoint(x: 21, y: 17.2))
            p.addLine(to: CGPoint(x: 15, y: 14))
            p.closeSubpath()

        case .musicOff:
            p.addRect(CGRect(x: 6.2, y: 10, width: 1.6, height: 4))
            p.addRect(CGRect(x: 10.2, y: 6.5, width: 1.6, height: 11))
            p.addRect(CGRect(x: 14.2, y: 9, width: 1.6, height: 6))
            p.move(to: CGPoint(x: 4, y: 18.6))
            p.addLine(to: CGPoint(x: 18.6, y: 4))
            p.addLine(to: CGPoint(x: 20, y: 5.4))
            p.addLine(to: CGPoint(x: 5.4, y: 20))
            p.closeSubpath()

        case .shield:
            p.move(to: CGPoint(x: 12, y: 2.5))
            p.addLine(to: CGPoint(x: 19.5, y: 5.5))
            p.addLine(to: CGPoint(x: 19.5, y: 11.2))
            p.addCurve(to: CGPoint(x: 12, y: 21),
                       control1: CGPoint(x: 19.5, y: 15.9), control2: CGPoint(x: 16.4, y: 19.5))
            p.addCurve(to: CGPoint(x: 4.5, y: 11.2),
                       control1: CGPoint(x: 7.6, y: 19.5), control2: CGPoint(x: 4.5, y: 15.9))
            p.addLine(to: CGPoint(x: 4.5, y: 5.5))
            p.closeSubpath()

        case .check:
            p.move(to: CGPoint(x: 9.8, y: 16.2))
            p.addLine(to: CGPoint(x: 5.6, y: 12))
            p.addLine(to: CGPoint(x: 4.2, y: 13.4))
            p.addLine(to: CGPoint(x: 9.8, y: 19))
            p.addLine(to: CGPoint(x: 20, y: 8.8))
            p.addLine(to: CGPoint(x: 18.6, y: 7.4))
            p.closeSubpath()
        }
        return p.applying(Self.fit(24, in: rect))
    }

    /// Uniform scale + centre, so a glyph never stretches in a non-square frame.
    static func fit(_ viewport: CGFloat, in rect: CGRect) -> CGAffineTransform {
        let s = min(rect.width, rect.height) / viewport
        return CGAffineTransform(translationX: rect.minX + (rect.width - viewport * s) / 2,
                                 y: rect.minY + (rect.height - viewport * s) / 2)
            .scaledBy(x: s, y: s)
    }
}

/// The brand mark: the bowl of ن whose dot is a play triangle — the same two
/// paths the app icon is built from, copied from `branding/naqi-icon.svg` in
/// that file's 1024 viewport. One shape rather than two, so a single `.fill`
/// covers both: the icon's white bowl and mint triangle only read against its
/// dark gradient, and every in-app placement sits on paper.
struct NaqiMark: Shape {
    /// A mirrored brand mark is a different brand mark.
    var layoutDirectionBehavior: LayoutDirectionBehavior { .fixed }

    func path(in rect: CGRect) -> Path {
        // Bowl: the lower half of a circle, stroked with round caps.
        var bowl = Path()
        bowl.addArc(center: CGPoint(x: 512, y: 556), radius: 190,
                    startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
        var p = bowl.strokedPath(StrokeStyle(lineWidth: 112, lineCap: .round))

        // The dot, as a play triangle. Filled *and* stroked exactly as the SVG
        // does it — the stroke is what rounds the three corners. Overlapping
        // subpaths union under the non-zero fill rule SwiftUI fills with.
        var dot = Path()
        dot.move(to: CGPoint(x: 446, y: 232))
        dot.addLine(to: CGPoint(x: 446, y: 412))
        dot.addLine(to: CGPoint(x: 614, y: 322))
        dot.closeSubpath()
        p.addPath(dot)
        p.addPath(dot.strokedPath(StrokeStyle(lineWidth: 46, lineJoin: .round)))

        // Fit the ink, not the viewport: the SVG's generous margin is app-icon
        // padding, and inside a 22pt toolbar frame it would shrink the mark to
        // nothing. `NaqiIcon.fit` can't do this — its viewport is square.
        let ink = p.boundingRect
        let s = min(rect.width / ink.width, rect.height / ink.height)
        return p.applying(CGAffineTransform(translationX: rect.midX, y: rect.midY)
            .scaledBy(x: s, y: s)
            .translatedBy(x: -ink.midX, y: -ink.midY))
    }
}
