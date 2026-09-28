package com.haithamassoli.naqi.ui.screen

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.data.Battery
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.ml.ModelSmoke
import com.haithamassoli.naqi.ml.SmokeReport
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.NaqiTopBar
import com.haithamassoli.naqi.ui.SectionHeader
import com.haithamassoli.naqi.ui.ToggleTile
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About + open-source licences, the link to the repository, the only place the user can force a
 * yt-dlp update, and — since this redesign — where the model smoke report lives. That report is a
 * diagnostic: useful when a device misbehaves, noise on the screen where someone is trying to pick
 * a video.
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
    val uriHandler = LocalUriHandler.current

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
            SectionHeader(stringResource(R.string.settings_eyebrow))
            NaqiCard(contentPadding = 0.dp) { BatteryToggle() }

            Spacer(Modifier.height(NaqiTokens.space5))
            SectionHeader(stringResource(R.string.about_eyebrow_contact))
            val feedbackSubject = stringResource(R.string.about_feedback_subject)
            LinkCard(
                title = stringResource(R.string.about_feedback_title),
                desc = stringResource(R.string.about_feedback_desc),
                onClick = { uriHandler.openUri(feedbackMailto(feedbackSubject)) },
            )

            if (System.currentTimeMillis() >= DOWNLOADER_VISIBLE_FROM) {
                Spacer(Modifier.height(NaqiTokens.space5))
                SectionHeader(stringResource(R.string.about_eyebrow_downloader))
                NaqiCard { DownloaderCard() }
            }

            Spacer(Modifier.height(NaqiTokens.space5))
            SectionHeader(stringResource(R.string.pick_diag_title))
            NaqiCard { Diagnostics(smoke) }

            Spacer(Modifier.height(NaqiTokens.space5))
            SectionHeader(stringResource(R.string.about_eyebrow_licenses))
            LinkCard(
                title = stringResource(R.string.about_repo_title),
                desc = stringResource(R.string.about_repo_desc),
                onClick = { uriHandler.openUri(REPO_URL) },
            )

            Spacer(Modifier.height(NaqiTokens.space3))
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

private const val REPO_URL = "https://github.com/haithamassoli/NaqiHalalVideoFilter"
private const val FEEDBACK_EMAIL = "haitham.b.assoli@gmail.com"

/**
 * A mailto link with the subject filled in and the app/device line under a gap for the user's text,
 * so a report arrives with the version it is about. mailto's own query, not ACTION_SENDTO extras:
 * every mail client reads the query; the extras are ignored by some.
 */
private fun feedbackMailto(subject: String): String {
    val body = "\n\n—\nNaqi ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
        "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"
    return "mailto:$FEEDBACK_EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"
}

/** A tappable card row: title and description, with action_open trailing. */
@Composable
private fun LinkCard(title: String, desc: String, onClick: () -> Unit) {
    NaqiCard(contentPadding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                // runCatching: a device with no browser or mail app must not take the app down on a tap.
                .clickable { runCatching(onClick) }
                .padding(NaqiTokens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(NaqiTokens.space3))
            Text(
                stringResource(R.string.action_open),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The downloader section is hidden until 2026-08-28 (2026-08-14 + two weeks).
 *
 * ponytail: a date constant, not a build flag or a remote toggle — the hide has a known end and the
 * build that carries it will still be installed then. Delete the constant and its `if` to bring the
 * section back for good.
 */
private const val DOWNLOADER_VISIBLE_FROM = 1_787_875_200_000L

/**
 * yt-dlp's installed version and the button that replaces it with the current nightly release.
 *
 * Its own composable so that the state and the version read cost nothing while the section is
 * hidden — see [DOWNLOADER_VISIBLE_FROM].
 */
@Composable
private fun DownloaderCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Null until read; the version only exists once an update has run at least once (it is written by
    // the updater, not read out of the bundled zipapp).
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(context) }
    }

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
        shape = NaqiTokens.shapeButton,
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

/**
 * Mirrors the system's battery-optimization exemption for Naqi — see [Battery]. The state lives in the
 * system, so it is re-read on every resume rather than remembered: the user flips it in a system screen.
 */
@Composable
private fun BatteryToggle() {
    val context = LocalContext.current
    var unrestricted by remember { mutableStateOf(Battery.unrestricted(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = Battery.unrestricted(context)
        onPauseOrDispose {}
    }
    ToggleTile(
        title = stringResource(R.string.battery_setting_title),
        desc = stringResource(R.string.battery_setting_desc),
        checked = unrestricted,
        onCheckedChange = { on -> if (on) Battery.request(context) else Battery.openSettings(context) },
    )
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
