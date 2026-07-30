package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.ml.NUDENET_CLASSES

/** One NudeNet box, as primitives — a pure mirror of `Infer.Detection` so ORT never enters this file. */
internal data class RegionBox(val classIndex: Int, val score: Float, val rect: NRect)

/** One 5 fps gate FIRING plus the censorable boxes seen on that frame (empty ⇒ nothing localized). */
internal data class RegionSample(val ptsMs: Long, val boxes: List<RegionBox>)

/**
 * Opt-in per-region NSFW planning (a PRD non-goal, see [com.haithamassoli.naqi.model.FilterOps]).
 * Pure JVM like [NsfwGate], for the same reason: this is the decision that can weaken a content
 * filter, so it has to be unit-testable without Android, ORT or a device.
 *
 * **The safety invariant, and the only reason this feature is shippable.** [plan] may only ever
 * REMOVE a firing, never add or invent one, and it may only remove firings from the INTERIOR of a
 * run whose first and last firings it keeps. Because [NsfwGate.intervals] merges any two firings
 * less than PRE_MS+POST_MS+1 apart, and [RUN_GAP_MS] is far below that, keeping the endpoints a and
 * b reproduces whole-frame's `[a-PRE, b+POST]` exactly, and the emitted span covers `[a, b]` ⊇
 * `(a+POST, b-PRE)`. So **no millisecond that whole-frame censored becomes uncensored** — what
 * changes is that inside `(a+POST, b-PRE)` the censoring is a rect instead of the whole frame.
 * That window is the entire concession, and it only exists for a run that fired continuously for
 * over two seconds with a [TRUSTED] box on every one of its samples. `NsfwRegionsTest` asserts it.
 *
 * All times are MILLIseconds; all rects are normalized upright frame space (see Contracts.kt).
 */
internal object NsfwRegions {

    /** Score floor for a box to contribute blur area at all. Same value, same reason, as GenderVoter's. */
    const val MIN_SCORE = 0.35f

    /**
     * Score floor for a [TRUSTED] box to vouch that NudeNet actually READ this frame. Higher than
     * [MIN_SCORE] on purpose: a junk detection must not license un-blanking a frame the gate flagged.
     *
     * ponytail: reasoned, not measured. It is the single load-bearing guess in this file — too low and
     * a junk detection vouches for a frame NudeNet never really read, too high and the feature never
     * engages. Re-tune it against the `covered=` counter on qa-assets women-music-3min before this
     * toggle is offered to anyone.
     */
    const val COVER_SCORE = 0.50f

    /** Two gate periods (the gate is 5 fps): one skipped sample stays one run, two start a new shot. */
    const val RUN_GAP_MS = 400L

    /**
     * A run shorter than its own hysteresis window is entirely inside the coverage its kept endpoints
     * already produce, so a span there would be dead weight in [com.haithamassoli.naqi.edl.Edl.regionsAt]'s
     * per-rendered-frame scan. Derived from the gate, NOT a new tunable.
     */
    const val MIN_SPAN_MS = NsfwGate.PRE_MS + NsfwGate.POST_MS

    /**
     * Grow the union about its centre to at least this side. Under-detection is NudeNet's expected
     * failure here (a 640x360 frame is 320x180 of real content inside a black-padded 320x320 tensor),
     * so a lone genitalia box must not become a postage stamp over an otherwise revealed body.
     */
    const val MIN_SIDE = 0.28f

    /** Past this the "region" is a lie: neither watchable nor safer than the whole frame. */
    const val MAX_AREA = 0.60f

    /**
     * Mirror of the private `CensorEffect.MAX_REGIONS`. Over that count the shader keeps the LARGEST
     * rects and only logs — which would drop a small exposed-part box in favour of big padded face
     * rects, i.e. reveal exactly what this feature must hide. So a run whose span is already crowded
     * with face tracks is downgraded to whole-frame instead of gambling on the sort.
     *
     * ponytail: two copies of one number. The alternative is making a render constant public or
     * teaching this pure planner about the renderer. If the shader's slot count ever changes, this is
     * the second place.
     */
    private const val MAX_REGIONS = 8

    private val FACE_NAMES = setOf("FACE_FEMALE", "FACE_MALE")

    private val TRUSTED_NAMES = setOf(
        "FEMALE_GENITALIA_COVERED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED",
        "ANUS_COVERED", "ANUS_EXPOSED", "BUTTOCKS_COVERED", "BUTTOCKS_EXPOSED",
        "FEMALE_BREAST_COVERED", "FEMALE_BREAST_EXPOSED",
    )

    /**
     * Everything except the two FACE_* classes. Stated as a subtraction rather than a curated list:
     * this only ever runs on a frame the gate already flagged, i.e. a frame whole-frame mode was going
     * to blank entirely, so ADDING a class can only shrink what is revealed. A generous union that
     * grows too big trips [MAX_AREA] and falls back to whole-frame, which is the safest outcome.
     * Faces are out because they are [FaceTracker]'s job and because 320n's FACE_MALE is effectively
     * dead (real male portraits score FACE_FEMALE 0.7-0.86) — letting a portrait "cover" a frame would
     * downgrade the gate's verdict to a face blur.
     */
    internal val CENSORABLE: Set<Int> = NUDENET_CLASSES.indices.toSet() - idx(FACE_NAMES)

    /**
     * Only these can make a frame COVERED. The four *_COVERED twins are the load-bearing inclusion:
     * the gate's "sexy" tier (bikini, lingerie) is where the blackouts are most unwatchable and NudeNet
     * labels exactly that content COVERED, so an exposed-only set would leave the commonest target
     * content permanently on the whole-frame path — the feature would do nothing while pretending to work.
     */
    internal val TRUSTED: Set<Int> = idx(TRUSTED_NAMES)

    // Resolved by NAME. NUDENET_CLASSES is index-locked to the model's output rows, and the PRD lists
    // the labels in a different order — never index by that order. A re-export that renames or reorders
    // a label fails loudly here instead of silently blurring feet and ignoring genitalia.
    private fun idx(names: Set<String>): Set<Int> = names.map { n ->
        NUDENET_CLASSES.indexOf(n).also { require(it >= 0) { "unknown NudeNet class: $n" } }
    }.toSet()

    /** True when this box contributes blur area. */
    fun censorable(classIndex: Int, score: Float): Boolean =
        score >= MIN_SCORE && classIndex in CENSORABLE

    /** True when this box vouches that NudeNet localized the reason the gate fired. */
    fun covers(classIndex: Int, score: Float): Boolean =
        score >= COVER_SCORE && classIndex in TRUSTED

    /** Both halves of the decision, so the fail-safe is a return value a test can assert. */
    class Plan(val fallbackFiringsMs: List<Long>, val tracks: List<FaceTrackEdl>)

    /**
     * [firingsMs] are every gate firing; [samples] the boxes for the ones per-region observed (empty
     * when the flag is off, which makes this the identity); [faceTracks] the face spans already planned
     * for the same time range, for the [MAX_REGIONS] budget check.
     *
     * ponytail: per-region output is segmentation-dependent where whole-frame output is not — a
     * coverage run straddling a `Checkpoint` segment boundary is split and both halves keep their own
     * endpoints, so up to ~4 s of extra whole-frame appears at the seam. Safe in direction (it only
     * adds coverage back), but it breaks the property that the segmented and single-pass routes produce
     * the same EDL, and nothing tests it. Plan runs across seams only if QA ever sees the seam.
     */
    fun plan(
        firingsMs: List<Long>,
        samples: List<RegionSample>,
        faceTracks: List<FaceTrackEdl>,
    ): Plan {
        if (samples.isEmpty()) return Plan(firingsMs, emptyList())
        val byPts = samples.associateBy { it.ptsMs }
        val sorted = firingsMs.sorted()
        val fallback = ArrayList<Long>(sorted.size)
        val tracks = ArrayList<FaceTrackEdl>()
        var i = 0
        while (i < sorted.size) {
            val start = i
            while (i < sorted.size && covered(byPts[sorted[i]]) &&
                (i == start || sorted[i] - sorted[i - 1] <= RUN_GAP_MS)
            ) i++
            if (i == start) {                       // not covered: stays whole-frame, and splits the run
                fallback += sorted[i]
                i++
                continue
            }
            val run = sorted.subList(start, i)
            val kfs = if (run.last() - run.first() > MIN_SPAN_MS && budgetOk(run, faceTracks)) {
                keyframes(run, byPts)
            } else {
                null
            }
            if (kfs == null) {
                fallback += run                     // any doubt at all: the whole run stays whole-frame
            } else {
                tracks += FaceTrackEdl(run.first(), run.last(), kfs)
                fallback += run.first()             // the endpoints keep their full PRE/POST hysteresis,
                fallback += run.last()              // which is what makes the invariant above hold
            }
        }
        return Plan(fallback, tracks)
    }

    private fun covered(s: RegionSample?): Boolean =
        s != null && s.boxes.any { covers(it.classIndex, it.score) }

    // Conservative on purpose: counts every face track OVERLAPPING the run, not the simultaneous peak.
    private fun budgetOk(run: List<Long>, faceTracks: List<FaceTrackEdl>): Boolean =
        faceTracks.count { it.startMs <= run.last() && it.endMs >= run.first() } + 1 <= MAX_REGIONS

    /**
     * One keyframe per run sample. Each rect is the bbox of the padded censorable boxes at that sample
     * AND ITS TWO RUN NEIGHBOURS — which is what closes the 200 ms cadence gap provably rather than
     * hopefully: rect_k and rect_(k+1) both contain every box observed at t_k and t_(k+1), and a linear
     * interpolation of two rects that both contain X also contains X, so `FaceTrackEdl.rectAt` covers
     * both endpoint observations at every rendered instant between them. It also means a class dropping
     * out for a single sample does not blink the blur off. Null ⇒ downgrade the run to whole-frame.
     */
    private fun keyframes(run: List<Long>, byPts: Map<Long, RegionSample>): List<Pair<Long, NRect>>? {
        val per = run.map { t ->
            bbox(byPts.getValue(t).boxes.filter { censorable(it.classIndex, it.score) }.map { padRect(it.rect) })
        }
        val out = ArrayList<Pair<Long, NRect>>(run.size)
        for (k in run.indices) {
            val win = listOfNotNull(per.getOrNull(k - 1), per[k], per.getOrNull(k + 1))
            val r = atLeast(bbox(win) ?: return null, MIN_SIDE)
            if (r.width * r.height > MAX_AREA) return null
            out += run[k] to r
        }
        return out
    }

    // The union's bounding box, not one rect per box: two boxes on one body would otherwise leave an
    // unblurred strip between them, and one span per box would multiply Edl.regionsAt's per-frame scan.
    private fun bbox(rects: List<NRect>): NRect? {
        if (rects.isEmpty()) return null
        var l = 1f
        var t = 1f
        var r = 0f
        var b = 0f
        for (x in rects) {
            if (x.left < l) l = x.left
            if (x.top < t) t = x.top
            if (x.right > r) r = x.right
            if (x.bottom > b) b = x.bottom
        }
        return NRect(l, t, r, b)
    }

    // Grow about the centre to at least [side] per axis. An edge-hugging rect stays smaller after the
    // clamp — accepted: it is still centred on what was detected.
    private fun atLeast(r: NRect, side: Float): NRect {
        val gx = ((side - r.width) / 2f).coerceAtLeast(0f)
        val gy = ((side - r.height) / 2f).coerceAtLeast(0f)
        return NRect(
            (r.left - gx).coerceIn(0f, 1f),
            (r.top - gy).coerceIn(0f, 1f),
            (r.right + gx).coerceIn(0f, 1f),
            (r.bottom + gy).coerceIn(0f, 1f),
        )
    }
}
