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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.theme.NaqiTokens
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
 * It opens immediately and leaves metadata discovery to the queued download, matching Seal's quick
 * path. A network round-trip before showing the controls would make sharing feel like an app launch.
 *
 * The two flows differ in three places and are otherwise the same screen: a file has no Quality (there
 * is nothing to choose — the file exists), its primary button says Filter, and that button is disabled
 * when both filters are off, because filtering nothing is a no-op the user should not be able to queue.
 *
 * Quality is a scrollable segmented control, and the filters are switches in one
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

    var ops by rememberSaveable { mutableStateOf(Prefs.ops(context)) }
    var quality by rememberSaveable { mutableStateOf(Prefs.quality(context)) }
    // What the faces toggle turns back ON to. The sheet has no Who control by design
    // (plan-censor-who §2.3) — it re-asks nothing, but it must not silently ANSWER either, so off-then-on
    // restores the last saved pick rather than hard-coding Everyone and persisting that downgrade below.
    val lastWho = remember { Prefs.lastWho(context) }

    val title = (shared as? Shared.LocalFile)?.name

    // Audio has no picture to blur, so the option is disabled rather than obeyed-and-ignored. Disabled
    // rather than hidden: switching quality must not make the row under the user's finger disappear.
    val audioOnly = isLink && quality == Downloader.Quality.AUDIO
    val effectiveOps = if (audioOnly) ops.copy(censorWho = FilterOps.NONE) else ops

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
        Preflight.checkSpaceForDownload(context, 0L, effectiveOps) ?: 0
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
            // ---- Header: title + domain ----
            Column {
                Text(
                    title ?: stringResource(R.string.share_untitled),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle(shared)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(NaqiTokens.space5))

            // ---- Quality (links only) — one row instead of three ----
            if (isLink) {
                SectionHeader(stringResource(R.string.share_eyebrow_quality))
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                ) {
                    val qualities = Downloader.Quality.entries
                    qualities.forEachIndexed { index, q ->
                        SegmentedButton(
                            selected = quality == q,
                            onClick = { quality = q },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = qualities.size),
                            modifier = Modifier.widthIn(min = 76.dp),
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
                    checked = effectiveOps.censorFaces,
                    enabled = !audioOnly,
                    onCheckedChange = { ops = ops.copy(censorWho = if (it) lastWho else FilterOps.NONE) },
                )
                if (effectiveOps.censorFaces) {
                    NaqiRowDivider()
                    ToggleTile(
                        title = stringResource(R.string.opt_nsfw_title),
                        checked = ops.censorNsfw,
                        onCheckedChange = { ops = ops.copy(censorNsfw = it) },
                    )
                }
            }

            // ---- Warnings ----
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
                    enabled = spaceError == 0 && (isLink || effectiveOps.any),
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

/** `youtube.com` for a link; nothing for a local file, whose name is already the title. */
@Composable
private fun subtitle(shared: Shared): String? {
    if (shared !is Shared.Link) return null
    return runCatching { shared.url.toUri().host?.removePrefix("www.") }.getOrNull()
}

private fun qualityLabel(q: Downloader.Quality) = when (q) {
    Downloader.Quality.BEST -> R.string.share_quality_best
    Downloader.Quality.P1080 -> R.string.share_quality_1080
    Downloader.Quality.P720 -> R.string.share_quality_720
    Downloader.Quality.P480 -> R.string.share_quality_480
    Downloader.Quality.AUDIO -> R.string.share_quality_audio
}
