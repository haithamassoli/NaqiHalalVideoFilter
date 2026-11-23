package com.haithamassoli.naqi.desktop.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.haithamassoli.naqi.audio.DemucsSeparator
import com.haithamassoli.naqi.audio.MusicGate
import com.haithamassoli.naqi.desktop.Models
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// The ORT halves of core's DemucsSeparator and MusicGate, ported from Android's HtdemucsSession.kt and
// MusicGateSession.kt. Both are SERIALIZED: one caller at a time, because the direct input buffers are reused.

private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

/**
 * htdemucs on the CPU EP: `infer` for [DemucsSeparator]. Inputs and outputs are matched by rank (wav 3,
 * spec 4 in; spec 5, time 4 out), as on Android. Only the [keep] stems are copied out of ORT, compacted
 * in that order. The returned arrays are reused by the next call.
 */
internal class HtdemucsSession(file: File, private val keep: IntArray) : AutoCloseable {
    private val session: OrtSession
    private val specOut = FloatArray(keep.size * DemucsSeparator.STEM_SPEC)
    private val timeOut = FloatArray(keep.size * 2 * DemucsSeparator.SEG)
    // Direct, so ORT wraps them instead of allocating ~14 MB of native memory per chunk.
    private val wavDirect = directFloats(2 * DemucsSeparator.SEG)
    private val specDirect = directFloats(DemucsSeparator.STEM_SPEC)

    init {
        require(file.isFile) { "htdemucs model missing: $file (run ./gradlew :app:stageModels)" }
        session = OrtSession.SessionOptions().use { opts ->
            // Android's options, for Android's reasons: the plain CPU EP (XNNPACK's fp16 kernels corrupt
            // this f16 graph's spectral branch), and no arena or memory-pattern planning, which otherwise
            // keep every chunk's multi-GB high-water mark resident for the whole job.
            opts.setIntraOpNumThreads(INTRA_OP_THREADS)
            opts.addConfigEntry("session.intra_op.allow_spinning", "0")
            opts.setCPUArenaAllocator(false)
            opts.setMemoryPatternOptimization(false)
            // The f16 graph's Sin/Cos/Mul nodes print a "can't constant fold" warning each, every session: noise
            // in the CLI's output for something that only means those nodes stay in the graph.
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            env.createSession(file.absolutePath, opts)
        }
    }

    fun infer(wav: FloatArray, spec: FloatArray): Pair<FloatArray, FloatArray> {
        wavDirect.clear(); wavDirect.put(wav); wavDirect.rewind()
        specDirect.clear(); specDirect.put(spec); specDirect.rewind()
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
                    val fb = tensor.floatBuffer // a fresh view per call: take it once, then seek forward
                    when ((tensor.info as TensorInfo).shape.size) {
                        5 -> for (k in keep.indices) { // [1,4,4,BINS,LE] masked spec, stems contiguous
                            fb.position(keep[k] * DemucsSeparator.STEM_SPEC)
                            fb.get(specOut, k * DemucsSeparator.STEM_SPEC, DemucsSeparator.STEM_SPEC)
                        }
                        4 -> for (k in keep.indices) { // [1,4,2,SEG] time branch
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

    override fun close() = session.close()

    private companion object {
        /**
         * Android's cap, swept on the S23 (its little cores slow every intra-op barrier). Not re-swept for
         * desktop yet: on the M3 (4P+4E) 4, 6 and 8 threads all landed between 0.39x and 0.57x realtime
         * on 30 s, but the machine was at load ~12 from other builds, so that spread is noise.
         */
        val INTRA_OP_THREADS = Runtime.getRuntime().availableProcessors().coerceAtMost(6)
    }
}

/**
 * YAMNet behind core's [MusicGate], or null when it cannot be opened (model absent, ORT refused it): null
 * means separate every chunk, the only safe failure for a gate whose job is deciding what NOT to process.
 * One intra-op thread, as Android's image options, minus XNNPACK (not in the desktop ORT build): three
 * ~1 ms runs per 2.6 s chunk do not need more.
 */
internal fun openGate(modelsDir: File): MusicGate? {
    val file = File(modelsDir, Models.YAMNET)
    if (!file.isFile) {
        System.err.println("MusicGate: $file missing, separating every chunk")
        return null
    }
    return runCatching {
        val session = OrtSession.SessionOptions().use { opts ->
            opts.setIntraOpNumThreads(1)
            opts.addConfigEntry("session.intra_op.allow_spinning", "0")
            env.createSession(file.absolutePath, opts)
        }
        val inputName = session.inputNames.first()
        MusicGate(
            infer = { frame, scores ->
                // Rank 1: the exported graph's input is [15600], not [1,15600].
                OnnxTensor.createTensor(env, frame, longArrayOf(MusicGate.FRAME.toLong())).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        (result[0] as OnnxTensor).floatBuffer.get(scores)
                    }
                }
            },
            release = session::close,
        )
    }.onFailure { System.err.println("MusicGate: yamnet session failed, separating every chunk: $it") }
        .getOrNull()
}

private fun directFloats(n: Int): FloatBuffer =
    ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
