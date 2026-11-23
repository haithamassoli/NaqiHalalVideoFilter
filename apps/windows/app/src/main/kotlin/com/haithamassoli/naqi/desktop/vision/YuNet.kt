package com.haithamassoli.naqi.desktop.vision

import ai.onnxruntime.OnnxTensor
import com.haithamassoli.naqi.analysis.BodyTracker
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.desktop.media.RgbFrame
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV's YuNet face detector (opencv_zoo `face_detection_yunet_2023mar.onnx`, MIT), standing in for
 * Android's ML Kit. It has no tracking id; [FaceTracks] associates by IoU instead.
 *
 * The graph's input is FIXED at [1,3,640,640], BGR in 0..255, no normalization. A frame is placed at the
 * top-left of a zero canvas (scaled down first only if a side is over 640, which the 640-px analysis frames
 * never are), so boxes come back in frame pixels with no letterbox offset to undo.
 */
internal class YuNet(model: File) : AutoCloseable {
    private val input = directFloats(3 * SIDE * SIDE) // before the session, as in NsfwClassifier
    private val session = createSession(model)

    /** Faces as raw (unpadded) boxes normalized to [frame], best score first. May reach past [0, 1]. */
    fun detect(frame: RgbFrame): List<NRect> {
        val scale = min(1f, SIDE.toFloat() / maxOf(frame.width, frame.height))
        val dw = (frame.width * scale).toInt().coerceAtLeast(1)
        val dh = (frame.height * scale).toInt().coerceAtLeast(1)
        fill(frame, dw, dh)
        val outputs = OnnxTensor.createTensor(ortEnv, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { t ->
            session.run(mapOf("input" to t), OUTPUTS).use { r ->
                OUTPUTS.associateWith { (r.get(it).get() as OnnxTensor).floatBuffer }
            }
        }
        val fw = frame.width * scale
        val fh = frame.height * scale
        return nms(decode(outputs)).map { (_, b) -> NRect(b.left / fw, b.top / fh, b.right / fw, b.bottom / fh) }
    }

    private fun fill(frame: RgbFrame, dw: Int, dh: Int) {
        val plane = SIDE * SIDE
        for (i in 0 until 3 * plane) input.put(i, 0f)
        val rgb = frame.rgb
        for (y in 0 until dh) {
            val row = (y * frame.height / dh) * frame.width
            for (x in 0 until dw) {
                val s = 3 * (row + x * frame.width / dw)
                val i = y * SIDE + x
                input.put(i, (rgb[s + 2].toInt() and 0xFF).toFloat())          // B
                input.put(plane + i, (rgb[s + 1].toInt() and 0xFF).toFloat())  // G
                input.put(2 * plane + i, (rgb[s].toInt() and 0xFF).toFloat())  // R
            }
        }
    }

    override fun close() = session.close()

    companion object {
        const val SIDE = 640

        /**
         * OpenCV's own default is 0.9. Lower, because a miss leaves a face visible while a false box only
         * blurs a patch of background: the app's safe direction is covering.
         * ponytail: picked, not swept. Tune it against Android's ML Kit EDLs on the same clips.
         */
        const val SCORE_THRESHOLD = 0.6f

        /** OpenCV's default NMS IoU. */
        const val NMS_IOU = 0.3f

        private val STRIDES = intArrayOf(8, 16, 32)
        private val OUTPUTS = STRIDES.flatMap { listOf("cls_$it", "obj_$it", "bbox_$it") }.toSet()

        /**
         * OpenCV's `FaceDetectorYN` decode (modules/objdetect/src/face_detect.cpp): per stride, one
         * anchor per grid cell; score = sqrt(cls * obj), both clamped to [0, 1]; centre = (cell + offset)
         * × stride, size = exp(log-size) × stride. Boxes are in 640-canvas pixels.
         */
        fun decode(outputs: Map<String, FloatBuffer>): List<Pair<Float, NRect>> {
            val out = ArrayList<Pair<Float, NRect>>()
            for (stride in STRIDES) {
                val cls = outputs.getValue("cls_$stride")
                val obj = outputs.getValue("obj_$stride")
                val bbox = outputs.getValue("bbox_$stride")
                val cols = SIDE / stride
                for (i in 0 until cols * cols) {
                    val score = sqrt(cls.get(i).coerceIn(0f, 1f) * obj.get(i).coerceIn(0f, 1f))
                    if (score < SCORE_THRESHOLD) continue
                    val cx = (i % cols + bbox.get(4 * i)) * stride
                    val cy = (i / cols + bbox.get(4 * i + 1)) * stride
                    val w = exp(bbox.get(4 * i + 2)) * stride
                    val h = exp(bbox.get(4 * i + 3)) * stride
                    out += score to NRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                }
            }
            return out
        }

        /** Greedy NMS, best score first. The IoU is scale-free, so it works on pixel boxes as well. */
        fun nms(boxes: List<Pair<Float, NRect>>): List<Pair<Float, NRect>> {
            val kept = ArrayList<Pair<Float, NRect>>()
            for (b in boxes.sortedByDescending { it.first }) {
                if (kept.none { BodyTracker.overlap(it.second, b.second) > NMS_IOU }) kept += b
            }
            return kept
        }
    }
}
