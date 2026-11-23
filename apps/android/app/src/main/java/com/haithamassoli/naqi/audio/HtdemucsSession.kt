package com.haithamassoli.naqi.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.NaqiModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * ORT session owner for [NaqiModel.HTDEMUCS] — provides the real `infer` for [DemucsSeparator].
 * CPU-EP session options (see [sessionOptions]). Inputs are matched to the graph by rank (wav =
 * rank 3, spec = rank 4), outputs likewise (spec = rank 5, time = rank 4). Input tensors are backed
 * by two DIRECT buffers allocated once and reused every chunk — passing heap arrays makes ORT
 * allocateDirect ~14 MB per call, and that non-movable churn OOMs ART's 256 MB heap mid-job. The
 * returned arrays are REUSED across calls too — the caller must consume them before the next
 * [infer] (DemucsSeparator does, within the same chunk).
 *
 * [keep] is the ascending set of stems to read back, from [DemucsSeparator.keptStems]. Only those come
 * out of ORT and the outputs are compacted to them (A4, `plan-v2` §5.9): the graph emits all four either
 * way, but drums and bass are 14.7 MB of spec + 3.7 MB of time per chunk that the caller discarded on
 * arrival, so copying them was pure memcpy for the bin.
 */
class HtdemucsSession(context: Context, private val keep: IntArray) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val specOut = FloatArray(keep.size * DemucsSeparator.STEM_SPEC)
    private val timeOut = FloatArray(keep.size * 2 * DemucsSeparator.SEG)
    private val wavDirect: FloatBuffer = ByteBuffer.allocateDirect(2 * DemucsSeparator.SEG * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val specDirect: FloatBuffer = ByteBuffer.allocateDirect(DemucsSeparator.STEM_SPEC * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    init {
        val file = ModelSmoke.modelFile(context, NaqiModel.HTDEMUCS)
            ?: error("htdemucs model not bundled — run scripts/fetch-models.sh")
        // C2 (`perf-plan-v4` §5, setOptimizedModelFilePath) was implemented here and REMOVED after M2
        // measured what §5 could only call "NOT ESTIMABLE until M2". On an S23 over the 643 s tv1.webm:
        //
        //     sessionCreateMs = 881          i.e. 0.23 % of a 385 420 ms `separate` stage
        //     serialized graph = 157.6 MB    against the 87.9 MB .onnx it is built from
        //     peak RSS         = 1 267 532 -> 1 401 700 KB
        //
        // The graph is nearly 2x the model because ORT demotes the fp16 initializers to fp32 at load
        // (`perf-plan-v4` §6.1) and serializes the demoted form. So the trade was 157 MB of the user's
        // storage plus 10.6 % of a RAM budget lmkd has already killed this app over, to save at most
        // 0.9 s per session create — and even the film path, which pays it once per resume, cannot turn
        // that into a number worth the disk. Do not re-add it without a measurement that beats these.
        session = sessionOptions().use { env.createSession(file.absolutePath, it) }
    }

    fun infer(wav: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
        wavDirect.clear()
        wavDirect.put(wav)
        wavDirect.rewind()
        specDirect.clear()
        specDirect.put(spec)
        specDirect.rewind()
        val feeds = HashMap<String, OnnxTensor>(2)
        try {
            for ((name, info) in session.inputInfo) {
                feeds[name] = when ((info.info as TensorInfo).shape.size) {
                    3 -> OnnxTensor.createTensor(env, wavDirect, longArrayOf(1, 2, DemucsSeparator.SEG.toLong()))
                    4 -> OnnxTensor.createTensor(
                        env, specDirect,
                        longArrayOf(1, 4, DemucsSeparator.BINS.toLong(), DemucsSeparator.LE.toLong()),
                    )
                    else -> error("unexpected htdemucs input rank for $name")
                }
            }
            session.run(feeds).use { result ->
                for (i in 0 until result.size()) {
                    val tensor = result[i] as OnnxTensor
                    // One buffer view per output — `floatBuffer` hands back a fresh view each call, so
                    // taking it once is what makes the position() walk below mean anything. Stems are one
                    // contiguous block each in both outputs, and [keep] is ascending, so this only ever
                    // seeks forward.
                    val fb = tensor.floatBuffer
                    when ((tensor.info as TensorInfo).shape.size) {
                        5 -> for (k in keep.indices) {       // [1,4,4,BINS,LE] masked spec
                            fb.position(keep[k] * DemucsSeparator.STEM_SPEC)
                            fb.get(specOut, k * DemucsSeparator.STEM_SPEC, DemucsSeparator.STEM_SPEC)
                        }
                        4 -> for (k in keep.indices) {       // [1,4,2,SEG] time branch
                            fb.position(keep[k] * 2 * DemucsSeparator.SEG)
                            fb.get(timeOut, k * 2 * DemucsSeparator.SEG, 2 * DemucsSeparator.SEG)
                        }
                        else -> error("unexpected htdemucs output rank")
                    }
                }
            }
        } finally {
            feeds.values.forEach { it.close() }
        }
        return specOut to timeOut
    }

    override fun close() {
        session.close()
    }

    // Deliberately NOT com.haithamassoli.naqi.ml.imageSessionOptions: that one is XNNPACK + a single
    // intra-op thread, and neither is right here. Plus memory clamps — the default BFC arena +
    // memory-pattern planning retain multi-GB high-water allocations across htdemucs runs (lmkd
    // killed the app at 5.6 GB RSS without these two).
    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        // CPU EP (multi-threaded), NOT XNNPACK: XNNPACK's fp16 kernels corrupt this f16 graph's
        // spectral branch on-device (broadband-noise stems; time branch survives) — same family of
        // fp16 defects that already disqualified XNNPACK for the NSFW model in M0.
        // Arena OFF: with the arena, each run's high-water stays resident across every chunk and
        // Samsung's global memory watchdog kills the app; without it RSS drops back between chunks
        // and long jobs survive. The peak itself is intrinsic to the graph (NOT thread scratch —
        // 1 thread peaks HIGHER), which is why the graph was re-exported at the 2.6 s [SEG] it ships at
        // today (M3 first went to 3.9 s; `perf-plan-v4` §6.2 then measured 2.6 s as the OPTIMUM rather
        // than a RAM compromise — 7.8 s costs +20.6 % compute per second of audio as well as 3.24 GB).
        // Neither of those two is a dial. The two below are — see their declarations.
        setIntraOpNumThreads(INTRA_OP_THREADS)
        addConfigEntry("session.intra_op.allow_spinning", ALLOW_SPINNING)
        setCPUArenaAllocator(false)
        setMemoryPatternOptimization(false)
    }

    companion object {

        /**
         * The two dials of this stage, both SWEPT on an S23 2026-07-28 (`perf-plan.md` Phase 3.1/3.2),
         * median per-chunk ms over chunks 3-7:
         *
         *     threads 8 (=availableProcessors) 2244 | 6 **2136** | 4 2155      spinning 0 **2244** | 1 2305
         *
         * 6 wins by ~5 % because every intra-op barrier waits on the slowest thread and the S23's little
         * cores are it — 4 of 6's five chunks came in under 8's fastest. Capped rather than hardcoded so a
         * 4-core device still gets 4. Spinning stays "0": it was chosen for power and is ALSO faster here,
         * so there is nothing left to trade. Peak RSS moved <0.2 % across every config.
         */
        private val INTRA_OP_THREADS = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
        private const val ALLOW_SPINNING = "0"
    }
}
