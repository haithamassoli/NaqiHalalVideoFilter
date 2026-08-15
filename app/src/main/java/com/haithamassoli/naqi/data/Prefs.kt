package com.haithamassoli.naqi.data

import android.content.Context
import com.haithamassoli.naqi.download.Downloader
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
    private const val KEY_CENSOR_NSFW = "censor_nsfw"

    /** Read-only legacy: what [KEY_CENSOR_WHO] replaced. Still read so an upgrade keeps the last pick. */
    private const val KEY_CENSOR_WOMEN = "censor_women"
    private const val KEY_QUALITY = "quality"
    // New key forces one NIGHTLY check after upgrading from the former stable channel.
    private const val KEY_LAST_UPDATE_CHECK = "last_nightly_update_check"

    /** Weekly, per the PRD. Long enough not to nag, short enough to beat a broken extractor. */
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

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
            censorNsfw = getBoolean(KEY_CENSOR_NSFW, true),
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

    fun quality(context: Context): Downloader.Quality =
        Downloader.Quality.of(prefs(context).getString(KEY_QUALITY, null))

    fun save(context: Context, ops: FilterOps, quality: Downloader.Quality) {
        prefs(context).edit()
            .putBoolean(KEY_REMOVE_MUSIC, ops.removeMusic)
            .putString(KEY_CENSOR_WHO, ops.censorWho)
            .putBoolean(KEY_CENSOR_NSFW, ops.censorNsfw)
            .putString(KEY_QUALITY, quality.name)
            .apply()
    }

    /** Has it been a week since the last yt-dlp update check? */
    fun updateDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_UPDATE_CHECK, 0L) > UPDATE_INTERVAL_MS

    fun markUpdateChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
