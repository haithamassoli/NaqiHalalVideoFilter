package com.haithamassoli.naqi.work

import com.haithamassoli.naqi.render.RenderSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Checkpoint.plan]'s boundary arithmetic, after `long-film-followups.md` item 2 gave it a cut hook.
 *
 * The point of the hook is that a boundary can move off the nominal `i * segmentMs` — on a real source it
 * moves to the next sync sample, up to a keyframe interval away — WITHOUT the plan losing any property
 * that resume depends on: contiguous, non-empty, starts at 0, ends at durationMs, densely indexed. An
 * empty or inverted segment is not a cosmetic bug, it makes media3 throw on an inverted clip, and a gap
 * between two segments is source the output would simply be missing.
 *
 * The real hook is a [android.media.MediaExtractor], which cannot run in a JVM test; these pin the
 * arithmetic around it with pure lambdas, which is the half that can actually be got wrong.
 */
class CheckpointPlanTest {

    private val hour = 60L * 60 * 1000
    private val seg = Checkpoint.SEGMENT_MS // 5 min

    /** Every property a resumable plan needs, asserted together — they only mean anything as a set. */
    private fun assertWellFormed(plan: List<RenderSegment>, durationMs: Long) {
        assertTrue("plan is empty", plan.isNotEmpty())
        assertEquals("segment 0 must start at the film's own start", 0L, plan.first().startMs)
        assertEquals("last segment must end at the film's own end", durationMs, plan.last().endMs)
        plan.forEach { assertTrue("empty/inverted segment $it", it.endMs > it.startMs) }
        plan.zipWithNext().forEach { (a, b) -> assertEquals("gap or overlap at $a/$b", a.endMs, b.startMs) }
        // seg-NNN.mp4 / an-NNN.json are named by index, so a hole here orphans a checkpoint.
        plan.forEachIndexed { i, s -> assertEquals(i, s.index) }
    }

    @Test
    fun shortSourcesStillRunInOnePass() {
        assertEquals(emptyList<RenderSegment>(), Checkpoint.plan(29 * 60_000L))
        assertEquals(emptyList<RenderSegment>(), Checkpoint.plan(0L))
        assertEquals(emptyList<RenderSegment>(), Checkpoint.plan(-1L))
    }

    /** The default hook is identity, so a source we cannot open plans exactly as it did before item 2. */
    @Test
    fun theDefaultHookReproducesTheOldPlanExactly() {
        val plan = Checkpoint.plan(hour)
        assertEquals(12, plan.size)
        plan.forEachIndexed { i, s ->
            assertEquals(i * seg, s.startMs)
            assertEquals(minOf((i + 1) * seg, hour), s.endMs)
        }
        assertWellFormed(plan, hour)
    }

    /** A snapped plan is still a well-formed plan — the whole reason the hook is allowed to move a cut. */
    @Test
    fun snappingKeepsThePlanContiguousAndWholeFilmLong() {
        val duration = 2 * hour + 137 // deliberately not a multiple of anything
        // Stands in for a real keyframe grid: every cut lands a few seconds late, never on a round number.
        val plan = Checkpoint.plan(duration) { it + 2_137 }
        assertWellFormed(plan, duration)
        plan.drop(1).forEach { assertEquals(2_137L, it.startMs % seg) }
    }

    /**
     * The film's own two ends are never snapped. Segment 0 starting late would clip the opening frames
     * away, and a last segment past the source would be an inverted clip.
     */
    @Test
    fun theFilmsOwnEndsAreNeverMoved() {
        val duration = 2 * hour
        val plan = Checkpoint.plan(duration) { it + 90_000 } // an absurdly sparse keyframe grid
        assertEquals(0L, plan.first().startMs)
        assertEquals(duration, plan.last().endMs)
    }

    /** A sparse-keyframe source gets FEWER, LONGER segments — never an empty one media3 would throw on. */
    @Test
    fun cutsThatCollapseOntoOneSampleMergeInsteadOfEmittingEmptySegments() {
        val duration = 2 * hour
        val plan = Checkpoint.plan(duration) { 40 * 60_000L } // everything snaps to the same instant
        assertEquals(listOf(0L to 40 * 60_000L, 40 * 60_000L to duration), plan.map { it.startMs to it.endMs })
        assertWellFormed(plan, duration)
    }

    /** A cut past the end of the source collapses into the final segment rather than adding a stub. */
    @Test
    fun aCutPastTheEndCollapsesIntoTheLastSegment() {
        val duration = 65L * 60 * 1000 // 13 nominal segments
        val plan = Checkpoint.plan(duration) { if (it >= 12 * seg) duration + 5_000 else it }
        assertEquals(12, plan.size)
        assertEquals(11 * seg, plan.last().startMs)
        assertWellFormed(plan, duration)
    }

    /** The debug override is the only way to exercise multi-segment concat on a clip; keep it working. */
    @Test
    fun theDebugOverrideStillSegmentsAShortClip() {
        val plan = Checkpoint.plan(193_000L, forcedSegmentMs = 60_000L) { it + 41 }
        assertEquals(4, plan.size)
        assertEquals(listOf(0L, 60_041L, 120_041L, 180_041L), plan.map { it.startMs })
        assertWellFormed(plan, 193_000L)
    }
}
