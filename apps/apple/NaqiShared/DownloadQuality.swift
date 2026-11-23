import Foundation

/// The quality choices the sheet offers. A fixed set on purpose — the PRD
/// forbids ever rendering yt-dlp's raw format table at the user; selection is
/// Swift (`select(_:processing:hw:)`) on every platform. Names match Android
/// `Downloader.Quality` so a shared last-used value stays meaningful if the two
/// trees ever share prefs.
enum DownloadQuality: String, Codable, Sendable, CaseIterable {
    case best = "BEST"
    case p1080 = "P1080"
    case p720 = "P720"
    case p480 = "P480"
    case audio = "AUDIO"

    var heightCap: Int? {
        switch self {
        case .best: nil
        case .p1080: 1080
        case .p720: 720
        case .p480: 480
        case .audio: nil
        }
    }

    /// Fast mode avoids transferring pixels it will immediately discard.
    /// Explicitly smaller and audio-only choices remain untouched.
    func resolved(fast: Bool) -> DownloadQuality {
        guard fast else { return self }
        return switch self {
        case .best, .p1080: .p720
        case .p720, .p480, .audio: self
        }
    }

    static func of(_ name: String?) -> DownloadQuality {
        DownloadQuality(rawValue: name ?? "") ?? .best
    }

    private static let key = "naqi.downloadQuality"

    static func loadLastUsed() -> DownloadQuality {
        of(AppGroup.defaults?.string(forKey: key) ?? UserDefaults.standard.string(forKey: key))
    }

    func saveAsLastUsed() {
        (AppGroup.defaults ?? .standard).set(rawValue, forKey: Self.key)
    }
}

/// First http(s) URL in ordinary text, excluding sentence punctuation at the
/// end — people paste "look at this https://…", not a bare URL.
///
/// Shared with the pick screen's link field on purpose: both are the same
/// trust boundary, and two copies would mean fixing one for a new URL shape
/// and silently leaving the other stricter. Ported from Android `URL_IN_TEXT`.
enum VideoURL {
    static let inText = try! NSRegularExpression(
        pattern: #"https?://[\w\-]+(\.[\w\-]+)+([\w\-.,@?^=%&:/~+#]*[\w\-@?^=%&/~+#])?"#)

    static func first(in text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        guard let match = inText.firstMatch(in: text, range: range),
              let swift = Range(match.range, in: text) else { return nil }
        return String(text[swift])
    }
}
