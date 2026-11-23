package com.haithamassoli.naqi.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.util.Log
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.NaqiModel
import com.haithamassoli.naqi.ml.imageSessionOptions

// The Android half of [MusicGate]: where the YAMNet session comes from. The gate's policy lives in core.
// Extensions on the companion so call sites keep reading `MusicGate.openIfLong(...)`.

private const val TAG = "MusicGate"

private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

/**
 * Open the gate, or **null when it cannot be opened** — the model is not installed (the asset is
 * gitignored, so a fresh clone has no `yamnet.onnx` until `scripts/fetch-models.sh` runs) or ORT
 * refused it. Null means every chunk gets separated, i.e. exactly the pre-A1 behaviour, which is
 * the only safe failure for a gate whose job is deciding what NOT to process.
 */
fun MusicGate.Companion.open(context: Context): MusicGate? {
    val file = ModelSmoke.modelFile(context, NaqiModel.YAMNET)
    if (file == null) {
        Log.w(TAG, "yamnet not installed — separating every chunk (A1 disabled)")
        return null
    }
    return runCatching {
        // The same options ModelSmoke load-checks it under, so the gate can never infer under
        // options its smoke test never saw. XNNPACK's thread count is irrelevant here: three
        // ~1 ms runs against a ~2100 ms chunk.
        val session = imageSessionOptions().use { env.createSession(file.absolutePath, it) }
        val inputName = session.inputNames.first()
        MusicGate(
            infer = { frame, scores ->
                OnnxTensor.createTensor(env, frame, longArrayOf(MusicGate.FRAME.toLong())).use { tensor ->
                    session.run(mapOf(inputName to tensor)).use { result ->
                        (result[0] as OnnxTensor).floatBuffer.get(scores)
                    }
                }
            },
            release = session::close,
        )
    }.onFailure { Log.w(TAG, "yamnet session failed — separating every chunk (A1 disabled)", it) }
        .getOrNull()
}

/** [open] only for audio of [MusicGate.LONG_FRAMES] or more ([estFrames] at 44.1 kHz). Null = separate everything. */
fun MusicGate.Companion.openIfLong(context: Context, estFrames: Long): MusicGate? =
    if (estFrames >= MusicGate.LONG_FRAMES) open(context) else null
