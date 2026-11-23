package com.haithamassoli.naqi.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization exemption: the one thing that lets a queued job start at full speed while Naqi
 * is in the background.
 *
 * On API 31+ a chained item that starts with the app out of sight is refused a foreground service, and
 * without one it runs as a plain background job — measured ~5x slower on an S23 (9.5 s vs 1.9 s per
 * separator chunk). An app the user exempted from battery optimization is allowed that start.
 *
 * There is no API to *revoke* the exemption, so turning it off opens the app's own settings page.
 */
object Battery {

    fun unrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** The system's one-tap "let this app run in the background?" dialog; the settings page if it is absent. */
    @SuppressLint("BatteryLife") // Long video jobs are this app's core function; see the class comment.
    fun request(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context)))
        }.onFailure { openSettings(context) }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context)))
        }
    }

    private fun packageUri(context: Context) = Uri.fromParts("package", context.packageName, null)
}
