package com.haithamassoli.naqi.analysis

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.haithamassoli.naqi.edl.BodyMask
import com.haithamassoli.naqi.ml.Infer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * YOLO11n-seg people in the same upright coordinates as ML Kit. Buffers live for one pass. With
 * [masks] each person also carries a silhouette (precise body mode); otherwise the prototype output
 * is never fetched and no mask math runs.
 */
internal class PersonDetector(private val context: Context, private val masks: Boolean = false) {
    private val input = ByteBuffer.allocateDirect(3 * SIDE * SIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var previous: FloatArray? = null

    data class Person(val box: NRect, val mask: BodyMask? = null)
    data class Frame(val people: List<Person>, val sceneCut: Boolean)

    /**
     * ponytail: no iOS-style crop retry on empty samples. Cropping this pipeline's 640 px analysis frames
     * found nobody on the regression clip's camera-display shot on the S23 (the crops need source pixels)
     * while S23 analysis went from 74 s to 135 s. `BodyTracker`'s shot lead-in and the orphan-face
     * fallback cover that shot instead. Revisit with a source-resolution crop path if small people go missing.
     */
    fun detect(image: InputImage, width: Int, height: Int): Frame {
        val bytes = requireNotNull(image.byteBuffer)
        val scale = SIDE.toFloat() / maxOf(width, height)
        val w = (width * scale).roundToInt()
        val h = (height * scale).roundToInt()
        val left = (SIDE - w) / 2
        val top = (SIDE - h) / 2
        val plane = SIDE * SIDE
        for (i in 0 until 3 * plane) input.put(i, 114f / 255f)
        val signature = FloatArray(16 * 16)
        val chroma = image.width * image.height
        for (y in 0 until h) for (x in 0 until w) {
            val ux = (x / scale).toInt().coerceAtMost(width - 1)
            val uy = (y / scale).toInt().coerceAtMost(height - 1)
            val dx: Int
            val dy: Int
            when (image.rotationDegrees) {
                90 -> { dx = uy; dy = width - 1 - ux }
                180 -> { dx = width - 1 - ux; dy = height - 1 - uy }
                270 -> { dx = height - 1 - uy; dy = ux }
                else -> { dx = ux; dy = uy }
            }
            val luma = bytes.get(dy * image.width + dx).toInt() and 255
            val ci = chroma + (dy shr 1) * image.width + (dx shr 1) * 2
            val v = (bytes.get(ci).toInt() and 255) - 128
            val u = (bytes.get(ci + 1).toInt() and 255) - 128
            val i = (y + top) * SIDE + x + left
            input.put(i, (luma + ((1436 * v) shr 10)).coerceIn(0, 255) / 255f)
            input.put(plane + i, (luma - ((352 * u + 731 * v) shr 10)).coerceIn(0, 255) / 255f)
            input.put(2 * plane + i, (luma + ((1815 * u) shr 10)).coerceIn(0, 255) / 255f)
        }
        for (y in 0 until 16) for (x in 0 until 16) {
            val i = (top + y * h / 16) * SIDE + left + x * w / 16
            signature[y * 16 + x] = (input.get(i) + input.get(plane + i) + input.get(2 * plane + i)) / 3f
        }
        // ponytail: coarse appearance cut; gradual transitions can evade it. Add shot detection if QA shows vote transfer.
        val cut = previous?.let { old -> signature.indices.sumOf { abs(signature[it] - old[it]).toDouble() } / signature.size > 0.20 } ?: false
        previous = signature
        val people = Infer.people(context, input, masks) { out, proto ->
            decode(out, proto).mapNotNull { (lb, mask) ->
                val box = NRect(
                    ((lb.left - left) / (scale * width)).coerceIn(0f, 1f),
                    ((lb.top - top) / (scale * height)).coerceIn(0f, 1f),
                    ((lb.right - left) / (scale * width)).coerceIn(0f, 1f),
                    ((lb.bottom - top) / (scale * height)).coerceIn(0f, 1f),
                )
                if (box.width <= 0f || box.height <= 0f) return@mapNotNull null
                Person(box, mask?.let { it(box, width, height, scale, left, top) })
            }
        }
        return Frame(people, cut)
    }

    companion object {
        const val SIDE = 640
        private const val ANCHORS = 8400
        /** Same thresholds as iOS `PersonDetector.swift`. */
        private const val CONFIDENCE = 0.25f
        private const val NMS_IOU = 0.7f
        /** Long side of a stored [BodyMask], in cells. */
        const val MASK_SIDE = 32

        /**
         * Decodes YOLO11's `output0` [1,116,8400] (cx, cy, w, h, 80 class scores, 32 mask coefficients):
         * person (class 0) anchors whose best class is person, NMS, at most 300 like the validated
         * reference. Boxes stay in letterbox pixels. With [proto] each comes with a mask builder.
         */
        fun decode(out: FloatBuffer, proto: FloatBuffer?): List<Pair<NRect, MaskBuilder?>> {
            val candidates = ArrayList<Int>()
            for (i in 0 until ANCHORS) {
                val person = out.get(4 * ANCHORS + i)
                check(person.isFinite()) { "Non-finite person score" }
                if (person <= CONFIDENCE) continue
                // classes=[0], multi_label=False: only anchors whose best class is person.
                var best = true
                for (c in 5 until 84) {
                    val score = out.get(c * ANCHORS + i)
                    check(score.isFinite()) { "Non-finite class score" }
                    if (score > person) { best = false; break }
                }
                if (best) candidates += i
            }
            candidates.sortWith(compareByDescending<Int> { out.get(4 * ANCHORS + it) }.thenBy { it })
            val kept = ArrayList<Pair<Int, NRect>>()
            for (i in candidates) {
                val cx = out.get(i); val cy = out.get(ANCHORS + i)
                val bw = out.get(2 * ANCHORS + i); val bh = out.get(3 * ANCHORS + i)
                check(cx.isFinite() && cy.isFinite() && bw.isFinite() && bh.isFinite()) { "Non-finite person box" }
                if (bw <= 0f || bh <= 0f) continue
                val box = NRect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
                if (kept.any { BodyTracker.overlap(it.second, box) > NMS_IOU }) continue
                kept += i to box
                if (kept.size == 300) break
            }
            return kept.map { (anchor, box) -> box to proto?.let { MaskBuilder(out, it, anchor) } }
        }
    }

    /**
     * `sigmoid(coefficients · prototypes) > 0.5` sampled at each cell centre of the upright box; the
     * logit sign is the same test without `exp`. Prototypes cover the 640 letterbox at a quarter of
     * its resolution, so each cell centre is mapped upright -> letterbox -> prototype cell.
     */
    class MaskBuilder(out: FloatBuffer, private val proto: FloatBuffer, anchor: Int) {
        private val coefficients = FloatArray(32) { out.get((84 + it) * ANCHORS + anchor) }

        operator fun invoke(box: NRect, width: Int, height: Int, scale: Float, left: Int, top: Int): BodyMask {
            val bw = box.width * width
            val bh = box.height * height
            val long = max(bw, bh)
            val cols = max(1, (MASK_SIDE * bw / long).roundToInt())
            val rows = max(1, (MASK_SIDE * bh / long).roundToInt())
            val bits = ByteArray((cols * rows + 7) / 8)
            for (y in 0 until rows) {
                val ly = (box.top * height + (y + 0.5f) * bh / rows) * scale + top
                val py = (ly / 4f).toInt().coerceIn(0, 159)
                for (x in 0 until cols) {
                    val lx = (box.left * width + (x + 0.5f) * bw / cols) * scale + left
                    val px = (lx / 4f).toInt().coerceIn(0, 159)
                    var logit = 0f
                    for (c in 0 until 32) logit += coefficients[c] * proto.get(c * 25_600 + py * 160 + px)
                    check(logit.isFinite()) { "Non-finite mask logit" }
                    val i = y * cols + x
                    if (logit > 0f) bits[i / 8] = (bits[i / 8].toInt() or (1 shl (i % 8))).toByte()
                }
            }
            return BodyMask(box, cols, rows, bits)
        }
    }
}
