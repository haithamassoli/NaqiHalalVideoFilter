package com.haithamassoli.naqi.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.SmokeReport
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiTopBar
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About + open-source licences, the only place the user can force a yt-dlp update, and — since this
 * redesign — where the model smoke report lives. That report is a diagnostic: useful when a device
 * misbehaves, noise on the screen where someone is trying to pick a video.
 *
 * The attribution text is the repository's own `NOTICE`, copied into assets by the build rather than
 * retyped as a string resource — see `app/build.gradle.kts`. It is deliberately not translated: a
 * licence notice is a legal document, and a translated one would be a second, unreviewed version of it.
 * It is collapsed by default: a screen of legal text between the user and everything below it helps
 * nobody, and the obligation is that it be reachable, not that it be unavoidable.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notice by remember { mutableStateOf("") }
    var showNotice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        notice = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("NOTICE").bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
    }

    var smoke by remember { mutableStateOf<SmokeReport?>(null) }
    LaunchedEffect(Unit) { smoke = withContext(Dispatchers.Default) { ModelSmoke.run(context) } }

    // Null until read; the version only exists once an update has run at least once (it is written by
    // the updater, not read out of the bundled zipapp).
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(context) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NaqiTopBar(stringResource(R.string.about_title), onBack = onBack) },
        modifier = modifier,
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space4, bottom = NaqiTokens.space7),
        ) {
            // The brand lockup, once, where there is room for it.
            Wordmark()
            Spacer(Modifier.height(NaqiTokens.space3))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.pick_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(NaqiTokens.space2))
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(NaqiTokens.space6))
            SectionHeader(stringResource(R.string.about_eyebrow_downloader))
            NaqiCard {
                Text(
                    stringResource(
                        R.string.about_ytdlp_version,
                        ytdlpVersion ?: stringResource(R.string.about_ytdlp_unknown),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(NaqiTokens.space1))
                Text(
                    stringResource(R.string.about_ytdlp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                updateResult?.let {
                    Spacer(Modifier.height(NaqiTokens.space2))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(NaqiTokens.space3))
                OutlinedButton(
                    enabled = !updating,
                    shape = RoundedCornerShape(NaqiTokens.radiusButton),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        updating = true
                        updateResult = null
                        scope.launch {
                            val status = runCatching { Downloader.update(context) }
                            ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(context) }
                            updateResult = context.getString(
                                if (status.isSuccess) R.string.about_update_ok else R.string.about_update_failed,
                            )
                            updating = false
                        }
                    },
                ) {
                    Text(stringResource(if (updating) R.string.about_updating else R.string.about_update))
                }
            }

            Spacer(Modifier.height(NaqiTokens.space5))
            SectionHeader(stringResource(R.string.pick_diag_title))
            NaqiCard { Diagnostics(smoke) }

            Spacer(Modifier.height(NaqiTokens.space5))
            SectionHeader(stringResource(R.string.about_eyebrow_licenses))
            NaqiCard(contentPadding = 0.dp) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showNotice = !showNotice }
                        .padding(NaqiTokens.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.about_notices_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        stringResource(if (showNotice) R.string.action_hide else R.string.action_show),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (showNotice) {
                    Text(
                        notice.ifBlank { stringResource(R.string.about_notice_missing) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = NaqiTokens.space4,
                            end = NaqiTokens.space4,
                            bottom = NaqiTokens.space4,
                        ),
                    )
                }
            }
        }
    }
}

/** ORT execution providers plus one line per bundled model — what to read out when a device misbehaves. */
@Composable
private fun Diagnostics(smoke: SmokeReport?) {
    val cs = MaterialTheme.colorScheme
    if (smoke == null) {
        Text(stringResource(R.string.pick_diag_running), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        return
    }
    Text(
        stringResource(R.string.pick_diag_eps, smoke.providers.joinToString(", ").ifEmpty { "—" }),
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurface,
    )
    smoke.models.forEach { r ->
        val (mark, color) = when {
            r.ok -> "✓" to cs.onSurface
            !r.bundled -> "–" to cs.onSurfaceVariant
            else -> "✗" to cs.error
        }
        Text(
            stringResource(R.string.pick_diag_model_line, mark, r.model.assetName.removeSuffix(".onnx"), r.detail),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
