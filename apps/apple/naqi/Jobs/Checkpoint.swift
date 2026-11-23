import CryptoKit
import Foundation
import os

/// One slice of a long source, rendered and checkpointed independently.
struct RenderSegment: Codable, Sendable, Equatable {
    var index: Int
    var startMs: Int64
    var endMs: Int64
    var durationMs: Int64 { endMs - startMs }
}

/// Per-segment resume.
///
/// **The checkpoint unit is one COMPLETED segment**, and only the *render* is
/// ever segmented (`AnalyzePass`'s doc block has the reason). Mid-segment state
/// — gate firings so far, the live face-track map — is deliberately never
/// persisted, so an interruption costs the segment in flight and nothing more.
///
/// **Every file is written to `<name>.tmp` and renamed.** A file existing under
/// its final name *means* it is complete: no manifest to keep in sync, and no
/// way for a checkpoint to reference a half-written segment.
enum Checkpoint {

    /// One fixed length for every device. The per-export fixed cost is small
    /// next to what a lost segment costs: analyze runs near 0.45x realtime, so
    /// one lost 5-minute segment is ~2.3 min of work.
    static let segmentMs: Int64 = 5 * 60 * 1000
    /// Below this, the whole timeline runs in one pass — the unsegmented route
    /// stays byte-for-byte unchanged for ordinary clips.
    static let longSourceThresholdMs: Int64 = 30 * 60 * 1000
    /// No segment may be shorter than this.
    ///
    /// **Measured failure**, not a precaution. A source whose duration lands a
    /// few milliseconds past a segment multiple — 35:00.001 — produced a
    /// trailing segment of `2100000...2100001`. No cut is duplicated there, so
    /// `distinct` below never sees it, and 1 ms holds no frame at any frame
    /// rate: `RenderPass` refuses to write an empty segment and the whole job
    /// dies with "decoded no frames", leaving a work directory that fails the
    /// same way on every resume. Android has the identical gap
    /// (`work/Checkpoint.kt:70-79`); it is fatal here only because this renderer
    /// checks. One second is one frame at the slowest rate anything calling
    /// itself video runs at, so a surviving segment always holds at least one.
    static let minSegmentMs: Int64 = 1_000
    /// Work directories untouched for this long are swept. Age-based on
    /// purpose: "delete every temp at startup" is the obvious reading and the
    /// one change that could silently destroy hours of work.
    static let staleInterval: TimeInterval = 7 * 24 * 60 * 60

    /// Empty means "run the whole timeline in one pass".
    ///
    /// - Parameter cutAt: snaps a proposed cut to a real sync sample. Returning
    ///   the input unchanged disables snapping.
    static func plan(durationMs: Int64,
                     forcedSegmentMs: Int64 = 0,
                     cutAt: (Int64) -> Int64 = { $0 }) -> [RenderSegment] {
        let seg = forcedSegmentMs > 0 ? forcedSegmentMs : segmentMs
        guard durationMs > 0 else { return [] }
        if forcedSegmentMs <= 0 && durationMs < longSourceThresholdMs { return [] }
        guard durationMs > seg else { return [] }

        let count = (durationMs + seg - 1) / seg
        var cuts: [Int64] = [0]
        for i in 1..<count { cuts.append(min(max(cutAt(Int64(i) * seg), 0), durationMs)) }
        cuts.append(durationMs)
        // `distinct` is load-bearing: it collapses two cuts that a sparse-keyframe
        // source snapped onto the same sample, so such a source gets fewer,
        // longer segments rather than an empty one — which would make the
        // clipper throw on an inverted range.
        cuts = Array(Set(cuts)).sorted()

        // The same collapse, widened from "identical" to "too close to hold a
        // frame" — see `minSegmentMs`. The film's own two ends are never
        // dropped, so a final cut sitting too close to the end takes the
        // *interior* cut with it and leaves one longer last segment.
        var kept = [cuts[0]]
        for cut in cuts.dropFirst().dropLast() where cut - kept[kept.count - 1] >= minSegmentMs {
            kept.append(cut)
        }
        let end = cuts[cuts.count - 1]
        if kept.count > 1, end - kept[kept.count - 1] < minSegmentMs { kept.removeLast() }
        kept.append(end)

        return zip(kept, kept.dropFirst()).enumerated().map {
            RenderSegment(index: $0.offset, startMs: $0.element.0, endMs: $0.element.1)
        }
    }

    /// Stable identity for a job's work directory. A real digest, not a hash
    /// value: a collision here would resume the wrong job's segments into a
    /// user's video. Parts are length-delimited so ("a","bc") and ("ab","c")
    /// cannot hash alike.
    static func key(_ parts: [String]) -> String {
        var d = SHA256()
        for p in parts { d.update(data: Data("\(p.count):\(p)|".utf8)) }
        return d.finalize().prefix(8).map { String(format: "%02x", $0) }.joined()
    }

    /// Bump whenever the *meaning* of a `seg-NNN.mp4` or of `analysis.json`
    /// changes. Android's recorded history is `plan2 → plan3`
    /// when the gender vote was dropped (an old file held only tracks that
    /// voted FEMALE, a new one holds every face — mixing them would leave half
    /// a film censored under the old semantics) and `plan3 → plan4` when the
    /// music guard changed which chunks a resumed audio checkpoint claimed.
    /// Bumping orphans stale directories and the 7-day sweep collects them.
    static let planGeneration = "apple-plan3-person-crops"

    /// - Parameter forcedSegmentMs: the debug segment-length override (Android's
    ///   `segment_ms` Data key). It changes how many `seg-NNN.mp4` there are and
    ///   which source window each one holds, so it changes what the directory
    ///   *means* — resuming a 5-minute plan's segments into a 5-second plan would
    ///   splice the wrong windows together silently. Appended only when set, so
    ///   every shipping key stays byte-identical to the ones already on disk.
    static func key(source: URL, ops: FilterOps, forcedSegmentMs: Int64 = 0,
                    quality: String? = nil, sourceIdentity: String? = nil) -> String {
        var parts = [
            source.absoluteString,
            String(ops.removeMusic),
            ops.resolvedWho.rawValue,
            String(ops.censor),
            ops.censorMode.rawValue,
            ops.censorTarget.rawValue,
            String(ops.strictness),
            String(ops.blurAmount),
            String(ops.grayscale),
            ops.keepStems.rawValue,
            planGeneration,
        ]
        // Keep the old default key byte-identical so an in-flight job made by
        // 1.0.2 can resume. Only non-default new choices extend the identity.
        if !ops.censorNsfw { parts.append("nsfw-off") }
        if ops.solidColor.isSolid { parts.append("solid-\(ops.solidColor.rawValue)") }
        if ops.processingMode != .current { parts.append("mode-fast720-v1") }
        // The last segment carries the card, so its absence is a different file.
        if !ops.endCard { parts.append("endcard-off") }
        if let quality { parts.append("quality-\(quality)") }
        if let sourceIdentity { parts.append("source-\(sourceIdentity)") }
        if source.isFileURL, MediaTypes.isWebM(source) {
            let values = try? source.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            parts.append("webm-v1|\(values?.fileSize ?? 0)|\(values?.contentModificationDate?.timeIntervalSince1970 ?? 0)")
        }
        if forcedSegmentMs > 0 { parts.append("seg\(forcedSegmentMs)") }
        return key(parts)
    }

    // MARK: Files

    static func segmentURL(_ dir: URL, segment: Int) -> URL {
        dir.appendingPathComponent(String(format: "seg-%03d.mp4", segment))
    }
    static var audioTrackName: String { "audio.m4a" }

    /// The joined segments — itself a checkpoint, which is why the segments are
    /// deleted the moment it lands. Keeping both would put three full-size temps
    /// on disk at once where `Preflight.tempCopies` charges for two.
    static var concatName: String { "concat.mp4" }
    /// The partial-write name for the above. The marker goes in the *stem* and
    /// not the extension because `Remux` re-opens its own output as an
    /// `AVURLAsset` to verify the duration, and AVFoundation is much happier
    /// doing that for a path that still ends in `.mp4`.
    static var concatPartName: String { "concat.part.mp4" }

    /// Write-then-rename. `rename` will not overwrite on every filesystem, so a
    /// rewrite removes the target first.
    static func writeAtomically(_ data: Data, to url: URL) throws {
        let tmp = url.appendingPathExtension("tmp")
        try? FileManager.default.removeItem(at: tmp)
        try data.write(to: tmp, options: .atomic)
        try? FileManager.default.removeItem(at: url)
        try FileManager.default.moveItem(at: tmp, to: url)
    }

    /// Segments already on disk, so resume can skip them.
    static func completedSegments(dir: URL, of plan: [RenderSegment]) -> Set<Int> {
        Set(plan.map(\.index).filter { FileManager.default.fileExists(atPath: segmentURL(dir, segment: $0).path) })
    }

    /// The same question without a plan, for the resume guard that runs before
    /// one exists.
    static func hasRenderedSegments(dir: URL) -> Bool {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: dir.path)) ?? []
        return names.contains { $0.hasPrefix("seg-") && $0.hasSuffix(".mp4") }
    }

    // MARK: The unsegmented route's analysis checkpoint

    /// The whole finished EDL, intervals and all.
    ///
    /// There is no per-segment analysis file to compose this from, and there
    /// must not be: hysteresis, the region-overflow promotion and the
    /// whole-frame floor all span seams, so the EDL is only meaningful whole.
    /// Analyze therefore resumes at stage granularity, on this one file.
    static var analysisName: String { "analysis.json" }

    static func writeEdl(_ edl: Edl, dir: URL) throws {
        try writeAtomically(try edl.toJSONData(), to: dir.appendingPathComponent(analysisName))
    }

    /// nil means "not analyzed yet, or its write never completed".
    static func readEdl(dir: URL) -> Edl? {
        guard let d = try? Data(contentsOf: dir.appendingPathComponent(analysisName)) else { return nil }
        return try? Edl.fromJSONData(d)
    }

    /// Runs at the head of every job. Descends only into the work root, and
    /// only into entries untouched for 7 days. A directory's own mtime does not
    /// track writes to files inside it on every filesystem, so a job is aged by
    /// its newest *file*.
    static func sweepStale(now: Date = Date()) {
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(at: WorkDir.root,
                                                        includingPropertiesForKeys: [.isDirectoryKey])
        else { return }
        for dir in entries {
            guard (try? dir.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true else { continue }
            // Seed from the directory's own mtime, not `.distantPast`. An empty
            // work dir has no file to read a date off, and `.distantPast` makes
            // it infinitely stale — so a job whose directory exists but is not
            // yet written to would have its scratch deleted out from under it.
            // Harmless only while `JobQueue` is strictly serial; this removes
            // the dependency on that rather than documenting it.
            var newest = (try? dir.resourceValues(forKeys: [.contentModificationDateKey]))?
                .contentModificationDate ?? Date.distantPast
            let walker = fm.enumerator(at: dir, includingPropertiesForKeys: [.contentModificationDateKey])
            while let f = walker?.nextObject() as? URL {
                if let m = try? f.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate {
                    newest = max(newest, m)
                }
            }
            if now.timeIntervalSince(newest) > staleInterval {
                Log.job.notice("sweeping stale work dir \(dir.lastPathComponent, privacy: .public)")
                try? fm.removeItem(at: dir)
            }
        }
    }
}
