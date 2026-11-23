package com.haithamassoli.naqi.analysis

import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.model.FilterOps

/**
 * M1 pass-1 face tracking. The caller feeds sampled frames at 10 fps — [detect] starts ML Kit
 * (bundled, fast, tracking ON) and [onFaces] consumes what it found — grouping detections into
 * [FaceTrack]s and turning each into a [FaceTrackEdl] as it ends; [finish] only drains whatever is
 * still live.
 *
 * **Which faces get censored is [who]** (plan-censor-who §4). Under [FilterOps.EVERYONE] — the only
 * mode that shipped between plan-v2 §5.4 and now — every detected face is censored and this class is
 * byte for byte what "V4" did. Under [FilterOps.WOMEN]/[FilterOps.MEN] each track collects a gender
 * vote from [classifier] and [shouldCensor] reads it at eviction.
 *
 * The repo failed this task once: the NudeNet vote was AGPL-3.0 inside a closed-source APK, it held
 * ~500 MB of crop bitmaps through the pass, and it did not do what it claimed — `m0-spikes.md:35`
 * records `FACE_FEMALE` firing 0.69–0.83 on male portraits while `FACE_MALE` stayed ≤0.07, so it
 * already censored approximately every face. What changed: the model is InsightFace `genderage.onnx`,
 * trained to discriminate rather than to detect nudity, and it had to clear plan-censor-who §3.3's
 * measured bar (balanced accuracy AND how many men stay visible) before this wiring was allowed to
 * exist; and the verdict is four ints per live track ([FaceTrack]), never a retained crop.
 *
 * [classifier] is null for [FilterOps.EVERYONE]/[FilterOps.NONE], and null costs literally nothing on
 * that path (§4.1) — no crop, no tensor, no ORT call — so no existing user pays for this feature.
 *
 * Coordinates: ML Kit `boundingBox` is pixels in the UPRIGHT frame (ML Kit rotates the sampler's
 * unrotated buffer itself), so [onFaces] normalizes by the upright width/height the sampler reports —
 * see [FrameSampler.uprightSize]. Every [NRect] here lives in that one space ([Contracts]).
 * Timestamps are MILLIseconds throughout.
 *
 * **Retention (`long-film-plan.md` Phase 1).** Tracks leave as soon as they are over — unseen for
 * [EVICT_AFTER_MS] ⇒ emit the EDL span, drop it from the live map — so a 155-min film holds a handful
 * of live tracks instead of the 3 362 a never-evicting map reached.
 *
 * Not thread-safe: one frame's [detect]→[onFaces] must complete before the next frame's starts, and
 * neither may overlap [closeDetector] — the caller drives frames sequentially on one background
 * dispatcher and blocks there on `Tasks.await` (which is why no coroutines-play-services dep is
 * needed).
 */
class FaceTracker(
    private val classifier: FaceGenderVoter? = null,
    private val who: String = FilterOps.EVERYONE,
    private val coverBody: Boolean = false,
    /** With [coverBody]: silhouettes instead of rectangles (precise body mode). */
    private val precise: Boolean = false,
) {
    private val body = if (coverBody) BodyTracker(who) else null
    private var people: PersonDetector? = null
    internal val bodyFallback: List<LongRange> get() = body?.fallbackIntervals ?: emptyList()

    /** Live tracks keyed by tracking id — only those still being seen. */
    private val tracks = LinkedHashMap<Int, FaceTrack>()

    /** EDLs of tracks already evicted, in eviction order. */
    private val emittedEdls = mutableListOf<FaceTrackEdl>()

    /** Lazily created so it survives across frames (tracking ids persist only within one detector). */
    private var detector: FaceDetector? = null

    /**
     * Ids for detections ML Kit gave no tracking id to, counting DOWN from -1 so they can never
     * collide with a real ML Kit id (which are positive and monotonically increasing).
     */
    private var nextSyntheticId = -1

    /**
     * Retention + sizing counters for the soak log. [trackCount] is CUMULATIVE so it stays comparable
     * to the 3 362 measured before eviction existed. [faceCount]/[untrackedCount] are plan-v2 §7.1's
     * "size it first": how many raw detections the pass saw, and how many of them ML Kit could not
     * assign a tracking id to — the ones the old `?: continue` silently never censored. That number
     * has never been measured; it is expected to spike at scene cuts and fast pans.
     */
    var trackCount: Int = 0
        private set
    var faceCount: Int = 0
        private set
    var untrackedCount: Int = 0
        private set

    /**
     * Tracks left UNcensored by the vote — plan-censor-who §3.3's "men left visible in Women mode",
     * which is the number that decides whether the feature ships. Zero unless [classifier] is set, and
     * the only vote statistic counted here: crops, abstentions and per-crop cost are the classifier
     * side's to count, and `FilterWorker.logVotes` already does (it alone spans every segment).
     */
    private var sparedCount = 0

    /**
     * The retention line for the soak log. Read it whenever — including from a `finally` after a cancel
     * mid-pass, which is the only way these numbers ever surface on a multi-hour job that gets stopped.
     * Well under logcat's ~4 kB line cap: it is a fixed set of integers.
     */
    fun retention(): String =
        body?.retention() ?: "tracks=$trackCount faces=$faceCount untracked=$untrackedCount liveTracks=${tracks.size} spared=$sparedCount"

    /**
     * Start ML Kit and hand the Task back un-awaited (perf-plan 1.3a): it runs on ML Kit's own executor,
     * so the caller gets the NSFW gate for free by awaiting only after running it. The caller must await
     * before [image]'s backing buffer is reused — ML Kit reads it until the Task completes, and
     * [FrameSampler] hands out ring buffers that are valid for one callback only.
     */
    fun detect(image: InputImage): Task<List<Face>> = detector().process(image)

    /**
     * Group [faces] into tracks, then sweep. [uprightW]/[uprightH] are the frame dimensions ML Kit
     * reported boxes against — NOT the buffer's own, which are the swapped pair for a 90/270 source.
     * [image] is only forwarded to [classifier] (it is the NV21 the crop is cut from) and is untouched
     * when there is none; it must still be valid here, i.e. call this before the sampler reuses the
     * ring buffer — which is already true, since ML Kit's own Task has just been awaited.
     */
    fun onFaces(faces: List<Face>, image: InputImage, uprightW: Int, uprightH: Int, ptsMs: Long) {
        val w = uprightW.toFloat()
        val h = uprightH.toFloat()
        faceCount += faces.size
        if (body != null) {
            val frame = requireNotNull(people).detect(image, uprightW, uprightH)
            body.onFrame(frame.people.map { it.box }, faces.map { face ->
                val b = face.boundingBox
                NRect(b.left / w, b.top / h, b.right / w, b.bottom / h)
            }, uprightW, uprightH, ptsMs, frame.sceneCut,
                classifier?.let { voter -> { rect -> voter.vote(image, uprightW, uprightH, rect) } },
                frame.people.map { it.mask })
            return
        }
        for (face in faces) {
            // plan-v2 §7.1: this was `val id = face.trackingId ?: continue`, which silently DROPPED any
            // detection ML Kit found but did not assign a tracking id to — never blurred, and the failure
            // is content-correlated (ML Kit's tracker is motion-based with no re-identification, so ids
            // are least likely to be populated exactly at scene cuts and fast pans, and we sample 10 fps
            // out of 23.976 which is ~2.4x the per-step motion it was built for). An untracked face now
            // becomes its own one-frame track and is censored like any other; normal eviction closes it.
            val id = face.trackingId ?: run { untrackedCount++; nextSyntheticId-- }
            val box = face.boundingBox
            // A reused tracking id after eviction just starts a fresh track, which yields two EDL spans
            // covering their own samples — coverage is preserved either way.
            val track = tracks.getOrPut(id) { trackCount++; FaceTrack(id) }
            val rect = NRect(box.left / w, box.top / h, box.right / w, box.bottom / h)
            track.samples += FaceSample(ptsMs, rect)

            // Guards cheapest-first: the null check skips the whole feature for everyone/none without
            // touching the track; the cap is one int compare and stops a 90 s face costing more than a
            // 0.5 s one; the size floor and the "bigger than anything already classified" rule both need
            // the box, and only the last one can be true often enough to matter. Nothing below allocates.
            if (classifier == null) continue
            // Untracked detections are NEVER classified. Each gets a fresh synthetic id every frame, so
            // it is a brand-new one-sample track with `votesTried == 0` — VOTE_CAP could not bound it and
            // a fast-pan run of N untracked frames would cost N classifications instead of 5, voiding §5's
            // whole cost argument. A single crop is also the weakest possible evidence, and no vote cast
            // already means censor (§4.2), so skipping them costs coverage in no mode.
            if (id < 0) continue
            if (track.votesTried >= VOTE_CAP) continue
            val px = maxOf(box.width(), box.height())   // raw ML Kit box, unpadded, upright px
            if (px < MIN_FACE_PX) continue              // §4.2: no vote cast, which already means censor
            if (px <= track.classifiedPx) continue      // §4.2: spend the 5 on the biggest crops
            track.votesTried++
            track.classifiedPx = px
            // 0 = below the confidence floor: votes for nobody, and no vote cast already means censor.
            when (classifier.vote(image, uprightW, uprightH, rect)) {
                1 -> track.maleVotes++
                -1 -> track.femaleVotes++
            }
        }
        sweep(ptsMs)
    }

    /**
     * Evict the tracks that are over. O(live tracks) per frame, and eviction is exactly what keeps that
     * count small — on the measured film it is a handful, against the 3 362 a never-evicting map reached.
     */
    private fun sweep(nowMs: Long) {
        val iter = tracks.entries.iterator()
        while (iter.hasNext()) {
            val track = iter.next().value
            // A live track always holds at least the sample that created it, so last() cannot throw and
            // its pts IS "last seen" — no separate field to keep in sync with the samples.
            if (isStale(track.samples.last().ptsMs, nowMs)) {
                emit(track)
                iter.remove()
            }
        }
    }

    /**
     * Close one track: build its span, or count it as spared. The verdict is read HERE — at eviction,
     * never earlier (§4.3) — so a track is judged only once it is over and all its votes are in.
     */
    private fun emit(track: FaceTrack) {
        val edl = edlFor(track, who)
        if (edl != null) emittedEdls += edl
        else if (track.samples.isNotEmpty()) sparedCount++
    }

    /** Emit whatever is still live, then return every EDL the pass produced. */
    fun finish(): List<FaceTrackEdl> {
        body?.let { return it.finish() }
        tracks.values.forEach { emit(it) }
        tracks.clear()
        // Emission order is track-END order now that tracks leave as they finish, where M1 emitted in
        // first-detection order. Rendered pixels do not care (Edl.regionsAt unions the active rects and
        // CensorEffect re-sorts when it overflows), but sorting keeps Edl.toJson() diffable against the
        // M1-verified runs. Copy, so the returned Edl does not alias state this class keeps mutating.
        return emittedEdls.sortedBy { it.startMs }
    }

    /** Release the ML Kit detector. Idempotent. */
    fun closeDetector() {
        detector?.close()
        detector = null
        people = null
    }

    internal fun enableBody(context: android.content.Context) {
        if (coverBody && people == null) people = PersonDetector(context, masks = precise)
    }

    private fun detector(): FaceDetector = detector ?: FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .enableTracking()
            .build(),
    ).also { detector = it }
}

/**
 * Classifies one face crop: **+1 male, -1 female, 0 abstain** (below the confidence floor, or the
 * model is not installed). The caller owns the ORT session and the reused input buffer; this file owns
 * only the POLICY — which crops are worth a vote and what the tallies mean — which is what keeps
 * [shouldCensor] and [edlFor] JVM-testable with no Android and no ORT on the test classpath.
 *
 * [rect] is the raw (unpadded) ML Kit box in UPRIGHT-normalized [0,1] space, against [uprightW] ×
 * [uprightH] — NOT the padded EDL rect; [image] is the NV21 frame the crop comes from, still in its
 * unrotated buffer layout.
 */
fun interface FaceGenderVoter {
    fun vote(image: InputImage, uprightW: Int, uprightH: Int, rect: NRect): Int
}
