package com.haithamassoli.naqi.desktop.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.Models
import com.haithamassoli.naqi.desktop.media.RgbFrame
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.ml.NSFW_CLASSES
import com.haithamassoli.naqi.model.FilterOps
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

// Owner: the vision agent. Contract in apps/windows/SEAMS.md.

/**
 * YuNet detection + IoU tracking + genderage votes, written from Android's FaceTracker semantics through
 * core's FaceTrack / edlFor / isStale / VOTE_CAP / MIN_FACE_PX (not from the iOS tracker). [who] is
 * `FilterOps.censorWho`. Not thread-safe: one caller, frames in pts order.
 */
class FaceAnalyzer(who: String, modelsDir: File = Models.dir()) : AutoCloseable {
    private val detector = YuNet(File(modelsDir, Models.YUNET))

    // Android's rule (FilterWorker.genderVoter): only Women/Men pay for the vote, and a missing genderage
    // degrades to "no vote cast", which edlFor already censors, i.e. to Everyone, the safe direction.
    // A genderage that is there but will not load throws, and must not strand the detector opened above.
    private val voter = try {
        File(modelsDir, Models.GENDERAGE)
            .takeIf { (who == FilterOps.WOMEN || who == FilterOps.MEN) && it.isFile }
            ?.let { GenderVoter(it) }
    } catch (t: Throwable) {
        detector.close()
        throw t
    }

    private val tracker = FaceTracks(who)
    private val cuts = SceneCuts()

    /** One UPRIGHT analysis frame from `VideoDecoder.sample`. Must not keep [frame] past the call. */
    fun onFrame(frame: RgbFrame) {
        if (cuts.isCut(frame)) tracker.cut()
        val faces = detector.detect(frame)
        tracker.onFrame(faces, frame.width, frame.height, frame.ptsMs, voter?.let { v -> { rect -> v.vote(frame, rect) } })
    }

    /** Censored tracks only (edlFor with [who]), keyframes padded, sorted by startMs. Call once, at the end. */
    fun finish(): List<FaceTrackEdl> = tracker.finish()

    override fun close() {
        detector.use { voter?.close() } // both, even if one close throws
    }
}

/** The GantMan NSFW model ([Models.NSFW], input [1,3,224,224]). Not thread-safe. */
class NsfwClassifier(modelsDir: File = Models.dir()) : AutoCloseable {
    // The buffer first: once the session is open nothing else in the constructor may throw.
    private val input = directFloats(3 * SIDE * SIDE)
    private val session = createSession(File(modelsDir, Models.NSFW))

    /** Softmax probabilities in core's `NSFW_CLASSES` order, ready for `NsfwGate.fires`. */
    fun classify(frame: RgbFrame): FloatArray {
        // A nearest-neighbour STRETCH to 224², RGB / 255, no mean/std: Android's gate input
        // (FrameSampler's gx/gy maps), whose strictness table was tuned against exactly that.
        // ponytail: Android samples the full-size source; this samples the ≤640 analysis frame, because the
        // seam hands over only that. Android measured its own downscale-of-downscale variant as
        // under-censoring (FrameSampler.gatherGate KDoc, A4), so re-check recall here before trusting it.
        val plane = SIDE * SIDE
        val w = frame.width
        val h = frame.height
        val rgb = frame.rgb
        for (oy in 0 until SIDE) {
            val row = (oy * h / SIDE) * w
            for (ox in 0 until SIDE) {
                val s = 3 * (row + ox * w / SIDE)
                val i = oy * SIDE + ox
                input.put(i, (rgb[s].toInt() and 0xFF) / 255f)
                input.put(plane + i, (rgb[s + 1].toInt() and 0xFF) / 255f)
                input.put(2 * plane + i, (rgb[s + 2].toInt() and 0xFF) / 255f)
            }
        }
        return run(session, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong()), NSFW_CLASSES.size)
    }

    override fun close() = session.close()

    private companion object {
        const val SIDE = 224
    }
}

/**
 * InsightFace genderage on one face: **+1 male, -1 female, 0 abstain**, Android's `FaceGenderVoter`
 * contract with FilterWorker's body (crop, sigmoid of the logit gap, [CONF_FLOOR]).
 */
internal class GenderVoter(model: File) : AutoCloseable {
    private val input = directFloats(3 * SIDE * SIDE) // before the session, as in NsfwClassifier
    private val session = createSession(model)

    fun vote(frame: RgbFrame, rect: NRect): Int = try {
        cropToTensor(frame, rect, input)
        val out = run(session, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong()), 3)
        // Raw logits [female, male, age]; softmax over the first two is a sigmoid of their difference.
        val p = 1f / (1f + exp(-(out[1] - out[0])))
        if (max(p, 1f - p) < CONF_FLOOR) 0 else if (p >= 0.5f) 1 else -1
    } catch (e: Exception) {
        // One bad crop must not cost a long job, and an abstention already means censor.
        0
    }

    override fun close() = session.close()

    companion object {
        const val SIDE = 96

        /** Android's FilterWorker.CONF_FLOOR: below this the crop votes for nobody (censor). */
        const val CONF_FLOOR = 0.60f

        /**
         * Android's `FrameSampler.cropToTensor` on an upright RGB frame: a SQUARE of side
         * `max(boxW, boxH) * 1.5` centred on the box (InsightFace's training crop), nearest-neighbour,
         * edge-clamped, NCHW RGB in 0..255 unscaled (genderage has mean 0, std 1, unlike the NSFW gate).
         */
        fun cropToTensor(frame: RgbFrame, rect: NRect, out: FloatBuffer) {
            val w = frame.width
            val h = frame.height
            val half = maxOf(rect.width * w, rect.height * h) * 1.5f / 2f
            val x0 = (rect.left + rect.right) / 2f * w - half
            val y0 = (rect.top + rect.bottom) / 2f * h - half
            val step = half * 2f / SIDE
            val xs = IntArray(SIDE) { (x0 + it * step).toInt().coerceIn(0, w - 1) }
            val ys = IntArray(SIDE) { (y0 + it * step).toInt().coerceIn(0, h - 1) }
            val plane = SIDE * SIDE
            for (oy in 0 until SIDE) {
                val row = ys[oy] * w
                for (ox in 0 until SIDE) {
                    val s = 3 * (row + xs[ox])
                    val i = oy * SIDE + ox
                    out.put(i, (frame.rgb[s].toInt() and 0xFF).toFloat())
                    out.put(plane + i, (frame.rgb[s + 1].toInt() and 0xFF).toFloat())
                    out.put(2 * plane + i, (frame.rgb[s + 2].toInt() and 0xFF).toFloat())
                }
            }
        }
    }
}

internal val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

/** CPU, ORT's default threading: analysis runs one model at a time, so each run may use every core. */
internal fun createSession(model: File): OrtSession {
    require(model.isFile) { "Model missing: ${model.absolutePath}" }
    return OrtSession.SessionOptions().use { ortEnv.createSession(model.absolutePath, it) }
        .also { s -> openedSessions?.let { synchronized(it) { it += s } } }
}

/** Test seam: when set, every session [createSession] opens is added here, so a test can see it closed. */
@Volatile internal var openedSessions: MutableList<OrtSession>? = null

/** Direct and native-order, so ORT wraps it without a copy (a heap buffer is copied on every run). */
internal fun directFloats(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()

/** One input, first output, its first [n] floats. */
private fun run(session: OrtSession, input: FloatBuffer, shape: LongArray, n: Int): FloatArray =
    OnnxTensor.createTensor(ortEnv, input, shape).use { tensor ->
        session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
            val out = (result[0] as OnnxTensor).floatBuffer
            FloatArray(n) { out.get(it) }
        }
    }
