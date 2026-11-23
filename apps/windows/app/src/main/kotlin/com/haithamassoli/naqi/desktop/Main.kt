package com.haithamassoli.naqi.desktop

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.haithamassoli.naqi.desktop.media.OverwriteOutput
import com.haithamassoli.naqi.desktop.pipeline.JobOptions
import com.haithamassoli.naqi.desktop.pipeline.Pipeline
import com.haithamassoli.naqi.desktop.ui.App
import com.haithamassoli.naqi.desktop.ui.JobQueue
import com.haithamassoli.naqi.desktop.ui.NaqiIconBitmap
import com.haithamassoli.naqi.desktop.ui.Strings
import com.haithamassoli.naqi.desktop.ui.sameFile
import com.haithamassoli.naqi.model.FilterOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.system.exitProcess

/** With arguments, the headless CLI ([runCli]); without, the window. */
fun main(args: Array<String>) {
    if (args.isNotEmpty()) exitProcess(runCli(args.toList()))

    // Outside composition so the close button can cancel running jobs before the JVM exits (see cancelAllAndWait).
    val queue = JobQueue(CoroutineScope(SupervisorJob() + Dispatchers.Default), Pipeline::run)
    val strings = Strings.forLocale(Locale.getDefault())
    application {
        Window(
            onCloseRequest = { queue.cancelAllAndWait(); exitApplication() },
            title = strings.appName,
            icon = BitmapPainter(NaqiIconBitmap),
            state = rememberWindowState(width = 560.dp, height = 860.dp),
        ) {
            App(queue, strings)
        }
    }
}

internal const val USAGE = """Usage: Naqi --input <video> --output <file.mp4> [options]

  --music vocals|vocals+other   remove music, keeping voices (or voices and other sounds)
  --faces everyone|women|men    censor these faces
  --style <style>[,<style>…]    blur[:<0-100>] (default blur:60) · fill:gray|black|white|navy|green
                                · gray (grayscale blur) · whole (cover the whole frame while a face shows)
  --nsfw <0-100>                cover whole flagged scenes at this strictness
  --overwrite                   replace --output if it exists (otherwise an existing file is refused)

At least one of --music, --faces or --nsfw. Progress goes to stdout as "<STAGE> <percent>" lines.
Exit status: 0 done, 1 the job failed, 2 bad arguments."""

private val FILL_NAMES = listOf("gray", "black", "white", "navy", "green") // FilterOps.SOLID_COLORS order

/** A parsed command line: the job, and whether it may replace an existing output. */
internal data class CliJob(val options: JobOptions, val overwrite: Boolean)

/** Arguments to a job. Every problem is an IllegalArgumentException whose message is shown with [USAGE]. */
internal fun parseCli(args: List<String>): CliJob {
    val values = mutableMapOf<String, String>()
    var overwrite = false
    var i = 0
    while (i < args.size) {
        val key = args[i]
        if (key == "--overwrite") {
            overwrite = true
            i += 1
            continue
        }
        require(key in setOf("--input", "--output", "--music", "--faces", "--style", "--nsfw")) { "Unknown argument: $key" }
        require(i + 1 < args.size) { "$key needs a value" }
        require(key !in values) { "$key given twice" }
        values[key] = args[i + 1]
        i += 2
    }
    val input = File(requireNotNull(values["--input"]) { "--input is required" })
    val output = File(requireNotNull(values["--output"]) { "--output is required" })
    require(input.isFile) { "No such file: $input" }
    require(!sameFile(input, output)) { "--output is the input file; the original is never overwritten" }
    require(overwrite || !output.exists()) { "$output exists; give --overwrite to replace it" }

    var ops = FilterOps(censorNsfw = false)
    values["--music"]?.let {
        val stems = when (it) {
            "vocals" -> "vocals"
            "vocals+other", "vocals_other" -> "vocals_other"
            else -> throw IllegalArgumentException("--music must be vocals or vocals+other, not $it")
        }
        ops = ops.copy(removeMusic = true, keepStems = stems)
    }
    values["--faces"]?.let {
        require(it in setOf(FilterOps.EVERYONE, FilterOps.WOMEN, FilterOps.MEN)) { "--faces must be everyone, women or men, not $it" }
        ops = ops.copy(censorWho = it)
    }
    values["--nsfw"]?.let {
        val strictness = it.toIntOrNull()
        require(strictness != null && strictness in 0..100) { "--nsfw must be 0..100, not $it" }
        ops = ops.copy(censorNsfw = true, strictness = strictness)
    }
    values["--style"]?.split(',')?.map { it.trim() }?.forEach { style ->
        ops = when {
            style == "blur" -> ops.copy(solidColor = FilterOps.BLUR)
            style.startsWith("blur:") -> {
                val amount = style.removePrefix("blur:").toIntOrNull()
                require(amount != null && amount in 0..100) { "blur strength must be 0..100: $style" }
                ops.copy(solidColor = FilterOps.BLUR, blurAmount = amount)
            }
            style.startsWith("fill:") -> {
                val index = FILL_NAMES.indexOf(style.removePrefix("fill:"))
                require(index >= 0) { "fill colour must be one of ${FILL_NAMES.joinToString()}: $style" }
                ops.copy(solidColor = FilterOps.SOLID_COLORS[index])
            }
            style == "gray" -> ops.copy(grayscale = true)
            style == "whole" -> ops.copy(wholeFrameBlur = true)
            else -> throw IllegalArgumentException("Unknown style: $style")
        }
    }
    require(ops.removeMusic || ops.censorFaces || ops.censorNsfw) { "Nothing to do: give --music, --faces or --nsfw" }
    return CliJob(JobOptions(input, output, ops), overwrite)
}

/** The scripted end-to-end entry: one job through the same Pipeline.run the window uses. */
internal fun runCli(args: List<String>): Int {
    if (args.firstOrNull() in setOf("-h", "--help")) {
        println(USAGE)
        return 0
    }
    val cli = try {
        parseCli(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println()
        System.err.println(USAGE)
        return 2
    }
    val job = cli.options
    return runBlocking {
        var last = ""
        // Set once Pipeline.run has returned, i.e. the output is in place: a Ctrl+C landing after that still
        // makes await() throw, but the job is done.
        var done = false
        // Not a child of runBlocking: a failing job must reach the catch below, not cancel the whole block.
        val run = CoroutineScope(Dispatchers.Default + (if (cli.overwrite) OverwriteOutput else EmptyCoroutineContext)).async {
            Pipeline.run(job) { stage, p ->
                val line = "$stage $p"
                if (line != last) { last = line; println(line) }
            }
            done = true
        }
        // Ctrl+C: cancel, and give the renderer a moment to delete its temp file before the JVM goes.
        val hook = Thread { run.cancel(); runBlocking { withTimeoutOrNull(5_000) { run.join() } } }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            runCatching { run.await() }.onFailure { if (!done) throw it }
            println("DONE ${job.output.absolutePath}")
            0
        } catch (e: Throwable) { // not just Exception: a job that dies of an Error must still exit 1 with a message
            System.err.println("FAILED: ${e.message ?: e.javaClass.name}")
            1
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
        }
    }
}
