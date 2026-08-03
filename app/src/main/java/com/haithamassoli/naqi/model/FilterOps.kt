package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [solidColor]/[blurAmount]/[grayscale] style both full-frame
 * and face censoring. [keepStems] selects which demucs stems survive music removal — "vocals" keeps
 * dialogue/singing only, "vocals_other" also keeps SFX/ambience (melodic-music leakage tradeoff).
 * Drums/bass are never kept.
 *
 * Face censoring is [censorWho], one of [NONE] · [EVERYONE] · [WOMEN] · [MEN]. It was a `censorWomen`
 * boolean until this rename, and that name had been a misnomer since plan-v2 §5.4 deleted the gender
 * vote: `FaceTracker` has censored *every* detected face ever since (`FaceTracker.kt:11-36`). So the
 * rename is the name catching up with the behaviour, not a behaviour change — [EVERYONE] is byte for
 * byte what `true` did. [WOMEN]/[MEN] need a face classifier the app does not have and that has not
 * passed its go/no-go bar yet (plan-censor-who §3); they parse and round-trip from day one so the four
 * wire formats never change again, but nothing offers them.
 *
 * `blurUnknownFaces` was removed with the gender vote (plan-v2 §5.4): every detected face is censored
 * now, so there is no "unknown" bucket for it to open. Dropping the field also drops it from the
 * `QueuedWorker` wire mapping and from `queue.json`; both readers default absent keys, so a queue file
 * written by an older build still loads.
 */
data class FilterOps(
    val removeMusic: Boolean = false,
    /**
     * Which faces get covered. A String like [keepStems], not an enum, so all four serializers
     * (`queue.json`, WorkManager `Data`, `Prefs`, the debug intent) take it unchanged — see [whoOrNull]
     * for the read side.
     *
     * **Defaults to [NONE], not [EVERYONE].** `FilterOps()` means "nothing picked yet" and must keep
     * `any == false`: `Queue.kt:56` defaults an item's ops to it, `ui/NaqiApp.kt:37` seeds the pick
     * screen from it, and `EtaTest.kt:62` asserts a no-op job estimates 0 ms. The entry points that
     * *should* open with censoring on default it themselves (`Prefs.kt:54`, `MainActivity.kt:208`).
     */
    val censorWho: String = NONE,
    val strictness: Int = 50,
    val blurAmount: Int = 60,
    val grayscale: Boolean = false,
    /**
     * Solid fill instead of blur. **0 means blur** — every offered swatch is opaque, so a zero alpha
     * cannot be a real choice and one field carries the mode as well as the colour, across all four
     * places [FilterOps] is serialized. Otherwise an opaque ARGB colour; [blurAmount]/[grayscale] are
     * dead while it is set.
     */
    val solidColor: Int = BLUR,
    val keepStems: String = "vocals", // "vocals" | "vocals_other"
) : java.io.Serializable {  // saved across rotation/process death by ui/NaqiApp

    /** The old `censorWomen`, verbatim: the pipeline branches on *whether*, never on *which*. */
    val censorFaces: Boolean get() = censorWho != NONE

    val any: Boolean get() = removeMusic || censorFaces

    companion object {
        /** [censorWho]'s four states. Persisted verbatim, so these literals are a wire format. */
        const val NONE = "none"
        const val EVERYONE = "everyone"
        const val WOMEN = "women"
        const val MEN = "men"

        /** Legacy `censorWomen` boolean -> the new field; `true` censored every detected face. */
        fun whoFromLegacy(censorWomen: Boolean): String = if (censorWomen) EVERYONE else NONE

        /**
         * Wire value -> field, for the four readers that each fall back to the legacy boolean when the
         * new key is absent, so an in-flight `queue.json` or a stale adb script keeps working
         * (plan-censor-who §1.1).
         *
         * Absent *and* empty ⇒ null, because the readers disagree on which one an absent key produces:
         * `JSONObject.optString` returns "", `Data.getString`/`SharedPreferences.getString`/
         * `Intent.getStringExtra` return null.
         *
         * Everything else is untrusted — an adb `--es` extra reaches here — and unrecognised resolves
         * to [EVERYONE]: censoring is this app's safe direction (plan-censor-who §4.2), and a typo that
         * quietly stopped censoring is the one failure the user would not see.
         */
        fun whoOrNull(raw: String?): String? = when (val who = raw?.trim()?.lowercase().orEmpty()) {
            "" -> null
            NONE, EVERYONE, WOMEN, MEN -> who
            else -> EVERYONE
        }

        /** [solidColor]'s "no solid fill, blur instead" value. */
        const val BLUR = 0

        /** The offered fills, in swatch order. Opaque by construction — see [solidColor]. */
        val SOLID_COLORS = listOf(
            0xFF9E9E9E.toInt(), // gray
            0xFF000000.toInt(), // black
            0xFFFFFFFF.toInt(), // white
            0xFF2C3E50.toInt(), // navy
            0xFF1E3A2F.toInt(), // green
        )
    }
}
