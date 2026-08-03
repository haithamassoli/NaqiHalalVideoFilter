package com.haithamassoli.naqi.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.nio.FloatBuffer

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
     * input transposed to NCHW, then **statically quantized to INT8** (V3, `plan-v2` §5.3) by
     * `scripts/nsfw_int8_quantize.py`: AveragePool[7,7] → GlobalAveragePool (bit-exact, max|Δ| 0),
     * `quant_pre_process`, then `quantize_static(QDQ, QInt8/QInt8, per_channel=True)` calibrated on 100
     * real frames. ORT fuses it to 75 nodes / 52 `QLinearConv` — real integer kernels, and XNNPACK
     * registers `QLinearConv`, so the fast path exists on-device.
     *
     * **3.37× faster (8.21 → 2.44 ms) and 17.3 → 5.1 MB, with no new model, licence or dataset.**
     * Measured off-device on 360 real frames from `test-video-1.webm`: 96.1 % argmax agreement with
     * fp32, and after [com.haithamassoli.naqi.analysis.NsfwGate]'s hysteresis the censored timeline at
     * the default strictness 50 is a strict SUPERSET of fp32's — 0 ms missed. Worst point of the
     * strictness sweep is 95.5 % interval recall at strictness 0. Hysteresis absorbs the per-frame
     * drift, which is the metric that decides whether a frame gets censored.
     *
     * **Validated on a physical S23** (2026-08-03, `benchmark` build), which `plan-v2` §5.3 insists on and
     * the repo's three fp16-corruption incidents are the reason for. Same 643 s source, same options:
     *
     *   fp32   867 firings   400.7 s censored   gate=61745 ms
     *   INT8   921 firings   416.9 s censored   gate=26844 ms
     *
     * **2.30× on the gate itself** (`session.run` alone; the earlier 3.37× was an off-device microbench
     * with no camera-pipeline contention). INT8 recall of the fp32 censored timeline is **99.20 %** (32 of
     * 4 010 sampled 100 ms points) and it censors 16.2 s MORE — it errs toward covering, which is the
     * direction a censoring product wants.
     *
     * The number is signal, and there is a control proving it: two INT8 runs on identical input scored
     * **100.00 % recall, +0.0 s** against each other, so the run-to-run noise floor is zero. It is worth
     * knowing *why* that control was needed — `intervals` is `intervalsFor(firings) + overflowSpans(faceTracks)`,
     * and the face half comes from ML Kit, which is NOT deterministic (4786 vs 4550 faces on identical
     * input across those same two runs). The censored *timeline* is nonetheless bit-stable, so an EDL diff
     * is a valid gate here even though a face-count diff is not.
     *
     * This also closes the XNNPACK question: `QLinearConv` kernel selection on real Snapdragon hardware
     * does not corrupt the graph — `gateFirings=921` reproduced exactly on three independent S23 runs and
     * matched the emulator, so the fp16 failure mode does not repeat for INT8.
     *
     * The fp32 graph is kept alongside (`nsfw_mnv2_140_f32.onnx`) rather than deleted: swapping
     * [assetName] and [sha256] back is the whole A/B, and it is what a recall regression is diffed
     * against. Do not lower the input resolution as a cheaper alternative — measured much worse
     * (71.7 % agreement @192 vs 91.1 % for INT8@224).
     *
     * Contract unchanged and drop-in: input [1,3,224,224] f32, RGB, scaled 1/255 — no mean/std.
     * Output [1,5] softmax. Class order locked in [NSFW_CLASSES] (upstream alphabetical).
     */
    NSFW_GATE(
        "nsfw_mnv2_140_int8.onnx",
        "6070dd6da875025b4c8df960a3a46dff583fb2bf8a499e214f98a8984674bba9",
        downloadUrl = null, // locally quantized from the tf2onnx GantMan export — no public host yet
        listOf(longArrayOf(1, 3, 224, 224)),
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

    /**
     * YAMNet — Google's Apache-2.0 AudioSet classifier (MobileNet-v1, 4.0 M weights), the music-activity
     * gate [com.haithamassoli.naqi.audio.MusicGate] runs so the separator can skip music-free chunks
     * (A1, `plan-v2` §5.5). Converted from Google's own TF2 SavedModel by `scripts/yamnet_export.py`,
     * opset 15; the upstream embedding and log-mel outputs are trimmed and the input length is pinned.
     *
     * Input `waveform` **[15600] f32 — RANK 1, not [1,15600]** — 0.975 s of mono 16 kHz in [-1,1].
     * Output `output_0` [1,521] f32, per-class scores in `yamnet_class_map.csv` order (the map itself is
     * deliberately not shipped: the gate needs two hardcoded index ranges, not 15 kB of CSV in the APK).
     *
     * **tf2onnx is not byte-reproducible.** Two runs over the same SavedModel produce numerically
     * identical graphs with different serialized bytes, so re-exporting changes this sha256 — take the
     * new one from the script's own output rather than assuming a mismatch means a bad artifact.
     */
    YAMNET(
        "yamnet.onnx",
        "afe82472f2f6250570b63d4f106e7a74b5232cfd17086d39076d80a4273d01f8",
        downloadUrl = null, // locally converted from the Kaggle SavedModel — no public host yet
        listOf(longArrayOf(15600)),
    ),

    /**
     * Face gender/age classifier — InsightFace `buffalo_l`'s own `genderage.onnx`, 1.3 MB, opset 12,
     * shipped verbatim (no conversion, no quantization). Powers the per-track vote behind the
     * Women/Men picker (`plan-censor-who` §3.1, §4). **The licence question was explicitly waived by
     * the owner on 2026-08-03** — it was §3.1's only objection to the obvious candidate, and with it
     * gone this is a 1.3 MB asset in the ORT runtime already here, i.e. no new dependency.
     *
     * **IO contract — and the trap.** Input `data` [N,3,96,96] f32, NCHW **RGB in 0..255 with NO
     * scaling and no mean subtraction** (insightface `Attribute`: `input_mean=0.0, input_std=1.0`).
     * The NSFW gate one entry up is 1/255 on the identical layout, so a copy-pasted fill silently
     * feeds this graph 1/255th of its trained range. Output `fc1` [1,3] is **raw logits, NOT softmax**
     * — unlike the gate, which softmaxes in-graph: `out[0]`=female, `out[1]`=male, `out[2] × 100`=age.
     * gender = `argmax(out[0..1])`, 1 = male, 0 = female; confidence over the two is
     * `1 / (1 + exp(-(out[male] - out[female])))` for the winner.
     *
     * Why this and not the alternatives (§3.1's rejected table, one line each): `gender_googlenet`
     * (Adience) is 23 MB @224² ⇒ ~17–25 ms/crop ⇒ ~300–400 s added to analyze, 6–8× the budget;
     * FairFace (ResNet-34) is ~90 MB for a secondary feature, 5× the NSFW gate, plus session load and
     * RSS; MiVOLO is a ViT at ~90–200 MB that also wants a body crop, out of budget by an order of
     * magnitude; fine-tuning MobileNetV3-Small is the fallback if this misses the bar, never the start.
     *
     * **No landmarks needed** (§3.1 reason 2). InsightFace trains on a SQUARE of side
     * `max(boxW, boxH) × 1.5` centred on the box centre, resized to 96² — and
     * `FaceTracker.KEYFRAME_PAD = 0.25f` already produces exactly that 1.5×. So detection stays on
     * `PERFORMANCE_MODE_FAST` + `enableTracking()`; an ArcFace-aligned model would have forced
     * `LANDMARK_MODE_ALL` and taxed detection on *every* frame, a cost `plan-v2` §5 never budgeted.
     *
     * **On the NudeNet precedent** — the repo has failed this exact task once: `FACE_FEMALE` fired
     * 0.69–0.83 on Einstein/Obama/Trump while `FACE_MALE` stayed ≤ 0.07 (`docs/m0-spikes.md:35`), so
     * the old vote censored ~everyone while claiming to select. But that was a detection class inside
     * a *nudity DETECTOR*, trained where female faces dominate the distribution and never trained to
     * discriminate between the two. This is a two-class classifier trained for precisely that, which
     * is why the repo is trying again rather than treating §3.3's bar as already lost.
     */
    GENDERAGE(
        "genderage.onnx",
        "4fde69b1c810857b88c64a335084f1c3fe8f01246c9a191b48c7bb756d6652fb",
        downloadUrl = null, // ships inside buffalo_l.zip (289 MB); scripts/fetch-models.sh unzips just this file
        listOf(longArrayOf(1, 3, 96, 96)),
    ),
}

/** GantMan class order (alphabetical, index-locked). sfw = drawings+neutral; nsfw = the rest. */
val NSFW_CLASSES = listOf("drawings", "hentai", "neutral", "porn", "sexy")

data class ModelReport(val model: NaqiModel, val bundled: Boolean, val ok: Boolean, val detail: String)

data class SmokeReport(val providers: List<String>, val models: List<ModelReport>)

/**
 * M0 validation: extract each bundled model, create an ORT session on the XNNPACK EP, and run
 * one zero-tensor inference — proves the runtime, the EP registration, and every model's
 * loadability + IO shapes on the actual device. Surfaced in the DEVICE RUNTIME footer.
 */
object ModelSmoke {
    private const val TAG = "ModelSmoke"

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
            imageSessionOptions().use { opts ->
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

}

/**
 * Threads handed to the XNNPACK EP (plan-v2 §5.2, "V2"). The STRUCTURE around it — ORT intra-op 1,
 * spinning off, XNNPACK owning the parallelism — is per ORT's XNNPACK EP guidance and is correct; the
 * NUMBER was never swept. It used to be `availableProcessors` (8 on an S23), which measured off-device
 * on this model class as the *worst* option available:
 *
 * | XNNPACK threads | inferences/s |
 * |---|---:|
 * | 1 | 20.1 |
 * | 2 | **47.8** |
 * | 4 | 42.3 |
 * | 8 *(the old value)* | 19.5 |
 *
 * 8 measured **2.4× worse than 2**. The mechanism transfers even if the absolutes do not: an S23's
 * little cores become stragglers in every parallel conv, the same effect that made 6 beat 8 for
 * htdemucs. 4 is the compromise pending the on-device A/B — **2 / 4 / 6 still need that A/B**, and
 * this constant is the whole of the change needed to run it.
 *
 * Do NOT batch instead: already tested, batch 2 = 0.65×, batch 4 = 0.87×, batch 8 = 0.47× per frame
 * against batch 1. Depthwise-separable convnets saturate at batch 1 on CPU.
 */
private const val XNNPACK_THREADS = 4

/**
 * The image-model ORT session options, shared by [ModelSmoke]'s load check and [Infer]'s real runs so
 * a model can never smoke-test under different options than it infers under. XNNPACK EP; one intra-op
 * thread with spinning disabled, because these run on a worker that is already saturating the CPU.
 * htdemucs deliberately does NOT use this — see `com.haithamassoli.naqi.audio.HtdemucsSession`.
 */
internal fun imageSessionOptions() = OrtSession.SessionOptions().apply {
    setIntraOpNumThreads(1)
    addConfigEntry("session.intra_op.allow_spinning", "0")
    addXnnpack(mapOf("intra_op_num_threads" to XNNPACK_THREADS.toString()))
}
