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

    // ponytail: the self-updater's four `app_update_*` keys are gone with it, and are not migrated
    // away on upgrade — orphan scalars in a prefs file cost nothing and a migration would.

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
