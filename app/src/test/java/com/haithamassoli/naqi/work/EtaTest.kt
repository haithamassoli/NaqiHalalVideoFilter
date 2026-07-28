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

    private val censor = FilterOps(censorWomen = true)
    private val music = FilterOps(removeMusic = true)
    private val both = FilterOps(censorWomen = true, removeMusic = true)

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

    /** The Phase-0 soak asset: 155 min combined has to read as "a few hours", not minutes or a day. */
    @Test
    fun theSoakFilmLandsInHoursAndNeedsConfirming() {
        val eta = Eta.estimateMs(155 * 60_000L, both)
        assertTrue(eta in 3 * HOUR..4 * HOUR)
        assertTrue(eta > Eta.CONFIRM_THRESHOLD_MS)
    }

    /** 0 means "no estimate" — anything else here would put a made-up number in front of the user. */
    @Test
    fun jobsWeCannotSizeEstimateNothing() {
        assertEquals(0L, Eta.estimateMs(0L, both)) // provider wouldn't give us a duration
        assertEquals(0L, Eta.estimateMs(-1L, both))
        assertEquals(0L, Eta.estimateMs(HOUR, FilterOps())) // no op selected — the UI forbids it
    }

    private companion object {
        const val FIVE_MIN = 5 * 60_000L
        const val HOUR = 60 * 60_000L
    }
}
