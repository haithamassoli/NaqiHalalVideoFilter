package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.BodyTracker
import com.haithamassoli.naqi.analysis.FaceSample
import com.haithamassoli.naqi.analysis.FaceTrack
import com.haithamassoli.naqi.analysis.MIN_FACE_PX
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.analysis.VOTE_CAP
import com.haithamassoli.naqi.analysis.edlFor
import com.haithamassoli.naqi.analysis.isStale
import com.haithamassoli.naqi.edl.FaceTrackEdl

/**
 * Android's `FaceTracker.onFaces`/`sweep`/`finish` with ML Kit's tracking id replaced by IoU
 * association, because YuNet has none. Everything after "which track is this face" is Android's, rule
 * for rule: the raw upright rect is the sample, the vote guards run cheapest-first (cap, size floor,
 * bigger than anything already classified), eviction is core's [isStale], and each track's verdict is read
 * once, at eviction, through core's [edlFor] (pad, ±50 ms, censor on tie or abstention).
 *
 * Pure (no ORT, no frames), so `FaceTracksTest` can drive it with fixed boxes and a fake voter.
 */
internal class FaceTracks(private val who: String) {
    private val live = ArrayList<FaceTrack>()
    private val emitted = ArrayList<FaceTrackEdl>()
    private var nextId = 0

    /**
     * [faces] are raw boxes normalized to the [uprightW] × [uprightH] analysis frame. [vote] classifies
     * one of them (+1 male, -1 female, 0 abstain), or is null when no vote is wanted (Everyone/Off, or no
     * genderage model), which costs nothing.
     */
    fun onFrame(faces: List<NRect>, uprightW: Int, uprightH: Int, ptsMs: Long, vote: ((NRect) -> Int)?) {
        val assigned = associate(faces, ptsMs)
        for ((i, rect) in faces.withIndex()) {
            val track = assigned[i] ?: FaceTrack(nextId++).also { live += it }
            track.samples += FaceSample(ptsMs, rect)
            if (vote == null) continue
            if (track.votesTried >= VOTE_CAP) continue
            val px = maxOf(rect.width * uprightW, rect.height * uprightH).toInt()  // unpadded, upright px
            if (px < MIN_FACE_PX) continue      // too small to read: no vote, which already means censor
            if (px <= track.classifiedPx) continue  // spend the 5 on the biggest crops
            track.votesTried++
            track.classifiedPx = px
            when (vote(rect)) {
                1 -> track.maleVotes++
                -1 -> track.femaleVotes++
            }
        }
        val iter = live.iterator()
        while (iter.hasNext()) {
            val track = iter.next()
            if (isStale(track.samples.last().ptsMs, ptsMs)) {
                edlFor(track, who)?.let { emitted += it }
                iter.remove()
            }
        }
    }

    /** Emit whatever is still live, then every censored span of the pass, by start. */
    fun finish(): List<FaceTrackEdl> {
        live.forEach { track -> edlFor(track, who)?.let { emitted += it } }
        live.clear()
        return emitted.sortedBy { it.startMs }
    }

    /**
     * One-to-one greedy matching, highest IoU first, of [faces] onto live tracks seen within
     * [MATCH_GAP_MS]. Unmatched faces start new tracks.
     *
     * Erring toward a split is deliberate. A split track costs nothing (both halves are censored or judged
     * on their own votes), while a wrong merge pools two people's votes, and in Women/Men mode that can
     * spare someone who should be covered. Hence a strict-ish IoU and a short gap: a face that vanished
     * longer than that, or jumped, starts over, like a lost ML Kit id does.
     */
    private fun associate(faces: List<NRect>, ptsMs: Long): Array<FaceTrack?> {
        val pairs = ArrayList<Triple<Float, FaceTrack, Int>>()
        for (track in live) {
            val last = track.samples.last()
            if (ptsMs - last.ptsMs > MATCH_GAP_MS) continue
            for ((i, rect) in faces.withIndex()) {
                val iou = BodyTracker.overlap(last.rect, rect)
                if (iou >= MATCH_IOU) pairs += Triple(iou, track, i)
            }
        }
        pairs.sortByDescending { it.first }
        val assigned = arrayOfNulls<FaceTrack>(faces.size)
        val taken = HashSet<FaceTrack>()
        for ((_, track, i) in pairs) {
            if (assigned[i] == null && track !in taken) {
                assigned[i] = track
                taken += track
            }
        }
        return assigned
    }

    companion object {
        /** The usual tracking-by-detection bar; at 10 fps a moving face still overlaps itself well past it. */
        const val MATCH_IOU = 0.3f

        /** Bridges up to four missed samples at 10 fps (a blink, a detector miss), not a cut-away. */
        const val MATCH_GAP_MS = 500L
    }
}
