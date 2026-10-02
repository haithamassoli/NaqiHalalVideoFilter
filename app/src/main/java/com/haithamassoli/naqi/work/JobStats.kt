package com.haithamassoli.naqi.work

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.haithamassoli.naqi.BuildConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Phase-0 soak instrumentation (`long-film-plan.md`): per-stage wall clock, peak RSS and worst thermal
 * status for one job, plus the live ETA the UI shows. Everything lands in logcat under `SOAK` — a 2 h
 * run's numbers have to survive `adb logcat`, there is no one watching a debugger for five hours.
 *
 * Peak RSS is the kernel's own high-water mark (`/proc/self/status` VmHWM), not a sampled maximum, so
 * no polling interval can miss a spike between ticks. Only thermal needs sampling: it is a level, not
 * a high-water mark, and [tick] rides the progress callbacks that already fire on every chunk/frame.
 *
 * [stage] and [finish] are called only from the worker's own coroutine, but [tick] rides progress
 * callbacks that arrive on the transformer's Looper and on Dispatchers.Default too. The only state it
 * touches is [maxThermal], and a lost racing update there costs one sampled thermal transition in a
 * log line — not worth synchronizing a diagnostic on the hot path.
 *
 * Cheap enough to leave in release builds: one `/proc` read per stage boundary, one binder call per tick.
 * `DEBUG_HOOKS` adds a 1 Hz JSON-lines snapshot under `filesDir/bench/` (mirrored to
 * `getExternalFilesDir("bench")`) — plan §3.3.
 */
class JobStats(private val context: Context) {

    private val startMs = SystemClock.elapsedRealtime()
    private var stageStartMs = startMs
    private var stageName = ""
    private var maxThermal = 0

    private val lastJsonMs = AtomicLong(0L)
    private val stageMs = LinkedHashMap<String, Long>()
    private val jsonName = if (BuildConfig.DEBUG_HOOKS) "job-${System.currentTimeMillis()}.jsonl" else null
    private val freqFiles: Array<File> =
        if (BuildConfig.DEBUG_HOOKS) policyFreqFiles() else emptyArray()

    /** Close the running stage (logging its cost) and open [name]. Names are stable ASCII, for grep. */
    fun stage(name: String) {
        closeStage()
        stageName = name
        stageStartMs = SystemClock.elapsedRealtime()
    }

    /** Sample the one number that is a level rather than a high-water mark. */
    fun tick() {
        try {
            val pm = context.getSystemService(PowerManager::class.java) ?: return
            val status = pm.currentThermalStatus
            if (status > maxThermal) maxThermal = status
            if (BuildConfig.DEBUG_HOOKS) maybeJson(pm)
        } catch (_: Throwable) {
        }
    }

    /**
     * Remaining wall clock from this device's own observed rate, or 0 while it is too early to say.
     *
     * ponytail: straight-line extrapolation over overall percent, which assumes the progress bands are
     * proportional to their real cost. Since the reweight they are — [Eta.Bands] carries the measured
     * shares — so the error is a wobble at each stage boundary rather than the 2× over-promise the even
     * split used to hand a feature-length job. Per-stage rates would be exact; add them when a soak
     * shows the wobble actually misleads anyone.
     */
    fun etaMs(pct: Int): Long =
        if (pct < MIN_PCT_FOR_ETA) 0L else elapsedMs() * (100 - pct) / pct

    /** One greppable summary line. [extra] carries whatever the shape measured (e.g. face-track counts). */
    fun finish(extra: String) {
        closeStage()
        Log.i(TAG, "SOAK total=${fmt(elapsedMs())} peakRssKb=${vmHwmKb()} maxThermal=$maxThermal $extra")
        if (!BuildConfig.DEBUG_HOOKS) return
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            appendJson(jsonSnapshot(pm, final = true))
        } catch (_: Throwable) {
        }
    }

    private fun closeStage() {
        if (stageName.isEmpty()) return
        val took = SystemClock.elapsedRealtime() - stageStartMs
        Log.i(
            TAG,
            "SOAK stage=$stageName took=${fmt(took)}" +
                " at=${fmt(elapsedMs())} peakRssKb=${vmHwmKb()} maxThermal=$maxThermal",
        )
        if (BuildConfig.DEBUG_HOOKS) {
            stageMs[stageName] = (stageMs[stageName] ?: 0L) + took
        }
        stageName = ""
    }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startMs

    /** Peak resident set since process start, in KB; -1 if the kernel stopped exposing it. */
    private fun vmHwmKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()
        }
    }.getOrNull() ?: -1L

    private fun maybeJson(pm: PowerManager) {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val prev = lastJsonMs.get()
            if (now - prev < JSON_PERIOD_MS) return
            if (lastJsonMs.compareAndSet(prev, now)) break
        }
        appendJson(jsonSnapshot(pm, final = false))
    }

    private fun jsonSnapshot(pm: PowerManager?, final: Boolean): String {
        val (rss, hwm) = vmRssHwm()
        val sb = StringBuilder(256)
        sb.append('{')
        if (final) sb.append("\"final\":true,")
        sb.append("\"t\":").append(elapsedMs())
        sb.append(",\"stage\":\"").append(esc(stageName)).append('"')
        if (final) {
            sb.append(",\"stages\":{")
            var first = true
            for ((n, ms) in stageMs) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(esc(n)).append("\":").append(ms)
            }
            sb.append('}')
        }
        sb.append(",\"freq\":[")
        for (i in freqFiles.indices) {
            if (i > 0) sb.append(',')
            sb.append(readLong(freqFiles[i]))
        }
        sb.append(']')
        sb.append(",\"battC\":").append(battCJson())
        sb.append(",\"headroom\":").append(headroomJson(pm))
        sb.append(",\"thermal\":").append(pm?.currentThermalStatus ?: -1)
        sb.append(",\"rssKb\":").append(rss)
        sb.append(",\"hwmKb\":").append(hwm)
        sb.append('}')
        return sb.toString()
    }

    private fun appendJson(line: String) {
        val name = jsonName ?: return
        val dirs = listOfNotNull(File(context.filesDir, "bench"), context.getExternalFilesDir("bench"))
        for (dir in dirs) {
            runCatching {
                dir.mkdirs()
                File(dir, name).appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    private fun battCJson(): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "null"
        if (!intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) return "null"
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        return "%.1f".format(Locale.ROOT, tenths / 10.0)
    }

    private fun headroomJson(pm: PowerManager?): String {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return "null"
        val h = pm.getThermalHeadroom(0)
        if (h.isNaN() || h.isInfinite()) return "null"
        return "%.4f".format(Locale.ROOT, h)
    }

    // Locale.ROOT: this is a diagnostic, and on an Arabic device the default locale renders the
    // minutes in Arabic-Indic digits, which is unparseable by whatever reads the soak log later.
    private fun fmt(ms: Long): String = "${ms}ms(%.1fmin)".format(Locale.ROOT, ms / 60_000f)

    private companion object {
        const val TAG = "JobStats"

        /** Below this the sample is one stage's warm-up and the extrapolation is nonsense. */
        const val MIN_PCT_FOR_ETA = 3

        /** getThermalHeadroom returns NaN when polled <500 ms apart; 1 Hz stays clear of that. */
        const val JSON_PERIOD_MS = 1000L

        fun policyFreqFiles(): Array<File> =
            File("/sys/devices/system/cpu/cpufreq").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("policy") }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
                ?.map { File(it, "scaling_cur_freq") }
                ?.toTypedArray()
                ?: emptyArray()

        fun readLong(f: File): Long =
            runCatching { f.readText().trim().toLong() }.getOrDefault(-1L)

        fun vmRssHwm(): Pair<Long, Long> {
            var rss = -1L
            var hwm = -1L
            runCatching {
                File("/proc/self/status").useLines { lines ->
                    for (line in lines) {
                        when {
                            line.startsWith("VmRSS:") -> rss = line.filter { it.isDigit() }.toLong()
                            line.startsWith("VmHWM:") -> hwm = line.filter { it.isDigit() }.toLong()
                        }
                        if (rss >= 0 && hwm >= 0) break
                    }
                }
            }
            return rss to hwm
        }

        fun esc(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(c)
            }
        }
    }
}
