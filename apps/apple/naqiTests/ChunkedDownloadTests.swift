import Foundation
import Testing
import os
@testable import naqi

@Suite("Chunked download", .serialized)
struct ChunkedDownloadTests {

    /// 25 MB: two full 10 MiB chunks and a short third.
    static let body: Data = {
        var d = Data(count: 25_000_000)
        d.withUnsafeMutableBytes { arc4random_buf($0.baseAddress, $0.count) }
        return d
    }()
    static let chunk = Downloader.chunkSize

    @Test("three chunks are stitched byte-identical")
    func stitched() async throws {
        let server = RangeServer()
        let run = Run(server)
        try await run.fetch()
        #expect(try Data(contentsOf: run.dest) == Self.body)
        #expect(server.ranges == [0...Self.chunk - 1, Self.chunk...2 * Self.chunk - 1,
                                  2 * Self.chunk...Int64(Self.body.count) - 1])
        #expect(run.meter.counters.chunks == 3)
        #expect(!FileManager.default.fileExists(atPath: run.part.path))
        #expect(!FileManager.default.fileExists(atPath: run.part.appendingPathExtension("json").path))
    }

    @Test("a cancel after chunk 1 resumes with chunks 2 and 3 only")
    func resumes() async throws {
        let server = RangeServer()
        let run = Run(server)
        let path = run.part.path
        // Not `resourceValues`: a URL caches those, and this must see the file grow.
        let size: @Sendable () -> Int64 = {
            ((try? FileManager.default.attributesOfItem(atPath: path)[.size]) as? NSNumber)?.int64Value ?? 0
        }
        let part = run.part
        await #expect(throws: DownloadError.self) {
            try await run.fetch(isCancelled: { size() >= Self.chunk })
        }
        #expect(Preflight.fileSize(part) == Self.chunk, "the .part keeps exactly chunk 1")

        server.reset()
        try await run.fetch()
        #expect(try Data(contentsOf: run.dest) == Self.body)
        #expect(server.ranges.first?.lowerBound == Self.chunk)
        #expect(server.requestedBytes == Int64(Self.body.count) - Self.chunk)
    }

    @Test("a 500 on chunk 2 backs off and retries")
    func retries() async throws {
        let server = RangeServer()
        server.failOnce[Self.chunk] = 500
        let run = Run(server)
        try await run.fetch()
        #expect(try Data(contentsOf: run.dest) == Self.body)
        #expect(run.backoffs.withLock { $0 } == [1])
        #expect(run.meter.counters.retries == 1)
        #expect(server.ranges.count == 4)
    }

    @Test("a 429 fails as rate-limited without retrying")
    func rateLimited() async throws {
        let server = RangeServer()
        server.failOnce[0] = 429
        let run = Run(server)
        do {
            try await run.fetch()
            Issue.record("expected rateLimited")
        } catch DownloadError.rateLimited {
        }
        #expect(run.backoffs.withLock { $0 }.isEmpty)
        #expect(server.ranges.count == 1)
    }

    @Test("a 403 on chunk 2 re-extracts once and continues from the same offset")
    func reextracts() async throws {
        let server = RangeServer()
        server.forbidden = ("/stale", Self.chunk)
        let run = Run(server, path: "/stale")
        let calls = OSAllocatedUnfairLock(initialState: 0)
        try await run.fetch(reextract: { old in
            calls.withLock { $0 += 1 }
            var fresh = old
            fresh.url = URL(string: "https://\(server.host)/fresh")!
            return fresh
        })
        #expect(calls.withLock { $0 } == 1)
        #expect(try Data(contentsOf: run.dest) == Self.body)
        let fresh = server.requests.filter { $0.path == "/fresh" }
        #expect(fresh.first?.range?.lowerBound == Self.chunk)
        #expect(run.meter.counters.reextracts == 1)
    }

    @Test("a second 403 after re-extracting fails as forbidden")
    func forbidden() async throws {
        let server = RangeServer()
        server.forbidden = ("/stale", Self.chunk)
        let run = Run(server, path: "/stale")
        let calls = OSAllocatedUnfairLock(initialState: 0)
        do {
            try await run.fetch(reextract: { old in calls.withLock { $0 += 1 }; return old })
            Issue.record("expected forbidden")
        } catch DownloadError.forbidden {
        }
        #expect(calls.withLock { $0 } == 1)
        #expect(Preflight.fileSize(run.part) == Self.chunk, "chunk 1 survives for the next attempt")
    }

    @Test("a server that ignores Range falls back to one plain request")
    func ignoresRange() async throws {
        let server = RangeServer()
        server.ignoresRange = true
        let run = Run(server)
        try await run.fetch()
        #expect(try Data(contentsOf: run.dest) == Self.body)
        #expect(server.requests.count == 2, "one refused ranged GET, then one plain")
        #expect(server.requests.last?.range == nil)
    }

    @Test("an unknown length is probed with HEAD and then chunked")
    func probed() async throws {
        let server = RangeServer()
        let run = Run(server, filesize: nil)
        try await run.fetch()
        #expect(try Data(contentsOf: run.dest) == Self.body)
        #expect(server.requests.first?.method == "HEAD")
        #expect(server.ranges.count == 3)
    }

    // MARK: - Aggregator

    @Test("two streams sum into monotonic stats at 1 Hz with an ETA within 10 %")
    func meter() throws {
        let posts = OSAllocatedUnfairLock<[DownloadStats]>(initialState: [])
        let meter = DownloadMeter { s in posts.withLock { $0.append(s) } }
        let t0 = ContinuousClock.now
        meter.expect([format(id: "v", size: 300_000_000), format(id: "a", size: 30_000_000)])
        meter.start("v", total: 300_000_000, done: 0, now: t0)
        meter.start("a", total: 30_000_000, done: 0, now: t0)
        // 10 MB/s combined, fed every 100 ms for 10 s.
        for i in 1...100 {
            let t = t0 + .milliseconds(100 * i)
            meter.update("v", done: Int64(i) * 900_000, now: t)
            meter.update("a", done: Int64(i) * 100_000, now: t)
        }
        #expect(posts.withLock { $0.count } <= 11, "throttled to 1 Hz")
        meter.finish(now: t0 + .seconds(10))
        let all = posts.withLock { $0 }
        let last = try #require(all.last)
        #expect(last.total == 330_000_000)
        #expect(last.done == 100_000_000)
        #expect(abs(last.bytesPerSec - 10e6) / 10e6 < 0.1)
        let eta = try #require(last.etaSec)
        #expect(abs(eta - 23) / 23 < 0.1, "230 MB left at 10 MB/s")
        #expect(zip(all, all.dropFirst()).allSatisfy { $0.done <= $1.done })

        // A stream restarting from zero (Range ignored) never moves done back.
        meter.start("v", total: 300_000_000, done: 0, now: t0 + .seconds(12))
        #expect(posts.withLock { $0.count } == all.count + 1)
        #expect(posts.withLock { $0.last?.done } == 100_000_000)
    }

    @Test("a progress encoded before download stats existed still decodes")
    func oldProgress() throws {
        let old = Data(#"{"shape":"censorOnly","removeMusic":false,"pct":12,"videoPct":0,"audioPct":0}"#.utf8)
        let p = try JSONDecoder().decode(JobProgress.self, from: old)
        #expect(p.download == nil && p.pct == 12)

        var q = JobProgress(shape: .censorOnly, removeMusic: false)
        q.postDownload(DownloadStats(done: 5, total: 10, bytesPerSec: 1, etaSec: 5))
        #expect(q.stage == .download && q.pct == 50)
        let back = try JSONDecoder().decode(JobProgress.self, from: JSONEncoder().encode(q))
        #expect(back == q)
    }

    @Test("filters map to the format policy's processing")
    func processing() {
        #expect(JobRunner.processing(FilterOps(removeMusic: false, censor: false)) == .none)
        #expect(JobRunner.processing(FilterOps(removeMusic: true, censor: false)) == .music)
        #expect(JobRunner.processing(FilterOps(removeMusic: false, censor: true)) == .visual)
        #expect(JobRunner.processing(FilterOps(removeMusic: true, censor: true)) == .visual)
    }

    // MARK: - Harness

    /// One stream into a scratch file through the fixture.
    struct Run {
        let server: RangeServer
        let format: MediaFormat
        let dest = Fixtures.scratch("chunked-\(UUID().uuidString).mp4")
        let meter = DownloadMeter { _ in }
        let backoffs = OSAllocatedUnfairLock<[Int]>(initialState: [])
        var part: URL { dest.appendingPathExtension("part") }

        init(_ server: RangeServer, path: String = "/v", filesize: Int64? = Int64(ChunkedDownloadTests.body.count)) {
            self.server = server
            format = MediaFormat(id: "299", url: URL(string: "https://\(server.host)\(path)")!, ext: "mp4",
                                 height: 1080, vcodec: "avc1", acodec: "none", filesize: filesize,
                                 tbr: nil, httpHeaders: [:], lastModified: "1700000000")
        }

        func fetch(reextract: @escaping @Sendable (MediaFormat) async throws -> MediaFormat = { $0 },
                   isCancelled: @escaping @Sendable () -> Bool = { false }) async throws {
            let config = URLSessionConfiguration.ephemeral
            config.protocolClasses = [RangeProtocol.self]
            let backoffs = backoffs
            let transport = Downloader.Transport(session: URLSession(configuration: config),
                                                 backoff: { n in backoffs.withLock { $0.append(n) } },
                                                 freeBytes: { .max })
            try await Downloader.fetch(format, to: dest, meter: meter, transport: transport,
                                       reextract: reextract, isCancelled: isCancelled)
        }
    }

    private func format(id: String, size: Int64) -> MediaFormat {
        MediaFormat(id: id, url: URL(string: "https://ex.com/\(id)")!, ext: "mp4", height: nil,
                    vcodec: nil, acodec: nil, filesize: size, tbr: nil, httpHeaders: [:])
    }
}

/// An in-process HTTP server with Range support: a `URLProtocol` on the
/// injected session, so no ports and no network. Each test owns a host.
final class RangeServer: @unchecked Sendable {
    struct Request { var method: String; var path: String; var range: ClosedRange<Int64>? }

    let host = "\(UUID().uuidString.lowercased()).fixture.test"
    var ignoresRange = false
    /// Range start → status to answer once instead of the bytes.
    var failOnce: [Int64: Int] = [:]
    /// Every ranged GET on this path at or past this offset answers 403.
    var forbidden: (path: String, from: Int64)?
    private var log: [Request] = []
    private let lock = NSLock()

    static let all = OSAllocatedUnfairLock<[String: RangeServer]>(initialState: [:])

    init() { Self.all.withLock { $0[host] = self } }

    var requests: [Request] { lock.withLock { log } }
    var ranges: [ClosedRange<Int64>] { requests.filter { $0.method == "GET" }.compactMap(\.range) }
    var requestedBytes: Int64 { ranges.reduce(0) { $0 + $1.upperBound - $1.lowerBound + 1 } }
    func reset() { lock.withLock { log.removeAll() } }

    func respond(to req: URLRequest) -> (Int, [String: String], Data) {
        let body = ChunkedDownloadTests.body
        let path = req.url?.path ?? ""
        let method = req.httpMethod ?? "GET"
        let range = req.value(forHTTPHeaderField: "Range").flatMap { header -> ClosedRange<Int64>? in
            let parts = header.dropFirst("bytes=".count).split(separator: "-").compactMap { Int64($0) }
            return parts.count == 2 ? parts[0]...parts[1] : nil
        }
        let status: Int? = lock.withLock {
            log.append(Request(method: method, path: path, range: range))
            if let start = range?.lowerBound, let s = failOnce.removeValue(forKey: start) { return s }
            if let f = forbidden, path == f.path, let start = range?.lowerBound, start >= f.from { return 403 }
            return nil
        }
        let full = ["Content-Length": "\(body.count)", "Accept-Ranges": "bytes"]
        if let status { return (status, [:], Data()) }
        if method == "HEAD" { return (200, full, Data()) }
        guard let range, !ignoresRange else { return (200, full, body) }
        let slice = body.subdata(in: Int(range.lowerBound)..<Int(range.upperBound) + 1)
        return (206, ["Content-Length": "\(slice.count)",
                      "Content-Range": "bytes \(range.lowerBound)-\(range.upperBound)/\(body.count)"], slice)
    }
}

final class RangeProtocol: URLProtocol, @unchecked Sendable {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url, let host = url.host,
              let server = RangeServer.all.withLock({ $0[host] }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotFindHost))
            return
        }
        let (status, headers, data) = server.respond(to: request)
        let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                       headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        // Several pieces, like a real socket, so progress is observed mid-chunk.
        let piece = 1 << 20
        var at = 0
        while at < data.count {
            client?.urlProtocol(self, didLoad: data.subdata(in: at..<min(at + piece, data.count)))
            at += piece
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
