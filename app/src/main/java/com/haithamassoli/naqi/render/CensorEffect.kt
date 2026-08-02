@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.haithamassoli.naqi.render

import android.content.Context
import android.opengl.GLES20
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.edl.Edl
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "CensorEffect"

/** Gaussian half-width cap; the shader's `uWeights` array has [MAX_RADIUS] + 1 entries (center + sides). */
private const val MAX_RADIUS = 10

/** Shader `uRegions` array size; more active regions than this are dropped, largest kept (never silently). */
private const val MAX_REGIONS = 8

/**
 * M1 pass-2 render effect. Per output frame the [Edl] selects one of: whole-frame censor, a list of
 * regions, or nothing. Censored pixels are separable-Gaussian blurred (when [blurAmount] > 0) and/or
 * BT.709 grayscaled (when [grayscale]); original pixels are kept elsewhere. A region's edge is
 * feathered OUTWARD (see [COMPOSITE_FRAGMENT]) so a face fades into the frame instead of sitting in a
 * visible rectangle — the rect itself stays fully censored, only pixels outside it are partially so.
 *
 * Regions arrive normalized [0,1] in UPRIGHT, top-left-origin frame space (see analysis.Contracts).
 * Whether effects receive upright or stored-orientation frames is decoder-dependent in media3 1.10
 * (observed BOTH on one S23: rotation-90 input arrived pre-rotated upright with rotation dropped
 * from the muxer; rotation-270 arrived in stored orientation with the display matrix forwarded), so
 * the shader decides per stream in configure(): frame dims matching the probed STORED dims mean
 * stored orientation and each rect is mapped via [NRect.toStoredSpace]; otherwise frames are
 * already upright and rects pass through. [meta] supplies the probed dims + rotation.
 */
class CensorGlEffect(
    private val edl: Edl,
    private val blurAmount: Int,
    private val grayscale: Boolean,
    private val meta: VideoMeta,
    private val timeOffsetMs: Long = 0L,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        CensorShaderProgram(edl, blurAmount, grayscale, meta, useHdr, timeOffsetMs)
}

/**
 * Multi-pass shader program behind [CensorGlEffect]. When blur is active it renders two separable
 * passes into downscaled scratch framebuffers, then a final composite into Base's output framebuffer;
 * the save/restore of the framebuffer binding + viewport around the scratch passes is mandatory.
 */
private class CensorShaderProgram(
    private val edl: Edl,
    private val blurAmount: Int,
    private val grayscale: Boolean,
    private val meta: VideoMeta,
    private val useHdr: Boolean,
    private val timeOffsetMs: Long,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity = */ 1) {

    private val blurEnabled = blurAmount > 0

    /** 0 when incoming frames are already upright; the source rotation when they are stored-orientation. */
    private var mapRotation = 0

    private val copyProgram = program(COPY_FRAGMENT)
    private val compositeProgram = program(COMPOSITE_FRAGMENT)
    private val blurProgram = if (blurEnabled) program(BLUR_FRAGMENT) else null

    // GlProgram can only set scalar/vector uniforms (count 1, float[16] backing); array uniforms are
    // uploaded directly by location — glUniform4fv/glUniform1fv with the true element count.
    private val compRegionsLoc = compositeProgram.arrayUniformLocation("uRegions")
    private val blurWeightsLoc = blurProgram?.arrayUniformLocation("uWeights") ?: -1

    // Blur scratch, sized to the downscaled blur resolution; recreated when the frame size changes.
    private var lowW = 0
    private var lowH = 0
    private var radius = 0
    private var kernel = FloatArray(MAX_RADIUS + 1) // normalized weights, [0] = center; tail stays 0
    private val scratchTex = intArrayOf(-1, -1)
    private val scratchFbo = intArrayOf(-1, -1)

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        mapRotation = when {
            meta.rotationDegrees % 180 == 0 -> {
                // 0 needs no mapping; a baked-vs-forwarded 180 is dimension-invisible — assume the
                // media3-documented decoder pre-rotation (upright). QA a real 180 asset in M3.
                if (meta.rotationDegrees == 180) Log.w(TAG, "180-rotated source: assuming pre-rotated frames")
                0
            }
            inputWidth == meta.width && inputHeight == meta.height -> {
                if (meta.width == meta.height) Log.w(TAG, "square rotated source: orientation ambiguous, mapping as stored")
                meta.rotationDegrees // stored-orientation frames: map rects upright -> stored
            }
            else -> 0 // dims already swapped: frames arrived upright
        }
        if (blurEnabled) {
            // sigma in full-res pixels, scaled by blur amount and resolution (1080p reference);
            // keyed on the short side so rotated and true-portrait videos blur identically.
            val sigmaPx = max(0.1f, blurAmount / 100f * 40f * (min(inputWidth, inputHeight) / 1080f))
            // Smallest downscale in {1,2,4,8} keeping the low-res sigma <= 4 (fall back to 8).
            val d = intArrayOf(1, 2, 4, 8).firstOrNull { sigmaPx / it <= 4f } ?: 8
            val newLowW = max(1, inputWidth / d)
            val newLowH = max(1, inputHeight / d)
            val sigmaLow = sigmaPx / d
            radius = min(MAX_RADIUS, ceil(2.5f * sigmaLow).toInt()).coerceAtLeast(1)
            kernel = gaussianKernel(sigmaLow, radius)
            if (newLowW != lowW || newLowH != lowH || scratchTex[0] < 0) {
                try {
                    releaseScratch()
                    lowW = newLowW
                    lowH = newLowH
                    for (i in 0..1) {
                        scratchTex[i] = GlUtil.createTexture(lowW, lowH, useHdr)
                        scratchFbo[i] = GlUtil.createFboForTexture(scratchTex[i])
                    }
                } catch (e: GlUtil.GlException) {
                    throw VideoFrameProcessingException(e)
                }
            }
        }
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        // EDL/analysis timeline is in ms; Media3 presentation time is in us — convert at this boundary.
        //
        // [timeOffsetMs] rebases a clipped (Phase 2 segment) export back onto the whole-timeline EDL.
        // Media3 hands effects CLIP-RELATIVE timestamps: ExoAssetLoaderVideoRenderer computes
        // `presentationTimeUs = decoderOutput.presentationTimeUs - streamStartPositionUs` (media3 1.10.1
        // sources, ExoAssetLoaderVideoRenderer.java:185), so a segment starting at 5 min sees its first
        // frame as 0. Without the offset every segment past the first would censor the wrong moments.
        val tMs = presentationTimeUs / 1000 + timeOffsetMs
        val full = edl.fullFrameAt(tMs)
        val regions =
            if (full) emptyList() else edl.regionsAt(tMs).map { it.toStoredSpace(mapRotation) }
        try {
            if (!full && regions.isEmpty()) {
                drawCopy(inputTexId) // nothing active: passthrough into the already-focused output FBO
                return
            }
            val kept = if (regions.size <= MAX_REGIONS) {
                regions
            } else {
                Log.w(TAG, "${regions.size} regions active at ${tMs}ms; keeping $MAX_REGIONS largest")
                regions.sortedByDescending { it.width * it.height }.take(MAX_REGIONS)
            }
            if (blurEnabled) {
                // Save the output framebuffer + viewport, blur into scratch, then restore before compositing.
                val prevFbo = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }
                val prevVp = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
                blurPass(inputTexId, scratchFbo[0], 1f / lowW, 0f)    // horizontal: input -> scratch0 (downscales)
                blurPass(scratchTex[0], scratchFbo[1], 0f, 1f / lowH) // vertical:   scratch0 -> scratch1
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0])
                GLES20.glViewport(prevVp[0], prevVp[1], prevVp[2], prevVp[3])
            }
            drawComposite(inputTexId, full, kept)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun blurPass(srcTexId: Int, dstFbo: Int, stepX: Float, stepY: Float) {
        val p = blurProgram!!
        GlUtil.focusFramebufferUsingCurrentContext(dstFbo, lowW, lowH)
        p.use()
        p.setSamplerTexIdUniform("uTexSampler", srcTexId, /* texUnitIndex = */ 0)
        p.setFloatsUniform("uTexelStep", floatArrayOf(stepX, stepY))
        p.setIntUniform("uRadius", radius)
        p.bindAttributesAndUniforms()
        GLES20.glUniform1fv(blurWeightsLoc, kernel.size, kernel, /* offset = */ 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
    }

    private fun drawComposite(inputTexId: Int, full: Boolean, regions: List<NRect>) {
        val p = compositeProgram
        p.use()
        p.setSamplerTexIdUniform("uInputTex", inputTexId, /* texUnitIndex = */ 0)
        // When blur is off, uBlurTex is unused at runtime (uUseBlur=0) but must still bind a complete texture.
        p.setSamplerTexIdUniform("uBlurTex", if (blurEnabled) scratchTex[1] else inputTexId, /* texUnitIndex = */ 1)
        p.setIntUniform("uCensorAll", if (full) 1 else 0)
        p.setIntUniform("uUseBlur", if (blurEnabled) 1 else 0)
        p.setIntUniform("uGrayscale", if (grayscale) 1 else 0)
        p.setIntUniform("uRegionCount", regions.size)
        p.bindAttributesAndUniforms()
        if (regions.isNotEmpty()) {
            val data = FloatArray(regions.size * 4)
            regions.forEachIndexed { i, r ->
                // NRect is top-left-origin (y-down); GL tex coords are y-up: y in [1-bottom, 1-top].
                data[i * 4] = r.left
                data[i * 4 + 1] = r.right
                data[i * 4 + 2] = 1f - r.bottom
                data[i * 4 + 3] = 1f - r.top
            }
            GLES20.glUniform4fv(compRegionsLoc, regions.size, data, /* offset = */ 0)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
    }

    private fun drawCopy(inputTexId: Int) {
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex = */ 0)
        copyProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
    }

    override fun release() {
        super.release()
        try {
            copyProgram.delete()
            compositeProgram.delete()
            blurProgram?.delete()
            releaseScratch()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private fun releaseScratch() {
        for (i in 0..1) {
            if (scratchFbo[i] >= 0) GlUtil.deleteFbo(scratchFbo[i])
            if (scratchTex[i] >= 0) GlUtil.deleteTexture(scratchTex[i])
            scratchFbo[i] = -1
            scratchTex[i] = -1
        }
        lowW = 0
        lowH = 0
    }

    private fun program(fragmentGlsl: String): GlProgram = try {
        GlProgram(VERTEX, fragmentGlsl).apply {
            setBufferAttribute(
                "aFramePosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
        }
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }

    /** Array uniforms may be exposed as `name` or `name[0]`; both denote element 0 per the GLES spec. */
    private fun GlProgram.arrayUniformLocation(name: String): Int =
        getUniformLocation(name).takeIf { it >= 0 } ?: getUniformLocation("$name[0]")
}

/** Normalized 1-D Gaussian (center + [radius] symmetric sides), padded to [MAX_RADIUS]+1 with zeros. */
private fun gaussianKernel(sigma: Float, radius: Int): FloatArray {
    val k = FloatArray(MAX_RADIUS + 1)
    var sum = 0f
    for (i in 0..radius) {
        val w = exp(-(i * i).toFloat() / (2f * sigma * sigma))
        k[i] = w
        sum += if (i == 0) w else 2f * w
    }
    for (i in 0..radius) k[i] /= sum
    return k
}

private val VERTEX = """
    #version 100
    attribute vec4 aFramePosition;
    varying vec2 vTexCoord;
    void main() {
      gl_Position = aFramePosition;
      vTexCoord = aFramePosition.xy * 0.5 + 0.5;
    }
    """.trimIndent()

private val COPY_FRAGMENT = """
    #version 100
    precision mediump float;
    uniform sampler2D uTexSampler;
    varying vec2 vTexCoord;
    void main() {
      gl_FragColor = texture2D(uTexSampler, vTexCoord);
    }
    """.trimIndent()

// Separable Gaussian, one axis per pass via uTexelStep. Loop bound is the literal MAX_RADIUS (10);
// uWeights has MAX_RADIUS+1 entries and uRadius breaks the loop at the active radius.
private val BLUR_FRAGMENT = """
    #version 100
    #ifdef GL_FRAGMENT_PRECISION_HIGH
    precision highp float;
    #else
    precision mediump float;
    #endif
    uniform sampler2D uTexSampler;
    uniform vec2 uTexelStep;
    uniform float uWeights[11];
    uniform int uRadius;
    varying vec2 vTexCoord;
    void main() {
      vec3 acc = texture2D(uTexSampler, vTexCoord).rgb * uWeights[0];
      for (int i = 1; i <= 10; i++) {
        if (i > uRadius) break;
        vec2 o = uTexelStep * float(i);
        acc += (texture2D(uTexSampler, vTexCoord + o).rgb
              + texture2D(uTexSampler, vTexCoord - o).rgb) * uWeights[i];
      }
      gl_FragColor = vec4(acc, 1.0);
    }
    """.trimIndent()

// Composite: mix original toward the blurred (or original) base, optionally grayscaled, by a coverage
// mask — 1 inside any region, falling to 0 across a feather band outside it. uRegions holds
// (left, right, yLow, yHigh) in GL y-up coords; loop bound is MAX_REGIONS (8).
//
// ponytail: feather width is a literal 0.15 of the region. Promote it to a uniform only if QA wants it
// per-video; blurAmount already controls the thing users actually reach for.
private val COMPOSITE_FRAGMENT = """
    #version 100
    #ifdef GL_FRAGMENT_PRECISION_HIGH
    precision highp float;
    #else
    precision mediump float;
    #endif
    uniform sampler2D uInputTex;
    uniform sampler2D uBlurTex;
    uniform int uCensorAll;
    uniform int uUseBlur;
    uniform int uGrayscale;
    uniform int uRegionCount;
    uniform vec4 uRegions[8];
    varying vec2 vTexCoord;
    void main() {
      vec4 orig = texture2D(uInputTex, vTexCoord);
      float mask = float(uCensorAll);
      for (int i = 0; i < 8; i++) {
        if (i >= uRegionCount) break;
        vec4 r = uRegions[i];
        // Feather OUTWARD only: mask is 1 across the whole rect and ramps to 0 over a band just
        // OUTSIDE it. Centering the ramp on the edge instead would leave the outer band of every
        // face half-blurred — softening must never uncover a pixel the hard rect covered.
        // Band is 15% of the region's own size, so a distant face gets a small feather and a
        // close-up a large one; the floor keeps smoothstep's edges distinct on a degenerate rect.
        vec2 f = max(vec2(r.y - r.x, r.w - r.z) * 0.15, 0.002);
        mask = max(mask,
            smoothstep(r.x - f.x, r.x, vTexCoord.x) *
            (1.0 - smoothstep(r.y, r.y + f.x, vTexCoord.x)) *
            smoothstep(r.z - f.y, r.z, vTexCoord.y) *
            (1.0 - smoothstep(r.w, r.w + f.y, vTexCoord.y)));
      }
      if (mask <= 0.0) {
        gl_FragColor = orig;
        return;
      }
      vec3 base = (uUseBlur == 1) ? texture2D(uBlurTex, vTexCoord).rgb : orig.rgb;
      if (uGrayscale == 1) {
        float luma = dot(base, vec3(0.2126, 0.7152, 0.0722)); // BT.709
        base = vec3(luma);
      }
      gl_FragColor = vec4(mix(orig.rgb, base, mask), orig.a);
    }
    """.trimIndent()
