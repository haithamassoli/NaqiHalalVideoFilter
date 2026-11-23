package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.edl.FaceTrackEdl
import java.io.File

// Owner: the vision agent. Contract in apps/windows/SEAMS.md.

/**
 * YuNet detection + IoU tracking + genderage votes, written from Android's FaceTracker semantics through
 * core's FaceTrack / edlFor / isStale / VOTE_CAP / MIN_FACE_PX (not from the iOS tracker). [who] is
 * `FilterOps.censorWho`. Not thread-safe: one caller, frames in pts order.
 */
class FaceAnalyzer(private val who: String, modelsDir: File = Models.dir()) : AutoCloseable {
    /** One UPRIGHT analysis frame from `VideoDecoder.sample`. Must not keep [frame] past the call. */
    fun onFrame(frame: RgbFrame): Unit = TODO("vision")

    /** Censored tracks only (edlFor with [who]), keyframes padded, sorted by startMs. Call once, at the end. */
    fun finish(): List<FaceTrackEdl> = TODO("vision")

    override fun close(): Unit = TODO("vision")
}

/** The GantMan NSFW model ([Models.NSFW], input [1,3,224,224]). Not thread-safe. */
class NsfwClassifier(modelsDir: File = Models.dir()) : AutoCloseable {
    /** Softmax probabilities in core's `NSFW_CLASSES` order, ready for `NsfwGate.fires`. */
    fun classify(frame: RgbFrame): FloatArray = TODO("vision")

    override fun close(): Unit = TODO("vision")
}

/** The pass-2 compositor: Android's CensorEffect on CPU RGB. No region cap, so no overflow spans. */
object Censor {
    /**
     * Paint [frame] in place. [fullFrame] covers everything; otherwise each of [regions] (normalized to
     * this frame as stored, i.e. already `NRect.toStoredSpace(rotation)`) is covered. Style is FilterOps':
     * [solidColor] != `FilterOps.BLUR` is an opaque ARGB fill; else blur at [blurAmount] (0..100),
     * desaturated when [grayscale].
     */
    fun apply(
        frame: RgbFrame,
        regions: List<NRect>,
        fullFrame: Boolean,
        blurAmount: Int,
        grayscale: Boolean,
        solidColor: Int,
    ): Unit = TODO("vision")
}
