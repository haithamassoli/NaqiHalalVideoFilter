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
 * harvests a few frontal crops per track for gender voting. [finish] then votes each track
 * ([GenderVoter]) and emits a [FaceTrackEdl] per track that must be censored.
 *
 * Coordinates: ML Kit `boundingBox` is pixels in the frame bitmap; every [NRect] here is normalized
 * to that bitmap (upright space — see [Contracts]). Timestamps are MILLIseconds throughout.
 *
 * Not thread-safe: [onFrame] must not overlap itself or [closeDetector] — the caller drives frames
 * sequentially on one background dispatcher (blocking `Tasks.await` is why no coroutines-play-services
 * dep is needed).
 */
class FaceTracker(private val blurUnknownFaces: Boolean) : AutoCloseable {

    /** Track state keyed by ML Kit tracking id; insertion order = EDL emission order. */
    private val tracks = LinkedHashMap<Int, TrackState>()

    /** Lazily created so it survives across frames (tracking ids persist only within one detector). */
    private var detector: FaceDetector? = null

    suspend fun onFrame(bitmap: Bitmap, ptsMs: Long) {
        val faces = Tasks.await(detector().process(InputImage.fromBitmap(bitmap, 0)))
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        for (face in faces) {
            val id = face.trackingId ?: continue
            val box = face.boundingBox
            val frontal = abs(face.headEulerAngleY) <= FRONTAL_YAW_MAX && abs(face.headEulerAngleZ) <= FRONTAL_ROLL_MAX
            val state = tracks.getOrPut(id) { TrackState(id) }
            state.track.samples += FaceSample(ptsMs, NRect(box.left / w, box.top / h, box.right / w, box.bottom / h), frontal)
            maybeStoreCrop(state, bitmap, box, ptsMs, frontal)
        }
    }

    suspend fun finish(context: Context): List<FaceTrackEdl> {
        val edls = mutableListOf<FaceTrackEdl>()
        for (state in tracks.values) {
            val track = state.track
            track.gender = GenderVoter.vote(context, state.crops)
            state.crops.forEach { it.recycle() }
            state.crops.clear()
            val censor = track.gender == Gender.FEMALE || (track.gender == Gender.UNKNOWN && blurUnknownFaces)
            if (!censor) continue
            edls += FaceTrackEdl(
                // Pad the span by half the 100 ms sample gap so between-sample frames stay covered.
                startMs = (track.samples.first().ptsMs - SPAN_PAD_MS).coerceAtLeast(0L),
                endMs = track.samples.last().ptsMs + SPAN_PAD_MS,
                keyframes = track.samples.map { it.ptsMs to pad(it.rect) },
            )
        }
        return edls
    }

    /** Release the ML Kit detector. Idempotent. Crops are recycled by [finish], not here. */
    fun closeDetector() {
        detector?.close()
        detector = null
    }

    /** [AutoCloseable] delegates to [closeDetector] so the tracker fits a `use { }` block. */
    override fun close() = closeDetector()

    private fun detector(): FaceDetector = detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    ).also { detector = it }

    /** Store up to [MAX_CROPS] frontal, large-enough, time-spread crops per track for gender voting. */
    private fun maybeStoreCrop(state: TrackState, bitmap: Bitmap, box: Rect, ptsMs: Long, frontal: Boolean) {
        if (!frontal || state.crops.size >= MAX_CROPS) return
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

    /** Pad a sample rect to 1.5x each dimension (25% per side) and clamp to [0,1] for the EDL keyframe. */
    private fun pad(r: NRect): NRect {
        val dx = r.width * KEYFRAME_PAD
        val dy = r.height * KEYFRAME_PAD
        return NRect(
            (r.left - dx).coerceIn(0f, 1f),
            (r.top - dy).coerceIn(0f, 1f),
            (r.right + dx).coerceIn(0f, 1f),
            (r.bottom + dy).coerceIn(0f, 1f),
        )
    }

    private class TrackState(id: Int) {
        val track = FaceTrack(id)
        val crops = mutableListOf<Bitmap>()
        var lastCropMs = 0L // only read once crops is non-empty
    }

    private companion object {
        const val FRONTAL_YAW_MAX = 20f
        const val FRONTAL_ROLL_MAX = 25f
        const val MAX_CROPS = 5
        const val MIN_FACE_PX = 48 // short side, in the sampled bitmap
        const val CROP_SPREAD_MS = 700L
        const val SPAN_PAD_MS = 50L // half the 100 ms (10 fps) sample gap
        const val KEYFRAME_PAD = 0.25f // 25% per side ⇒ 1.5x each dimension (PRD: pad 25%)
    }
}
