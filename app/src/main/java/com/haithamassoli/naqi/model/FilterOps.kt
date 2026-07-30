package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [blurAmount]/[grayscale] style both full-frame and face
 * censoring; [blurUnknownFaces] censors faces whose gender never resolves; [perRegionNsfw] blurs only
 * the body regions NudeNet localizes inside a long flagged run instead of the whole frame. [keepStems]
 * selects which demucs stems survive music removal — "vocals" keeps dialogue/singing only,
 * "vocals_other" also keeps SFX/ambience (melodic-music leakage tradeoff). Drums/bass are never kept.
 *
 * ponytail: [perRegionNsfw] is a PRD non-goal ("superseded by whole-frame") shipped behind an opt-in
 * flag that fails safe back to whole-frame — see `analysis.NsfwRegions` for the invariant. Promote it
 * to a normal option, or delete it, once QA has a box hit-rate from the `covered=` counter on
 * qa-assets women-music-3min.
 */
data class FilterOps(
    val removeMusic: Boolean = false,
    val censorWomen: Boolean = false,
    val strictness: Int = 50,
    val blurAmount: Int = 60,
    val grayscale: Boolean = false,
    val blurUnknownFaces: Boolean = false,
    val perRegionNsfw: Boolean = false,
    val keepStems: String = "vocals", // "vocals" | "vocals_other"
) : java.io.Serializable {  // saved across rotation/process death by ui/NaqiApp
    val any: Boolean get() = removeMusic || censorWomen
}
