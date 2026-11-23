import Foundation
#if canImport(UIKit) && os(iOS)
import UIKit
import UserNotifications
#endif

/// The one thing that tells the user a job ended.
///
/// A 90-minute job outlives the user's attention, and `LiveActivity.end`
/// dismisses the lock-screen card `.immediate` the moment the job stops — so
/// without this, the end of an hour of work is announced by nothing at all.
///
/// iOS only, the same way `LiveActivity` is.
/// ponytail: a Mac gets nothing. The foreground test below is UIKit's, so macOS
/// would need `NSApplication.isActive` and a second authorization path, for a
/// platform where the window that says "Done" is already open.
@MainActor
enum Notify {

    /// Asked when a job starts, not at launch: a permission sheet on first
    /// launch asks for something the app cannot yet justify, and the same sheet
    /// the moment an hour-long job begins justifies itself.
    ///
    /// Deliberately not awaited. The answer only has to be in by the time the
    /// job *ends*, which is an hour away, and a job that sits at its own
    /// starting line waiting for a permission sheet is a job the user is
    /// watching do nothing.
    static func requestAuthorization() {
        #if canImport(UIKit) && os(iOS)
        Task {
            _ = try? await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound])
        }
        #endif
    }

    static func done(name: String) {
        post(.notifDoneTitle, .notifDoneBody(name))
    }

    /// Title only: there is no body worth writing that the Progress screen's
    /// own failure sentence does not say better once the user is back.
    static func failed() {
        post(.notifFailedTitle, nil)
    }

    /// Silent while the app is in front — the Progress screen is already saying
    /// this, in more detail, on the screen the user is looking at.
    ///
    /// `applicationState` rather than a `UNUserNotificationCenterDelegate`
    /// returning `[]` from `willPresent`: the delegate route needs a class, a
    /// stored reference to keep it alive and a registration at launch to answer
    /// the same question this one line answers.
    private static func post(_ title: LocalizedStringResource,
                             _ body: LocalizedStringResource?) {
        #if canImport(UIKit) && os(iOS)
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: title)
        if let body { content.body = String(localized: body) }
        content.sound = .default
        // No trigger means "deliver now"; the identifier is unique so a second
        // finished job does not replace the first one's notification.
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
        #endif
    }
}
