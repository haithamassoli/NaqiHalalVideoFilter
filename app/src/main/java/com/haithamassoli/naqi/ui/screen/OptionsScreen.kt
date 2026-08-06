package com.haithamassoli.naqi.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.media.containerDurationMs
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiBottomAction
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.NaqiTopBar
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.SelectDot
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.Eta
import com.haithamassoli.naqi.work.JobController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** TalkBack names for [FilterOps.SOLID_COLORS], same order. A bare colour circle says nothing aloud. */
private val SOLID_LABELS = listOf(
    R.string.opt_solid_gray,
    R.string.opt_solid_black,
    R.string.opt_solid_white,
    R.string.opt_solid_navy,
    R.string.opt_solid_green,
)

/** Black: the censor bar everyone already recognizes. */
private val DEFAULT_SOLID = FilterOps.SOLID_COLORS[1]

/**
 * The three *reachable* [FilterOps.censorWho] values, in segment order. [FilterOps.NONE] is missing on
 * purpose: it is the toggle on step 1, not a segment here (plan-censor-who §2.2).
 */
private val WHO_CHOICES = listOf(
    FilterOps.EVERYONE to R.string.opt_who_everyone,
    FilterOps.WOMEN to R.string.opt_who_women,
    FilterOps.MEN to R.string.opt_who_men,
)

/** Shared with [PickOpsScreen], whose faces row states the current choice in its subtitle (§2.1). */
internal fun whoLabelRes(who: String): Int =
    WHO_CHOICES.firstOrNull { it.first == who }?.second ?: R.string.opt_who_everyone

/**
 * Step 2: tune the selected operations, then start the job. Every control is shown only when the op it
 * applies to is on — an option that can't affect the output would just be a lie on screen.
 *
 * One card per operation, rows inside it, instead of one card per control: the same settings in about
 * half the height, and the grouping now says which operation a control belongs to.
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

    // Probing opens MediaExtractor + MediaMetadataRetriever, so it never runs in composition. 0 means
    // "no estimate" — either still probing or the probe threw — and a failure here is deliberately
    // silent: an unreadable source is Preflight's story to tell when the job starts, and a broken
    // probe must never stand between the user and Start.
    var durationMs by remember(inputUri) { mutableStateOf(0L) }
    LaunchedEffect(inputUri) {
        durationMs = withContext(Dispatchers.IO) {
            // The probe opens the video track, so an audio source always throws here — falling back to
            // the container's own duration is what keeps the ETA line and the long-job confirmation
            // alive for it. A real failure still lands on 0, which is "say nothing".
            runCatching { FrameSampler.probe(context, inputUri).durationMs }
                .getOrElse { context.containerDurationMs(inputUri) }
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NaqiTopBar(stringResource(R.string.opt_title), onBack = onBack) },
        bottomBar = {
            NaqiBottomAction(
                label = stringResource(R.string.action_start),
                enabled = !jobRunning,
                onClick = ::onStart,
                above = {
                    // Hidden while probing and on a probe failure — see durationMs above.
                    if (etaMs > 0) {
                        Text(
                            stringResource(R.string.opt_eta_floor, durationText(etaMs)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (jobRunning) {
                        Text(
                            stringResource(R.string.opt_job_running),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space2, bottom = NaqiTokens.space5),
        ) {
            if (ops.censorFaces) {
                SectionHeader(stringResource(R.string.opt_section_censor_faces))
                NaqiCard(contentPadding = 0.dp) {
                    // First in the card: Who is the largest decision in the section, everything below
                    // only tunes what it selected.
                    // Saved on pick, not on Start: this is the only control that sets Who, and the
                    // pick screen's toggle reads it back the next time censoring is turned on.
                    WhoRow(ops.censorWho) { Prefs.saveWho(context, it); onOpsChange(ops.copy(censorWho = it)) }
                    NaqiRowDivider()
                    // Directly under Who because it is the other "how much gets covered" decision, and
                    // above Strictness for the same reason Who is: it changes the picture, not the tuning.
                    ToggleTile(
                        title = stringResource(R.string.opt_whole_frame_title),
                        desc = stringResource(R.string.opt_whole_frame_desc),
                        checked = ops.wholeFrameBlur,
                        onCheckedChange = { onOpsChange(ops.copy(wholeFrameBlur = it)) },
                    )
                    NaqiRowDivider()
                    SliderRow(
                        title = stringResource(R.string.opt_strictness_title),
                        desc = stringResource(R.string.opt_strictness_desc),
                        value = ops.strictness,
                    ) { onOpsChange(ops.copy(strictness = it)) }
                    NaqiRowDivider()
                    CensorStyleRow(ops.solidColor) { onOpsChange(ops.copy(solidColor = it)) }
                    // Both only style the blur, and a solid fill has no blur to style — showing them
                    // under Solid would be two controls that cannot change the output.
                    if (ops.solidColor == FilterOps.BLUR) {
                        NaqiRowDivider()
                        SliderRow(
                            title = stringResource(R.string.opt_blur_amount_title),
                            desc = stringResource(R.string.opt_blur_amount_desc),
                            value = ops.blurAmount,
                        ) { onOpsChange(ops.copy(blurAmount = it)) }
                        NaqiRowDivider()
                        ToggleTile(
                            title = stringResource(R.string.opt_grayscale_title),
                            desc = stringResource(R.string.opt_grayscale_desc),
                            checked = ops.grayscale,
                            onCheckedChange = { onOpsChange(ops.copy(grayscale = it)) },
                        )
                    }
                    // The collapsible "Advanced" row went with plan-v2 §5.4: "Blur unknown faces" was the
                    // only thing inside it, and once every detected face is censored there is no unknown
                    // bucket left to open. An expander over nothing is worse than no expander.
                }
                Spacer(Modifier.height(NaqiTokens.space5))
            }

            if (ops.removeMusic) {
                SectionHeader(stringResource(R.string.opt_section_remove_music))
                NaqiCard(contentPadding = 0.dp) {
                    KeepStemsOption(
                        title = stringResource(R.string.opt_keep_vocals_title),
                        desc = stringResource(R.string.opt_keep_vocals_desc),
                        selected = ops.keepStems == "vocals",
                    ) { onOpsChange(ops.copy(keepStems = "vocals")) }
                    NaqiRowDivider()
                    KeepStemsOption(
                        title = stringResource(R.string.opt_keep_vocals_other_title),
                        desc = stringResource(R.string.opt_keep_vocals_other_desc),
                        selected = ops.keepStems == "vocals_other",
                    ) { onOpsChange(ops.copy(keepStems = "vocals_other")) }
                }
            }
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

/**
 * Which faces get covered. Three segments, not four: the whole card renders only under
 * `ops.censorFaces`, so [FilterOps.NONE] cannot be the current value here and a fourth "Off" segment
 * would be a control that turns off the card containing it. Off stays the step-1 toggle.
 */
@Composable
private fun WhoRow(who: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Text(stringResource(R.string.opt_who_title), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text(stringResource(R.string.opt_who_desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(NaqiTokens.space3))
        SingleChoiceSegmentedButtonRow {
            WHO_CHOICES.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = who == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = WHO_CHOICES.size),
                ) { Text(stringResource(label)) }
            }
        }
    }
}

/**
 * Blur or a flat fill, and which fill. The two live in one row because they are one decision: picking
 * a swatch IS choosing Solid, so a user who reaches straight for a colour never has to notice the
 * segmented control at all.
 *
 * ponytail: five fixed swatches, no full picker. A censor bar wants to be unremarkable, and the set
 * covers that; add a picker if anyone actually asks to match a specific palette.
 */
@Composable
private fun CensorStyleRow(color: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Text(stringResource(R.string.opt_censor_style_title), style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        Text(stringResource(R.string.opt_censor_style_desc), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(NaqiTokens.space3))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(
                selected = color == FilterOps.BLUR,
                onClick = { onChange(FilterOps.BLUR) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text(stringResource(R.string.opt_style_blur)) }
            SegmentedButton(
                // Keeps the colour the user last picked; only a first visit needs a default.
                selected = color != FilterOps.BLUR,
                onClick = { if (color == FilterOps.BLUR) onChange(DEFAULT_SOLID) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text(stringResource(R.string.opt_style_solid)) }
        }
        if (color != FilterOps.BLUR) {
            Spacer(Modifier.height(NaqiTokens.space3))
            Row(horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space3)) {
                FilterOps.SOLID_COLORS.forEachIndexed { i, c ->
                    Swatch(c, selected = c == color, label = stringResource(SOLID_LABELS[i])) { onChange(c) }
                }
            }
        }
    }
}

/** The ring sits OUTSIDE the swatch with a gap, so it reads on black and on white alike. */
@Composable
private fun Swatch(color: Int, selected: Boolean, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .border(2.dp, if (selected) cs.primary else Color.Transparent, CircleShape)
            .selectable(selected = selected, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(1.dp, cs.outlineVariant, CircleShape),
    )
}

@Composable
private fun SliderRow(title: String, desc: String, value: Int, onChange: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            // The number in a tinted pill: the readout used to be a lone digit that read as decoration.
            Text(
                stringResource(R.string.opt_slider_value, value),
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                modifier = Modifier
                    .clip(NaqiTokens.shapePill)
                    .background(cs.primary.copy(alpha = 0.12f))
                    .padding(horizontal = NaqiTokens.space3, vertical = 2.dp),
            )
        }
        Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 0f..100f,
        )
    }
}

/** "vocals" / "vocals_other" are wire values read straight by the worker — never localize or rename them. */
@Composable
private fun KeepStemsOption(title: String, desc: String, selected: Boolean, onSelect: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val rowBgColor by animateColorAsState(
        targetValue = if (selected) cs.primary.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "keepStemsRowBg",
    )

    Row(
        Modifier
            .fillMaxWidth()
            .background(rowBgColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        SelectDot(selected)
    }
}
