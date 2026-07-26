package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [blurAmount]/[grayscale] style both full-frame and face
 * censoring; [blurUnknownFaces] censors faces whose gender never resolves. [keepStems] selects which
 * demucs stems survive music removal — "vocals" keeps dialogue/singing only, "vocals_other" also keeps
 * SFX/ambience (melodic-music leakage tradeoff). Drums/bass are never kept.
 */
data class FilterOps(
    val removeMusic: Boolean = false,
    val censorWomen: Boolean = false,
    val strictness: Int = 50,
    val blurAmount: Int = 60,
    val grayscale: Boolean = false,
    val blurUnknownFaces: Boolean = false,
    val keepStems: String = "vocals", // "vocals" | "vocals_other"
) : java.io.Serializable {  // saved across rotation/process death by ui/NaqiApp
    val any: Boolean get() = removeMusic || censorWomen
}
