import CoreGraphics
import CoreML
import CoreVideo
import Foundation
import os
import Vision

/// Vision's face detector, resolved to a compute device that can actually run.
///
/// The default device cannot always build an inference context — the simulator's
/// GPU is one such place — and it fails per *request*, not per frame, so one
/// probe at pass start settles it. Pinning the CPU costs throughput on hardware,
/// which is why it is a fallback and not the default.
struct FaceDetector: Sendable {
    private let request: DetectFaceRectanglesRequest

    static func resolve() async -> FaceDetector {
        var r = DetectFaceRectanglesRequest()
        var probe: CVPixelBuffer?
        CVPixelBufferCreate(nil, 64, 64, kCVPixelFormatType_32BGRA, nil, &probe)
        if let probe, (try? await r.perform(on: probe, orientation: .up)) != nil {
            return FaceDetector(request: r)
        }
        let cpu = MLComputeDevice.allComputeDevices.first { if case .cpu = $0 { true } else { false } }
        r.setComputeDevice(cpu, for: .main)
        Log.analyze.notice("Vision default compute device cannot run here; pinned to CPU")
        return FaceDetector(request: r)
    }

    /// Upright pixel boxes in `frame.transform.uprightSize`. Free of tracker
    /// state, so the caller can start it before the gate runs and await it after
    /// — the overlap §2.5 requires.
    func detect(_ frame: SampledFrame) async throws -> [CGRect] {
        try await request.perform(on: frame.detect, orientation: frame.orientation)
            .map { frame.transform.uprightRectFromVision($0.boundingBox.cgRect) }
    }
}

/// How much per-frame detector failure the analyze pass tolerates before it
/// gives up on the whole job.
///
/// Vision fails per *request*, not per pass, and it does so on real content: a
/// 1920x1080 29.97 fps clip died 133 s in with
/// `kVTImageRotationNotSupportedErr` out of `VTPixelTransferSession` — Vision
/// rotating a 4:2:0 face chip internally, which the simulator cannot always do.
/// That took down a ten-minute job with `resumable=false`. One lost sample
/// costs 100 ms of face tracking, which `associateWindowMs` already spans, so
/// the frame is skipped instead.
///
/// **The tolerance is bounded in both directions on purpose.** Swallowing every
/// failure would let a wholly broken detector produce an empty EDL, and an
/// empty EDL publishes an *uncensored* video — the one outcome this app
/// promises cannot happen. A streak means the detector is broken now; a high
/// scattered rate means it was broken all along. Neither is survivable, and
/// neither is silent.
struct DetectFailures {
    private(set) var total = 0
    private var streak = 0

    /// Call on every successful detection — the streak only counts *runs*.
    mutating func succeeded() { streak = 0 }

    /// Records one failure. `true` means stop the pass and rethrow.
    mutating func failed() -> Bool {
        total += 1
        streak += 1
        return streak >= AnalyzeConstants.detectFailStreakCap
    }

    /// Checked once at the end: scattered failures never trip the streak cap,
    /// but faces still went unseen. Integer arithmetic, so no epsilon.
    func exceededRate(sampled: Int) -> Bool {
        total * 100 > sampled * AnalyzeConstants.detectFailPercentCap
    }
}

/// Track identity, spans and the gender verdict — everything the EDL rests on.
///
/// ML Kit handed Android a stable `trackingId`; Vision has none
/// (`spec-analyze.md` §9.6), so identity is rebuilt here — detect on every
/// sampled frame, then greedy association against the live tracks. Everything
/// downstream (spans, `voteCap`, the 2 s eviction) is unchanged, because it only
/// ever needed *an* id, not ML Kit's. What had to be re-tuned, and why, is in
/// `docs/apple-port/vision-tuning.md`.
final class FaceTracker {

    // MARK: - Association tuning (Vision-only; see vision-tuning.md)

    /// Overlap needed to call two boxes the same face on consecutive samples.
    private static let iouFloor: CGFloat = 0.3
    /// Fallback for the fast pans where 10 fps leaves two boxes of one face with
    /// literally no overlap: centres within this fraction of the larger box.
    private static let centreFactor: CGFloat = 0.6
    /// …and only when the boxes are within this size ratio of each other, so a
    /// close-up never absorbs a background face behind it.
    private static let sizeRatio: CGFloat = 0.6
    /// A track unseen for longer than this stops being an association
    /// candidate. Matching against a box three samples stale is noise, and
    /// gluing two different faces together would interpolate a censor rect
    /// across a hole where no face was.
    private static let associateWindowMs: Int64 = 300

    // MARK: - State

    private struct Sample {
        let ptsMs: Int64
        let rect: NRect
    }

    private final class Track {
        var samples: [Sample] = []
        /// Upright pixel box of the last sample, for association only.
        var lastBox: CGRect = .zero
        var votesTried = 0
        var classifiedPx: CGFloat = 0
        var maleVotes = 0
        var femaleVotes = 0
        /// A live track always holds at least the sample that created it, so
        /// this cannot be empty — no separate "last seen" field to keep in sync.
        var lastSeenMs: Int64 { samples[samples.count - 1].ptsMs }
    }

    private let who: FilterOps.Who
    private var tracks: [Track] = []
    private var emitted: [FaceTrackEdl] = []

    private(set) var trackCount = 0
    private(set) var faceCount = 0
    private(set) var sparedCount = 0
    private(set) var votes = 0

    init(who: FilterOps.Who) { self.who = who }

    /// Group `boxes` into tracks, run the gender votes that qualify, then sweep.
    ///
    /// `uprightSize` is the frame size the boxes were reported against — the
    /// detector buffer's upright size, not the source's — because every `NRect`
    /// and the `minFacePx` floor both live in that space.
    func onFaces(_ boxes: [CGRect], uprightSize: CGSize, ptsMs: Int64, voter: ((NRect) -> Int)? = nil) {
        faceCount += boxes.count
        let matched = associate(boxes, ptsMs: ptsMs)
        let w = uprightSize.width, h = uprightSize.height

        for (i, box) in boxes.enumerated() {
            let track: Track
            if let t = matched[i] {
                track = tracks[t]
            } else {
                trackCount += 1
                track = Track()
                tracks.append(track)
            }
            let rect = NRect(left: Float(box.minX / w), top: Float(box.minY / h),
                             right: Float(box.maxX / w), bottom: Float(box.maxY / h))
            track.samples.append(Sample(ptsMs: ptsMs, rect: rect))
            track.lastBox = box

            // Guards cheapest-first; nothing below allocates until the last one
            // passes (§5.1).
            //
            // `everyone` and `none` already know the verdict, so they skip the
            // crop, the tensor and the ORT call entirely — the whole gender
            // stage costs nothing on those settings.
            guard !who.skipsGenderVote else { continue }
            guard let voter else { continue }
            // A one-sample track is this port's untracked detection (§10.8):
            // censored like any other, but never classified. Without it a fast
            // pan that starts a fresh track every frame would cost one crop per
            // frame instead of five per face, voiding the per-track cost bound.
            guard track.samples.count >= 2 else { continue }
            guard track.votesTried < AnalyzeConstants.voteCap else { continue }
            // Raw box, unpadded, upright px. 80 is measured, not guessed: below
            // it the classifier is 76.9 % correct against 95.9 % just above.
            let px = max(box.width, box.height)
            guard px >= AnalyzeConstants.minFacePx else { continue }
            // Spend the five on the biggest crops in this track.
            guard px > track.classifiedPx else { continue }
            track.votesTried += 1
            track.classifiedPx = px
            votes += 1
            switch voter(rect) {
            case 1: track.maleVotes += 1
            case -1: track.femaleVotes += 1
            default: break     // abstain votes for nobody
            }
        }
        sweep(nowMs: ptsMs)
    }

    /// Emit whatever is still live, then return every span the pass produced,
    /// sorted by `startMs`. Emission order is track-*end* order; the sort exists
    /// only so `Edl.toJSONData()` stays diffable against the Android runs.
    func finish() -> [FaceTrackEdl] {
        for t in tracks { emit(t) }
        tracks.removeAll()
        Log.analyze.info("""
            tracks=\(self.trackCount) faces=\(self.faceCount) spans=\(self.emitted.count) \
            spared=\(self.sparedCount) votes=\(self.votes)
            """)
        return emitted.sorted { $0.startMs < $1.startMs }
    }

    // MARK: - Identity

    /// Greedy IoU first, then a centre-distance pass for the boxes it could not
    /// place. Returns, per detection, the index of the track it joins.
    private func associate(_ boxes: [CGRect], ptsMs: Int64) -> [Int?] {
        var out = [Int?](repeating: nil, count: boxes.count)
        let live = tracks.indices.filter { ptsMs - tracks[$0].lastSeenMs <= Self.associateWindowMs }
        guard !live.isEmpty, !boxes.isEmpty else { return out }
        var taken = Set<Int>()

        var overlaps: [(b: Int, t: Int, score: CGFloat)] = []
        for b in boxes.indices {
            for t in live {
                let s = Self.iou(boxes[b], tracks[t].lastBox)
                if s >= Self.iouFloor { overlaps.append((b, t, s)) }
            }
        }
        overlaps.sort { $0.score > $1.score }
        for o in overlaps where out[o.b] == nil && !taken.contains(o.t) {
            out[o.b] = o.t
            taken.insert(o.t)
        }

        var near: [(b: Int, t: Int, score: CGFloat)] = []
        for b in boxes.indices where out[b] == nil {
            for t in live where !taken.contains(t) {
                let a = boxes[b], c = tracks[t].lastBox
                let sa = max(a.width, a.height), sc = max(c.width, c.height)
                guard min(sa, sc) / max(sa, sc) >= Self.sizeRatio else { continue }
                let d = hypot(a.midX - c.midX, a.midY - c.midY)
                if d <= Self.centreFactor * max(sa, sc) { near.append((b, t, d)) }
            }
        }
        near.sort { $0.score < $1.score }
        for n in near where out[n.b] == nil && !taken.contains(n.t) {
            out[n.b] = n.t
            taken.insert(n.t)
        }
        return out
    }

    private static func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let i = a.intersection(b)
        guard !i.isNull, i.width > 0, i.height > 0 else { return 0 }
        let inter = i.width * i.height
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    // MARK: - Eviction and span construction

    /// Runs after every frame's faces are processed. Eviction is exactly what
    /// keeps the live count small: Android's never-evicting predecessor reached
    /// 3 362 live tracks and ~500 MB retained on a 155-minute film, and its
    /// `finish()` stalled for 2.7 minutes (§8.1).
    private func sweep(nowMs: Int64) {
        var kept: [Track] = []
        kept.reserveCapacity(tracks.count)
        for t in tracks {
            // Source time, not wall clock.
            if nowMs - t.lastSeenMs >= AnalyzeConstants.evictAfterMs {
                emit(t)
            } else {
                kept.append(t)
            }
        }
        tracks = kept
    }

    /// Close one track: build its span, or count it as spared. The verdict is
    /// read HERE — at eviction, never earlier — so a track is judged only once
    /// it is over and all its votes are in. Reading it early is what once
    /// silently downgraded a female verdict to uncensored (§10.11).
    private func emit(_ track: Track) {
        guard let first = track.samples.first, let last = track.samples.last else { return }
        guard GenderVote.shouldCensor(female: track.femaleVotes, male: track.maleVotes, who: who) else {
            sparedCount += 1
            return
        }
        emitted.append(FaceTrackEdl(
            // Half the 100 ms sample gap at 10 fps, so between-sample frames
            // stay covered at a span's edges. A one-sample track spans 100 ms.
            startMs: max(0, first.ptsMs - AnalyzeConstants.spanPadMs),
            endMs: last.ptsMs + AnalyzeConstants.spanPadMs,
            keyframes: track.samples.map { .init(timeMs: $0.ptsMs, rect: Self.pad($0.rect)) }))
    }

    /// 25 % of each axis's **own** extent, per side, so a non-square box stays
    /// non-square; the pad is computed from the unclamped rect and each edge is
    /// clamped afterwards, which leaves a face at the frame edge with an
    /// asymmetric rect rather than a redistributed one (§4.3).
    static func pad(_ r: NRect) -> NRect {
        let padded = CGRect(x: CGFloat(r.left), y: CGFloat(r.top),
                            width: CGFloat(r.width), height: CGFloat(r.height))
            .padded(by: AnalyzeConstants.keyframePad, clampedTo: CGSize(width: 1, height: 1))
        return NRect(left: Float(padded.minX), top: Float(padded.minY),
                     right: Float(padded.maxX), bottom: Float(padded.maxY))
    }
}
