import SwiftUI

// Automation ids read `<kind>.<name>`: `toggle.grayscale`, `select.destPhotos`,
// `slider.strictness`, `action.start`, plus the fixed `seal.trust` and
// `progress.wavy`. A component given no `id` falls back to its title's
// string-catalog key, so every call site is addressable before it is named.

// MARK: - Card

/// Container fill, 24 pt corners, hairline border. Depth in Naqi is never a
/// shadow — Android has no elevation tokens at all (spec §6.4).
struct NaqiCard<Content: View>: View {
    var padding: CGFloat = Naqi.S.s4
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(padding)
            .naqiCard()
    }
}

/// A 1 px rule inset 16 pt on both sides — inset so it reads as a grouping, not
/// a cut.
struct NaqiRowDivider: View {
    var body: some View {
        Rectangle()
            .fill(Naqi.C.outlineVariant.opacity(Naqi.Border.dividerOpacity))
            .frame(height: 1)
            .padding(.horizontal, Naqi.S.s4)
    }
}

/// `titleMedium` eyebrow above a card.
struct SectionHeader<Trailing: View>: View {
    let title: LocalizedStringResource
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: Naqi.S.s2) {
            Text(title)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
            Spacer(minLength: 0)
            trailing
        }
        .padding(.leading, Naqi.S.s1)
        .padding(.bottom, Naqi.S.s2)
    }
}

extension SectionHeader where Trailing == EmptyView {
    init(_ title: LocalizedStringResource) {
        self.init(title: title) { EmptyView() }
    }
}

// MARK: - Trust seal

/// The centred capsule at the very top of step 1. The "·" is drawn here and
/// baked into neither string — the two halves are separate resources so each
/// translates on its own.
struct TrustSeal: View {
    var body: some View {
        HStack(spacing: Naqi.S.s2) {
            Text(.pickSealOnDevice)
            Text(verbatim: "·").foregroundStyle(Naqi.C.primary.opacity(0.55))
            Text(.pickSealPrivate)
        }
        .font(Naqi.F.labelMedium)
        .tracking(Naqi.F.labelTracking)
        .foregroundStyle(Naqi.C.primary)
        .padding(.horizontal, Naqi.S.s4)
        .padding(.vertical, Naqi.S.s2)
        .background(Naqi.C.primary.opacity(0.08), in: .capsule)
        .overlay(Capsule().strokeBorder(Naqi.C.primary.opacity(0.22), lineWidth: Naqi.Border.hairline))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("seal.trust")
    }
}

// MARK: - Rows

/// One row of an `OperationCard`. The **whole row** is the toggle target, not
/// just the switch, and `desc` is rendered at full emphasis whether the row is
/// on or off — a greyed-out description of what is happening would be as much
/// of a lie as the wrong sentence.
struct ToggleTile: View {
    let icon: NaqiIcon.Glyph?
    let title: LocalizedStringResource
    var desc: LocalizedStringResource?
    var id: String?
    @Binding var isOn: Bool

    /// Tied to the title's own text style: a 42 pt square left at 42 pt beside
    /// a 50 pt title reads as a bullet, not as an icon. The row itself has no
    /// fixed height, so it just grows.
    @ScaledMetric(relativeTo: .subheadline) private var tile: CGFloat = 42
    @ScaledMetric(relativeTo: .subheadline) private var glyph: CGFloat = 22

    var body: some View {
        Button {
            withAnimation(Naqi.spring) { isOn.toggle() }
        } label: {
            HStack(spacing: 0) {
                if let icon {
                    ZStack {
                        RoundedRectangle(cornerRadius: Naqi.R.button)
                            .fill(isOn ? Naqi.C.primary.opacity(0.16) : Naqi.C.surfaceContainerHighest)
                        NaqiIcon(icon)
                            .fill(isOn ? Naqi.C.primary : Naqi.C.onSurfaceVariant)
                            .frame(width: glyph, height: glyph)
                            .accessibilityHidden(true)
                    }
                    .frame(width: tile, height: tile)
                    .padding(.trailing, Naqi.S.s3)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Naqi.F.titleSmall)
                        .foregroundStyle(Naqi.C.onSurface)
                    if let desc {
                        Text(desc)
                            .font(Naqi.F.bodySmall)
                            .foregroundStyle(Naqi.C.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Toggle("", isOn: $isOn)
                    .labelsHidden()
                    // macOS would render a checkbox. The operation rows are the
                    // brand's one switch, jade-filled, on both platforms.
                    .toggleStyle(.switch)
                    .tint(Naqi.C.primary)
                    .allowsHitTesting(false)
                    .padding(.leading, Naqi.S.s3)
            }
            .padding(.horizontal, Naqi.S.s4)
            .padding(.vertical, Naqi.S.s3)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityRepresentation {
            // The id goes *inside*: the representation substitutes this row's
            // accessibility node with the Toggle's, so anything attached to the
            // row outside would be attached to the node that got replaced.
            Toggle(isOn: $isOn) { Text(title) }
                .accessibilityIdentifier(id ?? title.key)
        }
    }
}

/// 24 pt circle: filled with a check when selected, an outline ring when not.
/// Both the scale and the colours are spring-animated.
struct SelectDot: View {
    let isSelected: Bool

    /// Scaled with the row title beside it — and it is also the row's hit
    /// target, which must not stay a 24 pt dot for someone who needs 50 pt text.
    @ScaledMetric(relativeTo: .subheadline) private var dot: CGFloat = 24
    @ScaledMetric(relativeTo: .subheadline) private var check: CGFloat = 15

    var body: some View {
        ZStack {
            Circle()
                .fill(isSelected ? Naqi.C.primary : .clear)
                .overlay(Circle().strokeBorder(isSelected ? .clear : Naqi.C.outline,
                                               lineWidth: Naqi.Border.emphasis))
            if isSelected {
                NaqiIcon(.check)
                    .fill(Naqi.C.onPrimary)
                    .frame(width: check, height: check)
                    .accessibilityHidden(true)
            }
        }
        .frame(width: dot, height: dot)
        .scaleEffect(isSelected ? 1.0 : 0.85)
        .animation(Naqi.spring, value: isSelected)
    }
}

/// A pick-one row: title over description, `SelectDot` at the trailing edge.
struct SelectRow: View {
    let title: LocalizedStringResource
    var desc: LocalizedStringResource?
    let isSelected: Bool
    var id: String?
    let select: () -> Void

    var body: some View {
        Button(action: select) {
            HStack(spacing: Naqi.S.s3) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Naqi.F.titleSmall)
                        .foregroundStyle(Naqi.C.onSurface)
                    if let desc {
                        Text(desc)
                            .font(Naqi.F.bodySmall)
                            .foregroundStyle(Naqi.C.onSurfaceVariant)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                SelectDot(isSelected: isSelected)
            }
            .padding(.horizontal, Naqi.S.s4)
            .padding(.vertical, Naqi.S.s3)
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityIdentifier(id ?? title.key)
    }
}

/// The slider is the only writer of `strictness` and `blurAmount`, so the guard
/// lives here — but it is a clamp, not a rounding: 0…100 is a wire range shared
/// with the persisted job data, and a 101 would reach the NSFW gate.
func clampedSliderValue(_ raw: Double) -> Int {
    // Clamped in Double space so an infinity lands on the bound it is on the
    // wrong side of, not on zero. NaN has no bound and answers the minimum.
    guard !raw.isNaN else { return 0 }
    return Int(min(100, max(0, raw)).rounded())
}

/// Title + a value pill, a description, then a 0…100 slider that rounds to Int.
struct SliderRow: View {
    let title: LocalizedStringResource
    let desc: LocalizedStringResource
    /// What the number *means*. The visible `desc` is a separate element that
    /// VoiceOver reads before reaching the slider, so it cannot double as the
    /// hint: "Strictness, 50" alone gives no direction to drag in.
    var hint: LocalizedStringResource?
    var id: String?
    @Binding var value: Int

    private var proxy: Binding<Double> {
        Binding(get: { Double(value) }, set: { value = clampedSliderValue($0) })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            HStack(spacing: Naqi.S.s2) {
                Text(title)
                    .font(Naqi.F.titleSmall)
                    .foregroundStyle(Naqi.C.onSurface)
                Spacer(minLength: 0)
                Text(.optSliderValue(Int32(value)))
                    .font(Naqi.F.labelMedium)
                    .tracking(Naqi.F.labelTracking)
                    .monospacedDigit()
                    .foregroundStyle(Naqi.C.primary)
                    .padding(.horizontal, Naqi.S.s3)
                    .padding(.vertical, 2)
                    .background(Naqi.C.primary.opacity(0.12), in: .capsule)
            }
            Text(desc)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                // Without this the slider's flexible width wins the layout
                // negotiation and the sentence truncates instead of wrapping —
                // visible only in the iPad two-column layout.
                .fixedSize(horizontal: false, vertical: true)
            // The slider, not the VStack, carries the identity: it is the only
            // element of the row a test or VoiceOver can act on.
            Slider(value: proxy, in: 0...100, step: 1)
                .tint(Naqi.C.primary)
                .accessibilityLabel(Text(title))
                // No optional form of the modifier; an empty hint is no hint.
                .accessibilityHint(hint.map { Text($0) } ?? Text(verbatim: ""))
                .accessibilityIdentifier(id ?? title.key)
        }
        .padding(.horizontal, Naqi.S.s4)
        .padding(.vertical, Naqi.S.s3)
    }
}

/// Centred icon + sentence. Used for the reassurance line under the primary CTA.
struct NoteLine: View {
    let icon: NaqiIcon.Glyph
    let text: LocalizedStringResource

    /// Inline with the sentence, so it tracks the sentence's own text style.
    @ScaledMetric(relativeTo: .footnote) private var glyph: CGFloat = 15

    var body: some View {
        HStack(spacing: Naqi.S.s1) {
            NaqiIcon(icon)
                .fill(Naqi.C.primary)
                .frame(width: glyph, height: glyph)
                .accessibilityHidden(true)
            Text(text)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .center)
        .multilineTextAlignment(.center)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Bottom action bar

/// The pinned bottom column: an optional slot, then a full-width 56 pt button.
/// It sits on `background`, not on a raised surface — the app has no elevation.
struct NaqiBottomAction<Above: View>: View {
    let title: LocalizedStringResource
    var enabled: Bool = true
    var id: String?
    let action: () -> Void
    @ViewBuilder var above: Above

    var body: some View {
        // Capped rather than edge-to-edge: a 56 pt bar stretched across a 13"
        // iPad reads as a banner, not as a button.
        ReadableColumn {
            VStack(spacing: Naqi.S.s2) {
                above
                Button(action: action) {
                    Text(title)
                        .font(Naqi.F.labelLarge)
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(NaqiPrimaryButtonStyle(enabled: enabled))
                .disabled(!enabled)
                .accessibilityIdentifier(id ?? title.key)
            }
        }
        .padding(.horizontal, Naqi.S.gutter)
        .padding(.vertical, Naqi.S.s3)
        .background(Naqi.C.background)
    }
}

extension NaqiBottomAction where Above == EmptyView {
    init(title: LocalizedStringResource, enabled: Bool = true, id: String? = nil,
         action: @escaping () -> Void) {
        self.init(title: title, enabled: enabled, id: id, action: action) { EmptyView() }
    }
}

struct NaqiPrimaryButtonStyle: ButtonStyle {
    var enabled = true

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(enabled ? Naqi.C.onPrimary : Naqi.C.onSurfaceVariant)
            .background(enabled ? Naqi.C.primary : Naqi.C.surfaceContainerHighest,
                        in: .rect(cornerRadius: Naqi.R.button))
            .opacity(configuration.isPressed ? 0.85 : 1)
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .animation(Naqi.spring, value: configuration.isPressed)
    }
}

/// The outlined twin — Share and Save next to Play on the done screen.
struct NaqiOutlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(Naqi.C.primary)
            .overlay(RoundedRectangle(cornerRadius: Naqi.R.button)
                .strokeBorder(Naqi.C.outline, lineWidth: Naqi.Border.hairline))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .animation(Naqi.spring, value: configuration.isPressed)
    }
}

// MARK: - Layout

/// A readable centred column. iPad and Mac get more air, never a stretched
/// phone: the controls stop growing at `max` and the window grows around them.
struct ReadableColumn<Content: View>: View {
    var max: CGFloat = 560
    @ViewBuilder var content: Content

    var body: some View {
        content
            .frame(maxWidth: max)
            .frame(maxWidth: .infinity)
    }
}

/// True where a second column earns its keep — iPad regular width and every
/// Mac window.
@MainActor func isWideLayout(_ sizeClass: UserInterfaceSizeClass?) -> Bool {
    #if os(macOS)
    return true
    #else
    return sizeClass == .regular
    #endif
}
