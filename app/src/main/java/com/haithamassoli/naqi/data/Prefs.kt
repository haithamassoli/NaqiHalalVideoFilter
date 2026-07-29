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
    private const val KEY_CENSOR_WOMEN = "censor_women"
    private const val KEY_QUALITY = "quality"

    /** Last-used ops. Defaults to both filters on — the reason someone installed Naqi. */
    fun ops(context: Context): FilterOps = with(prefs(context)) {
        FilterOps(
            removeMusic = getBoolean(KEY_REMOVE_MUSIC, true),
            censorWomen = getBoolean(KEY_CENSOR_WOMEN, true),
        )
    }

    fun quality(context: Context): Downloader.Quality =
        Downloader.Quality.of(prefs(context).getString(KEY_QUALITY, null))

    fun save(context: Context, ops: FilterOps, quality: Downloader.Quality) {
        prefs(context).edit()
            .putBoolean(KEY_REMOVE_MUSIC, ops.removeMusic)
            .putBoolean(KEY_CENSOR_WOMEN, ops.censorWomen)
            .putString(KEY_QUALITY, quality.name)
            .apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
