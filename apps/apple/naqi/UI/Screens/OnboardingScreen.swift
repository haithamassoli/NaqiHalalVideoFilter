import SwiftUI

/// First launch only: language, what the app does, then the two choices that
/// matter — blur and music — written straight into `flow.ops`, so the first
/// video opens with them. Everything finer (strictness, whole frame, stems,
/// destination) stays on Options, where there is a video to apply it to.
struct OnboardingScreen: View {
    @Bindable var flow: Flow
    @Binding var language: String
    let finish: () -> Void

    @State private var step = 0
    private let steps = 5

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                ReadableColumn {
                    page
                        .id(step)
                        .transition(.asymmetric(insertion: .move(edge: .trailing).combined(with: .opacity),
                                                removal: .opacity))
                }
                .padding(.horizontal, Naqi.S.gutter)
                .padding(.bottom, Naqi.S.s5)
            }
            .scrollBounceBehavior(.basedOnSize)
            NaqiBottomAction(title: step == steps - 1 ? .onbGetStarted : .actionContinue,
                             id: "onb.next",
                             action: next) { dots }
        }
        .background(Naqi.C.background)
        .sensoryFeedback(.selection, trigger: step)
    }

    private func next() {
        if step == steps - 1 { finish() } else { withAnimation(.smooth) { step += 1 } }
    }

    private var topBar: some View {
        HStack {
            if step > 0 {
                Button { withAnimation(.smooth) { step -= 1 } } label: {
                    Image(systemName: "chevron.backward")
                        .font(.body.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .contentShape(.rect)
                }
                .accessibilityLabel(Text(.actionBack))
            }
            Spacer()
        }
        .buttonStyle(.plain)
        .foregroundStyle(Naqi.C.primary)
        .padding(.horizontal, Naqi.S.s3)
        .frame(height: 52)
    }

    private var dots: some View {
        HStack(spacing: Naqi.S.s2) {
            ForEach(0..<steps, id: \.self) { i in
                Capsule()
                    .fill(i == step ? Naqi.C.primary : Naqi.C.outlineVariant)
                    .frame(width: i == step ? 22 : 7, height: 7)
            }
        }
        .animation(Naqi.spring, value: step)
        .padding(.bottom, Naqi.S.s2)
        .accessibilityHidden(true)
    }

    @ViewBuilder private var page: some View {
        switch step {
        case 0: languagePage
        case 1:
            Page(image: "OnboardingImport", title: .onbWelcomeTitle, text: .onbWelcomeBody) {
                NaqiCard(padding: 0) {
                    Feature(icon: .shield, text: .onbFeatBlur)
                    NaqiRowDivider()
                    Feature(icon: .musicOff, text: .onbFeatMusic)
                    NaqiRowDivider()
                    Feature(icon: .check, text: .onbFeatPrivate)
                }
            }
        case 2:
            Page(image: "OnboardingPrivacy", title: .onbBlurTitle, text: .onbBlurBody) {
                NaqiCard(padding: 0) {
                    ToggleTile(icon: .shield, title: .pickOpFacesTitle, desc: .pickOpFacesDescOff,
                               id: "onb.censor", isOn: $flow.ops.censor)
                    if flow.ops.censor {
                        NaqiRowDivider()
                        WhoRow(flow: flow)
                        NaqiRowDivider()
                        CensorStyleRow(flow: flow)
                    }
                }
            }
        case 3:
            Page(image: "OnboardingMusic", title: .onbMusicTitle, text: .onbMusicBody) {
                NaqiCard(padding: 0) {
                    ToggleTile(icon: .musicOff, title: .pickOpMusicTitle, desc: .pickOpMusicDesc,
                               id: "onb.music", isOn: $flow.ops.removeMusic)
                }
            }
        default:
            Page(image: "OnboardingReady", title: .onbReadyTitle, text: .onbReadyBody) {
                TrustSeal()
            }
        }
    }

    /// Written before anything is translated, so both names are shown in their
    /// own script and the title carries both languages.
    private var languagePage: some View {
        VStack(spacing: Naqi.S.s5) {
            NaqiMark().fill(Naqi.C.primary)
                .frame(width: 88, height: 88)
                .padding(.top, Naqi.S.s6)
                .accessibilityHidden(true)
            VStack(spacing: Naqi.S.s2) {
                Text(verbatim: "نقي · Naqi")
                    .font(Naqi.F.display)
                    .foregroundStyle(Naqi.C.onSurface)
                Text(verbatim: "اختر اللغة · Choose your language")
                    .font(Naqi.F.bodyMedium)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
            }
            .multilineTextAlignment(.center)
            LanguagePicker(language: $language)
        }
    }
}

/// Both names in their own script, on onboarding and in Settings. A pick
/// re-renders this session through the environment and fades into it.
struct LanguagePicker: View {
    @Binding var language: String

    var body: some View {
        NaqiCard(padding: 0) {
            ForEach(Array(AppLanguage.all.enumerated()), id: \.element.code) { i, lang in
                if i > 0 { NaqiRowDivider() }
                // ponytail: the name is its own key; the catalog has no
                // entry, so it resolves to itself in every language.
                SelectRow(title: LocalizedStringResource(stringLiteral: lang.name),
                          isSelected: language == lang.code, id: "onb.lang.\(lang.code)") {
                    guard language != lang.code else { return }
                    crossfade { language = lang.code }
                    AppLanguage.save(lang.code)
                }
            }
        }
    }
}

/// One tour page: picture, headline, one sentence, then whatever it asks.
private struct Page<Content: View>: View {
    let image: String
    let title: LocalizedStringResource
    let text: LocalizedStringResource
    @ViewBuilder var content: Content

    var body: some View {
        VStack(spacing: Naqi.S.s4) {
            Image(image)
                .resizable()
                .scaledToFit()
                .frame(maxHeight: 180)
                .accessibilityHidden(true)
            VStack(spacing: Naqi.S.s2) {
                Text(title)
                    .font(Naqi.F.titleLarge)
                    .foregroundStyle(Naqi.C.onSurface)
                Text(text)
                    .font(Naqi.F.bodyMedium)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .multilineTextAlignment(.center)
            content.padding(.top, Naqi.S.s2)
        }
    }
}

private struct Feature: View {
    let icon: NaqiIcon.Glyph
    let text: LocalizedStringResource

    var body: some View {
        HStack(spacing: Naqi.S.s3) {
            NaqiIcon(icon).fill(Naqi.C.primary)
                .frame(width: 22, height: 22)
                .padding(10)
                .background(Naqi.C.primary.opacity(0.16), in: .rect(cornerRadius: 14))
                .accessibilityHidden(true)
            Text(text)
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, Naqi.S.s4)
        .padding(.vertical, Naqi.S.s3)
    }
}

/// The app's two localizations. The choice is written where iOS keeps the
/// per-app language, so it holds on the next launch and Settings › Naqi ›
/// Language shows (and can change) it.
enum AppLanguage {
    static let all = [(code: "ar", name: "العربية"), (code: "en", name: "English")]

    static var current: String {
        Bundle.main.preferredLocalizations.first.flatMap { code in all.first { $0.code == code }?.code } ?? "en"
    }

    static func save(_ code: String) {
        UserDefaults.standard.set([code], forKey: "AppleLanguages")
    }
}
