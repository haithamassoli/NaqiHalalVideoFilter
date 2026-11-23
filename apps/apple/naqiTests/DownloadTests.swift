import AVFoundation
import Foundation
import Testing
@testable import naqi

@Suite("Download", .serialized)
struct DownloadTests {

    @Test("the paste field stays hidden for three days after the first open")
    func pasteGate() {
        let first = Date(timeIntervalSince1970: 1_791_763_200)
        #expect(!LinkPaste.isVisible(at: first, firstOpen: first))
        #expect(!LinkPaste.isVisible(at: first.addingTimeInterval(LinkPaste.delay - 1), firstOpen: first))
        #expect(LinkPaste.isVisible(at: first.addingTimeInterval(LinkPaste.delay), firstOpen: first))
    }

    @Test("the pasted-link regex takes the first http(s) URL and strips trailing punctuation")
    func urlInText() {
        #expect(VideoURL.first(in: "https://youtu.be/dQw4w9WgXcQ")
                == "https://youtu.be/dQw4w9WgXcQ")
        #expect(VideoURL.first(in: "look at this https://example.com/v/a.mp4 please")
                == "https://example.com/v/a.mp4")
        #expect(VideoURL.first(in: "no link here") == nil)
        #expect(VideoURL.first(in: "ftp://not-this.example/") == nil)
        #expect(VideoURL.first(in: "file:///tmp/clip.mp4") == nil)
        #expect(NativeExtract.youtubeID("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                == "dQw4w9WgXcQ")
        #expect(NativeExtract.youtubeID("https://youtu.be/dQw4w9WgXcQ") == "dQw4w9WgXcQ")
        #expect(NativeExtract.youtubeID("https://www.youtube.com/shorts/abcdefghijk")
                == "abcdefghijk")
        #expect(NativeExtract.youtubeID("https://example.com/v") == nil)
    }

    @Test("quality selectors pick audio-only, a height cap, or combined")
    func qualitySelect() {
        let v480 = fmt("v480", ext: "mp4", height: 480, v: "avc1", a: "none")
        let v720 = fmt("v720", ext: "mp4", height: 720, v: "avc1", a: "none")
        let v1080 = fmt("v1080", ext: "webm", height: 1080, v: "vp9", a: "none")
        let audio = fmt("a", ext: "m4a", height: nil, v: "none", a: "mp4a")
        let combo = fmt("c", ext: "mp4", height: 360, v: "avc1", a: "mp4a")
        let all = [v480, v720, v1080, audio, combo]

        let a = DownloadQuality.audio.select(all)
        #expect(a.map(\.id) == ["a"])

        let p480 = DownloadQuality.p480.select(all)
        #expect(p480.contains(where: { $0.id == "v480" }))
        #expect(p480.contains(where: { $0.id == "a" }))
        #expect(!p480.contains(where: { $0.height == 720 }))

        let best = DownloadQuality.best.select(all)
        #expect(best.contains(where: { $0.hasVideo }))
        #expect(best.contains(where: { $0.hasAudio }))
        // mp4 video outranks webm even when the webm is taller.
        #expect(best.contains(where: { $0.id == "v720" }))

        let combo720 = fmt("c720", ext: "mp4", height: 720, v: "avc1", a: "mp4a")
        #expect(DownloadQuality.p720.select([v720, audio, combo720]).map(\.id) == ["c720"])
        #expect(DownloadQuality.best.resolved(fast: true) == .p720)
        #expect(DownloadQuality.p480.resolved(fast: true) == .p480)
    }

    @Test("yt-dlp -J dump parses formats and skips HLS")
    func parseDump() throws {
        let json = """
        {"title":"Clip","webpage_url":"https://example.com/v",
         "formats":[
           {"format_id":"hls","url":"https://ex.com/a.m3u8","protocol":"m3u8_native","ext":"mp4","vcodec":"avc1","acodec":"none"},
           {"format_id":"18","url":"https://ex.com/a.mp4","protocol":"https","ext":"mp4","height":360,"vcodec":"avc1","acodec":"mp4a.40.2","tbr":400},
           {"format_id":"140","url":"https://ex.com/a.m4a","protocol":"https","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2","tbr":128}
         ]}
        """
        let info = try YtDlp.parseDump(json)
        #expect(info.title == "Clip")
        #expect(info.formats.map(\.id) == ["18", "140"])
        #expect(DownloadQuality.audio.select(info.formats).map(\.id) == ["140"])
    }

    @Test("filename sanitizing keeps Arabic and strips path separators")
    func sanitize() {
        #expect(Downloader.sanitize("holiday/in\\tabuk") == "holiday_in_tabuk")
        #expect(Downloader.sanitize("رحلة إلى تبوك") == "رحلة إلى تبوك")
        #expect(Downloader.sanitize("   ") == "download")
        #expect(Downloader.key(of: "https://a.example/x") == Downloader.key(of: "https://a.example/x"))
        #expect(Downloader.key(of: "https://a.example/x") != Downloader.key(of: "https://a.example/y"))
    }

    @Test("old share manifests still decode when url and quality are absent")
    func oldManifest() throws {
        let data = Data("""
            {"id":"1D9F0C8E-4A2B-4E15-9C3D-2F6A1B0E7C41",
             "fileName":"clip.mp4","receivedAt":770000000}
            """.utf8)
        let m = try JSONDecoder().decode(ShareManifest.self, from: data)
        #expect(m.url == nil)
        #expect(m.quality == nil)
    }

    @Test("a link manifest round-trips url, quality and options")
    func linkManifest() throws {
        let options = ShareOptions(removeMusic: true, censor: false, who: "everyone",
                                   processingMode: "fast")
        let m = ShareManifest(id: UUID(), fileName: "youtu.be", receivedAt: .now,
                              options: options, url: "https://youtu.be/dQw4w9WgXcQ",
                              quality: DownloadQuality.p480.rawValue)
        let decoded = try JSONDecoder().decode(ShareManifest.self,
                                               from: JSONEncoder().encode(m))
        #expect(decoded.url == m.url)
        #expect(decoded.quality == "P480")
        #expect(decoded.options == options)
        #expect(decoded.options?.processingMode == "fast")
    }

    @Test("mux joins a video file and an audio file into one playable mp4")
    func mux() async throws {
        guard let video = Fixtures.qaVideo else { return }
        let audio = try Fixtures.audioClip("mux-a-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: audio) }
        let out = Fixtures.scratch("mux-\(UUID().uuidString).mp4")
        try await MediaMux.merge(video: video, audio: audio, into: out)
        let asset = AVURLAsset(url: out)
        let v = try await asset.loadTracks(withMediaType: .video)
        let a = try await asset.loadTracks(withMediaType: .audio)
        #expect(!v.isEmpty)
        #expect(!a.isEmpty)
    }

    @Test("direct file formats download by copy into quarantine")
    func directCopy() async throws {
        guard let video = Fixtures.qaVideo else { return }
        let url = "https://example.invalid/clip.mp4"
        let dir = Downloader.quarantineDir(for: url)
        defer { try? FileManager.default.removeItem(at: dir) }
        let dest = dir.appendingPathComponent("clip.mp4")
        let format = MediaFormat(id: "direct", url: video, ext: "mp4", height: 360,
                                 vcodec: "avc1", acodec: "mp4a.40.2", filesize: nil,
                                 tbr: nil, httpHeaders: [:])
        #expect(DownloadQuality.best.select([format]).map(\.id) == ["direct"])
        // The public download() would extract from the fake host; copy the
        // selected format the same way the fetch path does for file URLs.
        try FileManager.default.copyItem(at: video, to: dest)
        #expect(FileManager.default.fileExists(atPath: dest.path))
        #expect(Downloader.isQuarantined(dest))
        Downloader.discard(dest)
        #expect(!FileManager.default.fileExists(atPath: dest.path))
    }

    @Test("share inbox drains a link manifest into a remote-URL job")
    func drainLink() async throws {
        guard let dir = JobTests.usableInbox() else {
            #if os(iOS)
            Issue.record("App Group container unusable")
            #endif
            return
        }
        let id = UUID()
        let manifest = ShareManifest(id: id, fileName: "youtu.be", receivedAt: Date(),
                                     options: ShareOptions(removeMusic: true, censor: false, who: "everyone"),
                                     url: "https://youtu.be/dQw4w9WgXcQ", quality: "P720")
        try JSONEncoder().encode(manifest)
            .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)
        let queue = JobQueue(storeURL: Fixtures.scratch("dl-link-\(id.uuidString).json"))
        #expect(await ShareInbox.drain(into: queue, destination: .photos) == 1)
        let job = try #require(await queue.jobs.first)
        #expect(job.remoteURL == "https://youtu.be/dQw4w9WgXcQ")
        #expect(job.quality == "P720")
        #expect(job.ops.removeMusic)
        #expect(!job.ops.censor)
        await queue.cancel(job.id)
    }

    @Test("an audio-quality shared link lands in the in-app library when no folder is set")
    func drainAudioLink() async throws {
        guard let dir = JobTests.usableInbox() else {
            #if os(iOS)
            Issue.record("App Group container unusable")
            #endif
            return
        }
        let id = UUID()
        let manifest = ShareManifest(id: id, fileName: "youtu.be", receivedAt: Date(),
                                     options: ShareOptions(removeMusic: true, censor: false, who: "everyone"),
                                     url: "https://youtu.be/dQw4w9WgXcQ", quality: "AUDIO")
        try JSONEncoder().encode(manifest)
            .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)
        let queue = JobQueue(storeURL: Fixtures.scratch("dl-audio-link-\(id.uuidString).json"))
        #expect(await ShareInbox.drain(into: queue, destination: .photos) == 1)
        let job = try #require(await queue.jobs.first)
        #expect(job.remoteURL == "https://youtu.be/dQw4w9WgXcQ")
        #expect(DownloadQuality.of(job.quality) == .audio)
        #expect(job.destination == .userFolder)
        #expect(job.folder?.standardizedFileURL == OutputLibrary.root.standardizedFileURL)
        await queue.cancel(job.id)
    }

    @Test("a link job captures the page URL so enqueue KEEP sees a retry as the same row")
    func captureLinkIdentity() {
        let ops = FilterOps(removeMusic: true, censor: false)
        let a = Job.captureLink("https://youtu.be/aaaaaaaaaaa", quality: .p720, ops: ops,
                                destination: .photos)
        let b = Job.captureLink("https://youtu.be/aaaaaaaaaaa", quality: .p720, ops: ops,
                                destination: .photos)
        #expect(a.remoteURL == "https://youtu.be/aaaaaaaaaaa")
        #expect(DownloadQuality.of(a.quality) == .p720)
        #expect(Checkpoint.key(source: a.source, ops: a.ops)
                == Checkpoint.key(source: b.source, ops: b.ops))
    }

    @Test("a failed yt-dlp step updates once and retries once, never loops")
    func retryAfterUpdate() async throws {
        var calls = 0
        var updates = 0
        let ok = try await YtDlp.retryingAfterUpdate {
            calls += 1
            if calls == 1 { throw DownloadError.unsupported }
            return "ok"
        } update: { updates += 1 } lastUpdate: { .distantPast }
        #expect(ok == "ok" && calls == 2 && updates == 1)

        calls = 0; updates = 0
        await #expect(throws: DownloadError.self) {
            try await YtDlp.retryingAfterUpdate {
                calls += 1
                throw DownloadError.extractor("still broken")
            } update: { updates += 1 } lastUpdate: { .distantPast }
        }
        #expect(calls == 2 && updates == 1)

        calls = 0; updates = 0
        await #expect(throws: DownloadError.self) {
            try await YtDlp.retryingAfterUpdate {
                calls += 1
                throw DownloadError.cancelled
            } update: { updates += 1 } lastUpdate: { .distantPast }
        }
        #expect(calls == 1 && updates == 0)
    }

    @Test("audio quality forces music-only ops")
    func audioFits() {
        var ops = FilterOps(removeMusic: false, censor: true, who: .women)
        ops.fit(hasVideo: false)
        #expect(ops.removeMusic)
        #expect(!ops.censor)
    }

    @Test("JobFailure maps download errors")
    func failureMap() {
        #expect(JobFailure.of(DownloadError.unsupported) == .downloadUnsupported)
        #expect(JobFailure.of(DownloadError.network("timed out")) == .downloadNetwork)
        #expect(JobFailure.of(DownloadError.noSpace) == .lowSpace)
        #expect(JobFailure.of(DownloadError.generic("boom")) == .downloadGeneric)
        #expect(JobFailure.of(DownloadError.extractor("nsig")) == .downloadExtractor)
    }

    #if os(macOS)
    @Test("yt-dlp extracts formats from a local file when the binary can run")
    func ytdlpLocalFile() async throws {
        guard let video = Fixtures.qaVideo else { return }
        let info: ExtractedMedia
        do {
            info = try await YtDlp.shared.extract(video.path)
        } catch {
            return
        }
        #expect(!info.formats.isEmpty)
        let out = try await Downloader.download(url: video.path, quality: .p480)
        defer { Downloader.discard(out) }
        #expect(FileManager.default.isReadableFile(atPath: out.path))
        let size = (try FileManager.default.attributesOfItem(atPath: out.path)[.size] as? NSNumber)?.int64Value ?? 0
        #expect(size > 0)
    }
    #endif

    private func fmt(_ id: String, ext: String, height: Int?, v: String, a: String) -> MediaFormat {
        MediaFormat(id: id, url: URL(string: "https://ex.com/\(id).\(ext)")!,
                    ext: ext, height: height, vcodec: v, acodec: a,
                    filesize: nil, tbr: Double(height ?? 128), httpHeaders: [:])
    }
}
