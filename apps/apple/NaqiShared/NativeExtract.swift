import CryptoKit
import Foundation

/// Extractor that does not spawn yt-dlp. Used on iOS (no `Process`) and as the
/// fallback when the managed macOS executable is unavailable. Covers:
///
/// - a URL that is already a media file
/// - YouTube / youtu.be via InnerTube (VISIONOS with visitorData, which returns
///   plain URLs for every format; ANDROID_VR and ANDROID as degraded fallbacks)
/// - any page that advertises a file in `og:video`, JSON-LD, or `<video src>`
enum NativeExtract {

    private static let userAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    static let maxPageBytes = 2 * 1024 * 1024

    /// A YouTube answer younger than an hour comes from `cacheDir`: that is
    /// how the share sheet's prefetch reaches the app. `fresh` (after a 403,
    /// whose URLs are stale) drops it and asks again.
    static func extract(_ url: String, fresh: Bool = false,
                        cacheDir: URL? = Cache.dir) async throws -> ExtractedMedia {
        guard let page = URL(string: url), page.scheme == "http" || page.scheme == "https"
        else { throw DownloadError.unsupported }
        if let id = youtubeID(url) {
            if let cacheDir, let hit = Cache.lookup(url, id: id, fresh: fresh, dir: cacheDir) {
                return hit
            }
            return try await youtube(id: id, webpage: url, cacheDir: cacheDir)
        }
        return try await pageExtract(page)
    }

    // MARK: Direct / HTML

    private static func pageExtract(_ page: URL) async throws -> ExtractedMedia {
        let ext = page.pathExtension.lowercased()
        if let direct = directMedia(page, ext: ext, mime: nil, size: nil) { return direct }

        var head = URLRequest(url: page)
        head.httpMethod = "HEAD"
        configure(&head)
        if let (_, response) = try? await URLSession.shared.data(for: head),
           let http = response as? HTTPURLResponse,
           (200..<300).contains(http.statusCode) {
            let mime = http.value(forHTTPHeaderField: "Content-Type") ?? ""
            let length = http.value(forHTTPHeaderField: "Content-Length").flatMap(Int64.init)
            if let direct = directMedia(http.url ?? page, ext: ext, mime: mime, size: length) {
                return direct
            }
            if let length, length > maxPageBytes { throw DownloadError.unsupported }
        }

        var req = URLRequest(url: page)
        configure(&req)
        // Stream so a server that ignores HEAD/Range cannot make an unknown
        // page allocate an entire media body before its headers are inspected.
        let (bytes, response) = try await URLSession.shared.bytes(for: req)
        guard let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else {
            throw DownloadError.network("HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)")
        }
        let mime = http.value(forHTTPHeaderField: "Content-Type") ?? ""
        let length = http.value(forHTTPHeaderField: "Content-Length").flatMap(Int64.init)
        if let direct = directMedia(http.url ?? page, ext: ext, mime: mime, size: length) {
            return direct
        }
        if let length, length > maxPageBytes { throw DownloadError.unsupported }
        var data = Data()
        data.reserveCapacity(min(maxPageBytes, Int(length ?? 0)))
        for try await byte in bytes {
            guard data.count < maxPageBytes else { throw DownloadError.unsupported }
            data.append(byte)
        }
        let html = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        let title = meta(html, property: "og:title")
            ?? tag(html, "title")
            ?? page.host
            ?? "download"
        var found: [MediaFormat] = []
        func add(_ raw: String, id: String, height: Int?) {
            let cleaned = raw.replacingOccurrences(of: "\\u0026", with: "&")
                .replacingOccurrences(of: "\\/", with: "/")
                .replacingOccurrences(of: "&amp;", with: "&")
            guard let u = URL(string: cleaned), u.scheme == "http" || u.scheme == "https" else { return }
            let ext = u.pathExtension.nonEmpty ?? "mp4"
            let audio = ["m4a", "mp3", "aac"].contains(ext)
            found.append(MediaFormat(id: id, url: u, ext: ext, height: audio ? nil : (height ?? 720),
                                     vcodec: audio ? "none" : "avc1",
                                     acodec: "mp4a.40.2", filesize: nil, tbr: nil, httpHeaders: [:]))
        }
        if let v = meta(html, property: "og:video") ?? meta(html, property: "og:video:url")
            ?? meta(html, property: "og:video:secure_url") {
            add(v, id: "og", height: Int(meta(html, property: "og:video:height") ?? ""))
        }
        if let v = meta(html, name: "twitter:player:stream") { add(v, id: "twitter", height: nil) }
        for m in jsonLDVideos(html) { add(m, id: "ld", height: nil) }
        for m in quotedURLs(html) where looksLikeMedia(m) { add(m, id: "quoted", height: nil) }
        if found.isEmpty { throw DownloadError.unsupported }
        return ExtractedMedia(title: String(title.prefix(80)), webpageURL: page.absoluteString, formats: found)
    }

    private static func configure(_ request: inout URLRequest) {
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("text/html,application/xhtml+xml,application/json,video/*,audio/*;q=0.9,*/*;q=0.8",
                         forHTTPHeaderField: "Accept")
    }

    private static let mediaExts: Set<String> = ["mp4", "m4a", "mp3", "mov", "webm", "aac", "wav"]

    private static func directMedia(_ page: URL, ext: String, mime: String?, size: Int64?) -> ExtractedMedia? {
        let audioMime = mime?.hasPrefix("audio/") == true
        let videoMime = mime?.hasPrefix("video/") == true
        guard videoMime || audioMime || mediaExts.contains(ext) else { return nil }
        let audio = audioMime || ["m4a", "mp3", "aac", "wav"].contains(ext)
        return ExtractedMedia(
            title: page.deletingPathExtension().lastPathComponent,
            webpageURL: page.absoluteString,
            formats: [MediaFormat(id: "direct", url: page,
                                  ext: ext.nonEmpty ?? (audio ? "m4a" : "mp4"),
                                  height: audio ? nil : 720,
                                  vcodec: audio ? "none" : "avc1",
                                  acodec: "mp4a.40.2", filesize: size,
                                  tbr: nil, httpHeaders: [:])])
    }

    // MARK: YouTube InnerTube

    /// One InnerTube client. Data only (2.5.2 forbids downloaded code), so
    /// `config/youtube-clients.json` can follow yt-dlp's client bumps between
    /// App Store releases. Unknown JSON fields are ignored.
    struct YouTubeClient: Codable, Sendable, Equatable {
        var name: String
        var version: String
        var ua: String
        var deviceMake: String? = nil
        var deviceModel: String? = nil
        var osName: String? = nil
        var osVersion: String? = nil
        var androidSdkVersion: Int? = nil
    }

    /// Fallback when no fetched config is cached; keep in step with
    /// `config/youtube-clients.json`. Values from yt-dlp 2026.08.19.
    ///
    /// VISIONOS with visitorData hands out plain URLs for every format (4K
    /// AV1, 1080p H.264, AAC). ANDROID_VR is next; its https formats may need
    /// a PO token past ~20 MB. ANDROID is the last resort: only itag 18 (360p
    /// muxed) has a URL, but it still serves made-for-kids videos, which
    /// VISIONOS refuses.
    static let defaultClients: [YouTubeClient] = [
        YouTubeClient(name: "VISIONOS", version: "1.02",
                      ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
                      deviceMake: "Apple", deviceModel: "RealityDevice17,1",
                      osName: "visionOS", osVersion: "26.5.23O471"),
        YouTubeClient(name: "ANDROID_VR", version: "1.65.10",
                      ua: "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                      deviceMake: "Oculus", deviceModel: "Quest 3",
                      osName: "Android", osVersion: "12L", androidSdkVersion: 32),
        YouTubeClient(name: "ANDROID", version: "21.26.364",
                      ua: "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip",
                      osName: "Android", osVersion: "11", androidSdkVersion: 30),
    ]

    private struct ClientConfig: Decodable { var clients: [YouTubeClient] }

    private static let configURL = URL(string: "https://raw.githubusercontent.com/haithamassoli/naqi/main/config/youtube-clients.json")!
    private static let configKey = "naqi.yt.clients"
    private static let configCheckedKey = "naqi.yt.clientsCheckedAt"
    private static let visitorKey = "naqi.yt.visitorData"
    private static let visitorAtKey = "naqi.yt.visitorDataAt"
    /// App Group, so the share extension and the app share one cache.
    private static var store: UserDefaults { AppGroup.defaults ?? .standard }

    /// The cached config if one validated, else the compiled-in defaults.
    static var clients: [YouTubeClient] {
        store.data(forKey: configKey).flatMap(parseClientConfig) ?? defaultClients
    }

    /// Nil unless the list is non-empty and every entry has name, version and UA.
    static func parseClientConfig(_ data: Data) -> [YouTubeClient]? {
        guard let config = try? JSONDecoder().decode(ClientConfig.self, from: data),
              !config.clients.isEmpty,
              config.clients.allSatisfy({ !$0.name.isEmpty && !$0.version.isEmpty && !$0.ua.isEmpty })
        else { return nil }
        return config.clients
    }

    /// Weekly, like `YtDlp.updateIfDue`. `force` is for an `.extractor`
    /// failure (Android's recovery update), still at most hourly so a run of
    /// failing links cannot hammer GitHub. An invalid or missing file keeps
    /// the previous cache.
    static func refreshClientConfigIfDue(force: Bool = false) async {
        let last = store.object(forKey: configCheckedKey) as? Date ?? .distantPast
        guard Date.now.timeIntervalSince(last) > (force ? 3600 : 7 * 86400) else { return }
        var req = URLRequest(url: configURL, cachePolicy: .reloadIgnoringLocalCacheData,
                             timeoutInterval: 15)
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        // Offline: leave the timestamp so the next call tries again.
        guard let (data, response) = try? await URLSession.shared.data(for: req) else { return }
        store.set(Date.now, forKey: configCheckedKey)
        if (response as? HTTPURLResponse)?.statusCode == 200, parseClientConfig(data) != nil {
            store.set(data, forKey: configKey)
        }
    }

    /// VISIONOS answers "confirm you're not a bot" without a visitor id.
    /// Cached 12 h; nil when sw.js_data could not be read.
    static func visitorData(refresh: Bool = false) async -> String? {
        if !refresh, let cached = store.string(forKey: visitorKey),
           let at = store.object(forKey: visitorAtKey) as? Date,
           Date.now.timeIntervalSince(at) < 12 * 3600 { return cached }
        var req = URLRequest(url: URL(string: "https://www.youtube.com/sw.js_data")!,
                             timeoutInterval: 15)
        req.setValue(defaultClients[0].ua, forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: req),
              let value = parseVisitorData(data) else { return nil }
        store.set(value, forKey: visitorKey)
        store.set(Date.now, forKey: visitorAtKey)
        return value
    }

    /// `)]}'` + JSON; the id sits at `[0][2][0][0][13]`.
    static func parseVisitorData(_ data: Data) -> String? {
        var body = data
        let guardPrefix = Data(")]}'".utf8)
        if body.starts(with: guardPrefix) { body.removeFirst(guardPrefix.count) }
        var node = try? JSONSerialization.jsonObject(with: body)
        for i in [0, 2, 0, 0, 13] {
            guard let array = node as? [Any], i < array.count else { return nil }
            node = array[i]
        }
        guard let value = node as? String, !value.isEmpty else { return nil }
        return value
    }

    /// Clients in order. A bot check refetches visitorData once and retries
    /// the same client. Unavailable, geo, rate limit and network errors are the
    /// same on every client, so they fail fast; anything else moves on.
    private static func youtube(id: String, webpage: String,
                                cacheDir: URL?) async throws -> ExtractedMedia {
        var visitor = await visitorData()
        var refreshed = false
        var last = DownloadError.extractor("no YouTube client configured")
        for client in clients {
            while true {
                do {
                    return try await innertube(id: id, webpage: webpage, client: client,
                                               visitor: visitor, cacheDir: cacheDir)
                } catch DownloadError.extractor(let why) {
                    last = .extractor(why)
                    guard isBotCheck(why), !refreshed else { break }
                    refreshed = true
                    visitor = await visitorData(refresh: true)
                }
            }
        }
        throw last
    }

    private static func innertube(id: String, webpage: String, client: YouTubeClient,
                                  visitor: String?, cacheDir: URL?) async throws -> ExtractedMedia {
        let endpoint = URL(string: "https://www.youtube.com/youtubei/v1/player?prettyPrint=false")!
        var req = URLRequest(url: endpoint, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(client.ua, forHTTPHeaderField: "User-Agent")
        req.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        var context: [String: Any] = [
            "clientName": client.name,
            "clientVersion": client.version,
            "hl": "en",
            "gl": "US",
        ]
        let extras: [String: Any?] = [
            "deviceMake": client.deviceMake,
            "deviceModel": client.deviceModel,
            "osName": client.osName,
            "osVersion": client.osVersion,
            "androidSdkVersion": client.androidSdkVersion,
            "visitorData": visitor,
        ]
        for case let (key, value?) in extras { context[key] = value }
        if let visitor { req.setValue(visitor, forHTTPHeaderField: "X-Goog-Visitor-Id") }
        let body: [String: Any] = [
            "videoId": id,
            "context": ["client": context],
            "contentCheckOk": true,
            "racyCheckOk": true,
        ]
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 429 { throw DownloadError.rateLimited }
        if (500..<600).contains(status) { throw DownloadError.network("HTTP \(status)") }
        // 400 is how InnerTube retires a client version: try the next one.
        guard status == 200 else { throw DownloadError.extractor("\(client.name) HTTP \(status)") }
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DownloadError.extractor("\(client.name) returned no JSON")
        }
        let media = try parsePlayer(root, id: id, webpage: webpage, client: client)
        if let cacheDir { Cache.store(root, url: webpage, client: client.name, dir: cacheDir) }
        return media
    }

    /// A `/player` response to formats. Separate from the request so the
    /// saved VISIONOS fixture can be parsed without network.
    static func parsePlayer(_ root: [String: Any], id: String, webpage: String,
                            client: YouTubeClient) throws -> ExtractedMedia {
        let play = root["playabilityStatus"] as? [String: Any] ?? [:]
        let reason = play["reason"] as? String ?? (play["messages"] as? [String])?.first
        if let error = classify(status: play["status"] as? String, reason: reason) { throw error }

        let details = root["videoDetails"] as? [String: Any] ?? [:]
        let title = details["title"] as? String ?? "youtube-\(id)"
        let streaming = root["streamingData"] as? [String: Any] ?? [:]
        let raw = (streaming["formats"] as? [[String: Any]] ?? [])
            + (streaming["adaptiveFormats"] as? [[String: Any]] ?? [])
        var formats: [MediaFormat] = []
        var seen = Set<String>()
        var durationMs: Double?
        for f in raw {
            // The same itag repeats as a dynamic-range-compressed copy and as
            // dubbed tracks; keep the original so format ids stay unique.
            if f["isDrc"] as? Bool == true { continue }
            if let track = f["audioTrack"] as? [String: Any],
               track["audioIsDefault"] as? Bool == false { continue }
            guard let itag = f["itag"] as? Int, seen.insert(String(itag)).inserted,
                  let urlStr = f["url"] as? String, let url = URL(string: urlStr),
                  let mime = parseMime(f["mimeType"] as? String ?? "") else { continue }
            durationMs = durationMs ?? (f["approxDurationMs"] as? String).flatMap(Double.init)
            let transfer = (f["colorInfo"] as? [String: Any])?["transferCharacteristics"] as? String ?? ""
            formats.append(MediaFormat(
                id: String(itag),
                url: url,
                ext: mime.ext,
                height: mime.vcodec == "none" ? nil : f["height"] as? Int,
                vcodec: mime.vcodec,
                acodec: mime.acodec,
                filesize: (f["contentLength"] as? String).flatMap { Int64($0) },
                tbr: (f["bitrate"] as? Double).map { $0 / 1000 },
                httpHeaders: ["User-Agent": client.ua, "Referer": "https://www.youtube.com"],
                fps: f["fps"] as? Int,
                lastModified: f["lastModified"] as? String,
                hdr: (f["qualityLabel"] as? String)?.contains("HDR") == true
                    || transfer.contains("SMPTEST2084") || transfer.contains("ARIB_STD_B67")))
        }
        guard formats.contains(where: { $0.hasVideo && ($0.height ?? 0) >= 144 }) else {
            throw DownloadError.extractor("\(client.name): no playable formats")
        }
        return ExtractedMedia(
            title: String(title.prefix(80)), webpageURL: webpage, formats: formats,
            durationSec: durationMs.map { $0 / 1000 }
                ?? (details["lengthSeconds"] as? String).flatMap(Double.init),
            hlsManifestURL: (streaming["hlsManifestUrl"] as? String).flatMap(URL.init(string:)),
            client: client.name)
    }

    /// `video/mp4; codecs="avc1.42001E, mp4a.40.2"` → mp4 / avc1.42001E / mp4a.40.2.
    /// Audio mp4 becomes `m4a`; a missing codec is `none`.
    static func parseMime(_ mime: String) -> (ext: String, vcodec: String, acodec: String)? {
        let parts = mime.split(separator: ";", maxSplits: 1)
        let type = parts.first?.trimmingCharacters(in: .whitespaces).split(separator: "/") ?? []
        guard type.count == 2 else { return nil }
        var codecs: [String] = []
        if parts.count > 1, let r = parts[1].range(of: "codecs=") {
            codecs = parts[1][r.upperBound...]
                .split(separator: ",")
                .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")) }
                .filter { !$0.isEmpty }
        }
        let sub = String(type[1])
        if type[0] == "audio" {
            return (sub == "mp4" ? "m4a" : sub, "none", codecs.first ?? "none")
        }
        return (sub, codecs.first ?? "none", codecs.count > 1 ? codecs[1] : "none")
    }

    /// Phase 3 table over `playabilityStatus`; nil means playable.
    ///
    /// Bot checks come back as `.extractor` so the chain moves on (and
    /// `youtube` refetches visitorData first). "This video is not available"
    /// is deliberately not fail-fast: VISIONOS answers it for made-for-kids
    /// videos, which ANDROID still serves (measured 2026-09-27).
    static func classify(status: String?, reason: String?) -> DownloadError? {
        guard let status, status != "OK" else { return nil }
        let why = reason ?? status
        func says(_ pattern: String) -> Bool {
            why.range(of: #"\b("# + pattern + #")\b"#, options: [.regularExpression, .caseInsensitive]) != nil
        }
        if says("country|location") { return .geo(why) }
        if status == "LOGIN_REQUIRED", says("bot") { return .extractor(why) }
        if says("age|inappropriate") { return .unavailable(why) }
        if says("unavailable|private|removed|members|not a valid") {
            return .unavailable(why)
        }
        return .extractor(why)
    }

    private static func isBotCheck(_ reason: String) -> Bool {
        reason.range(of: #"\bbot\b"#, options: [.regularExpression, .caseInsensitive]) != nil
    }

    static func youtubeID(_ url: String) -> String? {
        let patterns = [
            #"(?:youtube\.com/watch\?(?:[^#]*&)?v=|youtube\.com/embed/|youtube\.com/shorts/|youtu\.be/)([A-Za-z0-9_-]{11})"#,
        ]
        for p in patterns {
            if let re = try? NSRegularExpression(pattern: p),
               let m = re.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)),
               m.numberOfRanges > 1, let r = Range(m.range(at: 1), in: url) {
                return String(url[r])
            }
        }
        return nil
    }

    // MARK: HTML crumbs

    private static func meta(_ html: String, property: String? = nil, name: String? = nil) -> String? {
        let key = property.map { "property=\"\($0)\"" } ?? name.map { "name=\"\($0)\"" } ?? ""
        guard let re = try? NSRegularExpression(
            pattern: "<meta[^>]+" + NSRegularExpression.escapedPattern(for: key)
                + "[^>]+content=\"([^\"]+)\"|<meta[^>]+content=\"([^\"]+)\"[^>]+"
                + NSRegularExpression.escapedPattern(for: key),
            options: .caseInsensitive),
              let m = re.firstMatch(in: html, range: NSRange(html.startIndex..., in: html))
        else { return nil }
        for i in 1..<m.numberOfRanges {
            if let r = Range(m.range(at: i), in: html), !r.isEmpty { return String(html[r]) }
        }
        return nil
    }

    private static func tag(_ html: String, _ name: String) -> String? {
        guard let re = try? NSRegularExpression(pattern: "<\(name)[^>]*>([^<]+)", options: .caseInsensitive),
              let m = re.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
              let r = Range(m.range(at: 1), in: html) else { return nil }
        return String(html[r])
    }

    private static func jsonLDVideos(_ html: String) -> [String] {
        guard let re = try? NSRegularExpression(
            pattern: "<script[^>]+type=\"application/ld\\+json\"[^>]*>(.*?)</script>",
            options: [.caseInsensitive, .dotMatchesLineSeparators]) else { return [] }
        let ns = NSRange(html.startIndex..., in: html)
        var urls: [String] = []
        re.enumerateMatches(in: html, range: ns) { m, _, _ in
            guard let m, let r = Range(m.range(at: 1), in: html),
                  let obj = try? JSONSerialization.jsonObject(with: Data(html[r].utf8)) else { return }
            func walk(_ any: Any) {
                if let d = any as? [String: Any] {
                    if let s = d["contentUrl"] as? String { urls.append(s) }
                    d.values.forEach(walk)
                } else if let a = any as? [Any] { a.forEach(walk) }
            }
            walk(obj)
        }
        return urls
    }

    private static func quotedURLs(_ html: String) -> [String] {
        guard let re = try? NSRegularExpression(
            pattern: #"https?://[^"'\\\s>]+\.(?:mp4|m4a|mp3|mov|webm)(?:\?[^"'\\\s>]*)?"#,
            options: .caseInsensitive) else { return [] }
        let ns = NSRange(html.startIndex..., in: html)
        var out: [String] = []
        re.enumerateMatches(in: html, range: ns) { m, _, _ in
            if let m, let r = Range(m.range, in: html) { out.append(String(html[r])) }
        }
        return out
    }

    private static func looksLikeMedia(_ s: String) -> Bool {
        let l = s.lowercased()
        return l.contains(".mp4") || l.contains(".m4a") || l.contains(".mp3") || l.contains(".webm") || l.contains(".mov")
    }
}

extension NativeExtract {
    /// Player responses in the App Group, one file per shared URL. The raw
    /// JSON rather than the parse, so a parser fix also covers entries
    /// already on disk.
    enum Cache {
        static let maxAge: TimeInterval = 3600

        /// `Library/Caches` inside the App Group: never backed up, purgeable.
        static var dir: URL? {
            AppGroup.container?.appendingPathComponent("Library/Caches/extract-cache", isDirectory: true)
        }

        static func file(_ url: String, dir: URL) -> URL {
            let digest = SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
            return dir.appendingPathComponent("\(digest).json")
        }

        /// Keeps only what `parsePlayer` reads: the full response also carries
        /// storyboards, captions and the description.
        static func store(_ root: [String: Any], url: String, client: String, dir: URL,
                          now: Date = .now) {
            let fm = FileManager.default
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            let key = URLResourceKey.contentModificationDateKey
            for f in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [key])) ?? [] {
                let at = (try? f.resourceValues(forKeys: [key]))?.contentModificationDate
                if now.timeIntervalSince(at ?? .distantPast) > maxAge { try? fm.removeItem(at: f) }
            }
            let details = root["videoDetails"] as? [String: Any] ?? [:]
            let player: [String: Any] = [
                "playabilityStatus": root["playabilityStatus"] ?? [String: Any](),
                "streamingData": root["streamingData"] ?? [String: Any](),
                "videoDetails": details.filter { $0.key == "title" || $0.key == "lengthSeconds" },
            ]
            let entry: [String: Any] = ["client": client, "fetchedAt": now.timeIntervalSince1970,
                                        "player": player]
            guard let data = try? JSONSerialization.data(withJSONObject: entry) else { return }
            try? data.write(to: file(url, dir: dir), options: .atomic)
        }

        /// The cached parse when younger than `maxAge`. `fresh` deletes the
        /// entry instead, so a failed re-extract cannot fall back to it.
        static func lookup(_ url: String, id: String, fresh: Bool, dir: URL,
                           now: Date = .now) -> ExtractedMedia? {
            let path = file(url, dir: dir)
            if fresh { try? FileManager.default.removeItem(at: path); return nil }
            guard let data = try? Data(contentsOf: path),
                  let entry = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let at = entry["fetchedAt"] as? Double,
                  now.timeIntervalSince1970 - at < maxAge,
                  let player = entry["player"] as? [String: Any],
                  let name = entry["client"] as? String,
                  let client = (clients + defaultClients).first(where: { $0.name == name })
            else { return nil }
            return try? parsePlayer(player, id: id, webpage: url, client: client)
        }
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}
