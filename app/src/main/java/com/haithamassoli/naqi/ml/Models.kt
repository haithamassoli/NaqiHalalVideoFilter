package com.haithamassoli.naqi.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer
import java.util.EnumSet

/**
 * The bundled ONNX models and their locked inference contracts (M0).
 *
 * Files ship in `assets/models/` (gitignored — run `scripts/fetch-models.sh`) and are copied
 * once into `filesDir/models/` because ORT wants a real file path. [ModelDownloader] fetches the
 * same file names into the same directory, so a downloaded build needs no asset at all.
 */
enum class NaqiModel(
    /** File name in `assets/models/` and in `filesDir/models/` — a download lands at the same path. */
    val assetName: String,
    /**
     * Verified after download, before the file is moved into place ([ModelDownloader]). Re-derive
     * with `shasum -a 256` whenever an artifact is re-exported, or downloads reject the new file.
     */
    val sha256: String,
    /**
     * Public download URL, or null for a locally converted artifact with no public host — those
     * resolve against `BuildConfig.NAQI_MODEL_BASE_URL`.
     */
    val downloadUrl: String?,
    val smokeShapes: List<LongArray>,
) {
    /**
     * NSFW 5-class gate — GantMan nsfw_model MobileNetV2 1.4-224, converted to ONNX with the
     * input transposed to NCHW. Kept f32: the f16 variant is rejected on-device by XNNPACK's
     * fp16 depthwise-conv path (xnn_create_convolution2d_nhwc_fp16 error 2).
     * Input [1,3,224,224] f32, RGB, scaled 1/255 — no mean/std normalization.
     * Output [1,5] softmax. Class order locked in [NSFW_CLASSES] (upstream alphabetical).
     */
    NSFW_GATE(
        "nsfw_mnv2_140_f32.onnx",
        "049ce7c51eaf3db429f0ffb22ba23345e7ec2483356432ea67e83446ed5cfe9e",
        downloadUrl = null, // tf2onnx conversion of GantMan/nsfw_model — no public host yet
        listOf(longArrayOf(1, 3, 224, 224)),
    ),

    /**
     * NudeNet v3 320n (YOLOv8n head). Input `images` [1,3,320,320] f32, RGB, scaled 1/255;
     * upstream preprocessing pads right/bottom to square, then resizes to 320
     * (cv2 blobFromImage, swapRB=true ⇒ RGB). Output `output0` [1,22,2100]:
     * rows 0..3 = cx,cy,w,h in 320-space, rows 4..21 = per-class scores in [NUDENET_CLASSES].
     * Upstream thresholds: keep score ≥ 0.2, NMS score 0.25 / IoU 0.45.
     */
    NUDENET(
        "nudenet_320n.onnx",
        "c15d8273adad2d0a92f014cc69ab2d6c311a06777a55545f2c4eb46f51911f0f",
        // Shipped as-is by upstream, so it downloads straight from the release `scripts/fetch-models.sh` uses.
        "https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.onnx",
        listOf(longArrayOf(1, 3, 320, 320)),
    ),

    /**
     * htdemucs, f16 weights — demucs.onnx export with STFT/iSTFT kept OUTSIDE the graph, re-exported
     * at a 3.9 s segment (M3) by `scripts/htdemucs_export.py`; the checkpoint's own 7.8 s segment
     * peaked ~3.2 GB RSS on device, over the PRD's 1.5 GB budget. Geometry lives in [DemucsSeparator].
     * Inputs: mix waveform [1,2,171990] f32 (3.9 s stereo @ 44.1 kHz, zero-padded to fit) and
     * complex-as-channels spectrogram [1,4,2048,168] f32 (nfft 4096, hop 1024).
     * Outputs: masked spectrogram [1,4,4,2048,168] + time-branch [1,4,2,171990];
     * stems = istft(spec output) + time branch, computed outside the graph (M2 driver).
     */
    HTDEMUCS(
        "htdemucs_s26_f16.onnx",
        "df8a2c2c8dd06ca279f58646dacc007b3e1e07436f31a3189408f0336c56eba5",
        downloadUrl = null, // locally re-exported from the demucs.onnx graph — no public host yet
        listOf(longArrayOf(1, 2, 114660), longArrayOf(1, 4, 2048, 112)),
    ),
}

/** GantMan class order (alphabetical, index-locked). sfw = drawings+neutral; nsfw = the rest. */
val NSFW_CLASSES = listOf("drawings", "hentai", "neutral", "porn", "sexy")

/** NudeNet v3 label order, index-locked to output rows 4..21. FACE_FEMALE=1, FACE_MALE=12. */
val NUDENET_CLASSES = listOf(
    "FEMALE_GENITALIA_COVERED", "FACE_FEMALE", "BUTTOCKS_EXPOSED", "FEMALE_BREAST_EXPOSED",
    "FEMALE_GENITALIA_EXPOSED", "MALE_BREAST_EXPOSED", "ANUS_EXPOSED", "FEET_EXPOSED",
    "BELLY_COVERED", "FEET_COVERED", "ARMPITS_COVERED", "ARMPITS_EXPOSED", "FACE_MALE",
    "BELLY_EXPOSED", "MALE_GENITALIA_EXPOSED", "ANUS_COVERED", "FEMALE_BREAST_COVERED",
    "BUTTOCKS_COVERED",
)

data class ModelReport(val model: NaqiModel, val bundled: Boolean, val ok: Boolean, val detail: String)

data class SmokeReport(val providers: List<String>, val models: List<ModelReport>)

/**
 * M0 validation: extract each bundled model, create an ORT session on the XNNPACK EP, and run
 * one zero-tensor inference — proves the runtime, the EP registration, and every model's
 * loadability + IO shapes on the actual device. Surfaced in the DEVICE RUNTIME footer.
 */
object ModelSmoke {
    private const val TAG = "ModelSmoke"

    /** Debug flag — XNNPACK is the portable default; NNAPI is opt-in and legacy (deprecated at API 35). */
    var useNnapi: Boolean = false

    // Process-scoped: the result cannot change while the process lives, and re-running it creates
    // three ORT sessions (87 MB htdemucs among them) — which the UI would otherwise do every time the
    // user navigates back to the pick screen, i.e. potentially alongside a running filter job.
    @Volatile
    private var cached: SmokeReport? = null

    fun run(context: Context): SmokeReport = cached ?: compute(context).also { cached = it }

    private fun compute(context: Context): SmokeReport {
        val env = OrtEnvironment.getEnvironment()
        val providers = OrtEnvironment.getAvailableProviders().map { it.name }
        val models = NaqiModel.entries.map { model -> smokeOne(context, env, model) }
        Log.i(TAG, "providers=$providers " + models.joinToString { "${it.model.name}:${it.detail}" })
        return SmokeReport(providers, models)
    }

    private fun smokeOne(context: Context, env: OrtEnvironment, model: NaqiModel): ModelReport = try {
        val file = modelFile(context, model)
        if (file == null) {
            ModelReport(model, bundled = false, ok = false, detail = "not installed")
        } else {
            val t0 = SystemClock.elapsedRealtime()
            sessionOptions().use { opts ->
                env.createSession(file.absolutePath, opts).use { session ->
                    if (model == NaqiModel.HTDEMUCS) {
                        // Load-only: a full htdemucs run peaks at multiple GB and takes ~9 s; running it
                        // here concurrently with an M2 filter job got the process lmkd-killed. The M2
                        // pipeline owns executing this graph; the smoke proves load + IO metadata only.
                        val out = (session.outputInfo.values.first().info as TensorInfo).shape
                        val ms = SystemClock.elapsedRealtime() - t0
                        ModelReport(model, bundled = true, ok = true, detail = "$ms ms load → ${out.contentToString()}")
                    } else {
                        // Match declared smoke shapes to graph inputs by rank — name-agnostic.
                        val feeds = session.inputInfo.entries.associate { (name, info) ->
                            val rank = (info.info as TensorInfo).shape.size
                            val shape = model.smokeShapes.first { it.size == rank }
                            val n = shape.reduce(Long::times).toInt()
                            name to OnnxTensor.createTensor(env, FloatBuffer.allocate(n), shape)
                        }
                        try {
                            session.run(feeds).use { result ->
                                val out = (result[0].info as TensorInfo).shape
                                val ms = SystemClock.elapsedRealtime() - t0
                                ModelReport(model, bundled = true, ok = true, detail = "$ms ms → ${out.contentToString()}")
                            }
                        } finally {
                            feeds.values.forEach { it.close() }
                        }
                    }
                }
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "${model.assetName} smoke failed", t)
        ModelReport(model, bundled = true, ok = false, detail = t.message ?: t.javaClass.simpleName)
    }

    /** Copy the bundled asset → filesDir (ORT wants a real path). Dev flow; null when not bundled. */
    private fun extracted(context: Context, model: NaqiModel): File? {
        val dir = ModelDownloader.modelsDir(context)
        val file = File(dir, model.assetName)
        return try {
            context.assets.open("models/${model.assetName}").use { input ->
                val tmp = File(dir, "${model.assetName}.tmp")
                tmp.outputStream().use { input.copyTo(it) }
                check(tmp.renameTo(file)) { "rename failed for ${model.assetName}" }
                file
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    /**
     * Resolve a model to a real file path for [Infer]. Whatever is already in `filesDir/models` wins
     * — an earlier asset copy, or an M3 download (same directory, same file name), which is what makes
     * the copy "once". Otherwise the bundled asset is copied in; null ⇒ neither source has it, so the
     * caller should offer a [ModelDownloader] fetch.
     */
    internal fun modelFile(context: Context, model: NaqiModel): File? =
        ModelDownloader.installed(context, model) ?: extracted(context, model)

    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addXnnpack(mapOf("intra_op_num_threads" to Runtime.getRuntime().availableProcessors().toString()))
        if (useNnapi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
        }
    }
}
