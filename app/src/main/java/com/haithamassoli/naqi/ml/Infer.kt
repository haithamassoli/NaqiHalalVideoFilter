package com.haithamassoli.naqi.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * M1 ORT inference for the NSFW gate, per the locked contract in [NaqiModel]. The session is created
 * lazily and cached for the process, on the same [imageSessionOptions] [ModelSmoke] load-checks it with.
 *
 * **Preprocessing no longer lives here (plan-v2 §5.2, "V2").** [nsfw] used to allocate, per call, a
 * 224² `Bitmap`, an `IntArray(50 176)` and a heap `FloatBuffer.allocate(150 528)` — ~1 MB × 46 500
 * calls ≈ 46 GB of churn on one film — and because `FloatBuffer.allocate` is a HEAP buffer, ORT then
 * copied it into native memory on every `run`. The caller now hands over a reused DIRECT buffer that
 * `FrameSampler.convertToTensor` filled straight from the decoder's YUV planes. Nothing is allocated
 * per call but the tensor view and the 5-float result.
 *
 * NudeNet is gone entirely (plan-v2 §5.4, "V4"): AGPL-3.0 in a closed-source APK, and the gender vote
 * it powered censored approximately every face anyway.
 *
 * Contract: one worker drives this at a time (thread-confined). [close] releases the session.
 */
object Infer {

    /** [NaqiModel.NSFW_GATE]'s locked input shape. */
    private val GATE_SHAPE = longArrayOf(1, 3, 224, 224)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<NaqiModel, OrtSession>()

    /**
     * GantMan 5-class softmax in [NSFW_CLASSES] order. [input] must be a DIRECT, native-order
     * `[1,3,224,224]` NCHW RGB buffer scaled 1/255 — i.e. exactly what `FrameSampler` hands the caller.
     *
     * plan-v2 §5.2 asks for "one tensor, created once, reused". Deliberately NOT done: `createTensor`
     * over a DIRECT native-order buffer is a zero-copy *view* on that memory (ORT copies only when the
     * buffer is not direct), so the per-call cost is one JNI wrap and one release — microseconds
     * against a ~7 ms model run, and the whole of the megabyte-per-call V2 targets is already gone with
     * the heap buffer. Caching the tensors instead would mean caching them **per buffer**, because the
     * sampler cycles a ring (a frame's buffer is valid for one callback only, so one shared buffer
     * would race the producer), and a process-lifetime cache keyed on buffer identity would then pin
     * ~2.4 MB of direct memory for every analyzed segment until the job ends — ~100 MB on a film, to
     * save a JNI call.
     *
     * plan-v2 §4.6: what is left in here IS the model. The preprocessing this call used to hide (the
     * rescale, the getPixels, the 150 528-iteration float loop) is now timed separately by the sampler
     * as `gateFill=`, so the caller's `gate=` finally measures `session.run`.
     */
    fun nsfw(context: Context, input: FloatBuffer): FloatArray {
        val session = session(context, NaqiModel.NSFW_GATE)
        OnnxTensor.createTensor(env, input, GATE_SHAPE).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(NSFW_CLASSES.size) { out.get(it) }
            }
        }
    }

    /** Idempotent — releases the cached sessions (safe when none exist). */
    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun session(context: Context, model: NaqiModel): OrtSession = sessions.getOrPut(model) {
        val file = ModelSmoke.modelFile(context, model) ?: error("model not bundled: ${model.assetName}")
        imageSessionOptions().use { env.createSession(file.absolutePath, it) }
    }
}
