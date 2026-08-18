package com.haithamassoli.naqi

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import com.haithamassoli.naqi.media.displayName
import com.haithamassoli.naqi.ui.screen.ShareSheet
import com.haithamassoli.naqi.ui.screen.Shared
import com.haithamassoli.naqi.ui.theme.NaqiTheme
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.Queue

/** Translucent share target: the sheet appears over the sending app and closes back to it. */
@SuppressLint("UnsafeIntentLaunch") // Shared content is scheme-checked and granted only to packageName.
class ShareActivity : ComponentActivity() {

    private var shared by mutableStateOf<Shared?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        shared = sharedOf(intent) ?: return finish()
        setContent {
            NaqiTheme {
                shared?.let {
                    ShareSheet(shared = it, onDismiss = ::finish, onQueued = ::finish)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedOf(intent)?.let { shared = it } ?: finish()
    }

    private fun sharedOf(intent: Intent): Shared? {
        if (intent.action != Intent.ACTION_SEND) return null
        val type = intent.type.orEmpty()

        if (type.startsWith("video/")) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?: return null
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
            if (runCatching {
                grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isFailure) {
                toast(R.string.err_unreadable)
                return null
            }
            return Shared.LocalFile(uri, displayName(uri) ?: uri.lastPathSegment)
        }

        if (!type.startsWith("text/")) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        val url = URL_IN_TEXT.find(text)?.value
        if (url == null) {
            toast(R.string.share_no_url)
            return null
        }
        if (Queue.isActive(this, url) || JobController.isQueued(this, url)) {
            toast(R.string.share_already_queued)
            return null
        }
        return Shared.Link(url)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}

/**
 * First http(s) URL in ordinary text, excluding sentence punctuation at the end — people paste
 * "look at this https://…", not a bare URL.
 *
 * Shared with the pick screen's link field on purpose: both are the same trust boundary, and two
 * copies would mean fixing one for a new URL shape and silently leaving the other stricter.
 */
internal val URL_IN_TEXT =
    Regex("""https?://[\w\-]+(\.[\w\-]+)+([\w\-.,@?^=%&:/~+#]*[\w\-@?^=%&/~+#])?""")
