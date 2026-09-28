@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.haithamassoli.naqi.ui.screen

import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.media.containerDurationMs
import com.haithamassoli.naqi.publish.PublishPreset
import com.haithamassoli.naqi.publish.Splitter
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Prepare to post": pick a [PublishPreset], and the video is either shared as-is (it fits) or cut into
 * parts that do. Everything platform-specific comes from the preset — this screen has no platform names.
 *
 * Closing the sheet mid-split cancels it, and [Splitter.split] then removes the parts it already wrote.
 */
@Composable
fun PublishSheet(uri: Uri, name: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseName = name.substringBeforeLast('.')

    // Installed apps only, most used first, ties in ALL's order (the sort is stable). Fixed while the
    // sheet is open so a chip never moves under the user's finger, and the first one is what the sheet opens on.
    val order = remember {
        (PublishPreset.ALL.filter { it.isInstalled(context) }.map { it.id } + PublishPreset.CUSTOM)
            .sortedByDescending { Prefs.presetUses(context, it) }
    }
    var selected by rememberSaveable { mutableStateOf(order.first()) }
    var customSeconds by rememberSaveable { mutableIntStateOf(60) }
    val preset = PublishPreset.ALL.firstOrNull { it.id == selected } ?: PublishPreset.custom(customSeconds)

    // Once per option per sheet: sharing eight parts one by one is still one use of WhatsApp.
    val counted = remember { mutableSetOf<String>() }
    fun share(uris: List<Uri>) {
        if (counted.add(selected)) Prefs.countPresetUse(context, selected)
        Splitter.share(context, preset, uris)
    }

    var durationMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(uri) { durationMs = withContext(Dispatchers.IO) { context.containerDurationMs(uri) } }

    var parts by remember { mutableStateOf(emptyList<Splitter.Part>()) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(preset.id) {
        parts = withContext(Dispatchers.IO) { Splitter.existingParts(context, baseName, preset) }
    }

    val needsSplit = durationMs?.let(preset::requiresSplitting) ?: false

    fun prepare() {
        working = true
        scope.launch {
            try {
                parts = Splitter.split(context, uri, baseName, preset)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                Toast.makeText(context, R.string.publish_failed, Toast.LENGTH_SHORT).show()
            } finally {
                working = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(bottom = NaqiTokens.space6),
        ) {
            Text(stringResource(R.string.action_prepare_publish), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(NaqiTokens.space4))

            // Chips, not a segmented row: segments share the width equally, which clipped "WhatsApp Status".
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2),
            ) {
                order.forEach { id ->
                    val label = PublishPreset.ALL.firstOrNull { it.id == id }?.labelRes ?: R.string.preset_custom
                    FilterChip(
                        selected = selected == id,
                        onClick = { selected = id },
                        enabled = !working,
                        label = { Text(stringResource(label), maxLines = 1) },
                    )
                }
            }

            if (selected == PublishPreset.CUSTOM) {
                Spacer(Modifier.height(NaqiTokens.space3))
                Text(
                    stringResource(R.string.publish_custom_max, DateUtils.formatElapsedTime(customSeconds.toLong())),
                    style = MaterialTheme.typography.bodyMedium,
                )
                // 15 s to 10 min in 15 s steps.
                Slider(
                    value = customSeconds.toFloat(),
                    onValueChange = { customSeconds = it.toInt() },
                    valueRange = 15f..600f,
                    steps = 38,
                    enabled = !working,
                )
            }

            Spacer(Modifier.height(NaqiTokens.space3))
            val limit = preset.maxSegmentMs
            Text(
                when {
                    parts.isNotEmpty() -> stringResource(R.string.publish_parts_saved)
                    needsSplit && limit != null ->
                        stringResource(R.string.publish_needs_split, DateUtils.formatElapsedTime(limit / 1000))
                    else -> stringResource(R.string.publish_as_is)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (parts.isNotEmpty()) {
                Spacer(Modifier.height(NaqiTokens.space3))
                NaqiCard(contentPadding = 0.dp) {
                    parts.forEachIndexed { i, part ->
                        if (i > 0) NaqiRowDivider()
                        Row(
                            Modifier.padding(start = NaqiTokens.space4, end = NaqiTokens.space2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (part.durationMs > 0) {
                                    stringResource(
                                        R.string.publish_part_duration, i + 1,
                                        DateUtils.formatElapsedTime(part.durationMs / 1000),
                                    )
                                } else {
                                    stringResource(R.string.publish_part, i + 1)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { share(listOf(part.uri)) }) {
                                Text(stringResource(R.string.action_share))
                            }
                        }
                    }
                }
            }

            if (working) {
                Spacer(Modifier.height(NaqiTokens.space4))
                Text(stringResource(R.string.publish_preparing), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(NaqiTokens.space2))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(NaqiTokens.space5))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (parts.isNotEmpty()) {
                    // No confirm: parts are copies, remade in seconds; the filtered video is untouched.
                    TextButton(
                        onClick = {
                            val gone = parts.map { it.uri }
                            scope.launch {
                                parts = withContext(Dispatchers.IO) {
                                    Splitter.delete(context, gone)
                                    Splitter.existingParts(context, baseName, preset) // whatever could not go
                                }
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.publish_delete_parts)) }
                } else {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
                Spacer(Modifier.weight(1f))
                val primary: (() -> Unit)? = when {
                    parts.isNotEmpty() ->
                        if (preset.supportsMultipleSegments) { { share(parts.map { it.uri }) } }
                        else null // one post per part: the rows' own Share buttons
                    needsSplit -> ::prepare
                    else -> { { share(listOf(uri)) } }
                }
                if (primary != null) {
                    Button(
                        onClick = primary,
                        // Duration unknown yet = cannot tell split from pass-through.
                        enabled = !working && durationMs != null,
                        shape = RoundedCornerShape(NaqiTokens.radiusButton),
                        modifier = Modifier.height(52.dp),
                    ) {
                        Text(
                            stringResource(
                                when {
                                    parts.isNotEmpty() -> R.string.publish_share_all
                                    needsSplit -> R.string.publish_split
                                    else -> R.string.action_share
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}
