@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.haithamassoli.naqi.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.ToggleTile
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
 *
 * Quality is a segmented control rather than three stacked rows, and the filters are switches in one
 * card: the whole sheet is a screenful shorter, which matters more here than anywhere — this is the
 * surface a user meets mid-share, on top of another app.
 */
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

    // Audio has no picture to blur, so the option is disabled rather than obeyed-and-ignored. Disabled
    // rather than hidden: switching quality must not make the row under the user's finger disappear.
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
                .padding(bottom = NaqiTokens.space6),
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
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(NaqiTokens.space5))

            // ---- Quality (links only) — one row instead of three ----
            if (isLink) {
                SectionHeader(stringResource(R.string.share_eyebrow_quality))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val qualities = Downloader.Quality.entries
                    qualities.forEachIndexed { index, q ->
                        SegmentedButton(
                            selected = quality == q,
                            onClick = { quality = q },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = qualities.size),
                        ) { Text(stringResource(qualityLabel(q)), maxLines = 1) }
                    }
                }
                Spacer(Modifier.height(NaqiTokens.space5))
            }

            // ---- Filters ----
            SectionHeader(stringResource(R.string.share_eyebrow_filters))
            NaqiCard(contentPadding = 0.dp) {
                ToggleTile(
                    title = stringResource(R.string.pick_op_music_title),
                    icon = NaqiIcons.MusicOff,
                    checked = ops.removeMusic,
                    onCheckedChange = { ops = ops.copy(removeMusic = it) },
                )
                NaqiRowDivider()
                ToggleTile(
                    title = stringResource(R.string.pick_op_faces_title),
                    icon = NaqiIcons.Shield,
                    checked = effectiveOps.censorWomen,
                    enabled = !audioOnly,
                    onCheckedChange = { ops = ops.copy(censorWomen = it) },
                )
            }

            // ---- Warnings ----
            val etaMs = Eta.estimateMs(durationMs, effectiveOps)
            if (etaMs > Eta.CONFIRM_THRESHOLD_MS) {
                Spacer(Modifier.height(NaqiTokens.space3))
                Text(
                    stringResource(R.string.dlg_long_job_body, durationText(etaMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (spaceError != 0) {
                Spacer(Modifier.height(NaqiTokens.space3))
                Text(
                    stringResource(spaceError),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // ---- Actions: the primary takes the width it deserves ----
            Spacer(Modifier.height(NaqiTokens.space5))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(NaqiTokens.space3))
                Button(
                    onClick = ::onPrimary,
                    // A local file with no filters selected has nothing to do — the file already exists.
                    // A link with no filters is still a download, so it stays enabled.
                    enabled = !loading && errorRes == 0 && spaceError == 0 &&
                        (isLink || effectiveOps.any),
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
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
