package com.haithamassoli.naqi.publish

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.audio.AudioPipeline
import com.haithamassoli.naqi.audio.ConcatAudio
import com.haithamassoli.naqi.audio.MuxOut
import com.haithamassoli.naqi.audio.canMuxVideo
import com.haithamassoli.naqi.audio.concatAudio
import com.haithamassoli.naqi.audio.Remux
import com.haithamassoli.naqi.media.requireTrackIndex
import com.haithamassoli.naqi.work.Publish
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.File
import kotlinx.coroutines.withContext

/**
 * Cuts a finished video into parts a [PublishPreset] accepts and shares them.
 *
 * Parts are sample copies cut on keyframes — seconds for a film, no quality loss — so a part usually
 * ends a little before the limit. They land in the gallery under `Movies/Naqi/Parts`, named
 * `<video>-<preset id>-<n>.mp4` (`.webm` for a WebM source), which is how [existingParts] finds them again after the sheet closes.
 */
object Splitter {

    /** Under `Movies/`. The library screen leaves this folder out: parts are not new videos. */
    const val PARTS_DIR = "Naqi/Parts"

    /**
     * Parts aim this far under the limit. A cut exactly at the limit still comes out a hair over it —
     * the last audio frame ends after the last video frame — and on a S23 a 90.001 s part made WhatsApp
     * say "Video trimmed to first 90 seconds". Half a second covers any audio frame with room to spare.
     */
    private const val HEADROOM_US = 500_000L

    private const val MIME_WEBM = "video/webm"

    data class Part(val uri: Uri, val durationMs: Long)

    /** Parts already made for [baseName] under [preset], in order; empty when none. */
    fun existingParts(context: Context, baseName: String, preset: PublishPreset): List<Part> = runCatching {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = Regex(Regex.escape("$baseName-${preset.id}-") + """(\d+)\.(mp4|webm)""")
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf("${Environment.DIRECTORY_MOVIES}/$PARTS_DIR/"),
            null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val n = name.matchEntire(c.getString(1))?.groupValues?.get(1)?.toInt() ?: continue
                    add(n to Part(ContentUris.withAppendedId(collection, c.getLong(0)), c.getLong(2)))
                }
            }.sortedBy { it.first }.map { it.second }
        }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Writes every part of [source] for [preset]. All or nothing: a failure or cancel part-way deletes
     * the parts already written, so the gallery never holds half a set.
     */
    suspend fun split(context: Context, source: Uri, baseName: String, preset: PublishPreset): List<Part> =
        withContext(Dispatchers.IO) {
            val maxMs = requireNotNull(preset.maxSegmentMs) { "${preset.id} has no limit to split to" }
            val (sync, durationUs) = keyframes(context, source)
            val starts = cutPoints(sync, durationUs, maxMs * 1000 - HEADROOM_US)
            // MP4 whenever it can carry the video (every filtered output; AV1 from API 34), its audio
            // transcoded to AAC once when MP4 cannot carry that (Opus). Otherwise VP8/VP9: WebM, which
            // takes Opus/Vorbis as they are. AV1 below API 34 fits neither and fails with the toast.
            val webm = !canMuxVideo(context, source)
            val (ext, mime) = if (webm) "webm" to MIME_WEBM else "mp4" to Publish.MIME_MP4
            val job = coroutineContext.job
            val made = mutableListOf<Uri>()
            val audioM4a = File(context.cacheDir, "publish-audio.m4a")
            try {
                val transcoded = !webm && concatAudio(context, source) == ConcatAudio.TRANSCODE
                if (transcoded) AudioPipeline.transcodeToAac(context, source, audioM4a) { job.isCancelled }
                starts.forEachIndexed { i, start ->
                    ensureActive()
                    val end = starts.getOrElse(i + 1) { Long.MAX_VALUE }
                    made += Publish.muxedVideo(context, "$baseName-${preset.id}-${i + 1}.$ext", PARTS_DIR, mime) { fd ->
                        Remux.copyRange(context, source, start, end, MuxOut.ToFd(fd), webm, audioM4a.takeIf { transcoded })
                    }
                }
            } catch (t: Throwable) {
                delete(context, made)
                throw t
            } finally {
                audioM4a.delete()
            }
            existingParts(context, baseName, preset)
        }

    /** Our own rows, so no permission prompt — except after a reinstall, where the row is simply left. */
    fun delete(context: Context, uris: List<Uri>) {
        uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    /**
     * Straight to the preset's app when one is installed, else the chooser. Several parts go in one
     * share only when the preset says the platform takes that; otherwise the sheet shares them one by one.
     */
    fun share(context: Context, preset: PublishPreset, uris: List<Uri>) {
        val intent = (
            if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            )
            // The file's own type: a WebM sent as video/mp4 is a lie the receiving app may act on.
            .setType(context.contentResolver.getType(uris[0]) ?: preset.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ponytail: try/catch instead of resolveActivity — a renamed activity still falls through to the next target.
        for (target in preset.targets) {
            val component = ComponentName.unflattenFromString(target)
            try {
                context.startActivity(
                    if (component != null) Intent(intent).setComponent(component) else Intent(intent).setPackage(target),
                )
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.jobs_share_chooser_title)))
        }
    }

    /** Every video keyframe time (µs, ascending) and the last sample's time, which is the duration. */
    private fun keyframes(context: Context, uri: Uri): Pair<List<Long>, Long> {
        val ext = MediaExtractor()
        try {
            ext.setDataSource(context, uri, null)
            ext.selectTrack(ext.requireTrackIndex("video/"))
            val sync = mutableListOf<Long>()
            var last = 0L
            while (true) {
                val t = ext.sampleTime
                if (t < 0) break
                if (ext.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) sync += t
                if (t > last) last = t
                ext.advance()
            }
            return sync.sorted() to last
        } finally {
            runCatching { ext.release() }
        }
    }
}

/**
 * Part start times (µs): 0, then the latest keyframe in [syncUs] that keeps each part within [maxUs].
 * Cutting only on keyframes is what keeps a split a copy rather than a re-encode.
 *
 * ponytail: a keyframe gap longer than [maxUs] cannot be honoured — that part runs long and the platform
 * trims it. Only an exact-length cut would fix it, and that means re-encoding.
 */
internal fun cutPoints(syncUs: List<Long>, durationUs: Long, maxUs: Long): List<Long> {
    val starts = mutableListOf(0L)
    while (durationUs - starts.last() > maxUs) {
        val start = starts.last()
        starts += syncUs.lastOrNull { it > start && it <= start + maxUs }
            ?: syncUs.firstOrNull { it > start }
            ?: break
    }
    return starts
}
