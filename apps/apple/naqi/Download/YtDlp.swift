import Foundation
import os

/// Managed yt-dlp: the latest standalone macOS executable from GitHub, kept in Application Support
/// and refreshed weekly the same way Android's `Downloader.updateIfDue` does.
///
/// YouTube (and Instagram, TikTok, …) rotate extractors on the order of weeks.
/// A frozen copy inside the binary goes stale; fetching the current release is
/// the whole point of "yt-dlp latest".
actor YtDlp {
    static let shared = YtDlp()

    /// Standalone PyInstaller binary. `/usr/bin/python3` is an xcrun stub that
    /// cannot run inside the App Sandbox, so the zipapp is not enough on Mac.
    private static let latestMacBinary = URL(string: "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_macos")!
    private static let latestAPI = URL(string: "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest")!
    private static let updateKey = "naqi.ytdlp.lastCheck"
    private static let versionKey = "naqi.ytdlp.version"
    private static let week: TimeInterval = 7 * 24 * 60 * 60

    private var ready = false
    /// The App Sandbox quarantines every file the app writes and refuses to
    /// execute it (EPERM), so a downloaded yt-dlp may never launch. That is not
    /// staleness — updating again cannot fix it — so retries skip the update.
    private(set) var launchBlocked = false

    var installDir: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("naqi-ytdlp", isDirectory: true)
        try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
        var dir = base
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try? dir.setResourceValues(rv)
        return dir
    }

    var binary: URL { installDir.appendingPathComponent("yt-dlp") }

    func version() -> String? {
        UserDefaults.standard.string(forKey: Self.versionKey)
            ?? (try? String(contentsOf: installDir.appendingPathComponent("VERSION"), encoding: .utf8))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
    }

    func updateIfDue() async {
        let last = UserDefaults.standard.object(forKey: Self.updateKey) as? Date ?? .distantPast
        #if os(macOS)
        let missing = !FileManager.default.fileExists(atPath: binary.path)
        #else
        let missing = version() == nil
        #endif
        guard Date.now.timeIntervalSince(last) > Self.week || missing else { return }
        _ = try? await update()
    }

    @discardableResult
    func update() async throws -> String {
        #if os(macOS)
        let (data, response) = try await URLSession.shared.data(from: Self.latestMacBinary)
        let http = response as? HTTPURLResponse
        guard let http, (200..<300).contains(http.statusCode), data.count > 1_000 else {
            throw DownloadError.network("yt-dlp download HTTP \(http?.statusCode ?? 0)")
        }
        let tmp = binary.appendingPathExtension("tmp")
        try data.write(to: tmp, options: .atomic)
        try? FileManager.default.removeItem(at: binary)
        try FileManager.default.moveItem(at: tmp, to: binary)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: binary.path)
        let ver = (try? await fetchLatestTag()) ?? "latest"
        Log.download.info("yt-dlp updated to \(ver, privacy: .public) (\(data.count) bytes)")
        #else
        // iOS cannot spawn yt-dlp; keep the tag so About can show it.
        let ver = try await fetchLatestTag()
        Log.download.info("yt-dlp latest is \(ver, privacy: .public)")
        #endif
        try? ver.write(to: installDir.appendingPathComponent("VERSION"), atomically: true, encoding: .utf8)
        UserDefaults.standard.set(ver, forKey: Self.versionKey)
        UserDefaults.standard.set(Date.now, forKey: Self.updateKey)
        ready = true
        return ver
    }

    func ensure() async throws {
        if ready, FileManager.default.fileExists(atPath: binary.path) { return }
        await updateIfDue()
        if FileManager.default.fileExists(atPath: binary.path) {
            ready = true
            return
        }
        _ = try await update()
    }

    /// `yt-dlp -J --no-playlist URL` → parsed formats. macOS only: iOS cannot
    /// spawn a process, so `NativeExtract` is the extractor there.
    func extract(_ url: String) async throws -> ExtractedMedia {
        try await ensure()
        return try await Self.retryingAfterUpdate {
            try await extractOnce(url)
        } update: {
            if launchBlocked { throw DownloadError.unsupported }
            _ = try await update()
        }
    }

    /// Minimum gap between recovery updates, as on Android: a burst of
    /// failures must not hammer GitHub's 60/h anonymous API limit, and a
    /// yt-dlp fetched minutes ago is not fixed by fetching it again.
    static let recoveryUpdateInterval: TimeInterval = 6 * 60 * 60

    /// When yt-dlp was last fetched. `update()` stamps it, so the weekly
    /// check and a recovery both restart the 6 h window.
    nonisolated static func lastUpdate() -> Date {
        UserDefaults.standard.object(forKey: updateKey) as? Date ?? .distantPast
    }

    /// Whether a newer yt-dlp can fix `error`. Only a stale extractor (or a
    /// failure nobody recognised, Android's UNKNOWN) qualifies; a removed or
    /// geo-blocked video, a rate limit or a dead network fails the same way
    /// on any version.
    nonisolated static func updateHelps(_ error: any Error) -> Bool {
        switch error {
        case let d as DownloadError:
            switch d {
            case .extractor, .generic, .unsupported, .noFile: true
            case .network, .noSpace, .cancelled, .unavailable, .geo, .rateLimited, .forbidden: false
            }
        case is URLError: false
        default: true
        }
    }

    /// Runs `op`; if it fails with an error an update can fix (`updateHelps`)
    /// and yt-dlp was not fetched in the last 6 h, runs `update` and then `op`
    /// exactly once more. Anything else surfaces at once. Cancellation is not
    /// retried, and a failed update surfaces the original error — there is no
    /// second update and no loop.
    nonisolated static func retryingAfterUpdate<T>(
        _ op: () async throws -> T,
        update: () async throws -> Void,
        lastUpdate: () -> Date = { YtDlp.lastUpdate() },
        now: Date = .now,
    ) async throws -> T {
        do {
            return try await op()
        } catch DownloadError.cancelled {
            throw DownloadError.cancelled
        } catch let error as CancellationError {
            throw error
        } catch {
            guard updateHelps(error),
                  now.timeIntervalSince(lastUpdate()) > recoveryUpdateInterval else { throw error }
            Log.download.warning("yt-dlp path failed; retrying once after an update: \(error.localizedDescription, privacy: .public)")
            let original = error
            do { try await update() } catch { throw original }
            return try await op()
        }
    }

    private func extractOnce(_ url: String) async throws -> ExtractedMedia {
        let json = try await run(args: [
            "-J", "--no-playlist", "--no-warnings", "--no-check-certificates",
            "--skip-download", url,
        ])
        return try Self.parseDump(json)
    }

    func run(args: [String]) async throws -> String {
        try await ensure()
        #if os(macOS)
        return try await runProcess(args: args)
        #else
        throw DownloadError.unsupported
        #endif
    }

    #if os(macOS)
    private func runProcess(args: [String]) async throws -> String {
        let owner = ProcessOwner()
        let timeout = Task {
            do { try await Task.sleep(for: .seconds(60)) }
            catch { return }
            owner.terminate(timedOut: true)
        }
        defer { timeout.cancel() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { cont in
            let proc = Process()
            owner.set(proc)
            proc.executableURL = binary
            proc.arguments = args
            proc.currentDirectoryURL = installDir
            var env = ProcessInfo.processInfo.environment
            env["PYTHONUNBUFFERED"] = "1"
            env["XDG_CACHE_HOME"] = installDir.appendingPathComponent("cache", isDirectory: true).path
            env["XDG_CONFIG_HOME"] = installDir.appendingPathComponent("config", isDirectory: true).path
            proc.environment = env
            let out = Pipe()
            let err = Pipe()
            proc.standardOutput = out
            proc.standardError = err
            let captured = OSAllocatedUnfairLock(initialState: (stdout: Data(), stderr: Data()))
            out.fileHandleForReading.readabilityHandler = { handle in
                let data = handle.availableData
                if data.isEmpty == false { captured.withLock { $0.stdout.append(data) } }
            }
            err.fileHandleForReading.readabilityHandler = { handle in
                let data = handle.availableData
                if data.isEmpty == false {
                    captured.withLock {
                        if $0.stderr.count < 1024 * 1024 {
                            $0.stderr.append(data.prefix(1024 * 1024 - $0.stderr.count))
                        }
                    }
                }
            }
            proc.terminationHandler = { p in
                out.fileHandleForReading.readabilityHandler = nil
                err.fileHandleForReading.readabilityHandler = nil
                let tailOut = out.fileHandleForReading.readDataToEndOfFile()
                let tailErr = err.fileHandleForReading.readDataToEndOfFile()
                let data = captured.withLock { value -> (Data, Data) in
                    value.stdout.append(tailOut)
                    if value.stderr.count < 1024 * 1024 {
                        value.stderr.append(tailErr.prefix(1024 * 1024 - value.stderr.count))
                    }
                    return (value.stdout, value.stderr)
                }
                let stdout = String(data: data.0, encoding: .utf8) ?? ""
                let stderr = String(data: data.1, encoding: .utf8) ?? ""
                if p.terminationStatus == 0 {
                    cont.resume(returning: stdout)
                } else {
                    let text = (stdout + "\n" + stderr)
                    Log.download.error("yt-dlp exit \(p.terminationStatus): \(text.prefix(800), privacy: .public)")
                    cont.resume(throwing: owner.didTimeOut
                                ? DownloadError.network("yt-dlp timed out")
                                : Self.classify(text))
                }
            }
            do { try proc.run() }
            catch {
                launchBlocked = true
                Log.download.error("yt-dlp cannot launch: \(error.localizedDescription, privacy: .public)")
                cont.resume(throwing: DownloadError.generic(error.localizedDescription))
            }
            }
        } onCancel: {
            owner.terminate(timedOut: false)
        }
    }
    #endif

    /// Android's `Downloader.classify`, ported as-is: it was tested against
    /// real yt-dlp messages. Only the `ERROR:` lines are read when there are
    /// any — warnings mention "unable to download" for fragments yt-dlp then
    /// retried fine. First match wins, most specific first. One deviation:
    /// "unsupported url" stays `.unsupported` (no extractor for the page),
    /// where Android folds it into UNAVAILABLE.
    nonisolated static func classify(_ text: String) -> DownloadError {
        let errors = text.split(whereSeparator: \.isNewline).filter { $0.contains("ERROR:") }
        let t = (errors.isEmpty ? text : errors.joined(separator: "\n")).lowercased()
        func has(_ needles: String...) -> Bool { needles.contains { t.contains($0) } }
        if has("no space left", "errno 28", "enospc") { return .noSpace }
        // YouTube's anti-bot wall: a newer yt-dlp is the fix, not a login the
        // user could provide.
        if has("not a bot") { return .extractor(text) }
        if has("unsupported url") { return .unsupported }
        if has("private video", "video unavailable", "is unavailable", "has been removed", "no longer available",
               "members-only", "join this channel", "confirm your age", "age-restricted", "sign in",
               "logged-in", "log in", "login", "--cookies", "is not a valid url",
               "does not exist", "http error 404", "http error 410") {
            return .unavailable(text)
        }
        if has("in your country", "geo restrict", "geo-restrict", "in your location", "geo-blocked") {
            return .geo(text)
        }
        if has("http error 429", "too many requests", "try again later", "rate-limit", "rate limit") {
            return .rateLimited
        }
        if has("http error 403", "forbidden") { return .forbidden }
        if has("requested format is not available", "unable to extract", "nsig", "signature",
               "challenge", "no video formats", "unable to decrypt", "player response") {
            return .extractor(text)
        }
        if has("timed out", "connection reset", "connection refused", "connection aborted",
               "network is unreachable", "name resolution", "failed to resolve", "no address associated",
               "unable to download", "incompleteread", "http error 5", "remote end closed", "ssl",
               "errno 7", "errno 101", "errno 104", "errno 110", "errno 111") {
            return .network(text)
        }
        return .generic(text)
    }

    private func fetchLatestTag() async throws -> String {
        var req = URLRequest(url: Self.latestAPI)
        req.setValue("Naqi/1.0 (yt-dlp-update)", forHTTPHeaderField: "User-Agent")
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, _) = try await URLSession.shared.data(for: req)
        struct Rel: Decodable { var tag_name: String }
        return try JSONDecoder().decode(Rel.self, from: data).tag_name
    }

    nonisolated static func parseDump(_ json: String) throws -> ExtractedMedia {
        guard let data = json.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw DownloadError.generic("yt-dlp produced no JSON") }
        let title = (root["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nonEmpty ?? "download"
        let webpage = (root["webpage_url"] as? String) ?? (root["original_url"] as? String) ?? ""
        let raw = (root["formats"] as? [[String: Any]]) ?? []
        var formats: [MediaFormat] = []
        formats.reserveCapacity(raw.count)
        for f in raw {
            guard let urlStr = f["url"] as? String, let url = URL(string: urlStr) else { continue }
            let proto = (f["protocol"] as? String) ?? url.scheme ?? ""
            // Skip HLS/DASH manifests — URLSession is a single-file downloader.
            if proto.contains("m3u8") || proto.contains("dash") || proto.contains("rtmp") { continue }
            let ext = (f["ext"] as? String) ?? url.pathExtension.nonEmpty ?? "mp4"
            let headers = (f["http_headers"] as? [String: String]) ?? [:]
            let height = f["height"] as? Int
            let vcodec = f["vcodec"] as? String
            let acodec = f["acodec"] as? String
            let size = (f["filesize"] as? Int64) ?? (f["filesize_approx"] as? Int64)
            let tbr = (f["tbr"] as? Double) ?? (f["vbr"] as? Double) ?? (f["abr"] as? Double)
            let id = (f["format_id"] as? String) ?? UUID().uuidString
            formats.append(MediaFormat(id: id, url: url, ext: ext, height: height,
                                       vcodec: vcodec, acodec: acodec, filesize: size,
                                       tbr: tbr, httpHeaders: headers))
        }
        if formats.isEmpty { throw DownloadError.unsupported }
        return ExtractedMedia(title: String(title.prefix(80)), webpageURL: webpage, formats: formats)
    }
}

#if os(macOS)
private final class ProcessOwner: @unchecked Sendable {
    private struct State { var process: Process?; var timedOut = false }
    private let state = OSAllocatedUnfairLock(initialState: State())

    var didTimeOut: Bool { state.withLock { $0.timedOut } }
    func set(_ process: Process) { state.withLock { $0.process = process } }
    func terminate(timedOut: Bool) {
        let process = state.withLock { value -> Process? in
            if timedOut { value.timedOut = true }
            return value.process
        }
        if process?.isRunning == true { process?.terminate() }
    }
}
#endif

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
