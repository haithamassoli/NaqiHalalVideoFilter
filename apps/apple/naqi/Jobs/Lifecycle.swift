import Foundation
import os
#if os(iOS)
import UIKit
#endif

/// iOS foreground reality.
///
/// There is no background mode that lets an hours-long transcode keep running,
/// so the honest design is two things and no more: hold the screen awake while
/// the user is here, and when they leave, spend the expiration grace on a
/// checkpoint rather than on pretending to continue. A suspension then costs
/// whatever was in flight and nothing that was already checkpointed.
///
/// macOS has no suspension problem, so none of that machinery exists there —
/// only the idle-sleep assertion, which is the same promise on a Mac three
/// hours into a film.
@MainActor
final class Lifecycle {
    nonisolated static let shared = Lifecycle()
    nonisolated init() {}

    /// Polled by the running job from AVFoundation's own queues, so the flag
    /// lives outside the main actor.
    private nonisolated let flag = OSAllocatedUnfairLock(initialState: false)

    /// True once the OS has warned that the app is about to be suspended. The
    /// runner reads it as `.interrupted`, which keeps the work directory —
    /// unlike a user cancel, which destroys it.
    nonisolated var isInterrupted: Bool { flag.withLock { $0 } }

    private var onBackground: (() -> Void)?
    private var observers: [any NSObjectProtocol] = []
    #if os(iOS)
    private var graceTask: UIBackgroundTaskIdentifier = .invalid
    #elseif os(macOS)
    private var sleepAssertion: (any NSObjectProtocol)?
    #endif

    /// - Parameter onBackground: the checkpoint flush. Runs the moment the app
    ///   leaves the foreground, while there is still a full runtime budget —
    ///   not from the expiration handler, which fires with seconds left.
    func jobStarted(title: String, onBackground: @escaping () -> Void = {}) {
        flag.withLock { $0 = false }
        self.onBackground = onBackground
        keepAwake(true)
        guard observers.isEmpty else { return }
        #if os(iOS)
        observe(UIApplication.didEnterBackgroundNotification) { $0.didEnterBackground() }
        observe(UIApplication.willEnterForegroundNotification) { $0.willEnterForeground() }
        #endif
    }

    func jobFinished() {
        self.onBackground = nil
        keepAwake(false)
        for o in observers { NotificationCenter.default.removeObserver(o) }
        observers.removeAll()
        #if os(iOS)
        endGrace()
        #endif
    }

    /// The user-facing toggle. Sleeping mid-job is the same loss on both
    /// platforms; only the API differs.
    func keepAwake(_ on: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = on
        #elseif os(macOS)
        if on {
            guard sleepAssertion == nil else { return }
            sleepAssertion = ProcessInfo.processInfo.beginActivity(
                options: [.idleSystemSleepDisabled, .userInitiated], reason: "Naqi is filtering a video")
        } else if let a = sleepAssertion {
            ProcessInfo.processInfo.endActivity(a)
            sleepAssertion = nil
        }
        #endif
    }

    private func observe(_ name: Notification.Name, _ body: @escaping @MainActor (Lifecycle) -> Void) {
        observers.append(NotificationCenter.default.addObserver(
            forName: name, object: nil, queue: .main
        ) { [weak self] _ in
            MainActor.assumeIsolated { if let self { body(self) } }
        })
    }

    #if os(iOS)
    private func didEnterBackground() {
        onBackground?()
        Task { await LiveActivity.setBackground(true) }
        guard graceTask == .invalid else { return }
        // ~30 s of runtime, then the expiration handler, then suspension. The
        // job is NOT stopped here: a two-second app switch should not cost a
        // 90-minute film. It is stopped when the grace actually runs out.
        graceTask = UIApplication.shared.beginBackgroundTask(withName: "naqi.checkpoint") { [weak self] in
            MainActor.assumeIsolated { self?.expire() }
        }
        Log.job.notice("backgrounded, grace period started")
    }

    private func expire() {
        flag.withLock { $0 = true }
        BackgroundWork.schedule()
        Log.job.notice("background grace expired: winding the job up to a checkpoint")
        endGrace()
    }

    private func willEnterForeground() {
        // Either the job already wound up — in which case the queue has marked
        // it resumable and the flag is moot — or it is still running and should
        // keep running.
        flag.withLock { $0 = false }
        Task { await LiveActivity.setBackground(false) }
        endGrace()
    }

    private func endGrace() {
        guard graceTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(graceTask)
        graceTask = .invalid
    }
    #endif
}
