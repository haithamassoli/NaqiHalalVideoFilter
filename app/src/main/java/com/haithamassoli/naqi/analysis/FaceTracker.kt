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
) {

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
        "tracks=$trackCount faces=$faceCount untracked=$untrackedCount liveTracks=${tracks.size} " +
            "spared=$sparedCount"

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

/**
 * Classifications per track, then stop (plan-censor-who §4.2): cost becomes per-TRACK, not per-frame,
 * so a face on screen for 90 s costs the same as one on screen for 0.5 s. §5 budgets 3 362 tracks × 5
 * crops × ~1 ms ≈ 17 s on a 155-min film. Tunable in Phase B — drop to 3 first if measured cost
 * exceeds ~5 % of analyze.
 */
private const val VOTE_CAP = 5

/**
 * Below this max side in upright pixels a crop is not classified at all — the input is 96², so a small
 * face upsampled into it carries no signal, and a guess from noise is worse than the fail-safe
 * (§3.2 pt 4, §4.2). Not classified ⇒ no vote ⇒ censored.
 *
 * **80, and it is measured, not guessed.** Phase B ran the real thing on an S23 — 479 crops the app
 * itself cut and classified, hand-labelled off the dump ([FilterWorker]'s `dump-crops` hook) — and
 * banded them exactly as §3.2 pt 4 asks:
 *
 * | crop max side | n | abstain | correct |
 * |---|---:|---:|---:|
 * | 40–80 px | 28 | 7.1 % | **76.9 %** |
 * | 80–160 px | 78 | 6.4 % | 95.9 % |
 * | 160–320 px | 222 | 0.9 % | 96.8 % |
 * | 320+ px | 39 | 0.0 % | 94.9 % |
 *
 * The 40–80 band is ~20 points worse than every band above it, and it is only ~8 % of crops, so
 * excluding it costs almost no coverage and removes most of the wrong votes. Below the floor the mode
 * degrades to Everyone for that track, which is the safe direction.
 */
private const val MIN_FACE_PX = 80

/**
 * Source-time gap after which an unseen track is considered over. 2 s = 20 sampled frames at 10 fps:
 * long enough that a blink or a brief occlusion does not split one face into two tracks, short
 * enough that the live map stays a handful of tracks on a feature-length film.
 *
 * ponytail: one fixed gap for every video. A shot-length-aware value would split fewer tracks on
 * fast-cut content — worth doing only if QA ever shows a split costing coverage. (It no longer costs
 * a censor decision: since V4 both halves of a split track are censored anyway.)
 */
private const val EVICT_AFTER_MS = 2_000L

/** Half the 100 ms (10 fps) sample gap, so between-sample frames stay covered at a span's edges. */
private const val SPAN_PAD_MS = 50L

/** 25% per side ⇒ 1.5x each dimension (PRD: pad 25%). */
private const val KEYFRAME_PAD = 0.25f

/**
 * The span for one finished track, or null if it is not censored under [who]. Pure (no Android, no ML
 * Kit) so it is unit-testable — see `FaceTrackerLogicTest`. This one branch is where the whole
 * Women/Men feature lands (plan-censor-who §4.3): the EDL, the renderer, `CensorEffect` and all of
 * pass 2 stay untouched, because a spared track simply never becomes a span.
 *
 * The empty-samples guard cannot happen through [FaceTracker] (a track is created with its first
 * sample) and exists so a future caller cannot crash on `samples.first()`.
 */
internal fun edlFor(track: FaceTrack, who: String = FilterOps.EVERYONE): FaceTrackEdl? {
    if (track.samples.isEmpty()) return null
    if (!shouldCensor(track.femaleVotes, track.maleVotes, who)) return null
    return FaceTrackEdl(
        startMs = (track.samples.first().ptsMs - SPAN_PAD_MS).coerceAtLeast(0L),
        endMs = track.samples.last().ptsMs + SPAN_PAD_MS,
        keyframes = track.samples.map { it.ptsMs to padRect(it.rect) },
    )
}

/**
 * The censor verdict for one finished track (plan-censor-who §4.2). Written as "censor unless the
 * OTHER gender strictly won", so the two facts that matter are visible at a glance: **0/0 censors**
 * (no vote cast — unreadable face, too small, model absent) and **a tie censors**. Uncertainty always
 * resolves toward covering; that is this app's safe direction.
 *
 * §8's caveat, stated because it is a measured risk and not an oversight: that fail-safe is identical
 * in both modes, so on every face the model cannot read, Women and Men do exactly the same thing. If
 * abstentions dominate, both collapse into Everyone under a different label — which is why §3.3 gates
 * on "men left visible in Women mode", not on accuracy alone.
 */
internal fun shouldCensor(femaleVotes: Int, maleVotes: Int, who: String): Boolean = when (who) {
    FilterOps.NONE -> false
    FilterOps.WOMEN -> !(maleVotes > femaleVotes)
    FilterOps.MEN -> !(femaleVotes > maleVotes)
    else -> true                                   // EVERYONE, and any unknown value: cover it
}

/** True once [nowMs] is [EVICT_AFTER_MS] past the last frame a track appeared in. */
internal fun isStale(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs >= EVICT_AFTER_MS

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
