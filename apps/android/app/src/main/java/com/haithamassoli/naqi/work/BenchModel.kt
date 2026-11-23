package com.haithamassoli.naqi.work

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Half
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.Random

/**
 * T1 microbench harness (plan §3.3). Gated by `DEBUG_HOOKS` via [com.haithamassoli.naqi.MainActivity.maybeAutorun].
 *
 *   adb shell am start -n com.haithamassoli.naqi.benchmark/com.haithamassoli.naqi.MainActivity \
 *     -e bench_model /sdcard/Android/data/com.haithamassoli.naqi.benchmark/files/bench/yamnet.onnx \
 *     --es bench_ep xnnpack --ei bench_threads 4 --ei bench_iters 30
 *
 * Logcat: `adb logcat -s BenchModel`. JSONL: `filesDir/bench/bench_model.jsonl` (mirrored to
 * getExternalFilesDir("bench")).
 */
object BenchModel {
    private const val TAG = "BenchModel"

    fun run(ctx: Context, intent: Intent) {
        try {
            go(ctx, intent)
        } catch (t: Throwable) {
            Log.e(TAG, "BENCH error=${t.javaClass.simpleName}: ${t.message}", t)
        } finally {
            Log.i(TAG, "BENCH done")
        }
    }

    private fun go(ctx: Context, intent: Intent) {
        val path = intent.getStringExtra("bench_model") ?: error("bench_model extra missing")
        val intern = File(ctx.filesDir, "bench").apply { mkdirs() }
        val ext = ctx.getExternalFilesDir("bench")?.apply { mkdirs() }
        val model = resolveModel(path, ext)
            ?: error("model not readable: $path (ext=${ext?.absolutePath} listing=${ext?.list()?.joinToString()})")
        val ep = (intent.getStringExtra("bench_ep") ?: "cpu").lowercase(Locale.ROOT)
        val defaultThreads =
            if (ep == "xnnpack") 4 else Runtime.getRuntime().availableProcessors().coerceAtMost(6)
        val threads = if (intent.hasExtra("bench_threads")) intent.getIntExtra("bench_threads", defaultThreads) else defaultThreads
        val iters = intent.getIntExtra("bench_iters", 50)
        val seconds = intent.getIntExtra("bench_seconds", 0)
        val profile = intent.getBooleanExtra("bench_profile", false)
        val shapeOverride = intent.getStringExtra("bench_shape")?.let { parseShape(it) }
        val tag = intent.getStringExtra("bench_tag") ?: ""

        val env = OrtEnvironment.getEnvironment()
        val opts = sessionOpts(ep, threads)
        if (profile) {
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
            opts.enableProfiling(File(intern, "profile").absolutePath)
        }
        val tCreate0 = SystemClock.elapsedRealtime()
        opts.use {
            env.createSession(model.absolutePath, opts).use { session ->
                val createMs = SystemClock.elapsedRealtime() - tCreate0
                val feeds = feeds(env, session, shapeOverride)
                try {
                    session.run(feeds).use { }
                    val start = probe(ctx)
                    val times = ArrayList<Double>()
                    val stamps = ArrayList<Long>()
                    val measure0 = SystemClock.elapsedRealtime()
                    if (seconds > 0) {
                        val until = measure0 + seconds * 1000L
                        while (SystemClock.elapsedRealtime() < until) {
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            session.run(feeds).use { }
                            times.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                            stamps.add(SystemClock.elapsedRealtime() - measure0)
                        }
                    } else {
                        repeat(iters) {
                            val t0 = SystemClock.elapsedRealtimeNanos()
                            session.run(feeds).use { }
                            times.add((SystemClock.elapsedRealtimeNanos() - t0) / 1e6)
                            stamps.add(SystemClock.elapsedRealtime() - measure0)
                        }
                    }
                    val end = probe(ctx)
                    report(intern, ext, tag, path, ep, threads, times, stamps, createMs, seconds, start, end, profile)
                } finally {
                    feeds.values.forEach { it.close() }
                }
            }
        }
        if (profile && ext != null) {
            intern.listFiles { _, n -> n.startsWith("profile") }?.forEach { f ->
                runCatching { f.copyTo(File(ext, f.name), overwrite = true) }
            }
        }
    }

    private fun sessionOpts(ep: String, threads: Int) = OrtSession.SessionOptions().apply {
        when (ep) {
            "cpu" -> {
                setIntraOpNumThreads(threads)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
            }
            "xnnpack" -> {
                setIntraOpNumThreads(1)
                addConfigEntry("session.intra_op.allow_spinning", "0")
                addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            }
            else -> error("bench_ep must be cpu|xnnpack, got $ep")
        }
    }

    private fun feeds(
        env: OrtEnvironment,
        session: OrtSession,
        override: LongArray?,
    ): LinkedHashMap<String, OnnxTensor> {
        val rng = Random(42)
        val out = LinkedHashMap<String, OnnxTensor>()
        val entries = session.inputInfo.entries.sortedBy { it.key }
        for ((name, node) in entries) {
            val info = node.info as TensorInfo
            val native = info.shape
            val dynamic = native.any { it <= 0L }
            val shape = when {
                dynamic && override == null ->
                    error("dynamic dim on $name shape=${native.contentToString()} — pass --es bench_shape")
                dynamic -> override!!
                override != null && entries.size == 1 -> override
                else -> native
            }
            out[name] = tensor(env, info.type, shape, rng)
        }
        return out
    }

    private fun tensor(env: OrtEnvironment, type: OnnxJavaType, shape: LongArray, rng: Random): OnnxTensor {
        val n = shape.fold(1L, Long::times).toInt()
        return when (type) {
            OnnxJavaType.FLOAT -> {
                val buf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                repeat(n) { buf.put(rng.nextFloat() * 2f - 1f) }
                buf.rewind()
                OnnxTensor.createTensor(env, buf, shape)
            }
            OnnxJavaType.FLOAT16 -> {
                val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                repeat(n) { buf.put(Half.toHalf(rng.nextFloat() * 2f - 1f)) }
                buf.rewind()
                OnnxTensor.createTensor(env, buf, shape, OnnxJavaType.FLOAT16)
            }
            else -> error("unsupported input type $type (need FLOAT or FLOAT16)")
        }
    }

    private fun resolveModel(path: String, ext: File?): File? {
        val requested = File(path)
        val alts = listOfNotNull(
            requested,
            File(path.replaceFirst("/sdcard", "/storage/emulated/0")),
            ext?.let { File(it, requested.name) },
        )
        return alts.firstOrNull { it.isFile && it.canRead() }
    }

    private fun parseShape(s: String): LongArray {
        val parts = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) error("empty bench_shape")
        return LongArray(parts.size) { parts[it].toLong() }
    }

    private class Probe(val freq: LongArray, val battC: Double?, val headroom: Float?)

    private fun probe(ctx: Context): Probe {
        val freq = File("/sys/devices/system/cpu/cpufreq").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
            ?.map { runCatching { File(it, "scaling_cur_freq").readText().trim().toLong() }.getOrDefault(-1L) }
            ?.toLongArray()
            ?: longArrayOf()
        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battC = if (sticky?.hasExtra(BatteryManager.EXTRA_TEMPERATURE) == true)
            sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0 else null
        val pm = ctx.getSystemService(PowerManager::class.java)
        val headroom = if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(0).takeUnless { it.isNaN() || it.isInfinite() }
        } else null
        return Probe(freq, battC, headroom)
    }

    private fun pct(v: List<Double>, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.lastIndex)]
    }

    private fun hwmKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()
        }
    }.getOrNull() ?: -1L

    private fun report(
        intern: File,
        ext: File?,
        tag: String,
        path: String,
        ep: String,
        threads: Int,
        times: List<Double>,
        stamps: List<Long>,
        createMs: Long,
        seconds: Int,
        start: Probe,
        end: Probe,
        profile: Boolean,
    ) {
        val n = times.size
        val p50 = pct(times, 0.50)
        val p90 = pct(times, 0.90)
        val max = times.maxOrNull() ?: 0.0
        val mean = if (n == 0) 0.0 else times.average()
        val hwm = hwmKb()
        var cool = Double.NaN
        var sustained = Double.NaN
        if (seconds > 0 && stamps.isNotEmpty()) {
            val last = stamps.last()
            cool = pct(times.filterIndexed { i, _ -> stamps[i] <= 60_000L }, 0.50)
            sustained = pct(times.filterIndexed { i, _ -> stamps[i] >= last - 60_000L }, 0.50)
        }
        fun d(x: Double) = "%.3f".format(Locale.ROOT, x)
        fun batt(x: Double?) = x?.let { "%.1f".format(Locale.ROOT, it) } ?: "null"
        fun hr(x: Float?) = x?.let { "%.4f".format(Locale.ROOT, it) } ?: "null"
        fun freq(a: LongArray) = a.joinToString(",")
        val line = buildString {
            append("BENCH")
            append(" tag=").append(tag)
            append(" model=").append(path)
            append(" ep=").append(ep)
            append(" threads=").append(threads)
            append(" n=").append(n)
            append(" createMs=").append(createMs)
            append(" p50=").append(d(p50))
            append(" p90=").append(d(p90))
            append(" max=").append(d(max))
            append(" mean=").append(d(mean))
            append(" hwmKb=").append(hwm)
            append(" freqStart=").append(freq(start.freq))
            append(" freqEnd=").append(freq(end.freq))
            append(" battCStart=").append(batt(start.battC))
            append(" battCEnd=").append(batt(end.battC))
            append(" headroomStart=").append(hr(start.headroom))
            append(" headroomEnd=").append(hr(end.headroom))
            if (seconds > 0) {
                append(" seconds=").append(seconds)
                append(" p50cool=").append(d(cool))
                append(" p50sustained=").append(d(sustained))
            }
            if (profile) append(" profile=true")
        }
        Log.i(TAG, line)
        val json = buildString {
            append('{')
            append("\"tag\":\"").append(tag.replace("\"", "\\\"")).append('"')
            append(",\"model\":\"").append(path.replace("\"", "\\\"")).append('"')
            append(",\"ep\":\"").append(ep).append('"')
            append(",\"threads\":").append(threads)
            append(",\"n\":").append(n)
            append(",\"createMs\":").append(createMs)
            append(",\"p50\":").append(d(p50))
            append(",\"p90\":").append(d(p90))
            append(",\"max\":").append(d(max))
            append(",\"mean\":").append(d(mean))
            append(",\"hwmKb\":").append(hwm)
            append(",\"freqStart\":[").append(freq(start.freq)).append(']')
            append(",\"freqEnd\":[").append(freq(end.freq)).append(']')
            append(",\"battCStart\":").append(batt(start.battC))
            append(",\"battCEnd\":").append(batt(end.battC))
            append(",\"headroomStart\":").append(hr(start.headroom))
            append(",\"headroomEnd\":").append(hr(end.headroom))
            if (seconds > 0) {
                append(",\"seconds\":").append(seconds)
                append(",\"p50cool\":").append(d(cool))
                append(",\"p50sustained\":").append(d(sustained))
            }
            append('}')
        }
        for (dir in listOfNotNull(intern, ext)) {
            runCatching { File(dir, "bench_model.jsonl").appendText(json + "\n", Charsets.UTF_8) }
        }
    }
}
