package com.haithamassoli.naqi.ui

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
 * Every screen wears the same bar: a title, an optional back arrow, and room for actions. Expressive top app bar.
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
 * M3 Expressive styled action button with organic pill/rounded shape.
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
            shape = NaqiTokens.shapeButton,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

/**
 * Section label. Sentence case at title size with M3 Expressive typography and alignment.
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** The selection indicator for single-choice rows with Expressive spring animation. */
@Composable
fun SelectDot(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.85f,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "selectDotScale",
    )
    val bgColor by animateColorAsState(
        targetValue = if (selected) cs.primary else Color.Transparent,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "selectDotBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) Color.Transparent else cs.outline,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "selectDotBorder",
    )

    Box(
        Modifier
            .scale(scale)
            .size(24.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.5.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(NaqiIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
    }
}

/**
 * The standard bordered block using M3 Expressive card shapes and surface containers.
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
            .clip(NaqiTokens.shapeCard)
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, NaqiTokens.shapeCard)
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
 * M3 Expressive Toggle Tile with animated spring feedback for selection states.
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
    val iconBgColor by animateColorAsState(
        targetValue = if (checked) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHighest,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "toggleIconBg",
    )
    val iconTintColor by animateColorAsState(
        targetValue = if (checked) cs.primary else cs.onSurfaceVariant,
        animationSpec = NaqiTokens.expressiveSpring(),
        label = "toggleIconTint",
    )

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
                    .size(42.dp)
                    .clip(NaqiTokens.shapeButton)
                    .background(iconBgColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = iconTintColor,
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
