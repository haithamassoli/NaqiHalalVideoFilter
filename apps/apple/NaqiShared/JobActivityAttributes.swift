#if canImport(ActivityKit) && os(iOS)
import ActivityKit
import Foundation

/// The ongoing-progress surface: Android's foreground-service notification with
/// a determinate 0-100 bar.
///
/// Compiled into both the app (which posts updates) and the widget extension
/// (which renders them). ActivityKit matches the two by type name *and* by the
/// `ContentState` encoding, so this cannot be two declarations that merely look
/// alike — it has to be one file in both targets.
struct NaqiJobAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var phase: Phase = .running
        /// Already localized by the app. The extension has no string catalog,
        /// and the app's language is a per-app choice the extension cannot see.
        var caption: String
        /// Secondary line, localized: time left and queue size, or what to do
        /// next. Empty hides it.
        var detail: String = ""
        /// SF Symbol for the stage, so the extension never maps stage names.
        var symbol: String
        var fraction: Double
    }

    enum Phase: String, Codable, Hashable {
        /// `paused` is iOS suspending the app, not the user: the checkpoint is
        /// kept and the card says so instead of freezing on a stale percent.
        case running, done, paused, failed
    }

    var title: String
    /// The app's own language direction, not the system's.
    var rightToLeft = false
    /// What the card says once it goes stale. iOS suspends the app ~30 s after
    /// it leaves the foreground, and a suspended app posts nothing — so the
    /// words have to be on the card before they are needed.
    var pausedCaption = ""
    var pausedDetail = ""
}
#endif
