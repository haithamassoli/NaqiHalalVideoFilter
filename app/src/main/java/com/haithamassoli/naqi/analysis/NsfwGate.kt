package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.ml.NSFW_CLASSES

/**
 * M1 NSFW whole-frame gate: the strictness→threshold table (PRD; constants tuned in QA), the
 * per-sample delta firing rule, and the hysteresis that expands firing timestamps into merged
 * censor intervals. Pure JVM (no Android) so the decision logic is unit-testable.
 *
 * All times are MILLIseconds (analysis/EDL space); the sampler converts from MediaCodec µs.
 */
object NsfwGate {
    /** Each firing at t censors [t - PRE_MS, t + POST_MS]. */
    const val PRE_MS = 500L
    const val POST_MS = 1500L

    // PRD strictness table keyed by class NAME (thr at s=0, thr at s=100), linear in between.
    // Single source of truth; the PRD lists these in a different order — never index by that order.
    private val TABLE = mapOf(
        "porn" to floatArrayOf(0.75f, 0.10f),
        "sexy" to floatArrayOf(0.90f, 0.10f),
        "hentai" to floatArrayOf(1.00f, 0.50f),
        "neutral" to floatArrayOf(0.30f, 1.00f),
        "drawings" to floatArrayOf(0.50f, 0.50f),
    )

    // Resolved into [NSFW_CLASSES] index order so probs[c] and thr(c) share one indexing.
    private val t0 = FloatArray(NSFW_CLASSES.size) { TABLE.getValue(NSFW_CLASSES[it])[0] }
    private val t100 = FloatArray(NSFW_CLASSES.size) { TABLE.getValue(NSFW_CLASSES[it])[1] }
    private val nsfwIdx = intArrayOf(NSFW_CLASSES.indexOf("porn"), NSFW_CLASSES.indexOf("sexy"), NSFW_CLASSES.indexOf("hentai"))
    private val sfwIdx = intArrayOf(NSFW_CLASSES.indexOf("neutral"), NSFW_CLASSES.indexOf("drawings"))

    /** Interpolated threshold for class [classIndex] at [strictness] (clamped to 0..100). */
    fun thr(classIndex: Int, strictness: Int): Float {
        val s = strictness.coerceIn(0, 100)
        return t0[classIndex] + (t100[classIndex] - t0[classIndex]) * s / 100f
    }

    /**
     * Delta rule per sample: nsfwMax = max over {porn,sexy,hentai} of (p − thr),
     * sfwMax = max over {neutral,drawings} of (p − thr); fire iff nsfwMax ≥ 0 AND nsfwMax > sfwMax.
     * At high strictness neutral's thr → 1.0, so its margin can't win — the veto disables by design.
     */
    fun fires(probs: FloatArray, strictness: Int): Boolean {
        var nsfwMax = Float.NEGATIVE_INFINITY
        for (c in nsfwIdx) { val d = probs[c] - thr(c, strictness); if (d > nsfwMax) nsfwMax = d }
        var sfwMax = Float.NEGATIVE_INFINITY
        for (c in sfwIdx) { val d = probs[c] - thr(c, strictness); if (d > sfwMax) sfwMax = d }
        return nsfwMax >= 0f && nsfwMax > sfwMax
    }

    /**
     * Hysteresis: expand each firing to [t − PRE_MS, t + POST_MS], merge overlapping/adjacent
     * spans, clamp to [0, durationMs]. Input need not be sorted. Result is time-ordered.
     */
    fun intervals(firingsMs: List<Long>, durationMs: Long): List<LongRange> {
        if (firingsMs.isEmpty()) return emptyList()
        val sorted = firingsMs.sorted()
        val out = ArrayList<LongRange>()
        var start = (sorted[0] - PRE_MS).coerceIn(0L, durationMs)
        var end = (sorted[0] + POST_MS).coerceIn(0L, durationMs)
        for (i in 1 until sorted.size) {
            val s = (sorted[i] - PRE_MS).coerceIn(0L, durationMs)
            val e = (sorted[i] + POST_MS).coerceIn(0L, durationMs)
            if (s <= end + 1) {            // overlapping or gap-free adjacent → merge
                if (e > end) end = e
            } else {
                out.add(start..end)
                start = s
                end = e
            }
        }
        out.add(start..end)
        return out
    }
}
