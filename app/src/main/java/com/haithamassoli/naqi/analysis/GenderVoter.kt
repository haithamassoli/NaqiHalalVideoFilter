package com.haithamassoli.naqi.analysis

import android.content.Context
import android.graphics.Bitmap
import com.haithamassoli.naqi.ml.Infer

/**
 * M1 pass-1 per-track gender resolution. Given the frontal face crops [FaceTracker] harvested for
 * one track, runs NudeNet on each and takes a majority vote of FACE_FEMALE vs FACE_MALE.
 *
 * Only a track voted FEMALE (or UNKNOWN with `blurUnknownFaces`) is censored, so a wrong or absent
 * vote fails safe toward "not censored" — the PRD accepts this and relies on the majority to mitigate
 * the gender model's two-way error.
 */
object GenderVoter {
    /** NudeNet class indices, locked in [com.haithamassoli.naqi.ml.NUDENET_CLASSES]. */
    private const val FACE_FEMALE = 1
    private const val FACE_MALE = 12

    /** Upstream-tuned minimum detection score for a crop to cast a vote. */
    private const val MIN_SCORE = 0.35f

    suspend fun vote(context: Context, crops: List<Bitmap>): Gender {
        var female = 0
        var male = 0
        for (crop in crops) {
            // One vote per crop: the highest-scoring face detection that clears the threshold.
            val best = Infer.nudenet(context, crop)
                .filter { (it.classIndex == FACE_FEMALE || it.classIndex == FACE_MALE) && it.score >= MIN_SCORE }
                .maxByOrNull { it.score }
            when (best?.classIndex) {
                FACE_FEMALE -> female++
                FACE_MALE -> male++
            }
        }
        return when {
            female > male -> Gender.FEMALE
            male > female -> Gender.MALE
            else -> Gender.UNKNOWN // tie or no votes
        }
    }
}
