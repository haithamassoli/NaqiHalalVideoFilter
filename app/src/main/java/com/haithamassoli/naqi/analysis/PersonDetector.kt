package com.haithamassoli.naqi.analysis

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.haithamassoli.naqi.ml.Infer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

/** YOLO26n person boxes in the same upright coordinates as ML Kit. Buffers live for one pass. */
internal class PersonDetector(private val context: Context) {
    private val input = ByteBuffer.allocateDirect(3 * SIDE * SIDE * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var previous: FloatArray? = null

    data class Frame(val boxes: List<NRect>, val sceneCut: Boolean)

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
        val rows = Infer.people(context, input)
        check(rows.all { it.isFinite() }) { "Non-finite person detections" }
        val boxes = ArrayList<NRect>()
        for (i in rows.indices step 6) {
            if (rows[i + 4] < 0.25f || rows[i + 5] != 0f) continue
            val r = NRect(
                ((rows[i] - left) / (scale * width)).coerceIn(0f, 1f),
                ((rows[i + 1] - top) / (scale * height)).coerceIn(0f, 1f),
                ((rows[i + 2] - left) / (scale * width)).coerceIn(0f, 1f),
                ((rows[i + 3] - top) / (scale * height)).coerceIn(0f, 1f),
            )
            if (r.width > 0f && r.height > 0f) boxes += r
        }
        return Frame(boxes, cut)
    }

    companion object { const val SIDE = 640 }
}
