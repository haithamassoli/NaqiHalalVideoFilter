package com.haithamassoli.naqi.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.SelectDot
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.Eta
import com.haithamassoli.naqi.work.JobController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Step 2: tune the selected operations, then start the job. Every control is shown only when the op it
 * applies to is on — an option that can't affect the output would just be a lie on screen.
 */
@Composable
fun OptionsScreen(
    inputUri: Uri,
    ops: FilterOps,
    onOpsChange: (FilterOps) -> Unit,
    onBack: () -> Unit,
    onStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var advanced by remember { mutableStateOf(false) }

    // Probing opens MediaExtractor + MediaMetadataRetriever, so it never runs in composition. 0 means
    // "no estimate" — either still probing or the probe threw — and a failure here is deliberately
    // silent: an unreadable source is Preflight's story to tell when the job starts, and a broken
    // probe must never stand between the user and Start.
    var durationMs by remember(inputUri) { mutableStateOf(0L) }
    LaunchedEffect(inputUri) {
        durationMs = withContext(Dispatchers.IO) {
            runCatching { FrameSampler.probe(context, inputUri).durationMs }.getOrDefault(0L)
        }
    }
    // Cheap enough to recompute on recomposition, and it must follow ops: the shape sets the factor.
    val etaMs = Eta.estimateMs(durationMs, ops)
    var confirming by remember { mutableStateOf(false) }

    // Naqi runs one job at a time and the unique work policy is KEEP, so starting a second one would
    // silently do nothing. Say so and disable Start instead of accepting a tap that goes nowhere.
    val workInfos by remember { JobController.observe(context) }.collectAsState(initial = emptyList())
    val jobRunning = workInfos.firstOrNull()?.state.let {
        it == WorkInfo.State.RUNNING || it == WorkInfo.State.ENQUEUED
    }

    fun startJob() {
        JobController.start(context, ops, inputUri.toString())
        onStarted()
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startJob() // start whether granted or not; without it the job runs but its notification is hidden
    }

    // Notifications are the only runtime permission left: publishing into Movies/Naqi is scoped
    // MediaStore at minSdk 29, which needs none.
    fun startWithPermissions() {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotif) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startJob()
    }

    // A long job gets one confirmation, in front of the permission dance so the user isn't asked for
    // notifications only to then back out. It is a WARNING, not a cap: confirming lands in exactly the
    // same place a short job's Start does.
    fun onStart() {
        if (etaMs > Eta.CONFIRM_THRESHOLD_MS) confirming = true else startWithPermissions()
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = modifier) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space4, bottom = NaqiTokens.space7),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.height(NaqiTokens.space2))
            Text(
                stringResource(R.string.opt_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(NaqiTokens.space5))

            if (ops.censorWomen) {
                Eyebrow(stringResource(R.string.opt_section_censor_women))
                Spacer(Modifier.height(NaqiTokens.space3))
                NaqiCard {
                    SliderRow(
                        title = stringResource(R.string.opt_strictness_title),
                        desc = stringResource(R.string.opt_strictness_desc),
                        value = ops.strictness,
                    ) { onOpsChange(ops.copy(strictness = it)) }
                }
                Spacer(Modifier.height(NaqiTokens.space3))
                NaqiCard {
                    SliderRow(
                        title = stringResource(R.string.opt_blur_amount_title),
                        desc = stringResource(R.string.opt_blur_amount_desc),
                        value = ops.blurAmount,
                    ) { onOpsChange(ops.copy(blurAmount = it)) }
                }
                Spacer(Modifier.height(NaqiTokens.space3))
                NaqiCard {
                    ToggleRow(
                        title = stringResource(R.string.opt_grayscale_title),
                        desc = stringResource(R.string.opt_grayscale_desc),
                        checked = ops.grayscale,
                    ) { onOpsChange(ops.copy(grayscale = it)) }
                }
                Spacer(Modifier.height(NaqiTokens.space5))
            }

            if (ops.removeMusic) {
                Eyebrow(stringResource(R.string.opt_section_remove_music))
                Spacer(Modifier.height(NaqiTokens.space3))
                KeepStemsSelector(keepStems = ops.keepStems) { onOpsChange(ops.copy(keepStems = it)) }
                Spacer(Modifier.height(NaqiTokens.space5))
            }

            // Advanced is censor-only today, so the whole section disappears on a music-only job.
            if (ops.censorWomen) {
                NaqiCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { advanced = !advanced },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.opt_advanced_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(if (advanced) R.string.action_hide else R.string.action_show),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (advanced) {
                        Spacer(Modifier.height(NaqiTokens.space3))
                        ToggleRow(
                            title = stringResource(R.string.opt_blur_unknown_faces_title),
                            desc = stringResource(R.string.opt_blur_unknown_faces_desc),
                            checked = ops.blurUnknownFaces,
                        ) { onOpsChange(ops.copy(blurUnknownFaces = it)) }
                    }
                }
                Spacer(Modifier.height(NaqiTokens.space5))
            }



            // Hidden while probing and on a probe failure — see durationMs above.
            if (etaMs > 0) {
                Text(
                    stringResource(R.string.opt_eta_floor, durationText(etaMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NaqiTokens.space3))
            }

            if (jobRunning) {
                Text(
                    stringResource(R.string.opt_job_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NaqiTokens.space3))
            }

            Button(
                onClick = ::onStart,
                enabled = !jobRunning,
                shape = RoundedCornerShape(NaqiTokens.radiusButton),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(stringResource(R.string.action_start), style = MaterialTheme.typography.labelLarge) }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.dlg_long_job_title)) },
            text = { Text(stringResource(R.string.dlg_long_job_body, durationText(etaMs))) },
            confirmButton = {
                TextButton(onClick = { confirming = false; startWithPermissions() }) {
                    Text(stringResource(R.string.action_start))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SliderRow(title: String, desc: String, value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.opt_slider_value, value), style = MaterialTheme.typography.labelMedium, color = cs.primary)
    }
    Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = 0f..100f,
    )
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** "vocals" / "vocals_other" are wire values read straight by the worker — never localize or rename them. */
@Composable
private fun KeepStemsSelector(keepStems: String, onSelect: (String) -> Unit) {
    NaqiCard {
        Text(
            stringResource(R.string.opt_keep_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        KeepStemsOption(
            title = stringResource(R.string.opt_keep_vocals_title),
            desc = stringResource(R.string.opt_keep_vocals_desc),
            selected = keepStems == "vocals",
        ) { onSelect("vocals") }
        Spacer(Modifier.height(NaqiTokens.space2))
        KeepStemsOption(
            title = stringResource(R.string.opt_keep_vocals_other_title),
            desc = stringResource(R.string.opt_keep_vocals_other_desc),
            selected = keepStems == "vocals_other",
        ) { onSelect("vocals_other") }
    }
}

@Composable
private fun KeepStemsOption(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusButton))
            .clickable(onClick = onSelect)
            .padding(vertical = NaqiTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        SelectDot(selected) // reuse the exact op-card selection dot
    }
}
