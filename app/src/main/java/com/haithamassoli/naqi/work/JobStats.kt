package com.haithamassoli.naqi.work

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale

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
 */
class JobStats(private val context: Context) {

    private val startMs = SystemClock.elapsedRealtime()
    private var stageStartMs = startMs
    private var stageName = ""
    private var maxThermal = 0

    /** Close the running stage (logging its cost) and open [name]. Names are stable ASCII, for grep. */
    fun stage(name: String) {
        closeStage()
        stageName = name
        stageStartMs = SystemClock.elapsedRealtime()
    }

    /** Sample the one number that is a level rather than a high-water mark. */
    fun tick() {
        val status = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: return
        if (status > maxThermal) maxThermal = status
    }

    /**
     * Remaining wall clock from this device's own observed rate, or 0 while it is too early to say.
     *
     * ponytail: straight-line extrapolation over overall percent, which assumes the progress bands are
     * proportional to their real cost. They roughly are (they were sized off the M2/M3 measurements),
     * so the error is a wobble at each stage boundary that corrects itself within seconds. Per-stage
     * rates would be exact — add them when a soak shows the wobble actually misleads anyone.
     */
    fun etaMs(pct: Int): Long =
        if (pct < MIN_PCT_FOR_ETA) 0L else elapsedMs() * (100 - pct) / pct

    /** One greppable summary line. [extra] carries whatever the shape measured (e.g. face-track counts). */
    fun finish(extra: String) {
        closeStage()
        Log.i(TAG, "SOAK total=${fmt(elapsedMs())} peakRssKb=${vmHwmKb()} maxThermal=$maxThermal $extra")
    }

    private fun closeStage() {
        if (stageName.isEmpty()) return
        Log.i(
            TAG,
            "SOAK stage=$stageName took=${fmt(SystemClock.elapsedRealtime() - stageStartMs)}" +
                " at=${fmt(elapsedMs())} peakRssKb=${vmHwmKb()} maxThermal=$maxThermal",
        )
        stageName = ""
    }

    private fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startMs

    /** Peak resident set since process start, in KB; -1 if the kernel stopped exposing it. */
    private fun vmHwmKb(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()
        }
    }.getOrNull() ?: -1L

    // Locale.ROOT: this is a diagnostic, and on an Arabic device the default locale renders the
    // minutes in Arabic-Indic digits, which is unparseable by whatever reads the soak log later.
    private fun fmt(ms: Long): String = "${ms}ms(%.1fmin)".format(Locale.ROOT, ms / 60_000f)

    private companion object {
        const val TAG = "JobStats"

        /** Below this the sample is one stage's warm-up and the extrapolation is nonsense. */
        const val MIN_PCT_FOR_ETA = 3
    }
}
