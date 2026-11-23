package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixed boxes and a scripted voter in, exact tracks, votes and censor decisions out. No models. */
class FaceTracksTest {
    // A 640x360 upright analysis frame: a box 0.25 wide is 160 px, well over MIN_FACE_PX.
    private val w = 640
    private val h = 360

    private fun box(left: Float, top: Float, size: Float = 0.25f) = NRect(left, top, left + size, top + size)

    @Test fun everyoneCensorsEveryTrackWithPaddedSpansAndNeverVotes() {
        val t = FaceTracks(FilterOps.EVERYONE)
        for (i in 0..2) {
            val dx = i * 0.01f // a little drift, as between 10 fps samples
            t.onFrame(listOf(box(0.10f + dx, 0.10f), box(0.60f + dx, 0.20f)), w, h, i * 100L, null)
        }
        val edls = t.finish()
        assertEquals(2, edls.size)
        for (e in edls) {
            assertEquals(0L, e.startMs)    // 0 - 50, clamped
            assertEquals(250L, e.endMs)    // 200 + 50
            assertEquals(listOf(0L, 100L, 200L), e.keyframes.map { it.first })
        }
        // padRect: 25% per side, clamped to the frame.
        val first = edls.first { it.keyframes[0].second.left < 0.5f }.keyframes[0].second
        assertRect(NRect(0.0375f, 0.0375f, 0.4125f, 0.4125f), first)
    }

    @Test fun womenAndMenSpareOnlyAStrictMajorityOfTheOtherGender() {
        // Four people, one per column. A votes female, B male, C one each (tie), D always abstains.
        fun run(who: String): List<Float> {
            val t = FaceTracks(who)
            var cCalls = 0
            val vote = { r: NRect ->
                when {
                    r.left < 0.2f -> -1
                    r.left < 0.45f -> 1
                    r.left < 0.7f -> if (cCalls++ % 2 == 0) 1 else -1
                    else -> 0
                }
            }
            for (i in 0..1) {
                // Each face grows, so the second frame is "bigger than anything classified" and votes too.
                val s = 0.2f + i * 0.02f
                t.onFrame(listOf(box(0.0f, 0.1f, s), box(0.25f, 0.1f, s), box(0.5f, 0.1f, s), box(0.75f, 0.1f, s)),
                    w, h, i * 100L, vote)
            }
            return t.finish().map { it.keyframes[0].second.left }.sorted()
        }
        // Padded lefts: 0.0 -> 0 (clamped), 0.25 -> 0.20, 0.5 -> 0.45, 0.75 -> 0.70.
        assertEquals(listOf(0, 45, 70), run(FilterOps.WOMEN).map { Math.round(it * 100) }) // B (male) spared
        assertEquals(listOf(20, 45, 70), run(FilterOps.MEN).map { Math.round(it * 100) })  // A (female) spared
    }

    @Test fun votesAreCappedAndSpentOnlyOnBiggerCropsOverTheSizeFloor() {
        val t = FaceTracks(FilterOps.WOMEN)
        val votedPx = mutableListOf<Int>()
        // A 1024-wide frame, so px / 1024 is exact in binary and the px the tracker recomputes is too.
        // Max side in px: 64, 72 (under MIN_FACE_PX 80), then 80, 80 (not bigger), 96, 112, 104 (smaller),
        // 128, 144 (the fifth vote: VOTE_CAP), then 160 and 176 unclassified.
        val sides = listOf(64, 72, 80, 80, 96, 112, 104, 128, 144, 160, 176)
        sides.forEachIndexed { i, px ->
            val r = NRect(0f, 0f, px / 1024f, px / 2048f)
            t.onFrame(listOf(r), 1024, 576, i * 100L) { votedPx += px; 0 }
        }
        assertEquals(listOf(80, 96, 112, 128, 144), votedPx)
        assertEquals(1, t.finish().size) // five abstentions: censored
    }

    @Test fun aFaceThatJumpsOrReturnsAfterTheGapStartsANewTrack() {
        val t = FaceTracks(FilterOps.EVERYONE)
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 0L, null)
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 100L, null)
        // IoU of 0.25-wide boxes 0.2 apart is 0.11 < MATCH_IOU: a different face.
        t.onFrame(listOf(box(0.3f, 0.1f)), w, h, 200L, null)
        // Same place as the first, but 700 ms after it was last seen: over MATCH_GAP_MS.
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 800L, null)
        val spans = t.finish().map { it.startMs to it.endMs }
        assertEquals(listOf(0L to 150L, 150L to 250L, 750L to 850L), spans)
    }

    @Test fun matchingIsOneToOne() {
        val t = FaceTracks(FilterOps.EVERYONE)
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 0L, null)
        // Two boxes both overlap the one track: the closer one continues it, the other starts its own.
        t.onFrame(listOf(box(0.14f, 0.1f), box(0.11f, 0.1f)), w, h, 100L, null)
        val edls = t.finish()
        assertEquals(2, edls.size)
        val continued = edls.single { it.keyframes.size == 2 }
        assertRect(box(0.11f, 0.1f).padded(), continued.keyframes[1].second)
    }

    @Test fun aDifferentPersonInTheSamePlaceLaterIsNotPooledIntoTheFirstTrack() {
        // The failure IoU tracking must not have: a woman leaves, and a second later a man stands where she
        // stood. Pooled into one track, 1 female vote against 2 male ones would spare the whole track in
        // Women mode, the woman included. Split, each is judged on their own votes.
        val t = FaceTracks(FilterOps.WOMEN)
        t.onFrame(listOf(box(0.4f, 0.2f)), w, h, 0L) { -1 }
        t.onFrame(listOf(box(0.4f, 0.2f, 0.26f)), w, h, 1_000L) { 1 }
        t.onFrame(listOf(box(0.4f, 0.2f, 0.27f)), w, h, 1_100L) { 1 }
        val edls = t.finish()
        assertEquals(1, edls.size)
        assertEquals(0L to 50L, edls[0].startMs to edls[0].endMs) // the woman; the man is spared
    }

    @Test fun aSmallerOverlappingFaceThatVotesTheOtherWayIsNotSparedOnTheFirstTracksVote() {
        // Codex review, finding 4: a male-voted box, then 100 ms later a slightly smaller overlapping box
        // that votes female. IoU matched them, her crop was "not bigger" so it never voted, and Women mode
        // spared the whole track: zero censor tracks. Her own vote disagrees with the spare, so it censors.
        fun run(who: String, first: Int): Int {
            val t = FaceTracks(who)
            t.onFrame(listOf(box(0.40f, 0.20f, 0.25f)), w, h, 0L) { first }
            t.onFrame(listOf(box(0.41f, 0.21f, 0.24f)), w, h, 100L) { -first }
            return t.finish().size
        }
        assertEquals(1, run(FilterOps.WOMEN, first = 1))
        assertEquals(1, run(FilterOps.MEN, first = -1))
    }

    @Test fun aSpareStandsWhileTheMatchedFacesKeepAgreeing() {
        // The check above must not cost a man in Women mode his spare just for being on screen.
        val t = FaceTracks(FilterOps.WOMEN)
        for (i in 0..9) t.onFrame(listOf(box(0.40f + (i % 2) * 0.01f, 0.20f, 0.25f - (i % 2) * 0.01f)), w, h, i * 100L) { 1 }
        assertEquals(0, t.finish().size)
    }

    @Test fun aFaceThatChangesSizeTooMuchStartsANewTrack() {
        val t = FaceTracks(FilterOps.EVERYONE)
        t.onFrame(listOf(box(0.30f, 0.30f, 0.20f)), w, h, 0L, null)
        // Concentric and 1.6x the side: IoU 0.39 clears MATCH_IOU, but no face grows that much in 100 ms.
        t.onFrame(listOf(box(0.24f, 0.24f, 0.32f)), w, h, 100L, null)
        assertEquals(2, t.finish().size)
    }

    @Test fun aSceneCutEndsEveryTrackSoTheNextShotIsJudgedOnItsOwn() {
        // A man voted male, then a cut to a woman framed where he was. Without the cut she would be matched
        // onto his track; with it she is a track of her own, and the agreeing check vote cannot save her.
        val t = FaceTracks(FilterOps.WOMEN)
        t.onFrame(listOf(box(0.40f, 0.20f)), w, h, 0L) { 1 }
        t.onFrame(listOf(box(0.40f, 0.20f)), w, h, 100L) { 1 }
        t.cut()
        t.onFrame(listOf(box(0.40f, 0.20f)), w, h, 200L) { -1 }
        val edls = t.finish()
        assertEquals(listOf(150L to 250L), edls.map { it.startMs to it.endMs }) // her span only; he is spared
    }

    @Test fun sceneCutsFireOnAChangeOfPictureAndNotOnAStillOne() {
        fun solid(r: Int, g: Int, b: Int) = RgbFrame(64, 36, ByteArray(64 * 36 * 3) { i ->
            when (i % 3) { 0 -> r; 1 -> g; else -> b }.toByte()
        }, 0L)
        val cuts = SceneCuts()
        assertFalse(cuts.isCut(solid(40, 40, 40)))     // the first frame is never a cut
        assertFalse(cuts.isCut(solid(50, 45, 40)))     // a small exposure change: mean diff 0.02
        assertTrue(cuts.isCut(solid(200, 190, 180)))   // a new picture: mean diff ~0.56
        assertFalse(cuts.isCut(solid(200, 190, 180)))
    }

    @Test fun staleTracksAreEmittedAndTheResultIsSortedByStart() {
        val t = FaceTracks(FilterOps.EVERYONE)
        t.onFrame(listOf(box(0.6f, 0.1f)), w, h, 0L, null)
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 2_000L, null) // evicts the first (2 s unseen)
        t.onFrame(listOf(box(0.1f, 0.1f)), w, h, 2_100L, null)
        assertEquals(listOf(0L, 1_950L), t.finish().map { it.startMs })
    }

    private fun NRect.padded() = NRect(left - width / 4, top - height / 4, right + width / 4, bottom + height / 4)

    private fun assertRect(expected: NRect, actual: NRect) {
        assertEquals(expected.left, actual.left, 1e-5f)
        assertEquals(expected.top, actual.top, 1e-5f)
        assertEquals(expected.right, actual.right, 1e-5f)
        assertEquals(expected.bottom, actual.bottom, 1e-5f)
    }
}
