package com.haithamassoli.naqi.ui

import android.app.DownloadManager
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.update.AppUpdate
import kotlinx.coroutines.launch

/**
 * The one place an app update is offered: a card above the video picker, in four states that share a
 * single footprint — available, downloading, ready, failed. It renders nothing at all when there is
 * nothing to say, so "you are up to date" is the absence of the card rather than a row claiming it.
 *
 * Everything it needs it owns, so dropping it into a screen costs one line and no new parameters
 * threaded through [NaqiApp][com.haithamassoli.naqi.ui.NaqiApp].
 *
 * It borrows the app's own accent for the icon tile — `colorScheme.primary`, jade since the M3
 * Expressive pass — exactly as [ToggleTile] does when checked, so it reads as part of the app rather
 * than a promotion parked on top of it. Its action is *outlined*, not filled: the one filled button
 * on this screen is Continue, and an update is never the reason someone opened Naqi.
 */
@Composable
fun UpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var release by remember { mutableStateOf<AppUpdate.Release?>(null) }
    var downloadId by remember { mutableLongStateOf(Prefs.appUpdateDownloadId(context)) }
    var downloadVersion by remember { mutableStateOf(Prefs.appUpdateDownloadVersion(context)) }
    var dismissed by remember { mutableStateOf(false) }

    fun forget() {
        AppUpdate.cancel(context)
        downloadId = Prefs.NO_DOWNLOAD
        downloadVersion = null
    }

    // Navigating back from the options screen recomposes this; the daily interval inside
    // Prefs.appUpdateDue is what keeps that from being a second request. The clock is only marked
    // when nothing was found, so a pending update survives being reopened instead of vanishing
    // until tomorrow.
    LaunchedEffect(Unit) {
        // First launch after the install is this app's only chance to notice the update landed:
        // drop the spent download before anything reads it, or the card comes back offering the
        // version already running. Clearing it also unblocks the check below.
        if (AppUpdate.isSuperseded(downloadVersion)) forget()
        if (downloadId == Prefs.NO_DOWNLOAD && Prefs.appUpdateDue(context)) {
            release = AppUpdate.check(context)
        }
    }

    val progress by produceState<AppUpdate.Progress?>(null, downloadId) {
        value = null
        if (downloadId != Prefs.NO_DOWNLOAD) AppUpdate.progress(context, downloadId).collect { value = it }
    }

    if (dismissed) return
    val version = release?.version?.toString() ?: downloadVersion ?: return
    val live = progress.takeIf { downloadId != Prefs.NO_DOWNLOAD }

    val skip = { Prefs.skipAppUpdate(context, version); dismissed = true }

    if (live == null) {
        val r = release ?: return
        Shell(
            modifier = modifier,
            title = stringResource(R.string.update_available, version),
            sub = stringResource(
                R.string.update_sub,
                Formatter.formatShortFileSize(context, r.sizeBytes),
                BuildConfig.VERSION_NAME,
            ),
            action = stringResource(R.string.update_action),
            onAction = { downloadId = AppUpdate.enqueue(context, r); downloadVersion = version },
            onDismiss = skip,
        )
        return
    }

    when (live.status) {
        DownloadManager.STATUS_SUCCESSFUL -> Shell(
            modifier = modifier,
            title = stringResource(R.string.update_ready, version),
            sub = stringResource(R.string.update_ready_sub),
            action = stringResource(R.string.update_install),
            onAction = { AppUpdate.install(context, downloadId) },
            // The one state that used to have no way out. If the install is declined — or already
            // happened somewhere this build cannot see — ✕ is what drops the APK.
            onDismiss = { forget() },
        )

        DownloadManager.STATUS_FAILED -> Shell(
            modifier = modifier,
            title = stringResource(R.string.update_failed),
            sub = stringResource(R.string.update_failed_sub),
            action = stringResource(R.string.update_retry),
            // A retry starts clean rather than resuming a broken transfer, and re-checks in case the
            // card was restored from a download whose release we no longer hold.
            onAction = { forget(); scope.launch { release = AppUpdate.check(context) } },
            onDismiss = skip,
        )

        // PENDING, RUNNING or PAUSED — all of them are "in flight" as far as the user is concerned.
        else -> Shell(
            modifier = modifier,
            title = stringResource(R.string.update_downloading, version),
            sub = stringResource(
                R.string.update_bytes,
                Formatter.formatShortFileSize(context, live.done),
                Formatter.formatShortFileSize(context, live.total),
            ).takeIf { live.total > 0 },
            bar = true,
            // -1 until the server reports a length; an indeterminate bar is the honest reading of
            // "started, size unknown".
            fraction = (live.done.toFloat() / live.total).takeIf { live.total > 0 },
            action = stringResource(R.string.action_cancel),
            // No dismiss while bytes are moving: cancelling is the exit, and an ✕ beside it would
            // only raise the question of which one stops the download.
            onAction = { forget() },
        )
    }
}

/**
 * The shared shell — icon tile, two lines, an optional bar and one trailing action. Same geometry as
 * [ToggleTile] so this reads as part of the app rather than as a promotion parked on top of it.
 */
@Composable
private fun Shell(
    title: String,
    sub: String?,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    bar: Boolean = false,
    fraction: Float? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    NaqiCard(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(NaqiTokens.shapeButton)
                    .background(cs.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NaqiIcons.Download, null, tint = cs.primary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(NaqiTokens.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sub != null) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        NaqiIcons.Close,
                        contentDescription = stringResource(R.string.update_dismiss),
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (bar) {
            Spacer(Modifier.height(NaqiTokens.space3))
            // The bar is the percentage — a number next to it would say the same thing twice.
            if (fraction != null) {
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(NaqiTokens.space3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onAction, shape = NaqiTokens.shapeButton) { Text(action) }
        }
    }
}
