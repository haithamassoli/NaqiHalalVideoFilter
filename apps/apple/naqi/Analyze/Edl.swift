import CoreGraphics
import Foundation

/// A rect in upright-normalised `[0,1]` space. Render geometry is clamped;
/// faces receive 25 % padding and bodies 12 %. Classifier crops use raw boxes.
struct NRect: Codable, Sendable, Equatable {
    var left: Float, top: Float, right: Float, bottom: Float

    var width: Float { right - left }
    var height: Float { bottom - top }
    var isEmpty: Bool { width <= 0 || height <= 0 }

    /// Denormalise into a pixel rect in the given upright frame size.
    func rect(in size: CGSize) -> CGRect {
        CGRect(x: CGFloat(left) * size.width,
               y: CGFloat(top) * size.height,
               width: CGFloat(width) * size.width,
               height: CGFloat(height) * size.height)
    }

    static func lerp(_ a: NRect, _ b: NRect, _ t: Float) -> NRect {
        NRect(left: a.left + (b.left - a.left) * t,
              top: a.top + (b.top - a.top) * t,
              right: a.right + (b.right - a.right) * t,
              bottom: a.bottom + (b.bottom - a.bottom) * t)
    }
}

/// A person's segmentation over their unpadded body box: `width`×`height`
/// cells, row-major from the top, one bit per cell (LSB first).
/// ponytail: 32 cells on the long side keeps an hour of every-frame tracks in
/// tens of MB; raise `PersonDetector.maskSide` if edges look too coarse.
struct BodyMask: Codable, Sendable, Equatable {
    var rect: NRect
    var width: Int
    var height: Int
    var bits: Data

    subscript(x: Int, y: Int) -> Bool {
        guard x >= 0, y >= 0, x < width, y < height else { return false }
        let i = y * width + x
        return bits[bits.startIndex + i / 8] & (1 << UInt8(i % 8)) != 0
    }

    var isValid: Bool {
        (1...64).contains(width) && (1...64).contains(height) && bits.count == (width * height + 7) / 8
            && [rect.left, rect.top, rect.right, rect.bottom].allSatisfy(\.isFinite) && !rect.isEmpty
    }
}

/// One face's on-screen span plus its sampled boxes. Between keyframes the box
/// is linearly interpolated, which is what turns a 10 fps detection pass into a
/// full-frame-rate censor (Android `analysis/FaceTracker.kt` §4.5).
struct FaceTrackEdl: Codable, Sendable, Equatable {
    /// First sample − 50 ms, clamped at 0.
    var startMs: Int64
    /// Last sample + 50 ms.
    var endMs: Int64
    /// Ascending by time. `(timeMs, rect)`.
    var keyframes: [Keyframe]

    struct Keyframe: Codable, Sendable, Equatable {
        var timeMs: Int64
        var rect: NRect
        /// Precise body mode only, and only on observed (not held) samples.
        var mask: BodyMask? = nil
    }

    /// Interpolated box at `t`, or nil outside the span / with no keyframes.
    /// Before the first and after the last keyframe the nearest box is held,
    /// so the pad around the span never renders an empty region.
    func rect(at t: Int64) -> NRect? {
        guard t >= startMs, t <= endMs, let first = keyframes.first, let last = keyframes.last
        else { return nil }
        if t <= first.timeMs { return first.rect }
        if t >= last.timeMs { return last.rect }
        let i = upperKeyframeIndex(at: t)
        let prev = keyframes[i - 1], next = keyframes[i]
        let span = Float(next.timeMs - prev.timeMs)
        guard span > 0 else { return prev.rect }
        return NRect.lerp(prev.rect, next.rect, Float(t - prev.timeMs) / span)
    }

    /// Body tracks can contain thousands of frames, so render lookup is logarithmic.
    func upperKeyframeIndex(at t: Int64) -> Int {
        var lo = 0, hi = keyframes.count - 1
        while lo < hi {
            let mid = (lo + hi) / 2
            if keyframes[mid].timeMs < t { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }
}

/// Evidence is kept even for people spared by the current policy. IDs belong
/// only to this shot; classification is an appearance estimate, with ties unknown.
struct PersonTrackEdl: Codable, Sendable, Equatable {
    var id: Int
    var shot: Int
    var faceOnly: Bool
    var geometry: FaceTrackEdl
    var faces: [FaceTrackEdl.Keyframe]
    var femaleVotes: Int
    var maleVotes: Int

    enum Classification: String, Codable, Sendable { case female, male, unknown }
    var classification: Classification {
        // One occluded face crop produced a contradictory gender in the video
        // review. Require repeated evidence before a person can be spared.
        guard max(femaleVotes, maleVotes) >= 2 else { return .unknown }
        return femaleVotes > maleVotes ? .female : maleVotes > femaleVotes ? .male : .unknown
    }

    func shouldCensor(_ who: FilterOps.Who) -> Bool {
        if classification == .unknown {
            return GenderVote.shouldCensor(female: 0, male: 0, who: who)
        }
        return GenderVote.shouldCensor(female: femaleVotes, male: maleVotes, who: who)
    }

    func rect(at t: Int64) -> NRect? {
        guard let r = geometry.rect(at: t) else { return nil }
        guard !faceOnly, let first = geometry.keyframes.first, let last = geometry.keyframes.last,
              t > first.timeMs, t < last.timeMs else { return r }
        let i = geometry.upperKeyframeIndex(at: t)
        // Keep an exact observation exact; across a detector gap cover both edges.
        if geometry.keyframes[i].timeMs == t { return geometry.keyframes[i].rect }
        let a = geometry.keyframes[i - 1].rect, b = geometry.keyframes[i].rect
        return NRect(left: min(a.left, b.left), top: min(a.top, b.top),
                     right: max(a.right, b.right), bottom: max(a.bottom, b.bottom))
    }

    /// The masks to draw at `t`, or nil when the box must be drawn instead:
    /// a held or unmasked sample never narrows coverage below its rectangle.
    /// Across a detector gap both neighbours are drawn, mirroring `rect(at:)`.
    func masks(at t: Int64) -> [BodyMask]? {
        let ks = geometry.keyframes
        guard !faceOnly, t >= geometry.startMs, t <= geometry.endMs,
              let first = ks.first, let last = ks.last else { return nil }
        if t <= first.timeMs { return first.mask.map { [$0] } }
        if t >= last.timeMs { return last.mask.map { [$0] } }
        let i = geometry.upperKeyframeIndex(at: t)
        if ks[i].timeMs == t { return ks[i].mask.map { [$0] } }
        guard let a = ks[i - 1].mask, let b = ks[i].mask else { return nil }
        return [a, b]
    }
}

/// The analyze pass's whole output: whole-frame censor intervals plus per-face
/// tracks. Pass 2 consumes only this.
struct Edl: Codable, Sendable, Equatable {
    /// Inclusive `[first, last]` ranges in absolute source milliseconds.
    /// **Not required to be sorted or disjoint** — the lookup is a pure OR, and
    /// in region mode this is the plain concatenation of gate intervals and
    /// track-overflow spans (Android `work/FilterWorker.kt:1167`).
    var censorIntervalsMs: [ClosedRange<Int64>] = []
    /// Sorted by `startMs` at build time.
    var faceTracks: [FaceTrackEdl] = []
    var personTracks: [PersonTrackEdl] = []
    var personWho: FilterOps.Who = .none
    var personWholeFrame: Bool = false
    /// Precise body mode: render person tracks through their masks.
    var personMasks: Bool = false

    var isEmpty: Bool {
        censorIntervalsMs.isEmpty && faceTracks.isEmpty
            && !personTracks.contains { $0.shouldCensor(personWho) }
    }

    /// Inclusive at both ends.
    func fullFrame(at t: Int64) -> Bool {
        if censorIntervalsMs.contains(where: { $0.contains(t) }) { return true }
        var count = 0
        for tr in personTracks where tr.shouldCensor(personWho)
            && t >= tr.geometry.startMs && t <= tr.geometry.endMs {
            // In person mode an unmatched face supplies no safe body bounds.
            if tr.faceOnly { return true }
            count += 1
        }
        return count > Self.maxRegionsPerFrame || (personWholeFrame && count > 0)
    }

    /// **The precedence rule**: a whole-frame interval blanks the frame and
    /// suppresses every face region at that timestamp. Pass 2 depends on this,
    /// so it lives here rather than at the call site.
    func regions(at t: Int64) -> [NRect] {
        coverage(at: t, masks: false).regions
    }

    /// What pass 2 draws: `regions(at:)`, except that in precise body mode a
    /// person track with masks at `t` contributes those instead of its box.
    func coverage(at t: Int64) -> (regions: [NRect], masks: [BodyMask]) {
        coverage(at: t, masks: personMasks)
    }

    private func coverage(at t: Int64, masks useMasks: Bool) -> (regions: [NRect], masks: [BodyMask]) {
        guard !fullFrame(at: t) else { return ([], []) }
        var out: [NRect] = []
        var masks: [BodyMask] = []
        out.reserveCapacity(2)
        for tr in faceTracks where t >= tr.startMs && t <= tr.endMs {
            if let r = tr.rect(at: t) { out.append(r) }
        }
        for tr in personTracks where tr.shouldCensor(personWho) {
            if useMasks, let m = tr.masks(at: t) { masks += m }
            else if let r = tr.rect(at: t) { out.append(r) }
        }
        return (out, masks)
    }

    /// The renderer applies at most this many regions per frame; a frame with
    /// more is promoted to whole-frame at EDL build. Must match the shader's
    /// region array size (Android `render/CensorEffect.kt:30`).
    static let maxRegionsPerFrame = 8
}

// MARK: - JSON, byte-compatible with Android

/// Android writes `censorIntervalsMs` as `[[first,last],…]` and each keyframe
/// as a flat `[timeMs, l, t, r, b]`, with the rect components widened from
/// Float32 to Double. Matching that exactly keeps EDL diffs against Android
/// runs meaningful, which is how the parity suite is scored.
extension Edl {
    func toJSONData() throws -> Data {
        var obj: [String: Any] = [
            "censorIntervalsMs": censorIntervalsMs.map { [$0.lowerBound, $0.upperBound] },
            "faceTracks": faceTracks.map { tr -> [String: Any] in
                [
                    "startMs": tr.startMs,
                    "endMs": tr.endMs,
                    "keyframes": tr.keyframes.map { k -> [Any] in
                        [k.timeMs, Double(k.rect.left), Double(k.rect.top),
                         Double(k.rect.right), Double(k.rect.bottom)]
                    },
                ]
            },
        ]
        if !personTracks.isEmpty || personWho != .none {
            obj["schemaVersion"] = 2
            obj["personTracks"] = try JSONSerialization.jsonObject(with: JSONEncoder().encode(personTracks))
            obj["personWho"] = personWho.rawValue
            obj["personWholeFrame"] = personWholeFrame
            if personMasks { obj["personMasks"] = true }
        }
        return try JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys])
    }

    static func fromJSONData(_ d: Data) throws -> Edl {
        guard let root = try JSONSerialization.jsonObject(with: d) as? [String: Any] else {
            throw EdlError.malformed("root is not an object")
        }
        var edl = Edl()
        if let people = root["personTracks"] {
            guard (root["schemaVersion"] as? NSNumber)?.intValue == 2,
                  let policy = root["personWho"] as? String,
                  let who = FilterOps.Who(rawValue: policy) else {
                throw EdlError.malformed("unsupported person analysis or missing policy")
            }
            edl.personTracks = try JSONDecoder().decode([PersonTrackEdl].self,
                from: JSONSerialization.data(withJSONObject: people))
            edl.personWho = who
            edl.personWholeFrame = root["personWholeFrame"] as? Bool ?? false
            edl.personMasks = root["personMasks"] as? Bool ?? false
            for tr in edl.personTracks {
                let ks = tr.geometry.keyframes
                guard tr.geometry.startMs <= tr.geometry.endMs, !ks.isEmpty,
                      tr.femaleVotes >= 0, tr.maleVotes >= 0,
                      ks == ks.sorted(by: { $0.timeMs < $1.timeMs }),
                      ks.allSatisfy({ $0.mask?.isValid ?? true }),
                      ks.allSatisfy({ k in
                          let r = k.rect
                          return [r.left, r.top, r.right, r.bottom].allSatisfy(\.isFinite)
                              && !r.isEmpty && r.left >= 0 && r.top >= 0 && r.right <= 1 && r.bottom <= 1
                      }) else { throw EdlError.malformed("invalid person geometry or evidence") }
            }
        }
        for p in root["censorIntervalsMs"] as? [[NSNumber]] ?? [] where p.count == 2 {
            let lo = p[0].int64Value, hi = p[1].int64Value
            guard lo <= hi else { throw EdlError.malformed("interval \(lo)..\(hi) is inverted") }
            edl.censorIntervalsMs.append(lo...hi)
        }
        for t in root["faceTracks"] as? [[String: Any]] ?? [] {
            guard let s = (t["startMs"] as? NSNumber)?.int64Value,
                  let e = (t["endMs"] as? NSNumber)?.int64Value else {
                throw EdlError.malformed("face track missing startMs/endMs")
            }
            var kfs: [FaceTrackEdl.Keyframe] = []
            for k in t["keyframes"] as? [[NSNumber]] ?? [] where k.count == 5 {
                kfs.append(.init(timeMs: k[0].int64Value,
                                 rect: NRect(left: k[1].floatValue, top: k[2].floatValue,
                                             right: k[3].floatValue, bottom: k[4].floatValue)))
            }
            edl.faceTracks.append(FaceTrackEdl(startMs: s, endMs: e, keyframes: kfs))
        }
        return edl
    }
}

enum EdlError: Error, CustomStringConvertible {
    case malformed(String)
    var description: String { if case .malformed(let s) = self { "malformed EDL: \(s)" } else { "" } }
}

/// Tuning constants shared by the analyze and render passes. Values are the
/// Android ones (`docs/apple-port/spec-analyze.md` §0); anything re-tuned for
/// Vision is marked and recorded in `vision-tuning.md`.
enum AnalyzeConstants {
    /// Decode/emit rate for the sampler.
    static let sampleFPS = 10.0
    /// The gate consumes every 2nd emitted frame.
    static let gateStride = 2
    /// Detector input long side.
    static let detectMaxDim = 640
    /// Hysteresis around a gate firing.
    static let preRollMs: Int64 = 500
    static let postRollMs: Int64 = 1500
    /// Pad applied to a face track's span.
    static let spanPadMs: Int64 = 50
    /// Per-side pad on a face box: 1.5x per axis.
    static let keyframePad: CGFloat = 0.25
    /// A track with no sample for this long is closed out.
    static let evictAfterMs: Int64 = 2_000
    static let voteCap = 5
    /// Min upright face size (max side, px) to be worth a gender vote.
    static let minFacePx: CGFloat = 80
    static let genderConfidenceFloor: Float = 0.60
    /// Whole-frame mode: gaps shorter than this are bridged...
    static let bridgeMs: Int64 = 400
    /// ...and spans shorter than this are dropped, which is what kills
    /// sub-second full-screen flashes from detector false positives.
    static let minFullFrameMs: Int64 = 500

    /// Consecutive failed detections that mean the detector is broken rather
    /// than unlucky — 3 s of source at `sampleFPS`. See `DetectFailures`.
    ///
    /// Measured, not guessed, and deliberately generous. The tv1 clip failed
    /// in a **burst of four consecutive** frames: at 100 ms apart they are the
    /// same shot, same face, same size, so failures correlate and a burst is
    /// the expected shape rather than the surprise. The costs are asymmetric —
    /// too high wastes ~3 s before giving up on a detector that is genuinely
    /// broken, too low kills a 90-minute job on a bad second of content. The
    /// scattered-rate check is the real safety net; this one only has to fail
    /// fast enough that a broken detector does not analyze a whole film first.
    static let detectFailStreakCap = 30
    /// ...and the share of the whole pass that may fail scattered, in percent.
    static let detectFailPercentCap = 2
}
