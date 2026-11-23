import SwiftUI
#if os(iOS)
import BackgroundTasks
import os
import UIKit
#endif

@main
struct NaqiApp: App {
    #if os(iOS)
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    #endif

    var body: some Scene {
        WindowGroup {
            // The share-inbox drain lives in `RootView` and not here because it
            // needs the destination the user last chose, and `Flow` is what
            // holds it: `ExportTarget.loadLastUsed()` opens a security scope it
            // never closes, so calling it again on every foreground would leak
            // one sandbox extension per activation.
            RootView()
                .font(Naqi.F.body)
        }
        #if os(macOS)
        .defaultSize(width: 760, height: 820)
        #endif
    }
}

#if os(iOS)
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Bar titles and segmented pickers are UIKit and ignore SwiftUI's `.font`.
        if let title = UIFont(name: "thmanyahsans-Bold", size: 17) {
            UINavigationBar.appearance().titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .headline).scaledFont(for: title)]
        }
        if let seg = UIFont(name: "thmanyahsans-Medium", size: 13) {
            UISegmentedControl.appearance().setTitleTextAttributes([.font: UIFontMetrics(forTextStyle: .footnote).scaledFont(for: seg)], for: .normal)
        }
        BGTaskScheduler.shared.register(forTaskWithIdentifier: BackgroundWork.identifier, using: nil) { task in
            guard let task = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            // BGTask is delivered on the registration queue and is not
            // Sendable. Keep ownership here; the runner immediately awaits
            // actor-isolated work and does not block the main actor.
            let expired = OSAllocatedUnfairLock(initialState: false)
            let work = Task {
                await BackgroundWork.run()
                let didExpire = expired.withLock { $0 }
                if !didExpire, await JobQueue.shared.hasResumableJob() { BackgroundWork.schedule() }
                task.setTaskCompleted(success: !didExpire)
            }
            task.expirationHandler = {
                expired.withLock { $0 = true }
                JobQueue.shared.signalBackgroundExpiration()
                BackgroundWork.schedule()
            }
            _ = work
        }
        return true
    }
}
#endif
