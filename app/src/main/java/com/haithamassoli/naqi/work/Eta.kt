package com.haithamassoli.naqi.work

import com.haithamassoli.naqi.model.FilterOps

/**
 * The up-front "this will take about N" the options screen shows before Start (`long-film-plan.md`
 * Phase 0): source duration times a per-shape factor, because every stage of this pipeline is linear
 * in source length.
 *
 * This is deliberately a **floor**, not a promise. Every number behind the factors below was measured
 * on a Galaxy S23 flagship, and the plan flags all of them as optimistically fast — the mid-range
 * hardware that actually needs the warning is still unmeasured (`tasks.md` M0 SPIKE, blocked on a
 * device since M0). So phrase it as "at least ~N" wherever it is shown, and let
 * [FilterWorker.KEY_ETA_MS] supersede it once the job is a few percent in: that one is extrapolated
 * from *this* device's observed rate, which is the only honest number available. Having both is the
 * point — one before there is any evidence, one as soon as there is.
 *
 * The Phase-0 soak (2026-07-27) turned [CENSOR] into a measured number. [MUSIC] was already one. Only
 * [COMBINED] is still part estimate: the soak was stopped after render, so htdemucs at feature length
 * has never been timed — see `tasks.md` M4.
 *
 * Pure Kotlin on purpose — no Android imports, so it unit-tests on the JVM without Robolectric.
 */
object Eta {

    /** Above this, Start asks the user to confirm rather than merely telling them the number. */
    const val CONFIRM_THRESHOLD_MS = 30L * 60 * 1000

    // Factors are wall clock ÷ source duration. Two significant figures: the inputs are one real
    // measurement and a pile of estimates, so a third digit would be invented precision.

    /**
     * **Measured** by the Phase-0 soak (2026-07-27, S23, 155.4 min film): analyze 70.5 + vote 2.7 +
     * render 9.7 = 82.9 min ⇒ 0.53. Kept at 0.54 — the difference is inside the render extrapolation's
     * own error, and rounding up is the cheap direction. The two estimates this replaced happened to
     * total the same number while being individually wrong by 2× and 3× in opposite directions.
     */
    private const val CENSOR = 0.54

    /** The one end-to-end measurement we have: 5-min clip → 3.4 min after the M3 re-export (`tasks.md` M2). */
    private const val MUSIC = 0.68

    /**
     * ~2.5 h per 2 h source — separate + analyze + render + mux (`long-film-plan.md` evidence table)
     * = 1.25, corroborated by the other two factors summing to 1.22. Rounded up rather than down
     * because combined is the shape already sitting on the 6 h foreground-service cap: an under-count
     * is the expensive direction to be wrong in.
     */
    private const val COMBINED = 1.3

    /**
     * Wall clock this job will take **at least**, in ms, or 0 when there is nothing honest to say.
     *
     * Zero covers the two degenerate inputs: a source whose duration the provider wouldn't give us,
     * and ops with neither flag set. The UI never offers that second case, but a confidently wrong
     * number on screen is worse than no number, and callers have to handle 0 for the first case anyway.
     */
    fun estimateMs(durationMs: Long, ops: FilterOps): Long {
        if (durationMs <= 0) return 0L
        val factor = when {
            ops.censorWomen && ops.removeMusic -> COMBINED
            ops.removeMusic -> MUSIC
            ops.censorWomen -> CENSOR
            else -> return 0L
        }
        return (durationMs * factor).toLong()
    }
}
