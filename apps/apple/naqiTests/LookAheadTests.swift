import Testing
import Foundation
import os
@testable import naqi

/// Phase 8: the queue downloads the next link while the running job filters.
/// The runner and the download are stand-ins, so nothing touches the network.
@Suite("Look-ahead download", .serialized)
struct LookAheadTests {

    /// What the stand-in download saw, and whether it was told to stop.
    final class Calls: Sendable {
        let started = OSAllocatedUnfairLock<[(url: String, quality: DownloadQuality)]>(initialState: [])
        let stopped = OSAllocatedUnfairLock(initialState: false)
    }

    /// Job 1 posts `.download`, waits for `gate`, posts `.analyze`, then
    /// blocks until stopped. Every other job stops at once.
    private static func queue(_ store: URL, first: Job.ID, gate: OSAllocatedUnfairLock<Bool>,
                              calls: Calls, mayPrefetch: Bool = true) -> JobQueue {
        JobQueue(storeURL: store, run: { job, progress, stop in
            if job.id == first {
                var p = JobProgress(shape: .censorOnly, removeMusic: false)
                p.post(.download, 0.5)
                progress(p)
                while !gate.withLock({ $0 }), stop() == nil { try await Task.sleep(for: .milliseconds(10)) }
                p = JobProgress(shape: .censorOnly, removeMusic: false)
                p.post(.analyze, 0.1)
                progress(p)
                while stop() == nil { try await Task.sleep(for: .milliseconds(10)) }
            }
            throw JobStopped(reason: stop() ?? .userCancelled, resumable: true)
        }, prefetch: { url, quality, _, isCancelled in
            calls.started.withLock { $0.append((url, quality)) }
            // The queue cancels the task too, so the sleep may throw first.
            defer { calls.stopped.withLock { $0 = isCancelled() } }
            while !isCancelled() { try await Task.sleep(for: .milliseconds(10)) }
            throw DownloadError.cancelled
        }, mayPrefetch: { mayPrefetch })
    }

    private static func links() -> (Job, Job) {
        var fast = FilterOps()
        fast.processingMode = .fast
        return (Job.captureLink("https://a.example/\(UUID())", quality: .best, ops: FilterOps(), destination: .photos),
                Job.captureLink("https://b.example/\(UUID())", quality: .best, ops: fast, destination: .photos))
    }

    @Test("the next link downloads once the running job has left .download, at the job's own quality")
    func startsAfterDownloadStage() async throws {
        let store = Fixtures.scratch("lookahead-start.json")
        defer { try? FileManager.default.removeItem(at: store) }
        let (job1, job2) = Self.links()
        let gate = OSAllocatedUnfairLock(initialState: false), calls = Calls()
        let queue = Self.queue(store, first: job1.id, gate: gate, calls: calls)
        await queue.enqueue(job1)
        await queue.enqueue(job2)

        try await Task.sleep(for: .milliseconds(200))
        #expect(calls.started.withLock { $0.isEmpty }, "job 1 is still downloading")

        gate.withLock { $0 = true }
        try await Self.waitUntil { !calls.started.withLock { $0.isEmpty } }
        let started = calls.started.withLock { $0 }
        #expect(started.count == 1)
        #expect(started.first?.url == job2.remoteURL)
        // Fast mode turns BEST into 720p: the same plan `JobRunner` downloads.
        #expect(started.first?.quality == .p720)

        await queue.cancel(job2.id)
        await queue.cancel(job1.id)
    }

    @Test("cancelling the queued job stops its look-ahead and drops its quarantine")
    func cancelStopsIt() async throws {
        let store = Fixtures.scratch("lookahead-cancel.json")
        defer { try? FileManager.default.removeItem(at: store) }
        let (job1, job2) = Self.links()
        let quarantine = Downloader.quarantineDir(for: job2.remoteURL!)
        let gate = OSAllocatedUnfairLock(initialState: false), calls = Calls()
        let queue = Self.queue(store, first: job1.id, gate: gate, calls: calls)
        await queue.enqueue(job1)
        await queue.enqueue(job2)
        gate.withLock { $0 = true }
        try await Self.waitUntil { !calls.started.withLock { $0.isEmpty } }

        await queue.cancel(job2.id)
        try await Self.waitUntil { calls.stopped.withLock { $0 } }
        try await Self.waitUntil { !FileManager.default.fileExists(atPath: quarantine.path) }
        await queue.cancel(job1.id)
    }

    @Test("no look-ahead when the device says not now")
    func skippedInLowPower() async throws {
        let store = Fixtures.scratch("lookahead-lowpower.json")
        defer { try? FileManager.default.removeItem(at: store) }
        let (job1, job2) = Self.links()
        let gate = OSAllocatedUnfairLock(initialState: false), calls = Calls()
        let queue = Self.queue(store, first: job1.id, gate: gate, calls: calls, mayPrefetch: false)
        await queue.enqueue(job1)
        await queue.enqueue(job2)
        gate.withLock { $0 = true }

        try await Task.sleep(for: .milliseconds(300))
        #expect(calls.started.withLock { $0.isEmpty })
        await queue.cancel(job2.id)
        await queue.cancel(job1.id)
    }

    private static func waitUntil(_ condition: @Sendable () async -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(10)
        while ContinuousClock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        Issue.record("Timed out waiting")
    }
}
