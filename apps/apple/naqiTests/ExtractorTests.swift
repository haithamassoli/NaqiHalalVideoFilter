import Foundation
import Testing
@testable import naqi

/// YouTube extractor (plan Phase 1), playability taxonomy (Phase 3) and format
/// policy (Phase 4), all against the saved VISIONOS player response.
@Suite("Extractor")
struct ExtractorTests {

    private final class BundleToken {}

    static func fixture() throws -> [String: Any] {
        let url = try #require(Bundle(for: BundleToken.self)
            .url(forResource: "youtube-visionos-player", withExtension: "json"))
        return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    static func media() throws -> ExtractedMedia {
        try NativeExtract.parsePlayer(fixture(), id: "aqz-KE-bpKQ",
                                      webpage: "https://youtu.be/aqz-KE-bpKQ",
                                      client: NativeExtract.defaultClients[0])
    }

    @Test("the VISIONOS fixture parses into unique formats with codecs, heights and fps")
    func parseFixture() throws {
        let root = try Self.fixture()
        let raw = (root["streamingData"] as? [String: Any])?["adaptiveFormats"] as? [Any]
        #expect(raw?.count == 32)

        let media = try Self.media()
        let ids = media.formats.map(\.id)
        // Five DRC audio duplicates dropped; every id is unique.
        #expect(ids.count == 27)
        #expect(Set(ids).count == ids.count)
        #expect(media.client == "VISIONOS")
        #expect(media.title.hasPrefix("Big Buck Bunny"))
        #expect(abs((media.durationSec ?? 0) - 634.566) < 0.01)
        #expect(media.hlsManifestURL != nil)

        let byID = Dictionary(uniqueKeysWithValues: media.formats.map { ($0.id, $0) })
        let av1 = try #require(byID["401"])
        #expect(av1.ext == "mp4" && av1.vcodec == "av01.0.13M.08" && av1.acodec == "none")
        #expect(av1.height == 2160 && av1.fps == 60 && av1.hasVideo && !av1.hasAudio)
        #expect(av1.filesize == 712_445_280 && av1.lastModified == "1719199091416524")
        let h264 = try #require(byID["299"])
        #expect(h264.vcodec == "avc1.64002a" && h264.height == 1080 && h264.fps == 60)
        #expect(!h264.hdr)
        let aac = try #require(byID["140"])
        #expect(aac.ext == "m4a" && aac.acodec == "mp4a.40.2" && aac.vcodec == "none")
        #expect(aac.height == nil && aac.hasAudio && !aac.hasVideo)
        #expect((aac.tbr ?? 0) > 100)
        let vp9 = try #require(byID["315"])
        #expect(vp9.ext == "webm" && vp9.vcodec == "vp9")
        let opus = try #require(byID["251"])
        #expect(opus.ext == "webm" && opus.acodec == "opus" && opus.vcodec == "none")
        #expect(media.formats.allSatisfy { $0.httpHeaders["User-Agent"] == NativeExtract.defaultClients[0].ua })
    }

    @Test("mimeType yields container and codecs", arguments: [
        ("video/mp4; codecs=\"av01.0.09M.08\"", "mp4", "av01.0.09M.08", "none"),
        ("audio/mp4; codecs=\"mp4a.40.2\"", "m4a", "none", "mp4a.40.2"),
        ("video/webm; codecs=\"vp9\"", "webm", "vp9", "none"),
        ("audio/webm; codecs=\"opus\"", "webm", "none", "opus"),
        ("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", "mp4", "avc1.42001E", "mp4a.40.2"),
    ])
    func mime(_ input: String, _ ext: String, _ vcodec: String, _ acodec: String) throws {
        let parsed = try #require(NativeExtract.parseMime(input))
        #expect(parsed.ext == ext && parsed.vcodec == vcodec && parsed.acodec == acodec)
    }

    @Test("a non-default dubbed track and a DRC copy are dropped")
    func dubbedAndDrc() throws {
        func format(_ itag: Int, _ extra: [String: Any]) -> [String: Any] {
            ["itag": itag, "url": "https://example.invalid/\(itag)",
             "mimeType": "audio/mp4; codecs=\"mp4a.40.2\""].merging(extra) { $1 }
        }
        let root: [String: Any] = [
            "playabilityStatus": ["status": "OK"],
            "streamingData": ["adaptiveFormats": [
                ["itag": 18, "url": "https://example.invalid/18", "height": 360,
                 "mimeType": "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\""],
                format(140, ["audioTrack": ["audioIsDefault": false, "id": "de.3"]]),
                format(140, ["isDrc": true]),
                format(140, ["audioTrack": ["audioIsDefault": true, "id": "en.4"]]),
            ]],
        ]
        let media = try NativeExtract.parsePlayer(root, id: "x", webpage: "w",
                                                  client: NativeExtract.defaultClients[2])
        #expect(media.formats.map(\.id) == ["18", "140"])
        #expect(media.formats[0].hasVideo && media.formats[0].hasAudio)
        #expect(media.client == "ANDROID")
    }

    @Test("a response with no video URL is an extractor failure")
    func noURLs() {
        let root: [String: Any] = [
            "playabilityStatus": ["status": "OK"],
            "streamingData": ["adaptiveFormats": [
                ["itag": 137, "mimeType": "video/mp4; codecs=\"avc1.640028\"", "height": 1080,
                 "signatureCipher": "s=…"],
            ]],
        ]
        do {
            _ = try NativeExtract.parsePlayer(root, id: "x", webpage: "w",
                                              client: NativeExtract.defaultClients[0])
            Issue.record("expected .extractor")
        } catch DownloadError.extractor {
        } catch {
            Issue.record("expected .extractor, got \(error)")
        }
    }

    enum Class: Equatable { case ok, unavailable, geo, extractor }

    /// Reasons are real `playabilityStatus` texts (bot, age, removed, kids
    /// measured 2026-09-27; private, members, geo from yt-dlp's tests).
    @Test("playabilityStatus maps to the Phase 3 classes", arguments: [
        ("OK", nil, Class.ok),
        ("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot", .extractor),
        ("LOGIN_REQUIRED", "Sign in to confirm your age", .unavailable),
        ("LOGIN_REQUIRED", "This video may be inappropriate for some users.", .unavailable),
        ("LOGIN_REQUIRED", "This video is private", .unavailable),
        ("ERROR", "This video is unavailable", .unavailable),
        ("ERROR", "This video has been removed by the uploader", .unavailable),
        ("UNPLAYABLE", "Join this channel to get access to members-only content like this video, and other exclusive perks.", .unavailable),
        ("ERROR", "This is not a valid video id", .unavailable),
        ("UNPLAYABLE", "The uploader has not made this video available in your country", .geo),
        ("UNPLAYABLE", "This video is not available in your location", .geo),
        // Made for kids on VISIONOS: ANDROID still serves it, so not fail-fast.
        ("UNPLAYABLE", "This video is not available", .extractor),
        ("LIVE_STREAM_OFFLINE", "This live event will begin in a few moments.", .extractor),
    ] as [(String, String?, Class)])
    func classify(_ status: String, _ reason: String?, _ expected: Class) {
        let got: Class = switch NativeExtract.classify(status: status, reason: reason) {
        case nil: .ok
        case .unavailable?: .unavailable
        case .geo?: .geo
        case .extractor?: .extractor
        default: .ok
        }
        #expect(got == expected)
    }

    @Test("sw.js_data yields visitorData at [0][2][0][0][13]")
    func visitor() {
        let inner: [Any] = Array(repeating: NSNull(), count: 13) + ["Cgt4eXo"]
        let json = try! JSONSerialization.data(withJSONObject: [[NSNull(), NSNull(), [[inner]]]])
        #expect(NativeExtract.parseVisitorData(Data(")]}'\n".utf8) + json) == "Cgt4eXo")
        #expect(NativeExtract.parseVisitorData(Data("[]".utf8)) == nil)
    }

    @Test("the repo client config validates and matches the compiled-in defaults")
    func clientConfig() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("config/youtube-clients.json")
        let clients = try #require(NativeExtract.parseClientConfig(Data(contentsOf: file)))
        #expect(clients == NativeExtract.defaultClients)
        // Unknown fields are ignored; a missing UA or an empty list is rejected.
        #expect(NativeExtract.parseClientConfig(Data(
            #"{"clients":[{"name":"X","version":"1","ua":"u","future":true}],"v":2}"#.utf8))?.count == 1)
        #expect(NativeExtract.parseClientConfig(Data(#"{"clients":[{"name":"X","version":"1"}]}"#.utf8)) == nil)
        #expect(NativeExtract.parseClientConfig(Data(#"{"clients":[]}"#.utf8)) == nil)
    }

    static let noAV1 = DeviceCodecs(av1: false, hevc: true)
    static let av1 = DeviceCodecs(av1: true, hevc: true)

    /// Fixture heights: AV1 and VP9 to 2160p60, H.264 to 1080p60; ≥ 720p is
    /// 60 fps only. Height outranks every preference, so "music" on an AV1
    /// device still takes the 4K AV1, and H.264 wins only at equal height.
    @Test("format policy by device, processing and quality", arguments: [
        (noAV1, Processing.none, DownloadQuality.best, ["299", "140"]),
        (av1, .none, .best, ["401", "140"]),
        (noAV1, .music, .best, ["299", "140"]),
        (av1, .music, .best, ["401", "140"]),
        (av1, .music, .p1080, ["299", "140"]),
        (av1, .visual, .best, ["399", "140"]),
        // 60 fps-only at 1080p: fps is a preference, not a filter.
        (noAV1, .visual, .best, ["299", "140"]),
        (av1, .none, .p720, ["398", "140"]),
        (noAV1, .none, .p720, ["298", "140"]),
        (av1, .music, .p720, ["298", "140"]),
        // Fast mode reaches select as p720.
        (av1, .visual, DownloadQuality.best.resolved(fast: true), ["398", "140"]),
        (noAV1, .visual, .p480, ["135", "140"]),
        (av1, .none, .audio, ["140"]),
        (noAV1, .visual, .audio, ["140"]),
    ] as [(DeviceCodecs, Processing, DownloadQuality, [String])])
    func policy(_ hw: DeviceCodecs, _ processing: Processing, _ quality: DownloadQuality,
                _ expected: [String]) throws {
        let formats = try Self.media().formats
        #expect(quality.select(formats, processing: processing, hw: hw).map(\.id) == expected)
    }

    @Test("visual prefers 30 fps and SDR at equal height; WebM and Opus never win")
    func visualPreferences() {
        func v(_ id: String, _ codec: String, fps: Int, hdr: Bool = false, ext: String = "mp4") -> MediaFormat {
            MediaFormat(id: id, url: URL(string: "https://e.invalid/\(id)")!, ext: ext, height: 1080,
                        vcodec: codec, acodec: "none", filesize: nil, tbr: 1000, httpHeaders: [:],
                        fps: fps, hdr: hdr)
        }
        let opus = MediaFormat(id: "opus", url: URL(string: "https://e.invalid/o")!, ext: "webm",
                               height: nil, vcodec: "none", acodec: "opus", filesize: nil,
                               tbr: 999, httpHeaders: [:])
        let aac = MediaFormat(id: "aac", url: URL(string: "https://e.invalid/a")!, ext: "m4a",
                              height: nil, vcodec: "none", acodec: "mp4a.40.2", filesize: nil,
                              tbr: 128, httpHeaders: [:])
        let formats = [v("av1-60", "av01.0.09M.08", fps: 60), v("avc-30", "avc1.640028", fps: 30),
                       v("av1-30-hdr", "av01.0.09M.10", fps: 30, hdr: true),
                       v("vp9-30", "vp09.00.40.08", fps: 30, ext: "webm"), opus, aac]
        #expect(DownloadQuality.best.select(formats, processing: .visual, hw: Self.av1).map(\.id)
                == ["avc-30", "aac"])
        #expect(DownloadQuality.best.select(formats, processing: .none, hw: Self.av1).map(\.id)
                == ["av1-60", "aac"])
        #expect(DownloadQuality.best.select(formats, processing: .music, hw: Self.av1).map(\.id)
                == ["avc-30", "aac"])
        #expect(DownloadQuality.audio.select(formats, processing: .none, hw: Self.av1).map(\.id)
                == ["aac"])
    }

    /// The test that rots first. Run by hand: remove `.disabled`.
    @Test("live: VISIONOS extracts a ≥ 720p format with a URL", .disabled("live network"))
    func live() async throws {
        let media = try await NativeExtract.extract("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        #expect(media.client == "VISIONOS")
        #expect(media.formats.contains { $0.hasVideo && ($0.height ?? 0) >= 720 })
        let chosen = DownloadQuality.best.select(media.formats, processing: .none, hw: Self.noAV1)
        #expect(chosen.map(\.id) == ["299", "140"])
    }
}
