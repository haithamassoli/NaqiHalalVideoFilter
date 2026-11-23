#if os(iOS)
import BackgroundTasks
import Foundation
import os

enum BackgroundWork {
    static let identifier = "com.haithamassoli.naqi.processing"

    static func schedule() {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresExternalPower = true
        request.requiresNetworkConnectivity = false
        do {
            try BGTaskScheduler.shared.submit(request)
            Log.job.notice("scheduled background checkpoint resume")
        } catch {
            Log.job.error("background resume scheduling failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    static func run() async {
        let queue = JobQueue.shared
        guard let id = await queue.startResumableHead() else { return }
        for await snapshot in await queue.observe() {
            guard let state = snapshot.jobs.first(where: { $0.id == id })?.state,
                  !state.isTerminal else { return }
        }
    }
}
#endif
