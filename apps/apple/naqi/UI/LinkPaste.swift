import Foundation

/// In-app paste field and the About yt-dlp card. Hidden for the first three
/// days after the app is first opened; share-to-download stays the link path
/// on iOS meanwhile. Both stay in the binary and come back on their own.
enum LinkPaste {
    static let delay: TimeInterval = 3 * 24 * 60 * 60
    private static let key = "linkPaste.firstOpen"

    /// Stamped on the first read, so the clock starts when the app first runs.
    static let firstOpen: Date = {
        let defaults = UserDefaults.standard
        if let date = defaults.object(forKey: key) as? Date { return date }
        let now = Date.now
        defaults.set(now, forKey: key)
        return now
    }()

    static func isVisible(at now: Date = .now, firstOpen: Date = firstOpen) -> Bool {
        now >= firstOpen.addingTimeInterval(delay)
    }

    static var isOffered: Bool { isVisible() }
}
