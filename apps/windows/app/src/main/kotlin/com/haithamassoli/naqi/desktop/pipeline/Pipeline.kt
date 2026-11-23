package com.haithamassoli.naqi.desktop.pipeline

import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.model.FilterOps
import java.io.File

// Owner: integration (after media, audio and vision land). Contract in apps/windows/SEAMS.md.

/**
 * One job. [ops] is core's FilterOps, the same type Android queues, so options mean the same on both. Body
 * modes (`bodyBlur`, `bodyPrecise`) are not on Windows yet and are ignored; the UI never sets them.
 */
data class JobOptions(val input: File, val output: File, val ops: FilterOps)

/** What a running job is doing, for the UI's label (Android's stage_* strings). */
enum class Stage { ANALYZE, MUSIC, RENDER }

object Pipeline {
    /**
     * Pass 1, when `ops.censorFaces`: `VideoDecoder.sample` at 10 fps → `FaceAnalyzer.onFrame` on every
     * frame and, when `ops.censorNsfw`, `NsfwClassifier` + `NsfwGate.fires(probs, ops.strictness)` on every
     * 2nd (5 fps). Then `NsfwGate.intervals(firings, durationMs or Long.MAX_VALUE when unknown)`, promoted
     * with `promoteFacesToFullFrame` when `ops.wholeFrameBlur`, and `Edl(intervals, faceTracks)`.
     */
    suspend fun analyze(input: File, meta: VideoMeta, ops: FilterOps, onProgress: (Int) -> Unit): Edl =
        TODO("integration")

    /**
     * Pass 2: `Renderer.render` with `AudioOut.Replace(music)` when music was removed, else `Keep`, and an
     * edit that runs `Censor.apply` with `edl.fullFrameAt(ptsMs)` and `edl.regionsAt(ptsMs)` mapped
     * `toStoredSpace(meta.rotationDegrees)`. A null edit (stream copy) when the EDL is empty.
     */
    suspend fun render(job: JobOptions, meta: VideoMeta, edl: Edl, music: File?, onProgress: (Int) -> Unit): Unit =
        TODO("integration")

    /** The whole job: probe, analyze, remove music, render. Cancel the coroutine to cancel the job. */
    suspend fun run(job: JobOptions, onProgress: (Stage, Int) -> Unit): Unit = TODO("integration")
}
