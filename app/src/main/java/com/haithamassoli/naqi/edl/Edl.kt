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
