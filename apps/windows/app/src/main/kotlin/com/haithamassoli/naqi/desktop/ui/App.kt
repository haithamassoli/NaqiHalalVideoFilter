package com.haithamassoli.naqi.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.haithamassoli.naqi.desktop.pipeline.JobOptions
import com.haithamassoli.naqi.model.FilterOps
import java.io.File
import java.net.URI
import java.util.Locale

private enum class Step { Pick, Options, Jobs, About }

/**
 * The whole app, Android's flow (`ui/NaqiApp.kt`): pick a video and the operations, tune them and choose
 * where the result goes, then watch the job list. A `when` over four steps; a nav library would add a
 * dependency to express a straight line.
 *
 * Every job goes through [queue], which calls only `Pipeline.run`. The two dialogs are parameters so the UI
 * test can stand in for the OS file pickers.
 */
@Composable
fun App(
    queue: JobQueue,
    strings: Strings = Strings.forLocale(Locale.getDefault()),
    chooseVideo: () -> File? = { chooseVideoDialog(strings.pickVideoNone) },
    chooseOutput: (suggested: File) -> File? = { chooseOutputDialog(strings.sectionOutput, it) },
) {
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalLayoutDirection provides if (strings.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        NaqiTheme {
            var step by remember { mutableStateOf(Step.Pick) }
            var input by remember { mutableStateOf<File?>(null) }
            var output by remember { mutableStateOf<File?>(null) }
            // Censoring on, for Android's default who, as Android's entry points open; off-then-on keeps
            // the last real choice, because NONE would erase it (PickOpsScreen's `who`).
            var ops by remember { mutableStateOf(FilterOps(censorWho = FilterOps.DEFAULT_WHO)) }
            var lastWho by remember { mutableStateOf(FilterOps.DEFAULT_WHO) }

            fun taken() = queue.items.filter { !it.finished }.map { it.options.output }
            fun pick(f: File) {
                input = f
                output = null
            }

            Box(Modifier.fillMaxSize().videoDropTarget { pick(it); step = Step.Pick }) {
                when (step) {
                    Step.Pick -> PickScreen(
                        input = input,
                        ops = ops,
                        lastWho = lastWho,
                        onPick = { chooseVideo()?.let(::pick) },
                        onOpsChange = { ops = it; if (it.censorFaces) lastWho = it.censorWho },
                        onContinue = {
                            // Recomputed while it is unset or another queued job claimed it since.
                            val current = output
                            if (current == null || taken().any { sameFile(it, current) }) output = defaultOutput(input!!, taken())
                            step = Step.Options
                        },
                        onJobs = { step = Step.Jobs },
                        onAbout = { step = Step.About },
                    )

                    Step.Options -> OptionsScreen(
                        input = input!!,
                        output = output!!,
                        ops = ops,
                        onOpsChange = { ops = it; if (it.censorFaces) lastWho = it.censorWho },
                        onChangeOutput = { chooseOutput(output!!)?.let { output = outputFor(it, taken()) } },
                        onBack = { step = Step.Pick },
                        onStart = {
                            // The NSFW switch only exists under "Censor faces" (as on Android), so a run without
                            // faces must not quietly analyze scenes the user never saw switched on.
                            val runOps = ops.copy(censorNsfw = ops.censorNsfw && ops.censorFaces)
                            queue.submit(JobOptions(input!!, output!!, runOps))
                            output = null
                            step = Step.Jobs
                        },
                    )

                    Step.Jobs -> JobsScreen(queue.items, onNewJob = { step = Step.Pick })
                    Step.About -> AboutScreen(onBack = { step = Step.Pick })
                }
            }
        }
    }
}

/** A video file dropped anywhere on the window picks it, as the file dialog would. Non-videos are refused. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun Modifier.videoDropTarget(onVideo: (File) -> Unit): Modifier {
    fun firstVideo(event: DragAndDropEvent): File? =
        (event.dragData() as? DragData.FilesList)?.readFiles()
            ?.firstNotNullOfOrNull { uri -> runCatching { File(URI(uri)) }.getOrNull()?.takeIf(::isVideoFile) }

    val current by rememberUpdatedState(onVideo)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = firstVideo(event)?.also { current(it) } != null
        }
    }
    return dragAndDropTarget(shouldStartDragAndDrop = { it.dragData() is DragData.FilesList }, target = target)
}
