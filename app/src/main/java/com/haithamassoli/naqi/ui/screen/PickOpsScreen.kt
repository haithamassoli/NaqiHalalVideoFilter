package com.haithamassoli.naqi.ui.screen

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.SmokeReport
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.FilterWorker
import com.haithamassoli.naqi.work.JobController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PickOpsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var ops by remember { mutableStateOf(FilterOps()) }
    var smoke by remember { mutableStateOf<SmokeReport?>(null) }

    LaunchedEffect(Unit) { smoke = withContext(Dispatchers.Default) { ModelSmoke.run(context) } }

    val workFlow = remember { JobController.observe(context) }
    val workInfos by workFlow.collectAsState(initial = emptyList())
    val info = workInfos.firstOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val succeeded = info?.state == WorkInfo.State.SUCCEEDED
    val failed = info?.state == WorkInfo.State.FAILED
    val outputName = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_NAME)
    val outputMessage = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_MESSAGE)
    val progress = info?.progress?.getInt(FilterWorker.KEY_PROGRESS, 0) ?: 0
    val stageText = info?.progress?.getString(FilterWorker.KEY_STAGE).orEmpty()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            pickedUri = uri
            pickedName = queryDisplayName(context, uri)
        }
    }

    fun startJob() {
        JobController.start(context, ops, pickedUri?.toString())
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startJob() // start whether granted or not; without it the job runs but its notification is hidden
    }
    // Pre-Q, saving into public Movies/Naqi needs WRITE_EXTERNAL_STORAGE; a Worker can't request it, so ask here.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startJob() // if denied, publish() fails fast with a surfaced message rather than saving nowhere
    }

    fun onContinue() {
        // Disjoint by API level: storage is pre-Q only, notifications are API 33+, so at most one is asked.
        val needsStorage = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        when {
            needsStorage -> storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            needsNotif -> notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> startJob()
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

            PickVideoCard(pickedName) { picker.launch(arrayOf("video/*")) }
            Spacer(Modifier.height(NaqiTokens.space5))

            Eyebrow("CHOOSE WHAT TO FILTER")
            Spacer(Modifier.height(NaqiTokens.space3))
            OperationCard(
                icon = NaqiIcons.MusicOff,
                title = "Remove music",
                desc = "Strip the soundtrack, keep dialogue.",
                selected = ops.removeMusic,
            ) { ops = ops.copy(removeMusic = !ops.removeMusic) }
            Spacer(Modifier.height(NaqiTokens.space3))
            OperationCard(
                icon = NaqiIcons.Shield,
                title = "Censor women",
                desc = "Blur female faces and flagged scenes.",
                selected = ops.censorWomen,
            ) { ops = ops.copy(censorWomen = !ops.censorWomen) }

            Spacer(Modifier.height(NaqiTokens.space5))
            if (running) {
                JobProgressCard(stageText, progress) { JobController.cancel(context) }
            } else {
                Button(
                    onClick = ::onContinue,
                    enabled = ops.any,
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) { Text("Continue", style = MaterialTheme.typography.labelLarge) }
            }

            Spacer(Modifier.height(NaqiTokens.space4))
            ReassuranceLine(
                savedName = if (succeeded) outputName else null,
                errorMessage = if (failed) outputMessage else null,
            )

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
        Text("On-device", style = MaterialTheme.typography.labelMedium, color = primary)
        Text("·", style = MaterialTheme.typography.labelMedium, color = primary.copy(alpha = 0.55f))
        Text("Offline", style = MaterialTheme.typography.labelMedium, color = primary)
    }
}

@Composable
private fun Wordmark() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "نقي",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        Text(
            "Naqi",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NaqiTokens.space3))
        Text(
            "Filter video on your device.\nNothing leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PickVideoCard(fileName: String?, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val picked = fileName != null
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
            fileName ?: "Pick a video",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(NaqiTokens.space1))
        Text(
            if (picked) "Tap to change" else "MP4 · MKV · WebM",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = NaqiTokens.space1),
    )
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
private fun SelectDot(selected: Boolean) {
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

@Composable
private fun JobProgressCard(stage: String, progress: Int, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(NaqiTokens.radiusCard))
            .padding(NaqiTokens.space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage.ifEmpty { "Starting…" },
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text("$progress%", style = MaterialTheme.typography.labelMedium, color = cs.primary)
        }
        Spacer(Modifier.height(NaqiTokens.space3))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(NaqiTokens.radiusPill)),
            color = cs.primary,
            trackColor = cs.surfaceContainerHighest,
        )
        Spacer(Modifier.height(NaqiTokens.space2))
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
    }
}

@Composable
private fun ReassuranceLine(savedName: String?, errorMessage: String?) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NaqiTokens.space1)) {
            Icon(NaqiIcons.Check, null, tint = cs.primary, modifier = Modifier.size(15.dp))
            Text("Original file is never changed.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (savedName != null) {
            Spacer(Modifier.height(NaqiTokens.space1))
            Text(
                "Saved to Movies/Naqi/$savedName",
                style = MaterialTheme.typography.bodySmall,
                color = cs.primary,
                textAlign = TextAlign.Center,
            )
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(NaqiTokens.space1))
            Text(
                errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
                textAlign = TextAlign.Center,
            )
        }
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
        Text("DEVICE RUNTIME", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(NaqiTokens.space1))
        if (smoke == null) {
            Text("Running model smoke…", style = MaterialTheme.typography.bodySmall, color = cs.onSurface)
        } else {
            Text(
                "EPs: ${smoke.providers.joinToString(", ").ifEmpty { "—" }}",
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
                    "$mark ${r.model.assetName.removeSuffix(".onnx")} · ${r.detail}",
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
