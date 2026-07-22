@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.haithamassoli.naqi.spike

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * M0 SPIKE — Media3 Transformer feasibility.
 *
 * Decision (recon-verified against media3 1.10.1): Transformer covers BOTH de-risk questions —
 * a custom [GlShaderProgram] video effect AND audio replacement via a multi-sequence Composition
 * (a video-only sequence with source audio removed + a separate audio-only sequence; the separate
 * track effectively replaces the original). No raw-MediaCodec fallback needed. See docs/m0-spikes.md.
 *
 * Call on a thread with a Looper (e.g. Dispatchers.Main): Transformer's callbacks run on the
 * thread that built it, and one Transformer runs one export at a time.
 */
object GrayscaleTransformerSpike {

    suspend fun exportGrayscale(
        context: Context,
        inputVideoUri: String,
        outputPath: String,
        replaceAudioUri: String? = null,
        useCustomShader: Boolean = false,
    ): ExportResult = suspendCancellableCoroutine { cont ->
        // Built-in grayscale is the production path; the custom shader proves the GlEffect API.
        val grayscale: Effect = if (useCustomShader) GrayscaleGlEffect() else RgbFilter.createGrayscaleFilter()

        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(inputVideoUri))
            .setEffects(Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ listOf(grayscale)))
            .setRemoveAudio(true)
            .build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    if (cont.isActive) cont.resume(result)
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            })
            .build()
        cont.invokeOnCancellation { runCatching { transformer.cancel() } }

        if (replaceAudioUri == null) {
            transformer.start(videoItem, outputPath)
        } else {
            val videoSeq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(videoItem).build()
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(replaceAudioUri)).build()
            val audioSeq = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
                .addItem(audioItem)
                .setIsLooping(true) // loop the shorter audio to fill the longer video
                .build()
            transformer.start(Composition.Builder(videoSeq, audioSeq).build(), outputPath)
        }
    }
}

/** Custom grayscale effect — de-risks the custom-shader API (fallback to built-in RgbFilter in prod). */
class GrayscaleGlEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        GrayscaleShaderProgram(useHdr)
}

private class GrayscaleShaderProgram(useHdr: Boolean) :
    BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    private val glProgram: GlProgram = try {
        GlProgram(
            """
            #version 100
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
            """.trimIndent(),
            """
            #version 100
            precision mediump float;
            uniform sampler2D uTexSampler;
            varying vec2 vTexCoord;
            void main() {
              vec4 c = texture2D(uTexSampler, vTexCoord);
              float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)); // BT.709
              gl_FragColor = vec4(vec3(luma), c.a);
            }
            """.trimIndent(),
        )
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(inputWidth, inputHeight)

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
