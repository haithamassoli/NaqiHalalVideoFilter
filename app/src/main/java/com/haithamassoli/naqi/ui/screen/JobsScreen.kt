package com.haithamassoli.naqi.ui.screen

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.FilterWorker
import com.haithamassoli.naqi.work.JobController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MIME_MP4 = "video/mp4"

/**
 * Step 3: the running/finished job plus everything Naqi has already saved. Job state is read straight
 * from WorkManager, so the screen survives leaving and re-entering the app while a job runs.
 */
@Composable
fun JobsScreen(
    onNewJob: () -> Unit,
    /**
     * Re-enqueue the same job (`long-film-plan.md` Phase 2). Null when the app has no picked video to
     * resume with — after process death the saved state can be gone even though the segments are not.
     */
    onResume: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val workFlow = remember { JobController.observe(context) }
    val workInfos by workFlow.collectAsState(initial = emptyList())
    val info = workInfos.firstOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val succeeded = info?.state == WorkInfo.State.SUCCEEDED
    val failed = info?.state == WorkInfo.State.FAILED
    val outputName = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_NAME)
    val outputUri = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_URI)
    // A @StringRes id, so a failure re-localizes if the language changes after the job failed; 0 = absent.
    val outputMessageId = info?.outputData?.getInt(FilterWorker.KEY_OUTPUT_MESSAGE, 0) ?: 0
    val resumable = info?.outputData?.getBoolean(FilterWorker.KEY_RESUMABLE, false) ?: false
    val progress = info?.progress?.getInt(FilterWorker.KEY_PROGRESS, 0) ?: 0
    val stageText = info?.progress?.getString(FilterWorker.KEY_STAGE).orEmpty()
    // Put as a Long by the worker, so it must be read as one — getInt would silently read 0 forever.
    // 0 is also the worker's own "too early to say", and both cases mean the same thing here: show nothing.
    val etaMs = info?.progress?.getLong(FilterWorker.KEY_ETA_MS, 0L) ?: 0L

    // Resolved off the main thread: the pre-Q output is a file:// uri that has to be looked up in MediaStore.
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(outputUri) {
        savedUri = outputUri?.takeIf { it.isNotEmpty() }
            ?.let { withContext(Dispatchers.IO) { shareableUri(context, Uri.parse(it)) } }
    }

    var library by remember { mutableStateOf(emptyList<LibraryItem>()) }
    // Re-read on every job state change so a fresh save shows up without a manual refresh.
    LaunchedEffect(info?.state, outputName) {
        library = withContext(Dispatchers.IO) { loadLibrary(context) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = modifier) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space4, bottom = NaqiTokens.space7),
        ) {
            TextButton(onClick = onNewJob) { Text(stringResource(R.string.jobs_new_job)) }
            Spacer(Modifier.height(NaqiTokens.space2))

            when {
                running -> JobProgressCard(stageText, progress, etaMs) { JobController.cancel(context) }
                succeeded -> SavedCard(outputName, savedUri, context)
                failed -> NaqiCard {
                    Text(
                        stringResource(if (outputMessageId != 0) outputMessageId else R.string.err_generic),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    // A long job that died past its first segment kept every finished segment on disk.
                    // Resuming re-runs only what is missing — the whole point of Phase 2.
                    if (resumable && onResume != null) {
                        Spacer(Modifier.height(NaqiTokens.space2))
                        Text(
                            stringResource(R.string.jobs_resume_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(NaqiTokens.space3))
                        Button(
                            onClick = onResume,
                            shape = RoundedCornerShape(NaqiTokens.radiusButton),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.action_resume)) }
                    }
                }
                else -> NaqiCard {
                    Text(
                        stringResource(R.string.jobs_none_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(NaqiTokens.space6))
            Eyebrow(stringResource(R.string.jobs_library))
            Spacer(Modifier.height(NaqiTokens.space3))
            if (library.isEmpty()) {
                Text(
                    stringResource(R.string.jobs_library_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                library.forEach { item ->
                    LibraryRow(item) { item.uri?.let { view(context, it) } }
                    Spacer(Modifier.height(NaqiTokens.space2))
                }
            }
        }
    }
}

@Composable
private fun JobProgressCard(stage: String, progress: Int, etaMs: Long, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val starting = stringResource(R.string.jobs_stage_starting)
    NaqiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage.ifEmpty { starting },
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.jobs_progress_percent, progress),
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
            )
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The worker publishes 0 until it has enough throughput to mean the number; show nothing then.
            if (etaMs > 0) {
                Text(
                    stringResource(R.string.jobs_eta_remaining, durationText(etaMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun SavedCard(name: String?, uri: Uri?, context: Context) {
    val cs = MaterialTheme.colorScheme
    NaqiCard {
        Text(stringResource(R.string.jobs_saved_label), style = MaterialTheme.typography.labelMedium, color = cs.primary)
        Spacer(Modifier.height(NaqiTokens.space1))
        Text(
            stringResource(R.string.jobs_saved_path, name.orEmpty()),
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
        )
        // No uri (missing key, or a pre-Q file the scanner hasn't indexed yet) means nothing safe to hand out.
        if (uri != null) {
            Spacer(Modifier.height(NaqiTokens.space3))
            Row {
                Button(
                    onClick = { view(context, uri) },
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.weight(1f),
                ) { Text("Open") }
                Spacer(Modifier.width(NaqiTokens.space3))
                OutlinedButton(
                    onClick = { share(context, uri) },
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }
    }
}

@Composable
private fun LibraryRow(item: LibraryItem, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    NaqiCard(Modifier.clickable(enabled = item.uri != null, onClick = onOpen)) {
        Text(
            item.name,
            style = MaterialTheme.typography.titleSmall,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(formatSize(item.bytes), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}

private data class LibraryItem(val name: String, val bytes: Long, val uri: Uri?)

/** Newest first. Reads MediaStore on API 29+ (own contributions need no permission), the public dir below. */
private fun loadLibrary(context: Context): List<LibraryItem> = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("${Environment.DIRECTORY_MOVIES}/Naqi/%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(LibraryItem(c.getString(1), c.getLong(2), ContentUris.withAppendedId(collection, c.getLong(0))))
                }
            }
        }.orEmpty()
    } else {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Naqi")
            .listFiles().orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { LibraryItem(it.name, it.length(), shareableUri(context, Uri.fromFile(it))) }
    }
}.getOrDefault(emptyList())

/**
 * A `file://` uri kills the app we hand it to (StrictMode's FileUriExposedException since API 24), so the
 * pre-Q output is swapped for its MediaStore row; null while the media scanner hasn't caught up.
 */
private fun shareableUri(context: Context, uri: Uri): Uri? {
    if (uri.scheme != "file") return uri
    val path = uri.path ?: return null
    return context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Video.Media._ID),
        "${MediaStore.Video.Media.DATA} = ?",
        arrayOf(path),
        null,
    )?.use { c ->
        if (c.moveToFirst()) {
            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
        } else {
            null
        }
    }
}

// runCatching: a device with no video player / no share target must not take the app down.
private fun view(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, MIME_MP4)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
}

private fun share(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(MIME_MP4)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share video")) }
}

private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
