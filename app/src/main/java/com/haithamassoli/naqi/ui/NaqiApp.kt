package com.haithamassoli.naqi.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.screen.JobsScreen
import com.haithamassoli.naqi.ui.screen.OptionsScreen
import com.haithamassoli.naqi.ui.screen.PickOpsScreen
import com.haithamassoli.naqi.work.JobController

private enum class Step { Pick, Options, Jobs }

/**
 * The whole app: three linear steps over one piece of shared state (the picked video + its [FilterOps]).
 * A `when` plus a [BackHandler] covers this — a nav library would add a dependency and a route DSL to
 * express a straight line.
 */
@Composable
fun NaqiApp(modifier: Modifier = Modifier) {
    // rememberSaveable, not remember: a filter job runs for minutes, so rotation and process death
    // are both likely mid-job. Plain remember would drop the user back on Pick with no route to the
    // running job — and the only way forward there (Start) REPLACEs the very job they were watching.
    var step by rememberSaveable { mutableStateOf(Step.Pick) }
    var pickedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var pickedName by rememberSaveable { mutableStateOf<String?>(null) }
    var ops by rememberSaveable { mutableStateOf(FilterOps()) }

    // Process death loses even saved state, so re-attach to a live job on a cold start.
    val context = LocalContext.current
    val jobs by remember { JobController.observe(context) }.collectAsState(initial = emptyList())
    var attached by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(jobs) {
        if (attached) return@LaunchedEffect
        if (jobs.any { !it.state.isFinished }) {
            step = Step.Jobs
            attached = true
        }
    }

    // Both later steps go back to the start: options is a detour off pick, and jobs ends the flow.
    BackHandler(enabled = step != Step.Pick) { step = Step.Pick }

    when (step) {
        Step.Pick -> PickOpsScreen(
            pickedUri = pickedUri,
            pickedName = pickedName,
            ops = ops,
            onPicked = { uri, name -> pickedUri = uri; pickedName = name },
            onOpsChange = { ops = it },
            onContinue = { step = Step.Options },
            modifier = modifier,
        )

        // Never null here: Continue is disabled until a video is picked.
        Step.Options -> pickedUri?.let { uri ->
            OptionsScreen(
                inputUri = uri,
                ops = ops,
                onOpsChange = { ops = it },
                onBack = { step = Step.Pick },
                onStarted = { step = Step.Jobs },
                modifier = modifier,
            )
        }

        // Resuming re-enqueues the SAME (source, options), which lands on the same JobStore key and skips
        // every segment already finished. Unavailable if the picked video is gone from saved state.
        Step.Jobs -> JobsScreen(
            onNewJob = { step = Step.Pick },
            onResume = pickedUri?.let { uri -> { JobController.start(context, ops, uri.toString()) } },
            modifier = modifier,
        )
    }
}
