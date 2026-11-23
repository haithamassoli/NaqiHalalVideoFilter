import Foundation

/// The 5-class whole-frame gate: strictness -> per-class threshold, the fire
/// predicate, and the hysteresis that turns firing timestamps into merged
/// censor intervals.
///
/// Pure value logic with no ORT in it, which is how the Android thresholds were
/// pinned and how they stay pinned here.
enum NsfwGate {
    /// `(thr at s=0, thr at s=100)` keyed by **class**, then resolved into
    /// `Models.Nsfw.Class` index order so `probs[c]` and `thr(c)` share one
    /// indexing. The PRD lists these in a different order; never index by that
    /// one (`spec-analyze.md` §2.3).
    private static let table: [Models.Nsfw.Class: (Float, Float)] = [
        .drawings: (0.50, 0.50),
        .hentai: (1.00, 0.50),
        .neutral: (0.30, 1.00),
        .porn: (0.75, 0.10),
        .sexy: (0.90, 0.10),
    ]
    private static let t0 = Models.Nsfw.Class.allCases.map { table[$0]!.0 }
    private static let t100 = Models.Nsfw.Class.allCases.map { table[$0]!.1 }

    private static let nsfwIdx = [Models.Nsfw.Class.porn, .sexy, .hentai].map(\.rawValue)
    private static let sfwIdx = [Models.Nsfw.Class.neutral, .drawings].map(\.rawValue)

    /// Linear between the two columns; out-of-range strictness returns the
    /// endpoint. The multiply happens before the float divide, as on Android.
    static func threshold(_ c: Int, strictness: Int) -> Float {
        let s = min(max(strictness, 0), 100)
        return t0[c] + (t100[c] - t0[c]) * Float(s) / 100
    }

    /// `nsfwMax` and `sfwMax` are **margins** (`p − thr`), not probabilities:
    /// `nsfwMax >= 0` is the "cleared its own threshold" test and `nsfwMax >
    /// sfwMax` is a veto by the strongest SFW margin. The veto is strict, so an
    /// exact tie fires; and at high strictness neutral's threshold reaches 1.00
    /// so its margin can never win — the veto disables itself by design (§2.4).
    static func fires(_ probs: [Float], strictness: Int) -> Bool {
        var nsfwMax = -Float.infinity
        for c in nsfwIdx { nsfwMax = max(nsfwMax, probs[c] - threshold(c, strictness: strictness)) }
        var sfwMax = -Float.infinity
        for c in sfwIdx { sfwMax = max(sfwMax, probs[c] - threshold(c, strictness: strictness)) }
        return nsfwMax >= 0 && nsfwMax > sfwMax
    }

    /// Hysteresis: expand each firing to `[t − 500, t + 1500]`, clamp both ends
    /// into `[0, durationMs]` **per firing**, then merge. Input need not be
    /// sorted; the result is time-ordered and disjoint.
    ///
    /// This runs ONCE over the whole timeline, never per segment — a censor
    /// interval that straddles a segment seam has to stay one interval, which is
    /// why checkpoints persist raw firings rather than intervals (§3).
    static func intervals(_ firingsMs: [Int64], durationMs: Int64) -> [ClosedRange<Int64>] {
        guard !firingsMs.isEmpty else { return [] }
        // Unknown duration is `max`, never 1: clamping the far end to a 1 ms
        // duration collapses every interval to [0,1] — the gate fires, the EDL
        // records it, and nothing is censored (§10.9).
        let limit = durationMs > 0 ? durationMs : .max
        let sorted = firingsMs.sorted()

        func clamp(_ t: Int64) -> Int64 { min(max(t, 0), limit) }

        var out: [ClosedRange<Int64>] = []
        var start = clamp(sorted[0] - AnalyzeConstants.preRollMs)
        var end = clamp(sorted[0] + AnalyzeConstants.postRollMs)
        for t in sorted.dropFirst() {
            let s = clamp(t - AnalyzeConstants.preRollMs)
            let e = clamp(t + AnalyzeConstants.postRollMs)
            // Merges overlapping *and* exactly-touching spans; a 1 ms uncovered
            // gap splits. Duplicate firings collapse naturally.
            if s <= end + 1 {
                if e > end { end = e }
            } else {
                out.append(start...end)
                start = s
                end = e
            }
        }
        out.append(start...end)
        return out
    }
}
