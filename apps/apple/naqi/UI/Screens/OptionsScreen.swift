import SwiftUI
import UniformTypeIdentifiers
import os

/// Step 2. Every control is shown **only when the op it applies to is on** — an
/// option that cannot affect the output would be a lie on screen.
struct OptionsScreen: View {
    @Bindable var flow: Flow
    #if canImport(UIKit)
    @Environment(\.horizontalSizeClass) private var hSize
    #endif

    @State private var showLongJobConfirm = false
    @State private var startFeedback = 0

    private var wide: Bool {
        #if canImport(UIKit)
        isWideLayout(hSize)
        #else
        true
        #endif
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Naqi.S.s5) {
                    ProcessingModeSection(selection: $flow.ops.processingMode)
                    if wide && flow.ops.censor && flow.ops.removeMusic {
                        VStack(alignment: .leading, spacing: Naqi.S.s5) {
                            HStack(alignment: .top, spacing: Naqi.S.s5) {
                                CensorSection(flow: flow)
                                MusicSection(flow: flow)
                            }
                            // Full width under the two columns, not a third
                            // one: it is one short card and a column of its own
                            // would leave a hole beside it on every shape.
                            DestinationSection(flow: flow, audioOnly: flow.isAudioOnly)
                        }
                        .frame(maxWidth: 860)
                    } else {
                        ReadableColumn {
                            VStack(alignment: .leading, spacing: Naqi.S.s5) {
                                if flow.ops.censor { CensorSection(flow: flow) }
                                if flow.ops.removeMusic { MusicSection(flow: flow) }
                                DestinationSection(flow: flow, audioOnly: flow.isAudioOnly)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, Naqi.S.gutter)
                .padding(.top, Naqi.S.s4)
                .padding(.bottom, Naqi.S.s5)
            }

            NaqiBottomAction(title: .actionStart,
                             enabled: flow.canStart,
                             action: startTapped) {
                // 0 means "too early to say" and the line is hidden entirely
                // rather than showing a number.
                if flow.estimateMs > 0 {
                    Text(.optEtaFloor(String(localized: durationText(ms: flow.estimateMs))))
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.optTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
        // Placed in front of any permission dance so the user is never asked
        // for something only to then back out (spec §7.3). It is a warning,
        // never a cap: confirming lands exactly where a short job's Start does.
        .confirmationDialog(Text(.dlgLongJobTitle),
                            isPresented: $showLongJobConfirm,
                            titleVisibility: .visible) {
            Button { start() } label: { Text(.actionStart) }
            Button(role: .cancel) {} label: { Text(.actionCancel) }
        } message: {
            Text(.dlgLongJobBody(String(localized: durationText(ms: flow.estimateMs))))
        }
        .sensoryFeedback(.impact, trigger: startFeedback)
    }

    private func startTapped() {
        if flow.estimateMs > Eta.confirmThresholdMs {
            showLongJobConfirm = true
        } else {
            start()
        }
    }

    private func start() {
        startFeedback += 1
        Task { await flow.start() }
    }
}

struct ProcessingModeSection: View {
    @Binding var selection: FilterOps.ProcessingMode

    var body: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            SectionHeader(.optPerformanceTitle)
            Picker(String(localized: .optPerformanceTitle), selection: $selection) {
                Text(.optPerformanceCurrent).tag(FilterOps.ProcessingMode.current)
                Text(.optPerformanceFast).tag(FilterOps.ProcessingMode.fast)
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("processing.mode")
            Text(selection == .fast ? .optPerformanceFastDesc : .optPerformanceCurrentDesc)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

// MARK: - Sections

// The three cards are views of their own rather than computed properties on the
// screen because Options is no longer the only screen that shows them: Settings
// edits the same `flow.ops` without a picked video. Left as properties they
// would have had to be copied, and the copy is what drifts the day an option is
// added to one of them.
//
// Only `DestinationSection` needed a parameter to make the move — see its
// `audioOnly`. The other two read `flow.ops` and nothing else, so they are the
// same view on both screens.

/// Who gets censored, face or body coverage, scene gating, and censor style.
struct CensorSection: View {
    @Bindable var flow: Flow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(.optSectionCensorFaces)
            NaqiCard(padding: 0) {
                WhoRow(flow: flow)
                NaqiRowDivider()
                // Both body descriptions say up front that filtering slows down.
                ForEach([(FilterOps.CensorTarget.face, LocalizedStringResource.optFaceTitle, LocalizedStringResource.optFaceDesc),
                         (.body, .optPersonTitle, .optPersonDesc),
                         (.bodyPrecise, .optBodyPreciseTitle, .optBodyPreciseDesc)], id: \.0) { target, title, desc in
                    SelectRow(title: title, desc: desc,
                              isSelected: flow.ops.censorTarget == target,
                              id: "censor.\(target.rawValue)") {
                        withAnimation(Naqi.spring) { flow.ops.censorTarget = target }
                    }
                    NaqiRowDivider()
                }
                // `regions` is off, `wholeFrame` is on.
                ToggleTile(icon: nil,
                           title: .optWholeFrameTitle,
                           desc: .optWholeFrameDesc,
                           isOn: Binding(get: { flow.ops.censorMode == .wholeFrame },
                                         set: { flow.ops.censorMode = $0 ? .wholeFrame : .regions }))
                NaqiRowDivider()
                ToggleTile(icon: nil,
                           title: .optNsfwTitle,
                           desc: .optNsfwDesc,
                           isOn: $flow.ops.censorNsfw)
                if flow.ops.censorNsfw {
                    NaqiRowDivider()
                    // The hint is what VoiceOver reads *after* the value, and
                    // says which way to drag: "Strictness, 50" alone does not.
                    SliderRow(title: .optStrictnessTitle,
                              desc: .optStrictnessDesc,
                              hint: .optStrictnessHint,
                              value: $flow.ops.strictness)
                }
                NaqiRowDivider()
                CensorStyleRow(flow: flow)
                if !flow.ops.solidColor.isSolid {
                    NaqiRowDivider()
                    SliderRow(title: .optBlurAmountTitle,
                              desc: .optBlurAmountDesc,
                              hint: .optBlurHint,
                              value: $flow.ops.blurAmount)
                    NaqiRowDivider()
                    ToggleTile(icon: nil,
                               title: .optGrayscaleTitle,
                               desc: .optGrayscaleDesc,
                               isOn: $flow.ops.grayscale)
                }
            }
        }
    }

}

/// Three segments, not four. `none` is the step-1 toggle, and an "Off"
/// segment here would be a control that turns off the card containing it.
/// Shared with onboarding, which asks the same question.
struct WhoRow: View {
    @Bindable var flow: Flow

    var body: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            Text(.optWhoTitle)
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
            Text(.optWhoDesc)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 0) {
                ForEach(FilterOps.Who.userSelectable, id: \.self) { who in
                    let selected = flow.ops.who == who
                    Button {
                        withAnimation(Naqi.spring) { flow.ops.who = who }
                    } label: {
                        Text(who.label)
                            .font(Naqi.F.titleSmall)
                            .foregroundStyle(selected ? Naqi.C.onPrimary : Naqi.C.onSurfaceVariant)
                            // 44 and not 36: the segments were below the
                            // minimum tap target at the *default* text size,
                            // not only at accessibility ones. A flat floor
                            // rather than a `@ScaledMetric` because the label
                            // inside grows the capsule past it on its own —
                            // this only has to stop it shrinking below.
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(selected ? Naqi.C.primary : .clear, in: .capsule)
                            .contentShape(.capsule)
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
                }
            }
            .padding(3)
            .background(Naqi.C.surfaceContainerHighest, in: .capsule)
        }
        .padding(.horizontal, Naqi.S.s4)
        .padding(.vertical, Naqi.S.s3)
    }
}

/// Blur or one of five fixed opaque fills. Picking a swatch also picks Solid,
/// so the common path takes one tap and the stored colour carries both choices.
struct CensorStyleRow: View {
    @Bindable var flow: Flow

    var body: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            Text(.optCensorStyleTitle)
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
            Text(.optCensorStyleDesc)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 0) {
                styleButton(.optStyleBlur, selected: !flow.ops.solidColor.isSolid) {
                    flow.ops.solidColor = .blur
                }
                styleButton(.optStyleSolid, selected: flow.ops.solidColor.isSolid) {
                    if !flow.ops.solidColor.isSolid { flow.ops.solidColor = .black }
                }
            }
            .padding(3)
            .background(Naqi.C.surfaceContainerHighest, in: .capsule)

            if flow.ops.solidColor.isSolid {
                HStack(spacing: Naqi.S.s3) {
                    ForEach(FilterOps.SolidColor.swatches, id: \.self) { color in
                        swatch(color)
                    }
                }
            }
        }
        .padding(.horizontal, Naqi.S.s4)
        .padding(.vertical, Naqi.S.s3)
    }

    private func styleButton(_ title: LocalizedStringResource,
                             selected: Bool,
                             action: @escaping () -> Void) -> some View {
        Button {
            withAnimation(Naqi.spring) { action() }
        } label: {
            Text(title)
                .font(Naqi.F.titleSmall)
                .foregroundStyle(selected ? Naqi.C.onPrimary : Naqi.C.onSurfaceVariant)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(selected ? Naqi.C.primary : .clear, in: .capsule)
                .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }

    private func swatch(_ color: FilterOps.SolidColor) -> some View {
        let rgb = color.rgb
        let selected = flow.ops.solidColor == color
        return Button {
            withAnimation(Naqi.spring) { flow.ops.solidColor = color }
        } label: {
            Circle()
                .fill(Color(red: rgb.red, green: rgb.green, blue: rgb.blue))
                .overlay(Circle().stroke(Naqi.C.outlineVariant, lineWidth: 1))
                .padding(4)
                .overlay(Circle().stroke(selected ? Naqi.C.primary : .clear, lineWidth: 2))
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(color.label))
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
    }
}

/// The two wire values are `vocals` / `vocalsAndOther`; drums and bass are
/// never kept whichever is picked.
struct MusicSection: View {
    @Bindable var flow: Flow

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(.optSectionRemoveMusic)
            NaqiCard(padding: 0) {
                SelectRow(title: .optKeepVocalsTitle,
                          desc: .optKeepVocalsDesc,
                          isSelected: flow.ops.keepStems == .vocals) {
                    withAnimation(Naqi.spring) { flow.ops.keepStems = .vocals }
                }
                NaqiRowDivider()
                SelectRow(title: .optKeepVocalsOtherTitle,
                          desc: .optKeepVocalsOtherDesc,
                          isSelected: flow.ops.keepStems == .vocalsAndOther) {
                    withAnimation(Naqi.spring) { flow.ops.keepStems = .vocalsAndOther }
                }
            }
        }
    }
}

/// The one section that is shown on every shape: unlike the op sections above,
/// "where does the copy go" is a decision every job has.
struct DestinationSection: View {
    @Bindable var flow: Flow
    /// The picked source has no picture, so Photos cannot take it — see
    /// `Flow.isAudioOnly`. Always `false` on Settings, which has no picked
    /// source: greying Photos out there because the *last* pick was an MP3
    /// would refuse a destination to every video that comes after it.
    var audioOnly = false

    @State private var showFolderPicker = false
    /// The folder picker's own refusal. Same treatment as the pick screen's —
    /// in place, in the app's own type, and cleared by the next folder that
    /// lands.
    @State private var folderFailed = false

    /// `Flow.destination` with the lock made a parameter. Options passes the
    /// picked source's answer and gets exactly `flow.destination` back;
    /// Settings passes `false` and gets the stored default.
    private var selected: Destination { audioOnly ? .userFolder : flow.export.destination }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(.optSectionSaveTo)
            NaqiCard(padding: 0) {
                SelectRow(title: .optDestPhotosTitle,
                          desc: .optDestPhotosDesc,
                          isSelected: selected == .photos) {
                    withAnimation(Naqi.spring) { flow.setDestination(.photos) }
                }
                // Disabled rather than hidden: a row that vanishes teaches
                // nothing, and the caption under the card says why this one
                // cannot be picked.
                .disabled(audioOnly)
                .opacity(audioOnly ? 0.4 : 1)

                NaqiRowDivider()

                SelectRow(title: .optDestFolderTitle,
                          desc: flow.export.folderName
                              .map { LocalizedStringResource.optDestFolderChosen($0) }
                              ?? .optDestFolderDesc,
                          isSelected: selected == .userFolder) {
                    // Tapping the row that is already chosen re-opens the
                    // picker: it is the only way to change folders, and a
                    // second control on a two-row card would be a third tap
                    // target for a once-a-year action.
                    if selected == .userFolder || flow.export.folder == nil {
                        showFolderPicker = true
                    } else {
                        withAnimation(Naqi.spring) { flow.setDestination(.userFolder) }
                    }
                }
            }
            if folderFailed {
                Text(.errImportFailed)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Naqi.S.s2)
                    .padding(.horizontal, Naqi.S.s1)
            }
            if audioOnly {
                Text(.optDestAudioOnly)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Naqi.S.s2)
                    .padding(.horizontal, Naqi.S.s1)
            }
        }
        .fileImporter(isPresented: $showFolderPicker, allowedContentTypes: [.folder]) { result in
            switch result {
            case .success(let url):
                folderFailed = false
                flow.setFolder(url)
            // Silence here left the row still reading "Choose a folder" with
            // Start disabled and no sentence connecting the two.
            case .failure(let error):
                Log.app.error("folder pick failed: \(error.localizedDescription, privacy: .public)")
                folderFailed = true
            }
        }
        .animation(Naqi.spring, value: audioOnly)
        .animation(Naqi.spring, value: folderFailed)
    }
}
