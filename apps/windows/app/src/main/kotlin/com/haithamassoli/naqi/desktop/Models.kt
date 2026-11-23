package com.haithamassoli.naqi.desktop

import java.io.File

/**
 * Where the bundled ONNX files live. The names are the files scripts/stage-models.sh stages and pins; the
 * tensor contracts are Android's (`apps/android/.../ml/Models.kt`) except [YUNET], which is Windows-only.
 */
object Models {
    const val YUNET = "face_detection_yunet_2023mar.onnx"
    const val GENDERAGE = "genderage.onnx"
    const val NSFW = "nsfw_mnv2_140_int8.onnx"
    const val YAMNET = "yamnet.onnx"
    const val HTDEMUCS = "htdemucs_s26_f16.onnx"

    /**
     * `-Dnaqi.models.dir`, then `NAQI_MODELS_DIR`, then the `models/` app resource that the packaged app
     * and `./gradlew :app:run` both get through `compose.application.resources.dir`. The overrides come
     * first so a developer or a test can point at another set without repackaging.
     */
    fun dir(): File = resolveDir(
        System.getProperty("naqi.models.dir"),
        System.getenv("NAQI_MODELS_DIR"),
        System.getProperty("compose.application.resources.dir"),
    )

    /** The model file, or null when it is not there (a test without staged models, a broken install). */
    fun fileOrNull(name: String): File? = File(dir(), name).takeIf { it.isFile }

    internal fun resolveDir(property: String?, env: String?, resourcesDir: String?): File = when {
        !property.isNullOrBlank() -> File(property)
        !env.isNullOrBlank() -> File(env)
        !resourcesDir.isNullOrBlank() -> File(resourcesDir, "models")
        else -> error("No models directory: set -Dnaqi.models.dir or NAQI_MODELS_DIR, or run the packaged app")
    }
}
