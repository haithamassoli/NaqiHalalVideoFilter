package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [solidColor]/[blurAmount]/[grayscale] style both full-frame
 * and face censoring. [keepStems] selects which demucs stems survive music removal — "vocals" keeps
 * dialogue/singing only, "vocals_other" also keeps SFX/ambience (melodic-music leakage tradeoff).
 * Drums/bass are never kept.
 *
 * `blurUnknownFaces` was removed with the gender vote (plan-v2 §5.4): every detected face is censored
 * now, so there is no "unknown" bucket for it to open. Dropping the field also drops it from the
 * `QueuedWorker` wire mapping and from `queue.json`; both readers default absent keys, so a queue file
 * written by an older build still loads.
 */
data class FilterOps(
    val removeMusic: Boolean = false,
    val censorWomen: Boolean = false,
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
    val any: Boolean get() = removeMusic || censorWomen

    companion object {
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
