package com.haithamassoli.naqi.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * M1 ORT inference for the image models — the NSFW gate and the face gender vote — per the locked
 * contracts in [NaqiModel]. Sessions are created lazily and cached for the process, on the same
 * [imageSessionOptions] [ModelSmoke] load-checks them with.
 *
 * **Preprocessing no longer lives here (plan-v2 §5.2, "V2").** [nsfw] used to allocate, per call, a
 * 224² `Bitmap`, an `IntArray(50 176)` and a heap `FloatBuffer.allocate(150 528)` — ~1 MB × 46 500
 * calls ≈ 46 GB of churn on one film — and because `FloatBuffer.allocate` is a HEAP buffer, ORT then
 * copied it into native memory on every `run`. The caller now hands over a reused DIRECT buffer that
 * `FrameSampler.gateFromGathered` filled from the source bytes the sampler gathered. Nothing is
 * allocated per call but the tensor view and the 5-float result.
 *
 * NudeNet is gone entirely (plan-v2 §5.4, "V4"): AGPL-3.0 in a closed-source APK, and the gender vote
 * it powered censored approximately every face anyway.
 *
 * Contract: [nsfw] and [genderAge] are safe to call concurrently **as long as each caller owns its own
 * `input` buffer** — `OrtSession.run` is thread-safe and the session cache is a [ConcurrentHashMap]
 * resolved through `computeIfAbsent`, so a model is created at most once however many callers race for
 * it, but the buffer is viewed in place and sharing one across threads corrupts the tensor rather than
 * failing. [close] is NOT covered either: it is the job-teardown call, and running it against a live
 * inference closes a session out from under `run`. Call it once, after every caller is done.
 */
object Infer {

    /** [NaqiModel.NSFW_GATE]'s locked input shape. */
    private val GATE_SHAPE = longArrayOf(1, 3, 224, 224)

    /** [NaqiModel.GENDERAGE]'s locked input shape. */
    private val GENDERAGE_SHAPE = longArrayOf(1, 3, 96, 96)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = ConcurrentHashMap<NaqiModel, OrtSession>()

    /** Memoized [genderAgeAvailable]; null until first asked. */
    @Volatile
    private var genderAgeInstalled: Boolean? = null

    /**
     * Is [NaqiModel.GENDERAGE] on disk? **Memoized, and that is the point** — resolving it means a
     * `File.length()` stat, and on the missing path an `AssetManager.open` that throws and swallows a
     * `FileNotFoundException`. A film classifies thousands of crops, and the answer cannot change
     * mid-pass (the file is installed once, at smoke time). Callers should ask ONCE, before the pass:
     * `FilterWorker.genderVoter()` returns null on false, so a build without the asset skips the crop
     * fill as well as the inference instead of paying 9 216 pixels per face for a guaranteed abstention.
     */
    fun genderAgeAvailable(context: Context): Boolean = genderAgeInstalled ?: run {
        val ok = ModelSmoke.modelFile(context, NaqiModel.GENDERAGE) != null
        genderAgeInstalled = ok
        if (!ok) Log.w("Infer", "${NaqiModel.GENDERAGE.assetName} not installed — face gender vote abstains")
        ok
    }

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
     * as `gateFill=`, so the caller's `gate=` finally measures `session.run`. Since `perf-plan-v4` A1
     * both counters are the CALLER's — the fill moved off the sampler's producer thread onto the
     * consumer that runs this — but they still mean preprocessing and model respectively.
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

    /**
     * InsightFace `genderage.onnx` (`plan-censor-who` §4.1). [input] must be a DIRECT, native-order
     * `[1,3,96,96]` NCHW RGB buffer in **0..255, unscaled** — see [NaqiModel.GENDERAGE]; the gate
     * above wants 1/255 on the same layout, so the two fills are NOT interchangeable. Same
     * zero-copy-view reasoning as [nsfw] for why the tensor is not cached.
     *
     * @return raw `[female, male, age]` logits (NOT softmax), or null when the model is not installed.
     *
     * Null rather than throwing because face censoring must keep working when only *this* model is
     * missing — [session] `error()`s, and the whole feature degrades to "no vote cast", which §4.2
     * already defines as censor. The warn is one-shot: a track classifies up to `VOTE_CAP` crops, so
     * logging per crop is 5 lines per track and thousands per film.
     */
    fun genderAge(context: Context, input: FloatBuffer): FloatArray? {
        if (!genderAgeAvailable(context)) return null
        val session = session(context, NaqiModel.GENDERAGE)
        OnnxTensor.createTensor(env, input, GENDERAGE_SHAPE).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(3) { out.get(it) }
            }
        }
    }

    /** Idempotent — releases the cached sessions (safe when none exist). */
    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    // computeIfAbsent, NOT getOrPut: Kotlin's getOrPut is get-then-put with no locking even on a
    // ConcurrentHashMap, so two racing callers would each create a session and the loser would be
    // overwritten unclosed — a native leak the GC cannot see, since an OrtSession's weights and arenas
    // live off-heap. computeIfAbsent runs the builder under the bin lock exactly once. An `error()`
    // inside it propagates and records no mapping, so a missing model still throws on every call.
    private fun session(context: Context, model: NaqiModel): OrtSession = sessions.computeIfAbsent(model) {
        val file = ModelSmoke.modelFile(context, model) ?: error("model not bundled: ${model.assetName}")
        imageSessionOptions().use { env.createSession(file.absolutePath, it) }
    }
}
