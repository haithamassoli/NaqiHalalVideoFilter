package com.haithamassoli.naqi.model

/**
 * The two independent operations plus their censor tuning. At least one op must be selected.
 * [strictness] drives only the NSFW gate; [blurAmount]/[grayscale] style both full-frame and face
 * censoring; [blurUnknownFaces] censors faces whose gender never resolves. (Keep-stems is M2.)
 */
data class FilterOps(
    val removeMusic: Boolean = false,
    val censorWomen: Boolean = false,
    val strictness: Int = 50,
    val blurAmount: Int = 60,
    val grayscale: Boolean = false,
    val blurUnknownFaces: Boolean = false,
) {
    val any: Boolean get() = removeMusic || censorWomen
}
