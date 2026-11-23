import CoreGraphics
import CoreVideo
import Foundation

/// Geometric identity inside one shot. Ambiguous crossings start unknown tracks
/// instead of borrowing another person's votes. No cross-shot re-identification.
final class PersonTracker {
    private final class Track {
        let id: Int
        let shot: Int
        let faceOnly: Bool
        let shotStart: Int64
        var samples: [FaceTrackEdl.Keyframe] = []
        var faces: [FaceTrackEdl.Keyframe] = []
        var lastBox: CGRect = .zero
        var previousBox: CGRect = .zero
        var lastSeen: Int64 = 0
        var previousSeen: Int64 = 0
        var female = 0
        var male = 0
        var votes = 0
        var ambiguous = false
        var unresolvedFaces = false

        init(id: Int, shot: Int, shotStart: Int64, faceOnly: Bool) {
            self.id = id; self.shot = shot; self.shotStart = shotStart; self.faceOnly = faceOnly
        }

        func predicted(at t: Int64) -> CGRect {
            let dt = lastSeen - previousSeen
            guard dt > 0 else { return lastBox }
            let factor = min(CGFloat(t - lastSeen) / CGFloat(dt), 3)
            return lastBox.offsetBy(dx: (lastBox.midX - previousBox.midX) * factor,
                                    dy: (lastBox.midY - previousBox.midY) * factor)
        }
    }

    private var active: [Track] = []
    private var finished: [PersonTrackEdl] = []
    private var nextID = 0
    private var shot = 0
    private var shotStart: Int64 = 0
    private var lastPtsMs: Int64?
    private let spanPad: Int64
    private static let holdMs: Int64 = 300

    init(frameRate: Double) {
        spanPad = max(1, min(50, Int64((500 / max(1, frameRate)).rounded(.up))))
    }

    /// `masks`, when given, parallels `bodies` (precise body mode).
    func onFrame(bodies: [CGRect], masks: [BodyMask?] = [], faces: [CGRect], uprightSize: CGSize,
                 ptsMs: Int64, sceneCut: Bool = false, voter: ((NRect) -> Int)? = nil) {
        if sceneCut {
            for track in active { emit(track, before: ptsMs) }
            active.removeAll()
            shot += 1
            // The cut happened somewhere after the previous sample; at 10 fps
            // that is up to 100 ms of the new shot this frame must also cover.
            shotStart = lastPtsMs.map { $0 + 1 } ?? ptsMs
        }
        lastPtsMs = ptsMs
        let bodyTracks = associate(bodies, faceOnly: false, ptsMs: ptsMs)
        var faceForBody: [Int: [Int]] = [:]
        var unmatchedFaces: [CGRect] = []
        for f in faces.indices {
            let candidates = bodies.indices.filter { b in
                let overlap = faces[f].intersection(bodies[b])
                return !overlap.isNull && overlap.width * overlap.height >= faces[f].width * faces[f].height * 0.8
                    && bodies[b].contains(CGPoint(x: faces[f].midX, y: faces[f].midY))
            }
            if candidates.count == 1 { faceForBody[candidates[0], default: []].append(f) }
            else { unmatchedFaces.append(faces[f]) }
        }
        var seen = Set<Int>()
        for b in bodies.indices {
            var track = bodyTracks[b]
            let assigned = faceForBody[b] ?? []
            if !track.samples.isEmpty && ((assigned.count > 1 && !track.unresolvedFaces)
                || (assigned.count == 1 && track.unresolvedFaces)) {
                // Never let a later vote rewrite geometry belonging to several
                // people, or overwrite the verdict before the ambiguity began.
                track.ambiguous = true
                nextID += 1
                track = Track(id: nextID, shot: shot, shotStart: ptsMs, faceOnly: false)
                active.append(track)
            }
            if assigned.count > 1 { track.unresolvedFaces = true }
            observe(track, box: bodies[b], mask: masks.indices.contains(b) ? masks[b] : nil,
                    size: uprightSize, ptsMs: ptsMs)
            seen.insert(track.id)
            if assigned.count == 1 {
                addFace(faces[assigned[0]], to: track, size: uprightSize, ptsMs: ptsMs, voter: voter)
            } else if assigned.count > 1 {
                // A box containing several faces cannot own a gender verdict.
                unmatchedFaces.append(contentsOf: assigned.map { faces[$0] })
            }
        }
        let fallbackTracks = associate(unmatchedFaces, faceOnly: true, ptsMs: ptsMs)
        for f in unmatchedFaces.indices {
            let track = fallbackTracks[f]
            observe(track, box: unmatchedFaces[f], size: uprightSize, ptsMs: ptsMs)
            seen.insert(track.id)
            addFace(unmatchedFaces[f], to: track, size: uprightSize, ptsMs: ptsMs, voter: voter)
        }
        var kept: [Track] = []
        for track in active {
            if track.ambiguous {
                emit(track, before: ptsMs)
            } else if ptsMs - track.lastSeen > Self.holdMs {
                emit(track)
            } else {
                if !seen.contains(track.id) {
                    appendGeometry(track, box: track.lastBox.union(track.predicted(at: ptsMs)),
                                   size: uprightSize, ptsMs: ptsMs)
                }
                kept.append(track)
            }
        }
        active = kept
    }

    func finish() -> [PersonTrackEdl] {
        for track in active { emit(track) }
        active.removeAll()
        return finished.sorted { $0.geometry.startMs < $1.geometry.startMs }
    }

    private func associate(_ boxes: [CGRect], faceOnly: Bool, ptsMs: Int64) -> [Track] {
        let live = active.filter { $0.faceOnly == faceOnly && !$0.ambiguous && ptsMs - $0.lastSeen <= Self.holdMs }
        var choices: [(box: Int, track: Track, score: CGFloat)] = []
        for b in boxes.indices {
            for track in live {
                let score = Self.iou(boxes[b], track.predicted(at: ptsMs))
                if score >= 0.25 { choices.append((b, track, score)) }
            }
        }
        // ponytail: geometry only, so reset near-ties; add appearance ReID only
        // if annotated crossing tests demonstrate a worthwhile quality gain.
        for b in boxes.indices {
            let options = choices.filter { $0.box == b }.sorted { $0.score > $1.score }
            if options.count > 1 && options[0].score - options[1].score < 0.15 {
                for option in options { option.track.ambiguous = true }
            }
        }
        for track in live {
            let options = choices.filter { $0.track === track }.sorted { $0.score > $1.score }
            if options.count > 1 && options[0].score - options[1].score < 0.15 { track.ambiguous = true }
        }
        var matched: [Int: Track] = [:]
        var used = Set<Int>()
        for choice in choices.sorted(by: { $0.score > $1.score })
            where !choice.track.ambiguous && matched[choice.box] == nil && !used.contains(choice.track.id) {
            matched[choice.box] = choice.track
            used.insert(choice.track.id)
        }
        return boxes.indices.map { b in
            if let track = matched[b] { return track }
            nextID += 1
            let track = Track(id: nextID, shot: shot, shotStart: shotStart, faceOnly: faceOnly)
            active.append(track)
            return track
        }
    }

    private func observe(_ track: Track, box: CGRect, mask: BodyMask? = nil, size: CGSize, ptsMs: Int64) {
        track.previousBox = track.lastBox
        track.previousSeen = track.lastSeen
        if track.samples.isEmpty { track.previousBox = box; track.previousSeen = ptsMs }
        track.lastBox = box; track.lastSeen = ptsMs
        appendGeometry(track, box: box, mask: mask, size: size, ptsMs: ptsMs)
    }

    private func appendGeometry(_ track: Track, box: CGRect, mask: BodyMask? = nil, size: CGSize, ptsMs: Int64) {
        let padded = box.padded(by: track.faceOnly ? AnalyzeConstants.keyframePad : 0.12, clampedTo: size)
        guard !padded.isNull, padded.width > 0, padded.height > 0 else { return }
        let rect = Self.normalized(padded, size)
        track.samples.append(.init(timeMs: ptsMs, rect: rect, mask: mask))
    }

    private func addFace(_ box: CGRect, to track: Track, size: CGSize, ptsMs: Int64,
                         voter: ((NRect) -> Int)?) {
        let rect = Self.normalized(box, size)
        track.faces.append(.init(timeMs: ptsMs, rect: rect))
        guard let voter, track.faces.count >= 2,
              track.votes < AnalyzeConstants.voteCap,
              max(box.width, box.height) >= AnalyzeConstants.minFacePx else { return }
        track.votes += 1
        switch voter(rect) {
        case -1: track.female += 1
        case 1: track.male += 1
        default: break
        }
    }

    private func emit(_ track: Track, before cut: Int64? = nil) {
        guard let first = track.samples.first, let last = track.samples.last else { return }
        let end = min(last.timeMs + spanPad, cut.map { $0 - 1 } ?? .max)
        guard end >= first.timeMs else { return }
        finished.append(PersonTrackEdl(id: track.id, shot: track.shot, faceOnly: track.faceOnly,
            // Detection can settle a few frames after a cut, especially for
            // small screen images. Cover that lead-in within this shot only.
            geometry: FaceTrackEdl(startMs: max(track.shotStart, first.timeMs - Self.holdMs), endMs: end,
                                   keyframes: track.samples),
            faces: track.faces, femaleVotes: track.female, maleVotes: track.male))
    }

    private static func normalized(_ box: CGRect, _ size: CGSize) -> NRect {
        NRect(left: Float(box.minX / size.width), top: Float(box.minY / size.height),
              right: Float(box.maxX / size.width), bottom: Float(box.maxY / size.height))
    }

    static func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let rect = a.intersection(b)
        guard !rect.isNull else { return 0 }
        let area = rect.width * rect.height
        return area / max(1, a.width * a.height + b.width * b.height - area)
    }
}

/// A small luma grid catches abrupt shot changes without another ML model.
/// ponytail: cuts with very similar pictures can evade this heuristic; test
/// annotated cuts before adding an appearance model or cross-shot identity.
struct ShotCuts {
    private var previous: [Float] = []

    mutating func add(_ frame: SampledFrame) -> Bool {
        let buffer = frame.detect
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let base = CVPixelBufferGetBaseAddressOfPlane(buffer, 0)!.assumingMemoryBound(to: UInt8.self)
        let row = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0)
        let w = CVPixelBufferGetWidthOfPlane(buffer, 0), h = CVPixelBufferGetHeightOfPlane(buffer, 0)
        let grid = (0..<256).map { i in
            Float(base[((2 * (i / 16) + 1) * h / 32) * row + (2 * (i % 16) + 1) * w / 32])
        }
        defer { previous = grid }
        guard previous.count == grid.count else { return false }
        return zip(previous, grid).reduce(Float(0)) { $0 + abs($1.0 - $1.1) } / Float(grid.count) > 45
    }
}
