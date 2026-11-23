import SwiftUI

/// What arrived on the share intent or the pick-screen link field.
enum SharedSource: Sendable, Equatable {
    case link(String)
    case file(name: String?)
}

/// Quality + filters, matching Android `ShareSheet`. Opens immediately — a
/// network round-trip before the controls would make sharing feel like an
/// app launch. Metadata discovery happens in the queued download.
struct DownloadSheet: View {
    let shared: SharedSource
    var initialOps: FilterOps? = nil
    let onDismiss: () -> Void
    let onConfirm: (DownloadQuality, FilterOps) -> Void

    @State private var quality: DownloadQuality = .loadLastUsed()
    @State private var ops: FilterOps = .loadLastUsed()

    private var isLink: Bool {
        if case .link = shared { true } else { false }
    }
    private var audioOnly: Bool { isLink && quality == .audio }
    private var effectiveOps: FilterOps {
        var o = ops
        if audioOnly { o.fit(hasVideo: false) }
        return o
    }

    var body: some View {
        VStack(spacing: 0) {
            Capsule()
                .fill(Naqi.C.outlineVariant)
                .frame(width: 36, height: 4)
                .padding(.top, Naqi.S.s2)
                .padding(.bottom, Naqi.S.s4)

            header
            if isLink {
                qualityRow
                    .padding(.top, Naqi.S.s5)
            }
            ProcessingModeSection(selection: $ops.processingMode)
                .padding(.top, Naqi.S.s5)
            SectionHeader(.shareEyebrowFilters)
                .padding(.top, Naqi.S.s5)
            filters
            Spacer(minLength: Naqi.S.s5)
            actions
        }
        .padding(.horizontal, Naqi.S.gutter)
        .padding(.bottom, Naqi.S.s5)
        .background(Naqi.C.background)
        .onAppear {
            if let initialOps {
                ops.removeMusic = initialOps.removeMusic
                ops.censor = initialOps.censor
                ops.who = initialOps.who
                ops.processingMode = initialOps.processingMode
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(Naqi.C.onSurface)
                .lineLimit(2)
            if case .link(let url) = shared, let host = URL(string: url)?.host {
                Text(host.replacingOccurrences(of: "www.", with: ""))
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var title: String {
        switch shared {
        case .file(let name): name ?? String(localized: .shareUntitled)
        case .link: String(localized: .shareUntitled)
        }
    }

    private var qualityRow: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            SectionHeader(.shareEyebrowQuality)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                    ForEach(DownloadQuality.allCases, id: \.self) { q in
                        Button {
                            withAnimation(Naqi.spring) { quality = q }
                        } label: {
                            HStack(spacing: 4) {
                                if quality == q {
                                    Image(systemName: "checkmark")
                                        .font(.caption.bold())
                                }
                                Text(q.label)
                            }
                            .font(Naqi.F.labelMedium)
                            .foregroundStyle(quality == q ? Naqi.C.onPrimary : Naqi.C.onSurface)
                            .padding(.horizontal, Naqi.S.s3)
                            .padding(.vertical, Naqi.S.s2)
                            .frame(minWidth: 72, minHeight: 36)
                            .background(quality == q ? Naqi.C.primary : Color.clear,
                                        in: .rect)
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(quality == q ? [.isButton, .isSelected] : .isButton)
                        .accessibilityIdentifier("quality.\(q.rawValue)")
                    }
                }
                .background(Naqi.C.surfaceContainer, in: .capsule)
                .overlay(Capsule().strokeBorder(Naqi.C.outlineVariant, lineWidth: Naqi.Border.hairline))
                .clipShape(.capsule)
            }
        }
    }

    private var filters: some View {
        NaqiCard(padding: 0) {
            ToggleTile(icon: .musicOff, title: .pickOpMusicTitle, isOn: $ops.removeMusic)
            NaqiRowDivider()
            ToggleTile(icon: .shield, title: .pickOpFacesTitle, isOn: Binding(
                get: { audioOnly ? false : ops.censor },
                set: { ops.censor = $0 }
            ))
                .disabled(audioOnly)
                .opacity(audioOnly ? 0.45 : 1)
        }
        .animation(Naqi.spring, value: audioOnly)
    }

    private var actions: some View {
        HStack(spacing: Naqi.S.s3) {
            Button(action: onDismiss) {
                Text(.actionCancel)
                    .font(Naqi.F.labelLarge)
                    .foregroundStyle(Naqi.C.primary)
                    .frame(minHeight: 52)
            }
            .buttonStyle(.plain)
            Button {
                var next = effectiveOps
                if audioOnly { next.censor = false }
                onConfirm(quality, next)
            } label: {
                Text(isLink ? .actionDownload : .actionFilter)
                    .font(Naqi.F.labelLarge)
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(NaqiPrimaryButtonStyle(enabled: isLink || effectiveOps.isValid))
            .disabled(!isLink && !effectiveOps.isValid)
            .accessibilityIdentifier("action.download")
        }
    }
}

extension DownloadQuality {
    var label: LocalizedStringResource {
        switch self {
        case .best: .shareQualityBest
        case .p1080: .shareQuality1080
        case .p720: .shareQuality720
        case .p480: .shareQuality480
        case .audio: .shareQualityAudio
        }
    }
}

#Preview {
    DownloadSheet(shared: .link("https://youtu.be/dQw4w9WgXcQ"),
                  onDismiss: {}, onConfirm: { _, _ in })
}
