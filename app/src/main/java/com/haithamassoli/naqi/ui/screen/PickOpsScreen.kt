package com.haithamassoli.naqi.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.SmokeReport
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.SelectDot
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Step 1: choose the video and which operations to run. Tuning for those operations lives on the
 * options screen, so nothing here writes anything but [FilterOps.removeMusic]/[FilterOps.censorWomen].
 */
@Composable
fun PickOpsScreen(
    pickedUri: Uri?,
    pickedName: String?,
    ops: FilterOps,
    onPicked: (Uri, String?) -> Unit,
    onOpsChange: (FilterOps) -> Unit,
    onContinue: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var smoke by remember { mutableStateOf<SmokeReport?>(null) }

    LaunchedEffect(Unit) { smoke = withContext(Dispatchers.Default) { ModelSmoke.run(context) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onPicked(uri, queryDisplayName(context, uri))
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = modifier) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space5, bottom = NaqiTokens.space7),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TrustSeal()
            Spacer(Modifier.height(NaqiTokens.space6))
            Wordmark()
            Spacer(Modifier.height(NaqiTokens.space6))

            PickVideoCard(picked = pickedUri != null, fileName = pickedName) { picker.launch(arrayOf("video/*")) }
            Spacer(Modifier.height(NaqiTokens.space5))

            Eyebrow(stringResource(R.string.pick_eyebrow_choose))
            Spacer(Modifier.height(NaqiTokens.space3))
            OperationCard(
                icon = NaqiIcons.MusicOff,
                title = stringResource(R.string.pick_op_music_title),
                desc = stringResource(R.string.pick_op_music_desc),
                selected = ops.removeMusic,
            ) { onOpsChange(ops.copy(removeMusic = !ops.removeMusic)) }
            Spacer(Modifier.height(NaqiTokens.space3))
            OperationCard(
                icon = NaqiIcons.Shield,
                title = stringResource(R.string.pick_op_women_title),
                desc = stringResource(R.string.pick_op_women_desc),
                selected = ops.censorWomen,
            ) { onOpsChange(ops.copy(censorWomen = !ops.censorWomen)) }

            Spacer(Modifier.height(NaqiTokens.space5))
            Button(
                onClick = onContinue,
                // Both are required: a job without a video has nothing to filter, one without an op nothing to do.
                enabled = pickedUri != null && ops.any,
                shape = RoundedCornerShape(NaqiTokens.radiusButton),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) { Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.labelLarge) }

            // ponytail: system per-app language picker (API 33+); in-app switcher needs appcompat, add when pre-33 users complain.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Spacer(Modifier.height(NaqiTokens.space4))
                NaqiCard(
                    Modifier.clickable {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_LOCALE_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.opt_language),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.opt_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // About & licences. Attribution has to be reachable from the app itself, not only from the
            // repository — GPL-3.0 and an AGPL-3.0 model are not obligations a README discharges.
            Spacer(Modifier.height(NaqiTokens.space4))
            NaqiCard(Modifier.clickable(onClick = onAbout)) {
                Text(
                    stringResource(R.string.about_open),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.about_open_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(NaqiTokens.space4))
            ReassuranceLine()

            Spacer(Modifier.height(NaqiTokens.space6))
            DiagnosticsFooter(smoke)
        }
    }
}

@Composable
private fun TrustSeal() {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space2),
        modifier = Modifier
            .clip(RoundedCornerShape(NaqiTokens.radiusPill))
            .background(primary.copy(alpha = 0.08f))
            .border(1.dp, primary.copy(alpha = 0.22f), RoundedCornerShape(NaqiTokens.radiusPill))
            .padding(horizontal = NaqiTokens.space4, vertical = NaqiTokens.space2),
    ) {
        Icon(NaqiIcons.Droplet, contentDescription = null, tint = primary, modifier = Modifier.size(16.dp))
        Text(stringResource(R.string.pick_seal_on_device), style = MaterialTheme.typography.labelMedium, color = primary)
        Text("·", style = MaterialTheme.typography.labelMedium, color = primary.copy(alpha = 0.55f))
        Text(stringResource(R.string.pick_seal_private), style = MaterialTheme.typography.labelMedium, color = primary)
    }
}

@Composable
private fun Wordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // The mark is the bowl of ن holding a droplet — it rhymes with the نـ of the wordmark below,
        // so the two read as one lockup rather than a logo parked above a title.
        Icon(
            painterResource(R.drawable.ic_naqi_mark),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(60.dp),
        )
        Spacer(Modifier.height(NaqiTokens.space3))
        Text(
            stringResource(R.string.pick_wordmark_ar),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        Text(
            stringResource(R.string.pick_wordmark_latin),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NaqiTokens.space3))
        Text(
            stringResource(R.string.pick_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PickVideoCard(picked: Boolean, fileName: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .background(cs.surfaceContainer)
            .border(1.5.dp, if (picked) cs.primary else cs.outlineVariant, RoundedCornerShape(NaqiTokens.radiusCard))
            .clickable(onClick = onClick)
            .padding(NaqiTokens.space5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (picked) NaqiIcons.Check else NaqiIcons.Video,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        Text(
            // A provider may not expose a display name; the video is still picked, so don't look unpicked.
            fileName ?: stringResource(if (picked) R.string.pick_video_selected else R.string.pick_video_none),
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(NaqiTokens.space1))
        Text(
            stringResource(if (picked) R.string.pick_video_change else R.string.pick_video_formats),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun OperationCard(
    icon: ImageVector,
    title: String,
    desc: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainer)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) cs.primary else cs.outlineVariant,
                RoundedCornerShape(NaqiTokens.radiusCard),
            )
            .clickable(onClick = onToggle)
            .padding(NaqiTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(NaqiTokens.radiusButton))
                .background(if (selected) cs.primary.copy(alpha = 0.14f) else cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (selected) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) cs.onPrimaryContainer.copy(alpha = 0.82f) else cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        SelectDot(selected)
    }
}

@Composable
private fun ReassuranceLine() {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space1)) {
        Icon(NaqiIcons.Check, null, tint = cs.primary, modifier = Modifier.size(15.dp))
        Text(
            stringResource(R.string.pick_reassurance),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun DiagnosticsFooter(smoke: SmokeReport?) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusButton))
            .background(cs.surfaceContainerLow)
            .padding(NaqiTokens.space3),
    ) {
        Text(stringResource(R.string.pick_diag_title), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(NaqiTokens.space1))
        if (smoke == null) {
            Text(stringResource(R.string.pick_diag_running), style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        } else {
            Text(
                stringResource(R.string.pick_diag_eps, smoke.providers.joinToString(", ").ifEmpty { "—" }),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface,
            )
            smoke.models.forEach { r ->
                val (mark, color) = when {
                    r.ok -> "✓" to cs.onSurface
                    !r.bundled -> "–" to cs.onSurfaceVariant
                    else -> "✗" to cs.error
                }
                Text(
                    stringResource(
                        R.string.pick_diag_model_line,
                        mark,
                        r.model.assetName.removeSuffix(".onnx"),
                        r.detail,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                )
            }
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
