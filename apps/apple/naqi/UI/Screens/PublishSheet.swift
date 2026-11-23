import AVFoundation
import SwiftUI

/// "Prepare to post": pick a platform, and the video is shared as is when it
/// fits or cut into parts that each do. Parts are copies in `Documents/Parts/`
/// that can be remade in seconds, so Delete asks nothing.
///
/// Closing the sheet mid-split cancels it, and `Splitter.split` then removes
/// the parts it already wrote.
struct PublishSheet: View {
    let target: PublishTarget

    @Environment(\.dismiss) private var dismiss
    /// Fixed while the sheet is open so a chip never moves under the finger.
    @State private var order: [PublishPreset]
    @State private var selected: String
    @State private var customSeconds = 60
    @State private var duration: Duration?
    @State private var parts: [URL] = []
    @State private var partDurations: [URL: Duration] = [:]
    @State private var work: Task<Void, Never>?
    @State private var failed = false
    /// Once per option per sheet: sharing eight parts is still one use.
    @State private var counted: Set<String> = []

    init(target: PublishTarget) {
        self.target = target
        let order = PublishPreset.ordered(includingCustom: true)
        _order = State(initialValue: order)
        _selected = State(initialValue: target.preset ?? order.first?.id ?? PublishPreset.customID)
    }

    private var preset: PublishPreset {
        PublishPreset.all.first { $0.id == selected } ?? .custom(seconds: customSeconds)
    }
    private var stem: String { target.url.deletingPathExtension().lastPathComponent }
    private var needsSplit: Bool { duration.map(preset.requiresSplitting) ?? false }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Naqi.S.s4) {
                    chips
                    if selected == PublishPreset.customID { customSlider }
                    Text(status)
                        .font(Naqi.F.bodyMedium)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                    if !parts.isEmpty { partList }
                    if failed {
                        Text(.publishFailed)
                            .font(Naqi.F.bodyMedium)
                            .foregroundStyle(Naqi.C.error)
                    }
                    primaryAction
                    if !parts.isEmpty && work == nil {
                        Button(role: .destructive) {
                            Splitter.delete(parts)
                            refresh()
                        } label: {
                            Text(.publishDeleteParts)
                                .font(Naqi.F.labelLarge)
                                .foregroundStyle(Naqi.C.error)
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("publish.deleteParts")
                    }
                }
                .padding(Naqi.S.gutter)
            }
            .background(Naqi.C.background)
            .navigationTitle(Text(.publishTitle))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Text(.actionDone) }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            let seconds = (try? await AVURLAsset(url: target.url).load(.duration))?.seconds ?? 0
            if seconds.isFinite, seconds > 0 { duration = .seconds(seconds) }
            refresh()
            // Opened from a platform tile: the user already said where it goes.
            if target.preset != nil, needsSplit, parts.isEmpty { prepare() }
        }
        .onChange(of: preset.id) {
            work?.cancel()
            work = nil
            failed = false
            refresh()
        }
        .onDisappear { work?.cancel() }
    }

    // MARK: - Pieces

    private var chips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Naqi.S.s2) {
                ForEach(order) { option in
                    let id = option.id.hasPrefix("custom") ? PublishPreset.customID : option.id
                    let on = id == selected
                    Button { selected = id } label: {
                        Text(option.label)
                            .font(Naqi.F.labelLarge)
                            .foregroundStyle(on ? Naqi.C.onPrimary : Naqi.C.onSurface)
                            .padding(.horizontal, Naqi.S.s4)
                            .frame(minHeight: 40)
                            .background(on ? Naqi.C.primary : .clear, in: .capsule)
                            .overlay(Capsule().strokeBorder(on ? .clear : Naqi.C.outline,
                                                            lineWidth: Naqi.Border.hairline))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                    .accessibilityIdentifier("publish.chip.\(id)")
                }
            }
        }
        .contentMargins(.horizontal, Naqi.S.gutter, for: .scrollContent)
        .padding(.horizontal, -Naqi.S.gutter)
    }

    private var customSlider: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s1) {
            Text(.publishCustomMax(clockText(.seconds(customSeconds))))
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
            Slider(value: Binding(get: { Double(customSeconds) }, set: { customSeconds = Int($0) }),
                   in: 15...600, step: 15)
        }
    }

    private var status: LocalizedStringResource {
        if !parts.isEmpty { return .publishPartsSaved }
        if needsSplit, let max = preset.maxSegment { return .publishNeedsSplit(clockText(max)) }
        return .publishAsIs
    }

    private var partList: some View {
        NaqiCard(padding: 0) {
            ForEach(Array(parts.enumerated()), id: \.element) { i, part in
                HStack {
                    Text(.publishPartDuration(i + 1, partDurations[part].map(clockText) ?? "…"))
                        .font(Naqi.F.titleSmall)
                        .foregroundStyle(Naqi.C.onSurface)
                    Spacer()
                    ShareLink(item: part) {
                        Image(systemName: "square.and.arrow.up")
                            .font(.system(size: 17, weight: .medium))
                            .frame(width: 44, height: 44)
                    }
                    .simultaneousGesture(TapGesture().onEnded(countUse))
                    .accessibilityIdentifier("publish.part.\(i + 1)")
                }
                .padding(.leading, Naqi.S.s4)
                .padding(.trailing, Naqi.S.s2)
                .frame(minHeight: 52)
                if part != parts.last { NaqiRowDivider() }
            }
        }
    }

    @ViewBuilder
    private var primaryAction: some View {
        if !needsSplit {
            ShareLink(item: target.url) { primaryLabel(.actionShare) }
                .buttonStyle(NaqiPrimaryButtonStyle())
                .simultaneousGesture(TapGesture().onEnded(countUse))
                .accessibilityIdentifier("publish.share")
        } else if parts.isEmpty || work != nil {
            Button { prepare() } label: {
                HStack(spacing: Naqi.S.s2) {
                    if work != nil { ProgressView().tint(Naqi.C.onSurfaceVariant) }
                    primaryLabel(work == nil ? .publishSplit : .publishPreparing)
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(NaqiPrimaryButtonStyle(enabled: work == nil))
            .disabled(work != nil)
            .accessibilityIdentifier("publish.prepare")
        } else if preset.supportsMultipleSegments {
            let batches = shareBatches(parts, max: preset.maxItemsPerShare)
            ForEach(batches, id: \.startIndex) { batch in
                ShareLink(items: Array(batch)) {
                    primaryLabel(batches.count == 1
                                 ? .publishShareAll
                                 : .publishShareRange(batch.startIndex + 1, batch.endIndex))
                }
                .buttonStyle(NaqiPrimaryButtonStyle())
                .simultaneousGesture(TapGesture().onEnded(countUse))
                .accessibilityIdentifier("publish.shareAll")
            }
        } else {
            Text(.publishOneByOne)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
        }
    }

    private func primaryLabel(_ title: LocalizedStringResource) -> some View {
        Text(title)
            .font(Naqi.F.labelLarge)
            .frame(maxWidth: .infinity, minHeight: 48)
    }

    // MARK: - Actions

    private func countUse() {
        let id = selected
        if counted.insert(id).inserted { PublishUsage.record(id) }
    }

    private func refresh() {
        parts = Splitter.existingParts(stem: stem, preset: preset)
        let parts = parts
        Task {
            for part in parts where partDurations[part] == nil {
                let s = (try? await AVURLAsset(url: part).load(.duration))?.seconds ?? 0
                if s.isFinite { partDurations[part] = .seconds(s) }
            }
        }
    }

    private func prepare() {
        failed = false
        let (url, stem, preset) = (target.url, stem, preset)
        work = Task {
            do {
                _ = try await Splitter.split(url, stem: stem, preset: preset)
            } catch {
                failed = !Task.isCancelled
            }
            guard !Task.isCancelled else { return }
            work = nil
            refresh()
        }
    }
}
