package com.haithamassoli.naqi.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.haithamassoli.naqi.analysis.NRect
import java.nio.FloatBuffer

/**
 * M1 ORT inference for the two image models — the NSFW gate and NudeNet, per the locked
 * contracts in [NaqiModel]. Preprocessing (resize/pad, RGB, /255, NCHW) and postprocessing
 * (softmax passthrough; YOLO decode + per-class NMS) live here so pass-1 workers see typed
 * results only. Sessions are created lazily and cached for the process on the XNNPACK EP
 * (same options as [ModelSmoke]).
 *
 * Contract: one worker drives this at a time (thread-confined). [close] releases the sessions.
 */
object Infer {
    /** Upstream NudeNet decode thresholds (see [NaqiModel.NUDENET]). */
    private const val KEEP_SCORE = 0.2f
    private const val NMS_SCORE = 0.25f
    private const val NMS_IOU = 0.45f

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessions = HashMap<NaqiModel, OrtSession>()

    /** GantMan 5-class softmax in [NSFW_CLASSES] order — stretch to 224², RGB /255, NCHW. */
    fun nsfw(context: Context, frame: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(frame, 224, 224, true)
        val input = chwFloat(scaled, 224, 224)
        if (scaled !== frame) scaled.recycle()
        val session = session(context, NaqiModel.NSFW_GATE)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 224, 224)).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(5) { out.get(it) }
            }
        }
    }

    /** One NudeNet box; [rect] is normalized to the INPUT bitmap. */
    data class Detection(val classIndex: Int, val score: Float, val rect: NRect)

    /** NudeNet 320n boxes — pad right/bottom to square, resize to 320², RGB /255, NCHW; decode + per-class NMS. */
    fun nudenet(context: Context, image: Bitmap): List<Detection> {
        val w = image.width
        val h = image.height
        val side = maxOf(w, h)
        val square = if (w == h) image else padSquare(image, side)
        val resized = Bitmap.createScaledBitmap(square, 320, 320, true)
        val input = chwFloat(resized, 320, 320)
        if (resized !== square && resized !== image) resized.recycle()
        if (square !== image) square.recycle()

        val session = session(context, NaqiModel.NUDENET)
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, 320, 320)).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                return decode((result[0] as OnnxTensor).floatBuffer, side, w, h)
            }
        }
    }

    /** Idempotent — releases the cached sessions (safe when none exist). */
    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun session(context: Context, model: NaqiModel): OrtSession = sessions.getOrPut(model) {
        val file = ModelSmoke.modelFile(context, model) ?: error("model not bundled: ${model.assetName}")
        sessionOptions().use { env.createSession(file.absolutePath, it) }
    }

    // Mirror of ModelSmoke.sessionOptions: XNNPACK EP, single intra-op thread, spinning disabled.
    private fun sessionOptions() = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        addConfigEntry("session.intra_op.allow_spinning", "0")
        addXnnpack(mapOf("intra_op_num_threads" to Runtime.getRuntime().availableProcessors().toString()))
    }

    // ARGB bitmap -> NCHW RGB float buffer, scaled 1/255, no mean/std.
    private fun chwFloat(bmp: Bitmap, w: Int, h: Int): FloatBuffer {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatBuffer.allocate(3 * w * h)
        val a = buf.array()
        val plane = w * h
        for (i in 0 until plane) {
            val p = pixels[i]
            a[i] = ((p ushr 16) and 0xFF) / 255f          // R plane
            a[plane + i] = ((p ushr 8) and 0xFF) / 255f   // G plane
            a[2 * plane + i] = (p and 0xFF) / 255f         // B plane
        }
        return buf
    }

    // Pad to a square with the source at top-left; the untouched right/bottom stays RGB 0 (upstream pad).
    private fun padSquare(src: Bitmap, side: Int): Bitmap {
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, null)
        return out
    }

    // YOLOv8 head [1,22,2100]: rows 0..3 = cx,cy,w,h (320-space), rows 4..21 = per-class scores.
    private fun decode(out: FloatBuffer, side: Int, bmpW: Int, bmpH: Int): List<Detection> {
        val cols = 2100
        val classes = NUDENET_CLASSES.size // 18
        val cands = ArrayList<Cand>()
        for (j in 0 until cols) {
            var bestC = 0
            var bestS = out.get(4 * cols + j)
            for (c in 1 until classes) {
                val s = out.get((4 + c) * cols + j)
                if (s > bestS) { bestS = s; bestC = c }
            }
            if (bestS >= KEEP_SCORE) {
                val cx = out.get(j)
                val cy = out.get(cols + j)
                val bw = out.get(2 * cols + j)
                val bh = out.get(3 * cols + j)
                cands.add(Cand(bestC, bestS, cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f))
            }
        }
        return nms(cands, side / 320f, bmpW, bmpH)
    }

    // Class-wise greedy NMS; survivors mapped to bitmap-normalized rects.
    private fun nms(cands: List<Cand>, scale: Float, bmpW: Int, bmpH: Int): List<Detection> {
        val out = ArrayList<Detection>()
        cands.filter { it.score >= NMS_SCORE }
            .groupBy { it.cls }
            .forEach { (_, group) ->
                val sorted = group.sortedByDescending { it.score }
                val dead = BooleanArray(sorted.size)
                for (i in sorted.indices) {
                    if (dead[i]) continue
                    val a = sorted[i]
                    out.add(Detection(a.cls, a.score, a.toRect(scale, bmpW, bmpH)))
                    for (k in i + 1 until sorted.size) {
                        if (!dead[k] && iou(a, sorted[k]) >= NMS_IOU) dead[k] = true
                    }
                }
            }
        return out
    }

    // Box in 320-space corners. Pad is right/bottom only, so bitmap px = corner * side/320 (no offset).
    private class Cand(val cls: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
        fun toRect(scale: Float, bmpW: Int, bmpH: Int) = NRect(
            (x1 * scale).coerceIn(0f, bmpW.toFloat()) / bmpW,
            (y1 * scale).coerceIn(0f, bmpH.toFloat()) / bmpH,
            (x2 * scale).coerceIn(0f, bmpW.toFloat()) / bmpW,
            (y2 * scale).coerceIn(0f, bmpH.toFloat()) / bmpH,
        )
    }

    private fun iou(a: Cand, b: Cand): Float {
        val iw = (minOf(a.x2, b.x2) - maxOf(a.x1, b.x1)).coerceAtLeast(0f)
        val ih = (minOf(a.y2, b.y2) - maxOf(a.y1, b.y1)).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
