import AVFoundation
import Foundation
import os

/// Naqi's optional server for long sources (`server/naqi_modal.py` in the Android repo), standing in
/// for two jobs the phone already knows how to do:
///
/// - `removeMusic`: the audio track goes up, the same htdemucs runs on a GPU, and the kept stems come
///   back as the AAC .m4a `AudioPipeline.removeMusic` would have written.
/// - `analyze`: body mode's pass 1 comes back as Android's segment checkpoint, mapped here onto the
///   person tracks `RenderPass` already draws.
///
/// The server runs on Modal's free monthly credit only, so **every failure answers "do it here"** —
/// no network, credit spent (429), a server error — and only a cancel propagates.
enum Cloud {
    /// Shorter sources stay on the phone: the upload and a ~1 min GPU cold start would eat the win.
    static let minSeconds: Double = 10 * 60
    /// Settings' "Use the server for long videos"; on unless turned off.
    static let enabledKey = "naqi.server"

    /// 8 MB: under the 150 s request cap down to ~0.5 Mbit/s of uplink.
    private static let partBytes = 8 << 20
    /// One request ingests at only ~3 MB/s on Modal's side; a few in flight fill a phone's uplink.
    private static let parallelParts = 4
    private static let pollInterval: Duration = .seconds(3)

    /// `NaqiCloud.plist` (`url`, `key`) is gitignored, never committed: absent means a phone-only build.
    static let config: (url: String, key: String)? = {
        guard let file = Bundle.main.url(forResource: "NaqiCloud", withExtension: "plist"),
              let d = NSDictionary(contentsOf: file),
              let url = d["url"] as? String, let key = d["key"] as? String,
              !url.isEmpty, !key.isEmpty else { return nil }
        return (url, key)
    }()

    private static let session: URLSession = {
        let c = URLSessionConfiguration.ephemeral
        c.timeoutIntervalForRequest = 60
        return URLSession(configuration: c)
    }()

    private static func eligible(_ src: MediaSource) -> Bool {
        config != nil && (UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true)
            && src.duration.isNumeric && src.duration.seconds >= minSeconds
    }

    /// True when `out` holds the separated .m4a; false means separate on the phone.
    static func removeMusic(_ src: MediaSource, keepStems: FilterOps.KeepStems, to out: URL,
                            progress: @escaping @Sendable (Double) -> Void,
                            isCancelled: @escaping @Sendable () -> Bool) async throws -> Bool {
        guard eligible(src), src.audio != nil else { return false }
        let track = out.deletingLastPathComponent().appendingPathComponent("cloud-audio.m4a")
        defer { try? FileManager.default.removeItem(at: track) }
        let ok = try await attempt("music") { () async throws -> Void in
            // The audio track alone (~60 MB/h) when it passes through into .m4a; otherwise the whole
            // source, which the server's ffmpeg reads whatever it holds.
            let upload = await extractAudio(src.url, to: track) ? track : src.url
            let keep = keepStems == .vocals ? "vocals" : "vocals_other"
            let result = try await run("/v1/music?keep=\(keep)", src, upload: upload,
                                       progress: progress, isCancelled: isCancelled)
            try? FileManager.default.removeItem(at: out)
            try FileManager.default.moveItem(at: result, to: out)
            guard (try out.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) > 0 else {
                throw CloudError(description: "empty result")
            }
        } != nil
        if !ok { try? FileManager.default.removeItem(at: out) }
        return ok
    }

    /// Body mode's pass 1 for the whole source, or nil to run it on the phone. The caller writes the
    /// result to the job's `analysis.json`, so a resume never uploads the film twice.
    static func analyze(_ src: MediaSource, ops: FilterOps,
                        progress: @escaping @Sendable (Double) -> Void,
                        isCancelled: @escaping @Sendable () -> Bool) async throws -> Edl? {
        let who = ops.resolvedWho
        guard ops.censor, who != .none, ops.censorTarget != .face, src.video != nil, eligible(src) else {
            return nil
        }
        let precise = ops.censorTarget == .bodyPrecise
        let query = "/v1/analyze?who=\(who.rawValue)&precise=\(precise)&nsfw=\(ops.censorNsfw)&strictness=\(ops.strictness)"
        return try await attempt("analyze") {
            let result = try await run(query, src, upload: src.url, progress: progress, isCancelled: isCancelled)
            defer { try? FileManager.default.removeItem(at: result) }
            return try edl(from: Data(contentsOf: result), ops: ops,
                           durationMs: Int64(src.duration.seconds * 1000))
        }
    }

    /// `block`'s value, or nil on any failure but a cancel.
    private static func attempt<T>(_ job: String, _ block: () async throws -> T) async throws -> T? {
        let started = ContinuousClock.now
        do {
            let r = try await block()
            Log.job.info("cloud \(job, privacy: .public) ok in \(Int(msSince(started))) ms")
            return r
        } catch MediaError.cancelled {
            throw MediaError.cancelled
        } catch is CancellationError {
            throw MediaError.cancelled
        } catch {
            Log.job.notice("cloud \(job, privacy: .public) unavailable, doing it on the phone: \(String(describing: error), privacy: .public)")
            return nil
        }
    }

    /// One server job: ask, upload, poll, then hand back the 200 body as a temporary file.
    /// Progress bands: upload 0.02…0.20, server 0.20…0.90.
    private static func run(_ path: String, _ src: MediaSource, upload: URL,
                            progress: @escaping @Sendable (Double) -> Void,
                            isCancelled: @escaping @Sendable () -> Bool) async throws -> URL {
        // Asked before a single byte goes up: a spent credit must not cost the user an upload to find out.
        guard let job = try await json("/v1/upload?seconds=\(Int(src.duration.seconds))", method: "POST")["job"] as? String
        else { throw CloudError(description: "no job") }
        progress(0.02)

        // In parts: a Modal web request may not run past 150 s, and a whole film over a phone's uplink would.
        let size = try upload.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
        let parts = max(1, (size + partBytes - 1) / partBytes)
        let sent = OSAllocatedUnfairLock(initialState: 0)
        try await withThrowingTaskGroup(of: Void.self) { group in
            for part in 0..<parts {
                if part >= parallelParts { try await group.next() }
                group.addTask {
                    let chunk = try readPart(upload, part)
                    try await putPart(job, part, chunk, isCancelled: isCancelled)
                    let n = sent.withLock { $0 += chunk.count; return $0 }
                    progress(0.02 + 0.18 * Double(n) / Double(max(size, 1)))
                }
            }
            try await group.waitForAll()
        }

        guard let id = try await json("\(path)&job=\(job)", method: "POST")["id"] as? String
        else { throw CloudError(description: "no job id") }
        while true {
            if isCancelled() { throw MediaError.cancelled }
            let (file, response) = try await session.download(for: request("/v1/jobs/\(id)"))
            switch (response as? HTTPURLResponse)?.statusCode {
            case 200:
                progress(0.98)
                return file
            case 202:
                let body = try? JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any]
                try? FileManager.default.removeItem(at: file)
                progress(0.20 + 0.70 * Double((body?["progress"] as? NSNumber)?.intValue ?? 0) / 100)
            case let code:
                try? FileManager.default.removeItem(at: file)
                throw CloudError(description: "result HTTP \(code ?? 0)")
            }
            try await Task.sleep(for: pollInterval)
        }
    }

    private static func readPart(_ file: URL, _ part: Int) throws -> Data {
        let h = try FileHandle(forReadingFrom: file)
        defer { try? h.close() }
        try h.seek(toOffset: UInt64(part * partBytes))
        return try h.read(upToCount: partBytes) ?? Data()
    }

    /// One upload part, retried: on a phone a dropped request is ordinary, and a part is cheap to resend.
    private static func putPart(_ job: String, _ part: Int, _ chunk: Data,
                                isCancelled: @Sendable () -> Bool) async throws {
        for attempt in 1...3 {
            if isCancelled() { throw MediaError.cancelled }
            // A session of its own per part, so each part gets its own connection: in one session every
            // part shared a single HTTP/3 connection, which uploaded at ~0.2 MB/s in the simulator,
            // against ~8 MB/s for four connections side by side.
            let fresh = URLSession(configuration: session.configuration)
            defer { fresh.finishTasksAndInvalidate() }
            do {
                let (_, response) = try await fresh.upload(
                    for: request("/v1/upload/\(job)/\(part)", method: "PUT"), from: chunk)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard code == 204 else { throw CloudError(description: "part HTTP \(code)") }
                return
            } catch {
                if attempt == 3 { throw error }
            }
        }
    }

    private static func json(_ path: String, method: String) async throws -> [String: Any] {
        let (data, response) = try await session.data(for: request(path, method: method))
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200, let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CloudError(description: "server refused: HTTP \(code)")
        }
        return obj
    }

    private static func request(_ path: String, method: String = "GET") throws -> URLRequest {
        guard let config, let url = URL(string: config.url + path) else {
            throw CloudError(description: "no server configured")
        }
        var r = URLRequest(url: url)
        r.httpMethod = method
        r.setValue(config.key, forHTTPHeaderField: "x-naqi-key")
        r.setValue(deviceId, forHTTPHeaderField: "x-naqi-device")
        return r
    }

    /// Random per install: the server's daily per-device limit counts against it, nothing else.
    private static var deviceId: String {
        let d = UserDefaults.standard
        if let id = d.string(forKey: "naqi.deviceId") { return id }
        let id = UUID().uuidString
        d.set(id, forKey: "naqi.deviceId")
        return id
    }

    /// The audio track alone, passed through into .m4a; false when it cannot be (a codec .m4a cannot
    /// carry), and the caller uploads the source instead.
    /// ponytail: placed at 0, so a track that starts late in its container (rare, tens of ms) comes
    /// back that much early; insert at the track's own start if a source ever shows it.
    private static func extractAudio(_ src: URL, to dst: URL) async -> Bool {
        try? FileManager.default.removeItem(at: dst)
        let asset = AVURLAsset(url: src)
        let comp = AVMutableComposition()
        guard let track = try? await asset.loadTracks(withMediaType: .audio).first,
              let range = try? await track.load(.timeRange),
              let into = comp.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid),
              (try? into.insertTimeRange(range, of: track, at: .zero)) != nil,
              let export = AVAssetExportSession(asset: comp, presetName: AVAssetExportPresetPassthrough)
        else { return false }
        do {
            try await export.export(to: dst, as: .m4a)
            return true
        } catch {
            return false
        }
    }

    /// The server's answer is Android's segment checkpoint: `firingsMs` from the NSFW gate, whole-frame
    /// `censorIntervalsMs` for faces outside every body, and `faceTracks` that are body tracks which
    /// already passed the gender vote — so they become person tracks censored under `.everyone`. The
    /// firings go through this app's own hysteresis, and the > 8 overflow is `Edl.fullFrame`'s, as on
    /// the phone.
    static func edl(from data: Data, ops: FilterOps, durationMs: Int64) throws -> Edl {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let firings = root["firingsMs"] as? [NSNumber],
              let body = root["edl"] as? [String: Any] else {
            throw CloudError(description: "malformed analysis")
        }
        let fallback = (body["censorIntervalsMs"] as? [[NSNumber]] ?? []).compactMap { p -> ClosedRange<Int64>? in
            p.count == 2 && p[0].int64Value <= p[1].int64Value ? p[0].int64Value...p[1].int64Value : nil
        }
        var people: [PersonTrackEdl] = []
        for (i, t) in (body["faceTracks"] as? [[String: Any]] ?? []).enumerated() {
            let masks = t["masks"] as? [Any] ?? []
            var ks: [FaceTrackEdl.Keyframe] = []
            for (j, k) in (t["keyframes"] as? [[NSNumber]] ?? []).enumerated() where k.count == 5 {
                let r = NRect(left: unit(k[1]), top: unit(k[2]), right: unit(k[3]), bottom: unit(k[4]))
                guard !r.isEmpty else { continue }
                ks.append(.init(timeMs: k[0].int64Value, rect: r, mask: j < masks.count ? mask(masks[j]) : nil))
            }
            guard let s = (t["startMs"] as? NSNumber)?.int64Value, let e = (t["endMs"] as? NSNumber)?.int64Value,
                  s <= e, !ks.isEmpty else { continue }
            people.append(PersonTrackEdl(
                id: i, shot: 0, faceOnly: false,
                geometry: FaceTrackEdl(startMs: s, endMs: e, keyframes: ks.sorted { $0.timeMs < $1.timeMs }),
                faces: [], femaleVotes: 0, maleVotes: 0))
        }
        return Edl(censorIntervalsMs: NsfwGate.intervals(firings.map(\.int64Value), durationMs: durationMs) + fallback,
                   faceTracks: [], personTracks: people, personWho: .everyone,
                   personWholeFrame: ops.censorMode == .wholeFrame,
                   personMasks: ops.censorTarget == .bodyPrecise)
    }

    private static func unit(_ n: NSNumber) -> Float { min(max(n.floatValue, 0), 1) }

    /// `[l, t, r, b, cols, rows, base64]`: the unpadded box and its LSB-first, row-major cells — the
    /// same layout as `BodyMask`.
    private static func mask(_ any: Any) -> BodyMask? {
        guard let m = any as? [Any], m.count == 7,
              let l = m[0] as? NSNumber, let t = m[1] as? NSNumber, let r = m[2] as? NSNumber,
              let b = m[3] as? NSNumber, let w = m[4] as? NSNumber, let h = m[5] as? NSNumber,
              let s = m[6] as? String, let bits = Data(base64Encoded: s) else { return nil }
        let mask = BodyMask(rect: NRect(left: l.floatValue, top: t.floatValue, right: r.floatValue, bottom: b.floatValue),
                            width: w.intValue, height: h.intValue, bits: bits)
        return mask.isValid ? mask : nil
    }
}

struct CloudError: Error, CustomStringConvertible {
    let description: String
}
