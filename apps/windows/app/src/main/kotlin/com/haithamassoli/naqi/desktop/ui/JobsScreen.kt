package com.haithamassoli.naqi.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.desktop.pipeline.Stage

/**
 * Step 3, Android's `JobsScreen` for a desktop: every job of this session, newest first, each with its own
 * progress, ETA and cancel. Kept in memory only; the outputs themselves are ordinary files the user can find.
 */
@Composable
internal fun JobsScreen(items: List<JobItem>, onNewJob: () -> Unit) {
    val s = LocalStrings.current
    NaqiScreen(
        title = s.jobsTitle,
        onBack = onNewJob,
        bottom = { NaqiBottomAction(label = s.jobsNewJob, enabled = true, onClick = onNewJob) },
    ) {
        if (items.isEmpty()) {
            Text(
                s.jobsNone,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = NaqiTokens.space1),
            )
        }
        items.forEachIndexed { i, item ->
            if (i > 0) Spacer(Modifier.height(NaqiTokens.space3))
            JobCard(item)
        }
    }
}

@Composable
private fun JobCard(item: JobItem) {
    val s = LocalStrings.current
    val cs = MaterialTheme.colorScheme
    NaqiCard {
        Text(
            item.options.input.name,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        when (item.state) {
            JobState.QUEUED, JobState.RUNNING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.state == JobState.QUEUED) s.jobQueued else stageLabel(s, item.stage, item.options.ops.censorFaces || item.options.ops.censorNsfw),
                        style = MaterialTheme.typography.titleMedium,
                        color = cs.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(s.progressPercent(item.percent), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                }
                Spacer(Modifier.height(NaqiTokens.space3))
                LinearProgressIndicator(
                    progress = { item.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(NaqiTokens.shapePill),
                    color = cs.primary,
                    trackColor = cs.surfaceContainerHighest,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (item.etaMs > 0) s.etaRemaining(s.duration(item.etaMs)) else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = item::cancel) { Text(s.actionCancel) }
                }
            }

            JobState.DONE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(NaqiIcons.Check, null, tint = cs.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(NaqiTokens.space1))
                    Text(
                        "${s.savedLabel} · ${item.options.output.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(NaqiTokens.space3))
                Row {
                    OutlinedButton(onClick = { openFile(item.options.output) }, shape = NaqiTokens.shapeButton) {
                        Icon(NaqiIcons.Play, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(NaqiTokens.space1))
                        Text(s.actionOpen)
                    }
                    Spacer(Modifier.width(NaqiTokens.space2))
                    TextButton(onClick = { showInFolder(item.options.output) }) { Text(s.showInFolder) }
                }
            }

            JobState.FAILED -> Column {
                Text(s.errGeneric, style = MaterialTheme.typography.bodyMedium, color = cs.error)
                item.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }

            JobState.CANCELLED -> Text(s.jobCancelled, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

/** Render without censoring is only the remux of the new audio, which Android calls "Finishing up". */
private fun stageLabel(s: Strings, stage: Stage?, censoring: Boolean) = when (stage) {
    null -> s.stageStarting
    Stage.ANALYZE -> s.stageAnalyzing
    Stage.MUSIC -> s.stageSeparating
    Stage.RENDER -> if (censoring) s.stageRendering else s.stageMuxing
}
