package com.haithamassoli.naqi.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The gate's policy around a fake YAMNet: "music" wherever a frame holds a sample above 0.5. */
class MusicGateTest {
    private var calls = 0
    private val gate = MusicGate(infer = { frame, scores ->
        calls++
        scores.fill(0f)
        var loud = false
        while (frame.hasRemaining()) if (frame.get() > 0.5f) loud = true
        if (loud) scores[132] = 0.9f // first class of the music block
    })

    private fun window(fill: Float, loudFrom: Int) =
        FloatArray(DemucsSeparator.SEG) { if (it >= loudFrom) 0.9f else fill }

    @Test fun silenceScoresZeroWithoutRunningTheModel() {
        assertEquals(0f, gate.score(window(0f, Int.MAX_VALUE), DemucsSeparator.SEG))
        assertEquals(0, calls)
    }

    @Test fun theLastFrameSitsFlushWithTheEndSoTheTailIsScored() {
        // Music only in the final 0.3 s: a plain 0.975 s stride would never reach it.
        assertEquals(0.9f, gate.score(window(0.01f, DemucsSeparator.SEG - 13_000), DemucsSeparator.SEG))
        assertEquals(3, calls)
    }

    @Test fun stopsAtTheFirstFrameOverTheThreshold() {
        assertEquals(0.9f, gate.score(window(0.01f, 0), DemucsSeparator.SEG))
        assertEquals(1, calls)
    }
}
