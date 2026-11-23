import AVKit
import Photos
import SwiftUI
#if os(macOS)
import AppKit
#endif

/// Step 4. Where the filtered copy went, and the three things that can be done
/// with it. Deleting the original is opt-in, two-step, and never automatic.
struct DoneScreen: View {
    @Bindable var flow: Flow

    @State private var playback: PlaybackItem?
    @State private var saving = false
    @State private var showDeleteConfirm = false
    @State private var deleteResult: LocalizedStringResource?
    /// Fallback for a queue row written before Photos publishes kept a local
    /// copy: the published asset fetched back out of the photo library.
    @State private var libraryVideo: AVAsset?
    /// Set once the library has declined to hand the asset back — access
    /// refused, or an identifier that no longer resolves. Play is withdrawn
    /// rather than left as a button that opens an empty sheet.
    @State private var libraryRefused = false
    /// Flips once the in-app copy is gone, so the screen re-reads the disk.
    @State private var copyDropped = false
    @State private var publishing: PublishTarget?
    @AppStorage("naqi.endCardHintSeen") private var endCardHintSeen = false
    /// Once ever, on the first video that ends with the card. Captured on
    /// appear so marking it seen does not take it away mid-visit.
    @State private var showEndCardHint = false

    /// The name the publish actually used — not the job's `title`, which is the
    /// **source** name and would read as the filtered copy's. Available on both
    /// destinations now that the publish records it, including the Photos path
    /// that leaves no file behind to take a name from.
    private var outputName: String? { flow.monitor.outputName }

    /// The library asset the publish created, for the Photos destination.
    private var assetID: String? { flow.monitor.assetID }

    /// Whether Play has anything to show: a file still on disk, or a library
    /// asset the library has not already refused.
    private var canOpen: Bool {
        flow.monitor.output != nil || (assetID != nil && !libraryRefused)
    }

    /// The in-app twin of a video that also went into Photos, while it is
    /// still on disk. Deleting it costs Share and Save; Play falls back to
    /// the library.
    private var spareCopy: URL? {
        guard assetID != nil, !copyDropped, let url = flow.monitor.output,
              OutputLibrary.owns(url) else { return nil }
        return url
    }

    /// Only offered when there is something we can actually delete: a photo
    /// library asset, or a file the user imported in place. A Photos pick
    /// without library access hands back a copy in our own container, and
    /// deleting *that* would be a lie.
    private var canDeleteOriginal: Bool {
        guard let source = flow.source else { return false }
        return source.assetID != nil || source.securityScoped
    }

    /// Read off the finished job, not off the picker — the picker is free to
    /// have moved on, and claiming the photo library for a copy that went into
    /// a folder sends the user looking in the wrong app.
    private var savedWhere: LocalizedStringResource {
        // In the app's own Documents and nowhere else: Photos refused it, or it
        // is audio. Claiming the photo library (or naming "Documents") would
        // send the user looking in the wrong place.
        if assetID == nil, let url = flow.monitor.output, OutputLibrary.owns(url) {
            return .jobsSavedApp
        }
        if flow.monitor.destination == .userFolder, let folder = flow.monitor.folderName {
            return .jobsSavedFolder(folder)
        }
        return .jobsSavedPhotos
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                ReadableColumn {
                    VStack(alignment: .leading, spacing: Naqi.S.s5) {
                        savedCard
                        if let spareCopy { copyCard(spareCopy) }
                        if let deleteResult {
                            Text(deleteResult)
                                .font(Naqi.F.bodySmall)
                                .foregroundStyle(Naqi.C.onSurfaceVariant)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                .padding(.horizontal, Naqi.S.gutter)
                .padding(.top, Naqi.S.s4)
                .padding(.bottom, Naqi.S.s5)
            }
            NaqiBottomAction(title: .jobsNewJob) {
                Task { await flow.finishAndPickAnother() }
            }
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.doneTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
        .navigationBarBackButtonHidden(true)
        .onAppear {
            guard flow.monitor.published?.endCard == true, !endCardHintSeen else { return }
            showEndCardHint = true
            endCardHintSeen = true
        }
        .sheet(item: $playback) { MediaPlayerSheet(item: $0) }
        .sheet(item: $publishing) { PublishSheet(target: $0) }
        .mediaFileExporter(isPresented: $saving, url: flow.monitor.output,
                           name: outputName ?? "naqi")
        .confirmationDialog(Text(.dlgDeleteOriginalTitle),
                            isPresented: $showDeleteConfirm,
                            titleVisibility: .visible) {
            Button(role: .destructive) { deleteOriginal() } label: { Text(.actionDelete) }
            Button(role: .cancel) { deleteResult = .dlgOriginalKept } label: { Text(.actionKeep) }
        } message: {
            Text(.dlgDeleteOriginalBody(outputName
                ?? String(localized: .dlgDeleteOriginalFallbackName)))
        }
    }

    /// Play is the thumbnail, sharing is the platform row, and Save rides at
    /// the end of that row. The one destructive action sits under a divider
    /// with the sentence that says why you would use it.
    private var savedCard: some View {
        SavedCard(url: flow.monitor.output,
                  name: outputName ?? String(localized: .dlgDeleteOriginalFallbackName),
                  detail: savedWhere,
                  play: canOpen ? { openTapped() } : nil,
                  publish: { publishing = $0 }) {
            extraTiles
        } footer: {
            if showEndCardHint {
                Text(.doneEndCardHint)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, Naqi.S.s3)
            }
            deleteFooter
        }
    }

    @ViewBuilder
    private var extraTiles: some View {
        Button { saving = true } label: {
            ShareTile(label: .actionSave) { SymbolTile(systemName: "square.and.arrow.down") }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("action.save")
        #if os(macOS)
        // The sandbox container's Documents is not somewhere a Mac user
        // would look on their own.
        if let url = flow.monitor.output {
            Button { NSWorkspace.shared.activateFileViewerSelecting([url]) } label: {
                ShareTile(label: .actionShowInFinder) { SymbolTile(systemName: "folder") }
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("action.showInFinder")
        }
        #endif
    }

    @ViewBuilder
    private var deleteFooter: some View {
        if canDeleteOriginal && deleteResult == nil {
            NaqiRowDivider()
                .padding(.horizontal, -Naqi.S.s4)
                .padding(.top, Naqi.S.s3)
            HStack {
                Text(.jobsOriginalStillHere)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button { showDeleteConfirm = true } label: {
                    Text(.actionDelete)
                        .font(Naqi.F.labelLarge)
                        .foregroundStyle(Naqi.C.error)
                        .frame(minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("action.deleteOriginal")
            }
            .padding(.top, Naqi.S.s2)
        }
    }

    private func copyCard(_ url: URL) -> some View {
        let bytes = Int64((try? url.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0)
        return NaqiCard {
            Text(.doneCopyNote(String(localized: fileSizeText(bytes: bytes))))
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button {
                OutputLibrary.remove(url)
                copyDropped = true
            } label: {
                Text(.doneCopyDelete)
                    .font(Naqi.F.labelLarge)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(NaqiOutlineButtonStyle())
            .padding(.top, Naqi.S.s3)
            .accessibilityIdentifier("action.deleteCopy")
        }
    }

    /// A file on disk plays straight away. A Photos publish from before the
    /// local copy existed has to go back to the library first, and reading the
    /// library needs an access level the job never asked for — the publish
    /// only ever wanted add-only. So the request happens on the tap, where
    /// the user has just said they want to see the video, and never unbidden
    /// as the screen appears.
    private func openTapped() {
        let title = outputName ?? String(localized: .dlgDeleteOriginalFallbackName)
        if let url = flow.monitor.output {
            playback = .file(url, title: title)
            return
        }
        if let libraryVideo {
            playback = .library(libraryVideo, title: title)
            return
        }
        Task {
            libraryVideo = await Self.libraryVideo(assetID)
            // Always answers, one way or the other: a refusal takes Play away
            // rather than leaving a button that does nothing when pressed.
            if let libraryVideo {
                playback = .library(libraryVideo, title: title)
            } else {
                libraryRefused = true
            }
        }
    }

    /// The published asset as something `AVPlayer` can play.
    ///
    /// `PHImageManager` hands back an `AVAsset` that reads the library file in
    /// place, which is the point: the device has just spent its whole space
    /// budget on the render and cannot afford a copy to play from. Limited
    /// access is enough — an asset the app itself created is always in the
    /// user's selection.
    static func libraryVideo(_ id: String?) async -> AVAsset? {
        guard let id else { return nil }
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        guard status == .authorized || status == .limited,
              let asset = PHAsset.fetchAssets(withLocalIdentifiers: [id], options: nil).firstObject
        else { return nil }
        let options = PHVideoRequestOptions()
        // Not `.automatic`, which may deliver a degraded version first and so
        // call back twice — and a continuation resumed twice traps.
        options.deliveryMode = .highQualityFormat
        return await withCheckedContinuation { continuation in
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { avAsset, _, _ in
                nonisolated(unsafe) let video = avAsset
                continuation.resume(returning: video)
            }
        }
    }

    private func deleteOriginal() {
        Task {
            // Always reports the outcome. Silently keeping a file the user
            // asked to delete is worse than saying we could not.
            deleteResult = await flow.deleteOriginal() ? .dlgOriginalDeleted : .dlgDeleteOriginalFailed
        }
    }
}
