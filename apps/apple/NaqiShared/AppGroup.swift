import Foundation

/// The one identifier the app, the share extension and the widget must agree
/// on, plus the two paths derived from it.
///
/// This file is compiled into all three targets (a synchronized group listed in
/// each), which is the point: a share extension that writes to a container the
/// app does not read is a bug with no compile error and no runtime error — the
/// video simply never arrives. Sharing the constant makes that unrepresentable.
enum AppGroup {
    static let identifier = "group.com.haithamassoli.naqi"

    static var container: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    /// Where the extension drops shared videos and the app picks them up.
    static var inbox: URL? {
        container?.appendingPathComponent("Inbox", isDirectory: true)
    }

    /// Shared defaults. The app writes last-used options here so a share-in can
    /// inherit them without the extension having to ask.
    static var defaults: UserDefaults? { UserDefaults(suiteName: identifier) }
}

/// The three choices the share sheet can override without importing the app's
/// full pipeline model into the extension target.
struct ShareOptions: Codable, Sendable, Equatable {
    private static let key = "naqi.shareOptions"
    var removeMusic = false
    var censor = true
    var who = "women"
    /// Nil is Current quality, preserving manifests written before Fast mode.
    var processingMode: String? = nil

    static func loadLastUsed() -> ShareOptions {
        if let data = AppGroup.defaults?.data(forKey: key),
           let options = try? JSONDecoder().decode(ShareOptions.self, from: data) {
            return options
        }
        struct Stored: Decodable {
            var removeMusic: Bool?
            var censor: Bool?
            var who: String?
            var processingMode: String?
        }
        guard let data = AppGroup.defaults?.data(forKey: "naqi.filterOps"),
              let stored = try? JSONDecoder().decode(Stored.self, from: data)
        else { return ShareOptions() }
        return ShareOptions(removeMusic: stored.removeMusic ?? false,
                            censor: stored.censor ?? true,
                            who: stored.who ?? "women",
                            processingMode: stored.processingMode)
    }

    func saveAsLastUsed() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        AppGroup.defaults?.set(data, forKey: Self.key)
    }
}

/// What the extension writes beside each copied video — or, for a shared
/// link, instead of a copied video. `url` set and no media file is a link
/// the app will fetch with yt-dlp; a media file and no `url` is the original
/// share-in. Older manifests have neither `url` nor `quality` and still decode.
struct ShareManifest: Codable, Sendable {
    var id: UUID
    var fileName: String
    var receivedAt: Date
    /// Nil keeps manifests written by older extension builds decodable.
    var options: ShareOptions? = nil
    /// http(s) URL to fetch. Mutually exclusive with a media file of `id`.
    var url: String? = nil
    /// `DownloadQuality.rawValue`. Nil means last-used / BEST.
    var quality: String? = nil

    static func mediaURL(_ dir: URL, id: UUID, ext: String) -> URL {
        dir.appendingPathComponent("\(id.uuidString).\(ext)")
    }

    /// **The manifest is the completion marker.** The extension copies the
    /// movie first and writes `<id>.json` only once the copy has finished, so
    /// the app can never pick up a half-copied video — the same atomicity story
    /// as the checkpoint layer, and for the same reason: no second piece of
    /// state to keep in sync with the first.
    static func manifestURL(_ dir: URL, id: UUID) -> URL {
        dir.appendingPathComponent("\(id.uuidString).json")
    }
}
