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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.data.Prefs
import com.haithamassoli.naqi.media.displayName
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.durationText
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.Eta
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Queue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a shared video lands: pick the filters, queue it, get out of the way.
 *
 * It is a sheet and not a screen because it appears mid-share, on top of another app — the user is not
 * here to configure anything, they are here to hand Naqi a file. So it asks the one question that
 * cannot be defaulted (which filters) and answers everything else from [Prefs]. The full control set
 * lives on [OptionsScreen], one step off the picker, for when someone actually wants to tune a job.
 *
 * The primary button is disabled when both filters are off: filtering nothing is a no-op the user
 * should not be able to queue, and unlike the picker path the file already exists.
 */
@Composable
fun ShareSheet(
    uri: Uri,
    onDismiss: () -> Unit,
    onQueued: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var ops by remember { mutableStateOf(Prefs.ops(context)) }
    // What the faces toggle turns back ON to. The sheet has no Who control by design
    // (plan-censor-who §2.3) — it re-asks nothing, but it must not silently ANSWER either, so off-then-on
    // restores the last saved pick rather than hard-coding Everyone and persisting that downgrade below.
    val lastWho = remember { Prefs.lastWho(context) }
    val title = remember(uri) { context.displayName(uri) ?: uri.lastPathSegment }

    // Same contract as OptionsScreen: probing opens MediaExtractor + MediaMetadataRetriever, so it never
    // runs in composition, and 0 means "no estimate" rather than "instant". A probe failure is silent —
    // it must never stand between the user and the one button they came here to press.
    var durationMs by remember(uri) { mutableStateOf(0L) }
    LaunchedEffect(uri) {
        durationMs = withContext(Dispatchers.IO) {
            runCatching { FrameSampler.probe(context, uri).durationMs }.getOrDefault(0L)
        }
    }

    fun queue() {
        Prefs.save(context, ops)
        // Through the queue rather than straight to JobController.start: one list, one place the user
        // looks, and one set of retry/cancel rules however many videos get shared in a row.
        JobController.enqueue(
            context,
            Queue.Item(sourceUri = uri.toString(), title = title, ops = ops),
        )
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
            Text(
                title ?: stringResource(R.string.share_untitled),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            durationMs.takeIf { it > 0 }?.let {
                Text(
                    durationText(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(NaqiTokens.space5))

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
                    checked = ops.censorFaces,
                    onCheckedChange = { ops = ops.copy(censorWho = if (it) lastWho else FilterOps.NONE) },
                )
            }

            // ---- Warning ----
            val etaMs = Eta.estimateMs(durationMs, ops)
            if (etaMs > Eta.CONFIRM_THRESHOLD_MS) {
                Spacer(Modifier.height(NaqiTokens.space3))
                Text(
                    stringResource(R.string.dlg_long_job_body, durationText(etaMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Actions: the primary takes the width it deserves ----
            Spacer(Modifier.height(NaqiTokens.space5))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(NaqiTokens.space3))
                Button(
                    onClick = ::onPrimary,
                    enabled = ops.any,
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                ) {
                    Text(stringResource(R.string.action_filter))
                }
            }
        }
    }
}
