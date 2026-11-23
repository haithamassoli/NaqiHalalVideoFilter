package com.haithamassoli.naqi.desktop.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Ported from apps/android .../ui/Components.kt: the pieces more than one screen uses.

/**
 * One screen: Android's top bar, a scrolling body held to a phone-like width in the middle of the window,
 * and an optional pinned bottom action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaqiScreen(
    title: String,
    onBack: (() -> Unit)? = null,
    titleIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalStrings.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2)) {
                        titleIcon?.let {
                            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(NaqiIcons.ArrowBack, contentDescription = s.actionBack) }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { bottom?.let { Centered { it() } } },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = NaqiTokens.maxContentWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = NaqiTokens.gutter)
                    .padding(top = NaqiTokens.space2, bottom = NaqiTokens.space5),
                content = content,
            )
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = NaqiTokens.maxContentWidth)) { content() }
    }
}

/** The screen's primary action, pinned to the bottom so it is never scrolled away. */
@Composable
fun NaqiBottomAction(label: String, enabled: Boolean, onClick: () -> Unit, above: @Composable (ColumnScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = NaqiTokens.gutter, vertical = NaqiTokens.space3)) {
        above?.let {
            it()
            Spacer(Modifier.height(NaqiTokens.space2))
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = NaqiTokens.shapeButton,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = NaqiTokens.space1, bottom = NaqiTokens.space2),
    )
}

@Composable
fun SelectDot(selected: Boolean) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (selected) 1f else 0.85f, NaqiTokens.expressiveSpring(), label = "dotScale")
    val bg by animateColorAsState(if (selected) cs.primary else Color.Transparent, NaqiTokens.expressiveSpring(), label = "dotBg")
    val border by animateColorAsState(if (selected) Color.Transparent else cs.outline, NaqiTokens.expressiveSpring(), label = "dotBorder")
    Box(
        Modifier.scale(scale).size(24.dp).clip(CircleShape).background(bg).border(1.5.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(NaqiIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(15.dp))
    }
}

@Composable
fun NaqiCard(modifier: Modifier = Modifier, contentPadding: Dp = NaqiTokens.space4, content: @Composable ColumnScope.() -> Unit) {
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

@Composable
fun NaqiRowDivider() {
    HorizontalDivider(Modifier.padding(horizontal = NaqiTokens.space4), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
}

@Composable
fun ToggleTile(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, desc: String? = null, icon: ImageVector? = null) {
    val cs = MaterialTheme.colorScheme
    val iconBg by animateColorAsState(
        if (checked) cs.primary.copy(alpha = 0.16f) else cs.surfaceContainerHighest, NaqiTokens.expressiveSpring(), label = "tileBg",
    )
    val iconTint by animateColorAsState(if (checked) cs.primary else cs.onSurfaceVariant, NaqiTokens.expressiveSpring(), label = "tileTint")
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(42.dp).clip(NaqiTokens.shapeButton).background(iconBg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(NaqiTokens.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Icon + one line of small print: the reassurance lines. */
@Composable
fun NoteLine(icon: ImageVector, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(NaqiTokens.space1))
        Text(text, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}
