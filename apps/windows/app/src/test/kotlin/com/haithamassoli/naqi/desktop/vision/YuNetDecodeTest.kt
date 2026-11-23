package com.haithamassoli.naqi.desktop.vision

import com.haithamassoli.naqi.analysis.NRect
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.ln

/** YuNet's output decoding and NMS on hand-made tensors, no model. */
class YuNetDecodeTest {
    @Test fun decodesOneAnchorAboveThresholdIntoCanvasPixels() {
        val outputs = HashMap<String, FloatBuffer>()
        for (stride in intArrayOf(8, 16, 32)) {
            val n = (640 / stride) * (640 / stride)
            outputs["cls_$stride"] = FloatBuffer.allocate(n)
            outputs["obj_$stride"] = FloatBuffer.allocate(n)
            outputs["bbox_$stride"] = FloatBuffer.allocate(4 * n)
        }
        // Stride 32, row 2, column 3 (20 columns): score sqrt(0.81 * 1) = 0.9.
        val i = 2 * 20 + 3
        outputs.getValue("cls_32").put(i, 0.81f)
        outputs.getValue("obj_32").put(i, 1.5f) // clamped to 1
        outputs.getValue("bbox_32").put(4 * i, 0.5f).put(4 * i + 1, 0.5f).put(4 * i + 2, ln(2f)).put(4 * i + 3, ln(4f))
        // Another anchor at sqrt(0.5 * 0.5) = 0.5: under SCORE_THRESHOLD.
        outputs.getValue("cls_8").put(5, 0.5f)
        outputs.getValue("obj_8").put(5, 0.5f)

        val found = YuNet.decode(outputs)
        assertEquals(1, found.size)
        assertEquals(0.9f, found[0].first, 1e-5f)
        // Centre (3.5, 2.5) * 32 = (112, 80); size (64, 128).
        assertEquals(NRect(80f, 16f, 144f, 144f).toString(), found[0].second.round().toString())
    }

    @Test fun nmsKeepsTheBestOfEachOverlappingGroup() {
        val a = 0.9f to NRect(0f, 0f, 10f, 10f)
        val b = 0.95f to NRect(1f, 1f, 11f, 11f)  // IoU with a ~0.68, and the better score: a goes
        val c = 0.7f to NRect(50f, 50f, 60f, 60f)
        assertEquals(listOf(b, c), YuNet.nms(listOf(a, c, b)))
    }

    private fun NRect.round() = NRect(Math.round(left).toFloat(), Math.round(top).toFloat(), Math.round(right).toFloat(), Math.round(bottom).toFloat())
}
