package com.haithamassoli.naqi.ml

// In core rather than Models.kt (Android-only, holds the ORT sessions) because NsfwGate indexes by it.

/** GantMan class order (alphabetical, index-locked). sfw = drawings+neutral; nsfw = the rest. */
val NSFW_CLASSES = listOf("drawings", "hentai", "neutral", "porn", "sexy")
