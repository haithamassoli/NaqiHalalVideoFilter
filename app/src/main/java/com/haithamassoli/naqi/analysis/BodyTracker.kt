package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.model.FilterOps
import kotlin.math.max
import kotlin.math.min

/** Person-level votes survive a disappearing face. Uncertain associations start an unknown track. */
internal class BodyTracker(private val who: String) {
    private val live = mutableListOf<FaceTrack>()
    private val emitted = mutableListOf<FaceTrackEdl>()
    private var nextId = 0
    private var sparedCount = 0
    val fallbackIntervals = mutableListOf<LongRange>()
    var frameCount = 0
        private set
    var detectionCount = 0
        private set
    var cutCount = 0
        private set

    fun onFrame(
        people: List<NRect>, faces: List<NRect>, width: Int, height: Int, ptsMs: Long,
        sceneCut: Boolean = false, vote: ((NRect) -> Int)? = null,
    ) {
        frameCount++
        detectionCount += people.size
        if (sceneCut) { live.forEach { emit(it, tailMs = 0) }; live.clear(); cutCount++ }
        live.removeAll { tr ->
            if (ptsMs - tr.samples.last().ptsMs > HOLD_MS) { emit(tr); true } else false
        }
        val old = live.toList()
        val used = mutableSetOf<FaceTrack>()
        val scores = people.map { box -> old.map { overlap(box, it.samples.last().rect) } }
        for ((index, box) in people.withIndex()) {
            val ranked = old.indices.sortedByDescending { scores[index][it] }
            val best = ranked.firstOrNull()
            val score = best?.let { scores[index][it] } ?: 0f
            val runnerUp = ranked.getOrNull(1)?.let { scores[index][it] } ?: 0f
            val competing = best?.let { j -> scores.indices.filter { it != index }.maxOfOrNull { scores[it][j] } } ?: 0f
            // ponytail: IoU with reciprocal ambiguity guards, no re-identification. Crossings restart evidence and cover unknown people.
            val matched = best?.takeIf { score >= 0.25f && score - runnerUp >= 0.10f && score - competing >= 0.10f }
                ?.let { old[it] }?.takeIf { it !in used }
            val track = matched ?: FaceTrack(nextId++).also { live += it }
            used += track
            track.samples += FaceSample(ptsMs, box)
            if (vote == null || track.votesTried >= VOTE_CAP) continue
            val associated = faces.filter { face ->
                faceInside(face, box) && people.count { faceInside(face, it) } == 1
            }
            if (associated.size != 1) continue
            val face = associated.single()
            val px = max(face.width * width, face.height * height).toInt()
            if (px < MIN_FACE_PX || (track.votesTried > 0 && ptsMs - track.lastVoteMs < 100)) continue
            track.classifiedPx = max(track.classifiedPx, px)
            track.lastVoteMs = ptsMs
            track.votesTried++
            when (vote(face)) { 1 -> track.maleVotes++; -1 -> track.femaleVotes++ }
        }
        // A replacement/ambiguous box already covers this location. Retaining the old box would
        // manufacture competing candidates on the next frame and eventually overflow the renderer.
        for (track in old) {
            if (track !in used && people.any { overlap(it, track.samples.last().rect) > 0.1f }) {
                emit(track, tailMs = 0)
                live.remove(track)
            }
        }
        // A detected face with no reliable body must never disappear from a body-mode result.
        if (faces.any { face -> people.none { faceInside(face, it) } }) {
            fallbackIntervals += (ptsMs - 50).coerceAtLeast(0)..(ptsMs + 50)
        }
    }

    fun finish(): List<FaceTrackEdl> {
        live.forEach { emit(it) }
        live.clear()
        return emitted.sortedBy { it.startMs }
    }

    fun retention() = "bodyFrames=$frameCount people=$detectionCount tracks=$nextId live=${live.size} cuts=$cutCount fallback=${fallbackIntervals.size} spared=$sparedCount"

    private fun emit(track: FaceTrack, tailMs: Long = HOLD_MS) {
        val spareVotes = when (who) {
            FilterOps.WOMEN -> track.maleVotes
            FilterOps.MEN -> track.femaleVotes
            else -> 2
        }
        // A single misread face must not expose an entire body for the rest of its track.
        if (!shouldCensor(track.femaleVotes, track.maleVotes, who) && spareVotes >= 2) { sparedCount++; return }
        emitted += FaceTrackEdl(
            track.samples.first().ptsMs.coerceAtLeast(0),
            track.samples.last().ptsMs + tailMs,
            track.samples.map { it.ptsMs to padRect(it.rect) },
        )
    }

    companion object {
        private const val HOLD_MS = 300L
        private fun area(r: NRect) = max(0f, r.width) * max(0f, r.height)
        private fun intersection(a: NRect, b: NRect) = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
            max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        internal fun overlap(a: NRect, b: NRect): Float {
            val common = intersection(a, b)
            return common / (area(a) + area(b) - common).coerceAtLeast(1e-6f)
        }
        private fun faceInside(face: NRect, body: NRect): Boolean =
            area(face) > 0 && intersection(face, body) / area(face) >= 0.8f &&
                (face.top + face.bottom) / 2 < body.top + body.height * 0.65f
    }
}
