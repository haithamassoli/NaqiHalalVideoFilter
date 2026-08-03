package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [blurAmount]/[grayscale] style both full-frame and face
 * censoring. [keepStems] selects which demucs stems survive music removal — "vocals" keeps
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
    val keepStems: String = "vocals", // "vocals" | "vocals_other"
) : java.io.Serializable {  // saved across rotation/process death by ui/NaqiApp
    val any: Boolean get() = removeMusic || censorWomen
}
