package com.haithamassoli.naqi.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.model.FilterOps
import java.io.File

/**
 * Step 1, Android's `PickOpsScreen`: the video and which operations run. Tuning lives on the options
 * screen, so nothing here writes anything but [FilterOps.removeMusic] and [FilterOps.censorWho].
 */
@Composable
internal fun PickScreen(
    input: File?,
    ops: FilterOps,
    lastWho: String,
    onPick: () -> Unit,
    onOpsChange: (FilterOps) -> Unit,
    onContinue: () -> Unit,
    onJobs: () -> Unit,
    onAbout: () -> Unit,
) {
    val s = LocalStrings.current
    NaqiScreen(
        title = s.appName,
        titleIcon = NaqiIcons.Mark,
        actions = {
            TextButton(onClick = onJobs) { Text(s.jobsTitle) }
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) { Icon(NaqiIcons.More, contentDescription = s.actionMore) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(s.aboutTitle) }, onClick = { open = false; onAbout() })
            }
        },
        bottom = {
            NaqiBottomAction(
                label = s.actionContinue,
                // A job without a video has nothing to filter, one without an op nothing to do.
                enabled = input != null && ops.any,
                onClick = onContinue,
                above = { NoteLine(NaqiIcons.Check, s.reassurance) },
            )
        },
    ) {
        TrustSeal()
        Spacer(Modifier.height(NaqiTokens.space5))
        PickVideoCard(input, onPick)
        Spacer(Modifier.height(NaqiTokens.space2))
        Text(
            s.pickDrop,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NaqiTokens.space5))

        SectionHeader(s.pickEyebrowChoose)
        NaqiCard(contentPadding = 0.dp) {
            ToggleTile(
                title = s.opMusicTitle,
                desc = s.opMusicDesc,
                icon = NaqiIcons.MusicOff,
                checked = ops.removeMusic,
                onCheckedChange = { onOpsChange(ops.copy(removeMusic = it)) },
            )
            NaqiRowDivider()
            ToggleTile(
                title = s.opFacesTitle,
                // States the current choice rather than claiming "every face"; off gets the plain description.
                desc = if (ops.censorFaces) s.opFacesDesc(whoLabel(s, ops.censorWho)) else s.opFacesDescOff,
                icon = NaqiIcons.Shield,
                checked = ops.censorFaces,
                onCheckedChange = { onOpsChange(ops.copy(censorWho = if (it) lastWho else FilterOps.NONE)) },
            )
        }
    }
}

internal fun whoLabel(s: Strings, who: String) = when (who) {
    FilterOps.WOMEN -> s.whoWomen
    FilterOps.MEN -> s.whoMen
    else -> s.whoEveryone
}

@Composable
private fun TrustSeal() {
    val s = LocalStrings.current
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2),
            modifier = Modifier
                .clip(NaqiTokens.shapePill)
                .background(primary.copy(alpha = 0.08f))
                .border(1.dp, primary.copy(alpha = 0.22f), NaqiTokens.shapePill)
                .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space2),
        ) {
            Text(s.sealOnDevice, style = MaterialTheme.typography.labelMedium, color = primary)
            Text("·", style = MaterialTheme.typography.labelMedium, color = primary.copy(alpha = 0.55f))
            Text(s.sealPrivate, style = MaterialTheme.typography.labelMedium, color = primary)
        }
    }
}

/** A wide, unmistakable target that also reports what is picked. */
@Composable
private fun PickVideoCard(input: File?, onClick: () -> Unit) {
    val s = LocalStrings.current
    val cs = MaterialTheme.colorScheme
    val picked = input != null
    val bg by animateColorAsState(if (picked) cs.primary.copy(alpha = 0.08f) else cs.surfaceContainer, NaqiTokens.expressiveSpring())
    val border by animateColorAsState(if (picked) cs.primary else cs.outlineVariant, NaqiTokens.expressiveSpring())
    val iconBg by animateColorAsState(if (picked) cs.primary else cs.surfaceContainerHighest, NaqiTokens.expressiveSpring())
    Row(
        Modifier
            .fillMaxWidth()
            .clip(NaqiTokens.shapeCard)
            .background(bg)
            .border(1.5.dp, border, NaqiTokens.shapeCard)
            .clickable(onClick = onClick)
            .padding(NaqiTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp).clip(NaqiTokens.shapeButton).background(iconBg), contentAlignment = Alignment.Center) {
            Icon(
                if (picked) NaqiIcons.Check else NaqiIcons.Video,
                contentDescription = null,
                tint = if (picked) cs.onPrimary else cs.onSurfaceVariant,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(NaqiTokens.space4))
        Column(Modifier.weight(1f)) {
            Text(
                input?.name ?: s.pickVideoNone,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (picked) s.pickVideoChange else s.pickVideoFormats,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
