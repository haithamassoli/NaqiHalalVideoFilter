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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiApp
import com.haithamassoli.naqi.ui.theme.NaqiTheme
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.JobNotifications
import java.io.File

class MainActivity : ComponentActivity() {

    /** Set when the "Delete original" notification action opened us; drives the confirm dialog. */
    private var deleteTarget by mutableStateOf<Pair<Uri, String>?>(null)

    /** Incremented for every notification-body tap so an already-running app handles repeated taps. */
    private var activitiesRequest by mutableIntStateOf(0)

    // API 30+ fallback: the system asks the user itself, the only path that works for media we don't own.
    private val systemDelete =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            toast(if (result.resultCode == RESULT_OK) R.string.dlg_original_deleted else R.string.dlg_original_kept)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.DEBUG_HOOKS) maybeAutorun()
        deleteTarget = deleteTargetOf(intent)
        if (consumeActivitiesRequest(intent)) activitiesRequest++
        // Weekly yt-dlp check (PRD M4.4). Off the main thread, failure-tolerant, and no-op six days out
        // of seven — sites change how they serve video far faster than the app ships.
        lifecycleScope.launch { Downloader.updateIfDue(this@MainActivity) }
        setContent {
            NaqiTheme {
                NaqiApp(
                    activitiesRequest = activitiesRequest,
                    onDeleteOriginal = ::confirmDeleteOriginal,
                    modifier = Modifier.fillMaxSize(),
                )
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
        // singleTask means a redelivered intent lands here, not in onCreate — the debug
        // cancel hook has to be honoured on both paths or `am start --ez autorun_cancel` is a no-op.
        if (BuildConfig.DEBUG_HOOKS) maybeAutorun()
        deleteTargetOf(intent)?.let { deleteTarget = it }
        if (consumeActivitiesRequest(intent)) activitiesRequest++
    }

    /**
     * The Saved card's "Delete original", which asks for exactly the confirmation the notification
     * action does — one dialog, one delete, no second path to get a file wrong.
     */
    fun confirmDeleteOriginal(uri: Uri, name: String?) {
        deleteTarget = uri to (name ?: getString(R.string.dlg_delete_original_fallback_name))
    }

    private fun deleteTargetOf(intent: Intent): Pair<Uri, String>? {
        if (intent.action != JobNotifications.ACTION_CONFIRM_DELETE) return null
        val uri = intent.getStringExtra(JobNotifications.EXTRA_DELETE_ORIGINAL)?.takeIf { it.isNotBlank() } ?: return null
        val name = intent.getStringExtra(JobNotifications.EXTRA_DELETE_NAME)
            ?: getString(R.string.dlg_delete_original_fallback_name)
        return uri.toUri() to name
    }

    private fun consumeActivitiesRequest(intent: Intent): Boolean {
        if (intent.action != JobNotifications.ACTION_SHOW_ACTIVITIES) return false
        // Do not replay the notification route on a configuration recreation; the Compose step itself
        // is already saveable. A later tap delivers a fresh intent and still reaches onNewIntent.
        setIntent(Intent(intent).setAction(null))
        return true
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
            // createDeleteRequest only speaks MediaStore ids, and what the picker hands us is a
            // *document* uri — passing that straight through is a request the media provider cannot
            // resolve, i.e. every SAF pick failing at the last step. getMediaUri translates it (it
            // needs the read grant we persisted at pick time); anything it refuses — a provider that
            // is not the device's own, a file no scanner has indexed — falls through to the uri we
            // already had, which is no worse than before.
            val target = runCatching { MediaStore.getMediaUri(this, uri) }.getOrDefault(uri)
            val request = MediaStore.createDeleteRequest(contentResolver, listOf(target))
            systemDelete.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.isSuccess
        if (!asked) toast(R.string.dlg_delete_original_failed)
    }

    private fun toast(@StringRes resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    /**
     * Debug E2E entry point: `-e autorun_path <file>` starts a job with no permission prompts, and
     * `--ez autorun_cancel true` cancels the running one (the only way to exercise cancel-mid-job
     * from adb — the real cancel lives on the notification and in the UI).
     *
     * Gated on `DEBUG_HOOKS`, not `DEBUG`: the `benchmark` build type is non-debuggable and still has to
     * reach this, because it is the only way to start a job from adb and so the only way the SOAK lines
     * get emitted on an optimised build (plan-v2 §4.1).
     */
    private fun maybeAutorun() {
        if (intent.getBooleanExtra("autorun_cancel", false)) {
            JobController.cancel(this)
            return
        }
        val removeMusic = intent.getBooleanExtra("remove_music", false)
        // censor defaults on, except a music-only run (music requested, censor not explicitly passed).
        // `--es censor_who none|everyone|women|men` is the current key; `--ez censor_women` still reads
        // so a stale script keeps working (plan-censor-who §1.1), and the default rule above applies
        // only when neither was passed. Unparseable `censor_who` resolves to everyone, not to the
        // legacy branch — see FilterOps.whoOrNull.
        val censorWho = FilterOps.whoOrNull(intent.getStringExtra("censor_who"))
            ?: FilterOps.whoFromLegacy(
                if (intent.hasExtra("censor_women")) intent.getBooleanExtra("censor_women", true)
                else !removeMusic
            )
        val ops = FilterOps(
            removeMusic = removeMusic,
            censorWho = censorWho,
            // `--ez whole_frame true` — covers the whole picture while a censored face is on screen.
            wholeFrameBlur = intent.getBooleanExtra("whole_frame", false),
            // `--ez censor_nsfw false` — keeps face censoring but disables whole-scene NSFW spans.
            censorNsfw = intent.getBooleanExtra("censor_nsfw", true),
            strictness = intent.getIntExtra("strictness", FilterOps.DEFAULT_STRICTNESS),
            blurAmount = intent.getIntExtra("blur", 60),
            grayscale = intent.getBooleanExtra("grayscale", false),
            // `--ei solid 0xFF000000`-style; 0 (the default) keeps blur.
            solidColor = intent.getIntExtra("solid", FilterOps.BLUR),
            // `--ez blur_unknown` is gone with the gender vote (plan-v2 §5.4); passing it is now a no-op.
            keepStems = intent.getStringExtra("keep_stems") ?: "vocals",
        )
        // Echo what actually parsed. `everyone` and `women` censor identically until Phase C ships a
        // classifier, so without this an adb run cannot show WHICH value survived the wire — only that
        // something censored. Same reason the E2E hooks exist at all: logcat beats UI scripting here.
        android.util.Log.i("NaqiOps", "autorun censorWho=${ops.censorWho} censorFaces=${ops.censorFaces} censorNsfw=${ops.censorNsfw} removeMusic=${ops.removeMusic} wholeFrame=${ops.wholeFrameBlur}")

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
