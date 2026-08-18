package com.haithamassoli.naqi.download

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.work.FilterWorker
import com.haithamassoli.naqi.work.JobController
import com.haithamassoli.naqi.work.JobNotifications
import com.haithamassoli.naqi.work.Preflight
import com.haithamassoli.naqi.work.Publish
import com.haithamassoli.naqi.work.Queue
import com.haithamassoli.naqi.work.QueuedWorker
import com.haithamassoli.naqi.work.filterOps
import kotlinx.coroutines.CancellationException

/**
 * Fetch one URL into quarantine, then hand it to the filter pipeline.
 *
 * Network-bound where [FilterWorker] is CPU-bound, which is the whole reason they are separate unique
 * work names: a download and a filter job may run at the same time, and neither should wait on the
 * other. FGS type `dataSync` for the same reason — on API 35+ that draws from a different 6 h/24 h
 * budget than filtering's `mediaProcessing`.
 *
 * **Nothing this worker writes is ever visible to the gallery.** The download lands in
 * [Downloader.quarantineDir] and only [FilterWorker] publishes it — except for the one case where the
 * user asked for no filters at all, which is published here and the quarantine dropped immediately.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : QueuedWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val quality = Downloader.Quality.of(inputData.getString(KEY_QUALITY))
        // Face censoring is meaningless on an audio-only item; the sheet hides it, and this is the half
        // that cannot be raced by a stale queue entry written before that rule existed. Dropped to NONE
        // rather than remembered, so `ops.any` can go false and the publish-as-is path below can fire.
        val ops = inputData.filterOps()
            .let { if (quality == Downloader.Quality.AUDIO) it.copy(censorWho = FilterOps.NONE) else it }
        val title = inputData.getString(KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: applicationContext.getString(R.string.stage_downloading)

        // Reclaim abandoned quarantines before filling the disk with our own, same rule as JobStore.
        runCatching { Downloader.sweep(applicationContext) }

        // Refuse before a single byte is fetched when the size is known. The sheet checks this too, so
        // the user gets the refusal before the item is even queued; this is the half that still holds
        // when the queue is drained hours later on a disk that has filled in the meantime.
        // Size 0 = unknown, so this still enforces the fixed 2 GiB floor; onSpaceCheck below is the net.
        val knownBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        Preflight.checkSpaceForDownload(applicationContext, knownBytes, ops)?.let {
            Log.w(TAG, "refusing download: $knownBytes bytes will not fit")
            return fail(it)
        }

        // error = null: a re-run means whatever failed last time is no longer what is happening. Without
        // this a transient failure (network drop) that later succeeds leaves its message on the item,
        // and the row renders a stale "Download failed" under a green DONE tick.
        queued { it.copy(state = Queue.State.DOWNLOADING, error = null) }
        setForeground(foregroundInfo(title, 0))
        try {
            val file = Downloader.download(
                applicationContext, url, quality, processId = id.toString(),
                // The PRD's "abort early once yt-dlp reports total bytes", without parsing yt-dlp's
                // progress lines: the only thing the check actually needs is whether the volume we are
                // filling still has room, and that is a syscall.
                // ponytail: a bare headroom test, not a projected final size. It catches the real failure
                // (the disk fills) at the cost of catching it later than a projection would. Project from
                // (bytes on disk ÷ percent) if aborting at 90 % ever proves too late to be useful.
                onSpaceCheck = { applicationContext.noBackupFilesDir.usableSpace > SPACE_FLOOR },
            ) { pct, etaSeconds ->
                setProgressAsync(
                    workDataOf(
                        FilterWorker.KEY_PROGRESS to pct,
                        FilterWorker.KEY_STAGE to applicationContext.getString(R.string.stage_downloading),
                        FilterWorker.KEY_ETA_MS to etaSeconds.coerceAtLeast(0L) * 1000L,
                    ),
                )
                setForegroundAsync(foregroundInfo(title, pct))
            }
            Log.i(TAG, "downloaded ${file.name} (${file.length()} bytes) for $url")

            val fileUri = Uri.fromFile(file)
            val downloadedTitle = file.nameWithoutExtension
            if (!ops.any) {
                // Nothing to filter: publish the original as-is and empty the quarantine. The only path
                // where a downloaded file reaches the gallery without passing through FilterWorker.
                val name = "${file.nameWithoutExtension}-naqi-${System.currentTimeMillis()}.${file.extension}"
                val audio = quality == Downloader.Quality.AUDIO
                val outputUri = if (audio) {
                    Publish.audio(applicationContext, file, name) { isStopped }
                } else {
                    Publish.video(applicationContext, file, name) { isStopped }
                }
                Downloader.discard(applicationContext, fileUri)
                queued {
                    it.copy(
                        title = it.title ?: downloadedTitle,
                        state = Queue.State.DONE,
                        outputUri = outputUri.toString(),
                    )
                }
                JobNotifications.done(
                    applicationContext, name, outputUri.toString(), null,
                    if (audio) Publish.MIME_M4A else Publish.MIME_MP4,
                )
                return Result.success(
                    workDataOf(
                        FilterWorker.KEY_OUTPUT_NAME to name,
                        FilterWorker.KEY_OUTPUT_URI to outputUri.toString(),
                    ),
                )
            }

            // Cross-name append: the filter chain is a different unique work name, so this never races
            // with our own chain and the queue keeps draining while this job's filter waits its turn.
            queued {
                it.copy(
                    title = it.title ?: downloadedTitle,
                    state = Queue.State.PENDING_FILTER,
                    sourceUri = fileUri.toString(),
                )
            }
            JobController.start(
                applicationContext, ops, fileUri.toString(),
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                queueId = queueId,
            )
            return Result.success()
        } catch (c: CancellationException) {
            // The .part file is deliberately kept: yt-dlp resumes it byte-for-byte on the next attempt,
            // and a cancelled download is the case most likely to be retried.
            Log.i(TAG, "download stopped for $url")
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "download failed for $url", t)
            val message = messageFor(t)
            JobNotifications.downloadFailed(applicationContext, applicationContext.getString(message))
            return fail(message)
        }
    }

    /**
     * Downloads fail for reasons filtering never sees — a dead extractor, a geo-block, no network — so
     * they get their own small taxonomy rather than [Preflight.messageFor]'s codec-shaped one.
     */
    private fun messageFor(t: Throwable): Int {
        val text = generateSequence(t) { it.cause }.mapNotNull { it.message }.joinToString(" ").lowercase()
        return when {
            "enospc" in text || "no space left" in text || "space" in text -> Preflight.LOW_SPACE_DOWNLOAD
            "unsupported url" in text || "unable to extract" in text ||
                "no video formats" in text -> R.string.err_download_unsupported
            "unable to download" in text || "timed out" in text ||
                "connection" in text || "network" in text -> R.string.err_download_network
            else -> R.string.err_download_generic
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(applicationContext.getString(R.string.stage_downloading), 0)

    private fun foregroundInfo(title: String, progress: Int) =
        JobNotifications.downloadForegroundInfo(applicationContext, id, title, progress)

    companion object {
        const val KEY_URL = "url"
        const val KEY_QUALITY = "quality"
        const val KEY_TITLE = "title"

        /** `filesize_approx` from the sheet's `getInfo`; 0 when the extractor wouldn't say. */
        const val KEY_SIZE_BYTES = "size_bytes"

        /** Separate name from `naqi_filter_job`, so a download and a filter run concurrently. */
        const val UNIQUE_WORK = "naqi_download"

        /** Abort a running download below this much free space — the PRD's slack, minus the temps. */
        private const val SPACE_FLOOR = 2L * 1024 * 1024 * 1024

        private const val TAG = "DownloadWorker"
    }
}
