import Foundation
import Testing
@testable import naqi

/// Share-extension prefetch (plan Phase 6): the extraction cache that hands
/// the sheet's player response to the app, and the sheet's size preflight.
@Suite("Share prefetch")
struct SharePrefetchTests {

    private static let url = "https://youtu.be/aqz-KE-bpKQ"

    private static func tempDir() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("extract-cache-\(UUID().uuidString)", isDirectory: true)
    }

    @Test("a cached player response is what extract returns, with no network")
    func cacheRoundTrip() async throws {
        let dir = Self.tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        NativeExtract.Cache.store(try ExtractorTests.fixture(), url: Self.url, client: "VISIONOS", dir: dir)

        let media = try await NativeExtract.extract(Self.url, cacheDir: dir)
        let expected = try ExtractorTests.media()
        #expect(media.formats == expected.formats)
        #expect(media.title == expected.title)
        #expect(media.durationSec == expected.durationSec)
        #expect(media.client == "VISIONOS")
        // Trimmed to what the parser reads.
        let size = try #require(try NativeExtract.Cache.file(Self.url, dir: dir)
            .resourceValues(forKeys: [.fileSizeKey]).fileSize)
        #expect(size < 64 * 1024)
    }

    @Test("fresh drops the entry instead of returning it")
    func freshBypasses() throws {
        let dir = Self.tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        NativeExtract.Cache.store(try ExtractorTests.fixture(), url: Self.url, client: "VISIONOS", dir: dir)
        let file = NativeExtract.Cache.file(Self.url, dir: dir)

        #expect(NativeExtract.Cache.lookup(Self.url, id: "aqz-KE-bpKQ", fresh: false, dir: dir) != nil)
        #expect(NativeExtract.Cache.lookup(Self.url, id: "aqz-KE-bpKQ", fresh: true, dir: dir) == nil)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test("an entry older than an hour is ignored and pruned on the next write")
    func expires() throws {
        let dir = Self.tempDir()
        defer { try? FileManager.default.removeItem(at: dir) }
        let root = try ExtractorTests.fixture()
        let old = Date.now.addingTimeInterval(-3601)
        NativeExtract.Cache.store(root, url: Self.url, client: "VISIONOS", dir: dir, now: old)
        let file = NativeExtract.Cache.file(Self.url, dir: dir)
        try FileManager.default.setAttributes([.modificationDate: old], ofItemAtPath: file.path)

        #expect(NativeExtract.Cache.lookup(Self.url, id: "aqz-KE-bpKQ", fresh: false, dir: dir) == nil)
        NativeExtract.Cache.store(root, url: "https://youtu.be/other", client: "VISIONOS", dir: dir)
        #expect(!FileManager.default.fileExists(atPath: file.path))
    }

    @Test("download size follows the job's quality, fast mode and processing")
    func downloadBytes() throws {
        let media = try ExtractorTests.media()
        let hw = DeviceCodecs(av1: false, hevc: false)
        func expected(_ q: DownloadQuality, _ p: Processing) -> Int64 {
            q.select(media.formats, processing: p, hw: hw).compactMap(\.filesize).reduce(0, +)
        }
        let plain = ShareOptions(removeMusic: false, censor: false)
        #expect(media.downloadBytes(.p720, options: plain, hw: hw) == expected(.p720, .none))
        let censorFast = ShareOptions(censor: true, processingMode: "fast")
        // Fast resolves Best to 720p.
        #expect(media.downloadBytes(.best, options: censorFast, hw: hw) == expected(.p720, .visual))
        #expect(expected(.p720, .visual) < expected(.best, .visual))
        #expect(media.downloadBytes(.audio, options: plain, hw: hw) == expected(.audio, .none))

        var unsized = media
        unsized.formats = unsized.formats.map { var f = $0; f.filesize = nil; return f }
        #expect(unsized.downloadBytes(.p720, options: plain, hw: hw) == nil)
    }

    @Test("the sheet's space rule is Preflight's", arguments: [
        (false, false, Int64(0)), (true, false, 1), (false, true, 1), (true, true, 2),
    ])
    func requiredBytes(removeMusic: Bool, censor: Bool, copies: Int64) {
        let options = ShareOptions(removeMusic: removeMusic, censor: censor)
        let aac = removeMusic ? 600 * Preflight.aacBytesPerSecond : 0
        #expect(SpaceBudget.requiredBytes(downloadBytes: 1_000_000, options: options, durationSec: 600)
                == Preflight.requiredBytes(sourceBytes: 1_000_000, tempCopies: copies, extraScratch: aac))
        #expect(Preflight.requiredBytes(sourceBytes: 10, tempCopies: 1, extraScratch: 5)
                == 25 + Preflight.slackBytes)
    }
}
