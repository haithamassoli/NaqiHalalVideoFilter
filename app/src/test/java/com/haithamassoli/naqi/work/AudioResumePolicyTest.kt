package com.haithamassoli.naqi.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioResumePolicyTest {
    @Test
    fun longOrForcedAudioUsesCheckpoints() {
        val threshold = Eta.CONFIRM_THRESHOLD_MS
        assertFalse(shouldResumeAudio(0L, false))
        assertFalse(shouldResumeAudio(threshold - 1L, false))
        assertTrue(shouldResumeAudio(threshold, false))
        assertTrue(shouldResumeAudio(0L, true))
    }
}
