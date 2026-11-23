package com.haithamassoli.naqi.analysis

import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.model.FilterOps

// The pure half of Android's `FaceTracker` (whose ML Kit side stays in the app): the vote policy and span
// building shared by it and [BodyTracker]. The four used from the Android module are public, since
// `internal` stops at the module boundary; the rest stay internal.

/**
 * Classifications per track, then stop (plan-censor-who §4.2): cost becomes per-TRACK, not per-frame,
 * so a face on screen for 90 s costs the same as one on screen for 0.5 s. §5 budgets 3 362 tracks × 5
 * crops × ~1 ms ≈ 17 s on a 155-min film. Tunable in Phase B — drop to 3 first if measured cost
 * exceeds ~5 % of analyze.
 */
const val VOTE_CAP = 5

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
const val MIN_FACE_PX = 80

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
fun edlFor(track: FaceTrack, who: String = FilterOps.EVERYONE): FaceTrackEdl? {
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
fun isStale(lastSeenMs: Long, nowMs: Long): Boolean = nowMs - lastSeenMs >= EVICT_AFTER_MS

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
