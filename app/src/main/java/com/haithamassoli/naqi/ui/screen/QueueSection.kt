package com.haithamassoli.naqi.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiIcons
import com.haithamassoli.naqi.ui.NaqiRowDivider
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Queue

/**
 * The share queue, shown above the single-job card on the jobs screen.
 *
 * Deliberately not its own screen: the queue and the running job are the same question ("what is Naqi
 * doing?"), and splitting them would mean the user has to know which of two places to look. The legacy
 * picker path renders exactly as before — this section is simply absent when nothing was ever shared.
 *
 * **One list, one row per item.** It used to be a full bordered card per item — title, state, error and
 * a row of text buttons, ~130dp each — so three shared links filled the screen before the running job
 * was even visible. Now: one card, one 56dp row per item, a status glyph carrying the state that used
 * to need its own line, and exactly one action per row. "Clear finished" moved into the section header,
 * where it reads as a list action instead of a stray button under the last item.
 *
 * State comes from `queue.json` rather than `WorkInfo`, because a queue-driven run always returns
 * success; see [Queue].
 */
@Composable
internal fun QueueCard(items: List<Queue.Item>) {
    val context = LocalContext.current

    SectionHeader(
        stringResource(R.string.queue_eyebrow),
        trailing = {
            if (items.any { it.state.isTerminal }) {
                TextButton(onClick = { Queue.clearTerminal(context) }) {
                    Text(stringResource(R.string.queue_clear_finished), style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    )
    NaqiCard(contentPadding = 0.dp) {
        items.forEachIndexed { index, item ->
            if (index > 0) NaqiRowDivider()
            QueueRow(item)
        }
    }
}

@Composable
private fun QueueRow(item: Queue.Item) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val failed = item.state == Queue.State.FAILED

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = NaqiTokens.space4, end = NaqiTokens.space2, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(item.state)
        Spacer(Modifier.width(NaqiTokens.space3))
        Column(Modifier.weight(1f)) {
            Text(
                item.title ?: stringResource(R.string.share_untitled),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // A failure says why on this line rather than adding a third one; the state name would only
            // repeat what the red glyph already says.
            val error = item.error?.takeIf { it != 0 }
            Text(
                stringResource(error ?: stateLabel(item.state)),
                style = MaterialTheme.typography.bodySmall,
                color = if (failed) cs.error else cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(NaqiTokens.space2))
        // Exactly one action per row. A failed item offers the only thing worth doing to it; getting rid
        // of it is "Clear finished" in the header, which handles the whole list at once.
        when {
            failed -> TextButton(onClick = { JobController.retry(context, item) }) {
                Text(stringResource(R.string.action_retry))
            }

            item.state.isTerminal -> IconButton(onClick = { Queue.remove(context, item.id) }) {
                Icon(NaqiIcons.Close, stringResource(R.string.queue_dismiss), tint = cs.onSurfaceVariant)
            }

            else -> IconButton(onClick = { JobController.cancelItem(context, item) }) {
                Icon(NaqiIcons.Close, stringResource(R.string.action_cancel), tint = cs.onSurfaceVariant)
            }
        }
    }
}

/**
 * 20dp of state: a spinner while it works, a tick when it lands, a cross when it doesn't.
 *
 * Every variant is drawn inside one fixed 20dp box — [CircularProgressIndicator] brings its own
 * padding, so sized on its own it lands a couple of dp off the column the other glyphs line up on.
 */
@Composable
private fun StatusGlyph(state: Queue.State) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (state) {
            Queue.State.FILTERING ->
                CircularProgressIndicator(Modifier.size(18.dp), color = cs.primary, strokeWidth = 2.5.dp)

            Queue.State.DONE -> Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(NaqiIcons.Check, null, tint = cs.onPrimary, modifier = Modifier.size(13.dp)) }

            Queue.State.FAILED -> Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(cs.error),
                contentAlignment = Alignment.Center,
            ) { Icon(NaqiIcons.Close, null, tint = cs.onError, modifier = Modifier.size(13.dp)) }

            // Waiting: an empty ring, so a queued item reads as "nothing is happening to this yet".
            else -> Box(
                Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .border(2.dp, cs.outlineVariant, CircleShape),
            )
        }
    }
}

private fun stateLabel(state: Queue.State) = when (state) {
    Queue.State.PENDING_FILTER -> R.string.queue_state_pending_filter
    Queue.State.FILTERING -> R.string.queue_state_filtering
    Queue.State.DONE -> R.string.queue_state_done
    Queue.State.FAILED -> R.string.queue_state_failed
}
