package com.haithamassoli.naqi.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ui.theme.NaqiTokens

// The handful of pieces that appear on more than one screen. Anything used once stays private to its screen.

/** Small all-caps section label. */
@Composable
fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NaqiTokens.space1),
    )
}

/** The selection indicator shared by the op cards (pick screen) and the keep-stems options (options screen). */
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

/** The standard bordered block used for every option group and job/library panel. */
@Composable
fun NaqiCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(NaqiTokens.radiusCard))
            .padding(NaqiTokens.space4),
        content = content,
    )
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
