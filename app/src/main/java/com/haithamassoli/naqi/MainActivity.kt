package com.haithamassoli.naqi

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.media.displayName
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.spike.SegmentConcatSpike
import com.haithamassoli.naqi.ui.NaqiApp
import com.haithamassoli.naqi.ui.screen.ShareSheet
import com.haithamassoli.naqi.ui.screen.Shared
import com.haithamassoli.naqi.ui.theme.NaqiTheme
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.JobNotifications
import com.haithamassoli.naqi.work.Queue
import java.io.File

class MainActivity : ComponentActivity() {

    /** Set when the "Delete original" notification action opened us; drives the confirm dialog. */
    private var deleteTarget by mutableStateOf<Pair<Uri, String>?>(null)

    /** Set when something was shared into Naqi; drives the share sheet. */
    private var shared by mutableStateOf<Shared?>(null)

    // API 30+ fallback: the system asks the user itself, the only path that works for media we don't own.
    private val systemDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            toast(if (result.resultCode == RESULT_OK) R.string.dlg_original_deleted else R.string.dlg_original_kept)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) maybeAutorun()
        deleteTarget = deleteTargetOf(intent)
        shared = sharedOf(intent)
        // Weekly yt-dlp check (PRD M4.4). Off the main thread, failure-tolerant, and no-op six days out
        // of seven — sites change how they serve video far faster than the app ships.
        lifecycleScope.launch { Downloader.updateIfDue(this@MainActivity) }
        setContent {
            NaqiTheme {
                NaqiApp(modifier = Modifier.fillMaxSize())
                deleteTarget?.let { (uri, name) ->
                    ConfirmDeleteDialog(
                        name = name,
                        onDismiss = { deleteTarget = null },
                        onConfirm = { deleteTarget = null; deleteOriginal(uri) },
                    )
                }
                shared?.let {
                    ShareSheet(
                        shared = it,
                        onDismiss = { shared = null },
                        onQueued = { shared = null },
                    )
                }
            }
        }
    }

    /** The notification action uses CLEAR_TOP, so a running instance is reused rather than recreated. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Default launchMode means a redelivered intent lands here, not in onCreate — the debug
        // cancel hook has to be honoured on both paths or `am start --ez autorun_cancel` is a no-op.
        if (BuildConfig.DEBUG) maybeAutorun()
        deleteTargetOf(intent)?.let { deleteTarget = it }
        sharedOf(intent)?.let { shared = it }
    }

    /**
     * Parse an `ACTION_SEND`. Two shapes, per the PRD's flows A and B.
     *
     * **Link.** The shared text is rarely a bare URL — apps wrap it in "Check this out: <url> via …" —
     * so the first http(s) URL is scraped out rather than the whole extra being trusted. No match is a
     * toast and nothing else; so is a URL already in the queue, because sharing twice must not queue
     * twice.
     *
     * **File.** A share grant dies with the receiving task and cannot be persisted, so the read
     * permission is re-granted to ourselves immediately — that survives until reboot, which is long
     * enough for a queued job to reach the front. A reboot before then is the case the worker reports as
     * "Re-share the file".
     */
    private fun sharedOf(intent: Intent): Shared? {
        if (intent.action != Intent.ACTION_SEND) return null
        // Consumed, so a configuration change or a redelivered intent cannot re-open the sheet.
        val type = intent.type.orEmpty()

        if (type.startsWith("video/")) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: return null
            intent.removeExtra(Intent.EXTRA_STREAM)
            runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return Shared.LocalFile(uri, displayNameOf(uri))
        }

        if (!type.startsWith("text/")) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        intent.removeExtra(Intent.EXTRA_TEXT)
        val url = URL_IN_TEXT.find(text)?.value
        if (url == null) {
            toast(R.string.share_no_url)
            return null
        }
        // Two sources of truth deliberately: queue.json is what the user sees, and the WorkManager tag
        // covers an item enqueued by the debug intent, which never touches the queue.
        if (Queue.isActive(this, url) || JobController.isQueued(this, url)) {
            toast(R.string.share_already_queued)
            return null
        }
        return Shared.Link(url)
    }

    private fun displayNameOf(uri: Uri): String? = displayName(uri) ?: uri.lastPathSegment

    private fun deleteTargetOf(intent: Intent): Pair<Uri, String>? {
        if (intent.action != JobNotifications.ACTION_CONFIRM_DELETE) return null
        val uri = intent.getStringExtra(JobNotifications.EXTRA_DELETE_ORIGINAL)?.takeIf { it.isNotBlank() } ?: return null
        val name = intent.getStringExtra(JobNotifications.EXTRA_DELETE_NAME)
            ?: getString(R.string.dlg_delete_original_fallback_name)
        return uri.toUri() to name
    }

    /**
     * Delete the picked source. The SAF pick only grants us READ, so the direct delete legitimately
     * fails for most real sources; API 30+ then hands the decision to the system's own confirmation.
     * A failure is always reported — silently keeping a file the user asked to delete is worse than
     * saying we couldn't.
     */
    private fun deleteOriginal(uri: Uri) {
        val deleted = runCatching {
            when {
                uri.scheme == "file" -> uri.path?.let { File(it).delete() } == true
                DocumentsContract.isDocumentUri(this, uri) -> DocumentsContract.deleteDocument(contentResolver, uri)
                else -> contentResolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
        if (deleted) {
            toast(R.string.dlg_original_deleted)
            return
        }
        val asked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            systemDelete.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.isSuccess
        if (!asked) toast(R.string.dlg_delete_original_failed)
    }

    private fun toast(@StringRes resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    /**
     * Debug E2E entry point: `-e autorun_path <file>` starts a job with no permission prompts, and
     * `--ez autorun_cancel true` cancels the running one (the only way to exercise cancel-mid-job
     * from adb — the real cancel lives on the notification and in the UI).
     */
    private fun maybeAutorun() {
        if (intent.getBooleanExtra("autorun_cancel", false)) {
            JobController.cancel(this)
            return
        }
        // Phase-2 spike: two clipped exports, concatenated and decoded back. Its own thread —
        // RenderPipeline blocks on a Transformer export, which must not be driven from onCreate.
        if (intent.getBooleanExtra("segment_concat_probe", false)) {
            val source = intent.getStringExtra("probe_source")
                ?: File(filesDir, "test-video.mp4").absolutePath
            Thread { SegmentConcatSpike.run(applicationContext, source) }.start()
            return
        }
        val removeMusic = intent.getBooleanExtra("remove_music", false)
        // censor defaults true, except a music-only run (music requested, censor not explicitly passed).
        val censorWomen = if (intent.hasExtra("censor_women")) {
            intent.getBooleanExtra("censor_women", true)
        } else {
            !removeMusic
        }
        val ops = FilterOps(
            removeMusic = removeMusic,
            censorWomen = censorWomen,
            strictness = intent.getIntExtra("strictness", 50),
            blurAmount = intent.getIntExtra("blur", 60),
            grayscale = intent.getBooleanExtra("grayscale", false),
            blurUnknownFaces = intent.getBooleanExtra("blur_unknown", false),
            perRegionNsfw = intent.getBooleanExtra("per_region", false),
            keepStems = intent.getStringExtra("keep_stems") ?: "vocals",
        )

        // `--ez ytdlp_update true` forces the yt-dlp self-update from adb.
        if (intent.getBooleanExtra("ytdlp_update", false)) {
            kotlinx.coroutines.MainScope().launch {
                val status = runCatching { Downloader.update(this@MainActivity) }
                Toast.makeText(this@MainActivity, "yt-dlp: ${status.getOrNull() ?: status.exceptionOrNull()}", Toast.LENGTH_LONG).show()
            }
            return
        }

        // M4.1 debug entry point, ahead of any UI: `-e download_url <url> [-e quality AUDIO]` runs the
        // whole download → quarantine → filter → publish chain from adb. The share sheet (M4.2) ends up
        // calling exactly this, so what this exercises is the real path, not a parallel one.
        val downloadUrl = intent.getStringExtra("download_url")
        if (downloadUrl != null) {
            JobController.download(
                this, downloadUrl,
                Downloader.Quality.of(intent.getStringExtra("quality")),
                ops,
                intent.getStringExtra("title"),
            )
            return
        }

        val path = intent.getStringExtra("autorun_path") ?: return
        JobController.start(
            this, ops, Uri.fromFile(File(path)).toString(),
            intent.getStringExtra("force_intervals_ms"),
            // `--el segment_ms 60000` forces the Phase 2 segmented route on a short clip.
            intent.getLongExtra("segment_ms", 0L),
        )
    }
}

/**
 * First http(s) URL inside arbitrary shared text. Deliberately permissive about what follows the host
 * and strict about how the match ENDS — a trailing "." or "," in "watch this: <url>." belongs to the
 * sentence, not the link.
 */
private val URL_IN_TEXT =
    Regex("""https?://[\w\-]+(\.[\w\-]+)+([\w\-.,@?^=%&:/~+#]*[\w\-@?^=%&/~+#])?""")

@androidx.compose.runtime.Composable
private fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dlg_delete_original_title)) },
        text = {
            Text(
                stringResource(R.string.dlg_delete_original_body, name),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_keep)) } },
    )
}
