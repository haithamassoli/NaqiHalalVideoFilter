package com.haithamassoli.naqi.analysis

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.haithamassoli.naqi.edl.FaceTrackEdl
import kotlin.math.abs

/**
 * M1 pass-1 face tracking. The caller feeds sampled upright frames at 10 fps via [onFrame]; this
 * runs ML Kit (bundled, fast, tracking ON), groups detections by tracking id into [FaceTrack]s, and
 * harvests a few frontal crops per track for gender voting. Each track is voted ([GenderVoter]) and
 * turned into a [FaceTrackEdl] *during* the pass; [finish] only drains whatever is still live.
 *
 * Coordinates: ML Kit `boundingBox` is pixels in the frame bitmap; every [NRect] here is normalized
 * to that bitmap (upright space — see [Contracts]). Timestamps are MILLIseconds throughout.
 *
 * **Retention (`long-film-plan.md` Phase 1).** A 155-min film produced 3 362 tracks holding 2 906 crop
 * bitmaps — ~500 MB — alive until a single end-of-pass `finish()`, which then spent 2.7 min running
 * every vote back-to-back with the progress bar frozen. Both follow from keeping tracks live for the
 * length of the film, so tracks now leave as soon as they are done:
 *
 * - **Crops full** ([MAX_CROPS]) ⇒ vote now and recycle the bitmaps. [MAX_CROPS] is all the evidence
 *   this track will ever get, so the verdict is already final; the track stays live because its
 *   `samples` are the EDL keyframes and still have to grow.
 * - **Unseen for [EVICT_AFTER_MS]** ⇒ the track is over: vote it if it never filled its crop budget,
 *   emit its EDL, drop it from the live map.
 *
 * Not thread-safe: [onFrame] must not overlap itself or [closeDetector] — the caller drives frames
 * sequentially on one background dispatcher (blocking `Tasks.await` is why no coroutines-play-services
 * dep is needed). Voting inside [onFrame] means [GenderVoter]'s NudeNet inference now runs on that
 * same dispatcher, which is what spreads the old `finish()` stall across the pass.
 */
class FaceTracker(
    private val context: Context,
    private val blurUnknownFaces: Boolean,
) {

    /** Live tracks keyed by ML Kit tracking id — only those still being seen, or not yet voted. */
    private val tracks = LinkedHashMap<Int, TrackState>()

    /** EDLs of tracks already voted and evicted, in eviction order. */
    private val emittedEdls = mutableListOf<FaceTrackEdl>()

    /** Lazily created so it survives across frames (tracking ids persist only within one detector). */
    private var detector: FaceDetector? = null

    /**
     * Retention counters for the soak log (`long-film-plan.md` wall #3). [trackCount] is CUMULATIVE so
     * it stays comparable to the 3 362 measured before eviction existed; [cropCount] is the PEAK live
     * crop count, which is the number that proves the fix — it should now be a handful, not 2 906.
     */
    var trackCount: Int = 0
        private set
    var cropCount: Int = 0
        private set

    /**
     * The retention line for the soak log. Read it whenever — including from a `finally` after a cancel
     * mid-pass, which is the only way these numbers ever surface on a multi-hour job that gets stopped.
     */
    fun retention(): String = "tracks=$trackCount peakLiveCrops=$cropCount liveTracks=${tracks.size}"

    suspend fun onFrame(bitmap: Bitmap, ptsMs: Long) {
        val faces = Tasks.await(detector().process(InputImage.fromBitmap(bitmap, 0)))
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        for (face in faces) {
            val id = face.trackingId ?: continue
            val box = face.boundingBox
            val frontal = abs(face.headEulerAngleY) <= FRONTAL_YAW_MAX && abs(face.headEulerAngleZ) <= FRONTAL_ROLL_MAX
            // A reused tracking id after eviction just starts a fresh track, which costs one extra vote
            // and yields two EDL spans covering their own samples — coverage is preserved either way.
            val state = tracks.getOrPut(id) { trackCount++; TrackState(id) }
            state.lastSeenMs = ptsMs
            state.track.samples += FaceSample(ptsMs, NRect(box.left / w, box.top / h, box.right / w, box.bottom / h), frontal)
            maybeStoreCrop(state, bitmap, box, ptsMs, frontal)
        }
        sweep(ptsMs)
    }

    /**
     * Vote the tracks that have all the evidence they will get, and evict the ones that are over.
     * O(live tracks) per frame, and eviction is exactly what keeps that count small — on the measured
     * film it is a handful, against the 3 362 a never-evicting map reached.
     */
    private suspend fun sweep(nowMs: Long) {
        val iter = tracks.entries.iterator()
        var liveCrops = 0
        while (iter.hasNext()) {
            val state = iter.next().value
            when (trackAction(state.voted, state.crops.size, state.lastSeenMs, nowMs)) {
                TrackAction.VOTE -> voteAndRecycle(state)
                TrackAction.EVICT -> {
                    voteAndRecycle(state) // no-op if already voted
                    emitIfCensored(state)
                    iter.remove()
                }
                TrackAction.WAIT -> liveCrops += state.crops.size
            }
        }
        if (liveCrops > cropCount) cropCount = liveCrops
    }

    /**
     * Vote this track and free its bitmaps. The verdict is final — no more crops will be stored.
     *
     * **Idempotent, and that guard is load-bearing.** A second call would vote an already-cleared crop
     * list; [GenderVoter.vote] returns UNKNOWN for "no votes", which would OVERWRITE a FEMALE verdict and
     * silently stop censoring that face. The guard lives here rather than at the call sites so no future
     * edit to [sweep] can reopen the only fail-open path in this class.
     */
    private suspend fun voteAndRecycle(state: TrackState) {
        if (state.voted) return
        state.track.gender = GenderVoter.vote(context, state.crops)
        state.crops.forEach { it.recycle() }
        state.crops.clear()
        state.voted = true
    }

    private fun emitIfCensored(state: TrackState) {
        edlFor(state.track, blurUnknownFaces)?.let { emittedEdls += it }
    }

    /** Vote and emit whatever is still live, then return every EDL the pass produced. */
    suspend fun finish(): List<FaceTrackEdl> {
        val iter = tracks.entries.iterator()
        while (iter.hasNext()) {
            val state = iter.next().value
            voteAndRecycle(state)
            emitIfCensored(state)
            iter.remove()
        }
        // Emission order is track-END order now that tracks leave as they finish, where M1 emitted in
        // first-detection order. Rendered pixels do not care (Edl.regionsAt unions the active rects and
        // CensorEffect re-sorts when it overflows), but sorting keeps Edl.toJson() diffable against the
        // M1-verified runs. Copy, so the returned Edl does not alias state this class keeps mutating.
        return emittedEdls.sortedBy { it.startMs }
    }

    /** Release the ML Kit detector. Idempotent. Crops are recycled as each track votes, not here. */
    fun closeDetector() {
        detector?.close()
        detector = null
    }

    private fun detector(): FaceDetector = detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    ).also { detector = it }

    /** Store up to [MAX_CROPS] frontal, large-enough, time-spread crops per track for gender voting. */
    private fun maybeStoreCrop(state: TrackState, bitmap: Bitmap, box: Rect, ptsMs: Long, frontal: Boolean) {
        // `voted` is the important half of this gate: voting CLEARS the crop list, so without it a track
        // that stays on screen after its vote refills with up to MAX_CROPS more bitmaps that nothing will
        // ever vote or recycle — held for the rest of the track and counted into [cropCount], the one
        // metric this whole item exists to move.
        if (state.voted || !frontal || state.crops.size >= MAX_CROPS) return
        if (minOf(box.width(), box.height()) < MIN_FACE_PX) return
        // Spread crops across the track instead of clustering at its start (first crop always stored).
        if (state.crops.isNotEmpty() && ptsMs - state.lastCropMs < CROP_SPREAD_MS) return
        // Expand 50% each side (⇒ ~2x the box) — context helps NudeNet — then clamp to the bitmap.
        val left = (box.left - box.width() / 2).coerceIn(0, bitmap.width)
        val top = (box.top - box.height() / 2).coerceIn(0, bitmap.height)
        val right = (box.right + box.width() / 2).coerceIn(0, bitmap.width)
        val bottom = (box.bottom + box.height() / 2).coerceIn(0, bitmap.height)
        if (right - left <= 0 || bottom - top <= 0) return
        val region = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        // createBitmap can hand back the source itself for a full-frame subset; copy so the
        // soon-to-be-recycled sampler bitmap can't pull our pixels out from under us.
        state.crops += if (region === bitmap) region.copy(region.config ?: Bitmap.Config.ARGB_8888, false) else region
        state.lastCropMs = ptsMs
    }

    private class TrackState(id: Int) {
        val track = FaceTrack(id)
        val crops = mutableListOf<Bitmap>()
        var lastCropMs = 0L // only read once crops is non-empty
        var lastSeenMs = 0L // set on every frame this track appears in
        var voted = false   // crops have been voted AND recycled; never vote twice
    }

    private companion object {
        const val FRONTAL_YAW_MAX = 20f
        const val FRONTAL_ROLL_MAX = 25f
        const val MIN_FACE_PX = 48 // short side, in the sampled bitmap
        const val CROP_SPREAD_MS = 700L
    }
}

/** Crops harvested per track before its gender vote is final. */
private const val MAX_CROPS = 5

/**
 * Source-time gap after which an unseen track is considered over. 2 s = 20 sampled frames at 10 fps:
 * long enough that a blink or a brief occlusion does not split one face into two tracks (a split
 * fragment can collect too few crops to vote, and an UNKNOWN vote is a face left uncensored), short
 * enough that the live map stays a handful of tracks on a feature-length film.
 *
 * ponytail: one fixed gap for every video. A shot-length-aware value would split fewer tracks on
 * fast-cut content — worth doing only if QA ever shows split tracks losing their vote.
 */
private const val EVICT_AFTER_MS = 2_000L

/** Half the 100 ms (10 fps) sample gap, so between-sample frames stay covered at a span's edges. */
private const val SPAN_PAD_MS = 50L

/** 25% per side ⇒ 1.5x each dimension (PRD: pad 25%). */
private const val KEYFRAME_PAD = 0.25f

/**
 * The censor decision and span construction for one voted track, or null when it must not be censored.
 * Pure (no Android, no ML Kit) so the logic that moved out of the old end-of-pass `finish()` is
 * unit-testable — see `FaceTrackerLogicTest`.
 */
internal fun edlFor(track: FaceTrack, blurUnknownFaces: Boolean): FaceTrackEdl? {
    val censor = track.gender == Gender.FEMALE || (track.gender == Gender.UNKNOWN && blurUnknownFaces)
    if (!censor || track.samples.isEmpty()) return null
    return FaceTrackEdl(
        startMs = (track.samples.first().ptsMs - SPAN_PAD_MS).coerceAtLeast(0L),
        endMs = track.samples.last().ptsMs + SPAN_PAD_MS,
        keyframes = track.samples.map { it.ptsMs to padRect(it.rect) },
    )
}

/** True once [nowMs] is [EVICT_AFTER_MS] past the last frame a track appeared in. */
internal fun isStale(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs >= EVICT_AFTER_MS

/** What the per-frame sweep should do with one live track. */
internal enum class TrackAction { WAIT, VOTE, EVICT }

/**
 * The two-trigger decision, pure so it is testable without ML Kit or a Context. Order matters: EVICT
 * outranks VOTE, because an evicting track still needs its vote and the evict branch votes anyway.
 */
internal fun trackAction(voted: Boolean, cropCount: Int, lastSeenMs: Long, nowMs: Long): TrackAction = when {
    isStale(lastSeenMs, nowMs) -> TrackAction.EVICT
    !voted && cropCount >= MAX_CROPS -> TrackAction.VOTE
    else -> TrackAction.WAIT
}

/** Pad a sample rect to 1.5x each dimension (25% per side) and clamp to [0,1] for the EDL keyframe. */
internal fun padRect(r: NRect): NRect {
    val dx = r.width * KEYFRAME_PAD
    val dy = r.height * KEYFRAME_PAD
    return NRect(
        (r.left - dx).coerceIn(0f, 1f),
        (r.top - dy).coerceIn(0f, 1f),
        (r.right + dx).coerceIn(0f, 1f),
        (r.bottom + dy).coerceIn(0f, 1f),
    )
}
