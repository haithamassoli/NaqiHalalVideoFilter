package com.haithamassoli.naqi.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.net.toUri
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.SelectDot
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.Eta
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Preflight
import com.haithamassoli.naqi.work.Queue

/** What arrived on the share intent. */
sealed interface Shared {
    /** `ACTION_SEND text/plain` — a link to fetch. */
    data class Link(val url: String) : Shared

    // NOTE: the mime is deliberately not written with a star anywhere in a comment in this file —
    // Kotlin block comments NEST, so a "video/" followed by a star opens one and swallows the rest.
    /** `ACTION_SEND` with a `video/` mime — a file that already exists; no download, no quarantine. */
    data class LocalFile(val uri: Uri, val name: String?) : Shared
}

/**
 * The one sheet both share flows land in (PRD flows A and B).
 *
 * It opens **immediately** and fills in afterwards: `getInfo` is a network round-trip through a forked
 * yt-dlp process and can take seconds, and a share that shows nothing until it returns reads as a
 * broken app. So the sheet appears with a spinner, then a title, or an inline error with Retry.
 *
 * The two flows differ in three places and are otherwise the same screen: a file has no Quality (there
 * is nothing to choose — the file exists), its primary button says Filter, and that button is disabled
 * when both filters are off, because filtering nothing is a no-op the user should not be able to queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    shared: Shared,
    onDismiss: () -> Unit,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isLink = shared is Shared.Link

    var ops by remember { mutableStateOf(Prefs.ops(context)) }
    var quality by remember { mutableStateOf(Prefs.quality(context)) }

    // Link metadata. `loading` starts true only for a link — a local file has nothing to fetch.
    var loading by remember { mutableStateOf(isLink) }
    var errorRes by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf((shared as? Shared.LocalFile)?.name) }
    var durationMs by remember { mutableStateOf(0L) }
    var sizeBytes by remember { mutableStateOf(0L) }
    var attempt by remember { mutableIntStateOf(0) } // bumped by Retry to re-run the effect

    LaunchedEffect(shared, attempt) {
        if (shared !is Shared.Link) return@LaunchedEffect
        loading = true
        errorRes = 0
        runCatching { Downloader.getInfo(context, shared.url) }
            .onSuccess { info ->
                title = info.title?.takeIf { it.isNotBlank() }
                durationMs = info.duration.toLong() * 1000L
                sizeBytes = info.fileSizeApproximate.takeIf { it > 0L } ?: info.fileSize
            }
            .onFailure { errorRes = R.string.share_info_failed }
        loading = false
    }

    // Audio has no picture to blur, so the option is hidden rather than shown-and-ignored.
    val audioOnly = isLink && quality == Downloader.Quality.AUDIO
    val effectiveOps = if (audioOnly) ops.copy(censorWomen = false) else ops

    fun queue() {
        Prefs.save(context, ops, quality)
        // Everything goes through the queue, including a local file: one list, one place the user looks,
        // and one set of retry/cancel rules regardless of where the item came from.
        val item = when (shared) {
            is Shared.Link -> Queue.Item(
                url = shared.url,
                title = title,
                state = Queue.State.PENDING_DOWNLOAD,
                ops = effectiveOps,
                quality = quality.name,
            )

            is Shared.LocalFile -> Queue.Item(
                sourceUri = shared.uri.toString(),
                title = title,
                state = Queue.State.PENDING_FILTER,
                ops = effectiveOps,
            )
        }
        JobController.enqueue(context, item)
        onQueued()
    }

    // Same contract OptionsScreen uses: start either way, because a denied notification hides the
    // progress but does not stop the job. Asked on the first primary tap, not at share time.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        queue()
    }

    fun onPrimary() {
        val needsNotif = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotif) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else queue()
    }

    // Space is checked here rather than only in the worker so the refusal arrives before the item is
    // queued — "it failed four items later" is not a useful thing to tell someone about disk space.
    val spaceError = if (isLink) {
        Preflight.checkSpaceForDownload(context, sizeBytes, effectiveOps) ?: 0
    } else {
        0
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NaqiTokens.gutter)
                .padding(bottom = NaqiTokens.space7),
        ) {
            // ---- Header: title + duration · domain ----
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(NaqiTokens.space3))
                    Text(
                        stringResource(R.string.share_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                errorRes != 0 -> Column {
                    Text(
                        stringResource(errorRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                }

                else -> Column {
                    Text(
                        title ?: stringResource(R.string.share_untitled),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    subtitle(shared, durationMs)?.let {
                        Spacer(Modifier.height(NaqiTokens.space1))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(NaqiTokens.space5))

            // ---- Quality (links only) ----
            if (isLink) {
                Eyebrow(stringResource(R.string.share_eyebrow_quality))
                Spacer(Modifier.height(NaqiTokens.space2))
                Downloader.Quality.entries.forEach { q ->
                    OptionRow(
                        label = stringResource(qualityLabel(q)),
                        selected = quality == q,
                        onClick = { quality = q },
                    )
                }
                Spacer(Modifier.height(NaqiTokens.space5))
            }

            // ---- Filters ----
            Eyebrow(stringResource(R.string.share_eyebrow_filters))
            Spacer(Modifier.height(NaqiTokens.space2))
            OptionRow(
                label = stringResource(R.string.pick_op_music_title),
                selected = ops.removeMusic,
                onClick = { ops = ops.copy(removeMusic = !ops.removeMusic) },
            )
            if (!audioOnly) {
                OptionRow(
                    label = stringResource(R.string.pick_op_women_title),
                    selected = ops.censorWomen,
                    onClick = { ops = ops.copy(censorWomen = !ops.censorWomen) },
                )
            }

            // ---- Warnings ----
            val etaMs = Eta.estimateMs(durationMs, effectiveOps)
            if (etaMs > Eta.CONFIRM_THRESHOLD_MS) {
                Spacer(Modifier.height(NaqiTokens.space4))
                Text(
                    stringResource(R.string.dlg_long_job_body, durationText(etaMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spaceError != 0) {
                Spacer(Modifier.height(NaqiTokens.space4))
                Text(
                    stringResource(spaceError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ---- Actions ----
            Spacer(Modifier.height(NaqiTokens.space5))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(NaqiTokens.space2))
                Button(
                    onClick = ::onPrimary,
                    // A local file with no filters selected has nothing to do — the file already exists.
                    // A link with no filters is still a download, so it stays enabled.
                    enabled = !loading && errorRes == 0 && spaceError == 0 &&
                        (isLink || effectiveOps.any),
                ) {
                    Text(stringResource(if (isLink) R.string.action_download else R.string.action_filter))
                }
            }
        }
    }
}

/** `12:34 · youtube.com` for a link; nothing for a local file, whose name is already the title. */
@Composable
private fun subtitle(shared: Shared, durationMs: Long): String? {
    if (shared !is Shared.Link) return null
    val host = runCatching { shared.url.toUri().host?.removePrefix("www.") }.getOrNull()
    val duration = durationMs.takeIf { it > 0 }?.let { durationText(it) }
    return listOfNotNull(duration, host).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun qualityLabel(q: Downloader.Quality) = when (q) {
    Downloader.Quality.BEST -> R.string.share_quality_best
    Downloader.Quality.P720 -> R.string.share_quality_720
    Downloader.Quality.AUDIO -> R.string.share_quality_audio
}

/** One tappable row with the app's shared selection dot — same affordance as the options screen. */
@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NaqiTokens.radiusCard))
            .clickable(onClick = onClick)
            .padding(vertical = NaqiTokens.space3, horizontal = NaqiTokens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SelectDot(selected)
        Spacer(Modifier.width(NaqiTokens.space3))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
