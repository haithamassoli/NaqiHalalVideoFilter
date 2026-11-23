package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.edl.BodyMask
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
    /** The previous sample. At 10 fps anything that first shows up now may have been on screen since then. */
    private var lastPtsMs: Long? = null
    private var shotStartMs = 0L
    val fallbackIntervals = mutableListOf<LongRange>()
    var frameCount = 0
        private set
    var detectionCount = 0
        private set
    var cutCount = 0
        private set

    /** [masks], when given, parallels [people] (precise body mode). */
    fun onFrame(
        people: List<NRect>, faces: List<NRect>, width: Int, height: Int, ptsMs: Long,
        sceneCut: Boolean = false, vote: ((NRect) -> Int)? = null, masks: List<BodyMask?> = emptyList(),
    ) {
        frameCount++
        detectionCount += people.size
        // A cut happened somewhere after the previous sample, so the new shot starts just after it (iOS
        // `PersonTracker` shotStart = lastPtsMs + 1) and the old shot's tracks keep their last box up to
        // this sample. Both shots' boxes overlap for those frames rather than leaving either uncovered.
        val since = lastPtsMs?.let { it + 1 } ?: ptsMs.also { shotStartMs = it }
        lastPtsMs = ptsMs
        if (sceneCut) { live.forEach { emit(it, endMs = ptsMs - 1) }; live.clear(); cutCount++; shotStartMs = since }
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
            // Detection can settle a few samples after a person appears, especially small people on a
            // screen, so a new span reaches back LEAD_MS within its shot (iOS PersonTracker lead-in), or
            // to the cut itself early in a shot. A box replacing a nearby track only needs the frames
            // since the previous sample: that track covered the place until then, and reaching further
            // back stacks ghosts (finding 1).
            val fresh = old.none { overlap(box, it.samples.last().rect) > 0.1f }
            val track = matched ?: FaceTrack(nextId++).also {
                it.startMs = if (fresh) leadIn(ptsMs, max(shotStartMs, ptsMs - LEAD_MS)) else since
                live += it
            }
            used += track
            track.samples += FaceSample(ptsMs, box, masks.getOrNull(index))
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
                emit(track, endMs = ptsMs - 1)
                live.remove(track)
            }
        }
        // A detected face with no reliable body must never disappear from a body-mode result. From the
        // previous sample and held like a body box: at 10 fps small faces (a comment avatar, a person on
        // a camera screen) are found on fewer samples than every-frame detection found them on.
        if (faces.any { face -> people.none { faceInside(face, it) } }) {
            fallbackIntervals += leadIn(ptsMs, since).coerceAtLeast(0)..(ptsMs + HOLD_MS)
        }
    }

    /**
     * Someone first found within [SETTLE_MS] of a cut was most likely there from the cut: the
     * regression clip's camera display shows a person from 7.33 s, and neither model finds them before
     * ~8.1 s at 10 fps. ponytail: a fixed window; a person who really walks in during it is covered early.
     */
    private fun leadIn(ptsMs: Long, otherwise: Long) =
        if (ptsMs - shotStartMs <= SETTLE_MS) min(shotStartMs, otherwise) else otherwise

    fun finish(): List<FaceTrackEdl> {
        live.forEach { emit(it) }
        live.clear()
        return emitted.sortedBy { it.startMs }
    }

    fun retention() = "bodyFrames=$frameCount people=$detectionCount tracks=$nextId live=${live.size} cuts=$cutCount fallback=${fallbackIntervals.size} spared=$sparedCount"

    private fun emit(track: FaceTrack, endMs: Long = track.samples.last().ptsMs + HOLD_MS) {
        val spareVotes = when (who) {
            FilterOps.WOMEN -> track.maleVotes
            FilterOps.MEN -> track.femaleVotes
            else -> 2
        }
        // A single misread face must not expose an entire body for the rest of its track.
        if (!shouldCensor(track.femaleVotes, track.maleVotes, who) && spareVotes >= 2) { sparedCount++; return }
        emitted += FaceTrackEdl(
            track.startMs.coerceAtLeast(0),
            max(endMs, track.samples.last().ptsMs),
            track.samples.map { it.ptsMs to padRect(it.rect) },
            union = true,
            masks = if (track.samples.any { it.mask != null }) track.samples.map { it.mask } else null,
        )
    }

    companion object {
        /**
         * A missed person keeps their last box this long. Every-frame detection held 300 ms, i.e. nine
         * missed frames; at 10 fps the same nine misses are 900 ms. The regression clip's small people
         * on a camera display drop out of detection for that long and stayed visible with 300.
         */
        private const val HOLD_MS = 900L
        private const val LEAD_MS = 300L
        private const val SETTLE_MS = 1_000L
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
