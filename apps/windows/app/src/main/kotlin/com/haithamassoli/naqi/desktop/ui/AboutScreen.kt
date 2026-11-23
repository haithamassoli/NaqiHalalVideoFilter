package com.haithamassoli.naqi.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import com.haithamassoli.naqi.desktop.Models
import java.io.File
import java.net.URLEncoder

private const val REPO_URL = "https://github.com/haithamassoli/NaqiHalalVideoFilter"
private const val FEEDBACK_EMAIL = "haitham.b.assoli@gmail.com"

/** jpackage's launcher sets this; `./gradlew :app:run` does not. */
private val appVersion = System.getProperty("jpackage.app-version") ?: "dev"

/**
 * Android's `AboutScreen` without the phone-only settings: version and licence, contact, which models this
 * install found, and the open-source notices. The notices are collapsed by default: the obligation is that
 * they be reachable, not unavoidable.
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    var showNotice by remember { mutableStateOf(false) }

    NaqiScreen(title = s.aboutTitle, onBack = onBack) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(NaqiIconBitmap, contentDescription = null, modifier = Modifier.size(72.dp).clip(NaqiTokens.shapeButton))
            Spacer(Modifier.height(NaqiTokens.space3))
            Text(s.tagline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(NaqiTokens.space2))
            Text(s.aboutVersion(appVersion), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(s.aboutLicense, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(NaqiTokens.space6))
        SectionHeader(s.eyebrowContact)
        LinkCard(s.feedbackTitle, s.feedbackDesc) { uriHandler.openUri(feedbackMailto(s.feedbackSubject)) }

        Spacer(Modifier.height(NaqiTokens.space5))
        SectionHeader(s.diagTitle)
        NaqiCard { ModelLines() }

        Spacer(Modifier.height(NaqiTokens.space5))
        SectionHeader(s.eyebrowLicenses)
        LinkCard(s.repoTitle, s.repoDesc) { uriHandler.openUri(REPO_URL) }
        Spacer(Modifier.height(NaqiTokens.space3))
        NaqiCard(contentPadding = 0.dp) {
            Row(
                Modifier.fillMaxWidth().clickable { showNotice = !showNotice }.padding(NaqiTokens.space4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s.noticesTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(if (showNotice) s.actionHide else s.actionShow, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            if (showNotice) {
                // English legal text keeps LTR even inside the Arabic layout.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SelectionContainer {
                        Text(
                            NOTICE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = NaqiTokens.space4, end = NaqiTokens.space4, bottom = NaqiTokens.space4),
                        )
                    }
                }
            }
        }
    }
}

/** One line per bundled model: what to read out when an install misbehaves. */
@Composable
private fun ModelLines() {
    val s = LocalStrings.current
    val cs = MaterialTheme.colorScheme
    // Models.dir() throws when no models directory is configured at all; that is "all missing" here.
    val dir = remember { runCatching { Models.dir() }.getOrNull() }
    listOf(Models.YUNET, Models.GENDERAGE, Models.NSFW, Models.YAMNET, Models.HTDEMUCS).forEach { name ->
        val ok = dir != null && File(dir, name).isFile
        Text(
            "${if (ok) "✓" else "✗"} ${name.removeSuffix(".onnx")} · ${if (ok) s.modelPresent else s.modelMissing}",
            style = MaterialTheme.typography.bodySmall,
            color = if (ok) cs.onSurface else cs.error,
        )
    }
}

@Composable
private fun LinkCard(title: String, desc: String, onClick: () -> Unit) {
    val s = LocalStrings.current
    NaqiCard(contentPadding = 0.dp) {
        Row(
            // runCatching: a machine with no browser or mail client must not take the app down on a click.
            Modifier.fillMaxWidth().clickable { runCatching(onClick) }.padding(NaqiTokens.space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(NaqiTokens.space3))
            Text(s.actionOpen, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Subject filled in and the app/OS line under a gap, so a report arrives with the version it is about. */
private fun feedbackMailto(subject: String): String {
    fun enc(v: String) = URLEncoder.encode(v, Charsets.UTF_8).replace("+", "%20")
    val body = "\n\n—\nNaqi $appVersion · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · ${System.getProperty("os.arch")}"
    return "mailto:$FEEDBACK_EMAIL?subject=${enc(subject)}&body=${enc(body)}"
}
