import SwiftUI

/// The same defaults Options edits, without a video in front of them.
///
/// Options is only reachable *through* a pick, so the one job that carries no
/// options of its own — a file shared in from another app, which inherits
/// exactly these — could not be inspected without first picking a video the
/// user did not want to filter.
///
/// It is not a second copy of Options: the cards are the same three views, and
/// the only thing this screen changes is that no picked source is allowed to
/// constrain them. Every change persists as it happens, through the same
/// `RootView` observer that already watches `flow.ops`.
struct SettingsScreen: View {
    @Bindable var flow: Flow
    @Binding var language: String
    @AppStorage(AppTheme.key) private var theme = AppTheme.system
    @AppStorage(Cloud.enabledKey) private var useServer = true

    var body: some View {
        ScrollView {
            ReadableColumn {
                VStack(alignment: .leading, spacing: Naqi.S.s5) {
                    // First, not last: it is what makes the rest of the screen
                    // mean something other than "settings for the video you are
                    // looking at", which is what Options is.
                    Text(.settingsDefaultsNote)
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, Naqi.S.s1)

                    ProcessingModeSection(selection: $flow.ops.processingMode)
                    operationsSection
                    // Options' rule, applied to the only thing there is here to
                    // apply it to. On Options the sections are hidden because
                    // the op is off *for this run*; here they are hidden
                    // because the op is off *by default*, which is the same
                    // statement — a strictness that no job will read is as much
                    // a lie on Settings as it is on Options. The difference is
                    // that the toggles above are on the screen, so a hidden
                    // section is one tap from coming back rather than a dead
                    // end reachable only by re-picking a video.
                    if flow.ops.censor { CensorSection(flow: flow) }
                    if flow.ops.removeMusic { MusicSection(flow: flow) }
                    // `audioOnly` left at its default: the Photos lock belongs
                    // to a *picked* file, and this screen sets the destination
                    // for one nobody has picked yet. Passing `flow.isAudioOnly`
                    // would grey Photos out for every future video because the
                    // last pick happened to be an MP3.
                    DestinationSection(flow: flow)
                    NaqiCard(padding: 0) {
                        ToggleTile(icon: .video,
                                   title: .settingsEndCardTitle,
                                   desc: .settingsEndCardDesc,
                                   id: "settings.endCard",
                                   isOn: $flow.ops.endCard)
                    }
                    // Only in a build that has a server to use (`Cloud.config`).
                    if Cloud.config != nil {
                        NaqiCard(padding: 0) {
                            ToggleTile(icon: nil,
                                       title: .settingsServerTitle,
                                       desc: .settingsServerDesc,
                                       id: "settings.server",
                                       isOn: $useServer)
                        }
                    }
                    StorageSection(monitor: flow.monitor)
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.settingsLanguageTitle)
                        LanguagePicker(language: $language)
                    }
                    appearanceSection
                }
            }
            .padding(.horizontal, Naqi.S.gutter)
            .padding(.top, Naqi.S.s4)
            .padding(.bottom, Naqi.S.s5)
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.settingsTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
    }

    private var appearanceSection: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s2) {
            SectionHeader(.settingsAppearanceTitle)
            Picker(String(localized: .settingsAppearanceTitle), selection: Binding(
                get: { theme },
                set: { new in crossfade { theme = new; new.apply() } }
            )) {
                ForEach(AppTheme.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("settings.theme")
        }
    }

    /// The pair the Pick screen shows, because they are defaults too — a
    /// shared-in file inherits `censor` and `removeMusic` before it inherits
    /// anything either section below sets. Without them this screen could hide
    /// a section and offer nothing that brings it back.
    ///
    /// The censor row is unconditional here, where Pick drops it for a source
    /// with no picture: there is no source, and "this file has no picture" is
    /// not a sentence a default can be true of.
    private var operationsSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(.pickEyebrowChoose)
            NaqiCard(padding: 0) {
                ToggleTile(icon: .musicOff,
                           title: .pickOpMusicTitle,
                           desc: .pickOpMusicDesc,
                           isOn: $flow.ops.removeMusic)
                NaqiRowDivider()
                ToggleTile(icon: .shield,
                           title: .pickOpFacesTitle,
                           // Same rule as Pick: the substituted line asserts
                           // what IS happening, the off line describes what
                           // turning it on would do.
                           desc: flow.ops.censor
                               ? .pickOpFacesDesc(String(localized: flow.ops.who.label))
                               : .pickOpFacesDescOff,
                           isOn: $flow.ops.censor)
            }
        }
    }
}

/// What the app is holding on disk, so the space it takes is never a surprise.
/// Re-measured on appear and after every delete.
private struct StorageSection: View {
    let monitor: JobMonitor

    @State private var use: JobQueue.StorageUse?
    @State private var canClearTemporary = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(.settingsStorageTitle)
            NaqiCard(padding: 0) {
                row(.settingsStorageCopies, .settingsStorageCopiesDesc, bytes: use?.copies,
                    enabled: true) { await monitor.clearCopies() }
                NaqiRowDivider()
                row(.settingsStorageTemp, .settingsStorageTempDesc, bytes: use?.temporary,
                    enabled: canClearTemporary) { await monitor.clearTemporary() }
                NaqiRowDivider()
                row(.settingsStorageOnly, .settingsStorageOnlyDesc, bytes: use?.onlyCopies, clear: nil)
            }
        }
        .task { await refresh() }
    }

    private func refresh() async {
        use = await monitor.storageUse()
        canClearTemporary = await monitor.canClearTemporary()
    }

    private func row(_ title: LocalizedStringResource, _ desc: LocalizedStringResource,
                     bytes: Int64?, enabled: Bool = false,
                     clear: (() async -> Void)?) -> some View {
        HStack(spacing: Naqi.S.s3) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(Naqi.F.titleSmall)
                    .foregroundStyle(Naqi.C.onSurface)
                Text(desc)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if let bytes {
                    Text(fileSizeText(bytes: bytes))
                        .font(Naqi.F.bodySmall)
                        .monospacedDigit()
                        .foregroundStyle(Naqi.C.primary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let clear, let bytes, bytes > 0 {
                Button {
                    Task {
                        await clear()
                        await refresh()
                    }
                } label: {
                    Text(.actionDelete).font(Naqi.F.labelLarge).padding(.horizontal, Naqi.S.s3)
                        .frame(minHeight: 44)
                }
                .buttonStyle(NaqiOutlineButtonStyle())
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.4)
            }
        }
        .padding(Naqi.S.s4)
    }
}
