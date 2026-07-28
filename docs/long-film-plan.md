# long-film-plan.md — feature-length input (60–150 min)

Source: `prd-video-filter-android.md`, `tasks.md` (M2/M3 measurements). Written 2026-07-27, after an end-to-end read of the shipped pipeline.

## The user problem

Someone points Naqi at a 2 h film, taps Continue, and puts the phone down. Today one of two things happens: the job dies somewhere past the second hour with nothing saved, or it finishes after the phone has been pinned at 100 % CPU long enough to flatten the battery. Either way the user learns this by waiting — the app never told them what they were signing up for, and it kept no partial result.

The bug is not "slow". The bug is **the app makes a promise it can't keep and gives back nothing when it breaks it.**

## The contradiction to settle first

The PRD says all three of these:

- It **contemplates feature-length input**: "never materialize all 4 stems (2 h movie ≈ 2.5 GB/stem f32)" — `prd-video-filter-android.md:70`
- It declares **"job resume after process death" a non-goal** for v1 — `:12`
- It specifies **acceptance only for a 5-min clip** — `:84`

Resume is exactly the mechanism a 2 h film needs, so the first two cannot both stand; and the third means nothing has ever been measured at the length the first one assumes. **Nothing in Phase 2 below should be built until that is settled** — see the decision gate. The 2 h soak has been open since M2 (`tasks.md:73`).

## Evidence: four walls, in the order you hit them

**Updated 2026-07-27 with the Phase 0 soak.** One instrumented combined run on `qa-assets/movie-test.mp4`
(155.4 min, 1728×720 @ 23.976, S23, on charger) replaced the first three estimates with measurements. The
run was stopped by hand after render, so `separate`/`mux` on a film remain carried from M2's 5-min numbers.

| stage | plan estimate (2 h) | **measured (155 min film, S23)** | note |
|---|---|---|---|
| analyze @10 fps (ML Kit + NSFW) | ~35 min | **70.5 min** | **2.0× the estimate** — 93 k frames at ~45 ms each |
| gender vote (`FaceTracker.finish`) | unbudgeted, feared a long stall | **2.7 min** | 2 906 crops; not the wall this plan expected |
| render (Transformer + GL) | ~30 min | **~9.7 min** | **3× cheaper**; extrapolated from 64 % done in 6.2 min |
| htdemucs separate | ~80 min | not run | soak stopped here deliberately |
| mux | ~5 min | not run | |
| **combined** | **~2.5 h** | **~3.1 h projected** | passes 1–2 **measured** at 82.9 min; separate carried at M2's 0.65× |

The total moved only ~25 %, but the *shape* is completely different: analyze is the expensive pass, not
render. Anything that optimizes rendering is aimed at the wrong stage — and 10 fps sampling, not GL, is
what a mid-range device will struggle with. Peak RSS through render was **985 MB**; thermal status never
left 0 (AP ≤ ~50 °C) across 79 min on a charger, so the PRD's thermal-yield path was never exercised.

1. **The 6-hour foreground-service cap.** `JobNotifications.kt:64` runs as `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING`; targetSdk 36 means Android 15+ allows that type 6 h per 24 h *cumulative per app*, then calls `onTimeout()` and WorkManager stops the worker. Splitting into several work requests does not help — the budget is per-app, not per-service. **Measured 2026-07-27:** combined on a 155-min film projects to **~3.1 h on an S23**, i.e. roughly half the budget, not on the line — but the budget is *cumulative per 24 h*, so a second film the same day still hits it, and the unmeasured mid-range multiplier (open question 4) puts one film back on the line at ~2×. Softer than feared on a flagship, unchanged as a design constraint.
2. **No resume.** Every shape returns `Result.failure()`, and `finally` deletes every temp (`FilterWorker.kt:96`, `:174`). Timeout, OOM, reboot, thermal kill, or a second Continue tap (`JobController.kt:34` uses `REPLACE`) discards 100 % of the work.
3. **`FaceTracker` grows for the length of the film.** `tracks` never evicts (`FaceTracker.kt:30`) and every track holds up to 5 crop `Bitmap`s alive until `finish()` at the very end (`:99`). ML Kit mints a new tracking id per shot; a feature film is thousands of shots. ~~Back-of-envelope: ~4 000 tracks × ~2 crops × ~150 KB ≈ **500 MB retained** — unmeasured~~ — **measured 2026-07-27: 3 362 tracks, 2 906 retained crops, and VmRSS fell 988 MB → 481 MB the instant `finish()` recycled them, i.e. ~500 MB retained.** The estimate was right to within a rounding error. What it got *wrong* is the consequence: this did not break anything. Peak RSS was 985 MB, well inside the budget, because the crops are freed before htdemucs (the real RAM peak, 1.30–1.45 GB) ever starts. So this is a **real 500 MB of waste on the wrong side of the job, not a crash risk** — which demotes it from "the first thing that breaks" to an optimization, and it stops being free the moment a device with less headroom, or a longer film, is in play.
4. **Disk.** `Preflight.kt:76` requires `(tempCopies+1) × source + 2 GB`; combined is **3× source + 2 GB** (~16 GB free for a 4.5 GB film). Temps live in `cacheDir` (`FilterWorker.kt:83`, `:147`), which the system may reclaim under storage pressure — during a multi-hour job that is itself filling the disk.

## Phase 0 — measure, then fail honestly

**Exit:** a 2 h run produces either a saved file or an up-front refusal with a number in it. No silent five-hour losses.

- [x] **Build the 2 h asset.** ~~Concatenate the existing 5-min QA clip 24×~~ — unnecessary: `qa-assets/movie-test.mp4` is a real **155 min** film (1728×720 @ 23.976 fps, H.264 1.14 Mbps + AAC 44.1 kHz stereo, 1.33 GB). Better than the planned concat, which would have been useless for gate/face QA; this one carries real shot changes, so the FaceTracker growth it provokes is the real number rather than 24 copies of one clip's tracks. Staged on the S23 per `adb-input-scaling-test-device`: push to `/sdcard/Download`, then `run-as … cp` into `files/` (MediaExtractor cannot open the external paths).
- [x] **Run one instrumented combined soak** on the S23 — `work/JobStats.kt` logs per-stage wall clock, peak RSS (kernel `VmHWM`, not a sampled max), worst thermal status and the `FaceTracker` counts, one greppable `SOAK` line per stage. Run 2026-07-27 on the 155-min film; table above updated. **Stopped by hand after render** rather than run to completion — the remaining two stages are the two already measured at 5 min, and the caller judged another ~1.7 h of soak not worth the information. So `separate`/`mux` at feature length stay projected, and the end-to-end "does a 2 h job finish" claim is **not** proven. The three things the soak was actually for — where the time goes, where the memory goes, and whether it survives — are answered.
- [x] **Is 4 GB a wall for `MediaMuxer`? No.** Answered directly by `spike/MuxerLimitSpike.kt` (debug intent `--ez muxer_limit_probe true`) instead of via the soak output — which was the right call twice over: the film re-encodes to ~1.3 GB, so the soak would never have reached 4 GB and would have "passed" while proving nothing. The probe loops one real video track into a single `MediaMuxer` until the file passes 4.5 GiB, then seeks back to the sample that crossed 4 GiB and walks to the end. Result on S23 / API 36: **4 831 840 641 bytes, 921 943 samples, `offsetTable=co64` (64-bit offsets), readback intact, `VERDICT=OK`.** So the framework writer switches to `co64` on its own, the hypothesised 32-bit `stco` wall does not exist here, and **Phase 2's concat is not blocked by file size** — no bitrate cap, no split output. Caveat: one device, one API level; `MediaMuxer` exposes no way to *request* 64-bit offsets, so this is observed behaviour, not a contract.
  - Two false results on the way, both instrument bugs rather than findings, both worth remembering: the hook re-fired on activity recreation and ran **two probes into one output file**; and the verdict logic asserted monotonically increasing PTS, which **any H.264 stream with B-frames violates by design** (samples arrive in decode order) — that alone flagged 48 % of samples and produced a confident `VERDICT=BROKEN` on a healthy file. A probe that can cry wolf is worse than no probe.
- [x] **Duration warning + ETA.** `work/Eta.kt` (`estimateMs(durationMs, ops)`, factors 0.54 censor / 0.68 music / 1.3 combined, each carrying the measurement it came from) + `EtaTest`. `OptionsScreen` probes the source duration off the main thread and shows "Estimated at least ~N on this phone" above Start; above `Eta.CONFIRM_THRESHOLD_MS` (30 min) Start first opens a confirm dialog, placed **before** the permission launchers so the user isn't asked for notifications only to then back out. A **warning, not a refusal** — confirming lands exactly where a short job's Start does. A failed probe hides the estimate rather than blocking Start; an unreadable source is `Preflight`'s story to tell.
- [x] **Refine the ETA live.** `JobStats.etaMs` extrapolates from this device's own observed rate and the worker publishes it as `FilterWorker.KEY_ETA_MS`; `JobsScreen` shows "~N remaining", and — added because it is the only surface a user sees during a multi-hour job, with the app closed and the phone locked — **so does the foreground-service notification** ("Removing music · ~12 min remaining"). Verified live on device from `dumpsys notification`, localized, RTL correct.
  - **Known ceiling, now measured:** the extrapolation is straight-line over overall percent, which assumes the progress bands are proportional to real cost. They are not. analyze+vote spend 25 progress points on 73 min while render spends the same 25 points on ~10 min, so the ETA over-promises the moment render ends and htdemucs starts. Reweighting the bands to the measured costs needs the `separate` number at feature length, which the shortened soak did not produce — so it is deliberately left as-is rather than re-guessed.

Ship Phase 0 independent of the decision gate. It is worth having even if long films are declared out of scope forever.

## Phase 1 — fixes that are correct regardless

**Exit:** a 45-min input behaves the same as a 5-min one. Verified on device, not just by tests.

- [x] **Vote and recycle per track** — when a track hits `MAX_CROPS` or goes unseen for N seconds, run `GenderVoter`, keep the verdict, recycle the bitmaps, move the track out of the live map. Kills the retained-bitmap peak *and* spreads the multi-thousand-inference `finish()` stall (currently silent, progress frozen) across the pass.
  - **Measured A/B on the 155-min film, 8 min analyzed:** 151 cumulative tracks but **peakLiveCrops=12, liveTracks=1**, and **peak RSS 500 MB against Phase 0’s 988 MB during analyze**. The ~500 MB of retained crops is gone and the peak is bounded by *concurrent faces*, not film length. `finish()` fell from **2.7 min to 1 ms**. The decision is the pure `trackAction`; `edlFor`/`isStale`/`padRect` came out with it, so the logic that moved is JVM-testable (13 tests).
  - Two real bugs surfaced in review *after* the first draft passed: `maybeStoreCrop` never consulted `voted`, so a track still on screen after its vote refilled with up to 5 more bitmaps nothing would ever vote or recycle — inflating the exact metric this item exists to move; and `voteAndRecycle` was not idempotent, so a second call voted an empty list, got UNKNOWN, and **silently downgraded a FEMALE verdict to uncensored**. The guard now lives inside the function so no caller can reopen it. Emission is sorted by `startMs`, restoring M1 ordering (rendered pixels never depended on it — `Edl.regionsAt` unions rects).
- [x] **"≈4 h — plug in" at the confirm step.** Deliberately *not* a `setRequiresCharging` constraint: that is the one item that would not be correct regardless, and it breaks this phase's own exit criterion — a 30 s clip would sit `ENQUEUED` with no explanation until the user plugs in, which needs "waiting for charger" UI that does not exist. `setRequiresBatteryNotLow` has the same mystery-queued problem at 14 %. A sentence of copy costs nothing and leaves the choice with the user. Shipped as copy in `dlg_long_job_body`, both locales.
- [x] **`ExistingWorkPolicy.REPLACE` → `KEEP`** plus a "job already running" message, so a stray tap can't nuke four hours of work. `KEEP` is the half that cannot be raced; `OptionsScreen` also observes the job flow and disables Start with `opt_job_running` above it, so the stray tap never happens.
  - **This exposed a total break in the failure channel.** `FilterWorker` put `KEY_OUTPUT_MESSAGE` as a String while `JobsScreen` read it with `getInt`, so `Data.getInt` fell to its default on **every** failure and the UI only ever showed “Filtering failed” — and `Preflight`’s eight hardcoded English sentences bypassed the Arabic translations that had existed since the localization pass. `Preflight` now returns `@StringRes` ids; an unrecognized cause resolves to `err_generic` rather than leaking the throwable’s untranslated text to the screen (the stack still goes to logcat).
- [x] **Temps to `filesDir`** + a stale-temp sweep at startup. `cacheDir` is explicitly reclaimable by the system. Landed as **`noBackupFilesDir/naqi-work/<key>/`** (`work/JobStore.kt`), one directory per job, keyed by SHA-256 over the source plus every option that changes the output — not the WorkManager id, which changes when Resume enqueues a new request. `noBackupFilesDir` rather than `filesDir` because several GB of job-local scratch has no business in a cloud backup, and the platform excludes that directory by construction (better than an `<exclude>` in two backup XMLs a later edit can forget).
  - **The sweep is age-based, not “delete every temp at startup”** — the obvious reading would delete a killed job’s completed segments, the one thing Phase 2 exists to keep. It descends only into `naqi-work` and only into entries untouched for 7 days; a finished or cancelled job deletes its own directory immediately. It runs at the head of `doWork`, the only entry point that always runs (WorkManager can restart a persisted job after a reboot with the app never opened).

Smaller, same phase:

- [x] `check(emitted == totalFrames)` (`DemucsSeparator.kt:106`) fails the entire job at ~98 % if the two decode passes disagree by one frame. Acceptable at 5 min; not at 5 h. Needs care — it is a real invariant, so relax it deliberately (tolerance + log) rather than deleting it. Relaxed to a ~100 ms tolerance with the shortfall exposed for the caller to log — **but the honest finding is that the equality was structurally unreachable, not a live trap**: `finish` drives the chunk grid to `shiftedLen` unconditionally and `flush` walks the whole `[MAX_SHIFT, shiftedLen)` window, so `emitted` lands on `totalFrames` even when the stream pass feeds nothing (short input reads as zeros). Resume does not change it either — it suppresses the emit *call*, never the counter. The invariant that can actually catch a short stream pass is the PCM scratch length, now asserted in `AudioPipeline`.

Considered and dropped: removing `AudioPipeline`'s second decode pass (`:78` stats, `:101` stream) by estimating whole-track mean/std from a subsample. It saves ~3 min out of ~150, and it changes the normalization for *every* video — including the 5-min clips already device-verified — so it trades existing verification for a 2 % win on a QA-blocked path.

## Decision gate

**Is a 60–150 min film a use case we support, or is Naqi a tool for clips?**

**Answered 2026-07-28: FILMS.** The caller asked for Phase 1 and Phase 2 to be implemented, which is this
question answered in the affirmative. The PRD's "job resume after process death" non-goal (`:12`) is now
contradicted by shipped, device-verified behaviour, and **has been removed from the PRD** (2026-07-28) with a
note pointing at the evidence. `prd:70`'s "never materialize all 4 stems (2 h movie …)" line STAYS — under the
films answer it is a live constraint, not a leftover.

- *Clips* → stop after Phase 1. Set the cap where the measured numbers say the experience is still honest, write it into the PRD scope, delete the "2 h movie" line from `:70`, done.
- *Films* → Phase 2, and the "job resume after process death" non-goal comes out of the PRD.

Answering this is worth more than any code below it.

## Phase 2 — segment + checkpoint (gated)

**Exit:** a 2 h combined job survives being killed at any point and resumes within one segment's worth of work; total runtime may span more than one FGS session.

**Done 2026-07-28, exit criterion met and verified by SIGKILL on the S23** — three times, at each stage that
can be interrupted. Verified on `qa-assets/women-music-3min-video.mp4` (193 s, 1920x802 @ 23.976, faces +
music) with `--el segment_ms 60000` forcing 4 segments; the 30-min production threshold is far too long an
iteration loop for this. **The kill/resume evidence:**

| killed during | state that survived | what the resumed run redid | cost |
|---|---|---|---|
| analyze, mid-segment 2 | `an-000.json`, `an-001.json`; segment 2's in-flight work correctly NOT persisted | segments 2-3 only; 0-1 replayed from checkpoint with identical firing counts (105, 136) | — |
| render, mid-segment 2 | all four `an-*.json`, `seg-000.mp4`, `seg-001.mp4`, plus `seg-002.mp4.part` | segments 2-3 only (`already done, skipping` for 0-1); the stale `.part` discarded | **16.7 s vs ~3 min** |
| separation, chunk 20/100 | `audio.pcm` = 6 791 400 B and `audio.json` claiming 1 697 850 frames — **exactly equal**, and exactly `20*STRIDE - MAX_SHIFT` | one chunk of inference (chunk 19), then continued at 20; analyze 129 ms and render 66 ms, both fully skipped | **5 s vs 37 s** |

Atomicity held in every case: a file under its final name always meant complete work, and the one partial
export was sitting under `.part` where `isRendered()` correctly refused it. WorkManager restarted the worker
by itself after each SIGKILL, so **the automatic resume trigger needed no code** — `Result.retry()` turned out
to be unnecessary, because a stopped (as opposed to cancelled) worker is already rescheduled, and the
checkpoints are what make that reschedule cheap.

Process the timeline in ~5-minute segments; checkpoint after each; concatenate at the end. One mechanism handles walls 1–3: memory resets per segment, an interruption costs one segment, and the job can span multiple foreground-service sessions and days.

Cheaper than it sounds, because three pieces already exist. **Prove the concat first** — everything below it is wasted if that fails:

- [x] **SPIKE: encoder CSD equality across segments.** The concat assumes two separate `Transformer` exports emit identical SPS/PPS. Usually true with identical settings, not guaranteed. `MediaMuxer` accepts one format per track, so a mismatch means an undecodable file. Two segments and a diff — before anything below is scoped. **PASSED on S23 / API 36** via `spike/SegmentConcatSpike.kt` (`--ez segment_concat_probe true`), which answers three questions from two exports because they share the same work: `CSD_EQUAL=true` (byte-identical SPS `…6764003cacb403c0113f2cd4…` and PPS `0000000168ee06f2c0`), `REBASED=true` (segment 1 starts at PTS 0, not 5 000 000), and the joined file **decoded all 299 frames** with `maxPts=9933333us` = exactly `5000000 + 4933333`. The verdict is a real `MediaCodec` decode, not a format comparison — the failure being hunted is a file that muxes fine and decodes wrong. Neither of `MuxerLimitSpike`'s two cry-wolf bugs was repeated (an `AtomicBoolean` guards the re-fired hook; nothing asserts monotonic PTS).
  - The rebasing was **also confirmed from the media3 1.10.1 sources**, not just measured: `ExoAssetLoaderVideoRenderer.java:185` computes `presentationTimeUs = decoderOutput.presentationTimeUs - streamStartPositionUs`, and :186-187 drops frames whose rebased PTS went negative — which is both why the offset is exactly `startMs*1000` and why clipping is frame-accurate on the transcode path. The same rebasing means a GL effect sees CLIP-RELATIVE time, so `CensorGlEffect` took a `timeOffsetMs`; **verified at the pixel level** by censoring 6000..8000 ms, a window lying only inside segment 1 — frames at 6.5 s and 7.5 s came back blurred and 1.0/3.0/4.5/9.0 s came back sharp.
- [x] **Checkpoint file** keyed by input Uri + ops hash. Pass 1's state is already serializable — `Edl.toJson()` (`Edl.kt:41`) exists and is unused by the worker today. The checkpoint unit is one **completed segment**: mid-segment state (the firings list, the live `FaceTracker` map) is deliberately not persisted. `work/Checkpoint.kt`. **No manifest**: every file is written to `<name>.tmp` and renamed, so a file existing under its final name *means* it is complete — which removes any way for a checkpoint to reference a half-written segment, and any manifest to keep in sync. `an-NNN.json` per segment (its firings + face-track EDL), `seg-NNN.mp4` per render, `audio.pcm`/`audio.json` for the separator.
- [x] **Per-segment analyze + render** — Media3 `ClippingConfiguration` gives frame-accurate clipped exports; `FaceTracker` is constructed per segment, which fixes its growth by construction. Segment length comes from the per-segment fixed cost measured in Phase 0 (encoder + ORT session init), not a hardcoded 5 min — 24 joins vs 12 is a real difference in both seam count and CSD risk. `FrameSampler.sample` gained a `[startMs, endMs)` window (seek to the preceding sync sample, discard below the start, anchor the 10 fps grid to the window so a segment samples the same timestamps fresh or resumed); `RenderPipeline.renderCensor` gained a `RenderSegment` that clips, drops audio and offsets the EDL. **The EDL is still assembled globally**: the gate's hysteresis merges firings across boundaries, so building it per segment would clip up to 1.5 s of censoring at every seam. Segment length is a 5-min constant (`SEGMENT_MS`) — the per-export fixed cost measured only ~0.5-0.7 s, so 31 segments cost ~19 s on a film and the plan's "derive it from the fixed cost" lands anywhere between 1 and 10 min with no practical difference.
- [x] **Concatenate** rendered segments — `Remux` already does sample-copy muxing; given the spike above, this is a PTS-offset walk. `Remux.concat`, one muxer pass over N segments + one audio track (no intermediate joined file — on a film that would be another 4+ GB of scratch). **Offsets are the intended segment starts, not a running sum of measured durations**: accumulating would fold a sub-frame rounding error into every following segment, up to ~1 s of A/V drift across 31 joins against the single continuous audio track, 20x past the PRD's 50 ms budget. **Measured on the 193 s asset: output 192.917 s vs source 192.911 s (6 ms), and the audio track came out at 9 044 frames — identical to the source, copied verbatim.** A runtime guard compares each segment's mime/geometry/csd against the first and fails loudly rather than writing an undecodable file.
  - **Known seam cost, measured:** ~2 frames are lost per boundary (4 619 vs 4 625 frames; largest inter-frame gap 148 ms at the 60 s seam against a normal 41.7 ms). That reads as a ~100 ms freeze at each seam — **no drift, no desync**, and at the 5-min production segment length a film has ~30 of them rather than this test's 3. Deliberately not chased: the fix is a per-segment overlap plus a drop rule and a new monotonicity invariant, to buy back 1-3 frames per five minutes.
- [x] **Resumable audio** — the overlap-add ring can't span segments, so write separated PCM to an append-only temp with the ring state checkpointed, and do the single AAC encode at the end. Budget **~1.3 GB of scratch for a 2 h film** and carry it into `Preflight` — wall #4 does not currently account for it. *The only genuinely fiddly part; scope it separately before committing to Phase 2.* Separated output appends to an **int16 LE stereo 44.1 kHz** scratch (635 MB per hour of source; **1.645 GB for the 155-min film, not the 1.3 GB budgeted here** — the rate is now carried into `Preflight` from duration), and ONE AAC encode reads it back at the end. **The ring state is not serialized**: on resume the source is re-decoded (cheap next to inference) and inference is skipped for chunks already emitted, restarting exactly `ceil(SEG/STRIDE)-1 = 1` chunk early to rebuild the overlap-add — `skipChunks = ((MAX_SHIFT + framesEmitted) / STRIDE - 1)`. Proved **bit-exact** by unit test (`toRawBits` against an uninterrupted reference at 6 chunk boundaries and 5 arbitrary points), with the `inferCalls` count as the other half of the proof: over-skipping breaks the bit comparison, under-skipping breaks the count, so an off-by-one either way fails without shipping a test hook.
  - **44.1 kHz, not 48, is load-bearing.** Resampling before the scratch would restart the 44.1→48 Sonic session at the resume seam (different interpolation phase, a click, a rounding-different frame count). At 44.1 kHz there is one uninterrupted session at the end and 1 scratch frame == 1 separator frame, which is what makes `framesEmitted * 4` an exact byte offset — confirmed on device, where the checkpoint and the file agreed exactly. int16 measured 87.3 dB round-trip SNR, ~50 dB below what AAC-LC at 192 kbps discards; f32 would double the disk for nothing, and disk is wall #4.
- [x] **Resume triggers** — `Result.retry()` with backoff on stop reasons that warrant it, **plus a user-triggered Resume in the app**. If the FGS budget is exhausted WorkManager cannot start another one, but the user reopening the app and tapping Resume works regardless of how open question 3 resolves. A button de-risks the whole scheduling question. **`Result.retry()` turned out to be unnecessary** — WorkManager reschedules a *stopped* (as opposed to cancelled) worker by itself, which was observed restarting the worker after every SIGKILL; the checkpoints are what make that restart cheap. What did need writing is the distinction between a stop and a cancel: only `WorkInfo.STOP_REASON_CANCELLED_BY_APP` deletes the work directory, because the 6 h FGS cap, an lmkd kill and a reboot all arrive as cancellation too and those are exactly the cases this phase exists to survive. Pre-31 has no stop reason and therefore keeps the work — an orphan the 7-day sweep collects is a far cheaper mistake than deleting three hours of rendering. The user-facing Resume button is in `JobsScreen`, shown when a failure reports `KEY_RESUMABLE`.

## What is still open

Phases 0–2 are closed. The four things this verification exposed or could not reach — a real film's AC-3 audio
having no resume path, the ~2 frames lost per seam, the unexplained resumed-separator timing, and the fact
that feature length has still never been run to completion — plus the long-standing QA-set and 778G blockers,
live in **`long-film-followups.md`** with an exit criterion each.

## Open questions

1. **Is a film in scope at all?** (the gate above) — everything else is downstream of this.
2. **Does the user watch, or leave it overnight?** If overnight-plugged-in is the real usage, the honest design is "queue it, notify when done, survive a reboot" — which pushes Phase 2 up and makes the wall-clock number nearly irrelevant. If they wait, no amount of engineering makes 5 h acceptable and the cap is the answer.
3. **What resets the FGS 6 h budget?** Documented as per-app/24 h; the reset semantics (app brought to foreground?) need confirming against current docs before any Phase 2 scheduling design leans on them. The user-triggered Resume above is the hedge — it works whatever the answer turns out to be.
4. **What is the real 778G multiplier?** Every number in this repo is S23. The whole plan is sized on a device we do not have (`tasks.md:15`, open since M0).
5. **`vocals`-only silences sound effects** (`prd:95`) — accepted for clips. Is it still accepted across a 2 h film, or does long-form force `vocals+other` to be the default?

## Deliberately not doing

- Making the pipeline faster. htdemucs at ~1.3× realtime is the floor for this model; a smaller model is a different project.
- Mid-export resume inside a single Media3 export — Transformer has no such API. Segmentation is the supported path.
- Background-queue UI, multi-job scheduling, or per-segment previews. Not the problem being solved.
