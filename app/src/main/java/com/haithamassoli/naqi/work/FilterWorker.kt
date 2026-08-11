package com.haithamassoli.naqi.work

import android.app.ActivityManager
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import androidx.core.net.toUri
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.haithamassoli.naqi.BuildConfig
import com.haithamassoli.naqi.R
import com.haithamassoli.naqi.analysis.FaceGenderVoter
import com.haithamassoli.naqi.analysis.FaceTracker
import com.haithamassoli.naqi.analysis.FrameSampler
import com.haithamassoli.naqi.analysis.NRect
import com.haithamassoli.naqi.analysis.NsfwGate
import com.haithamassoli.naqi.analysis.VideoMeta
import com.haithamassoli.naqi.audio.AudioPipeline
import com.haithamassoli.naqi.audio.ConcatAudio
import com.haithamassoli.naqi.audio.ConcatPart
import com.haithamassoli.naqi.audio.MuxOut
import com.haithamassoli.naqi.audio.Remux
import com.haithamassoli.naqi.audio.TrackSource
import com.haithamassoli.naqi.audio.concatAudio
import com.haithamassoli.naqi.download.Downloader
import com.haithamassoli.naqi.edl.Edl
import com.haithamassoli.naqi.model.FilterOps
import com.haithamassoli.naqi.edl.FaceTrackEdl
import com.haithamassoli.naqi.edl.promoteFacesToFullFrame
import com.haithamassoli.naqi.media.displayName
import com.haithamassoli.naqi.media.firstTrackIndex
import com.haithamassoli.naqi.media.requireTrackIndex
import com.haithamassoli.naqi.ml.Infer
import com.haithamassoli.naqi.render.MAX_REGIONS
import com.haithamassoli.naqi.render.RenderPipeline
import com.haithamassoli.naqi.render.RenderSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max

/**
 * Filtering job. Up to two passes over one source video plus an audio pass, promoted to a foreground
 * service with staged progress and cancellation. Three job shapes:
 *
 * - **Censor-only** (M1's two passes): pass 1 (0..50) decodes at 10 fps — every frame feeds ML Kit face
 *   tracking, every 2nd frame (5 fps) the NSFW gate; firings become hysteresis censor intervals and
 *   every face track becomes a censored span (plan-v2 §5.4 removed the gender vote), together the
 *   [Edl]. Pass 2 (50..100) renders with [RenderPipeline] into a cache temp. Published directly (no mux).
 *   perf-plan Phase 2 dropped sampling to 5 fps and was REVERTED — see `docs/perf-plan.md`. The speedup
 *   in pass 1 is item 1.3's two overlaps, which do not touch which frames are sampled.
 * - **Music-only**: [AudioPipeline.removeMusic] strips music into a temp .m4a (1..97), then
 *   [Remux.mux] copies the ORIGINAL video track verbatim next to the new audio (97..99).
 * - **Combined**: analyze 0..8, render 8..17, separate 17..97, mux the pass-2 video + new audio.
 *
 * Every band above is a MEASURED share of the wall, not an even split — see [Eta.Bands] for the numbers
 * and for what the old even split did to the ETA on a feature-length job.
 *
 * The two branches of the combined and segmented shapes run CONCURRENTLY on a device with the memory
 * for it (`plan-v2` §5.8 S1) — see [branches] for why, and for the sequential fallback. The bands above
 * become shares of the bar rather than time slices; see [reportVideo]/[reportAudio].
 *
 * Every shape preflights the source ([Preflight]: readable, not DRM'd, has the tracks it needs, enough
 * free space) before any foreground work, and failures carry a per-cause message to the UI.
 * Every shape publishes into `Movies/Naqi` (MediaStore on API 29+, public dir + media scan below).
 * Cancel/failure delete every temp and any un-published MediaStore row; the ORT sessions and the face
 * detector always close. Cancellation propagates through the suspend calls untouched.
 */
class FilterWorker(ctx: Context, params: WorkerParameters) : QueuedWorker(ctx, params) {

    /** Soak instrumentation + the live ETA source (`long-film-plan.md` Phase 0). */
    private val stats = JobStats(ctx)

    /**
     * The tuning knobs, read once, through [filterOps] — the one place the wire format and its defaults
     * live. This used to spell the keys and defaults out again here (and each shape below re-read its
     * own subset before that), so [KEY_STRICTNESS]'s default existed in two files and the `censorWho`
     * legacy fallback in three. `DownloadWorker` already read them this way.
     */
    private val ops = inputData.filterOps()

    private val strictness = ops.strictness
    private val grayscale = ops.grayscale

    /** Non-zero replaces blur with a flat fill; see [FilterOps.solidColor] for why 0 means blur. */
    private val solidColor = ops.solidColor

    /**
     * Correctness item 7.3: `blurAmount = 0` with `grayscale = false` makes [CensorGlEffect] draw the
     * source pixels back unchanged, so the job spends a full render producing a copy of the input while
     * telling the user it censored it. On a safety product that is the worst possible silent failure.
     *
     * **Coerced rather than failed**, and the deciding reason is prosaic: a per-cause failure needs a
     * sentence the user can act on, `Preflight`'s taxonomy carries @StringRes ids, and `res/values*` is
     * not this change's to edit — so "fail" would mean failing a queued job with `err_generic`, which
     * tells nobody anything. Coercion delivers what the job promised. Read here rather than in the UI
     * because a queued job carries its own input data and must not be able to bypass the guard.
     *
     * Only the exact no-op combination is touched; every other slider position is the user's. A solid
     * fill covers the region on its own, so it takes the combination out of no-op territory too.
     */
    private val blurAmount = ops.blurAmount.let {
        if (it > 0 || grayscale || solidColor != FilterOps.BLUR) it else MIN_EFFECTIVE_BLUR
    }
    private val keepStems = ops.keepStems

    /**
     * WHICH faces get censored — one of [FilterOps]' four states (plan-censor-who §1.1). Every branch
     * of [doWork] still asks only *whether* (`!= NONE`); only [genderVoter] and the [FaceTracker]s look
     * at the value.
     */
    private val censorWho = ops.censorWho

    /** [FilterOps.wholeFrameBlur] — read here, applied once per EDL build in [promoteFacesToFullFrame]. */
    private val wholeFrameBlur = ops.wholeFrameBlur

    /**
     * Key for this job's working directory. Derived from the source and every option that changes the
     * OUTPUT — not from [getId] — so Phase 2's resume finds the same directory after process death, and
     * so a job restarted with different settings can never resume into work rendered under the old ones.
     *
     * These reads stay inline rather than using the vals above, even though they look identical. This
     * hash is a **persisted-state contract**: it names a directory that may already hold hours of
     * rendered segments waiting for a Resume tap. `getString(KEY_KEEP_STEMS)` hashes an absent key as
     * "null" where the val substitutes "vocals", so folding them in would change the key for that one
     * input and orphan exactly the work this key exists to find again. Not worth five lines.
     */
    private val jobKey by lazy {
        JobStore.keyOf(
            inputData.getString(KEY_INPUT_URI),
            inputData.getBoolean(KEY_REMOVE_MUSIC, false),
            // Back-compat read like doWork's, and hashed as the STRING since Phase C: "women" and "men"
            // now select different faces, so two jobs that differ only in Who produce different output
            // and must never resume into each other's segments. Hashed as a raw getBoolean it would
            // read false for every job written after the rename and stop encoding the censor op at all.
            //
            // This moves every existing key once and so orphans any resumable directory left by a
            // pre-Phase-C build. That is correct — such a directory holds segments rendered under
            // "censor every face", which a resumed "women" job would silently adopt as its verdict —
            // and harmless: the orphan re-analyzes from scratch and the 7-day sweep collects the old one.
            FilterOps.whoOrNull(inputData.getString(KEY_CENSOR_WHO))
                ?: FilterOps.whoFromLegacy(inputData.getBoolean(KEY_CENSOR_WOMEN, false)),
            inputData.getInt(KEY_STRICTNESS, FilterOps.DEFAULT_STRICTNESS),
            inputData.getInt(KEY_BLUR_AMOUNT, 60),
            inputData.getBoolean(KEY_GRAYSCALE, false),
            // Adding this moved every existing key once, orphaning any resumable directory left by a
            // pre-solid build — the same cost as the plan bump below, and unavoidable: a blurred segment
            // and a solid-filled one must never resume into each other.
            inputData.getInt(KEY_SOLID_COLOR, FilterOps.BLUR),
            // Same reason as the two above: a rect-blurred segment and a whole-frame one must never
            // resume into each other. Moves every existing key once, orphaning pre-whole-frame dirs.
            inputData.getBoolean(KEY_WHOLE_FRAME, false),
            inputData.getString(KEY_KEEP_STEMS),
            inputData.getString(KEY_FORCE_INTERVALS), // debug hook, but it does change the output
            // Not an input: a plan generation. Segment boundaries moved to sync samples in
            // long-film-followups.md item 2, so an `an-NNN.json` or `seg-NNN.mp4` left by an older build
            // describes a DIFFERENT time window under the same index. Resuming into it would fold analysis
            // into the wrong span and have Remux.concat place rendered frames at the wrong absolute time —
            // silently. Bumping this orphans that directory instead, and the 7-day sweep collects it.
            // plan2 -> plan3: plan-v2 §5.4 dropped the gender vote, so an `an-NNN.json` from an older
            // build holds only the tracks that voted FEMALE while a new segment holds every face. Mixing
            // the two on a resume would leave half the film censored under the old semantics. The
            // blurUnknownFaces input also left the hash in the same change, which moves the key anyway.
            // plan3 -> plan4: C1's two-tier music guard changes WHICH chunks a resumed `audio.json` says
            // were separated — persisted, straddling a resume, and not an input to the hash. (perf-plan-v4
            // A1 also landed in this build but does NOT belong on this list: it splits the gate fill across
            // two threads and leaves the tensor bit-identical, so an older `an-NNN.json`'s `firingsMs` is
            // still valid. A4, which would have changed the gate's pixels, was cut for under-censoring.)
            "plan4",
        )
    }

    /** `noBackupFilesDir/naqi-work/<jobKey>/` — see [JobStore] for why neither `cacheDir` nor `filesDir`. */
    private val workDir by lazy { JobStore.dir(applicationContext, jobKey) }

    override suspend fun doWork(): Result {
        val removeMusic = ops.removeMusic
        // Whether, never which: every branch below asks only whether faces are censored, which is what
        // FilterOps.censorFaces is and why "everyone" is byte for byte the old `true` path. Which faces
        // is [censorWho]'s business, and it reaches only [genderVoter] and the FaceTrackers.
        val censorFaces = ops.censorFaces
        if (!removeMusic && !censorFaces) return Result.failure()
        val inputUri = (inputData.getString(KEY_INPUT_URI) ?: return Result.failure()).toUri()

        // Reclaim orphaned job directories before writing several GB of our own. Here rather than in
        // Application/MainActivity because this is the only entry point that always runs: WorkManager
        // can restart a persisted job after a reboot without the user ever opening the app.
        // ponytail: orphans outlive an app the user never runs another job in. Move it to app startup
        // if that ever shows up as a storage complaint.
        runCatching { JobStore.sweep(applicationContext) }

        // ONE probe for the whole job (`plan-v2` §5.9 S3). It used to run 4x for an unsegmented censor job
        // and N+3 for a segmented one — every call opens a MediaExtractor AND a MediaMetadataRetriever
        // over the source, i.e. 35-70 container opens per film. Every shape now takes [VideoMeta] as an
        // argument. (FrameSampler.sample still probes once itself, for the rotation it hands ML Kit;
        // that one is inside the sampler and stays there.)
        // Probed before Preflight because the segment plan changes how much scratch the job needs.
        val probed = runCatching { FrameSampler.probe(applicationContext, inputUri) }
        val meta = probed.getOrNull()
        val durationMs = meta?.durationMs ?: 0L

        // The fifth job shape: an "Audio only" download has no video track at all. Every other shape
        // assumes one — music removal *copies* it sample-for-sample — so this is detected rather than
        // flagged: the source itself is the only thing that can be trusted about what tracks it has, and
        // a flag threaded from the download would be one more thing to keep in sync with reality.
        val audioOnly = removeMusic && !hasVideoTrack(inputUri)
        if (audioOnly) Log.i(TAG, "audio-only job: no video track, publishing to Music/Naqi")

        val plan = if (audioOnly) emptyList() else planFor(inputUri, durationMs, inputData.getLong(KEY_SEGMENT_MS, 0L))
        val segmented = censorFaces && plan.isNotEmpty()
        // The censor-only concat copies the SOURCE audio track sample-for-sample, and framework MediaMuxer
        // carries only AAC/AMR — which rejects the AC-3/E-AC-3/DTS a film ships AND the Opus/Vorbis in every
        // MKV/WebM. Such a source used to give up the segmented route entirely: correct output, but NO
        // RESUME on a ~3 h job, which is the exact failure Phase 2 exists to remove, and no asset in
        // qa-assets/ exercised it. Now the track is transcoded to AAC once up front and the concat copies
        // that. Nothing is traded away — the unsegmented fallback was already having Transformer transcode
        // the same track inside every export. See AudioPipeline.transcodeToAac.
        // Only meaningful for the censor-only segmented shape; with music removal the concat's audio is the
        // separator's own AAC output, so the source's codec never reaches the muxer.
        val audioPlan = if (segmented && !removeMusic) concatAudio(applicationContext, inputUri) else ConcatAudio.COPY
        if (segmented && !removeMusic) Log.i(TAG, "segmented audio: $audioPlan")
        // The debug segment override also turns the audio scratch on, so the resumable separator can be
        // exercised on a clip short enough to kill and resume by hand — it is otherwise unreachable below
        // 30 min, which is far too long an iteration loop for the fiddliest code in the phase.
        val forcedSegments = inputData.getLong(KEY_SEGMENT_MS, 0L) > 0
        val resumableAudio = removeMusic && (durationMs >= Eta.CONFIRM_THRESHOLD_MS || forcedSegments)
        if (segmented) Log.i(TAG, "segmented: ${plan.size} segments over ${durationMs}ms key=$jobKey")

        // --- Preflight (BEFORE any heavy setForeground work) ---
        // Runs for EVERY shape, not just music removal: opening the source is the only way to learn
        // it is DRM'd/undecodable, and that throw used to escape doWork with no message at all.
        // tempCopies: combined holds the render temp AND the published output at once (S5 removed the
        // mux temp between them, but the two survivors still coexist); the single-op shapes write one. The
        // segmented shape holds every rendered segment (~1x source) AND the concat output at once.
        Preflight.check(
            applicationContext, inputUri,
            needsAudio = removeMusic,
            allowNoVideo = audioOnly,
            tempCopies = when {
                segmented -> 2
                removeMusic && censorFaces -> 2
                else -> 1
            },
            // The separated-audio scratch: int16 stereo 44.1 kHz = 176 400 B per second of source. Plus,
            // for a source MediaMuxer cannot copy, the AAC transcode the concat copies instead: 192 000
            // bit/s stereo = 24 000 B per second, ~220 MB on a 155-min film, same lifetime as the PCM.
            // The two are mutually exclusive (TRANSCODE is only chosen when !removeMusic); they are added
            // rather than branched so a later edit does not have to re-prove that exclusivity.
            extraScratchBytes = (if (resumableAudio || (segmented && removeMusic)) durationMs / 1000 * 176_400 else 0L) +
                (if (audioPlan == ConcatAudio.TRANSCODE) durationMs / 1000 * 24_000 else 0L),
        )?.let { return fail(it) }

        // Every shape but audio-only needs the probe's geometry to render. It used to be re-probed inside
        // analyze/render, where a failure surfaced as an opaque throw after Preflight had already passed;
        // now it is one nullable and one per-cause message, at the same moment as every other check.
        if (meta == null && !audioOnly) {
            return fail(Preflight.messageFor(probed.exceptionOrNull() ?: IllegalStateException("probe failed")))
        }

        queued { it.copy(state = Queue.State.FILTERING) }
        return when {
            audioOnly -> runAudioOnly(inputUri)
            segmented -> runSegmented(inputUri, plan, removeMusic, audioPlan, durationMs, meta!!)
            removeMusic && censorFaces -> runCombined(inputUri, meta!!)
            removeMusic -> runMusicOnly(inputUri, durationMs)
            else -> runCensorOnly(inputUri, meta!!) // M1's unsegmented path
        }
    }

    /**
     * S1 (`plan-v2` §5.8): may the audio and video branches run at the same time?
     *
     * **Measured on a physical S23, 2026-08-03**, 643 s source, both schedules from a rebooted and cooled
     * device: sequential 616.1 s vs concurrent 549.7 s = **1.12×**, and peak RSS is the SAME either way
     * (1294 MB vs 1287 MB) because the separator's peak dominates and the video branch has already exited
     * by the time it arrives. So this buys ~11 % of the wall for no memory.
     *
     * Beware the per-stage SOAK lines when comparing the two: under the concurrent schedule
     * `stats.stage("separate")` only starts after `video()` returns, so that line is the tail alone and
     * the overlapped audio work is billed into analyze/render. **Total wall is the only valid comparison.**
     *
     * The measured peak is 1.29 GB (separate) + 0.53 GB (segmented censor) ≈ 1.82 GB. That is fine on the
     * 8 GB device it was measured on and **unproven on the 6 GB minimum spec**, and an earlier review
     * rejected the whole idea over exactly that — so the sequential schedule stays reachable and is keyed
     * on the device class, not on a flag. `totalMem` rather than `availMem`: available memory swings by
     * hundreds of MB minute to minute, and this decision has to hold for three hours.
     *
     * An 8 GB device reports ~7.4-7.6 GB after carveouts and a 6 GB device ~5.5-5.7, so the threshold
     * separates the two classes with room on both sides. Those figures are decimal GB; the constant is
     * binary — see [CONCURRENT_MIN_TOTAL_MEM], which was 7 GiB and so excluded the very device class it
     * was sized for. Thermal demotion is separate and happens at a chunk boundary — see
     * `AudioPipeline.thermalYield`.
     */
    private fun concurrentBranches(): Boolean {
        val am = applicationContext.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { runCatching { am?.getMemoryInfo(it) } }
        val ok = am != null && !am.isLowRamDevice && info.totalMem >= CONCURRENT_MIN_TOTAL_MEM
        Log.i(TAG, "schedule=${if (ok) "concurrent" else "sequential"} totalMem=${info.totalMem / (1 shl 20)}MB " +
            "lowRam=${am?.isLowRamDevice}")
        return ok
    }

    /** True when the source carries a video track; false for an "Audio only" download's `.m4a`. */
    private fun hasVideoTrack(uri: Uri): Boolean {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(applicationContext, uri, null)
            ext.firstTrackIndex("video/") != null
        } catch (_: Throwable) {
            true // undecidable: assume video, and let Preflight produce the real per-cause message
        } finally {
            runCatching { ext.release() }
        }
    }

    /**
     * Audio-only: [AudioPipeline.removeMusic] already emits an `.m4a`, so there is nothing to render and
     * nothing to mux — separate over the whole 1..99 band and publish into `Music/Naqi`.
     *
     * ponytail: this shape NEVER resumes, and unlike [runMusicOnly] that is not a choice. `durationMs`
     * comes from [FrameSampler.probe], which opens with `requireTrackIndex("video/")` and therefore throws
     * on the very `.m4a` that gets us here — doWork's `runCatching { … }.getOrDefault(0L)` then hands this
     * shape 0 on every run, so the >= 30 min resumable test could never fire. The dead branch is gone
     * rather than left to look like a live option. To actually give a long podcast a resumable separator,
     * measure duration off the AUDIO track (MediaMetadataRetriever's METADATA_KEY_DURATION works with no
     * video track) and pass `workDir` as `jobDir` above that threshold.
     */
    private suspend fun runAudioOnly(inputUri: Uri): Result {
        setForeground(foregroundInfo(stage(R.string.stage_separating), 1))

        val audioTemp = File(workDir, "audio.m4a")
        try {
            val sep = stage(R.string.stage_separating)
            stats.stage("separate")
            AudioPipeline.removeMusic(
                applicationContext, inputUri, keepStems, audioTemp,
                onProgress = { p -> reportBand(sep, p, 1, 98) },  // 0..100 -> 1..99
                isCancelled = { isStopped },
            )

            val displayName = outputName(inputUri, ext = "m4a")
            stats.stage("publish")
            val outputUri = Publish.audio(applicationContext, audioTemp, displayName) { isStopped }
            JobStore.delete(applicationContext, jobKey)
            return succeed(displayName, outputUri, inputUri, Publish.MIME_M4A)
        } catch (c: CancellationException) {
            runCatching { JobStore.delete(applicationContext, jobKey) }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "audio-only job failed", t)
            runCatching { JobStore.delete(applicationContext, jobKey) }
            return fail(Preflight.messageFor(t))
        } finally {
            stats.finish("shape=audio")
        }
    }

    /**
     * Phase 2's segmented censor route (`long-film-plan.md`), used whenever the source is long enough to
     * plan segments for. Three differences from [runCombined], all of them about surviving interruption:
     *
     * 1. **Analysis is per segment and checkpointed.** Each segment gets a fresh [FaceTracker] (which fixes
     *    its growth by construction) and writes `an-NNN.json` when it finishes. The EDL is still assembled
     *    GLOBALLY at the end, because the gate's hysteresis merges firings across boundaries — building it
     *    per segment would clip up to 1.5 s of censoring at every seam.
     * 2. **Rendering is per segment**, video-only, into `seg-NNN.mp4`; an already-rendered segment is
     *    skipped. Audio is muxed once at the end, since per-segment AAC cannot be concatenated.
     * 3. **The work directory survives a failure.** It is deleted on success and on a user cancel; a system
     *    stop (the 6 h foreground-service cap, an OOM kill, a reboot) leaves it for Resume to pick up.
     */
    private suspend fun runSegmented(
        inputUri: Uri,
        plan: List<RenderSegment>,
        removeMusic: Boolean,
        audioPlan: ConcatAudio,
        durationMs: Long,
        meta: VideoMeta,
    ): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val audioTemp = File(workDir, "audio.m4a")
        // Progress bands: analyze 0..8, render 8..17, separate 17..97, concat 97..99 (combined);
        // without music removal analyze/render get the room the separator would have used. See Eta.Bands.
        val analyzeSpan = if (removeMusic) Eta.Bands.ANALYZE else Eta.Bands.CENSOR_ANALYZE
        val renderBase = analyzeSpan
        val renderSpan = if (removeMusic) Eta.Bands.RENDER else Eta.Bands.CENSOR_RENDER
        try {
            // Up front, before the two long passes, because a device with no decoder for this codec cannot
            // do the job at all — the old unsegmented fallback needed the same decoder for Transformer to
            // transcode — so failing here costs seconds instead of the hours it would cost next to the
            // concat.
            if (audioPlan == ConcatAudio.TRANSCODE && audioTemp.length() == 0L) {
                stats.stage("transcode")
                report(stage(R.string.stage_preparing), 0)
                // Written to `.part` and renamed, exactly as renderSegments commits a segment: the final
                // name then EXISTS only over a complete file, so the length check above is a safe skip and
                // a SIGKILL mid-transcode cannot leave a truncated .m4a that looks finished. That is an
                // atomic write, not a checkpoint — no state file, nothing to keep in sync — so it honours
                // "do not checkpoint the transcode" while saving a resumed film ~10 min of redoing it.
                val part = File(workDir, "audio.m4a.part")
                runCatching { part.delete() }
                AudioPipeline.transcodeToAac(applicationContext, inputUri, part) { isStopped }
                check(part.renameTo(audioTemp)) { "could not commit the transcoded audio" }
            }
            // S1: the separator starts at t=0 and the two video passes run alongside it. See [runCombined]
            // for the reasoning and the failure/cancellation semantics — this is the same shape with the
            // segmented passes and the segmented bands (video 0..17, audio 0..80, concat 97..99).
            branches(
                audio = if (!removeMusic) null else { demote ->
                    AudioPipeline.removeMusic(
                        applicationContext, inputUri, keepStems, audioTemp,
                        onProgress = { p -> reportAudio(p, Eta.Bands.SEPARATE) },
                        isCancelled = { isStopped },
                        jobDir = workDir,
                        demoteWhile = demote,
                    )
                },
            ) {
                val edl = analyzeSegments(inputUri, plan, durationMs, 0, analyzeSpan)
                renderSegments(inputUri, plan, edl, meta, renderBase, renderSpan)
            }

            val audio = when {
                removeMusic -> TrackSource.FromFile(audioTemp)
                // The AAC written before analyze. Copied exactly as an AAC source's own track would be:
                // one continuous track, so no seam can drift against the video.
                audioPlan == ConcatAudio.TRANSCODE -> TrackSource.FromFile(audioTemp)
                // A source with no audio track at all. Remux.concat takes null and writes video only;
                // passing the Uri would make its requireTrackIndex("audio/") throw after the whole pass.
                audioPlan == ConcatAudio.NONE -> null
                // Censor-only: the source audio track is copied verbatim, which is strictly better than
                // M1's per-export transmux — one continuous track, so no seam can drift against the video.
                else -> TrackSource.FromUri(inputUri)
            }

            val joining = stage(R.string.stage_muxing)
            stats.stage("concat")
            val displayName = outputName(inputUri)
            // S5: the concat writes straight into the MediaStore row instead of into mux.mp4 that a
            // separate pass then copied there. `publish` remains for the shapes with a real temp.
            val outputUri = Publish.muxedVideo(applicationContext, displayName) { fd ->
                Remux.concat(
                    applicationContext,
                    parts = plan.map { ConcatPart(Checkpoint.segmentFile(workDir, it.index), it.startMs) },
                    audio = audio,
                    out = MuxOut.ToFd(fd),
                ) { p -> reportBand(joining, p, Eta.Bands.MUX_BASE, Eta.Bands.MUX) } // 0..100 -> 97..99
            }
            JobStore.delete(applicationContext, jobKey) // succeeded: nothing left to resume
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            // A stop is not automatically a cancel. Only the user asking to stop means "throw the work
            // away"; the 6 h cap, an lmkd kill and a reboot all arrive here too, and those are exactly the
            // cases this phase exists to survive. Pre-31 cannot tell them apart, so it keeps the work —
            // an orphan the sweep collects in 7 days is a far cheaper mistake than losing three hours.
            val cancelled = userCancelled()
            Log.i(TAG, "segmented job stopped: reason=${reasonName()} keepingWork=${!cancelled}")
            if (cancelled) runCatching { JobStore.delete(applicationContext, jobKey) }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "segmented job failed", t)
            // Work dir deliberately kept, and the UI is told so — this is what Resume resumes from.
            return fail(Preflight.messageFor(t), resumable = true)
        } finally {
            stats.finish("shape=segmented segments=${plan.size}")
            runCatching { Infer.close() }
            // audioTemp is deliberately NOT deleted here. On success and on a user cancel JobStore.delete
            // takes the whole directory anyway; what is left is a resumable failure, and that is exactly
            // when both writers of this file want it kept — the transcode to skip redoing ~10 min of work,
            // and removeMusic to skip re-encoding 1.6 GB of PCM.
        }
    }

    /**
     * [Checkpoint.plan] with its interior boundaries snapped to the source's own sync samples — see that
     * function for why the un-snapped boundaries silently drop 1-3 frames per seam.
     *
     * ONE extractor for the whole plan: `seekTo(SEEK_TO_NEXT_SYNC)` then read the sample's own time.
     * Truncating µs -> ms lands at or BEFORE the sync sample, which is what both ends of a seam need — a
     * clip end past it fires the decode-order EOS a frame early, and a clip start past it puts the IDR
     * itself below the clip start, where media3 drops it as negative.
     *
     * Boundaries stay whole ms deliberately, even though `setStartPositionUs` exists: `CensorGlEffect`
     * reconstructs absolute time as `presentationTimeUs / 1000 + startMs`, and that only equals
     * `floor(absoluteUs / 1000)` when the offset is a whole ms. The price of truncating is that both
     * passes then seek to the sync sample BEFORE the boundary and decode one extra GOP they discard —
     * ~5 s per segment on a film, against the ~2.6 s they already discarded. Real but small (<1 % of
     * decode work), and the alternative is plumbing µs through RenderSegment, Remux.concat and the EDL
     * lookup to save it.
     */
    private fun planFor(inputUri: Uri, durationMs: Long, forcedSegmentMs: Long): List<RenderSegment> {
        val ext = MediaExtractor()
        return try {
            ext.setDataSource(applicationContext, inputUri, null)
            ext.selectTrack(ext.requireTrackIndex("video/"))
            Checkpoint.plan(durationMs, forcedSegmentMs) { ms ->
                ext.seekTo(ms * 1000, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val t = ext.sampleTime
                when {
                    // No sync sample at or after `ms`, so no LATER cut can be snapped either. Collapsing
                    // this cut and the rest into the final segment keeps the invariant that every interior
                    // boundary is a sync sample. Answering `ms` here instead puts back exactly the
                    // decode-order tail loss this function exists to remove — measured on test-video.mp4,
                    // whose only sync samples are at 0 s and 8.3 s: 383 frames of 384 with `ms`, 384 with
                    // this. The cost is coarser resume near the end of such a source: the cheaper mistake.
                    t < 0L -> durationMs
                    // The seek did not move forward, so this source cannot be seeked by time at all — a
                    // fragmented mp4 with no `sidx` is the known case. Every cut would answer the same
                    // instant, distinct() would collapse them, and a 2.6 h film would run as ONE segment
                    // with no resume and no log line. Fall back to the nominal cut: lossy, but segmented.
                    t < ms * 1000 -> ms
                    else -> t / 1000
                }
            }
        } catch (t: Throwable) {
            // Deliberately NOT a second Checkpoint.plan() call. jobKey does not encode the plan, so a first
            // run that snapped and a resume that fell back would share a work directory: an-NNN.json folded
            // in for the wrong window, and seg-NNN.mp4 skipped as done then placed by Remux.concat at a
            // different absolute time — duplicated and missing content, published silently. Giving up
            // segmentation is correct output with no resume, which is the honest status quo instead.
            Log.w(TAG, "could not snap segment boundaries; running unsegmented", t)
            emptyList()
        } finally {
            runCatching { ext.release() }
        }
    }

    /**
     * True only when the USER asked to stop. A stop is not automatically a cancel: the 6 h
     * foreground-service cap, an lmkd kill and a reboot all surface as cancellation too, and those are the
     * cases Phase 2 exists to survive. Pre-31 has no stop reason, so it answers false and keeps the work —
     * an orphan the 7-day sweep collects is a much cheaper mistake than deleting three hours of rendering.
     */
    private fun userCancelled(): Boolean =
        Build.VERSION.SDK_INT >= 31 && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP

    private fun reasonName(): String = if (Build.VERSION.SDK_INT < 31) "unknown(pre-31)" else when (stopReason) {
        WorkInfo.STOP_REASON_CANCELLED_BY_APP -> "cancelled_by_app"
        WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> "fgs_timeout"
        WorkInfo.STOP_REASON_NOT_STOPPED -> "not_stopped"
        else -> "code_$stopReason"
    }

    /**
     * The NSFW gate's `[1,3,224,224]` input. **The consumer owns it now (perf-plan-v4 A1)**: the whole
     * fill used to run on the sampler's decode coroutine, which was 29 493 ms of a 114 648 ms producer
     * while this side sat idle for 75 s of the same pass. The sampler still gathers the source bytes
     * (~2.6 ms/gate-frame it cannot hand over — it is the only side holding the decoder's `Image`); the
     * ~8.2 ms of BT.601 arithmetic and 150 528 float writes over them happens here. Nothing gets faster,
     * ~26 s comes off the analyze wall, and the tensor is bit-identical to the pre-A1 one.
     *
     * **One buffer for the whole pass**, for exactly [genderVoter]'s reasons: DIRECT + native order
     * because ORT wraps such a buffer zero-copy and COPIES a heap one on every run (see [Infer.nsfw]),
     * and `by lazy` rather than per call because [analyzeSegments] builds fresh per-segment state and
     * would otherwise build a 602 KB buffer per segment. Safe to share for the same reason too: pass 1
     * is single-threaded, one frame is in the gate at a time, and the tensor is consumed inside the
     * [Infer.nsfw] call it was filled for.
     */
    private val gateInput: FloatBuffer by lazy {
        ByteBuffer.allocateDirect(3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
    }

    /**
     * [FrameSampler.gateFromGathered]'s heap scratch — 150 kB + 602 kB, one pair for the whole pass,
     * held here for exactly [gateInput]'s reasons (`FrameSampler` is an object; per-frame allocation
     * would be 752 kB × 3 215 ≈ 2.4 GB of large-object churn). See that function's KDoc.
     */
    private val gateYuv: ByteArray by lazy { ByteArray(3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE) }

    private val gateRgb: FloatArray by lazy { FloatArray(3 * FrameSampler.GATE_SIDE * FrameSampler.GATE_SIDE) }

    /**
     * The consumer-side wall of one pass-1 body, and the line both shapes log it on.
     *
     * perf-plan 1.2, re-read after 1.3b: decode+convert runs alongside [sampledFrame], so `wall` is
     * `max(producer, consumer)` and no subtraction recovers the convert. [detectNs]/[gateFillNs]/[gateNs]
     * are the consumer side ONLY — a pass where detect+gate is under wall is one the decoder is pacing.
     * plan-v2 §4.6: `gate=` is `session.run` alone and `gateFill=` is its preprocessing; both are on this
     * line since perf-plan-v4 A1 moved the fill off the sampler, and they still mean what they meant.
     */
    private class PassTimers {
        var detectNs = 0L
        var gateFillNs = 0L
        var gateNs = 0L
        private val startNs = System.nanoTime()
        private var wallNs = 0L

        /**
         * Latch `wall` the instant the sampler returns. Called explicitly rather than measured lazily in
         * [toString], because the log line it feeds sits after `finish()` and the checkpoint write — this
         * repo already lost a measurement to a timer that bracketed more than its name claimed
         * (perf-plan-v4 M1, the `nv21=` split), and it is not going to lose a second one for two lines.
         */
        fun stop() { wallNs = System.nanoTime() - startNs }

        override fun toString(): String =
            "wall=${wallNs / 1_000_000}ms detect=${detectNs / 1_000_000}ms " +
                "gateFill=${gateFillNs / 1_000_000}ms gate=${gateNs / 1_000_000}ms"
    }

    /**
     * One sampled frame's analysis, shared by [analyze] and [analyzeSegments] — which differ only in how
     * they report progress and where the firings go. It lived in both, and both restated the two rules
     * below; two copies of a rule that has to hold in both places is one edit away from holding in neither.
     *
     * perf-plan 1.3a: ML Kit works on its own executor while the gate runs here, so detect and gate
     * OVERLAP — [PassTimers.detectNs] times the await (the part that still blocks), and detect+gate can
     * exceed wall. **Awaiting inside this call is what keeps the sampler's ring buffers alive for both.**
     * `gateBytes != null` IS the 5 fps cadence: the sampler still decides which frames the gate sees and
     * still gathers their source pixels; perf-plan-v4 A1 moved only the arithmetic onto this side.
     *
     * **Both buffers are RING-OWNED and valid for exactly this call.** The gate tensor is therefore built
     * and consumed before returning (inside [Infer.nsfw]), and [image] is handed to [FaceTracker.onFaces]
     * — which crops the gender vote out of its NV21 pixels — while it is still live. Nothing here may
     * retain the image or defer the vote.
     */
    private fun sampledFrame(
        tracker: FaceTracker,
        image: InputImage,
        gateBytes: ByteBuffer?,
        uprightW: Int,
        uprightH: Int,
        ptsMs: Long,
        t: PassTimers,
        firings: MutableList<Long>,
    ) {
        val task = tracker.detect(image)
        if (gateBytes != null) {
            val t1 = System.nanoTime()
            FrameSampler.gateFromGathered(gateBytes, gateYuv, gateRgb, gateInput)
            val t2 = System.nanoTime()
            val probs = Infer.nsfw(applicationContext, gateInput)
            t.gateFillNs += t2 - t1; t.gateNs += System.nanoTime() - t2
            if (NsfwGate.fires(probs, strictness)) firings += ptsMs
        }
        val t0 = System.nanoTime(); val faces = Tasks.await(task); t.detectNs += System.nanoTime() - t0
        tracker.onFaces(faces, image, uprightW, uprightH, ptsMs)
    }

    /**
     * Pass 1, per segment. Segments already carrying an `an-NNN.json` are skipped entirely — that is the
     * resume. Firings accumulate across every segment and the hysteresis runs ONCE over the whole
     * timeline, so a censor interval that straddles a seam is still one interval.
     */
    private suspend fun analyzeSegments(
        inputUri: Uri,
        plan: List<RenderSegment>,
        durationMs: Long,
        progressBase: Int,
        progressSpan: Int,
    ): Edl {
        val stage = stage(R.string.stage_analyzing)
        stats.stage("analyze")
        val firings = ArrayList<Long>()
        val faceTracks = ArrayList<FaceTrackEdl>()
        // Outside the segment loop on purpose: report() is an AWAITED setProgress (Room write) plus a
        // setForeground binder IPC, and the sampler calls it ~93 000 times over a film for ~40 distinct
        // values. Hoisted, it also kills the duplicate write at every seam and on the resume branch.
        var lastPct = -1
        for (seg in plan) {
            val done = Checkpoint.readAnalysis(workDir, seg.index)
            if (done != null) {
                firings += done.firingsMs
                faceTracks += done.edl.faceTracks
                Log.i(TAG, "analyze seg-${seg.index}: resumed from checkpoint " +
                    "(${done.firingsMs.size} firings, ${done.edl.faceTracks.size} tracks)")
                val pct = progressBase + (seg.index + 1) * progressSpan / plan.size
                if (pct != lastPct) { lastPct = pct; reportVideo(stage, pct) }
                continue
            }
            val segFirings = ArrayList<Long>()
            // Fresh per segment: this is what bounds FaceTracker's growth by construction, on top of the
            // per-track eviction Phase 1 added. Only the TRACKER is fresh — the [genderVoter], its 110 KB
            // crop buffer and the counters it feeds are all the worker's and outlive every segment, which
            // is safe only because the segments run in sequence (perf-plan-v4 §10 corrects this comment:
            // it used to claim the voter was fresh too, contradicting the voter's own KDoc).
            val tracker = FaceTracker(genderVoter, censorWho)
            val timers = PassTimers()
            try {
                FrameSampler.sample(
                    applicationContext, inputUri, fps = 10f, maxDim = 640,
                    startMs = seg.startMs, endMs = seg.endMs,
                ) { image, gateBytes, uprightW, uprightH, ptsMs ->
                    sampledFrame(tracker, image, gateBytes, uprightW, uprightH, ptsMs, timers, segFirings)
                    val within = ((ptsMs - seg.startMs).toFloat() / (seg.endMs - seg.startMs).coerceAtLeast(1))
                    val pct = (progressBase + ((seg.index + within.coerceIn(0f, 1f)) * progressSpan / plan.size).toInt())
                        .coerceIn(progressBase, progressBase + progressSpan)
                    if (pct != lastPct) { lastPct = pct; reportVideo(stage, pct) }
                }
                timers.stop()
                val segTracks = tracker.finish()
                // The checkpoint goes in only once BOTH halves of this segment's analysis exist, and it is
                // written atomically — so a file under its final name always means a complete segment.
                Checkpoint.writeAnalysis(workDir, seg.index, segFirings, Edl(emptyList(), segTracks))
                firings += segFirings
                faceTracks += segTracks
                Log.i(TAG, "analyze seg-${seg.index} [${seg.startMs}..${seg.endMs}): " +
                    "firings=${segFirings.size} faceTracks=${segTracks.size} ${tracker.retention()} " +
                    timers)
            } finally {
                runCatching { tracker.closeDetector() }
            }
        }
        // ONE hysteresis pass over the whole timeline — the reason firings are accumulated rather than
        // turned into intervals per segment. The 7.4 overflow promotion also runs once, here, for the same
        // reason: it needs every segment's tracks to know how many faces overlap across a seam.
        val intervals = censorSpans(firings, durationMs, faceTracks)
        Log.i(TAG, "pass1 segmented: gateFirings=${firings.size} intervalCount=${intervals.size} " +
            "faceTracks=${faceTracks.size} wholeFrame=$wholeFrameBlur")
        logVotes("pass1 segmented")
        return Edl(intervals, faceTracks.sortedBy { it.startMs })
    }

    /** Pass 2, per segment. An existing `seg-NNN.mp4` is skipped; that plus [analyzeSegments] is the resume. */
    private suspend fun renderSegments(
        inputUri: Uri,
        plan: List<RenderSegment>,
        edl: Edl,
        meta: VideoMeta,
        progressBase: Int,
        progressSpan: Int,
    ) {
        val stage = stage(R.string.stage_rendering)
        stats.stage("render")
        // ONE bitrate for the whole job (`plan-v2` §5.9 S3). Hoisted out of the loop because
        // resolveBitrate opens a MediaExtractor and sometimes a MediaMetadataRetriever — 31 container
        // opens on a film — and because Remux.concat can only write one track format, so every segment
        // MUST be encoded identically. Resolving it once makes that an invariant rather than a
        // coincidence of every call answering the same number.
        val bitrate = RenderPipeline.resolveBitrate(applicationContext, inputUri)
        for (seg in plan) {
            if (Checkpoint.isRendered(workDir, seg.index)) {
                Log.i(TAG, "render seg-${seg.index}: already done, skipping")
                reportVideo(stage, progressBase + (seg.index + 1) * progressSpan / plan.size)
                continue
            }
            // Export to `.part` and rename, so a killed export never leaves a file that looks complete.
            val part = File(workDir, "seg-%03d.mp4.part".format(seg.index))
            runCatching { part.delete() }
            RenderPipeline.renderCensor(
                applicationContext, inputUri, part, edl, blurAmount, grayscale, meta, solidColor,
                segment = seg, bitrate = bitrate,
            ) { p ->
                reportVideoAsync(stage, progressBase + ((seg.index * 100 + p) * progressSpan / (plan.size * 100)))
            }
            check(part.renameTo(Checkpoint.segmentFile(workDir, seg.index))) {
                "could not commit segment ${seg.index}"
            }
            Log.i(TAG, "render seg-${seg.index}: ${Checkpoint.segmentFile(workDir, seg.index).length()} bytes")
        }
    }

    // ---- Censor-only: M1's shape, extracted into a function ----
    private suspend fun runCensorOnly(inputUri: Uri, meta: VideoMeta): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val tempFile = File(workDir, "render.mp4")
        val faceTracker = FaceTracker(genderVoter, censorWho)
        try {
            val edl = analyze(inputUri, faceTracker, meta)
            // No removeAudio: this file IS the published output, so Transformer transmuxing the source
            // audio alongside is exactly what is wanted (the M1 no-audio-re-encode fast path).
            render(inputUri, tempFile, edl, meta)
            val displayName = outputName(inputUri)
            stats.stage("publish")
            val outputUri = publish(tempFile, displayName)
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            throw c // WorkManager cancellation — never swallow it
        } catch (t: Throwable) {
            Log.e(TAG, "censor job failed", t)
            return fail(Preflight.messageFor(t))
        } finally {
            stats.finish("shape=censor ${faceTracker.retention()}")
            runCatching { faceTracker.closeDetector() }
            runCatching { Infer.close() }
            runCatching { JobStore.delete(applicationContext, jobKey) }
        }
    }

    // ---- Music-only: audio 1..97, mux 97..99, video passthrough from the original Uri ----
    private suspend fun runMusicOnly(inputUri: Uri, durationMs: Long): Result {
        setForeground(foregroundInfo(stage(R.string.stage_separating), 1))

        val audioTemp = File(workDir, "audio.m4a")
        // There is no video pass to segment here, so "long" only buys the resumable separator. Same
        // 30-minute threshold as everything else, so the product has one notion of a long job.
        val resumable = durationMs >= Eta.CONFIRM_THRESHOLD_MS
        try {
            val sep = stage(R.string.stage_separating)
            stats.stage("separate")
            AudioPipeline.removeMusic(
                applicationContext, inputUri, keepStems, audioTemp,
                onProgress = { p -> reportBand(sep, p, 1, Eta.Bands.MUSIC_SEPARATE) },   // 0..100 -> 1..97
                isCancelled = { isStopped },
                jobDir = if (resumable) workDir else null,
            )

            val mux = stage(R.string.stage_muxing)
            stats.stage("mux")
            val displayName = outputName(inputUri)
            val outputUri = Publish.muxedVideo(applicationContext, displayName) { fd ->
                Remux.mux(applicationContext, TrackSource.FromUri(inputUri), audioTemp, MuxOut.ToFd(fd),
                    onProgress = { p -> reportBand(mux, p, Eta.Bands.MUX_BASE, Eta.Bands.MUX) })
            }
            JobStore.delete(applicationContext, jobKey)
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            // Same rule as the segmented route: only a user cancel throws the scratch away. On the
            // resumable path a system stop must leave audio.pcm + audio.json for Resume.
            if (!resumable || userCancelled()) runCatching { JobStore.delete(applicationContext, jobKey) }
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "music job failed", t)
            if (!resumable) runCatching { JobStore.delete(applicationContext, jobKey) }
            return fail(Preflight.messageFor(t))
        } finally {
            stats.finish("shape=music resumable=$resumable")
        }
    }

    // ---- Combined: analyze 0..8, render 8..17, separate 17..97, mux 97..99 (see Eta.Bands) ----
    private suspend fun runCombined(inputUri: Uri, meta: VideoMeta): Result {
        setForeground(foregroundInfo(stage(R.string.stage_analyzing), 0))

        val renderTemp = File(workDir, "render.mp4")
        val audioTemp = File(workDir, "audio.m4a")
        val faceTracker = FaceTracker(genderVoter, censorWho)
        try {
            branches(
                audio = { demote ->
                    AudioPipeline.removeMusic(
                        applicationContext, inputUri, keepStems, audioTemp,
                        onProgress = { p -> reportAudio(p, Eta.Bands.SEPARATE) },
                        isCancelled = { isStopped },
                        demoteWhile = demote,
                    )
                },
            ) {
                val edl = analyze(inputUri, faceTracker, meta, progressBase = 0, progressSpan = Eta.Bands.ANALYZE)
                // S2 (`plan-v2` §5.9): render WITHOUT audio. Transformer used to write the source audio
                // into render.mp4 and Remux.mux below reads only the video track back out of it — a wasted
                // transmux for an AAC source, and for Opus/AC-3 (which media3 cannot transmux) a full
                // decode + AAC encode that is thrown away, measured 12.9 s per 193 s track.
                render(
                    inputUri, renderTemp, edl, meta, removeAudio = true,
                    progressBase = Eta.Bands.ANALYZE, progressSpan = Eta.Bands.RENDER,
                )
            }

            val mux = stage(R.string.stage_muxing)
            stats.stage("mux")
            val displayName = outputName(inputUri)
            val outputUri = Publish.muxedVideo(applicationContext, displayName) { fd ->
                Remux.mux(applicationContext, TrackSource.FromFile(renderTemp), audioTemp, MuxOut.ToFd(fd),
                    onProgress = { p -> reportBand(mux, p, Eta.Bands.MUX_BASE, Eta.Bands.MUX) })
            }
            return succeed(displayName, outputUri, inputUri)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "combined job failed", t)
            return fail(Preflight.messageFor(t))
        } finally {
            stats.finish("shape=combined ${faceTracker.retention()}")
            runCatching { faceTracker.closeDetector() }
            runCatching { Infer.close() }
            runCatching { JobStore.delete(applicationContext, jobKey) }
        }
    }

    /**
     * S1 (`plan-v2` §5.8): run the audio branch at the same time as analyze → render.
     *
     * **The reason is not the obvious one.** Render does use different silicon, but render is 4 % of the
     * wall. The win is that `separate` holds only 6 of 8 cores for 55-65 % of the job **and cannot use
     * more** — 8 intra-op threads measured SLOWER than 6 (2244 vs 2136 ms/chunk) because every barrier
     * waits on the S23's little cores. So two cores sit idle for one to three hours. Ceiling is
     * `(2/8) x separate`; realistically 15-30 min, because the spare cores are the weak ones, which is
     * exactly why they are spare.
     *
     * **Structure.** [audio] is the `async` child and [video] runs in this coroutine, which is what gives
     * the required failure semantics for free:
     * - the video body throws -> `coroutineScope` cancels the audio child and rethrows the body's own
     *   exception;
     * - the audio child throws -> the scope is cancelled, the body's next suspension point sees a
     *   CancellationException, and `coroutineScope` rethrows the CHILD's ORIGINAL cause, not that
     *   cancellation. Either way `Preflight.messageFor` sees the real failure.
     * `videoDone` is set in a `finally` rather than after [video] so a failing video branch still releases
     * an audio branch parked in the thermal demotion below — the scope's cancellation cannot interrupt a
     * blocking sleep, only that flag can.
     *
     * The two branches checkpoint independently, so the failure model itself does not change.
     */
    private suspend fun branches(
        audio: (suspend (demoteWhile: () -> Boolean) -> Unit)?,
        video: suspend () -> Unit,
    ) {
        stageLabel = stage(R.string.stage_analyzing)
        if (audio == null) { video(); return }
        if (!concurrentBranches()) {
            video()
            stageLabel = stage(R.string.stage_separating)
            stats.stage("separate")
            audio { false } // nothing to demote from: this IS the sequential schedule
            return
        }
        coroutineScope {
            val separating = async { audio { !videoDone } }
            try {
                video()
            } finally {
                videoDone = true
            }
            // From here only the audio branch is left, so the stage the user sees is honest again. The
            // SOAK line for "separate" measures only this tail; the overlapped part is billed to
            // analyze/render, which is the one thing a single-threaded stage timer cannot express.
            stageLabel = stage(R.string.stage_separating)
            stats.stage("separate")
            separating.await()
        }
    }

    /**
     * Push progress to WorkData + the notification WITHOUT awaiting it. Every `onProgress` callback in
     * this file is non-suspend — they run on the transformer's Looper or on Dispatchers.Default — so they
     * must use the async variants. [report] is the awaited twin, used only where a suspend context
     * already exists.
     */
    private fun reportAsync(stage: String, overall: Int) {
        stats.tick()
        setProgressAsync(workDataOf(KEY_PROGRESS to overall, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(overall)))
        setForegroundAsync(foregroundInfo(stage, overall))
    }

    /** Map a 0..100 sub-progress onto [base, base+span] and push it. */
    private fun reportBand(stage: String, sub: Int, base: Int, span: Int) =
        reportAsync(stage, (base + sub * span / 100).coerceIn(0, 100))

    /**
     * S1's progress split. Under the concurrent schedule both branches post from different threads, so
     * neither may post an absolute overall percent — one would stomp the other and the bar would jump
     * backwards. Each owns a SHARE instead and what goes out is their sum, which is monotonic because
     * both shares are. The shares are the bands that were already documented: video 0..17 (analyze
     * 0..8, render 8..17), audio the remainder of its shape's band.
     *
     * Under the sequential schedule this is arithmetically identical to the old absolute posts — the
     * audio branch starts with `videoPct` already at 50 — so both shapes report the same numbers either
     * way, and the shapes with only one branch (censor-only, music-only) leave the other share at 0.
     */
    @Volatile private var videoPct = 0

    @Volatile private var audioPct = 0

    /** The stage the notification names: the video branch's, until [branches] hands it to the audio one. */
    @Volatile private var stageLabel: String? = null

    /** Set the moment the video branch stops, however it stopped. See [branches]. */
    @Volatile private var videoDone = false

    private suspend fun reportVideo(stage: String, pct: Int) {
        stageLabel = stage
        videoPct = pct
        report(stage, pct + audioPct)
    }

    private fun reportVideoAsync(stage: String, pct: Int) {
        stageLabel = stage
        videoPct = pct
        reportAsync(stage, pct + audioPct)
    }

    /** [sub] is the separator's own 0..100 within a [span]-wide share of the bar. */
    private fun reportAudio(sub: Int, span: Int) {
        audioPct = sub * span / 100
        reportAsync(stageLabel ?: stage(R.string.stage_separating), videoPct + audioPct)
    }

    /** Gender-vote instrumentation, cumulative over the whole pass. Reported by [logVotes]. */
    private var voteCrops = 0
    private var voteAbstained = 0
    private var voteNanos = 0L
    private var voteFailures = 0
    private var voteFailureLogged = false

    /**
     * The face-gender classifier the [FaceTracker]s vote with, or **null** under Everyone/Off — which is
     * the point: those two states allocate nothing and run pass 1 byte for byte as before, so no existing
     * user pays for this feature (plan-censor-who §4.1).
     *
     * FilterWorker owns the ORT call and the buffer; [FaceTracker] owns the policy (which crops are worth
     * classifying, `VOTE_CAP`, the majority rule). This side is deliberately just "pixels in, ±1 out".
     *
     * **One buffer for the whole pass** — hence `by lazy` and not a function: the segmented path builds a
     * fresh [FaceTracker] per segment and would otherwise build a buffer per segment too. ORT wraps a
     * DIRECT native-order buffer zero-copy and *copies* a heap one on every run (see [Infer.nsfw]'s KDoc),
     * so a per-crop buffer would additionally mean a 110 KB copy per face per frame. Safe to share because
     * pass 1 is single-threaded and the crop is consumed inside the `Infer.genderAge` call it was filled
     * for — the segments run in sequence, and in [runCombined] only the video branch votes.
     *
     * The whole body is guarded: pass 1 runs for up to three hours and one ORT throw on one crop must not
     * cost the job. A failure abstains, and an abstention already means censor (§4.2), so the safe
     * direction survives the error path too — as does a missing model, which is what the null return is.
     */
    private val genderVoter: FaceGenderVoter? by lazy {
        // Asked once, here, rather than per crop: without the asset every eligible face would pay a full
        // 9 216-pixel YUV->RGB walk to reach a guaranteed abstention. Null degrades Women/Men to "no vote
        // cast", which §4.2 already defines as censor — i.e. to Everyone, the safe direction.
        if ((censorWho != FilterOps.WOMEN && censorWho != FilterOps.MEN) ||
            !Infer.genderAgeAvailable(applicationContext)
        ) return@lazy null
        val side = FrameSampler.CROP_SIDE
        val crop = ByteBuffer.allocateDirect(3 * side * side * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        FaceGenderVoter { image, uprightW, uprightH, rect ->
            val t0 = System.nanoTime()
            val vote = try {
                // InputImage.getWidth/getHeight are the UNROTATED NV21 buffer's dims here (the sampler
                // hands ML Kit the buffer plus a rotation), which is exactly cropToTensor's dispW/dispH.
                FrameSampler.cropToTensor(
                    image.byteBuffer!!, image.width, image.height, image.rotationDegrees,
                    uprightW, uprightH, rect, crop,
                )
                // out is RAW logits [female, male]; softmax over the two collapses to a sigmoid of their
                // difference. p = P(male). Null = model not installed. See Models.kt's GENDERAGE contract.
                val out = Infer.genderAge(applicationContext, crop)
                if (out == null) 0 else {
                    val p = 1f / (1f + exp(-(out[1] - out[0])))
                    dumpCrop(crop, p, rect, uprightW, uprightH, image.rotationDegrees)
                    if (max(p, 1f - p) < CONF_FLOOR) 0 else if (p >= 0.5f) 1 else -1
                }
            } catch (t: Throwable) {
                // One trace, not thousands: a per-crop failure mode (an ORT throw on this graph, a moved
                // buffer limit) is systematic, so logging it per crop would bury the pass's own lines.
                if (!voteFailureLogged) {
                    voteFailureLogged = true
                    Log.w(TAG, "gender vote failed, abstaining (logged once)", t)
                }
                voteFailures++
                0
            }
            voteNanos += System.nanoTime() - t0
            voteCrops++
            if (vote == 0) voteAbstained++
            vote
        }
    }

    /**
     * plan-censor-who §3.2's eval set: the crops the vote actually saw, as PNGs, named with the model's
     * own p(male) and the face's upright pixel size. Labelling these by hand is what turns the logged
     * abstention rate into §3.3's balanced accuracy — and looking at even one of them is what proves the
     * crop is a FACE, which no vote-rate statistic can (a transposed axis would still abstain plausibly).
     *
     * Off unless `filesDir/dump-crops` exists, so enabling it is one `adb shell run-as ... touch` and
     * needs no new intent extra, no new `Data` key and no wire-format change. `DEBUG_HOOKS`, not `DEBUG`,
     * to match the rest of this file's hooks: the non-debuggable `benchmark` build has to keep them.
     *
     * ponytail: capped at [DUMP_CAP] and no directory rotation — this is a measurement harness, not a
     * feature. A film would otherwise write ~17 000 files into the app's own data dir.
     */
    private fun dumpCrop(
        crop: FloatBuffer, pMale: Float,
        rect: NRect, uprightW: Int, uprightH: Int, rotation: Int,
    ) {
        val dir = cropDumpDir ?: return
        if (dumped >= DUMP_CAP) return
        val px = maxOf(rect.width * uprightW, rect.height * uprightH).toInt()
        val side = FrameSampler.CROP_SIDE
        val plane = side * side
        // NCHW 0..255 floats back to ARGB. Nothing here is on the hot path — it returns above when off.
        val pixels = IntArray(plane) { i ->
            (0xFF shl 24) or (crop.get(i).toInt() shl 16) or
                (crop.get(plane + i).toInt() shl 8) or crop.get(2 * plane + i).toInt()
        }
        val name = "crop_%04d_p%.3f_%dpx_box%.3f,%.3f,%.3f,%.3f_up%dx%d_rot%d.png"
            .format(dumped, pMale, px, rect.left, rect.top, rect.right, rect.bottom, uprightW, uprightH, rotation)
        runCatching {
            File(dir, name).outputStream().use {
                Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            dumped++
        }
    }

    /**
     * Non-null only while the marker exists — resolved once, see [dumpCrop]. `touch` creates a FILE, so
     * that is turned into the directory; the final `isDirectory` is what keeps a failed `mkdirs` from
     * throwing a swallowed `FileNotFoundException` per crop for the rest of the pass.
     */
    private val cropDumpDir: File? by lazy {
        File(applicationContext.filesDir, "dump-crops")
            .takeIf { BuildConfig.DEBUG_HOOKS && it.exists() }
            ?.also { if (!it.isDirectory) { it.delete(); it.mkdirs() } }
            ?.takeIf { it.isDirectory }
    }
    private var dumped = 0

    /**
     * plan-censor-who §5 budgets ~1 ms/crop and says drop `VOTE_CAP` to 3 if the measured cost exceeds
     * ~5 % of analyze — this line is how that gets measured on the S23, so it sits with the pass's other
     * timings. Its OWN line rather than appended to `pass1:`, because logcat truncates a message at ~4 kB
     * and that line already lost content to it once (see [analyze]).
     */
    private fun logVotes(where: String) {
        if (voteCrops == 0) return
        val ms = voteNanos / 1_000_000
        Log.i(TAG, "$where gender: who=$censorWho crops=$voteCrops abstained=$voteAbstained " +
            "failed=$voteFailures total=${ms}ms perCrop=${"%.2f".format(ms.toFloat() / voteCrops)}ms")
    }

    /**
     * Pass 1: sample once, run faces and the gate on every sampled frame, build the EDL. [pct]
     * becomes `progressBase + fraction*progressSpan`; the defaults reproduce the M1 0..50 band exactly.
     */
    private suspend fun analyze(
        uri: Uri, faceTracker: FaceTracker, meta: VideoMeta,
        progressBase: Int = 0, progressSpan: Int = 50,
    ): Edl {
        val durationMs = meta.durationMs
        // Correctness item 7.2 lives in [intervalsFor]; this one is only the progress denominator, where a
        // zero would divide by zero and an unknown duration can honestly do nothing better than pin the
        // bar at its band start until the pass ends.
        val progressDen = durationMs.coerceAtLeast(1L)
        val stage = stage(R.string.stage_analyzing)
        stats.stage("analyze")
        val firings = ArrayList<Long>()
        var index = 0
        var lastPct = -1
        val timers = PassTimers()
        FrameSampler.sample(applicationContext, uri, fps = 10f, maxDim = 640) { image, gateBytes, uprightW, uprightH, ptsMs ->
            sampledFrame(faceTracker, image, gateBytes, uprightW, uprightH, ptsMs, timers, firings)
            index++
            val pct = (progressBase + (ptsMs.toFloat() / progressDen) * progressSpan)
                .toInt().coerceIn(progressBase, progressBase + progressSpan)
            if (pct != lastPct) { lastPct = pct; reportVideo(stage, pct) }
            // Retention, live. On a feature-length film the end-of-pass numbers only arrive 70 minutes
            // in, and never at all if the job is cancelled — this is the line that shows the per-track
            // eviction actually holding a flat live-track count, and (plan-v2 §7.1) how many detections
            // ML Kit refused a tracking id, which has never been measured.
            // Every 1 200 sampled frames = every 2 min of source at 10 fps.
            if (index % 1_200 == 0) Log.i(TAG, "pass1 live at=${ptsMs}ms ${faceTracker.retention()}")
        }
        timers.stop()

        val faceTracks = faceTracker.finish()
        val intervals = censorSpans(firings, durationMs, faceTracks)
        // Counts on their own line: a feature-length film produces hundreds of intervals, and logcat
        // truncates a message at ~4 kB — on the first 155-min soak that silently ate the face counts
        // off the end of the combined line, which were the whole point of logging it.
        Log.i(TAG, "pass1: gateFirings=${firings.size} intervalCount=${intervals.size} " +
            "wholeFrame=$wholeFrameBlur faceTracks=${faceTracks.size} ${faceTracker.retention()} " +
            timers)
        logVotes("pass1")
        Log.i(TAG, "pass1 intervals=$intervals")
        return Edl(intervals, faceTracks)
    }

    /**
     * Pass 2: render the EDL into [tempFile]; the transformer's 0..100 maps onto
     * `progressBase + p*progressSpan/100`. The defaults reproduce the M1 50..100 band exactly.
     */
    private suspend fun render(
        uri: Uri, tempFile: File, edl: Edl, meta: VideoMeta,
        removeAudio: Boolean = false,
        progressBase: Int = 50, progressSpan: Int = 50,
    ) {
        val stage = stage(R.string.stage_rendering)
        stats.stage("render")
        reportVideo(stage, progressBase)
        RenderPipeline.renderCensor(
            applicationContext, uri, tempFile, edl, blurAmount, grayscale, meta, solidColor,
            removeAudio = removeAudio,
        ) { p ->
            reportVideoAsync(stage, progressBase + p * progressSpan / 100)
        }
    }

    /**
     * Every whole-frame span the EDL will carry: the gate's hysteresis intervals, the 7.4 overflow
     * promotion, and — under [wholeFrameBlur] — every censored face track promoted to the whole frame.
     *
     * One function because both pass-1 shapes must agree. It runs ONCE over the whole timeline, never
     * per segment: a segment checkpoint stores its bare tracks (`Edl(emptyList(), segTracks)`), so a
     * resume under a different flag still rebuilds the right spans — and promoting per segment could
     * not bridge a face that spans a seam.
     */
    private fun censorSpans(firings: List<Long>, durationMs: Long, tracks: List<FaceTrackEdl>): List<LongRange> {
        val base = intervalsFor(firings, durationMs) + overflowSpans(tracks)
        return if (wholeFrameBlur) promoteFacesToFullFrame(base, tracks) else base
    }

    /**
     * The gate's hysteresis intervals over the whole timeline, plus the debug E2E hook: `force_intervals`
     * adds full-frame spans so an SFW test asset still exercises pass 2's censoring.
     *
     * **Correctness item 7.2.** [NsfwGate.intervals] clamps every span into `[0, durationMs]`, so a source
     * whose duration the probe could not read — it used to arrive here as `coerceAtLeast(1)` — collapsed
     * every censor interval to `[0, 1 ms]`: the gate fires, the EDL says it fired, and NOTHING is
     * censored. An unknown duration must mean "do not clamp the far end", not "clamp it to nothing". The
     * mapping is here rather than at the two call sites so no third one can reintroduce it.
     */
    private fun intervalsFor(firings: List<Long>, durationMs: Long): List<LongRange> {
        val intervals = NsfwGate.intervals(firings, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
        if (!BuildConfig.DEBUG_HOOKS) return intervals
        val forced = parseForceIntervals(inputData.getString(KEY_FORCE_INTERVALS))
        return if (forced.isEmpty()) intervals else intervals + forced
    }

    /**
     * Correctness item 7.4: spans where more faces are on screen at once than the renderer can composite.
     *
     * `CensorEffect` blurs at most [MAX_REGIONS] rects per frame and silently **drops the
     * smallest** past that — it fails OPEN, on the frames with the most people in them. Promoting those
     * moments to whole-frame censor intervals fixes it without touching the shader: `Edl.regionsAt`
     * returns empty under full-frame precedence, so the renderer never sees an overflowing frame at all.
     * That is the seam `docs/plan-whole-frame-blur.md` §1 already picked for exactly this conversion, and
     * it lands in the EDL, so a checkpoint resume and a QA diff both reproduce the decision.
     *
     * Counted by a sweep over track lifetimes, which OVER-counts slightly against `regionsAt` (a track
     * with no keyframe near `t` contributes nothing there). Over-counting censors a little more than
     * strictly needed, which is the safe direction for this bug.
     */
    private fun overflowSpans(tracks: List<FaceTrackEdl>): List<LongRange> {
        if (tracks.size <= MAX_REGIONS) return emptyList()
        // (time, delta); a track is active over the INCLUSIVE [startMs, endMs] regionsAt tests, so its
        // end event lands at endMs + 1.
        val events = ArrayList<Pair<Long, Int>>(tracks.size * 2)
        for (t in tracks) { events.add(t.startMs to 1); events.add((t.endMs + 1) to -1) }
        events.sortBy { it.first }
        val out = ArrayList<LongRange>()
        var active = 0
        var from = -1L
        var i = 0
        while (i < events.size) {
            // Every delta at one instant is applied before the count is read: evaluating mid-instant would
            // invent a one-ms gap wherever one track ends exactly as another begins.
            val t = events[i].first
            while (i < events.size && events[i].first == t) { active += events[i].second; i++ }
            if (active > MAX_REGIONS) { if (from < 0L) from = t } else if (from >= 0L) {
                out.add(from..(t - 1))
                from = -1L
            }
        }
        if (out.isNotEmpty()) Log.w(TAG, "7.4: ${out.size} spans over $MAX_REGIONS faces — whole-frame there")
        return out
    }

    /** Copy the temp into `Movies/Naqi` and return its uri — see [Publish] for the cancellation contract. */
    private fun publish(tempFile: File, displayName: String): Uri =
        Publish.video(applicationContext, tempFile, displayName) { isStopped }

    /**
     * Success path for every shape: post the done notification, then report the output to the UI.
     *
     * A quarantined download is deleted here and never offered as a "Delete original" target. It is not
     * the user's file — they never had it — and the PRD's promise is that the unfiltered original stops
     * existing the moment the filtered one is safely published. Ordered deliberately: the delete runs
     * only after [Publish] has finalized the output.
     */
    private fun succeed(
        displayName: String,
        outputUri: Uri,
        inputUri: Uri,
        mime: String = Publish.MIME_MP4,
    ): Result {
        val quarantined = Downloader.isQuarantined(applicationContext, inputUri)
        if (quarantined) Downloader.discard(applicationContext, inputUri)
        queued { it.copy(state = Queue.State.DONE, outputUri = outputUri.toString()) }
        JobNotifications.done(
            applicationContext, displayName, outputUri.toString(),
            inputUri.toString().takeUnless { quarantined }, mime,
        )
        return Result.success(
            workDataOf(
                KEY_OUTPUT_NAME to displayName,
                KEY_OUTPUT_URI to outputUri.toString(),
                // The Saved card offers the same delete the notification does, so it needs the same
                // target — null for a quarantine, which is not the user's file to be asked about.
                KEY_SOURCE_URI to inputUri.toString().takeUnless { quarantined },
            ),
        )
    }

    /**
     * `<sourceNameNoExt>-naqi-<epochMillis>.<ext>`; source name queried, falling back to the file's own
     * name and only then to the literal "video".
     *
     * The `file://` fallback is what makes a downloaded video keep its title. There is no provider
     * behind `file://`, so `DISPLAY_NAME` returns null and every quarantined download would have been
     * published as `video-naqi-<ts>.mp4` — the title yt-dlp was asked to name the file after, thrown
     * away one step before the user sees it.
     */
    private fun outputName(uri: Uri, ext: String = "mp4"): String {
        val source = applicationContext.displayName(uri)
            ?: uri.takeIf { it.scheme == "file" }?.path?.let { File(it).name }
            ?: "video"
        return "${source.substringBeforeLast('.')}-naqi-${System.currentTimeMillis()}.$ext"
    }

    private suspend fun report(stage: String, pct: Int) {
        stats.tick()
        setProgress(workDataOf(KEY_PROGRESS to pct, KEY_STAGE to stage, KEY_ETA_MS to stats.etaMs(pct)))
        setForeground(foregroundInfo(stage, pct))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(stage(R.string.stage_analyzing), 0)

    private fun stage(resId: Int): String = applicationContext.getString(resId)

    // The ETA is derived here rather than passed in, so every existing call site posts it for free.
    private fun foregroundInfo(stage: String, progress: Int) =
        JobNotifications.foregroundInfo(applicationContext, id, stage, progress, stats.etaMs(progress))

    companion object {
        const val KEY_REMOVE_MUSIC = "remove_music"

        /**
         * The censor op: one of [FilterOps]' four `censorWho` states, not a boolean
         * (plan-censor-who §1.1). The literal is PERSISTED in the input data of enqueued jobs.
         */
        const val KEY_CENSOR_WHO = "censor_who"

        /**
         * READ-ONLY legacy — the boolean this op was until the rename above. Nothing writes it any
         * more, but a job enqueued by an older build is sitting in WorkManager's database carrying only
         * this key, so every read of [KEY_CENSOR_WHO] falls back to it — [filterOps], which is now the
         * only reader of the ops, and [jobKey], which re-reads inline for the reason stated there.
         * Dropping it would re-default those jobs to "do not censor" — a full render that silently
         * returns the input, which is the worst failure this app has.
         */
        const val KEY_CENSOR_WOMEN = "censor_women"
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_STRICTNESS = "strictness"
        const val KEY_BLUR_AMOUNT = "blur_amount"
        const val KEY_GRAYSCALE = "grayscale"
        const val KEY_SOLID_COLOR = "solid_color"
        const val KEY_WHOLE_FRAME = "whole_frame"

        // KEY_BLUR_UNKNOWN ("blur_unknown_faces") was deleted with the gender vote (plan-v2 §5.4)
        // along with FilterOps.blurUnknownFaces. Unlike KEY_CENSOR_WOMEN above, the wire string did NOT
        // have to be kept: nothing reads it, so an older job's input Data just carries an ignored key.
        const val KEY_KEEP_STEMS = "keep_stems"
        const val KEY_FORCE_INTERVALS = "force_intervals"

        /**
         * Debug-only segment length override in ms. Any positive value forces the Phase 2 segmented route
         * regardless of duration — the only way to exercise multi-segment concat and kill/resume on a clip
         * short enough to iterate on. 0 (the default) means "decide from the source duration".
         */
        const val KEY_SEGMENT_MS = "segment_ms"
        const val KEY_PROGRESS = "progress"
        const val KEY_STAGE = "stage"

        /** Live remaining-time estimate in ms, refined from observed throughput; 0 = too early to say. */
        const val KEY_ETA_MS = "eta_ms"
        const val KEY_OUTPUT_NAME = "output_name"
        const val KEY_OUTPUT_URI = "output_uri"
        const val KEY_OUTPUT_MESSAGE = "output_message"

        /** The filtered file's source, echoed back so the Saved card can offer to delete it; null for a quarantine. */
        const val KEY_SOURCE_URI = "source_uri"

        /**
         * Set on a failure that left completed segments on disk, so the UI can offer Resume. Re-running the
         * same (source, options) lands on the same [JobStore] key and skips everything already finished.
         */
        const val KEY_RESUMABLE = "resumable"
        const val UNIQUE_WORK = "naqi_filter_job"

        /** Ties this run to its [Queue] item. Absent = the picker/debug path, which keeps real failures. */
        const val KEY_QUEUE_ID = "queue_id"

        private const val TAG = "FilterWorker"

        /**
         * What `blurAmount = 0 && !grayscale` becomes (item 7.3). `CensorEffect` derives
         * `sigma = blurAmount/100 * 40 * (min(w,h)/1080)`, so this is 10 px at 1080p and 4.4 px at 480p —
         * unambiguously a blur at both, and far enough below the 60 default that nobody who picked a
         * value on purpose is affected. Only the exact no-op combination reaches it.
         */
        private const val MIN_EFFECTIVE_BLUR = 25

        /**
         * Softmax probability of the winning gender below which a crop votes for nobody
         * (plan-censor-who §4.2). **Tunable, and it is THE dial** of §3.3's curve: raise it and
         * abstentions rise, so Women/Men both drift toward Everyone; lower it and more men stay visible
         * at the cost of more women exposed. 0.60 is a starting point, not a measured optimum — Phase B
         * reads the real value off the sweep, using the `perCrop=`/`abstained=` numbers [logVotes]
         * prints. An abstention already means censor, so this only ever trades in the safe direction.
         */
        private const val CONF_FLOOR = 0.60f

        /** Crops [dumpCrop] writes before it stops. Enough to hand-label for §3.3, few enough to `adb pull`. */
        private const val DUMP_CAP = 240

        /**
         * Device total RAM below which S1's two branches run sequentially — see [concurrentBranches].
         *
         * 6.5 GiB, and the halves matter. This was 7 GiB, written against a carveout figure quoted in
         * decimal GB ("an 8 GB device reports ~7.4-7.6"): 7.4 GB is 6.91 GiB, so the bar sat *above* the
         * 8 GB class and S1 fell back to sequential on every device. Measured `totalMem` on the S23 it
         * was designed for is **7072 MiB**. The 6 GB class reports ~5.5-5.7 GB = ~5.2-5.3 GiB, so 6.5 GiB
         * still separates the two with ~570 MiB of room on the near side. Concurrent peak is ~1.82 GB.
         */
        private const val CONCURRENT_MIN_TOTAL_MEM = 6_656L * 1024 * 1024 // 6.5 GiB

        /** Parse "startMs-endMs,startMs-endMs"; bad segments are skipped. */
        private fun parseForceIntervals(spec: String?): List<LongRange> {
            if (spec.isNullOrBlank()) return emptyList()
            return spec.split(",").mapNotNull { part ->
                val nums = part.split("-")
                val start = nums.getOrNull(0)?.trim()?.toLongOrNull()
                val end = nums.getOrNull(1)?.trim()?.toLongOrNull()
                if (start != null && end != null) start..end else null
            }
        }
    }
}
