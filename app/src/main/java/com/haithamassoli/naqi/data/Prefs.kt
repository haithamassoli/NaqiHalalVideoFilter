package com.haithamassoli.naqi.data

import android.content.Context
import com.haithamassoli.naqi.model.FilterOps

/**
 * What the share sheet was set to last time.
 *
 * The sheet always opens — there is no zero-tap path — so the only thing that makes repeated shares
 * bearable is that it opens already set the way the user last left it.
 *
 * ponytail: `SharedPreferences`, not the PRD's "one JSON file". It IS one file, the platform already
 * does the atomic write and the background flush, and it costs no serialization code at all. Six
 * scalars do not need a schema. Revisit if this ever has to hold a list.
 */
object Prefs {

    private const val FILE = "naqi_share_prefs"

    private const val KEY_REMOVE_MUSIC = "remove_music"
    private const val KEY_CENSOR_WHO = "censor_who"

    /** Read-only legacy: what [KEY_CENSOR_WHO] replaced. Still read so an upgrade keeps the last pick. */
    private const val KEY_CENSOR_WOMEN = "censor_women"
    private const val KEY_APP_UPDATE_CHECK = "app_update_check"
    private const val KEY_APP_UPDATE_SKIPPED = "app_update_skipped"
    private const val KEY_APP_UPDATE_ID = "app_update_download_id"
    private const val KEY_APP_UPDATE_VERSION = "app_update_version"

    /**
     * Daily. It costs a single unauthenticated GET, and the thing it catches — a release that fixes
     * something — is worth knowing about sooner rather than later.
     */
    private const val APP_UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** No download in flight. -1 is safe: [android.app.DownloadManager] ids start at 1. */
    const val NO_DOWNLOAD = -1L

    /**
     * Last-used ops. Defaults to both filters on — the reason someone installed Naqi — which is why
     * neither key falls back to [FilterOps]'s own defaults (`FilterOps.kt:30-34`).
     *
     * The legacy key is read only when it is actually there: an upgrade keeps whatever the old boolean
     * said, a fresh install gets [FilterOps.DEFAULT_WHO].
     */
    fun ops(context: Context): FilterOps = with(prefs(context)) {
        FilterOps(
            removeMusic = getBoolean(KEY_REMOVE_MUSIC, true),
            censorWho = FilterOps.whoOrNull(getString(KEY_CENSOR_WHO, null))
                ?: if (contains(KEY_CENSOR_WOMEN)) FilterOps.whoFromLegacy(getBoolean(KEY_CENSOR_WOMEN, true))
                else FilterOps.DEFAULT_WHO,
        )
    }

    /**
     * The last *real* Who pick, for the two places that turn censoring back on and must not answer the
     * question themselves: the faces toggle on step 1 and the share sheet's. Never [FilterOps.NONE] —
     * "off" is the toggle's state, not a choice of whom to cover.
     */
    fun lastWho(context: Context): String =
        FilterOps.whoOrNull(prefs(context).getString(KEY_CENSOR_WHO, null))
            ?.takeIf { it != FilterOps.NONE } ?: FilterOps.DEFAULT_WHO

    /** Remembers a Who pick made in the main flow, which has no [save] of its own to ride along on. */
    fun saveWho(context: Context, who: String) {
        if (who != FilterOps.NONE) prefs(context).edit().putString(KEY_CENSOR_WHO, who).apply()
    }

    fun save(context: Context, ops: FilterOps) {
        prefs(context).edit()
            .putBoolean(KEY_REMOVE_MUSIC, ops.removeMusic)
            .putString(KEY_CENSOR_WHO, ops.censorWho)
            .apply()
    }

    /** Has it been a day since the last GitHub release check? */
    fun appUpdateDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_APP_UPDATE_CHECK, 0L) > APP_UPDATE_INTERVAL_MS

    fun markAppUpdateChecked(context: Context) {
        prefs(context).edit().putLong(KEY_APP_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * The version the user dismissed the update card for, if any. Stored as the version rather than
     * a boolean so the card comes back on its own for the *next* release — dismissing means "not
     * this one", not "never again".
     */
    fun appUpdateSkipped(context: Context): String? = prefs(context).getString(KEY_APP_UPDATE_SKIPPED, null)

    fun skipAppUpdate(context: Context, version: String) {
        prefs(context).edit().putString(KEY_APP_UPDATE_SKIPPED, version).apply()
    }

    /** The in-flight APK download, so a cold start re-attaches to it instead of offering it again. */
    fun appUpdateDownloadId(context: Context): Long = prefs(context).getLong(KEY_APP_UPDATE_ID, NO_DOWNLOAD)

    fun appUpdateDownloadVersion(context: Context): String? =
        prefs(context).getString(KEY_APP_UPDATE_VERSION, null)

    fun setAppUpdateDownload(context: Context, id: Long, version: String?) {
        prefs(context).edit()
            .putLong(KEY_APP_UPDATE_ID, id)
            .putString(KEY_APP_UPDATE_VERSION, version)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
