package com.haithamassoli.naqi.ui.screen

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.work.WorkInfo
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Battery
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.publish.Splitter
import com.haithamassoli.naqi.ui.NaqiBottomAction
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiTopBar
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.FilterWorker
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Queue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Ask to delete the source, via the same confirm dialog the notification action opens. */
    onDeleteOriginal: (Uri, String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val workFlow = remember { JobController.observe(context) }
    val workInfos by workFlow.collectAsState(initial = emptyList())
    val info = JobController.currentWork(workInfos)
    val running = info?.state?.isFinished == false
    val succeeded = info?.state == WorkInfo.State.SUCCEEDED
    val failed = info?.state == WorkInfo.State.FAILED
    val outputName = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_NAME)
    val outputUri = info?.outputData?.getString(FilterWorker.KEY_OUTPUT_URI)
    // Absent for a download: the quarantine is Naqi's own temp, already gone, and never the user's file.
    val sourceUri = info?.outputData?.getString(FilterWorker.KEY_SOURCE_URI)?.takeIf { it.isNotEmpty() }
    // A @StringRes id, so a failure re-localizes if the language changes after the job failed; 0 = absent.
    val outputMessageId = info?.outputData?.getInt(FilterWorker.KEY_OUTPUT_MESSAGE, 0) ?: 0
    val resumable = info?.outputData?.getBoolean(FilterWorker.KEY_RESUMABLE, false) ?: false
    val progress = info?.progress?.getInt(FilterWorker.KEY_PROGRESS, 0) ?: 0
    val stageText = info?.progress?.getString(FilterWorker.KEY_STAGE).orEmpty()
    // Put as a Long by the worker, so it must be read as one — getInt would silently read 0 forever.
    // 0 is also the worker's own "too early to say", and both cases mean the same thing here: show nothing.
    val etaMs = info?.progress?.getLong(FilterWorker.KEY_ETA_MS, 0L) ?: 0L

    // Shared items: the queue the user is waiting on. Empty entirely when nothing was ever shared, so
    // the picker path looks exactly as it did.
    val queue by Queue.items.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Queue.load(context) } }
    val queueId = JobController.queueIdOf(info)
    val runningQueueItem = queue.firstOrNull { it.id == queueId }
    val showSaved = succeeded && queueId == null
    val onCancel: (() -> Unit)? = when {
        queueId == null -> { { JobController.cancel(context) } }
        runningQueueItem != null -> { { JobController.cancelItem(context, runningQueueItem) } }
        else -> null // The queue is still loading; never cancel the whole chain by mistake.
    }

    // Resolved off the main thread: the pre-Q output is a file:// uri that has to be looked up in MediaStore.
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(outputUri) {
        savedUri = outputUri?.takeIf { it.isNotEmpty() }
            ?.let { withContext(Dispatchers.IO) { shareableUri(context, Uri.parse(it)) } }
    }

    // Re-read on resume: "Allow" leaves for a system dialog, and the card must be gone when the user
    // comes back from granting it.
    var showBatteryCard by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        showBatteryCard = !Battery.unrestricted(context) && !Prefs.batteryCardDismissed(context)
        onPauseOrDispose {}
    }

    var library by remember { mutableStateOf(emptyList<LibraryItem>()) }
    // Bumped after a delete, so the list re-reads MediaStore instead of guessing what is left.
    var libraryVersion by remember { mutableIntStateOf(0) }
    // Re-read on every job state change so a fresh save shows up without a manual refresh; the queue
    // size too, because a queued item leaves the queue at the moment its output lands in the library.
    LaunchedEffect(info?.state, outputName, queue.size, libraryVersion) {
        library = withContext(Dispatchers.IO) { loadLibrary(context) }
    }

    var pendingDelete by remember { mutableStateOf<LibraryItem?>(null) }
    var publishing by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    publishing?.let { (uri, name) -> PublishSheet(uri, name, onDismiss = { publishing = null }) }
    val scope = rememberCoroutineScope()
    val systemDelete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) libraryVersion++
    }
    pendingDelete?.let { item ->
        DeleteOutputDialog(
            name = item.name,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                val uri = item.uri ?: return@DeleteOutputDialog
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { deleteOutput(context, uri) } }
                    when (val sender = result.getOrNull()) {
                        null -> if (result.isSuccess) {
                            libraryVersion++
                        } else {
                            Toast.makeText(context, R.string.jobs_delete_failed, Toast.LENGTH_SHORT).show()
                        }
                        else -> systemDelete.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NaqiTopBar(stringResource(R.string.jobs_title), onBack = onNewJob) },
        bottomBar = {
            NaqiBottomAction(
                label = stringResource(R.string.jobs_new_job),
                enabled = true,
                onClick = onNewJob,
            )
        },
        modifier = modifier,
    ) { pad ->
        // Lazy: the library is every file Naqi ever saved, and a plain scrolling Column composed all
        // of them up front.
        LazyColumn(
            Modifier
                .padding(pad)
                .fillMaxSize(),
            contentPadding = PaddingValues(
                start = NaqiTokens.gutter,
                end = NaqiTokens.gutter,
                top = NaqiTokens.space2,
                bottom = NaqiTokens.space5,
            ),
        ) {
            if (showBatteryCard) item(key = "battery") {
                BatteryCard(
                    onAllow = { Battery.request(context) },
                    onLater = { Prefs.dismissBatteryCard(context); showBatteryCard = false },
                )
                Spacer(Modifier.height(NaqiTokens.space5))
            }
            item(key = "status") {
                when {
                    running -> JobProgressCard(stageText, progress, etaMs, onCancel)
                    showSaved -> SavedCard(outputName, savedUri, sourceUri, onDeleteOriginal, context) { uri ->
                        publishing = uri to outputName.orEmpty()
                    }
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
                    // Nothing to report and nothing queued: one muted line, not an empty card that looks
                    // like something went missing.
                    queue.isEmpty() -> Text(
                        stringResource(R.string.jobs_none_running),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = NaqiTokens.space1),
                    )
                }
            }

            if (queue.isNotEmpty()) item(key = "queue") {
                Column {
                    if (running || showSaved || failed) Spacer(Modifier.height(NaqiTokens.space5))
                    QueueCard(queue)
                }
            }

            item(key = "library-header") {
                Column {
                    Spacer(Modifier.height(NaqiTokens.space6))
                    SectionHeader(stringResource(R.string.jobs_library))
                    if (library.isEmpty()) {
                        Text(
                            stringResource(R.string.jobs_library_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = NaqiTokens.space1),
                        )
                    }
                }
            }
            // One segment per row rather than one card around all of them: a lazy list cannot wrap its
            // items in a single parent, so the card's outer corners go on the first and last segment.
            itemsIndexed(library, key = { _, item -> item.uri?.toString() ?: item.name }) { index, item ->
                val outer = 24.dp
                val inner = 6.dp
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) outer else inner,
                    topEnd = if (index == 0) outer else inner,
                    bottomStart = if (index == library.lastIndex) outer else inner,
                    bottomEnd = if (index == library.lastIndex) outer else inner,
                )
                LibraryRow(
                    item,
                    onOpen = { item.uri?.let { view(context, it) } },
                    onDelete = { pendingDelete = item },
                    onPublish = { item.uri?.let { publishing = it to item.name } },
                    modifier = Modifier
                        .padding(top = if (index == 0) 0.dp else 2.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
                )
            }
        }
    }
}

/** Same shape as the delete-original confirm; this one is about Naqi's own output. */
@Composable
private fun DeleteOutputDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.jobs_delete_title)) },
        text = { Text(stringResource(R.string.jobs_delete_body, name), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_keep)) } },
    )
}

/**
 * Deletes a file Naqi published. Our own MediaStore rows need no permission — until a reinstall drops
 * that ownership, and then the system has to ask. Returns that system prompt, or null when already done.
 */
private fun deleteOutput(context: Context, uri: Uri): IntentSender? = try {
    context.contentResolver.delete(uri, null, null)
    null
} catch (e: SecurityException) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
    } else {
        (e as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender ?: throw e
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun JobProgressCard(stage: String, progress: Int, etaMs: Long, onCancel: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val starting = stringResource(R.string.jobs_stage_starting)
    NaqiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage.ifEmpty { starting },
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.jobs_progress_percent, progress),
                style = MaterialTheme.typography.titleMedium,
                color = cs.primary,
            )
        }
        Spacer(Modifier.height(NaqiTokens.space3))
        LinearWavyProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            color = cs.primary,
            trackColor = cs.surfaceContainerHighest,
        )
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
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

@Composable
private fun SavedCard(
    name: String?,
    uri: Uri?,
    sourceUri: String?,
    onDeleteOriginal: (Uri, String?) -> Unit,
    context: Context,
    onPublish: (Uri) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    NaqiCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(NaqiTokens.radiusButton))
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(NaqiIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(NaqiTokens.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.jobs_saved_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurface,
                )
                Text(
                    // The audio-only shape publishes into Music/Naqi, so the one path label this app
                    // shows has to follow it — naming the wrong folder is worse than naming none.
                    stringResource(savedPathRes(name), name.orEmpty()),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // No uri (missing key, or a pre-Q file the scanner hasn't indexed yet) means nothing safe to hand out.
        if (uri != null) {
            Spacer(Modifier.height(NaqiTokens.space4))
            Row {
                Button(
                    onClick = { view(context, uri) },
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_open)) }
                Spacer(Modifier.width(NaqiTokens.space3))
                OutlinedButton(
                    onClick = { share(context, uri) },
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.action_share)) }
            }
            // Audio has nothing to post to a video platform.
            if (!isAudioOutput(name)) {
                Spacer(Modifier.height(NaqiTokens.space2))
                OutlinedButton(
                    onClick = { onPublish(uri) },
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.action_prepare_publish)) }
            }
        }
        // Offered here as well as on the notification, which is gone the moment it is swiped. Text, not
        // a third button in that row: it is the one destructive thing on the screen and must not read
        // as a peer of Open and Share. It only ASKS — the confirm dialog is the same one the
        // notification action opens, and the deleting happens there.
        if (sourceUri != null) {
            TextButton(
                onClick = { onDeleteOriginal(sourceUri.toUri(), name) },
                colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
                modifier = Modifier.align(Alignment.Start),
            ) { Text(stringResource(R.string.action_delete_original)) }
        }
    }
}

@Composable
private fun LibraryRow(
    item: LibraryItem,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onPublish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val uri = item.uri
    Row(
        modifier
            .fillMaxWidth()
            // The tap is the only way to open now, so it has to say so out loud to a screen reader.
            .clickable(enabled = uri != null, onClickLabel = stringResource(R.string.action_open), onClick = onOpen)
            // An IconButton is 48dp against a text button's 40dp; the shorter vertical padding keeps
            // the row exactly as tall as it was, and the smaller end inset absorbs the button's own.
            .padding(
                start = NaqiTokens.space4,
                end = NaqiTokens.space2,
                top = NaqiTokens.space2,
                bottom = NaqiTokens.space2,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(NaqiTokens.radiusButton))
                .background(cs.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (isAudioOutput(item.name)) NaqiIcons.MusicOff else NaqiIcons.Video,
                null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(NaqiTokens.space3))
        Column(Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatSize(item.bytes), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        // Tapping the row already opens it, so a trailing "Open" only repeated the row. Share and Delete
        // sit behind one overflow: Delete is destructive and must not be a one-tap peer of Share.
        // [loadLibrary] builds this uri with ContentUris, so it is content:// and safe to hand out as is.
        if (uri != null) {
            Spacer(Modifier.width(NaqiTokens.space2))
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(NaqiIcons.More, stringResource(R.string.action_more), tint = cs.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_share)) },
                        onClick = { menu = false; share(context, uri) },
                    )
                    if (!isAudioOutput(item.name)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_prepare_publish)) },
                            onClick = { menu = false; onPublish() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = cs.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

private data class LibraryItem(val name: String, val bytes: Long, val uri: Uri?)

/**
 * The extension, not the mime: this is asked about a *name* the job reported, before there is a uri to
 * ask MediaStore about. `.m4a` is what the audio-only shape writes and the only output that is not an
 * mp4 — [FilterWorker]'s `outputName(uri, ext = "m4a")` is the one place it comes from.
 */
private fun isAudioOutput(name: String?): Boolean = name?.endsWith(".m4a", ignoreCase = true) == true

private fun savedPathRes(name: String?) =
    if (isAudioOutput(name)) R.string.jobs_saved_path_audio else R.string.jobs_saved_path

/** Newest first, straight out of MediaStore — our own contributions need no permission to read back. */
private fun loadLibrary(context: Context): List<LibraryItem> = runCatching {
    // Files, not Video: the audio-only shape publishes into Music/Naqi, which a Video-collection query
    // cannot see — the app would have made a file it then refuses to list. One query over both
    // directories keeps them in one date order; scoped storage still limits the rows to this app's own,
    // which is exactly Naqi's output. MEDIA_TYPE pins it to real media so no stray row can appear.
    val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    context.contentResolver.query(
        collection,
        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
        "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) " +
            "AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN " +
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}) " +
            // Publish-ready parts are copies of a video already listed, not new output.
            "AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
        arrayOf(
            "${Environment.DIRECTORY_MOVIES}/Naqi/%",
            "${Environment.DIRECTORY_MUSIC}/Naqi/%",
            "${Environment.DIRECTORY_MOVIES}/${Splitter.PARTS_DIR}/%",
        ),
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(LibraryItem(c.getString(1), c.getLong(2), ContentUris.withAppendedId(collection, c.getLong(0))))
            }
        }
    }.orEmpty()
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

/**
 * The output's own mime, falling back to [MIME_MP4]. Asked of the resolver rather than threaded down
 * from the job: the audio-only shape publishes an `.m4a`, and handing that to a video player is how a
 * finished job looks broken. MediaStore already knows which it is.
 */
private fun mimeOf(context: Context, uri: Uri): String = context.contentResolver.getType(uri) ?: MIME_MP4

// runCatching: a device with no video player / no share target must not take the app down.
internal fun view(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(context, uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, R.string.jobs_open_failed, Toast.LENGTH_SHORT).show() }
}

/**
 * [share] for a uri that came back from a job rather than from MediaStore, so it could still be the
 * pre-Q `file://` shape. [shareableUri] is a resolver query in that case, which is not something a
 * click handler does on the main thread. Null means the scanner never indexed it and there is nothing
 * safe to hand out — silence beats crashing whichever app it would have gone to.
 */
internal suspend fun shareOutput(context: Context, uri: Uri) {
    val shareable = withContext(Dispatchers.IO) { shareableUri(context, uri) } ?: return
    share(context, shareable)
}

private fun share(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType(mimeOf(context, uri))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.jobs_share_chooser_title)))
    }
}

/**
 * Whole resource strings rather than a number glued to a unit — same rule [durationText] follows, and
 * for the same reason: Arabic wants its own digits AND its own unit word ("غيغابايت"), which
 * `"%.1f GB".format(...)` cannot produce.
 */
@Composable
private fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) stringResource(R.string.jobs_size_gb, bytes / 1e9f)
    else stringResource(R.string.jobs_size_mb, bytes / 1e6f)

/** Why the battery exemption matters, asked where the user watches the queue — see [Battery]. */
@Composable
private fun BatteryCard(onAllow: () -> Unit, onLater: () -> Unit) {
    NaqiCard {
        Text(
            stringResource(R.string.battery_card_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(NaqiTokens.space1))
        Text(
            stringResource(R.string.battery_card_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(NaqiTokens.space3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onLater) { Text(stringResource(R.string.battery_card_later)) }
            Spacer(Modifier.width(NaqiTokens.space2))
            Button(onClick = onAllow, shape = RoundedCornerShape(NaqiTokens.radiusButton)) {
                Text(stringResource(R.string.battery_card_allow))
            }
        }
    }
}
