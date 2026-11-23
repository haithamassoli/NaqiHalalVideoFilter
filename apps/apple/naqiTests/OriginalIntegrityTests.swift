import CryptoKit
import Foundation
import Testing
@testable import naqi

/// The product's central promise, and the one claim on the storefront that
/// cannot be allowed to be false: **Naqi never modifies the file you picked.**
///
/// Every other suite tests that the *output* is right. This one tests that the
/// *input* is unchanged, through the real queue, on the shape most likely to
/// break it — a both-ops job, which is the only shape that writes an
/// intermediate audio file and re-muxes, and therefore the only one with a
/// plausible path to touching the source by mistake (`moveItem` instead of
/// `copyItem`, `shouldMoveFile`, or writing in place).
///
/// A `fileExists` check does not cover this. A job that truncated the source to
/// zero bytes would pass one.
@Suite("Original integrity", .serialized)
struct OriginalIntegrityTests {

    private struct Fingerprint: Equatable {
        var sha256: String
        var bytes: Int
        var modified: Date?
    }

    private func fingerprint(_ url: URL) throws -> Fingerprint {
        // Streamed, not `Data(contentsOf:)` — a feature film would not fit, and
        // this helper should be usable on one.
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        var bytes = 0
        while let chunk = try handle.read(upToCount: 4 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
            bytes += chunk.count
        }
        let rv = try? url.resourceValues(forKeys: [.contentModificationDateKey])
        return Fingerprint(sha256: hasher.finalize().map { String(format: "%02x", $0) }.joined(),
                           bytes: bytes,
                           modified: rv?.contentModificationDate)
    }

    @Test("a full both-ops job leaves the source byte-identical")
    func sourceSurvivesRealJob() async throws {
        let qa = try requireQAVideo()

        // Work on a copy so the test owns the file, then verify THAT copy — the
        // bundled fixture is read-only inside the app bundle, which would hide
        // a bug that only fires on a writable source.
        let source = Fixtures.scratch("integrity-source.mp4")
        try FileManager.default.copyItem(at: qa, to: source)
        let before = try fingerprint(source)

        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = true

        let outFolder = Fixtures.scratch("integrity-out")
        try FileManager.default.createDirectory(at: outFolder, withIntermediateDirectories: true)
        let queue = JobQueue(storeURL: Fixtures.scratch("integrity-queue.json"))
        _ = await queue.enqueue(Job.capture(source: source, ops: ops,
                                            destination: .userFolder, folder: outFolder))
        try await settle(timeout: .seconds(600)) { await queue.jobs.allSatisfy(\.state.isTerminal) }

        let job = try #require(await queue.jobs.first)
        // A job that failed proves nothing about the promise — it may not have
        // reached the code that would have broken it.
        guard case .done = job.state else {
            Issue.record("job did not complete: \(job.state) — integrity unproven, not proven")
            return
        }

        let after = try fingerprint(source)
        #expect(after.sha256 == before.sha256, "the source file was MODIFIED by a job")
        #expect(after.bytes == before.bytes)
        #expect(after.modified == before.modified,
                "the source's mtime changed — something opened it for writing")

        try? FileManager.default.removeItem(at: source)
        try? FileManager.default.removeItem(at: outFolder)
    }

    /// A cancelled job is the other way a source gets damaged: cleanup code that
    /// deletes "the temp file" and gets the wrong URL.
    @Test("cancelling mid-job leaves the source byte-identical")
    func sourceSurvivesCancel() async throws {
        let qa = try requireQAVideo()
        let source = Fixtures.scratch("integrity-cancel-source.mp4")
        try FileManager.default.copyItem(at: qa, to: source)
        let before = try fingerprint(source)

        var ops = FilterOps()
        ops.censor = true
        let outFolder = Fixtures.scratch("integrity-cancel-out")
        try FileManager.default.createDirectory(at: outFolder, withIntermediateDirectories: true)
        let queue = JobQueue(storeURL: Fixtures.scratch("integrity-cancel-queue.json"))
        let id = await queue.enqueue(Job.capture(source: source, ops: ops,
                                                 destination: .userFolder, folder: outFolder))

        // Let it get past preflight and into real work before pulling the rug:
        // cancelling a `.pending` job never reaches the cleanup code this test
        // is aimed at.
        try await settle { await queue.jobs.first?.state == .running }
        await queue.cancel(id)
        try await settle { await queue.jobs.allSatisfy(\.state.isTerminal) }

        let after = try fingerprint(source)
        #expect(after.sha256 == before.sha256, "cancellation cleanup damaged the source")
        #expect(after.modified == before.modified)

        try? FileManager.default.removeItem(at: source)
        try? FileManager.default.removeItem(at: outFolder)
    }

    /// Polls rather than sleeping a fixed time: the queue hops through an actor
    /// and its own task, and a fixed sleep is either slow or flaky.
    private func settle(timeout: Duration = .seconds(30),
                        _ condition: @Sendable () async -> Bool) async throws {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        Issue.record("timed out waiting for the queue to settle")
    }
}
