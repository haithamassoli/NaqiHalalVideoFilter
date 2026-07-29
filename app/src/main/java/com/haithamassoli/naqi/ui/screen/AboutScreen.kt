package com.haithamassoli.naqi.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.ui.Eyebrow
import com.haithamassoli.naqi.ui.NaqiCard
import com.haithamassoli.naqi.ui.theme.NaqiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * About + open-source licences, and the only place the user can force a yt-dlp update.
 *
 * The attribution text is the repository's own `NOTICE`, copied into assets by the build rather than
 * retyped as a string resource — see `app/build.gradle.kts`. It is deliberately not translated: a
 * licence notice is a legal document, and a translated one would be a second, unreviewed version of it.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notice by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        notice = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("NOTICE").bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
    }

    // Null until read; the version only exists once an update has run at least once (it is written by
    // the updater, not read out of the bundled zipapp).
    var ytdlpVersion by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        ytdlpVersion = withContext(Dispatchers.IO) { Downloader.version(context) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, modifier = modifier) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NaqiTokens.gutter)
                .padding(top = NaqiTokens.space4, bottom = NaqiTokens.space7),
        ) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            Spacer(Modifier.height(NaqiTokens.space2))

            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(NaqiTokens.space1))
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(NaqiTokens.space1))
            Text(
                stringResource(R.string.about_license),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(NaqiTokens.space5))
            Eyebrow(stringResource(R.string.about_eyebrow_downloader))
            Spacer(Modifier.height(NaqiTokens.space2))
            NaqiCard {
                Text(
                    stringResource(R.string.about_ytdlp_version, ytdlpVersion ?: stringResource(R.string.about_ytdlp_unknown)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(NaqiTokens.space1))
                Text(
                    stringResource(R.string.about_ytdlp_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                updateResult?.let {
                    Spacer(Modifier.height(NaqiTokens.space1))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    enabled = !updating,
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
            Eyebrow(stringResource(R.string.about_eyebrow_licenses))
            Spacer(Modifier.height(NaqiTokens.space2))
            NaqiCard {
                Text(
                    notice.ifBlank { stringResource(R.string.about_notice_missing) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
