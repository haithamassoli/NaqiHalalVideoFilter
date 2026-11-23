import AVFoundation
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// A finished video and the places it can go, in one card: the thumbnail
/// plays it, and a row of platforms shares it. Used by Done and by the newest
/// row in Activity.
///
/// A platform whose limit the video already fits is a `ShareLink` — one tap to
/// the share sheet. One that needs cutting opens `PublishSheet` on that
/// platform instead. iOS cannot aim the share sheet at one app, so the tile
/// decides how the video is prepared, and the user picks the app in the sheet
/// (`docs/publish-presets-plan-ios.md` §3.1).
struct SavedCard<Extra: View, Footer: View>: View {
    let url: URL?
    let name: String
    var detail: LocalizedStringResource? = nil
    /// Nil when there is nothing to play.
    let play: (() -> Void)?
    let publish: (PublishTarget) -> Void
    @ViewBuilder var extra: Extra
    @ViewBuilder var footer: Footer

    @State private var thumb: CGImage?
    @State private var duration: Duration?
    /// Fixed once loaded, so a tile never moves under the user's finger.
    @State private var presets: [PublishPreset] = []

    private var isAudio: Bool { MediaKind.of(url ?? URL(fileURLWithPath: name)) == .audio }

    var body: some View {
        NaqiCard {
            Button { play?() } label: { header }
                .buttonStyle(.plain)
                .disabled(play == nil)
                .accessibilityIdentifier("action.play")

            if let url {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: Naqi.S.s1) {
                        ForEach(presets) { tile($0, url: url) }
                        ShareLink(item: url) {
                            ShareTile(label: presets.isEmpty ? .actionShare : .actionMore) {
                                SymbolTile(systemName: "square.and.arrow.up")
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("action.share")
                        extra
                    }
                }
                // Edge to edge inside the card: a tile cut off at the edge says "scroll".
                .contentMargins(.horizontal, Naqi.S.s3, for: .scrollContent)
                .padding(.horizontal, -Naqi.S.s4)
                .padding(.top, Naqi.S.s4)
            }

            footer
        }
        .task(id: url) { await load() }
    }

    private var header: some View {
        HStack(spacing: Naqi.S.s3) {
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Naqi.C.surfaceContainerHighest)
                if let thumb {
                    Image(decorative: thumb, scale: 1)
                        .resizable()
                        .scaledToFill()
                }
                if play != nil {
                    Image(systemName: isAudio ? "music.note" : "play.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(width: 32, height: 32)
                        .background(.black.opacity(0.45), in: .circle)
                }
            }
            .frame(width: 96, height: 64)
            .clipShape(.rect(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: Naqi.S.s1) {
                Text(name)
                    .font(Naqi.F.titleSmall)
                    .foregroundStyle(Naqi.C.onSurface)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                Label {
                    Text(duration.map { .jobsSavedMeta(clockText($0)) } ?? .jobsSavedLabel)
                } icon: {
                    Image(systemName: "checkmark")
                }
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.primary)
                if let detail {
                    Text(detail)
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .contentShape(.rect)
    }

    @ViewBuilder
    private func tile(_ preset: PublishPreset, url: URL) -> some View {
        let tile = ShareTile(label: preset.label) { BrandTile(preset: preset) }
        // Unknown length goes through the sheet, which can wait for it.
        if let duration, !preset.requiresSplitting(duration) {
            ShareLink(item: url) { tile }
                .buttonStyle(.plain)
                .simultaneousGesture(TapGesture().onEnded { PublishUsage.record(preset.id) })
                .accessibilityIdentifier("publish.\(preset.id)")
        } else {
            Button { publish(PublishTarget(url: url, name: name, preset: preset.id)) } label: { tile }
                .buttonStyle(.plain)
                .accessibilityIdentifier("publish.\(preset.id)")
        }
    }

    private func load() async {
        guard let url else { return }
        let asset = AVURLAsset(url: url)
        let seconds = (try? await asset.load(.duration))?.seconds ?? 0
        if seconds.isFinite, seconds > 0 { duration = .seconds(seconds) }
        // Audio has nothing to post to a video platform: Share alone.
        guard !isAudio else { return }
        presets = PublishPreset.ordered()
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 320, height: 320)
        let at = CMTime(seconds: min(1, seconds / 2), preferredTimescale: 600)
        thumb = try? await generator.image(at: at).image
    }
}

extension SavedCard where Extra == EmptyView, Footer == EmptyView {
    init(url: URL?, name: String, play: (() -> Void)?, publish: @escaping (PublishTarget) -> Void) {
        self.init(url: url, name: name, play: play, publish: publish) { EmptyView() } footer: { EmptyView() }
    }
}

/// What `PublishSheet` opens on.
struct PublishTarget: Identifiable {
    let id = UUID()
    let url: URL
    let name: String
    /// The platform tapped to open it; nil opens on the most used one.
    var preset: String? = nil
}

/// `0:31`, or `1:02:05` past the hour.
func clockText(_ duration: Duration) -> String {
    duration.formatted(.time(pattern: duration >= .seconds(3600) ? .hourMinuteSecond : .minuteSecond))
}

extension PublishPreset {
    /// Whether the platform's app is on this device.
    @MainActor var isInstalled: Bool {
        #if os(iOS) && !targetEnvironment(simulator)
        schemes.contains { URL(string: "\($0)://").map(UIApplication.shared.canOpenURL) ?? false }
        #else
        // ponytail: the simulator has none of these apps and the Mac cannot be
        // asked the same way; offer every platform there.
        true
        #endif
    }

    /// Installed platforms, most used first.
    @MainActor static func ordered(includingCustom: Bool = false) -> [PublishPreset] {
        let ids = all.filter(\.isInstalled).map(\.id) + (includingCustom ? [customID] : [])
        return PublishUsage.ordered(ids).map { id in all.first { $0.id == id } ?? .custom(seconds: 60) }
    }
}

// MARK: - Tiles

/// A 52 pt icon over a two-line label: "Instagram Story" and "Instagram Reels"
/// are told apart by their second word.
struct ShareTile<Icon: View>: View {
    let label: LocalizedStringResource
    @ViewBuilder var icon: Icon

    var body: some View {
        VStack(spacing: Naqi.S.s1 + 2) {
            icon.frame(width: 52, height: 52)
            Text(label)
                .font(Naqi.F.labelMedium)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .lineLimit(2, reservesSpace: true)
        }
        .frame(width: 76)
        .padding(.vertical, Naqi.S.s1)
        .contentShape(.rect)
    }
}

/// A platform's mark on its own colour, the way its app icon wears it.
struct BrandTile: View {
    let preset: PublishPreset

    private var brand: String { String(preset.id.prefix { $0 != "-" }) }

    var body: some View {
        RoundedRectangle(cornerRadius: 14, style: .continuous)
            .fill(background)
            .overlay {
                Image("brand-\(brand)")
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(brand == "snapchat" ? .black : .white)
                    .padding(13)
            }
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(.white.opacity(0.12), lineWidth: Naqi.Border.hairline))
            .accessibilityHidden(true)
    }

    private var background: AnyShapeStyle {
        switch brand {
        case "whatsapp": AnyShapeStyle(Color(hex: 0x25D366))
        case "telegram": AnyShapeStyle(Color(hex: 0x229ED9))
        case "snapchat": AnyShapeStyle(Color(hex: 0xFFFC00))
        case "instagram": AnyShapeStyle(LinearGradient(
            colors: [0xFFD600, 0xFF7A00, 0xFF0069, 0xD300C5, 0x7638FA].map { Color(hex: $0) },
            startPoint: .bottomLeading, endPoint: .topTrailing))
        case "messenger": AnyShapeStyle(LinearGradient(
            colors: [0x0099FF, 0xA033FF, 0xFF5280, 0xFF7061].map { Color(hex: $0) },
            startPoint: .top, endPoint: .bottomTrailing))
        default: AnyShapeStyle(Color.black)
        }
    }
}

/// The non-platform tiles (Share, Save) in the app's own quiet style.
struct SymbolTile: View {
    let systemName: String

    var body: some View {
        RoundedRectangle(cornerRadius: 14, style: .continuous)
            .fill(Naqi.C.surfaceContainerHighest)
            .overlay {
                Image(systemName: systemName)
                    .font(.system(size: 20, weight: .medium))
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
            }
            .accessibilityHidden(true)
    }
}
