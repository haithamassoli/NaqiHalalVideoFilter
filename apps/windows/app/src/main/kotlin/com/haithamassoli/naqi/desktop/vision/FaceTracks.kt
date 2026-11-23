package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.BodyTracker
import com.haithamassoli.naqi.analysis.FaceSample
import com.haithamassoli.naqi.analysis.FaceTrack
import com.haithamassoli.naqi.analysis.MIN_FACE_PX
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.analysis.VOTE_CAP
import com.haithamassoli.naqi.analysis.edlFor
import com.haithamassoli.naqi.analysis.isStale
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.model.FilterOps
import kotlin.math.abs

/**
 * Android's `FaceTracker.onFaces`/`sweep`/`finish` with ML Kit's tracking id replaced by IoU
 * association, because YuNet has none. Everything after "which track is this face" is Android's, rule
 * for rule: the raw upright rect is the sample, the vote guards run cheapest-first (cap, size floor,
 * bigger than anything already classified), eviction is core's [isStale], and each track's verdict is read
 * once, at eviction, through core's [edlFor] (pad, ±50 ms, censor on tie or abstention).
 *
 * Two rules are Windows-only, because IoU is weaker evidence of identity than ML Kit's tracking id and a
 * wrong merge fails OPEN (one person's spare covers another): a shot cut ([cut]) ends every live track,
 * and a track that would be spared re-checks each readable face matched to it ([CHECK] below).
 *
 * Pure (no ORT, no frames), so `FaceTracksTest` can drive it with fixed boxes and a fake voter.
 */
internal class FaceTracks(private val who: String) {
    private val live = ArrayList<FaceTrack>()
    private val emitted = ArrayList<FaceTrackEdl>()
    private var nextId = 0

    /** Tracks a matched face voted against while they were spared: censored whatever the majority says. */
    private val contested = HashSet<FaceTrack>()

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
            if (vote == null || track in contested) continue  // contested is already censored: nothing to learn
            val px = maxOf(rect.width * uprightW, rect.height * uprightH).toInt()  // unpadded, upright px
            if (px < MIN_FACE_PX) continue      // too small to read: no vote, which already means censor
            // Android's guards: at most VOTE_CAP votes, spent on the biggest crops.
            val counted = track.votesTried < VOTE_CAP && px > track.classifiedPx
            // CHECK: a spared track never carries its spare onto a readable face without that face's own
            // vote, because IoU cannot tell a smaller face in the same place from the same face. If the face
            // votes against the spare, the track is censored (a wrong merge and a wrong vote look the same,
            // and either way covering is the safe side). Costs one crop per readable frame of a spared face.
            val spared = spared(track)
            if (!counted && !spared) continue
            val v = vote(rect)
            if (counted) {
                track.votesTried++
                track.classifiedPx = px
                when (v) {
                    1 -> track.maleVotes++
                    -1 -> track.femaleVotes++
                }
            }
            if (spared && v == against) contested += track
        }
        val iter = live.iterator()
        while (iter.hasNext()) {
            val track = iter.next()
            if (isStale(track.samples.last().ptsMs, ptsMs)) {
                emit(track)
                iter.remove()
            }
        }
    }

    /**
     * A shot cut between the previous frame and the next: end every live track now, so nobody in the new
     * shot is matched onto (and spared by) a track from the old one. Same as BodyTracker's `sceneCut`.
     */
    fun cut() {
        live.forEach(::emit)
        live.clear()
    }

    /** Emit whatever is still live, then every censored span of the pass, by start. */
    fun finish(): List<FaceTrackEdl> {
        cut()
        return emitted.sortedBy { it.startMs }
    }

    private fun emit(track: FaceTrack) {
        edlFor(track, if (track in contested) FilterOps.EVERYONE else who)?.let { emitted += it }
        contested -= track
    }

    /** What core's `shouldCensor` (internal there) would leave uncovered right now. */
    private fun spared(t: FaceTrack): Boolean = when (who) {
        FilterOps.WOMEN -> t.maleVotes > t.femaleVotes
        FilterOps.MEN -> t.femaleVotes > t.maleVotes
        else -> false
    }

    /** The vote that contradicts a spare: female in Women mode, male in Men mode. */
    private val against = when (who) {
        FilterOps.WOMEN -> -1
        FilterOps.MEN -> 1
        else -> 0
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
                if (iou >= MATCH_IOU && similarSize(last.rect, rect)) pairs += Triple(iou, track, i)
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

        /**
         * Largest change of either side between matched boxes. IoU alone lets a box 1.6x the size match a
         * concentric one; a face does not grow or shrink that much within [MATCH_GAP_MS], a different
         * person nearer or further from the camera does.
         */
        const val MAX_SIDE_RATIO = 1.5f

        private fun similarSize(a: NRect, b: NRect): Boolean =
            ratio(a.width, b.width) <= MAX_SIDE_RATIO && ratio(a.height, b.height) <= MAX_SIDE_RATIO

        private fun ratio(x: Float, y: Float): Float = if (x <= 0f || y <= 0f) Float.MAX_VALUE else maxOf(x / y, y / x)
    }
}

/**
 * Android's coarse shot-cut test (`PersonDetector.detect`): a 16x16 grid of mean-of-RGB samples, /255, of
 * each upright analysis frame, and a cut when the mean absolute change from the previous frame's grid is
 * over [THRESHOLD]. Gradual transitions can slip past it, which is why [FaceTracks] also re-checks spares.
 */
internal class SceneCuts {
    private var previous: FloatArray? = null

    fun isCut(frame: RgbFrame): Boolean {
        val signature = FloatArray(GRID * GRID)
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val s = 3 * ((y * frame.height / GRID) * frame.width + x * frame.width / GRID)
            signature[y * GRID + x] =
                ((frame.rgb[s].toInt() and 0xFF) + (frame.rgb[s + 1].toInt() and 0xFF) + (frame.rgb[s + 2].toInt() and 0xFF)) / 3f / 255f
        }
        val cut = previous?.let { old -> signature.indices.sumOf { abs(signature[it] - old[it]).toDouble() } / signature.size > THRESHOLD } ?: false
        previous = signature
        return cut
    }

    private companion object {
        const val GRID = 16
        const val THRESHOLD = 0.20
    }
}
