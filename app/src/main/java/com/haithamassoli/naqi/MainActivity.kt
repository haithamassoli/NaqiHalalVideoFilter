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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.spike.MuxerLimitSpike
import com.haithamassoli.naqi.spike.SegmentConcatSpike
import com.haithamassoli.naqi.ui.NaqiApp
import com.haithamassoli.naqi.ui.theme.NaqiTheme
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.JobNotifications
import java.io.File

class MainActivity : ComponentActivity() {

    /** Set when the "Delete original" notification action opened us; drives the confirm dialog. */
    private var deleteTarget by mutableStateOf<Pair<Uri, String>?>(null)

    // API 30+ fallback: the system asks the user itself, the only path that works for media we don't own.
    private val systemDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            toast(if (result.resultCode == RESULT_OK) "Original deleted" else "Original kept")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG) maybeAutorun()
        deleteTarget = deleteTargetOf(intent)
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
    }

    private fun deleteTargetOf(intent: Intent): Pair<Uri, String>? {
        if (intent.action != JobNotifications.ACTION_CONFIRM_DELETE) return null
        val uri = intent.getStringExtra(JobNotifications.EXTRA_DELETE_ORIGINAL)?.takeIf { it.isNotBlank() } ?: return null
        return uri.toUri() to (intent.getStringExtra(JobNotifications.EXTRA_DELETE_NAME) ?: "this video")
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
            toast("Original deleted")
            return
        }
        val asked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            systemDelete.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.isSuccess
        if (!asked) toast("Couldn’t delete the original — remove it from your gallery instead.")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

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
        // Phase-0 spike: writes a >4 GiB mp4 and reads it back. Minutes of blocking I/O, so its own
        // thread; there is nothing to show on screen, the answer comes out of logcat.
        if (intent.getBooleanExtra("muxer_limit_probe", false)) {
            val source = intent.getStringExtra("probe_source")
                ?: File(filesDir, "movie-test.mp4").absolutePath
            Thread { MuxerLimitSpike.run(applicationContext, source) }.start()
            return
        }
        // Phase-2 spike: two clipped exports, concatenated and decoded back. Also its own thread —
        // RenderPipeline blocks on a Transformer export, which must not be driven from onCreate.
        if (intent.getBooleanExtra("segment_concat_probe", false)) {
            val source = intent.getStringExtra("probe_source")
                ?: File(filesDir, "test-video.mp4").absolutePath
            Thread { SegmentConcatSpike.run(applicationContext, source) }.start()
            return
        }
        val path = intent.getStringExtra("autorun_path") ?: return
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
            keepStems = intent.getStringExtra("keep_stems") ?: "vocals",
        )
        JobController.start(
            this, ops, Uri.fromFile(File(path)).toString(),
            intent.getStringExtra("force_intervals_ms"),
            // `--el segment_ms 60000` forces the Phase 2 segmented route on a short clip.
            intent.getLongExtra("segment_ms", 0L),
        )
    }
}

@androidx.compose.runtime.Composable
private fun ConfirmDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete the original?") },
        text = {
            Text(
                "The filtered copy ($name) stays in Movies/Naqi. The original video will be removed " +
                    "from this device and can’t be recovered.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
    )
}
