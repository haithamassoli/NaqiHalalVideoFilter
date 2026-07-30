package com.haithamassoli.naqi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ui.theme.NaqiTokens

// The handful of pieces that appear on more than one screen. Anything used once stays private to its screen.

/**
 * Every screen wears the same bar: a title, an optional back arrow, and room for actions. Replaces the
 * hand-rolled "← Back" text buttons — an [NaqiIcons.ArrowBack] auto-mirrors in Arabic, which a glyph
 * baked into a translated string can only do if every translator remembers to flip it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaqiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(NaqiIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/**
 * The primary action of a screen, pinned to the bottom so it is reachable without scrolling to it.
 * [above] takes the small print that qualifies the button (an estimate, a warning) — it belongs next to
 * the tap, not at the end of a scroll.
 */
@Composable
fun NaqiBottomAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    above: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(
        Modifier
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = NaqiTokens.gutter, vertical = NaqiTokens.space3),
    ) {
        above?.let {
            it()
            Spacer(Modifier.height(NaqiTokens.space2))
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(NaqiTokens.radiusButton),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Section label. Sentence case at title size, not 12sp all-caps with wide tracking: the old eyebrows
 * were the least readable text on every screen. [trailing] holds a section-level action ("Clear
 * finished"), which otherwise had to be parked as a stray button below the list.
 */
@Composable
fun SectionHeader(text: String, trailing: @Composable (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = NaqiTokens.space1, bottom = NaqiTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The selection indicator for single-choice rows (the keep-stems options). */
@Composable
fun SelectDot(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) cs.primary else Color.Transparent)
            .border(1.5.dp, if (selected) Color.Transparent else cs.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(NaqiIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
    }
}

/**
 * The standard bordered block. Related rows go in ONE card separated by [NaqiRowDivider] rather than a
 * card each — a card per row is what made the old screens scroll for so long.
 */
@Composable
fun NaqiCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = NaqiTokens.space4,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(NaqiTokens.radiusCard))
            .padding(contentPadding),
        content = content,
    )
}

/** Separator between rows of one card; inset so it reads as a grouping, not a cut. */
@Composable
fun NaqiRowDivider(inset: Dp = NaqiTokens.space4) {
    HorizontalDivider(
        Modifier.padding(horizontal = inset),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
    )
}

/**
 * One independent on/off setting: whole row tappable, a real [Switch] on the end. A switch rather than a
 * check dot because these are not a choice between options — each one is on or off by itself, and a dot
 * reads as "pick one of these".
 *
 * `Switch(onCheckedChange = null)` plus `toggleable` on the row is deliberate: it makes the row a single
 * node for TalkBack instead of a label and a separately-focusable control.
 */
@Composable
fun ToggleTile(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    desc: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(NaqiTokens.radiusButton))
                    .background(if (checked) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (checked) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(NaqiTokens.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            if (desc != null) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Icon + one line of small print — the trust and reassurance lines. */
@Composable
fun NoteLine(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(NaqiTokens.space1))
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

/**
 * Short wall-clock reading, shared by the up-front estimate (options), the live ETA (jobs) and the
 * progress notification. Picks one of three whole resource strings instead of gluing a number to a
 * unit letter, so Arabic keeps its own digits, word order and unit words rather than inheriting
 * "h"/"min".
 *
 * The notification is built off the Compose tree, hence the plain-[Context] entry point; the
 * composable one is a thin wrapper so there is only ever one set of rules about what "2 h 5 min" means.
 */
fun durationText(context: Context, ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> context.getString(R.string.dur_under_min)
        minutes < 60 -> context.getString(R.string.dur_min, minutes)
        else -> context.getString(R.string.dur_h_min, minutes / 60, minutes % 60)
    }
}

@Composable
fun durationText(ms: Long): String = durationText(LocalContext.current, ms)
