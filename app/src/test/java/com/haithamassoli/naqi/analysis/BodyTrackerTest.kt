package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.model.FilterOps
import org.junit.Assert.*
import org.junit.Test

class BodyTrackerTest {
    private val body = NRect(.1f, .1f, .9f, 1f)
    private val face = NRect(.3f, .15f, .6f, .35f)

    @Test fun faceDisappearsButPersonDecisionSurvives() {
        val tracker = BodyTracker(FilterOps.WOMEN)
        tracker.onFrame(listOf(body), listOf(face), 640, 640, 0, vote = { -1 })
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 30)
        val output = tracker.finish()
        assertEquals(1, output.size)
        assertTrue(output.single().endMs >= 30)
        assertEquals(1f, output.last().keyframes.last().second.bottom, 0f)
    }

    @Test fun cutAndAmbiguityNeverTransferASpareDecision() {
        val tracker = BodyTracker(FilterOps.WOMEN)
        tracker.onFrame(listOf(body), listOf(face), 640, 640, 0, vote = { 1 })
        tracker.onFrame(listOf(body), listOf(face), 640, 640, 100, vote = { 1 })
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 130)
        assertTrue(tracker.finish().isEmpty()) // classified male remains spared without a face

        val cut = BodyTracker(FilterOps.WOMEN)
        cut.onFrame(listOf(body), listOf(face), 640, 640, 0, vote = { 1 })
        cut.onFrame(listOf(body), listOf(face), 640, 640, 100, vote = { 1 })
        cut.onFrame(listOf(body), emptyList(), 640, 640, 130, sceneCut = true)
        assertEquals(1, cut.finish().size) // same coordinates, new unknown person, covered

        val crossing = BodyTracker(FilterOps.WOMEN)
        crossing.onFrame(listOf(body), listOf(face), 640, 640, 0, vote = { 1 })
        crossing.onFrame(listOf(body), listOf(face), 640, 640, 100, vote = { 1 })
        crossing.onFrame(listOf(body, body), emptyList(), 640, 640, 130)
        assertEquals(2, crossing.finish().size) // ambiguous continuation does not inherit "spare"
    }

    @Test fun missingBodyFallsBackAndUnknownPeopleAreCovered() {
        val tracker = BodyTracker(FilterOps.WOMEN)
        tracker.onFrame(emptyList(), listOf(face), 640, 640, 100)
        assertEquals(listOf(100L..400L), tracker.fallbackIntervals)
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 200)
        assertEquals(1, tracker.finish().size)
    }

    @Test fun orphanFacesStayContiguousAt10Fps() {
        val tracker = BodyTracker(FilterOps.WOMEN)
        tracker.onFrame(emptyList(), emptyList(), 640, 640, 0)
        for (pts in listOf(100L, 200L, 301L)) tracker.onFrame(emptyList(), listOf(face), 640, 640, pts)
        for (pts in listOf(401L, 501L, 601L, 701L)) tracker.onFrame(emptyList(), emptyList(), 640, 640, pts)
        tracker.onFrame(emptyList(), listOf(face), 640, 640, 801)
        val merged = com.haithamassoli.naqi.edl.mergeRanges(tracker.fallbackIntervals, bridgeMs = 0)
        // From just after the previous sample to HOLD_MS past the last one; a 400 ms miss reopens it.
        assertEquals(listOf(1L..601L, 702L..1101L), merged)
    }

    @Test fun tracksAfterACutReachBackToThePreviousSample() {
        val tracker = BodyTracker(FilterOps.EVERYONE)
        val other = NRect(.5f, .2f, .9f, .9f)
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 0)
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 100)
        tracker.onFrame(listOf(other), emptyList(), 640, 640, 200, sceneCut = true)
        val (old, new) = tracker.finish()
        // The cut happened somewhere in 101..199: the old shot keeps its box up to this sample and the
        // new one starts right after the previous sample, so no frame of either is left uncovered.
        // The usual 300 ms lead-in stops at the cut.
        assertEquals(0L, old.startMs)
        assertEquals(199L, old.endMs)
        assertEquals(101L, new.startMs)
        assertEquals(other.right, new.keyframes.single().second.right, 1f)
        assertTrue(old.union && new.union)
    }

    @Test fun ambiguousBoxesDoNotAccumulateGhostsAcrossFrames() {
        val tracker = BodyTracker(FilterOps.EVERYONE)
        for (i in 0..20) tracker.onFrame(listOf(body, body), emptyList(), 640, 640, i * 30L)
        val spans = tracker.finish()
        for (i in 0..20) assertTrue(spans.count { i * 30L in it.startMs..it.endMs } <= 2)
    }

    @Test fun oneWrongFaceVoteCannotExposeAnEntireBody() {
        val tracker = BodyTracker(FilterOps.WOMEN)
        tracker.onFrame(listOf(body), listOf(face), 640, 640, 0, vote = { 1 })
        tracker.onFrame(listOf(body), emptyList(), 640, 640, 100)
        assertEquals(1, tracker.finish().size)
    }

    @Test fun personRectIncludesAMarginForHandsOutsideTheDetectedBox() {
        val tracker = BodyTracker(FilterOps.EVERYONE)
        val narrow = NRect(.2f, .1f, .7f, .9f)
        tracker.onFrame(listOf(narrow), emptyList(), 640, 640, 0)
        val padded = tracker.finish().single().keyframes.single().second
        assertTrue(padded.right >= .82f)
        assertEquals(0f, padded.top, 0f)
        assertEquals(1f, padded.bottom, 0f)
    }
}
