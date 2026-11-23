import Foundation
import Testing
@testable import naqi

/// Phase 3's error taxonomy: yt-dlp stderr → `DownloadError` → `JobFailure`,
/// and which of those earn a yt-dlp update.
@Suite("Download errors")
struct DownloadErrorTests {

    /// The case name only; the associated message is the input echoed back.
    private static func kind(_ e: DownloadError) -> String {
        String(describing: e).prefix { $0 != "(" }.description
    }

    /// Real yt-dlp messages (the Android suite's, from S23 logcat and Mac
    /// runs), including the warnings that precede them.
    @Test("yt-dlp stderr classifies to the right case", arguments: [
        ("WARNING: [vimeo] The extractor is attempting impersonation, but no impersonate target is available.\n"
         + "ERROR: [vimeo] 1084537: The web client only works when logged-in. Use --cookies, --cookies-from-browser",
         "unavailable"),
        ("ERROR: [youtube] aaaaaaaaaaa: Video unavailable. This video is not available", "unavailable"),
        ("ERROR: [youtube] aaaaaaaaaaa: This video is unavailable", "unavailable"),
        ("ERROR: [youtube] x: Private video. Sign in if you've been granted access to this video", "unavailable"),
        ("ERROR: [youtube] x: Sign in to confirm your age. This video may be inappropriate for some users.", "unavailable"),
        ("ERROR: [youtube] x: Join this channel to get access to members-only content like this video", "unavailable"),
        ("ERROR: Unsupported URL: https://example.com/", "unsupported"),
        ("ERROR: [youtube] x: Sign in to confirm you're not a bot. Use --cookies-from-browser", "extractor"),
        ("ERROR: [youtube] x: The uploader has not made this video available in your country", "geo"),
        ("ERROR: unable to download video data: HTTP Error 403: Forbidden", "forbidden"),
        ("ERROR: [youtube] x: HTTP Error 429: Too Many Requests", "rateLimited"),
        ("ERROR: [youtube] x: Requested format is not available. Use --list-formats", "extractor"),
        ("ERROR: [youtube] x: Unable to extract nsig function code", "extractor"),
        ("ERROR: [generic] Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>",
         "network"),
        ("ERROR: unable to download video data: <urlopen error _ssl.c:1000: The handshake operation timed out>",
         "network"),
        ("ERROR: unable to download video data: HTTP Error 503: Service Unavailable", "network"),
        ("No space left on device (download aborted)", "noSpace"),
        // A warning about a retried fragment must not decide an unrelated error.
        ("WARNING: [download] Got error: Connection reset. Retrying fragment 3\nERROR: [youtube] x: Video unavailable",
         "unavailable"),
        ("something new and strange", "generic"),
    ])
    func classify(_ message: String, _ expected: String) {
        #expect(Self.kind(YtDlp.classify(message)) == expected)
    }

    @Test("each new download error has its own JobFailure")
    func failureMap() {
        #expect(JobFailure.of(DownloadError.unavailable("x")) == .downloadUnavailable)
        #expect(JobFailure.of(DownloadError.geo("x")) == .downloadGeo)
        #expect(JobFailure.of(DownloadError.rateLimited) == .downloadRateLimited)
        #expect(JobFailure.of(DownloadError.forbidden) == .downloadForbidden)
        #expect(JobFailure.of(DownloadError.extractor("x")) == .downloadExtractor)
        #expect(JobFailure.of(DownloadError.noFile) == .downloadGeneric)
    }

    @Test("a stored failure decodes by raw value")
    func failureCodable() throws {
        let data = try JSONEncoder().encode([JobFailure.downloadGeo, .downloadNetwork])
        #expect(try JSONDecoder().decode([JobFailure].self, from: data) == [.downloadGeo, .downloadNetwork])
    }

    @Test("errors an update cannot fix fail at once, without one", arguments: [
        DownloadError.unavailable("x"), .geo("x"), .rateLimited, .forbidden, .network("x"), .noSpace,
    ])
    func noUpdate(_ error: DownloadError) async {
        var calls = 0
        var updates = 0
        await #expect(throws: DownloadError.self) {
            try await YtDlp.retryingAfterUpdate {
                calls += 1
                throw error
            } update: { updates += 1 } lastUpdate: { .distantPast }
        }
        #expect(calls == 1 && updates == 0)
    }

    @Test("an extractor failure updates once, then not again within 6 h")
    func updateThrottle() async throws {
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        var last = Date.distantPast
        var updates = 0
        func attempt(at now: Date) async -> Int {
            var calls = 0
            let _: Void? = try? await YtDlp.retryingAfterUpdate({
                calls += 1
                throw DownloadError.extractor("Unable to extract")
            }, update: {
                updates += 1
                last = now
            }, lastUpdate: { last }, now: now)
            return calls
        }
        #expect(await attempt(at: start) == 2 && updates == 1)
        #expect(await attempt(at: start + 5 * 3600) == 1 && updates == 1)
        #expect(await attempt(at: start + 6 * 3600 + 1) == 2 && updates == 2)
    }
}
