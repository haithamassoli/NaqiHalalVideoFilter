import PhotosUI
import SwiftUI
import UniformTypeIdentifiers
import os

/// Step 1. One video, one pair of operations, one promise.
struct PickScreen: View {
    @Bindable var flow: Flow
    #if canImport(UIKit)
    @Environment(\.horizontalSizeClass) private var hSize
    #endif

    @State private var showSourceChoice = false
    @State private var showPhotosPicker = false
    @State private var showFileImporter = false
    @State private var photoItem: PhotosPickerItem?
    @State private var isDropTargeted = false
    /// A pick that did not land, cleared by the next one that does. The app has
    /// no alert anywhere: a failure says so in place, the way the Done screen
    /// reports a delete it could not perform.
    @State private var importFailed = false
    @State private var link = ""
    @State private var linkError = false
    @State private var linkToDownload: String?

    /// Tied to the card's own title, the way `ToggleTile`'s tile is: a 52 pt
    /// square left at 52 pt beside a 50 pt filename reads as a bullet rather
    /// than as an icon, and the row has no fixed height to fight.
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 52
    @ScaledMetric(relativeTo: .body) private var glyph: CGFloat = 26

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
                if wide {
                    HStack(alignment: .top, spacing: Naqi.S.s6) {
                        brandPanel.frame(maxWidth: 300)
                        controls
                    }
                    .frame(maxWidth: 860)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, Naqi.S.gutter)
                    .padding(.top, Naqi.S.s5)
                    .padding(.bottom, Naqi.S.s5)
                } else {
                    ReadableColumn {
                        VStack(spacing: 0) {
                            TrustSeal().padding(.bottom, Naqi.S.s5)
                            controls
                        }
                    }
                    .padding(.horizontal, Naqi.S.gutter)
                    .padding(.top, Naqi.S.s2)
                    .padding(.bottom, Naqi.S.s5)
                }
            }
            NaqiBottomAction(title: .actionContinue,
                             enabled: flow.canContinue,
                             action: { flow.path = [.options] }) {
                NoteLine(icon: .check, text: .pickReassurance)
            }
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.appName))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: Naqi.S.s1) {
                    NaqiMark().fill(Naqi.C.primary).frame(width: 22, height: 22)
                        .accessibilityHidden(true)
                    Text(.appName)
                        .font(Naqi.F.titleLarge)
                        .foregroundStyle(Naqi.C.onSurface)
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button { flow.path = [.jobs] } label: {
                    Image(systemName: "clock.arrow.circlepath")
                        .accessibilityLabel(Text(.jobsTitle))
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    // First, and above the two informational leaves: it is the
                    // only route to the defaults a shared-in file inherits, and
                    // reaching them otherwise costs a video the user does not
                    // want to filter.
                    Button { flow.path = [.settings] } label: { Text(.settingsTitle) }
                    Button { flow.path = [.about] } label: { Text(.aboutOpen) }
                    // The device-runtime panel is how the model smoke test is
                    // run on a real phone; it stays one tap from the first
                    // screen for exactly that reason.
                    Button { flow.path = [.diagnostics] } label: { Text(.pickDiagTitle) }
                    // Both store links: the person it is sent to may be on
                    // either platform.
                    ShareLink(item: String(localized: .shareAppMessage)
                        + "\n\niOS: https://apps.apple.com/app/id6798173054"
                        + "\nAndroid: https://play.google.com/store/apps/details?id=com.haithamassoli.naqi"
                    ) { Text(.shareApp) }
                } label: {
                    Image(systemName: "ellipsis")
                        .accessibilityLabel(Text(.actionMore))
                }
            }
        }
        // `preferredItemEncoding: .current` is the whole "does the picker hand
        // over the ORIGINAL?" question, and the default does not.
        // `.automatic` lets Photos transcode on the way out — an HEVC or HDR
        // capture arrives as a re-encoded H.264 copy, so the app would filter a
        // generation-lossy source and hand it back as the user's video. This
        // app's promise is that the picture it does not censor is untouched, so
        // it has to be the original bytes.
        .photosPicker(isPresented: $showPhotosPicker, selection: $photoItem,
                      matching: .videos, preferredItemEncoding: .current)
        // The two roots plus WebM: `.video`, `.mpeg4Movie` and
        // `.quickTimeMovie` all conform to `.movie`, and `.mp3`, `.wav`,
        // `.mpeg4Audio` and the rest to `.audio`. A bare audio file is a job
        // shape of its own (`Job.shape`, `audioOnly`) — music removal only.
        .fileImporter(isPresented: $showFileImporter,
                      allowedContentTypes: MediaTypes.importable) { result in
            switch result {
            case .success(let url):
                importFailed = false
                flow.adoptFileImport(url)
            // A refused import used to be dropped on the floor, which left the
            // card exactly as unpicked as before the picker opened — with
            // nothing on screen distinguishing that from a tap that missed.
            case .failure(let error):
                Log.app.error("file import failed: \(error.localizedDescription, privacy: .public)")
                importFailed = true
            }
        }
        .confirmationDialog(Text(.pickVideoNone), isPresented: $showSourceChoice, titleVisibility: .visible) {
            Button { showPhotosPicker = true } label: { Text(.pickSourcePhotos) }
            Button { showFileImporter = true } label: { Text(.pickSourceFiles) }
            Button(role: .cancel) {} label: { Text(.actionCancel) }
        }
        .onChange(of: photoItem) { adoptPhotoPick() }
        .sheet(isPresented: Binding(
            get: { linkToDownload != nil },
            set: { if !$0 { linkToDownload = nil } }
        )) {
            if let url = linkToDownload {
                DownloadSheet(shared: .link(url),
                              initialOps: flow.ops.isValid ? flow.ops : nil,
                              onDismiss: { linkToDownload = nil }) { quality, ops in
                    linkToDownload = nil
                    link = ""
                    Task { await flow.startLink(url, quality: quality, ops: ops) }
                }
                #if os(iOS)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.hidden)
                #endif
            }
        }
    }

    // MARK: - Panels

    private var brandPanel: some View {
        VStack(spacing: Naqi.S.s2) {
            NaqiMark().fill(Naqi.C.primary).frame(width: 56, height: 56)
            Text(.pickWordmarkAr)
                .font(Naqi.F.display)
                .foregroundStyle(Naqi.C.primary)
            // The gap exists because the ن of نقي drops a dot below its
            // baseline and the Latin line would otherwise sit in it.
            Text(.pickWordmarkLatin)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
            Text(.pickTagline)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .padding(.top, Naqi.S.s2)
            TrustSeal().padding(.top, Naqi.S.s4)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }

    private var controls: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Above the picker: a job that outlived the app is the one thing on
            // this screen the user did not just decide to do, and it has to be
            // answered before picking something else buries it.
            if let job = flow.resumableJobs.first {
                resumeCard(job)
                Spacer().frame(height: Naqi.S.s5)
            }

            pickCard
            if LinkPaste.isOffered {
                linkField
                    .padding(.top, Naqi.S.s3)
            }
            if importFailed {
                Text(.errImportFailed)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.error)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, Naqi.S.s2)
                    .padding(.horizontal, Naqi.S.s1)
            }
            #if os(macOS)
            // Drag-and-drop is the Mac way in; the card accepts a drop on every
            // platform but only says so where a pointer exists to do it with.
            NoteLine(icon: .video, text: .pickDropHint)
                .padding(.top, Naqi.S.s2)
            #endif
            Spacer().frame(height: Naqi.S.s5)

            SectionHeader(.pickEyebrowChoose)
            operationCard
        }
        .animation(Naqi.spring, value: importFailed)
        .animation(Naqi.spring, value: flow.resumableJobs.count)
    }

    /// A job that died with the app, offered rather than restarted: one that
    /// resumed itself while the user was looking at the picker would burn an
    /// hour of battery they did not ask for (`JobQueue.resumable`).
    ///
    /// One card for the first row, never a list. `pick_resume_body` names a
    /// single job, and acting on this one brings the next one up in its place —
    /// so a queue of survivors is answered one card at a time. A real queue
    /// screen is Q2's problem, the same call `JobMonitor.othersQueued` made.
    private func resumeCard(_ job: Job) -> some View {
        NaqiCard {
            Text(.pickResumeTitle)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(Naqi.C.onSurface)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(.pickResumeBody(job.title))
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 2)

            HStack(spacing: Naqi.S.s3) {
                Button { Task { await flow.resumeJob(job) } } label: {
                    Text(.actionResume)
                        .font(Naqi.F.labelLarge)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(NaqiPrimaryButtonStyle())

                Button { Task { await flow.discardJob(job) } } label: {
                    Text(.actionDiscard)
                        .font(Naqi.F.labelLarge)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(NaqiOutlineButtonStyle())
            }
            .padding(.top, Naqi.S.s4)
        }
    }

    /// A wide, unmistakable target that also reports what is picked. Every
    /// colour springs on the `picked` flag.
    private var pickCard: some View {
        let picked = flow.source != nil
        return Button {
            #if os(macOS)
            showFileImporter = true
            #else
            showSourceChoice = true
            #endif
        } label: {
            HStack(spacing: 0) {
                ZStack {
                    RoundedRectangle(cornerRadius: Naqi.R.button)
                        .fill(picked ? Naqi.C.primary : Naqi.C.surfaceContainerHighest)
                    NaqiIcon(picked ? .check : .video)
                        .fill(picked ? Naqi.C.onPrimary : Naqi.C.onSurfaceVariant)
                        .frame(width: glyph, height: glyph)
                        .accessibilityHidden(true)
                }
                .frame(width: tile, height: tile)
                .padding(.trailing, Naqi.S.s4)

                VStack(alignment: .leading, spacing: 2) {
                    // The filename falls back to a *selected* label, not the
                    // unpicked one: a provider may not expose a display name
                    // and the video is still picked.
                    Group {
                        if let name = flow.source?.name {
                            Text(name)
                        } else {
                            Text(picked ? .pickVideoSelected : .pickVideoNone)
                        }
                    }
                    .font(Naqi.F.titleMedium)
                    .foregroundStyle(Naqi.C.onSurface)
                    .lineLimit(1)
                    .truncationMode(.tail)

                    Text(picked ? .pickVideoChange : .pickVideoFormats)
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(Naqi.S.s4)
            .background(picked ? Naqi.C.primary.opacity(0.08) : Naqi.C.surfaceContainer,
                        in: .rect(cornerRadius: Naqi.R.card))
            .overlay(RoundedRectangle(cornerRadius: Naqi.R.card)
                .strokeBorder(isDropTargeted ? Naqi.C.primary
                              : (picked ? Naqi.C.primary : Naqi.C.outlineVariant),
                              lineWidth: Naqi.Border.emphasis))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .animation(Naqi.spring, value: picked)
        .animation(Naqi.spring, value: isDropTargeted)
        // Returning `false` is still the rejection: the system slides the item
        // back to where it came from, which is what every Mac app does with a
        // drop it cannot take. There is no hover-time filter available —
        // `dropDestination(for: URL.self)` reports `isTargeted` without ever
        // showing the payload — so the target highlights for a PDF and then
        // refuses it.
        //
        // The sentence is for iPad, where nothing slides back: the item simply
        // vanishes, and a refusal is indistinguishable from a dead target.
        .dropDestination(for: URL.self) { urls, _ in
            guard let url = urls.first(where: isDroppableSource) else {
                importFailed = true
                return false
            }
            importFailed = false
            flow.adoptFileImport(url)
            return true
        } isTargeted: { isDropTargeted = $0 }
    }

    /// One card, two rows — the pair is a single decision about what this run
    /// does, so it is one card and not two.
    ///
    /// An audio file drops to one row. The censor row is *removed* rather than
    /// disabled: unlike the Photos row on Options, which is greyed with a
    /// caption explaining why, there is nothing to explain here beyond "this
    /// file has no picture", which the row's own absence says.
    private var operationCard: some View {
        NaqiCard(padding: 0) {
            ToggleTile(icon: .musicOff,
                       title: .pickOpMusicTitle,
                       desc: .pickOpMusicDesc,
                       isOn: $flow.ops.removeMusic)
            if !flow.isAudioOnly {
                NaqiRowDivider()
                ToggleTile(icon: .shield,
                           title: .pickOpFacesTitle,
                           // The substituted line asserts what IS happening; the
                           // off line describes what turning it on would do. An off
                           // row reading "Women · and flagged scenes." would assert
                           // censoring that is not running.
                           desc: flow.ops.censor
                               ? .pickOpFacesDesc(String(localized: flow.ops.who.label))
                               : .pickOpFacesDescOff,
                           isOn: $flow.ops.censor)
            }
        }
        .animation(Naqi.spring, value: flow.isAudioOnly)
    }

    /// The other half of the source decision: one field, one action. The
    /// placeholder carries the "or", so the field needs no section label.
    private var linkField: some View {
        VStack(alignment: .leading, spacing: Naqi.S.s1) {
            HStack(spacing: Naqi.S.s2) {
                TextField(text: $link, prompt: Text(.pickLinkHint)) {
                    Text(.pickLinkHint)
                }
                #if os(iOS)
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                #endif
                .autocorrectionDisabled()
                .font(Naqi.F.bodyMedium)
                .foregroundStyle(Naqi.C.onSurface)
                .onSubmit(submitLink)
                Button(action: submitLink) {
                    Image(systemName: "arrow.down.circle")
                        .font(.title3)
                        .foregroundStyle(link.isEmpty ? Naqi.C.onSurfaceVariant : Naqi.C.primary)
                }
                .disabled(link.isEmpty)
                .accessibilityLabel(Text(.pickLinkAction))
                .accessibilityIdentifier("action.pasteLink")
            }
            .padding(.horizontal, Naqi.S.s4)
            .padding(.vertical, Naqi.S.s3)
            .background(Naqi.C.surfaceContainer, in: .rect(cornerRadius: Naqi.R.button))
            .overlay(RoundedRectangle(cornerRadius: Naqi.R.button)
                .strokeBorder(linkError ? Naqi.C.error : Naqi.C.outlineVariant,
                              lineWidth: Naqi.Border.hairline))
            if linkError {
                Text(.shareNoUrl)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.error)
                    .padding(.horizontal, Naqi.S.s1)
            }
        }
        .onChange(of: link) { linkError = false }
    }

    private func submitLink() {
        guard let url = VideoURL.first(in: link) else {
            linkError = true
            return
        }
        linkError = false
        linkToDownload = url
    }

    // MARK: - Photos

    private func adoptPhotoPick() {
        guard let item = photoItem else { return }
        Task {
            do {
                // `nil` is a failed transfer, not an empty one: the provider had
                // an item and could not produce the file. Both it and the throw
                // used to leave the card unpicked with nothing saying why.
                guard let movie = try await item.loadTransferable(type: MovieFile.self) else {
                    Log.app.error("photos pick produced no file")
                    importFailed = true
                    return
                }
                importFailed = false
                flow.setSource(PickedSource(url: movie.url,
                                            name: movie.url.lastPathComponent,
                                            // Present only when the app has
                                            // library read access; nil is the
                                            // normal case and simply means
                                            // "Delete original" has no target.
                                            assetID: item.itemIdentifier))
            } catch {
                Log.app.error("photos pick failed: \(error.localizedDescription, privacy: .public)")
                importFailed = true
            }
        }
    }
}

#Preview { RootView() }
