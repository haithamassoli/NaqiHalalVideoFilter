import CryptoKit
import Foundation
import os

/// Fetch one URL into quarantine and return the finished file.
///
/// **Quarantine, not the gallery.** Downloads land in
/// `Application Support/naqi-downloads/<urlKey>/` and are published only after
/// filtering — the PRD's core promise is that no unfiltered file is ever
/// visible to Photos. Excluded from backup the same way `WorkDir` is.
///
/// Extraction prefers the latest standalone yt-dlp executable on macOS. iOS,
/// and a sandboxed Mac that cannot launch it, fall back to `NativeExtract`.
enum Downloader {

    private static let rootName = "naqi-downloads"
    private static let inFlight: Set<String> = ["part", "ytdl", "temp"]
    private static let staleMs: TimeInterval = 7 * 24 * 60 * 60
    private static let recordName = "download.json"

    private struct Record: Codable {
        var quality: String
        var formatIDs: [String]
        var fileName: String
        var bytes: Int64
        var sha256: String
    }

    static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(rootName, isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var dir = base
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? dir.setResourceValues(rv)
        return dir
    }

    static func quarantineDir(for url: String) -> URL {
        let d = root.appendingPathComponent(key(of: url), isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    static func isQuarantined(_ url: URL) -> Bool {
        url.isFileURL && url.path.hasPrefix(root.path + "/")
    }

    static func discard(_ url: URL) {
        guard isQuarantined(url) else { return }
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
        Log.download.info("quarantine cleared for \(url.lastPathComponent, privacy: .public)")
    }

    static func discard(remoteURL: String) {
        try? FileManager.default.removeItem(at: root.appendingPathComponent(key(of: remoteURL), isDirectory: true))
    }

    static func sweep() {
        let cutoff = Date.now.addingTimeInterval(-staleMs)
        for d in (try? FileManager.default.contentsOfDirectory(
            at: root, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [] {
            let newest = ((try? FileManager.default.contentsOfDirectory(
                at: d, includingPropertiesForKeys: [.contentModificationDateKey])) ?? [d])
                .compactMap { try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate }
                .max() ?? cutoff
            guard newest < cutoff else { continue }
            try? FileManager.default.removeItem(at: d)
            Log.download.info("swept stale download \(d.lastPathComponent, privacy: .public)")
        }
    }

    /// Fetch `url` at `quality` into its quarantine directory.
    ///
    /// - Parameter processing: what the job will do to the file; it steers the
    ///   format policy (a visual filter does not want 4K60).
    /// - Parameter onProgress: summed over every stream, at most 1 Hz plus a
    ///   final post. Called off the main actor.
    static func download(
        url: String,
        quality: DownloadQuality,
        processing: Processing = .none,
        onProgress: @escaping @Sendable (DownloadStats) -> Void = { _ in },
        isCancelled: @escaping @Sendable () -> Bool = { false },
    ) async throws -> URL {
        let started = ContinuousClock.now
        sweep()
        if let cached = reusable(url: url, quality: quality) {
            let bytes = Preflight.fileSize(cached) ?? 0
            onProgress(DownloadStats(done: bytes, total: bytes, bytesPerSec: 0, etaSec: 0))
            Log.download.info("reusing completed download \(cached.lastPathComponent, privacy: .public)")
            return cached
        }
        let meter = DownloadMeter(post: onProgress)
        var summary = Summary(host: URL(string: url)?.host ?? "-", started: started)
        do {
            #if os(macOS)
            var info = try await summary.extract(url)
            // Extraction already refreshes yt-dlp when it fails. This covers the
            // other stale-yt-dlp symptom: format URLs that then refuse to download.
            // One update, one re-extract, one more fetch.
            let output = try await YtDlp.retryingAfterUpdate {
                try await fetchAll(info, url: url, quality: quality, processing: processing,
                                   meter: meter, summary: &summary, isCancelled: isCancelled)
            } update: {
                if await YtDlp.shared.launchBlocked { throw DownloadError.unsupported }
                _ = try await update()
                info = try await summary.extract(url)
            }
            #else
            let info = try await summary.extract(url)
            let output = try await fetchAll(info, url: url, quality: quality, processing: processing,
                                            meter: meter, summary: &summary, isCancelled: isCancelled)
            #endif
            summary.log(meter, outcome: "ok")
            return output
        } catch {
            summary.log(meter, outcome: outcome(of: error))
            // Every client came back empty: YouTube moved. Pull the client
            // config now instead of waiting out the week.
            if case DownloadError.extractor = error {
                Task { await NativeExtract.refreshClientConfigIfDue(force: true) }
            }
            throw error
        }
    }

    private static func fetchAll(
        _ info: ExtractedMedia,
        url: String,
        quality: DownloadQuality,
        processing: Processing,
        meter: DownloadMeter,
        summary: inout Summary,
        isCancelled: @escaping @Sendable () -> Bool,
    ) async throws -> URL {
        let chosen = quality.select(info.formats, processing: processing)
        summary.client = info.client
        summary.chosen = chosen
        guard !chosen.isEmpty else { throw DownloadError.unsupported }
        let dir = quarantineDir(for: url)
        let safeTitle = sanitize(info.title)
        meter.expect(chosen)
        // A 403 means the URL expired or the IP rotated: extract again and take
        // the same format id, so the resumed stream keeps its identity.
        let reextract: @Sendable (MediaFormat) async throws -> MediaFormat = { old in
            // Same bytes or nothing: appending a re-encoded file to the `.part`
            // would stitch two different streams together.
            guard let fresh = try await extract(url, fresh: true).formats.first(where: { $0.id == old.id }),
                  fresh.filesize == old.filesize, fresh.lastModified == old.lastModified
            else { throw DownloadError.forbidden }
            return fresh
        }

        if isCancelled() { throw DownloadError.cancelled }

        if chosen.count == 1 {
            let dest = dir.appendingPathComponent("\(safeTitle).\(chosen[0].ext)")
            try await fetch(chosen[0], to: dest, meter: meter, reextract: reextract, isCancelled: isCancelled)
            try writeRecord(output: dest, quality: quality, formats: chosen, dir: dir,
                            isCancelled: isCancelled)
            meter.finish()
            return dest
        }

        let video = chosen.first(where: \.hasVideo) ?? chosen[0]
        let audio = chosen.first(where: { $0.hasAudio && $0.id != video.id }) ?? chosen[1]
        let vURL = dir.appendingPathComponent("\(safeTitle).f\(video.id).\(video.ext)")
        let aURL = dir.appendingPathComponent("\(safeTitle).f\(audio.id).\(audio.ext)")
        async let fetchedVideo: Void = fetch(video, to: vURL, meter: meter, reextract: reextract,
                                             isCancelled: isCancelled)
        async let fetchedAudio: Void = fetch(audio, to: aURL, meter: meter, reextract: reextract,
                                             isCancelled: isCancelled)
        _ = try await (fetchedVideo, fetchedAudio)
        let merged = dir.appendingPathComponent("\(safeTitle).mp4")
        do {
            try await MediaMux.merge(video: vURL, audio: aURL, into: merged)
            try? FileManager.default.removeItem(at: vURL)
            try? FileManager.default.removeItem(at: aURL)
            try writeRecord(output: merged, quality: quality, formats: chosen, dir: dir,
                            isCancelled: isCancelled)
            meter.finish()
            return merged
        } catch {
            try? FileManager.default.removeItem(at: merged)
            Log.download.error("mux failed: \(error.localizedDescription, privacy: .public)")
            throw DownloadError.generic("mux: \(error.localizedDescription)")
        }
    }

    /// The Phase 0 line: one per download, success or not, so a field log
    /// answers "which client, how fast, how often did it retry" per run.
    private struct Summary {
        let host: String
        let started: ContinuousClock.Instant
        var client: String?
        var extractMs: Double = 0
        var chosen: [MediaFormat] = []

        mutating func extract(_ url: String) async throws -> ExtractedMedia {
            let t = ContinuousClock.now
            defer { extractMs += msSince(t) }
            return try await Downloader.extract(url)
        }

        func log(_ meter: DownloadMeter, outcome: String) {
            let c = meter.counters
            let totalMs = msSince(started)
            let firstByteMs = c.firstByte.map { started.duration(to: $0).milliseconds }
            let transferSec = max(0.001, (totalMs - extractMs) / 1000)
            let video = chosen.first(where: \.hasVideo)
            let line = """
                download host=\(host) client=\(client ?? "-") extract_ms=\(Int(extractMs)) \
                first_byte_ms=\(firstByteMs.map { String(Int($0)) } ?? "-") total_ms=\(Int(totalMs)) \
                bytes=\(c.transferred) MBps=\(String(format: "%.1f", Double(c.transferred) / 1e6 / transferSec)) \
                itag=\(chosen.isEmpty ? "-" : chosen.map(\.id).joined(separator: "+")) \
                vcodec=\(video?.vcodec ?? "-") height=\(video?.height.map(String.init) ?? "-") \
                fps=\(video?.fps.map(String.init) ?? "-") chunks=\(c.chunks) chunk_retries=\(c.retries) \
                reextracts=\(c.reextracts) outcome=\(outcome)
                """
            Log.download.notice("\(line, privacy: .public)")
        }
    }

    static func outcome(of error: any Error) -> String {
        if error is CancellationError { return "cancelled" }
        guard let d = error as? DownloadError else { return "generic" }
        return switch d {
        case .unsupported: "unsupported"
        case .network: "network"
        case .noFile: "no_file"
        case .noSpace: "no_space"
        case .cancelled: "cancelled"
        case .generic: "generic"
        case .unavailable: "unavailable"
        case .geo: "geo"
        case .rateLimited: "rate_limited"
        case .forbidden: "forbidden"
        case .extractor: "extractor"
        }
    }

    /// `fresh` skips the share sheet's cached extraction, whose URLs a 403 has
    /// just shown to be stale.
    static func extract(_ url: String, fresh: Bool = false) async throws -> ExtractedMedia {
        #if os(macOS)
        do { return try await YtDlp.shared.extract(url) }
        catch {
            Log.download.warning("yt-dlp extract failed, trying native: \(error.localizedDescription, privacy: .public)")
        }
        #endif
        return try await NativeExtract.extract(url, fresh: fresh)
    }

    static func version() async -> String? { await YtDlp.shared.version() }

    static func update() async throws -> String { try await YtDlp.shared.update() }

    static func updateIfDue() async {
        await NativeExtract.refreshClientConfigIfDue()
        await YtDlp.shared.updateIfDue()
    }

    /// Digest of the validated private artifact used to bind processing
    /// checkpoints to downloaded bytes rather than only to the page URL.
    static func sourceIdentity(for file: URL) -> String? {
        guard let record = readRecord(file.deletingLastPathComponent()),
              record.fileName == file.lastPathComponent else { return nil }
        return record.sha256
    }

    // MARK: Transfer

    /// googlevideo throttles any response over ~12 MB to ~0.8 MB/s; 10 MiB ranges run at line speed
    /// (measured 37 MB/s vs 0.8). Same size yt-dlp uses.
    static let chunkSize: Int64 = 10 << 20
    /// yt-dlp's `--retry-sleep http:exp=1:30` with 10 tries, per chunk.
    static let maxTries = 10
    /// Free space a chunk must leave behind. A download is never allowed to
    /// be what fills the phone; the job's own preflight runs after it.
    static let spaceFloor: Int64 = 100 << 20

    /// One session for every stream and chunk. `waitsForConnectivity` turns a
    /// Wi-Fi drop into a wait instead of an immediate failure; the 30 s request
    /// timeout still catches a stalled server. HTTP/2 stays on.
    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = true
        config.timeoutIntervalForRequest = 30
        return URLSession(configuration: config)
    }()

    /// What the transfer touches outside itself, so tests can swap each piece.
    struct Transport: Sendable {
        var session: URLSession
        /// Sleep before retry number `n` (1-based).
        var backoff: @Sendable (Int) async throws -> Void
        var freeBytes: @Sendable () -> Int64

        static let live = Transport(
            session: Downloader.session,
            backoff: { try await Task.sleep(for: .seconds(min(30, 1 << ($0 - 1)))) },
            freeBytes: { Preflight.availableBytes() })
    }

    /// What a `.part` was fetched against. `download` re-extracts on every
    /// run, so each attempt already carries a fresh URL and the plan's "URL
    /// older than 5 h" check has nothing to guard: `url` is kept for the logs,
    /// and identity is length + `lastModified` + format id.
    private struct PartInfo: Codable {
        var url: String
        var clen: Int64
        var lastModified: String?
        var itag: String

        func resumes(_ other: PartInfo) -> Bool {
            clen == other.clen && lastModified == other.lastModified && itag == other.itag
        }
    }

    /// One stream into `dest`. A known length goes through sequential ranged
    /// chunks; an unknown one is a single plain request.
    static func fetch(_ format: MediaFormat, to dest: URL, meter: DownloadMeter,
                      transport: Transport = .live,
                      reextract: @escaping @Sendable (MediaFormat) async throws -> MediaFormat,
                      isCancelled: @escaping @Sendable () -> Bool) async throws {
        if format.url.isFileURL {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: format.url, to: dest)
            let bytes = Preflight.fileSize(dest) ?? 0
            meter.start(format.id, total: bytes, done: bytes)
            return
        }
        var length = format.filesize
        if length == nil { length = await probeLength(format, session: transport.session) }
        if let length, Preflight.fileSize(dest) == length {
            meter.start(format.id, total: length, done: length)
            return
        }
        do {
            if let length {
                try await fetchChunked(format, length: length, to: dest, meter: meter,
                                       transport: transport, reextract: reextract, isCancelled: isCancelled)
            } else {
                try await fetchWhole(format, to: dest, meter: meter, transport: transport,
                                     isCancelled: isCancelled)
            }
        } catch {
            if isCancelled() || Task.isCancelled { throw DownloadError.cancelled }
            throw error
        }
        // Not `Preflight.fileSize(dest)`: `dest` cached its resource values
        // before the transfer.
        let written = (try? FileManager.default.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value ?? 0
        Log.download.info("fetched \(dest.lastPathComponent, privacy: .public) (\(written) bytes)")
    }

    /// Resume is the `.part`'s length: it only ever grows by appending bytes
    /// that arrived in order, so it is always a gapless prefix. A chunk that
    /// still fails after `maxTries` fails the stream and keeps the `.part`
    /// (yt-dlp's `--abort-on-unavailable-fragments`): never stitch a gap.
    ///
    /// ponytail: chunks run sequentially; 4 in parallel measured +10 % on
    /// googlevideo. Add a small pool here if a host ever shows more.
    private static func fetchChunked(_ format: MediaFormat, length: Int64, to dest: URL,
                                     meter: DownloadMeter, transport: Transport,
                                     reextract: @Sendable (MediaFormat) async throws -> MediaFormat,
                                     isCancelled: @escaping @Sendable () -> Bool) async throws {
        let fm = FileManager.default
        let part = dest.appendingPathExtension("part")
        let sidecar = part.appendingPathExtension("json")
        let info = PartInfo(url: format.url.absoluteString, clen: length,
                            lastModified: format.lastModified, itag: format.id)
        var offset: Int64 = 0
        if let data = try? Data(contentsOf: sidecar),
           let saved = try? JSONDecoder().decode(PartInfo.self, from: data), saved.resumes(info),
           let size = Preflight.fileSize(part), size <= length {
            offset = size
            Log.download.info("resuming \(part.lastPathComponent, privacy: .public) at \(offset)")
        } else {
            try? fm.removeItem(at: part)
            try JSONEncoder().encode(info).write(to: sidecar, options: .atomic)
        }
        if !fm.fileExists(atPath: part.path) { fm.createFile(atPath: part.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: part)
        // Closed on exit only: renaming or unlinking an open file is fine, and
        // one close avoids a double close on the fallback path.
        defer { try? handle.close() }
        try handle.truncate(atOffset: UInt64(offset))
        meter.start(format.id, total: length, done: offset)

        let id = format.id
        var format = format
        var reextracted = false
        var tries = 0
        while offset < length {
            if isCancelled() || Task.isCancelled { throw DownloadError.cancelled }
            let end = min(offset + chunkSize, length) - 1
            guard transport.freeBytes() - (end - offset + 1) > spaceFloor else { throw DownloadError.noSpace }
            let from = offset
            do {
                try await get(request(format, range: from...end), session: transport.session, into: handle,
                              accept: { $0 == 206 }, isCancelled: isCancelled,
                              onBytes: { meter.update(id, done: from + $0) })
                offset = Int64(try handle.offset())
                // A 206 that ended early is a network error with a clean prefix.
                guard offset > from else { throw URLError(.networkConnectionLost) }
                tries = 0
                meter.count { $0.chunks += 1 }
            } catch {
                offset = (try? handle.offset()).map(Int64.init) ?? from
                if isCancelled() || Task.isCancelled { throw DownloadError.cancelled }
                switch error {
                case HTTPStatus.code(200):
                    // The server ignored Range and started the whole body.
                    Log.download.notice("range ignored by \(format.url.host ?? "-", privacy: .public); single request")
                    try? fm.removeItem(at: part)
                    try? fm.removeItem(at: sidecar)
                    meter.start(format.id, total: length, done: 0)
                    try await fetchWhole(format, to: dest, meter: meter, transport: transport,
                                         isCancelled: isCancelled)
                    return
                case HTTPStatus.code(403):
                    guard !reextracted else { throw DownloadError.forbidden }
                    reextracted = true
                    meter.count { $0.reextracts += 1 }
                    format = try await reextract(format)
                    continue
                case HTTPStatus.code(429):
                    throw DownloadError.rateLimited
                case HTTPStatus.code(let code) where (500..<600).contains(code):
                    break
                case let e as URLError where retryable.contains(e.code):
                    break
                case HTTPStatus.code(let code):
                    throw DownloadError.generic("HTTP \(code)")
                default:
                    throw DownloadError.network(error.localizedDescription)
                }
                tries += 1
                guard tries < maxTries else {
                    throw DownloadError.network("chunk at \(from) failed \(maxTries) times: \(String(describing: error))")
                }
                meter.count { $0.retries += 1 }
                Log.download.notice("chunk at \(from) retry \(tries): \(String(describing: error), privacy: .public)")
                do { try await transport.backoff(tries) } catch { throw DownloadError.cancelled }
            }
        }
        try? fm.removeItem(at: dest)
        try fm.moveItem(at: part, to: dest)
        try? fm.removeItem(at: sidecar)
    }

    /// Unknown length, or a server that ignores Range: one plain request, from
    /// zero, no retries (there is nothing to resume from).
    private static func fetchWhole(_ format: MediaFormat, to dest: URL, meter: DownloadMeter,
                                   transport: Transport,
                                   isCancelled: @escaping @Sendable () -> Bool) async throws {
        let fm = FileManager.default
        let part = dest.appendingPathExtension("part")
        try? fm.removeItem(at: part)
        fm.createFile(atPath: part.path, contents: nil)
        let handle = try FileHandle(forWritingTo: part)
        defer { try? handle.close() }
        do {
            try await get(request(format, range: nil), session: transport.session, into: handle,
                          accept: { (200..<300).contains($0) }, isCancelled: isCancelled,
                          onBytes: { meter.update(format.id, done: $0) })
        } catch HTTPStatus.code(429) {
            throw DownloadError.rateLimited
        } catch HTTPStatus.code(403) {
            throw DownloadError.forbidden
        } catch HTTPStatus.code(let code) {
            throw DownloadError.network("HTTP \(code)")
        } catch let e as URLError {
            throw DownloadError.network(e.localizedDescription)
        }
        meter.count { $0.chunks += 1 }
        try? fm.removeItem(at: dest)
        try fm.moveItem(at: part, to: dest)
    }

    /// Transient network failures worth a backoff; anything else is final.
    private static let retryable: Set<URLError.Code> = [
        .timedOut, .networkConnectionLost, .notConnectedToInternet, .cannotFindHost,
        .cannotConnectToHost, .dnsLookupFailed, .dataNotAllowed, .internationalRoamingOff,
        .callIsActive, .secureConnectionFailed,
    ]

    private static func request(_ format: MediaFormat, range: ClosedRange<Int64>?) -> URLRequest {
        var req = URLRequest(url: format.url)
        req.setValue("Mozilla/5.0", forHTTPHeaderField: "User-Agent")
        for (k, v) in format.httpHeaders { req.setValue(v, forHTTPHeaderField: k) }
        if let range { req.setValue("bytes=\(range.lowerBound)-\(range.upperBound)", forHTTPHeaderField: "Range") }
        return req
    }

    /// `Content-Length` of a server that also promises byte ranges; nil means
    /// "one plain request".
    private static func probeLength(_ format: MediaFormat, session: URLSession) async -> Int64? {
        var req = request(format, range: nil)
        req.httpMethod = "HEAD"
        guard let (_, response) = try? await session.data(for: req),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              http.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased() == "bytes",
              let length = http.value(forHTTPHeaderField: "Content-Length").flatMap(Int64.init),
              length > 0 else { return nil }
        return length
    }

    /// One GET streamed straight into `handle`. Cancels the request as soon as
    /// `isCancelled` flips, even while it is waiting for connectivity: the
    /// background grace expiring must not wait out a 30 s timeout.
    ///
    /// - Parameter onBytes: bytes written by this request so far.
    private static func get(_ req: URLRequest, session: URLSession, into handle: FileHandle,
                            accept: @escaping @Sendable (Int) -> Bool,
                            isCancelled: @escaping @Sendable () -> Bool,
                            onBytes: @escaping @Sendable (Int64) -> Void) async throws {
        let receiver = StreamReceiver(handle: handle, accept: accept, onBytes: onBytes)
        let task = session.dataTask(with: req)
        task.delegate = receiver
        let watch = Task {
            while !Task.isCancelled {
                if isCancelled() { task.cancel(); return }
                try? await Task.sleep(for: .milliseconds(200))
            }
        }
        defer { watch.cancel() }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { cont in
                receiver.continuation = cont
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    /// Per-URL directory name, same idea as Android `JobStore.keyOf`. Stable
    /// across launches so a retry finds its own `.part` file.
    static func key(of url: String) -> String {
        SHA256.hash(data: Data(url.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    static func sanitize(_ title: String) -> String {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let banned = CharacterSet(charactersIn: "/\\:?%*|\"<>")
        let cleaned = trimmed.unicodeScalars.map { banned.contains($0) ? "_" : Character($0) }
        let s = String(cleaned)
        return s.isEmpty ? "download" : String(s.prefix(80))
    }

    private static func reusable(url: String, quality: DownloadQuality) -> URL? {
        let dir = quarantineDir(for: url)
        guard let record = readRecord(dir) else { return nil }
        guard record.quality == quality.rawValue else {
            try? FileManager.default.removeItem(at: dir)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            return nil
        }
        let file = dir.appendingPathComponent(record.fileName)
        let bytes = (try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init)
        guard bytes == record.bytes, record.bytes > 0 else { return nil }
        return file
    }

    private static func readRecord(_ dir: URL) -> Record? {
        let url = dir.appendingPathComponent(recordName)
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(Record.self, from: data)
    }

    private static func writeRecord(output: URL, quality: DownloadQuality,
                                    formats: [MediaFormat], dir: URL,
                                    isCancelled: @escaping @Sendable () -> Bool) throws {
        let bytes = (try output.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        guard bytes > 0 else { throw DownloadError.noFile }
        let record = Record(quality: quality.rawValue, formatIDs: formats.map(\.id),
                            fileName: output.lastPathComponent, bytes: bytes,
                            sha256: try digest(output, isCancelled: isCancelled))
        try JSONEncoder().encode(record).write(to: dir.appendingPathComponent(recordName), options: .atomic)
    }

    private static func digest(_ url: URL,
                               isCancelled: @escaping @Sendable () -> Bool) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hash = SHA256()
        while let data = try handle.read(upToCount: 1024 * 1024), data.isEmpty == false {
            if isCancelled() || Task.isCancelled { throw DownloadError.cancelled }
            hash.update(data: data)
        }
        return hash.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Sums every stream of one download into `DownloadStats`, and keeps the
/// counters the summary log line reports.
///
/// Speed is an EMA with a ~3 s time constant, sampled at most every 0.5 s so a
/// burst of small delegate callbacks cannot spike it. Posts are throttled to
/// 1 Hz and `finish` always posts. `done` never goes backwards, even when a
/// stream restarts from zero after a server ignored Range.
final class DownloadMeter: @unchecked Sendable {
    struct Counters: Sendable {
        var chunks = 0
        var retries = 0
        var reextracts = 0
        /// This run's bytes only; a resumed `.part` is not throughput.
        var transferred: Int64 = 0
        var firstByte: ContinuousClock.Instant?
    }
    private struct Entry { var done: Int64 = 0; var total: Int64? }
    private struct State {
        var entries: [String: Entry] = [:]
        var sampleAt: ContinuousClock.Instant?
        var sampleDone: Int64 = 0
        var speed: Double?
        var lastPost: ContinuousClock.Instant?
        var posted: Int64 = 0
        var counters = Counters()
    }
    private static let tau = 3.0
    private let post: @Sendable (DownloadStats) -> Void
    private let state = OSAllocatedUnfairLock(initialState: State())

    init(post: @escaping @Sendable (DownloadStats) -> Void) { self.post = post }

    var counters: Counters { state.withLock { $0.counters } }

    func count(_ body: @Sendable (inout Counters) -> Void) { state.withLock { body(&$0.counters) } }

    /// Every stream's advertised length up front, so the total is known
    /// before the first byte (InnerTube gives `clen` for every format).
    func expect(_ formats: [MediaFormat]) {
        let entries = Dictionary(formats.map { ($0.id, Entry(total: $0.filesize)) },
                                 uniquingKeysWith: { a, _ in a })
        state.withLock { $0.entries = entries }
    }

    /// A stream begins, or restarts, at `done` bytes. Bytes already on disk
    /// count as progress, never as speed.
    func start(_ id: String, total: Int64?, done: Int64, now: ContinuousClock.Instant = .now) {
        let stats = state.withLock { s -> DownloadStats? in
            var e = s.entries[id] ?? Entry()
            s.sampleDone += done - e.done
            e.done = done
            if let total { e.total = total }
            s.entries[id] = e
            if s.sampleAt == nil { s.sampleAt = now }
            return Self.due(&s, now: now)
        }
        if let stats { post(stats) }
    }

    func update(_ id: String, done: Int64, now: ContinuousClock.Instant = .now) {
        let stats = state.withLock { s -> DownloadStats? in
            var e = s.entries[id] ?? Entry()
            let delta = done - e.done
            guard delta > 0 else { return nil }
            e.done = done
            s.entries[id] = e
            s.counters.transferred += delta
            if s.counters.firstByte == nil { s.counters.firstByte = now }
            let sum = Self.sum(s)
            if let at = s.sampleAt {
                let dt = at.duration(to: now).milliseconds / 1000
                if dt >= 0.5 {
                    let rate = Double(sum - s.sampleDone) / dt
                    s.speed = s.speed.map { $0 + (1 - exp(-dt / Self.tau)) * (rate - $0) } ?? rate
                    s.sampleAt = now
                    s.sampleDone = sum
                }
            } else {
                s.sampleAt = now
            }
            return Self.due(&s, now: now)
        }
        if let stats { post(stats) }
    }

    /// The last word, whatever the throttle says.
    func finish(now: ContinuousClock.Instant = .now) {
        post(state.withLock { s in
            s.lastPost = now
            return Self.stats(&s)
        })
    }

    private static func sum(_ s: State) -> Int64 { s.entries.values.reduce(0) { $0 + $1.done } }

    private static func due(_ s: inout State, now: ContinuousClock.Instant) -> DownloadStats? {
        if let last = s.lastPost, last.duration(to: now) < .seconds(1) { return nil }
        s.lastPost = now
        return stats(&s)
    }

    private static func stats(_ s: inout State) -> DownloadStats {
        s.posted = max(s.posted, sum(s))
        let totals = s.entries.values.map(\.total)
        let total: Int64? = totals.isEmpty || totals.contains(nil) ? nil : totals.reduce(0) { $0 + ($1 ?? 0) }
        let speed = s.speed ?? 0
        let eta = total.flatMap { t in speed > 0 ? Double(max(0, t - s.posted)) / speed : nil }
        return DownloadStats(done: s.posted, total: total, bytesPerSec: speed, etaSec: eta)
    }
}

/// A response status the transfer did not accept, kept as a number so the
/// chunk loop can choose a recovery per code.
private enum HTTPStatus: Error { case code(Int) }

/// Task-level delegate for one GET. Bytes are written as they arrive, so
/// memory stays flat and progress is as fine as the network delivers it;
/// `bytes(for:)` hands them over one at a time, too slow at 37 MB/s.
///
/// Callbacks are serial on the session's delegate queue, and `continuation` is
/// set before `resume()`, so the mutable state needs no lock.
private final class StreamReceiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    var continuation: CheckedContinuation<Void, any Error>?
    private let handle: FileHandle
    private let accept: @Sendable (Int) -> Bool
    private let onBytes: @Sendable (Int64) -> Void
    private var status = 0
    private var written: Int64 = 0
    private var failure: (any Error)?

    init(handle: FileHandle, accept: @escaping @Sendable (Int) -> Bool,
         onBytes: @escaping @Sendable (Int64) -> Void) {
        self.handle = handle
        self.accept = accept
        self.onBytes = onBytes
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
        status = (response as? HTTPURLResponse)?.statusCode ?? 0
        completionHandler(accept(status) ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard failure == nil else { return }
        do {
            try handle.write(contentsOf: data)
        } catch {
            failure = error
            dataTask.cancel()
            return
        }
        written += Int64(data.count)
        onBytes(written)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
        guard let cont = continuation else { return }
        continuation = nil
        if let failure { cont.resume(throwing: failure) }
        else if status != 0, !accept(status) { cont.resume(throwing: HTTPStatus.code(status)) }
        else if let error { cont.resume(throwing: error) }
        else { cont.resume() }
    }
}

