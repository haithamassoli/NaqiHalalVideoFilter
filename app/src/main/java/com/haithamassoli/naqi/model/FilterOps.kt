package com.haithamassoli.naqi.model

/** The two independent operations. At least one must be selected to start a job. */
data class FilterOps(
    val removeMusic: Boolean = false,
    val censorWomen: Boolean = false,
) {
    val any: Boolean get() = removeMusic || censorWomen
}
