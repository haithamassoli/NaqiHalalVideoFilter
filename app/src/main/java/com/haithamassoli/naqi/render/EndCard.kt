@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.haithamassoli.naqi.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.opengl.GLES20
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import com.haithamassoli.naqi.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The 2-second Naqi outro appended to a re-encoded export (`docs/endcard/endcard-mockup.html`).
 *
 * It rides in the SAME Transformer export as the video, as a second [EditedMediaItem] in the sequence:
 * one encoder session, so the card's samples share the video's track format — which is what lets a
 * segmented job put it on its last segment and still pass `Remux.concat`'s one-format check. The item
 * is a 1x1 still whose pixels are ignored; [EndCardGlEffect] draws every frame from its timestamp.
 *
 * Silent by decision: the image item has no audio, so Transformer pads the card with silence when the
 * export carries audio, and a video-only export (combined, segmented) just ends its audio first.
 */
internal object EndCard {
    const val DURATION_MS = 2000L

    /** A 1x1 PNG of the card background. A `data:` URI, so there is no file to write or clean up. */
    private const val STILL =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGPgERUCAABkADQdRPiQAAAAAElFTkSuQmCC"

    fun item(handoff: EndCardHandoff, fps: Float): EditedMediaItem =
        EditedMediaItem.Builder(
            MediaItem.Builder()
                .setUri(STILL)
                .setMimeType(MimeTypes.IMAGE_PNG)
                .setImageDurationMs(DURATION_MS)
                .build(),
        )
            .setFrameRate(fps.takeIf { it.isFinite() && it >= 1f }?.roundToInt()?.coerceAtMost(60) ?: 30)
            .setEffects(Effects(/* audioProcessors = */ emptyList(), listOf(EndCardGlEffect(handoff))))
            .build()
}

/**
 * What the card needs from the video item before it, written by [CensorGlEffect] on the GL thread
 * that both items share (one VideoFrameProcessor, one GL context for the whole export).
 *
 * [width]/[height]/[rotation] are the video's frames as the effects saw them, so the card's frames are
 * the same size (the encoder surface is sized once, from the video) and drawn in the same orientation:
 * where media3 hands effects stored-orientation frames (see [CensorGlEffect]), an upright card would
 * play back rotated. Seeded with the upright size in case the video never configured.
 *
 * [lastFrameTex] is a copy of the newest output frame from [tailFromUs] on, so the card can fade in
 * over the video's real last frame. Owned here, not by either program: the video's shader program is
 * released before the card's is created.
 */
class EndCardHandoff(var width: Int, var height: Int, private val tailFromUs: Long) {
    var rotation = 0
    var lastFrameTex = -1

    /** Copies the frame just drawn into the focused output framebuffer, near the end of the clip only. */
    fun capture(presentationTimeUs: Long, useHdr: Boolean) {
        if (presentationTimeUs < tailFromUs) return
        if (lastFrameTex < 0) lastFrameTex = GlUtil.createTexture(width, height, useHdr)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lastFrameTex)
        GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height)
        GlUtil.checkGlError()
    }
}

internal fun clamp01(x: Float) = min(1f, max(0f, x))
internal fun seg(t: Float, a: Float, b: Float) = clamp01((t - a) / (b - a))
internal fun outCubic(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
internal fun outBack(x: Float): Float {
    val c = 1.9f
    val y = x - 1f
    return 1f + (c + 1f) * y * y * y + c * y * y
}

/** Every animated value of the card at [t] seconds into it — the mockup's `drawFrame` timeline, verbatim. */
internal class EndCardPose(t: Float) {
    /** Card over the last video frame, 0..1. */
    val fade = seg(t, 0f, 0.15f)
    /** Glow progress; drawn only above 0. */
    val glow = seg(t, 0.6f, 1.3f)
    /** Fraction of the arc drawn, from its left end through the bottom. */
    val arc = outCubic(seg(t, 0.05f, 0.55f))
    private val drop = seg(t, 0.35f, 0.7f)
    val dropAlpha = if (drop > 0f) clamp01(drop * 3f) else 0f
    /** Vertical offset in mark units; negative is above its resting place. */
    val dropOffset = -18f * (1f - outBack(drop))
    val arabic = outCubic(seg(t, 0.7f, 1.1f))
    val english = outCubic(seg(t, 0.82f, 1.22f))
}

private class EndCardGlEffect(private val handoff: EndCardHandoff) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        EndCardShaderProgram(context, handoff, useHdr)
}

/**
 * Draws [EndCardPose] with [Canvas] — the platform's text stack shapes the Arabic line, RTL with its
 * diacritics, which no GL text path here could — into an upright bitmap, then composites that over
 * the captured last frame in the frames' own orientation.
 */
private class EndCardShaderProgram(
    context: Context,
    private val handoff: EndCardHandoff,
    private val useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private val program = try {
        GlProgram(CARD_VERTEX, CARD_FRAGMENT).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    private val bold = context.resources.getFont(R.font.thmanyah_sans_bold)
    private val medium = context.resources.getFont(R.font.thmanyah_sans_medium)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcOval = RectF(54f - 13.36f, 57.09f - 13.36f, 54f + 13.36f, 57.09f + 13.36f)
    private val dropPath = Path().apply {
        moveTo(49.36f, 34.31f)
        lineTo(49.36f, 46.97f)
        lineTo(61.17f, 40.64f)
        close()
    }

    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private var cardTex = -1
    private var firstUs = C.TIME_UNSET

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        // The input is the 1x1 still: the output takes the video's size instead.
        val upright = handoff.rotation % 180 == 0
        val w = if (upright) handoff.width else handoff.height
        val h = if (upright) handoff.height else handoff.width
        if (bitmap?.width != w || bitmap?.height != h) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { canvas = Canvas(it) }
            deleteCardTex()
        }
        return Size(handoff.width, handoff.height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // Item timestamps carry the video's duration as an offset; the card's clock starts at its first frame.
        if (firstUs == C.TIME_UNSET) firstUs = presentationTimeUs
        val pose = EndCardPose((presentationTimeUs - firstUs) / 1_000_000f)
        val bmp = bitmap!!
        paint(canvas!!, bmp.width.toFloat(), bmp.height.toFloat(), pose)
        try {
            if (cardTex < 0) cardTex = GlUtil.createTexture(bmp) else GlUtil.setTexture(cardTex, bmp)
            val hasLast = handoff.lastFrameTex >= 0
            program.use()
            program.setSamplerTexIdUniform("uCardTex", cardTex, /* texUnitIndex = */ 0)
            // Unused when there is no captured frame, but the sampler must still bind a complete texture.
            program.setSamplerTexIdUniform("uLastTex", if (hasLast) handoff.lastFrameTex else cardTex, /* texUnitIndex = */ 1)
            program.setIntUniform("uHasLast", if (hasLast) 1 else 0)
            program.setFloatUniform("uFade", pose.fade)
            program.setIntUniform("uRotation", handoff.rotation)
            program.setFloatsUniform("uBackground", solidRgb(BACKGROUND))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    /** The mockup's `drawFrame` for t >= 0, minus the fade, which the shader does against the real frame. */
    private fun paint(canvas: Canvas, w: Float, h: Float, pose: EndCardPose) {
        canvas.drawColor(BACKGROUND)
        val s = min(w, h)
        val unit = s * 0.26f / 45.7f
        val cx = w / 2f
        val cy = h / 2f - s * 0.09f

        paint.reset()
        paint.isAntiAlias = true
        if (pose.glow > 0f) {
            val r = s * (0.18f + 0.12f * outCubic(pose.glow))
            val a = 0.22f * (1f - pose.glow * 0.55f)
            paint.shader = RadialGradient(
                cx, cy, r,
                Color.argb(a, 85 / 255f, 195 / 255f, 161 / 255f), Color.argb(0f, 85 / 255f, 195 / 255f, 161 / 255f),
                Shader.TileMode.CLAMP,
            )
            // The gradient is transparent from r out, so its bounding circle is the whole of it.
            canvas.drawCircle(cx, cy, r, paint)
            paint.shader = null
        }

        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(unit, unit)
        canvas.translate(-54f, -53.55f)
        paint.color = JADE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        if (pose.arc > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 7.88f
            // Left end (180 deg) counter-clockwise through the bottom: the SVG arc, trimmed from its start.
            canvas.drawArc(arcOval, 180f, -180f * pose.arc, false, paint)
        }
        if (pose.dropAlpha > 0f) {
            canvas.save()
            canvas.translate(0f, pose.dropOffset)
            paint.alpha = (pose.dropAlpha * 255f).roundToInt()
            paint.style = Paint.Style.FILL
            canvas.drawPath(dropPath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.23f
            canvas.drawPath(dropPath, paint)
            canvas.restore()
        }
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        if (pose.arabic > 0f) {
            paint.typeface = bold
            paint.textSize = s * 0.082f
            paint.letterSpacing = 0f
            paint.color = PAPER
            paint.alpha = (pose.arabic * 255f).roundToInt()
            canvas.drawText(ARABIC, cx, cy + s * 0.27f + s * 0.04f * (1f - pose.arabic), paint)
        }
        if (pose.english > 0f) {
            paint.typeface = medium
            paint.textSize = s * 0.046f
            paint.letterSpacing = 0.004f / 0.046f // s*0.004 px, in ems of an s*0.046 font
            paint.color = PAPER
            paint.alpha = (pose.english * 0.62f * 255f).roundToInt()
            canvas.drawText(ENGLISH, cx, cy + s * 0.355f + s * 0.04f * (1f - pose.english), paint)
        }
    }

    private fun deleteCardTex() {
        if (cardTex >= 0) GlUtil.deleteTexture(cardTex)
        cardTex = -1
    }

    override fun release() {
        super.release()
        try {
            program.delete()
            deleteCardTex()
            if (handoff.lastFrameTex >= 0) GlUtil.deleteTexture(handoff.lastFrameTex)
            handoff.lastFrameTex = -1
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        bitmap?.recycle()
        bitmap = null
    }
}

private const val BACKGROUND = 0xFF0C1512.toInt()
private const val JADE = 0xFF55C3A1.toInt()
private const val PAPER = 0xFFF5F7F3.toInt()

/** "نُقّي بتطبيق نقي", escaped so the damma and shadda survive any editor. */
private const val ARABIC =
    "نُقّي بتطبيق نقي"
private const val ENGLISH = "Filtered with Naqi"

private val CARD_VERTEX = """
    #version 100
    attribute vec4 aFramePosition;
    varying vec2 vTexCoord;
    void main() {
      gl_Position = aFramePosition;
      vTexCoord = aFramePosition.xy * 0.5 + 0.5;
    }
    """.trimIndent()

// vTexCoord is y-up over the output frame; the card bitmap is upright and y-down (row 0 at t = 0).
// For stored-orientation frames each output pixel is mapped back to its upright position, the same
// stored -> upright inverse rasterizeMasks uses.
private val CARD_FRAGMENT = """
    #version 100
    precision mediump float;
    uniform sampler2D uCardTex;
    uniform sampler2D uLastTex;
    uniform int uHasLast;
    uniform float uFade;
    uniform int uRotation;
    uniform vec3 uBackground;
    varying vec2 vTexCoord;
    void main() {
      vec2 s = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
      vec2 u = s;
      if (uRotation == 90) u = vec2(1.0 - s.y, s.x);
      else if (uRotation == 180) u = vec2(1.0 - s.x, 1.0 - s.y);
      else if (uRotation == 270) u = vec2(s.y, 1.0 - s.x);
      vec3 card = texture2D(uCardTex, u).rgb;
      vec3 last = (uHasLast == 1) ? texture2D(uLastTex, vTexCoord).rgb : uBackground;
      gl_FragColor = vec4(mix(last, card, uFade), 1.0);
    }
    """.trimIndent()
