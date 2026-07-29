package com.haithamassoli.naqi.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.NaqiCard
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
 * State comes from `queue.json` rather than `WorkInfo`, because a queue-driven run always returns
 * success; see [Queue].
 */
@Composable
internal fun QueueSection() {
    val context = LocalContext.current
    val items by Queue.items.collectAsState()

    // The flow is populated by whoever writes it; on a cold start nothing has yet.
    LaunchedEffect(Unit) { Queue.load(context) }

    if (items.isEmpty()) return

    Eyebrow(stringResource(R.string.queue_eyebrow))
    Spacer(Modifier.height(NaqiTokens.space2))

    items.forEach { item ->
        NaqiCard {
            Text(
                item.title ?: stringResource(R.string.share_untitled),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(NaqiTokens.space1))
            Text(
                stringResource(stateLabel(item.state)),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == Queue.State.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            item.error?.takeIf { it != 0 }?.let {
                Spacer(Modifier.height(NaqiTokens.space1))
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.state == Queue.State.FAILED) {
                    TextButton(onClick = { JobController.retry(context, item) }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    Spacer(Modifier.width(NaqiTokens.space1))
                }
                if (!item.state.isTerminal) {
                    TextButton(onClick = { JobController.cancelItem(context, item) }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                } else {
                    TextButton(onClick = { Queue.remove(context, item.id) }) {
                        Text(stringResource(R.string.queue_dismiss))
                    }
                }
            }
        }
        Spacer(Modifier.height(NaqiTokens.space2))
    }

    if (items.any { it.state.isTerminal }) {
        TextButton(onClick = { Queue.clearTerminal(context) }) {
            Text(stringResource(R.string.queue_clear_finished))
        }
    }
    Spacer(Modifier.height(NaqiTokens.space4))
}

private fun stateLabel(state: Queue.State) = when (state) {
    Queue.State.PENDING_DOWNLOAD -> R.string.queue_state_pending_download
    Queue.State.DOWNLOADING -> R.string.stage_downloading
    Queue.State.PENDING_FILTER -> R.string.queue_state_pending_filter
    Queue.State.FILTERING -> R.string.queue_state_filtering
    Queue.State.DONE -> R.string.queue_state_done
    Queue.State.FAILED -> R.string.queue_state_failed
}
