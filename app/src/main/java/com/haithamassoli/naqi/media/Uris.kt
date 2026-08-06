package com.haithamassoli.naqi.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/**
 * "What is this Uri called?" — asked by the picker, the share handler and the worker that names the
 * published file. It was three hand-rolled copies of the same projection + cursor dance, and the dance
 * has three ways to go subtly wrong (a null cursor, an empty result, a null column).
 *
 * Only the query is shared. Each caller's fallback differs deliberately and stays at its call site: the
 * worker falls back to the `file://` path name then "video", the share handler to `lastPathSegment`, the
 * picker to null.
 */
fun Context.displayName(uri: Uri): String? = runCatching {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull()

/** True when the picked source is an audio file — the only op it can have is music removal. */
fun Context.isAudio(uri: Uri): Boolean = contentResolver.getType(uri)?.startsWith("audio/") == true

/**
 * Duration in ms, or 0 when nothing will say. Every video path takes this from `FrameSampler.probe`,
 * which opens the video track and so throws on an audio source — this is that source's only answer,
 * and without it the options screen's ETA and its long-job confirmation both go silent.
 */
fun Context.containerDurationMs(uri: Uri): Long = runCatching {
    MediaMetadataRetriever().use {
        it.setDataSource(this, uri)
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }
}.getOrNull() ?: 0L
