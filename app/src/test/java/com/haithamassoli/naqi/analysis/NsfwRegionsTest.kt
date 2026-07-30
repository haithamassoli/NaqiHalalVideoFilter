package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.ml.NUDENET_CLASSES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run-merging and fail-safe decisions of [NsfwRegions] are pure, so they run without Android,
 * Robolectric or ORT; the NudeNet inference itself and the `runCatching` fallback around it are
 * device-verified via `--ez per_region true`, not unit-tested.
 *
 * `no censored millisecond is lost` is the load-bearing one — it is the executable form of the safety
 * invariant in [NsfwRegions]'s KDoc, and it fails in both directions (feed every firing through and
 * there is no watchability win; narrow the fallback window and content leaks).
 */
class NsfwRegionsTest {

    @Test
    fun `censorable and trusted sets resolve by name and never include a face`() {
        assertEquals(16, NsfwRegions.CENSORABLE.size)
        assertFalse(NsfwRegions.CENSORABLE.contains(FACE_F))
        assertFalse(NsfwRegions.CENSORABLE.contains(FACE_M))
        assertEquals(9, NsfwRegions.TRUSTED.size)
        assertTrue(NsfwRegions.CENSORABLE.containsAll(NsfwRegions.TRUSTED))
        assertEquals(
            setOf(
                "FEMALE_GENITALIA_COVERED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED",
                "ANUS_COVERED", "ANUS_EXPOSED", "BUTTOCKS_COVERED", "BUTTOCKS_EXPOSED",
                "FEMALE_BREAST_COVERED", "FEMALE_BREAST_EXPOSED",
            ),
            NsfwRegions.TRUSTED.map { NUDENET_CLASSES[it] }.toSet(),
        )
    }

    @Test
    fun `an empty sample list is the identity, which is the flag-off proof`() {
        val p = NsfwRegions.plan(listOf(1000L, 1200L), emptyList(), emptyList())
        assertEquals(listOf(1000L, 1200L), p.fallbackFiringsMs)
        assertTrue(p.tracks.isEmpty())
    }

    @Test
    fun `a firing with no box, an untrusted box, or a trusted box under the cover floor stays whole-frame`() {
        val samples = listOf(
            RegionSample(1000L, emptyList()),
            RegionSample(1200L, listOf(RegionBox(FEET, 0.9f, BOX))),                          // censorable, not trusted
            RegionSample(1400L, listOf(RegionBox(BREAST, NsfwRegions.COVER_SCORE - 0.01f, BOX))),
        )
        val p = NsfwRegions.plan(samples.map { it.ptsMs }, samples, emptyList())
        assertEquals(listOf(1000L, 1200L, 1400L), p.fallbackFiringsMs)
        assertTrue(p.tracks.isEmpty())
    }

    @Test
    fun `a covered run longer than the hysteresis window becomes one span and keeps only its endpoints`() {
        val samples = coveredRun(16)
        val p = NsfwRegions.plan(samples.map { it.ptsMs }, samples, emptyList())
        assertEquals(1, p.tracks.size)
        assertEquals(1000L, p.tracks[0].startMs)
        assertEquals(4000L, p.tracks[0].endMs)
        assertEquals(16, p.tracks[0].keyframes.size)
        assertEquals(listOf(1000L, 4000L), p.fallbackFiringsMs.sorted())
    }

    @Test
    fun `a run no longer than the hysteresis window keeps every firing`() {
        val samples = coveredRun(11) // 1000..3000, span exactly MIN_SPAN_MS, not greater
        assertEquals(NsfwRegions.MIN_SPAN_MS, samples.last().ptsMs - samples.first().ptsMs)
        val p = NsfwRegions.plan(samples.map { it.ptsMs }, samples, emptyList())
        assertTrue(p.tracks.isEmpty())
        assertEquals(11, p.fallbackFiringsMs.size)
    }

    @Test
    fun `no censored millisecond is lost`() {
        val samples = coveredRun(16) + RegionSample(5000L, emptyList())
        val allFirings = samples.map { it.ptsMs }
        val p = NsfwRegions.plan(allFirings, samples, emptyList())
        // Guard against passing by simply never trading anything away.
        assertEquals(1, p.tracks.size)

        val whole = Edl(NsfwGate.intervals(allFirings, DURATION), emptyList())
        val per = Edl(NsfwGate.intervals(p.fallbackFiringsMs, DURATION), p.tracks)
        var t = 0L
        while (t <= DURATION) {
            if (whole.fullFrameAt(t)) {
                assertTrue("uncensored at t=$t", per.fullFrameAt(t) || per.regionsAt(t).isNotEmpty())
            }
            t += 10L
        }
    }

    @Test
    fun `the sliding union contains both endpoint observations at every interpolated instant`() {
        val moving: (Int) -> NRect = { NRect(0.30f + it * 0.02f, 0.30f, 0.50f + it * 0.02f, 0.50f) }
        val samples = coveredRun(16, rect = moving)
        val p = NsfwRegions.plan(samples.map { it.ptsMs }, samples, emptyList())
        assertEquals(1, p.tracks.size)
        val kfs = p.tracks[0].keyframes
        val edl = Edl(emptyList(), p.tracks)

        for ((i, kf) in kfs.withIndex()) {
            assertTrue("keyframe $i out of [0,1]: ${kf.second}", inUnitSquare(kf.second))
        }
        for (k in 0 until kfs.size - 1) {
            val mid = (kfs[k].first + kfs[k + 1].first) / 2
            val r = edl.regionsAt(mid).single()
            // The RAW (unpadded) observations at both ends of this segment must be inside the
            // interpolated rect — this is the 200 ms cadence guarantee, not a padding coincidence.
            for (box in listOf(moving(k), moving(k + 1))) {
                assertTrue("t=$mid lost box $box in $r", contains(r, box))
            }
        }
    }

    @Test
    fun `a union covering most of the frame downgrades the whole run to whole-frame`() {
        val samples = coveredRun(16, rect = { NRect(0f, 0f, 1f, 1f) })
        val p = NsfwRegions.plan(samples.map { it.ptsMs }, samples, emptyList())
        assertTrue(p.tracks.isEmpty())
        assertEquals(16, p.fallbackFiringsMs.size)
    }

    @Test
    fun `a run whose span is crowded with face tracks is downgraded rather than gambling on the shader slots`() {
        val samples = coveredRun(16)
        val firings = samples.map { it.ptsMs }
        val faces = { n: Int ->
            (0 until n).map { FaceTrackEdl(0L, 6000L, listOf(0L to NRect(0f, 0f, 0.1f, 0.1f))) }
        }
        // 8 faces + 1 NSFW rect would be 9 for 8 shader slots, where largest-kept could evict the
        // small critical box in favour of a big padded face.
        val full = NsfwRegions.plan(firings, samples, faces(8))
        assertTrue(full.tracks.isEmpty())
        assertEquals(16, full.fallbackFiringsMs.size)

        val room = NsfwRegions.plan(firings, samples, faces(7))
        assertEquals(1, room.tracks.size)
    }

    // ---- fixtures ----

    private companion object {
        const val DURATION = 10_000L

        // Indices into NUDENET_CLASSES, spelled out so a reorder fails the first test rather than these.
        const val FACE_F = 1
        const val BREAST = 3   // FEMALE_BREAST_EXPOSED — censorable AND trusted
        const val FEET = 7     // FEET_EXPOSED — censorable, NOT trusted
        const val FACE_M = 12

        val BOX = NRect(0.4f, 0.3f, 0.6f, 0.5f)

        /** [n] consecutive covered gate samples 200 ms apart (the 5 fps cadence), from 1000 ms. */
        fun coveredRun(
            n: Int,
            step: Long = 200L,
            t0: Long = 1000L,
            cls: Int = BREAST,
            score: Float = 0.8f,
            rect: (Int) -> NRect = { BOX },
        ): List<RegionSample> =
            (0 until n).map { RegionSample(t0 + it * step, listOf(RegionBox(cls, score, rect(it)))) }

        fun inUnitSquare(r: NRect) =
            r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f && r.left <= r.right && r.top <= r.bottom

        fun contains(outer: NRect, inner: NRect) =
            outer.left <= inner.left && outer.top <= inner.top &&
                outer.right >= inner.right && outer.bottom >= inner.bottom
    }
}
