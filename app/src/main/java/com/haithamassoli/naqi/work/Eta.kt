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
    //
    // **They are asymptotes, not per-clip truth.** Every one of them is linear-in-duration, but the job
    // also pays a fixed cost that does not scale: loading an 88 MB htdemucs graph and standing up the ORT
    // sessions. Measured 2026-07-29 on an S23, music removal alone:
    //
    //   81.9 s source   separate 93.6 s   1.14x   <- fixed cost dominates
    //   634 s source    separate 390 s    0.62x
    //   5 min source    (M2/M3)           0.68x
    //
    // So a clip under ~2 min is quoted low — a 1-minute video estimated at 1 minute really takes two.
    // Deliberately not corrected for: adding a constant term would distort the long jobs these numbers
    // exist to warn about, and being 30 s out on a 1-minute clip is not a warning anyone needs. The
    // confirm threshold is 30 min, which is far inside the range where the linear factors hold.

    /**
     * **Re-measured 2026-07-29** (S23, `wm3.mp4`, 192.9 s, censor-only, unsegmented): analyze 36.9 s +
     * vote 0.001 s + render 13.5 s + publish 0.4 s = 51.1 s ⇒ **0.265**. Set to 0.28, rounding up for
     * the concat a segmented film adds and to keep this a floor.
     *
     * The 0.54 this replaces was itself a real Phase-0 soak measurement (2026-07-27, 155.4 min film:
     * analyze 70.5 + vote 2.7 + render 9.7 = 82.9 min ⇒ 0.53) — it was not wrong, it was **stale**.
     * `perf-plan.md` item 1.3 landed the day after and cut analyze by 61 %, which is where essentially
     * the whole 2× came from; deriving 0.54 forward through that win independently gives 0.257, within
     * 3 % of what was measured here. Two routes, one answer.
     *
     * Honest caveat: this measurement is at 3 minutes and the 61 % was itself only ever measured on
     * short assets. A feature-length censor run has not been timed since the win, so the film-length
     * claim is projected, not observed — `long-film-followups.md` item 4 is what closes it.
     */
    private const val CENSOR = 0.28

    /** The one end-to-end measurement we have: 5-min clip → 3.4 min after the M3 re-export (`tasks.md` M2). */
    private const val MUSIC = 0.68

    /**
     * The other two factors sum to 0.96 after the 2026-07-29 [CENSOR] recalibration; rounded up to 1.0.
     *
     * That sum is the right model here because combined runs the two pipelines back to back rather than
     * overlapped — analyze, render, separate, mux, in sequence. The previous 1.3 came from the
     * `long-film-plan.md` evidence table and was corroborated by the then-current factors summing to
     * 1.22; the same corroboration now lands at 0.96, and the same "round up, an under-count is the
     * expensive direction" rule applies — combined is the shape already sitting on the 6 h
     * foreground-service cap.
     */
    private const val COMBINED = 1.0

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
            ops.censorFaces && ops.removeMusic -> COMBINED
            ops.removeMusic -> MUSIC
            ops.censorFaces -> CENSOR
            else -> return 0L
        }
        return (durationMs * factor).toLong()
    }

    /**
     * How many percentage points of the bar each stage owns, sized off the stage's **measured share of
     * the wall** rather than off how important it feels. [JobStats.etaMs] extrapolates straight-line over
     * the overall percent, so a band that is wider than its cost makes the ETA under-promise for exactly
     * as long as that stage runs — which is what `long-film-plan.md:56` recorded: the combined shape gave
     * analyze+render **50 points for 17 % of the work**, so the bar reached half way in a sixth of the
     * time and then crawled, and the quoted "N remaining" doubled the moment htdemucs started.
     *
     * From `perf-plan-v5.md` §8 — S23, **non-debuggable** build, `BSKHKT-EP-02-FHD.mp4` (1522 s, 1080p):
     *
     *   analyze  123 455 ms   8.4 %      separate  ~1 221 700 ms  82.7 %   (`ort` 1 038 472 ÷ 0.85)
     *   render   131 244 ms   8.9 %      mux/concat  a stream copy, under 1 % at any length
     *
     * §8.2 is why the build matters: the Kotlin-heavy stages run 2.7–5.3× faster once ART optimises them,
     * so the debuggable numbers behind [CENSOR] would have sized analyze nearly 2× too wide. htdemucs is
     * native ORT and moves 1.05×, so its share is the one number that is the same on both builds.
     *
     * ponytail: one set of shares for both schedules. Under the concurrent one ([FilterWorker.branches])
     * the video branch hides inside the separator and its honest share is ~7, not [ANALYZE] + [RENDER],
     * so the bar still runs up to ~13 points ahead mid-job — against ~43 before this. Split them by
     * schedule when someone complains about the remainder.
     */
    object Bands {
        /** Combined: analyze 0..8, render 8..17, separate 17..97, mux 97..99. */
        const val ANALYZE = 8
        const val RENDER = 9
        const val SEPARATE = 80

        /** Music-only: one long stage, 1..97. Starts at 1 so the bar is non-empty before the first chunk. */
        const val MUSIC_SEPARATE = 96

        /** Segmented censor-only: no separator, so the two video passes take its share. */
        const val CENSOR_ANALYZE = 47
        const val CENSOR_RENDER = 50

        /** Where every shape's final container write starts. It always runs to 99; 100 means published. */
        const val MUX_BASE = 97
        const val MUX = 99 - MUX_BASE
    }
}
