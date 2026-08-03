package com.haithamassoli.naqi.edl

import com.haithamassoli.naqi.analysis.NRect
import org.json.JSONArray
import org.json.JSONObject

/** One tracked face's censor span with normalized keyframe rects (already 25%-padded), sorted by time. */
data class FaceTrackEdl(val startMs: Long, val endMs: Long, val keyframes: List<Pair<Long, NRect>>)

/**
 * M1 edit-decision list: the per-frame censor plan emitted by pass 1 and consumed by the pass-2
 * renderer. [fullFrameAt] and [regionsAt] run once per rendered frame, so both stay allocation-light.
 * Precedence: an active censor interval blanks the whole frame and suppresses face regions.
 * Serializes via org.json (Android platform; JVM tests add the dep). All times are MILLIseconds.
 */
data class Edl(val censorIntervalsMs: List<LongRange>, val faceTracks: List<FaceTrackEdl>) {

    /** True when [tMs] falls inside any whole-frame censor interval. */
    fun fullFrameAt(tMs: Long): Boolean {
        for (i in censorIntervalsMs.indices) {
            val r = censorIntervalsMs[i]
            if (tMs >= r.first && tMs <= r.last) return true
        }
        return false
    }

    /** Face rects to censor at [tMs] — empty under full-frame precedence, else one per active track. */
    fun regionsAt(tMs: Long): List<NRect> {
        if (fullFrameAt(tMs)) return emptyList()
        var out: MutableList<NRect>? = null
        for (i in faceTracks.indices) {
            val tr = faceTracks[i]
            if (tMs < tr.startMs || tMs > tr.endMs) continue
            val rect = tr.rectAt(tMs) ?: continue
            if (out == null) out = ArrayList(2)
            out.add(rect)
        }
        return out ?: emptyList()
    }

    fun toJson(): String {
        val intervals = JSONArray()
        for (r in censorIntervalsMs) intervals.put(JSONArray().put(r.first).put(r.last))
        val tracks = JSONArray()
        for (tr in faceTracks) {
            val kfs = JSONArray()
            for ((t, rect) in tr.keyframes) {
                kfs.put(
                    JSONArray().put(t)
                        .put(rect.left.toDouble()).put(rect.top.toDouble())
                        .put(rect.right.toDouble()).put(rect.bottom.toDouble()),
                )
            }
            tracks.put(JSONObject().put("startMs", tr.startMs).put("endMs", tr.endMs).put("keyframes", kfs))
        }
        return JSONObject().put("censorIntervalsMs", intervals).put("faceTracks", tracks).toString()
    }

    companion object {
        fun fromJson(s: String): Edl {
            val root = JSONObject(s)
            val ij = root.getJSONArray("censorIntervalsMs")
            val intervals = ArrayList<LongRange>(ij.length())
            for (i in 0 until ij.length()) {
                val p = ij.getJSONArray(i)
                intervals.add(p.getLong(0)..p.getLong(1))
            }
            val tj = root.getJSONArray("faceTracks")
            val tracks = ArrayList<FaceTrackEdl>(tj.length())
            for (i in 0 until tj.length()) {
                val t = tj.getJSONObject(i)
                val kj = t.getJSONArray("keyframes")
                val kfs = ArrayList<Pair<Long, NRect>>(kj.length())
                for (j in 0 until kj.length()) {
                    val a = kj.getJSONArray(j)
                    kfs.add(
                        a.getLong(0) to NRect(
                            a.getDouble(1).toFloat(), a.getDouble(2).toFloat(),
                            a.getDouble(3).toFloat(), a.getDouble(4).toFloat(),
                        ),
                    )
                }
                tracks.add(FaceTrackEdl(t.getLong("startMs"), t.getLong("endMs"), kfs))
            }
            return Edl(intervals, tracks)
        }
    }
}

/**
 * Gap under which two whole-frame spans merge rather than strobe. A face track ends `SPAN_PAD_MS`
 * after its last sample and a re-detect a few frames later would otherwise unblur and re-blur the
 * whole picture — invisible when it is a rect, very visible when it is the frame.
 *
 * ponytail: one fixed bridge for every video. Make it shot-length-aware only if a real asset strobes.
 */
internal const val BRIDGE_MS = 400L

/** Merge overlapping or near-adjacent ranges. Input need not be sorted; output is, and is disjoint. */
internal fun mergeRanges(ranges: List<LongRange>, bridgeMs: Long = BRIDGE_MS): List<LongRange> {
    if (ranges.size <= 1) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<LongRange>(sorted.size)
    var start = sorted[0].first
    var end = sorted[0].last
    for (i in 1 until sorted.size) {
        val r = sorted[i]
        if (r.first <= end + bridgeMs) {
            if (r.last > end) end = r.last
        } else {
            out.add(start..end)
            start = r.first
            end = r.last
        }
    }
    out.add(start..end)
    return out
}

/**
 * Shortest whole-frame span worth keeping. A one-sample face track spans `first-50..last+50` = 100 ms,
 * which is 2-3 frames — the entire picture blinks and comes back, and it reads as a decode glitch.
 *
 * Measured on the S23 (tv1.webm, 643 s): 6 of the 29 merged spans were under 1 s and three were
 * exactly 100 ms. [BRIDGE_MS] cannot remove those; it only joins spans that are already near each
 * other, and these are isolated.
 *
 * **Dropping a short span costs no coverage.** `Edl.regionsAt` only returns rects where no full-frame
 * span is active, so a dropped promotion falls back to that track's own blurred rect — exactly what
 * rect mode does. The face stays censored; only the whole-frame flash goes away.
 */
internal const val MIN_FULL_MS = 500L

/**
 * Whole-frame mode: every censored face span becomes a whole-frame span, merged into [intervals].
 *
 * Merging is not cosmetic. The 155-min film in `long-film-plan.md` produced 3 362 tracks and
 * [Edl.fullFrameAt] is a linear scan per rendered frame; unmerged that is ~3 362 comparisons across
 * ~232 k frames. Merged, footage with people in most shots collapses to a few dozen ranges — fewer
 * than [Edl.regionsAt] scans today, which is why this mode does not cost render time (plan §4).
 *
 * The [MIN_FULL_MS] filter runs AFTER the merge, so two blips 300 ms apart become one 500 ms span and
 * survive together rather than being dropped one at a time. It cannot drop a gate interval either:
 * `NsfwGate` hysteresis floors those at 2 s, measured as the shortest span in every run.
 */
internal fun promoteFacesToFullFrame(intervals: List<LongRange>, tracks: List<FaceTrackEdl>): List<LongRange> =
    mergeRanges(intervals + tracks.map { it.startMs..it.endMs })
        .filter { it.last - it.first >= MIN_FULL_MS }

/** Linear-interpolate the track's rect at [tMs], clamped to the first/last keyframe at the span edges. */
private fun FaceTrackEdl.rectAt(tMs: Long): NRect? {
    val kf = keyframes
    if (kf.isEmpty()) return null
    val first = kf.first()
    if (tMs <= first.first) return first.second
    val last = kf.last()
    if (tMs >= last.first) return last.second
    // Binary search: largest index whose time <= tMs (the segment's left keyframe).
    var lo = 0
    var hi = kf.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (kf[mid].first <= tMs) lo = mid else hi = mid - 1
    }
    val (t0, r0) = kf[lo]
    val (t1, r1) = kf[lo + 1]
    if (t1 <= t0) return r0
    val f = (tMs - t0).toFloat() / (t1 - t0).toFloat()
    return NRect(
        r0.left + (r1.left - r0.left) * f,
        r0.top + (r1.top - r0.top) * f,
        r0.right + (r1.right - r0.right) * f,
        r0.bottom + (r1.bottom - r0.bottom) * f,
    )
}
