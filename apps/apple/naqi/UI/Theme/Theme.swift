import CoreText
import SwiftUI

/// The Naqi design language, ported from Android `ui/theme/`.
///
/// Concept: filtered water / clarity. **Jade is the interaction colour** —
/// every button, switch, selected state and trust mark. Ink/paper is the
/// reading surface. (The Apple task list describes this as "ink = interaction /
/// jade = video-truth", which inverts the shipped roles; the shipped semantics
/// win, because the app icon and every Android screenshot are built on them.
/// See `docs/apple-port/spec-jobs-ui.md` §6.1.)
///
/// Dynamic/system accent tinting is deliberately **not** adopted: the brand
/// identity has to beat the wallpaper palette (Android `ui/theme/Theme.kt:9`).
enum Naqi {

    // MARK: Semantic colours

    /// Each token resolves per colour scheme. Light values are the Android
    /// light scheme, dark values the `values-night` scheme.
    ///
    /// **The screens are the definition of this palette, not the Android file.**
    /// The full Material role set was ported wholesale and two thirds of it was
    /// never referenced — a container/on-container pair for every role, the
    /// inverse triple, the scrim, the five-rung surface ladder. Each is one
    /// `dyn(light, dark)` line to bring back off `values-night` the day a screen
    /// actually asks for it; carrying them unreferenced only made the eleven
    /// that are real harder to find.
    enum C {
        static let primary = dyn(0x1F6E5A, 0x55C3A1)
        static let onPrimary = dyn(0xFFFFFF, 0x00382A)

        static let error = dyn(0xBA1A1A, 0xFFB4AB)

        static let background = dyn(0xF5F7F3, 0x0C1512)
        static let onSurface = dyn(0x10201C, 0xDEE8E2)
        static let onSurfaceVariant = dyn(0x3F4A45, 0xBEC9C2)

        static let surfaceContainer = dyn(0xECF1ED, 0x182420)
        static let surfaceContainerHighest = dyn(0xE0E7E2, 0x2D3935)

        static let outline = dyn(0x6F7A74, 0x89948D)
        static let outlineVariant = dyn(0xBFC9C3, 0x3F4A45)

        private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
            #if canImport(UIKit)
            Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(rgb: dark) : UIColor(rgb: light) })
            #else
            Color(nsColor: NSColor(name: nil) {
                $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? NSColor(rgb: dark) : NSColor(rgb: light)
            })
            #endif
        }
    }

    // MARK: Typography

    /// Android's type scale (`ui/theme/Type.kt`) set in Thmanyah Sans, the
    /// website's face, at the default size of the nearest system text style so
    /// every slot scales with Dynamic Type. Title slots carry the site's
    /// `h1…h6 { font-feature-settings: "salt" 1 }`. The Android scale has no
    /// 600 weight, so every "SemiBold" slot resolves to Bold.
    enum F {
        /// displaySmall 36 — the Arabic wordmark only.
        static let display = heading("Bold", 34)
        /// titleLarge 22 — top-bar / screen titles.
        static let titleLarge = heading("Bold", 22)
        /// titleMedium 16 — section header, pick-card title, progress stage.
        static let titleMedium = heading("Medium", 17)
        /// titleSmall 14 — every card row title.
        static let titleSmall = heading("Medium", 15)
        /// bodyLarge 16 — app-wide default for any Text without its own font.
        static let body = Font.custom("thmanyahsans-Regular", size: 17, relativeTo: .body)
        /// bodyMedium 14 — failure sentence, dialog body.
        static let bodyMedium = Font.custom("thmanyahsans-Regular", size: 15, relativeTo: .subheadline)
        /// bodySmall 12 — every row description, ETA lines, note lines.
        static let bodySmall = Font.custom("thmanyahsans-Regular", size: 13, relativeTo: .footnote)
        /// labelLarge 14 — primary button label.
        static let labelLarge = Font.custom("thmanyahsans-Bold", size: 15, relativeTo: .subheadline)
        /// labelMedium 12 — trust-seal text, slider value pill.
        static let labelMedium = Font.custom("thmanyahsans-Medium", size: 12, relativeTo: .caption)

        /// SwiftUI has no OpenType-feature API, so `salt` goes in through CoreText.
        // ponytail: Dynamic Type is read once at launch (body-relative), so a text-size
        // change applies on next launch; move to a view modifier reading dynamicTypeSize if that matters.
        private static func heading(_ weight: String, _ size: CGFloat) -> Font {
            #if os(iOS)
            let size = UIFontMetrics.default.scaledValue(for: size)
            #endif
            let salt = [kCTFontOpenTypeFeatureTag: "salt", kCTFontOpenTypeFeatureValue: 1] as [CFString: Any]
            let desc = CTFontDescriptorCreateWithAttributes([
                kCTFontNameAttribute: "thmanyahsans-\(weight)",
                kCTFontFeatureSettingsAttribute: [salt],
            ] as CFDictionary)
            return Font(CTFontCreateWithFontDescriptor(desc, size, nil))
        }
        /// The two label slots carry a wide 0.8 tracking override.
        static let labelTracking: CGFloat = 0.8
    }

    // MARK: Spacing — base unit 4

    enum S {
        static let s1: CGFloat = 4
        static let s2: CGFloat = 8
        static let s3: CGFloat = 12
        static let s4: CGFloat = 16
        static let s5: CGFloat = 24
        static let s6: CGFloat = 32
        /// Screen-edge padding.
        static let gutter: CGFloat = 20
    }

    /// Two radii, because the app draws two things: cards and buttons. The
    /// other four rungs of the Material shape scale were never asked for.
    enum R {
        static let card: CGFloat = 24
        static let button: CGFloat = 20
    }

    /// Depth is a `surfaceContainer` fill plus a 1 pt `outlineVariant` border —
    /// never a shadow. The pick card and the select dot use a heavier 1.5 pt.
    enum Border {
        static let hairline: CGFloat = 1
        static let emphasis: CGFloat = 1.5
        /// Dividers are outlineVariant at 70 % opacity.
        static let dividerOpacity: Double = 0.7
    }

    /// One spring for every state transition in the app. Android uses
    /// `spring(stiffness: 400, dampingRatio: 0.5)`; SwiftUI's response/damping
    /// form of the same underdamped curve.
    static let spring = Animation.spring(response: 0.32, dampingFraction: 0.5)
}

// MARK: - Theme choice

/// Set on the window, not through `.preferredColorScheme`: every `Naqi.C` token
/// is a dynamic UIColor that resolves off the window's trait, sheets included,
/// and `.preferredColorScheme(nil)` is known not to hand control back to the system.
enum AppTheme: String, CaseIterable {
    case system, light, dark

    static let key = "naqi.theme"

    var label: LocalizedStringResource {
        switch self {
        case .system: .themeSystem
        case .light: .themeLight
        case .dark: .themeDark
        }
    }

    @MainActor func apply() {
        #if os(iOS)
        let style: UIUserInterfaceStyle = switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
        for window in allWindows() { window.overrideUserInterfaceStyle = style }
        #else
        NSApp.appearance = switch self {
        case .system: nil
        case .light: NSAppearance(named: .aqua)
        case .dark: NSAppearance(named: .darkAqua)
        }
        #endif
    }
}

/// Runs `change` under a fade on every window, so a theme or language switch
/// dissolves into the new look instead of cutting to it. The CATransition picks
/// up whatever the change commits in this run-loop turn, SwiftUI's re-render included.
// ponytail: macOS cuts; an NSView layer transition would fade it too.
@MainActor func crossfade(_ change: () -> Void) {
    #if os(iOS)
    for window in allWindows() {
        let fade = CATransition()
        fade.type = .fade
        fade.duration = 0.35
        window.layer.add(fade, forKey: "naqi.crossfade")
    }
    #endif
    change()
}

#if os(iOS)
@MainActor private func allWindows() -> [UIWindow] {
    UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
}
#endif

// MARK: - Hex helpers

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

#if canImport(UIKit)
import UIKit
extension UIColor {
    convenience init(rgb: UInt32) {
        self.init(red: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
#else
import AppKit
extension NSColor {
    convenience init(rgb: UInt32) {
        self.init(srgbRed: CGFloat((rgb >> 16) & 0xFF) / 255,
                  green: CGFloat((rgb >> 8) & 0xFF) / 255,
                  blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}
#endif

// MARK: - Shared component styling

extension View {
    /// The app's card treatment: container fill, 24 pt corners, hairline border.
    func naqiCard() -> some View {
        self
            .background(Naqi.C.surfaceContainer, in: .rect(cornerRadius: Naqi.R.card))
            .overlay(
                RoundedRectangle(cornerRadius: Naqi.R.card)
                    .strokeBorder(Naqi.C.outlineVariant, lineWidth: Naqi.Border.hairline)
            )
    }
}
