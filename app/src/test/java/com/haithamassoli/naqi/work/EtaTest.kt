package com.haithamassoli.naqi.work

import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase-0 soak will move [Eta]'s constants, so these pin the properties that must survive that
 * edit rather than the constants themselves: linear in duration, combined never cheaper than one op
 * alone, and no number at all for a job we can't size.
 */
class EtaTest {

    private val censor = FilterOps(censorWho = FilterOps.EVERYONE)
    private val music = FilterOps(removeMusic = true)
    private val both = FilterOps(censorWho = FilterOps.EVERYONE, removeMusic = true)

    @Test
    fun everyShapeScalesWithSourceDuration() {
        for (ops in listOf(censor, music, both)) {
            assertEquals(2 * Eta.estimateMs(FIVE_MIN, ops), Eta.estimateMs(2 * FIVE_MIN, ops))
        }
    }

    @Test
    fun combinedCostsMoreThanEitherOpAlone() {
        assertTrue(Eta.estimateMs(HOUR, both) > Eta.estimateMs(HOUR, censor))
        assertTrue(Eta.estimateMs(HOUR, both) > Eta.estimateMs(HOUR, music))
    }

    /**
     * The Phase-0 soak asset: 155 min combined has to read as "a few hours", not minutes or a day.
     *
     * The band moved from 3–4 h to 2–3 h with the 2026-07-29 recalibration (`perf-plan.md` item 1.3 cut
     * analyze 61 %, so `CENSOR` went 0.54 → 0.28 and `COMBINED` 1.3 → 1.0). Still a band and not an
     * equality, for the reason this file opens with: it pins the property, not the constant.
     */
    @Test
    fun theSoakFilmLandsInHoursAndNeedsConfirming() {
        val eta = Eta.estimateMs(155 * 60_000L, both)
        assertTrue(eta in 2 * HOUR..3 * HOUR)
        assertTrue(eta > Eta.CONFIRM_THRESHOLD_MS)
    }

    /**
     * The recalibration must not silently drift back. A 3-minute censor-only job was measured at 51.1 s
     * on an S23 (`wm3.mp4`, 192.9 s ⇒ 0.265); anything that quotes it above ~1.2 min has re-introduced
     * the stale pre-optimization factor.
     */
    @Test
    fun censorOnlyQuotesCloseToTheMeasuredRate() {
        val eta = Eta.estimateMs(192_911L, censor)
        assertTrue("quoted ${eta}ms for a 51.1 s job", eta in 51_000L..75_000L)
    }

    /** 0 means "no estimate" — anything else here would put a made-up number in front of the user. */
    @Test
    fun jobsWeCannotSizeEstimateNothing() {
        assertEquals(0L, Eta.estimateMs(0L, both)) // provider wouldn't give us a duration
        assertEquals(0L, Eta.estimateMs(-1L, both))
        assertEquals(0L, Eta.estimateMs(HOUR, FilterOps())) // no op selected — the UI forbids it
    }

    /**
     * Every shape's bands have to reach [Eta.Bands.MUX_BASE] exactly. A gap leaves the bar frozen while a
     * stage runs; an overlap makes the concurrent schedule's `videoPct + audioPct` sum overshoot 99 and
     * the bar hit "done" before the job is.
     */
    @Test
    fun everyShapesBandsReachTheMuxBase() {
        with(Eta.Bands) {
            assertEquals(MUX_BASE, ANALYZE + RENDER + SEPARATE)   // combined, and segmented with music
            assertEquals(MUX_BASE, CENSOR_ANALYZE + CENSOR_RENDER) // segmented censor-only
            assertEquals(MUX_BASE, 1 + MUSIC_SEPARATE)             // music-only, which starts at 1
            assertEquals(99, MUX_BASE + MUX)                       // 100 is reserved for "published"
        }
    }

    /**
     * The band that motivated the reweight. `perf-plan-v5.md` §8 measures the separator at **82.7 %** of a
     * combined job's wall against 17.3 % for analyze+render; the bands used to give those two 50 points
     * against the separator's 43, so [JobStats.etaMs] halved its estimate the moment htdemucs started
     * (`long-film-plan.md:56`). Anything under three times the video passes is that even split creeping
     * back in.
     */
    @Test
    fun theSeparatorOwnsMostOfTheCombinedBar() {
        with(Eta.Bands) {
            assertTrue(
                "separator has $SEPARATE points against ${ANALYZE + RENDER} for the video passes",
                SEPARATE > 3 * (ANALYZE + RENDER),
            )
        }
    }

    private companion object {
        const val FIVE_MIN = 5 * 60_000L
        const val HOUR = 60 * 60_000L
    }
}
