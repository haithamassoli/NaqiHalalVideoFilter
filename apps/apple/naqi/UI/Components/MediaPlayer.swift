import AVKit
import SwiftUI
import UniformTypeIdentifiers

/// Video vs a bare audio file. Audio-only jobs always write `.m4a`; everything
/// else the pipeline publishes is an `.mp4`.
enum MediaKind {
    case video, audio

    static func of(_ url: URL) -> MediaKind {
        let ext = url.pathExtension.lowercased()
        if ["m4a", "mp3", "wav", "aac", "caf"].contains(ext) { return .audio }
        if let type = UTType(filenameExtension: ext) {
            if type.conforms(to: .audio) && !type.conforms(to: .movie) { return .audio }
        }
        return .video
    }

    static func utType(of url: URL) -> UTType {
        UTType(filenameExtension: url.pathExtension)
            ?? (of(url) == .audio ? .mpeg4Audio : .mpeg4Movie)
    }
}

/// One thing the in-app player can show. Identifiable so a sheet can bind to
/// it without recreating `AVPlayer` on every view refresh.
struct PlaybackItem: Identifiable {
    let id = UUID()
    let title: String
    let url: URL?
    let asset: AVAsset?
    var isAudio: Bool { url.map(MediaKind.of) == .audio }

    static func file(_ url: URL, title: String) -> PlaybackItem {
        PlaybackItem(title: title, url: url, asset: nil)
    }

    static func library(_ asset: AVAsset, title: String) -> PlaybackItem {
        PlaybackItem(title: title, url: nil, asset: asset)
    }
}

/// Native player chrome: `AVPlayerViewController` on iOS, `AVPlayerView` on
/// Mac. The `AVPlayer` lives here, not in the view body — constructing it
/// inline is what made Open restart the clip on every redraw.
struct MediaPlayerSheet: View {
    let item: PlaybackItem
    @Environment(\.dismiss) private var dismiss
    @State private var session: PlaybackSession?
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Group {
                if let session {
                    ZStack {
                        NativePlayerView(player: session.player)
                            .ignoresSafeArea(edges: .bottom)
                        if item.isAudio {
                            audioChrome
                                .allowsHitTesting(false)
                        }
                    }
                } else {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Naqi.C.background)
                }
            }
            .navigationTitle(item.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(.actionDone) }
                }
                if let url = item.url {
                    ToolbarItem(placement: .primaryAction) {
                        ShareLink(item: url) { Text(.actionShare) }
                    }
                    ToolbarItem(placement: .automatic) {
                        Button { saving = true } label: { Text(.actionSave) }
                    }
                }
            }
            .mediaFileExporter(isPresented: $saving, url: item.url, name: item.title)
        }
        .onAppear { session = PlaybackSession(item: item) }
        .onDisappear { session?.stop() }
    }

    /// AVPlayerViewController is a black frame for a bare audio file. The
    /// transport stays native; this only names what is playing.
    private var audioChrome: some View {
        VStack(spacing: Naqi.S.s4) {
            Spacer()
            NaqiMark().fill(Naqi.C.primary).frame(width: 72, height: 72)
            Text(item.title)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Naqi.S.gutter)
            Spacer()
            Color.clear.frame(height: 120)
        }
        .accessibilityHidden(true)
    }
}

/// Owns the `AVPlayer` for the life of the sheet.
@MainActor
final class PlaybackSession {
    let player: AVPlayer

    init(item: PlaybackItem) {
        let playerItem: AVPlayerItem
        if let url = item.url {
            playerItem = AVPlayerItem(url: url)
        } else if let asset = item.asset {
            playerItem = AVPlayerItem(asset: asset)
        } else {
            player = AVPlayer()
            return
        }
        #if os(iOS)
        Self.annotate(playerItem, title: item.title)
        #endif
        player = AVPlayer(playerItem: playerItem)
        Self.activateAudioSession()
        player.play()
    }

    func stop() {
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    #if os(iOS)
    private static func annotate(_ item: AVPlayerItem, title: String) {
        let meta = AVMutableMetadataItem()
        meta.identifier = .commonIdentifierTitle
        meta.value = title as NSString
        meta.extendedLanguageTag = "und"
        item.externalMetadata = [meta]
    }
    #endif

    private static func activateAudioSession() {
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        #endif
    }
}

#if os(iOS)
private struct NativePlayerView: UIViewControllerRepresentable {
    let player: AVPlayer

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let vc = AVPlayerViewController()
        vc.player = player
        // PiP wants the `audio` background mode, which is a 2.5.4 rejection
        // vector if used to keep a transcode alive. Foreground playback only.
        vc.allowsPictureInPicturePlayback = false
        vc.updatesNowPlayingInfoCenter = true
        return vc
    }

    func updateUIViewController(_ vc: AVPlayerViewController, context: Context) {
        if vc.player !== player { vc.player = player }
    }
}
#else
private struct NativePlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> AVPlayerView {
        let view = AVPlayerView()
        view.player = player
        view.controlsStyle = .floating
        view.updatesNowPlayingInfoCenter = true
        return view
    }

    func updateNSView(_ view: AVPlayerView, context: Context) {
        if view.player !== player { view.player = player }
    }
}
#endif

/// A file already on disk, handed to the system save panel without reading it
/// into memory. `FileWrapper(url:)` keeps a reference; `.immediate` would
/// load a 90-minute film into RAM.
struct ExportedFile: FileDocument {
    static var readableContentTypes: [UTType] { writableContentTypes }
    static var writableContentTypes: [UTType] { [.mpeg4Movie, .mpeg4Audio, .movie, .audio, .data] }
    let url: URL

    init(url: URL) { self.url = url }
    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.fileReadCorruptFile)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        try FileWrapper(url: url)
    }
}

extension View {
    /// Native Save to Files / save panel for a finished copy.
    func mediaFileExporter(isPresented: Binding<Bool>, url: URL?, name: String) -> some View {
        modifier(MediaFileExporter(isPresented: isPresented, url: url, name: name))
    }
}

private struct MediaFileExporter: ViewModifier {
    @Binding var isPresented: Bool
    let url: URL?
    let name: String

    func body(content: Content) -> some View {
        if let url {
            content.fileExporter(
                isPresented: $isPresented,
                document: ExportedFile(url: url),
                contentType: MediaKind.utType(of: url),
                defaultFilename: URL(fileURLWithPath: name).deletingPathExtension().lastPathComponent
            ) { _ in }
        } else {
            content
        }
    }
}
