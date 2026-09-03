# Feature ideas — 2026-08-06

Produced by a fan-out over the codebase and `docs/`: four grounding passes (architecture and seams,
a decision ledger of everything the docs already rejected, the UI surface, the hard constraints),
seven independent ideation lenses that could not see each other, then two vetting passes per
candidate — one checking the seam actually exists by reading the source, one checking against the
ledger for "already shipped" and "already rejected". 70 raw ideas → 26 merged → 23 survived → 28
after a completeness critic.

**How to read an entry.** *Effort* is the vetting pass's revised number, not the proposer's.
*Lenses* is how many of the seven independently arrived at it — convergence is signal.
**The cheaper version is usually the one to build**: in most entries it is the vetting pass
stripping the idea back to the half that carries the value, and several are one tier of effort
below the headline.

Every entry here can ship in a Google Play build. Nothing was ranked on novelty.

## What the sweep found

- The pipeline knows everything and the app says nothing: coverage, interval counts, face-track retention, >MAX_REGIONS fallbacks and per-stage timing all go to logcat (JobStats.kt:60,:65 are Log.i-only). Six of seven lenses independently proposed surfacing it — the cheapest large win in the set is data that already exists on disk.
- Two entry points, two different answers. Prefs.ops() carries 2 of 8 FilterOps fields, so the picker and the share sheet disagree about strictness, blur, style and whole-frame — and the disagreement is invisible in both directions.
- Every irreversible action is unguarded: 'Delete original' fires with no verification of the output, the output carries no capture date, a cancelled job vanishes with its checkpoints, and a fragmented MP4 offers a Resume that can never succeed. This cluster is small, cheap, and the only place in the app where a bug destroys the user's data.
- The EDL is the seam that keeps paying: whole-frame blur, the >8-face overflow promotion and half the good ideas here land in Edl/FaceTracker as pure JVM with zero pass-2 change. Everything that needs a second renderer, a duplicated sigma curve or a preview surface immediately doubles in cost.
- Nothing in this list is Play-blocked, but the app is: REQUEST_INSTALL_PACKAGES and the self-updater still ship on main, and the NOASSERTION NSFW weights are unresolved. That work already exists on origin/cut-download — release engineering, not a feature, and it gates every user this list is written for.

## The list

| # | Feature | Effort | Lenses | Tier |
|---|---|---|---|---|
| 1 | A stopped job says so, and Delete original waits for proof | L | 3 | do-next |
| 2 | Every setting is remembered, and the share sheet stops lying | S | 4 | do-next |
| 3 | What was covered, and the sentence about Women/Men nobody shipped | L | 6 | do-next |
| 4 | The filtered copy keeps the day it was filmed | S | 1 | do-next |
| 5 | Accept audio shares | M | 2 | do-next |
| 6 | A real sample before the irreversible render | L | 6 | strong |
| 7 | The job that can never succeed, and the one that can never resume | M | 1 | strong |
| 8 | Stop disabling Start while a job runs | M | 3 | strong |
| 9 | Know before you start, and clean up from inside | L | 2 | strong |
| 10 | Make the controls speak, and stop the blur slider lying | M | 1 | strong |
| 11 | Keep the scores, not the verdicts | L | 3 | strong |
| 12 | Close the coverage holes in the EDL builder | S | 2 | worth-it |
| 13 | Audio-only output from a video | S | 1 | worth-it |
| 14 | Silence the audio under covered scenes | M | 1 | worth-it |
| 15 | Ship a 117 MB app instead of a 221 MB one | M | 1 | worth-it |
| 16 | Four presets | L | 2 | worth-it |
| 17 | Something got through | L | 1 | worth-it |
| 18 | Show the permissions Naqi does not have | M | 1 | worth-it |
| 19 | Say when almost nothing was covered | M | 1 | worth-it |
| 20 | Choose how big the output is | M | 1 | speculative |
| 21 | Filter only the part you'll watch | L | 2 | speculative |
| 22 | Lock the rule | L | 2 | speculative |
| 23 | Recitation and nasheed pass through untouched | S | 1 | speculative |
| 24 | Make the Play build physically incapable of the downloader | XL | 2 | speculative |
| 25 | Translation harness, then Indonesian and Urdu | L | 1 | speculative |
| 26 | Still photos as input | L | 1 | speculative |
| 27 | Stop quietly flattening HDR | M | 1 | speculative |
| 28 | Audition the music removal | XL | 2 | speculative |

---

## Do next

*High value, bounded work, and each one fixes something the app currently gets wrong.*

### 1. A stopped job says so, and Delete original waits for proof

`L` · 3 lenses

**A job killed by the FGS cap, an lmkd kill or a reboot tells you in a notification instead of silently vanishing, and the 'Delete original' button is withheld until Naqi has checked the output is actually playable.**

*Why.* JobNotifications has done() at :135 and no failure or stopped function anywhere in the file, so on a ~3.1 h film the user walks away and must reopen the app to learn it died. WorkInfo.CANCELLED matches none of the three states JobsScreen tests (JobsScreen.kt:84-86), so a system-stopped job renders 'No job running.' with its checkpoints still on disk. And done() adds a Delete-original action at :176 with nothing between the mux and that button verifying the output — a user who taps it on a truncated file has destroyed their only copy.

*How.* Ship pieces (3) and (4) first; each is S and independent of everything else. (3) failed()/stopped() on the existing LOW channel, reusing Preflight's @StringRes taxonomy so a failure re-localizes if the language changes. (4) before done() at :1277, probe the published uri and compare duration and size against the source's probed durationMs, omitting the Delete action when unverified — durationMs is not in scope in succeed() (:1268-1282) so it must be threaded, and it is 0 for the audio-only shape and any unprobeable source, where 'unverified' must mean 'no verification available', not 'hide Delete forever'. The paused card must NOT count checkpoints from the UI: that reproduces jobKey (a private `by lazy` with inline Data reads and the "plan4" literal, :137-180) and re-runs planFor's MediaExtractor sync-snap. Have the worker write filesDir/paused.json {uri, ops, jobKey, segmentsDone, segmentsTotal} from the cancellation catch instead — one file, no key duplication, and it carries (uri, ops) so Resume survives process death, which is the actual reason NaqiApp.kt:87's onResume is nullable.

*The catch.* The paused card is dead without a Pause marker: a real user Cancel already deletes the work dir at FilterWorker.kt:466, so 'the checkpoints are on disk' is only true after Pause exists — and Pause contradicts shipped copy in both locales (strings.xml:141 and values-ar:137 say the job 'can't be paused, only cancelled') and sits beside a green PRD acceptance criterion (prd:101, 'Cancel mid-job leaves no partial file'). The verification tolerance is genuinely unsettled: 42.67 ms AAC priming is a carried issue and segmented concat has its own seam behaviour, so too tight cries wolf and too loose passes a file truncated exactly where a MediaMuxer failure truncates it. JobStore.sweep ages a job by newest file mtime and is marker-blind, so a deliberately parked job dies at day 7 like an orphan.

*Cheaper version.* Ship only (3) the failure/stopped notification and (4) verify-before-Delete — S each, no new state, no cancel-contract change, no promise string edited. Add the cancelled branch in JobsScreen (counting Checkpoint.isRendered/analysisFile) as a third S. Leave Pause and the ENQUEUED split out entirely; the ENQUEUED split is not free either, since it changes what a share queued behind a running job shows today.

### 2. Every setting is remembered, and the share sheet stops lying

`S` · 4 lenses

**The six tuning fields that reset on every cold start are persisted, and the share sheet shows the settings it is about to apply instead of silently reverting to defaults.**

*Why.* Prefs.ops() (Prefs.kt:52-59) returns only removeMusic and censorWho, and ShareSheet.kt:96 builds its item from exactly that — so a user who sets strictness 90, whole-frame and black fill on Options and then shares a clip from WhatsApp gets strictness 40 and rect blur with nothing on screen admitting it. The bigger win the pitch under-sells: NaqiApp.kt:87's Resume re-enqueues with the in-memory ops, which after process death is a bare FilterOps(), so today a Resume on a cold start hashes a different jobKey and silently re-analyzes a 3-hour film from scratch. plan-whole-frame-blur.md:227-232 already lists this as its own open item, verbatim: 'Persist all five together or none.'

*How.* Six getX/putX pairs in Prefs for wholeFrameBlur, strictness, blurAmount, grayscale, solidColor, keepStems, taking FilterOps' own defaults — do NOT copy Queue.opsFromJson's fallbacks verbatim, because Queue defaults removeMusic=false while Prefs deliberately defaults it true (Prefs.kt:54, 'both filters on — the reason someone installed Naqi'). Prefs.save's signature is save(context, ops, quality), so OptionsScreen needs a saveOps overload that does not clobber KEY_QUALITY. Do NOT seed NaqiApp.kt:37 from Prefs.ops: FilterOps.kt:29-33 names NaqiApp as one of the reasons the bare constructor must keep any==false, and Prefs.ops returns removeMusic=true + WOMEN, which would flip the Pick screen to open with both filters pre-armed and Continue already live. Prefs is not JVM-testable (junit + org.json only, no Robolectric), so put the six-field read/write behind a pure mapper over a (key, default)->value lambda or it lands with zero gate coverage. The mandatory other half is a static, non-tappable summary row in ShareSheet — plan-censor-who.md:128-131 rejected a Who control there for costing vertical space to re-ask an answered question.

*The catch.* Persistence WITHOUT the summary row is strictly worse than the bug: an invisible setting that resets is annoying, an invisible setting that sticks forever is a silently wrong 3-hour render. Specifically, a remembered wholeFrameBlur=true routes shares into a combination nobody has QA'd — plan-whole-frame-blur:218 records the share sheet as always-rect and whole-frame as untested on the segmented path. The summary row's ' · ' join is a bidi hazard in Arabic and needs its own short strings; opt_whole_frame_title is 'Cover the whole frame', a sentence, not a chip label.

*Cheaper version.* Persist the six fields and add the ShareSheet summary row. Skip the Reset-to-defaults row on the censor card, and touch NaqiApp's seed not at all. Options already displays every value it will use; the share sheet is the only surface that lies.

### 3. What was covered, and the sentence about Women/Men nobody shipped

`L` · 6 lenses

**The finished job says what it covered — covered time, scene count, face spans, music found — plus the one honest line about the gender classifier that the plan wrote in August and no string in the app carries.**

*Why.* Six lenses independently found the same hole: the pipeline computes coverage, interval count, face-track retention, >MAX_REGIONS fallbacks and per-stage timing and logs all of it to logcat (JobStats.kt:60,:65 are Log.i-only), where no user will look. A parent deciding whether to hand over the tablet gets a green check and a filename. It is also the only place the Women/Men honesty can live: FaceTracker.kt:302 censors on abstain and on a tie, so Women drifts toward Everyone and Men leaks — plan-censor-who.md:326-340 has had the disclosure text written since 2026-08-03 and it has never reached a string.

*How.* Add Edl.summary() in edl/Edl.kt — pure JVM, unit-testable beside the existing mergeRanges tests — and it MUST call mergeRanges(censorIntervalsMs) before summing, because censorSpans (FilterWorker.kt:1196) concatenates intervalsFor + overflowSpans unmerged unless whole-frame is on, so a naive sum double-counts. Carry scalars in the existing Result.success(workDataOf(...)) at :1281 (Data caps at 10 KB — scalars and a ~200-byte bucket map, never per-track lists) and persist the record to filesDir/reports/<outputName>.json. Fix this first or every failed queued item grows a fake receipt: a queue-driven failure returns Result.success (QueuedWorker.kt:46) and JobsScreen treats SUCCEEDED as saved (:85,:143), so key the receipt off the report file existing, not off WorkInfo.SUCCEEDED. Hoist `edl` out of the branches lambda at :427 — it is not in scope where succeed() is called. And do not call the face number 'faces': trackCount is cumulative and every untracked detection becomes its own one-frame track (FaceTracker.kt:125,129), so say 'face spans covered'.

*The catch.* Coverage% means different things in rect and whole-frame mode, so scenes and faces must be reported separately and phrased mechanically ('covered'), never evaluatively — and the word 'receipt' implies a verification this does not have; these are the pipeline's own self-report. There is a real chance the honest numbers reduce trust, which is correct if true but should be a deliberate decision rather than a surprise. Reports accumulate in filesDir with no sweep (JobStore.sweep descends only into naqi-work). Percentages and durations must use the whole-string pattern (Components.kt:290) so Arabic keeps its digits, against 163 strings per locale today.

*Cheaper version.* Summary scalars + a coverage strip + the Women/Men honesty line in SavedCard, and the headline in JobNotifications.done. Drop the proof-frame grid entirely — it is 40-60 MediaMetadataRetriever decodes returning FULL-resolution Bitmaps (~8.3 MB each at 1080p, ~400-500 MB against a 1.5 GB ceiling; getScaledFrameAtTime is API 27+ and minSdk is 29), a GOP decode per tile because OPTION_CLOSEST starts from the preceding sync sample ~5 s back, a second screen, and one genuinely dangerous failure mode: a wrong URI showing source frames, i.e. the app displaying exactly what it exists to hide.

### 4. The filtered copy keeps the day it was filmed

`S` · 1 lens

**A filtered 2019 wedding keeps its 2019 date in the gallery instead of sorting above last night's clip, so the app stops inviting you to delete the only copy that knew when it happened.**

*Why.* Verified directly: Publish.pendingValues (work/Publish.kt:132-137) puts exactly DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH and IS_PENDING — no date column — and DATE_TAKEN appears nowhere in app/src at all (JobsScreen.kt:354 sorts the Library on DATE_ADDED, the row's insertion time). MediaMuxer and Transformer both stamp mvhd creation as now, so MediaStore has nothing to scan either, and outputName (FilterWorker.kt:1293) appends System.currentTimeMillis(), putting a 13-digit number where the date used to be. What makes this destructive rather than untidy is what the app does next: the done notification's Delete original leads to a dialog that says 'can't be recovered' (strings.xml:196).

*How.* One put of MediaStore.MediaColumns.DATE_TAKEN in pendingValues — it is on the shared MediaColumns superinterface since API 29, which is minSdk, so one addition serves the video and audio collections alike, exactly as that function's own comment describes. Source it with a fallback chain in media/Uris.kt next to containerDurationMs (that file exists to de-duplicate exactly this cursor dance): the source uri's DATE_TAKEN, else MediaMetadataRetriever.METADATA_KEY_DATE (the mp4 creation atom), else the source's DATE_MODIFIED, else null. Thread sourceUri into Publish.video/audio/muxedVideo; all five FilterWorker call sites already hold inputUri, and DownloadWorker's quarantined download legitimately has no date. GPS is deliberately out of scope and deserves a code comment so nobody adds it later: reading a source video's location needs ACCESS_MEDIA_LOCATION, a runtime storage-group permission the deliberately-absent permission list forbids.

*The catch.* The one judgement call is the no-date case, and it must resolve to leaving DATE_TAKEN unset (today's behaviour) rather than falling back to 'now' — a confidently wrong date is worse than an absent one. The realistic mechanical failure is a provider returning DATE_TAKEN in seconds rather than milliseconds, which shows up as a 1970 date and is caught by the device check: does Google Photos file the copy beside the original, or at today.

*Cheaper version.* The DATE_TAKEN put plus the Uris.kt fallback chain alone — skip the outputName rename. That is the half that fixes gallery sorting and makes the Delete-original offer honest. The epochMillis suffix is also what guarantees queued outputs cannot collide, so changing it interacts with batch picking; leave it.

### 5. Accept audio shares

`M` · 2 lenses

**The lecture MP3 or nasheed someone was just sent can go straight into Naqi from the share sheet, the way a video already can.**

*Why.* Release 1.3 made audio a legal input and the picker asks for ["video/*","audio/*"], but the manifest declares ACTION_SEND for text/plain and video/* only and MainActivity.sharedOf returns null for anything that is not video/ or text/ — so Naqi does not appear in the chooser when audio is shared, which is how audio actually arrives. On a Play build, where link-download is gone, share-in is the main way anything reaches the app at all. This is the cheapest correction of a shipped feature's reach in the whole set.

*How.* One <data android:mimeType="audio/*"> on the existing ACTION_SEND filter and one type.startsWith("audio/") branch in sharedOf, reusing the video branch's grantUriPermission verbatim (MainActivity.kt:120). The third piece is not cosmetic and must not be dropped: Shared.LocalFile carries no audio flag and ShareSheet seeds ops from Prefs.ops(context), which can arrive with removeMusic=false — with censor forced off for an audio source that gives effectiveOps.any == false and ShareSheet.kt:289-290 DISABLES the primary button, so a shared MP3 opens a sheet you cannot act on. Force removeMusic=true, matching the rule PickOpsScreen.kt:104 already has; today ShareSheet computes audioOnly = isLink && quality == AUDIO and only clears censorWho, so the two entry points already disagree. Do not ship ACTION_EDIT: Naqi writes a new file into Movies/Naqi (Publish.kt:132) and cannot honour edit-in-place.

*The catch.* Claiming audio/* puts Naqi in the chooser for every voice note in the OS, which is noise — keep it to ACTION_SEND and do not also claim ACTION_VIEW. The SEND_MULTIPLE half is where the effort actually lives and must not ride on the same estimate: senders routinely set type="*/*" on ACTION_SEND_MULTIPLE, which matches a video/* filter, so you receive lists containing images and PDFs and must resolve each URI with getType, drop non-media and say what you dropped; and ShareSheet is built around a single Shared — one title, one duration, one getInfo call, one primary label — so a list is a sheet redesign, not a branch.

*Cheaper version.* Ship the audio half alone: one manifest <data> line, one sharedOf branch, one forced removeMusic in ShareSheet. That is XS and it is the half the argument actually supports. Fold SEND_MULTIPLE into the batch-picking candidate so there is one answer about batching and one FGS-budget honesty problem, not two.

---

## Strong

*Clearly worth building. Larger, or carrying a dependency on something above.*

### 6. A real sample before the irreversible render

`L` · 6 lenses

**Render two seconds of your own video through the production censor path and play it inline, so blur amount, swatch, grayscale and whole-frame stop being blind choices against a re-encode that cannot be undone.**

*Why.* Six of seven lenses landed on this independently — there is no thumbnail, no before/after and no sample frame anywhere in the app, and the output is a re-encode of the user's only copy. Someone who picks whole-frame discovers three hours later that most of their film is a grey rectangle. Both halves of the seam are already parameterised and unused: FrameSampler.sample takes startMs/endMs (:118), and RenderPipeline.renderCensor takes segment: RenderSegment?, sets setClipStartPositionMs/EndPositionMs (:119-120) and passes segment.startMs as the EDL offset into CensorGlEffect.

*How.* Build the faces-only version: sample ~2 s at a fixed ~40% offset (never t=0, which is titles and black), never call Infer.nsfw, build Edl(emptyList(), tracks) from ML Kit alone, render through the real renderCensor, and play in a platform VideoView inside an AndroidView — media3-exoplayer is already resolved transitively via media3-transformer, so a real player is free and no new artifact is needed. Run it on a plain cancellable coroutine, never WorkManager, so it never spends the 6 h/24 h FGS budget that a 155-min film already half-consumes. Keeping ORT out of the UI process is a correctness requirement, not an optimisation: Infer.close() runs in the finally of every video shape (FilterWorker.kt:474,785,874) and Infer.kt:29-31 says it 'closes a session out from under run', so a preview running the gate while a queued job finishes is a native SIGSEGV — and Options is reachable while a job runs because shares enqueue independently. Drop the 'scan for the first NSFW firing' window selection: the scan IS the analysis, so it doubles the cost.

*The catch.* The headline claim — 'because it is the production path, the sample cannot disagree with the export' — is false in three nameable ways, so the copy must promise style and never coverage. overflowSpans short-circuits on a GLOBAL tracks.size <= MAX_REGIONS (FilterWorker.kt:1232), so a short window never trips the >8-face promotion; promoteFacesToFullFrame drops spans under MIN_FULL_MS=500 and BRIDGE_MS=400 bridges into neighbours outside the window; and NsfwGate.intervals pads every firing by PRE_MS 500 / POST_MS 1500. Separately, renderCensor forces setRemoveAudio(segment != null || removeAudio) (RenderPipeline.kt:127-128), so every sample is silent by construction — music removal, 65% of the wall, can never be previewed and the audio-only shape gets nothing. RenderPipeline.kt:113 makes passthrough require segment == null, so even a region-free window pays a full decode→GL→encode; there is no cheap case, and a fragmented MP4 without sidx fails clipped export, which is where a user would first meet it.

*Cheaper version.* The faces-only style preview at M: 2 s at a fixed offset, ML Kit only, no ORT in the UI process at all, production shader so CensorEffect.kt:145-155's sigma curve is never duplicated. It answers the four things people actually get wrong — blur too weak, black bar or grey, grayscale, does whole-frame eat the picture — and it is what makes a now-sticky wholeFrameBlur safe to ship. Resist the still-frame-on-a-Canvas fallback: a duplicated sigma rule is a drift generator, and a preview that lies is worse than no preview.

### 7. The job that can never succeed, and the one that can never resume

`M` · 1 lens

**A fragmented MP4 fails the segmented render every time, is flagged resumable, and so fails identically forever — while the same file finishes in 52.8 s on the route the app already has.**

*Why.* This is measured in the repo's own notes, not suspected. long-film-followups.md:312-320: the file plans 4 segments correctly, `render seg-1` throws ExportException: Asset loader error, Preflight.messageFor maps it to the generic 'Filtering failed', and KEY_RESUMABLE is set — so the user's only offered action is a Resume guaranteed to fail again, permanently. The note ends 'the fix is cheap and obvious if anyone wants it: catch ExportException out of renderSegments and re-run unsegmented, which is proven to work on this input.' The sibling at :334 is worse because it is silent: FrameSampler.probe failing gives durationMs=0, Checkpoint.plan returns empty and Preflight never looks at duration, so a film runs unsegmented and unresumable — and because Eta.estimateMs is also 0, the >30 min confirm cannot fire either.

*How.* Three changes, one theme: no route is a dead end. (1) Catch ExportException out of the segment loop in runSegmented and re-run the job unsegmented once, recording the retry in the checkpoint so it cannot loop — the fallback path is the code every short job already uses. (2) Fall back to Context.containerDurationMs (media/Uris.kt, currently used only by the audio path) when FrameSampler.probe yields 0; segmenting, checkpointing, the ETA and the >30 min confirm all come back at once. (3) When the plan still comes back empty, say 'this file can't be paused or resumed' before Start — the string followups:78 explicitly wanted and could not hang anywhere, because the same missing duration suppressed the dialog it would have ridden on. Prefer the probe when both answer; use the container only when the probe has nothing.

*The catch.* The unsegmented retry doubles the worst case on a file that was already going to fail — acceptable against failing forever, but it must be one retry recorded in the checkpoint, not a policy. This is repair work: it will not demo, and its value shows up in failure reports nobody is collecting yet. The verification asset is a real caveat — women-frag-nosidx.mp4 was staged on the S23 in July and qa-assets/ is gitignored (it holds four unrelated clips today), so it is one ffmpeg invocation to regenerate, not a file you can pick up. The whole class is invisible to the existing corpus, whose workhorse clips are AAC MP4s on the happy route.

*Cheaper version.* Piece (1) alone. The ExportException catch plus one unsegmented re-run converts a permanent dead end into a job that finishes, and it is a try/catch around an existing code path. Pieces (2) and (3) — the silent non-resumable half — can follow as their own item.

### 8. Stop disabling Start while a job runs

`M` · 3 lenses

**Pick several videos at once, tune once, and they run one after another through the FIFO queue that already exists and is currently reachable only by sharing.**

*Why.* The whole queue subsystem is built and device-verified for back-to-back items — queue.json, Queue.Item, QueuedWorker, APPEND_OR_REPLACE chains that survive process death, a QueueCard with per-item cancel and retry — and the picker path cannot reach it, because Start is hard-disabled while a job runs (OptionsScreen.kt:174). The person who owns the files on their phone has the worst path in the app; the person who shares links has the good one. The machinery is built and the door is locked.

*How.* PickOpsScreen.kt:99 swaps OpenDocument for OpenMultipleDocuments — not PickMultipleVisualMedia, which both vet passes rejected independently. Fix two live bugs first, because multi-select turns them from rare share-path accidents into guaranteed data loss: JobsScreen.kt:142's visible Cancel calls JobController.cancel = cancelUniqueWork(UNIQUE_WORK), which kills the ENTIRE chain with no re-append repair (contrast cancelItem at JobController.kt:163-175), so 'cancel this one' would silently destroy all five; and JobsScreen.kt:83 and OptionsScreen.kt:140 both use workInfos.firstOrNull() over the whole chain, so once item 1 SUCCEEDs the top card can show SavedCard while item 2 runs and jobRunning can flip false mid-queue, making Start a silent no-op under KEEP. Both need firstOrNull { !it.state.isFinished } ?: lastOrNull(). Skip the RELATIVE_PATH dedupe — a SAF document URI's provider does not expose MediaStore.MediaColumns.RELATIVE_PATH; match the app's own -naqi-<epochMillis>. name pattern instead.

*The catch.* The FGS budget is the wall, not the code: 6 h per 24 h cumulative and one 155-min film measures ~3.1 h, so a five-item queue of films is physically undeliverable in a day and the UI would be promising something the platform will not allow. The real regression to budget for: queue-driven runs always return Result.success and report through queue.json, so routing the picker through the queue moves it off the failure card and Resume button (JobsScreen.kt:86-91) and onto QueueCard, which has no Resume — bad for exactly the long films this app exists to survive. Two risks in the original pitch are wrong in the safe direction and are free: Preflight runs inside doWork, not at Start, so item 4 fails at its own start with err_low_space and the chain continues; and outputs carry an epochMillis suffix, so items cannot collide.

*Cheaper version.* Leave the picker single-file and just make Start enqueue: pass APPEND_OR_REPLACE and a queueId to JobController.start instead of disabling the button. That is ~5 lines against existing parameters, fixes the actual complaint, and defers the List<Uri> ripple through NaqiApp/OptionsScreen/ETA entirely. Ship the cancelItem and firstOrNull fixes regardless — they are live bugs for shares today.

### 9. Know before you start, and clean up from inside

`L` · 2 lenses

**Tell the user a file is DRM'd or unreadable when they pick it rather than after a minute of tuning, and let them delete Naqi's own outputs from the Library instead of leaving for a file manager.**

*Why.* Preflight.check runs inside the worker (FilterWorker.kt:244), so a user picks a DRM'd file, spends a minute tuning, taps Start, and only then learns it was never possible — and the error arrives as a dead-end sentence with no button. Meanwhile every job needs ~2× the source size free and publishes a full-size re-encode next to an original the user keeps, while the Library (JobsScreen.kt:340-360) is a read-only MediaStore query with no delete, no total and no way to act on err_low_space.

*How.* Add Preflight.checkPicked(context, uri, ops, durationMs) inside Preflight — do not call check() from PickOpsScreen, because tempCopiesFor is private and check takes tempCopies: Int, so a UI call site re-spells the shape rule at a third place, which is exactly what that helper's KDoc says it was extracted to prevent. Render the returned @StringRes as an inline band that is ADVISORY, not gating: OptionsScreen.kt:119-122 and long-film-plan.md:54 both record that 'a broken probe must never stand between the user and Start', so Preflight at Start stays the authority and the SAF-latency risk disappears with the gate. For the Library half, note the query is RELATIVE_PATH LIKE Movies/Naqi% OR Music/Naqi% — Naqi's outputs only, so no original is reachable and RecoverableSecurityException is near-impossible outside debug builds, which add READ_MEDIA_VIDEO and widen it. ConfirmDeleteDialog is private at MainActivity.kt:264 and bound to the notification's delete-ORIGINAL target, so reuse means hoisting it plus its own StartIntentSenderForResult launcher and new strings — the existing dlg_delete_original_* say 'original', the wrong noun.

*The catch.* The learned per-device ETA factor is the part to cut, and it is the only part carrying a measurement hazard. stats.finish() is called from a finally in all five shapes (FilterWorker.kt:363,473,783,827,871), so it samples cancelled and failed runs — a job killed at 20% writes a factor 5x too low and poisons the median permanently — and JobStats is constructed per doWork, so a resumed job contributes only its tail. For the advisory band: NO_AUDIO depends on removeMusic and LOW_SPACE on the whole shape, so the check is not stable across the screen and must re-open a MediaExtractor when a toggle flips; and the worker adds extraScratchBytes for the >=30 min resumable-audio PCM, so a pick-time space check is a floor, not a promise, and must be phrased as a warning.

*Cheaper version.* The advisory band restricted to the four ops-independent causes — open, PSSH, track presence, no space maths and no duration probe (XS) — plus the Library total and long-press delete (S). That kills the actual dead end ('I tuned for a minute and it was DRM'd') with zero new failure vocabulary and no judgement calls. Drop the learned ETA entirely; KEY_ETA_MS's live extrapolation already supersedes the constant a few percent into the job.

### 10. Make the controls speak, and stop the blur slider lying

`M` · 1 lens

**Sliders that tell TalkBack what they mean, a progress card that announces itself, and copy that admits blur 0 is not actually 0.**

*Why.* For a blind user Options is close to unusable: grep for stateDescription, liveRegion and progressSemantics across ui/ returns zero hits, so SliderRow (OptionsScreen.kt:372-399) reads '40 percent' of nothing, JobProgressCard is not a live region and ic_naqi_mark passes contentDescription = null. The blur slider also lies at its own left edge — FilterWorker.kt:111-113 rewrites blurAmount to MIN_EFFECTIVE_BLUR = 25 with no notice. And 'flagged scene' appears three times in strings.xml and is defined nowhere, which is the phrase the product leans on to explain what the NSFW gate does.

*How.* SectionHeader already takes a trailing slot (Components.kt:127-142) and both Options call sites at :206 and :255 pass nothing, so the '?' affordance costs no layout work; ModalBottomSheet is a proven in-repo pattern hosted from MainActivity outside NaqiApp's step machine, so it cannot break Back. The a11y half is mechanical: Modifier.semantics { stateDescription = ... } on both SliderRow instances, liveRegion plus progressSemantics on JobProgressCard, and a contentDescription param on NaqiTopBar's titleIcon. Two corrections to the copy plan: '163 strings and not one defines' overstates — opt_strictness_desc already says face blurring is never affected and opt_whole_frame_desc already says most of the video usually ends up covered, so the genuine gaps are exactly three. And the proposed blur tier string would itself be wrong: FilterWorker.kt:111 coerces only the exact triple (blur == 0, grayscale off, style == Blur), so '0-24 → minimum blur applied' lies for 1-24 and lies at 0 with grayscale on.

*The catch.* Every sentence is a support promise and a translation unit, and this pays per locale — 163 strings x 2 today — so the explainer bodies should land after whatever else adds copy, or the same words get paid for five times. Naming a tier implies a calibration the repo does not have (strictness-100/0 criteria are blocked on QA assets), so wording must describe direction ('covers more scenes') and must never quote the 92%-on-n=3 or 95.5%-recall figures. A UI-side tier label for the blur floor is a second source of truth for a rule the worker deliberately owns — FilterWorker.kt:100-110 says the guard lives there 'because a queued job carries its own input data and must not be able to bypass the guard' — and will drift. The sheets must stay read-only prose: the moment a control lands in one, it is the 'Advanced' expander that plan-censor-who.md:144 bans.

*Cheaper version.* Drop the four sheets and Tiers.kt. Ship the a11y half plus two copy edits: append the floor to opt_blur_amount_desc ('Below about 25, Naqi still applies a light blur') and define 'flagged scene' once inside opt_strictness_desc. That is ~0-2 new translation units instead of 15-25 per locale, removes both actual lies, and is the half nobody else will ever do.

### 11. Keep the scores, not the verdicts

`L` · 3 lenses

**Keep what the NSFW model actually said instead of the yes/no it collapses to, so the app can finally answer 'what would strictness 60 have covered on this video?'**

*Why.* FilterWorker.kt:641-643 runs the model, gets probs: FloatArray(5), and destroys the confidence on the same line. NsfwGate.fires and intervals are pure JVM functions of (probs, strictness), so every strictness the user did not pick is one array scan away — and strictness is the control with the least intuition behind it, because nothing on screen says what 40 means. Today re-deciding it costs a full analyze pass.

*How.* The simplification the sketch missed: fires() is MONOTONE in strictness — t0→t100 is decreasing for porn/sexy/hentai and increasing for neutral with drawings constant (NsfwGate.kt:19-25,44-50) — so the five floats collapse to ONE byte, the minimum strictness at which that frame fires (255 = never), EXACT at every integer strictness. That kills the u8 quantization worry and the 'a re-derived EDL is not bit-identical' caveat outright. You must still store pts: the sample grid resyncs after decode gaps (FrameSampler.kt:273-274) and gate frames are every 2nd emitted frame, so a cadence constant cannot recover timestamps — (u16 delta-ms, u8 minStrictness) is 3 B/gate-frame, ~140 KB per film. Persist it beside the OUTPUT, never in workDir: JobStore.delete wipes the work directory on every success path (:457,:786,:874), so 'forever, if the probs are kept' is false as written and the store would vanish at exactly the moment re-tuning becomes interesting.

*The catch.* Do NOT take strictness out of jobKey: seg-NNN.mp4 is rendered from the strictness-dependent EDL, so a shared directory would let a resume adopt segments rendered at another strictness — the exact failure jobKey exists to prevent. Restyle-without-reanalysis needs a second render-scoped key, and that is what takes this from M to L. Checkpoint.atomicWrite is private and String-only, and readAnalysis wraps the whole parse in runCatching, so an unconditional read of a new field makes an OLD checkpoint re-analyze that segment rather than read null. Standalone this is a diagnostic with nowhere to show itself — its payoff needs a receipt to live in, so it follows rank 3 rather than leading it. The near-miss 'cover when unsure' band is a guess the rig cannot settle: analyze moves ±32% between runs of one APK, so it needs a recall measurement, not a timing run.

*Cheaper version.* Keep the probs in RAM for the pass — 46,500 gate frames x 5 floats is ~0.9 MB against a 1.5 GB ceiling — and at end-of-pass emit a 101-row strictness→covered-ms table into the receipt. That answers 'nothing on screen says what 40 means' with no new file format, no checkpoint schema change and no jobKey surgery.

---

## Worth it

*Real value, smaller audience or a narrower failure they fix.*

### 12. Close the coverage holes in the EDL builder

`S` · 2 lenses

**Fix the case where a face box slides smoothly between two positions and covers neither, then let the user choose head-and-hair or a body column instead of one fixed 25% pad.**

*Why.* A track survives up to EVICT_AFTER_MS = 2 s of not being seen (FaceTracker.kt:264) and Edl.rectAt interpolates between adjacent keyframes unconditionally (Edl.kt:151-175), so across a sample gap the box travels between two positions and covers neither — under-censoring, which is the failure this product cannot afford. Separately the 25% pad is a decision made on the user's behalf: someone whose rule is 'hair and body' today must choose between too little and whole-frame, which blanks ~90.8% of the runtime.

*How.* Both fixes land in FaceTracker.edlFor and padRect (:281,:313) — internal, pure JVM, no Android or ML Kit, already covered by 13 tests in FaceTrackerLogicTest — and pass 2 needs zero change because the output is still an NRect per keyframe, which is exactly how whole-frame blur shipped. Get the missing evidence free first, in one soak run: FaceTracker.retention() is already logged per pass and per segment, so add max and p95 inter-sample gap to that string and run women-music-3min. Then emit two keyframes holding the union of neighbouring rects when consecutive samples are more than ~300 ms apart. Note the two halves overlap — a union keyframe already absorbs the displacement that pad-by-displacement would then pad for again — so ship the union, measure coverage, and only then decide on the scaled pad.

*The catch.* The premise is a challenge to a written position, not a gap the docs missed: perf-plan.md:233-236 argues rectAt 'degrades smoothly rather than falling off a cliff' — but it argues that at 200 ms spacing, not at the up-to-2 s gap EVICT_AFTER_MS permits, so say that rather than citing the 13.56 s fail-open, which perf-plan.md:216-218 explicitly attributes to the gender vote and CROP_SPREAD_MS instead. ML Kit's tracker is motion-based with no re-identification, so an id surviving 20 sampled frames of absence is unlikely and realistic gaps are 200-500 ms — still worth fixing, but a much smaller claim than '2 s'. No QA asset isolates the gap case; the profile-face set is a blocked non-code item. Both fixes only ever cover more, but they change what an an-NNN.json holds, so a job resumed across the change mixes geometries, and the repo bumped its plan generation for less.

*Cheaper version.* The two hole fixes alone, with the plan-generation bump — pure padRect/edlFor in JVM beside FaceTrackerLogicTest, no wire field, no jobKey input, no UI, no strings. Do the retention()-logging measurement before either. The coverShape control is a separate M that owes a coverage measurement on women-music-3min first, because a 2.5x-width column on close-up footage collapses into whole-frame and also raises MAX_REGIONS=8 overflow, which promotes to whole-frame anyway — i.e. it can silently become the mode the user was avoiding.

### 13. Audio-only output from a video

`S` · 1 lens

**Choose 'Audio only' and a video comes out as an .m4a in Music/Naqi with the music removed and the picture discarded.**

*Why.* The most common real use of music removal is a lecture, khutbah or nasheed delivered as video that the user wants as audio for the car. The shape already exists and is fully built — runAudioOnly — but it is reachable only by DETECTING that the source has no video track, never by choosing it. The one chosen affordance today is ShareSheet.kt:127's isLink && quality == AUDIO, which is precisely the half a Play build cannot carry, so on the target distribution there is no route from a local video to an .m4a at all.

*How.* audioOnly = removeMusic && (!hasVideoTrack(inputUri) || ops.audioOnlyOutput) — keep detection as the authority and OR the request on top, so FilterWorker.kt:211-214's recorded reason (a flag that CLAIMS the source has no video is 'one more thing to keep in sync with reality') still holds. Everything downstream ships already: AudioPipeline.removeMusic, Publish.audio → Music/Naqi, outputName(ext="m4a"), the Library's .m4a branch and Preflight's allowNoVideo. Do NOT add the field to jobKey: it changes only which container is published and every checkpointed artifact is byte-identical either way, so sharing the key means a music-only run that died at 80% resumes into an audio-only re-run and vice versa — but the jobKey KDoc says 'every option that changes the OUTPUT', so the omission needs a sentence of justification or someone will 'fix' it later. Must-fix while in there: runAudioOnly passes no jobDir and its catch deletes the work dir unconditionally, and its stated reason (FrameSampler.probe throws on an .m4a so durationMs was always 0) does not hold for a video source where the probe succeeds — copy runMusicOnly's resumable pattern, about ten lines.

*The catch.* The pitch's headline is wrong and must not become copy: a music-removal job already stream-copies the video (Remux.kt:91-95, 'copied sample-for-sample, ZERO re-encode'), so choosing audio-only saves the remux pass and ~1x source size of disk — roughly 1% of the wall on a feature-length lecture, not half. Sell the artifact a car player will actually see and the lower free-space floor, not speed. It also makes censorWho meaningless in combination, and this app has a documented habit of silently coercing (MIN_EFFECTIVE_BLUR) — ShareSheet.kt:256 sets the right precedent by disabling the row visibly rather than hiding it. Gate the control when Remove music is off, or it is settable-and-ignored, and hide it entirely for an audio source.

*Cheaper version.* Skip Prefs for v1 — ShareSheet reads Prefs.ops() and simply never sets the field — and ship the control on Options only. The ~10-line jobDir thread that makes it resumable is worth keeping even in the cheap version: a chosen non-resumable multi-hour shape is exactly the failure Phase 2 exists to remove.

### 14. Silence the audio under covered scenes

`M` · 1 lens

**Mute exactly the spans where an NSFW scene is covered, so a censored scene is censored in both channels.**

*Why.* Whole-frame blur covers the frame and the scene's audio keeps playing at full volume — for this product's user that is the filter visibly failing at the thing it claims to do. The spans are already computed, merged and persisted: the gate's hysteresis output floored at 2 s is precisely the 'a scene is happening' signal, and it needs no new model and no new analysis.

*How.* MANDATORY correction, found independently by both vet passes: do NOT key off edl.fullFrameAt. promoteFacesToFullFrame (Edl.kt:146-148) merges face spans INTO censorIntervalsMs, and under whole-frame that is ~90.8% of runtime — the sketch's own call would mute nearly the whole soundtrack, the exact outcome its risk paragraph forbids. Key off intervalsFor(firings, durationMs) instead; firings are in scope in both analyze (:1134) and analyzeSegments (:663), and an-NNN.json already persists firingsMs, so no EDL schema change is needed. Do not write audio/MuteSpans.kt either: on combined and segmented-with-music the separator's emit already hands you raw PCM with an exact running frame count (AudioPipeline.kt:347), so zeroing a span is ~10 lines at zero extra cost; and on the unsegmented censor route, use the empty FIRST argument of Effects(emptyList(), listOf(effect)) at RenderPipeline.kt:130 — one AudioProcessor located by flush(StreamMetadata).positionOffsetUs, with media3 supplying both decode and encode.

*The catch.* The accuracy risk is asymmetric and worse than blur's: a false positive that blurs 2 s is a glitch, one that mutes 2 s of dialogue is a hole in the film — and the gate is deliberately tuned toward firing, with prd:110 stating 'High strictness intentionally over-censors — that is the safe direction', which INVERTS under muting. The gate's false-positive rate has never been measured, because the beach/gym/lingerie and cartoon QA sets are a named blocked item. It also ends the shipped bit-identical audio passthrough on censor-only jobs — a green PRD acceptance line at prd:75 becomes opt-out — which must be stated on the switch. Two of the five shapes (music-only, audio-only) have no EDL at all, and setRemoveAudio(segment != null || removeAudio) strips audio per segment, so the AudioProcessor route does not reach the segmented long-film path.

*Cheaper version.* Ship the AudioProcessor on the unsegmented censor route only, keyed off the gate's pre-promotion intervals. media3 supplies decode and encode, so there is no new pass, no mux rewire and no MuteSpans.kt — it is one class in the argument the architecture already leaves empty. Build the standalone pass only if films actually need it.

### 15. Ship a 117 MB app instead of a 221 MB one

`M` · 1 lens

**The two biggest models are only ever loaded by music removal, and a complete, sha256-verifying downloader for them already sits in the tree with zero callers.**

*Why.* Verified: grep for ModelDownloader outside its own file returns only KDoc references, modelsDir and installed — the object is dead code, and it is not a stub (.part plus Range resume, whole-file sha256 before rename, usableSpace pre-check with a 64 MB margin, a six-value DownloadError taxonomy). NAQI_MODEL_BASE_URL defaults to "" in app/build.gradle.kts:25, so sourceUrl returns null and every model reports NO_SOURCE. The ledger has listed 'activate ModelDownloader (−105 MB; needs a host)' as an open upgrade and nobody turned it into a feature, while installed footprint sits at ~391 MB.

*How.* The split is clean along licence lines, which is what makes it shippable rather than merely desirable: the two models to move out are htdemucs (87.9 MB, MIT) and yamnet (16.1 MB, Apache-2.0), both freely redistributable, and the two that stay bundled are the NOASSERTION NSFW weights and the personally-waived genderage — exactly the two you would not put on a public host. scripts/fetch-models.sh already stages both, so this is a packaging decision, not a model change. Gate the fetch where the need is KNOWN, not where the load happens: MusicGate.kt:181 and DemucsSeparator.kt:651 call ModelSmoke.modelFile deep inside a running job, so the check belongs in Preflight/Options before the job promotes to a foreground service — a 104 MB download must never start 40 minutes into a film. It composes directly with the attestation candidate, which wants the About row to say bundled-vs-downloaded anyway.

*The catch.* It needs an operational commitment nobody has made: a host that stays up, and a rule that re-exporting an artifact means a new sha256 in NaqiModel AND a new upload, or every install breaks at once. It also puts a network step in front of a feature currently sold as fully on-device, so the copy must be upfront — 'music removal needs a one-time 104 MB download; everything after it is on-device' — and the fetch must be resumable and cancellable, which it already is. Play policy is fine here: model weights are data, not executable code, which is precisely what killed the yt-dlp path.

*Cheaper version.* Delete nsfw_mnv2_140_f32.onnx from assets/models/. Verified: 17 MB in the APK, referenced only by a KDoc line and scripts/nsfw_int8_quantize.py, and NOT one of the four NaqiModel enum entries — nothing loads it. That is 17 MB off the download with zero code change and zero operational commitment, and the quantize script can re-fetch it whenever someone next wants the INT8 A/B. Do that today and treat activating the downloader as a separate decision that needs a host owner.

### 16. Four presets

`L` · 2 lenses

**A chip row that sets every control at once for the four shapes people actually have — lecture, family film, strict, picture-only — so a first-time user has a map.**

*Why.* A first-time user meets seven controls with no map, and the choice they cannot see is a 4x time decision: Eta's factors are CENSOR 0.28, MUSIC 0.68 and COMBINED 1.0 of source duration, which checks out. Nothing in the pipeline changes — a preset resolves to a plain FilterOps before JobController.start, and the ETA line recomputes itself on the next recomposition.

*How.* model/Presets.kt as Preset(@StringRes label, @StringRes desc, ops: FilterOps), rendered as a chip row calling onOpsChange(preset.ops). Placement is the thing to get right and the original sketch has it wrong: the censor card renders only if (ops.censorFaces) (OptionsScreen.kt:205) and the music card only if (ops.removeMusic) (:254), so a chip row above the censor card vanishes for a music-only or audio-only job, and 'Lecture' (censorWho=NONE) would delete the card containing the chip that set it. The two op booleans are also step-1 decisions, so a preset applied on step 2 silently overrides what the user just set and Back-to-Pick then shows different toggles — which puts the chip row on PickOpsScreen, where those booleans already live. PickOpsScreen.kt:104 force-sets removeMusic and censorWho=NONE for audio sources and hides the faces row, so the row must collapse to one preset or vanish when isAudio.

*The catch.* Hard dependency on persisted settings: without them a preset is forgotten on cold start, so this ships after rank 2 or silently re-implements it. The strict preset flips whole-frame — an EDL-time promotion into an irreversible re-encode — so it must never be a hidden mode; Options has to stay visible underneath with every control showing its state. Worth knowing before someone leans on it: the proposal's strongest defensive citation does not support it. plan-v2:139's cut 'Preserve mode' is the v1 plan's video-output-preservation mode (source codec/HDR/PTS), cut as a 4%-of-wall idea, not a precedent about hidden UI modes. The real adjacent rejections are plan-censor-who:144 (no 'Advanced' expander) and §2.3 (no Who control in the sheet); a chip row clears both, a saved-rule selector in the share sheet does not.

*Cheaper version.* Four hardcoded presets as a pure model/Presets.kt chip row on the Pick screen — S, eight strings x two locales, no file format, no active-rule concept, no share-sheet change. Drop user-saved rules until someone asks: Prefs' own KDoc says 'Six scalars do not need a schema. Revisit if this ever has to hold a list', and a rules list triggers a new JSON file, a back-compat read, an active-rule id, name entry, edit/delete UI, RTL and a resolution order in ShareSheet. That is the entire M-to-L, for a demand nothing in docs/ or the ledger evidences.

### 17. Something got through

`L` · 1 lens

**When the user sees a miss mid-watch, they mark the timestamp and Naqi covers those exact moments unconditionally, regardless of what the models think.**

*Why.* This is the question nothing in the app answers. Today the only recourse is to raise a number the user cannot calibrate and re-run hoping. A forced interval is the one guarantee the pipeline can actually make, because it bypasses every model: the span is covered because the user said so. Good news the sketch got right — forced spans become whole-frame intervals through censorSpans, so Edl.fullFrameAt covers them with zero renderer change and MIN_FULL_MS cannot drop a ±2 s pad.

*How.* KEY_FORCE_INTERVALS and parseForceIntervals exist (FilterWorker.kt:1344,:1406, format startMs-endMs,...) and already merge into the gate's interval list — but intervalsFor early-returns at :1212 on `if (!BuildConfig.DEBUG_HOOKS) return intervals`, so promoting the key without touching that guard ships a control that does nothing, and removing the guard makes parseForceIntervals reachable from a release build, so audit it for hostile input first. The redesign that makes this usable inverts the sketch's safety claim: keep forced intervals OUT of jobKey. Hashing them mints a new directory, so a combined job re-runs `separate` too — ~41 s of ORT per video-minute, ~106 min on a 155-min film — to fix a video-only miss. Instead keep the work dir past success (JobStore.sweep collects it after 7 days), write the forced set into a file in the dir, and at job start delete only the seg-NNN.mp4 whose window overlaps a forced span: renderSegments already skips rendered segments (:741) and readAnalysis already skips analyze (:670), so 'cover this moment' becomes one segment re-render of ~26 s plus the concat write.

*The catch.* With the naive design the fix is a full re-run — hours on a film against a 6 h/24 h cumulative FGS budget that a 3.1 h film already half-consumes, so 'I saw a miss in the film' is exactly the case that may not have budget to re-run that day. The redesign removes that at the cost of retaining ~1x source of scratch for up to 7 days, which Preflight already sizes for. Standalone this is not M, because without a proof grid or a scrubber there is no way to pick tMs and it degrades to typing a timestamp. plan-censor-who.md:321 rejected per-face manual override for needing a preview scrubber; this is the opposite direction and needs no per-track UI, so the rejection does not bind — but half its rationale does. And the feature reads as an admission that the filter fails, which it does; the framing has to own that rather than bury it.

*Cheaper version.* A plain mm:ss field on the finished-job card that enqueues one re-run with tMs±2000. Same guarantee, no misses.json, no dependency on a proof grid, and no QA-data-that-must-never-leave question. Reuse the existing >30 min confirm (Eta.CONFIRM_THRESHOLD_MS) so the FGS budget problem is surfaced in the UI, not only in the plan — offering a re-run the budget will kill at hour 3 is worse than offering nothing.

### 18. Show the permissions Naqi does not have

`M` · 1 lens

**About lists the permissions the app holds and, explicitly, the ones it does not — plus each model's licence and whether its sha256 still matches what the build declares.**

*Why.* The whole product claim is 'entirely on-device', and About proves only that models load. The sentence 'no camera, no microphone, no storage permission' does not actually exist anywhere in the UI — the trust pill reads 'On-device · Private' and the negative-space claim lives only as a source comment in AndroidManifest.xml:18-19. Rendered from PackageManager it becomes something a user can check rather than believe. It is also the only detector for a live latent bug: ModelDownloader.installed() is a bare length() > 0, nothing ever clears filesDir/models, and that directory is backup-eligible, so a stale or restored same-named model silently wins over the bundled asset forever.

*How.* Permission panel from getPackageInfo(packageName, GET_PERMISSIONS) rendered as declared-vs-granted with a one-line purpose from a local map, and make the not-declared list DERIVED by asserting absence from requestedPermissions rather than printing a static array — otherwise it is a claim living in the same binary, which is the exact weakness the hash has. Needs a Build.VERSION branch for PackageInfoFlags at compileSdk 36. Drop the source column: ModelDownloader.download has zero call sites, every downloadUrl is null, and the asset copy and a download land at the same path, so provenance is unrecoverable today. No Prefs cache is needed either — ModelSmoke already caches process-wide in @Volatile cached and About is its only caller, so hash inside smokeOne while the file is already open; sha256 is private and needs one visibility bump.

*The catch.* The hash proves less than the pitch says: NaqiModel.sha256 ships in the same APK as the asset, so a repacked build carries the attacker's constants, and accidental corruption is already caught because smokeOne creates a real ORT session and runs an inference. The residual case is 'a valid but different ONNX at the same path', which needs root or a run-as debug build — say that plainly or the row is theatre. Ship warn-only, because refusing to run would brick a partially-restored install; pair the warning with a repair action that deletes the file and re-runs extracted(), which turns the check into the fix. The panel also puts REQUEST_INSTALL_PACKAGES second on the screen and forces someone to write its purpose line honestly, which is the Play contradiction still shipping on main — treat that as a feature. And the licence column will carry two entries a careful user asks about: NOASSERTION NSFW weights, and an owner-waived non-commercial genderage.

*Cheaper version.* The permission panel alone — getPackageInfo(GET_PERMISSIONS), a purpose map, a derived not-declared list, ~15 strings x two locales. XS, fully independent of the hashing half, and it is the part the pitch is actually about. Add a one-line licence: String field on the NaqiModel enum (four one-liners) beside the existing tick/dash/cross rows if you want licence honesty without the hash.

### 19. Say when almost nothing was covered

`M` · 1 lens

**Notice when the models found essentially nothing on this video and say so, instead of reporting success on a file that was barely filtered.**

*Why.* An empty EDL makes the render a container copy — RenderPipeline.kt:112 sets passthrough and Effects.EMPTY — that is published and reported 'Saved' with nothing telling the user nothing was censored. On an animated source ML Kit finds almost nothing, so the app reports a clean pass on a video that was barely filtered, which is the worst failure this product has because it is silent and looks like success.

*How.* Both vet passes independently killed one of the two proposed signals and converged on the same replacement. untrackedCount is INVERTED as an under-coverage signal: an untracked detection gets a synthetic id (FaceTracker.kt:125), casts no vote, and no vote CENSORS (:302), so a high untracked share on fast-cut content means MORE covering — the warning would fire on exactly the footage that is already over-covered. The drawings signal is real (index 0 of the FloatArray(5) discarded at :643) but is currently wired as a safety veto in NsfwGate.kt:31, so repurposing it is a second unvalidated use of the same number. Hidden work either way: segmented jobs need aggregates accumulated into worker fields AND written into an-NNN.json, because a fresh FaceTracker per segment (:686) resets the counters — a checkpoint schema change the sketch does not price.

*The catch.* The animation threshold has no corpus, and the corpus is a blocked item rather than a to-do — tasks.md:72 says tuning against anything else 'would fit the constants to the wrong data' — while the false-alarm cost is that users learn to dismiss the one warning that matters. The advisory also arrives AFTER an irreversible re-encode, and its only offered action (turn on whole-frame) is hashed into jobKey, so accepting it re-runs pass 1 from scratch. And there is no receipt to put it on today: the only worker-to-UI channel is Result.success(workDataOf(...)) plus queue.json, so a shared item would silently never see it.

*Cheaper version.* Drop the diagnosis and report the fact: when the pass produced an empty or near-empty EDL, say '0 spans · 0% of runtime covered' on SavedCard and attach the whole-frame offer there. That catches the animated source, the models-found-nothing source and the wrong-strictness source at once, with no threshold, no corpus and no new counter. Note this is already inside rank 3's summary — so the only thing left standing alone here is the animation classifier, which is M and blocked on an asset. Separately and for free, start logging mean(drawings) and mean(hentai) per job: two accumulators in sampledFrame, and it is the corpus any future threshold needs.

---

## Speculative

*Genuinely good ideas that are expensive, unproven, or need a measurement first.*

### 20. Choose how big the output is

`M` · 1 lens

**Let the user pick the output resolution, so a 4K job becomes a 1080p file at a quarter of the bytes on the one stage of the pipeline that is otherwise immovable.**

*Why.* Nothing in the surviving set touches the size of the thing the app produces: a filtered film comes out roughly the size of the source, needs 2x free space to make, and cannot be sent to anyone. It is also the only untried lever on the render stage — plan-v2 §6b settles that render sits at the hardware encode ceiling, and that ceiling is denominated in pixels, so 1080p to 720p is 2.25x fewer pixels through the stage documented as 50.8% of a censor-only job. Every rejected render optimisation tried to make the same pixels cheaper; none tried sending fewer. Analyze is untouched, since it already downsamples to 640 internally.

*How.* Append Presentation.createForShortSide(720) after CensorGlEffect in the list at RenderPipeline.kt:132 — media3-effect is already a dependency and censor regions are in normalized coordinates, so order is safe. Then bin bitrateCap on OUTPUT pixels rather than source (RenderPipeline.kt:264-283), or a 720p file keeps a 16 Mbps cap and the saving evaporates. Resolve it once per job alongside the bitrate and for the identical reason: Remux.concat's requireSameFormat compares width/height/csd-0/csd-1 across segments, so a per-segment answer produces an undecodable film. A new FilterOps field means the full wire seam — pairs()/filterOps(), Queue.toJson/opsFromJson, Prefs, and jobKey; omit the last and a resume adopts segments at a different resolution, which requireSameFormat then throws on after hours. Surface it as an outcome, not a resolution: 'about 1.1 GB instead of 3.8 GB'.

*The catch.* Second-generation encoding at lower resolution is a visible quality loss on purpose, and if the copy is not honest this becomes the setting users regret — it must default off, because the PRD promises source resolution preserved. Ledger adjacency worth reading first: plan-v2:139-145 cut both 'Preserve/Compatible mode split' and 'calibrated bitrate policy', though from a performance document as 4%-of-wall ideas, so the cut does not cover a storage-and-shareability feature. Measure the win on the render stage timer and never on the wall: the wall carries ±32% thermal noise, the render stage carries ±0.31%.

*Cheaper version.* Two fixed choices — 'Same as source' and '720p' — rather than a resolution list, and no Prefs entry so a shared item can never silently downscale. That is the same jobKey and bitrate-binning work but removes the per-source resolution matrix and the copy problem of explaining 1440p.

### 21. Filter only the part you'll watch

`L` · 2 lenses

**Set an in and out point before Start, so a 22-minute episode's 90-second intro and 2 minutes of credits are never processed at all.**

*Why.* Every stage of this pipeline is linear in source length, so skipped time is the one speedup nobody has to measure — roughly 16% off every episode of a weekly series, compounding across a queue already fighting the 6 h daily budget. It also covers the real case of a three-hour recording where the lecture is the first fifty minutes and the rest is a concert nobody wants filtered or kept. Today the user must trim in another app first, which means a second re-encode by a tool that does not censor.

*How.* Do NOT thread a window through the pipeline; do it as a lossless pre-step. Add one Remux.clip(source, inMs, outMs, out) beside Remux.mux — the same two-track interleaved sample copy it already does (Remux.kt:143-156), seeking to the sync sample at or below inMs, rebasing PTS to 0 and stopping past outMs — writing workDir/clip.mp4, then run the ENTIRE existing pipeline unchanged on that file. Every shape, the segment plan, the checkpoints, Eta, the >30 min confirm, the audio path and the resumable separator then see a normal short source starting at 0, so none of them changes. That matters because the sketch's claim that 'the pipeline already takes the window on both sides' holds for the censor shape only: music-only never touches RenderPipeline (it passthroughs through Remux.mux, which has no sample-range filter) and AudioPipeline.removeMusic has no window parameter anywhere.

*The catch.* The UI is priced at zero and is the real cost: there is no preview, thumbnail, scrubber or player anywhere in the app, so a usable in/out picker is a timeline plus per-position frame preview via MediaMetadataRetriever, RTL mirroring, a11y and ~8 strings x 2 locales — without it the user must already know the timecodes. A trim also needs a probed duration, and FrameSampler.probe failing gives durationMs=0, which is a documented open hole. Prefs is the one wire place you must NOT touch: a trim is per-video, not a remembered setting, and ShareSheet reads Prefs.ops(), so persisting it would silently trim shared items. The pre-step costs one more tempCopies in Preflight and lands the in-point on the nearest keyframe, which the copy must say.

*Cheaper version.* Ship the window for video-bearing shapes only and grey the handles out when Remove music is on, with the reason stated — that skips the two hardest sub-problems (windowing removeMusic and windowing Remux) and still delivers the 22-minute-episode case. If the pre-step route is taken instead, it sidesteps the fragmented-MP4 landmine entirely, because no clipped export is ever issued.

### 22. Lock the rule

`L` · 2 lenses

**A local code that lets anyone make the filter stricter but requires the code to weaken it.**

*Why.* This app gets installed on a family tablet and on a child's phone, and every control is one tap from the home screen — face censoring is a single ToggleTile on the pick screen with no friction at all. A filter anyone in the household can disable in two taps is not the tool the person who installed it believes they installed. The asymmetry is what makes it honest: strengthening never prompts, so a family member can always choose more cover without asking anyone, and the lock resists only the direction that breaks the arrangement.

*How.* The choke point is real and traced end to end: every entry path (picker, share queue, download, retry, adb) writes ops through pairs() and FilterWorker reads them back through filterOps(), so one clamp in the constructor beside the blurAmount coercion at :111 covers all five — and that coercion's own KDoc already states the rationale, 'a queued job carries its own input data and must not be able to bypass the guard'. Strictness is monotone in 'more censoring' (NsfwGate.kt:19-25), so a floor is coherent. Three sketch errors to fix: ShareSheet is NOT controls-free — ShareSheet.kt:252-258 is a Censor-faces ToggleTile setting censorWho = NONE, and ShareSheet.kt:131 then PERSISTS it, so it is the highest-leverage bypass, not the surface with nothing to gate. requiredWho is not a total order (EVERYONE contains WOMEN and MEN, but WOMEN and MEN are incomparable), so an ordinal >= lets the lateral WOMEN→MEN move through. And two places where the APP itself weakens ops must be exempt or picking an mp3 raises a PIN sheet: PickOpsScreen.kt:104 and ShareSheet.kt:128.

*The catch.* No recovery path: forgetting the code means clear-data or reinstall, and that must be stated up front rather than discovered. Two concrete hazards the sketch misses. Arabic keyboards type U+0660-0669, so hashing the raw PIN string means a code set in English cannot be entered in Arabic — an unopenable lock with, by design, no recovery; normalize with Character.digit before hashing. And a salted SHA-256 of a 4-digit PIN is 10^4 candidates, so use PBKDF2WithHmacSHA256 or say in the copy that it is a speed bump. It is a soft lock either way — clearing app data removes it, and allowBackup="true" with the stock template rules means a restore can put it back — so the UI must say both; overclaiming here is worse than not shipping, and reaching for device admin or usage-stats would be a different product and a Play problem.

*Cheaper version.* Lock only the on/off switch and Who; drop minStrictness, minBlur and requireWholeFrame entirely. It covers the actual scenario ('a filter anyone can disable in two taps'), and because censorWho is ALREADY in jobKey (FilterWorker.kt:150-151) the whole resume hazard disappears by construction — no keyOf change, no plan bump, no orphaned segments. It also kills the lattice design down to one enum comparison, and note that strictness/blur/wholeFrame are not persisted at all today, so a 'blur floor' would defend a value the app forgets between launches.

### 23. Recitation and nasheed pass through untouched

`S` · 1 lens

**Qur'an recitation, adhan and unaccompanied nasheed are recognised as voice-only audio and passed through instead of being run through the separator.**

*Why.* MusicGate.MUSIC_RANGES is 132..276 plus 24..32, and 24..32 is AudioSet's vocal-music block — Singing, Chant, Mantra, Humming. So a khutbah's opening recitation, an adhan and an a cappella nasheed all clear THRESHOLD = 0.15, get fed to htdemucs, and come back as the vocals stem with the room and reverb stripped out. Be precise about what that costs: the audio is not removed, it is degraded — a quality harm, which is a weaker motivation than 'the app alters what users most want left alone' implies, but a real one for exactly this audience.

*How.* MusicGate.score already fills scores = FloatArray(521) per frame and throws away everything but a max over two ranges, so per-block maxima come off the same array. Do NOT turn musicScore into a verdict lambda: keep the (FloatArray, Int) -> Float contract and have the gate return 0f when the block analysis says voice-only, and then separateChunk, the gate score ring, both dilation tiers, DemucsSeparatorTest and the resumed audio.json semantics are all untouched, with the whole rule as one branch inside MusicGate. The index-verification risk is smaller than stated: scripts/yamnet_export.py:62-105 already has verify_class_map and CLASS_MAP_ANCHORS asserting 24=Singing, 32=Humming, 132=Music, 276=Scary music on every export, so sub-block anchors are a few lines in a script that runs against the SavedModel's own class map.

*The catch.* It reverses a decision the code calls non-optional in as many words — MusicGate.kt:149-151, 'including it is not optional; it is the only thing that catches a-cappella singing, which the product must remove' — and it inverts the one invariant every other piece of the audio design holds (THRESHOLD's KDoc: 'the cost of down is wall clock, the cost of up is the product'). It is the first rule that fails toward NOT separating, so it can leave real music in, the one failure a user cannot fix after the fact. 'Zero extra inference' is also false: score() early-exits on the first frame clearing THRESHOLD, and that exit is documented as load-bearing, so a margin verdict must keep scanning and goes from 1 inference to up to 3 on exactly those chunks. And it owes a listening test against a recitation/nasheed/duff set that qa-assets does not contain, on a repo that already carries an unpaid listening-test debt for the smaller DILATE2_MIN_SCORE move.

*Cheaper version.* Ship the MEASUREMENT, not the rule. Behind DEBUG_HOOKS, log per chunk the four maxima — melodic sub-block, percussion sub-block, vocal-music 24..32, and Speech — off the array MusicGate already fills. Zero product change, zero wire change, no jobKey bump, ~30 lines, XS. Then run it on recitation/adhan/nasheed/duff clips: either the blocks separate cleanly and the rule becomes a 20-line change with numbers behind it, or a produced nasheed lights both blocks and the feature was never viable — learned for the price of a log line instead of a wire format, a plan bump and a fiqh position.

### 24. Make the Play build physically incapable of the downloader

`XL` · 2 lenses

**Split the build so a Play variant cannot contain the downloader, the self-updater or REQUEST_INSTALL_PACKAGES — and says so on the About screen.**

*Why.* README already documents a distribution split the build system does not implement. On main the manifest declares REQUEST_INSTALL_PACKAGES with a comment asserting GitHub distribution, UpdateCard renders on the pick screen, and About offers a yt-dlp self-update. A Play upload of this is a Device & Network Abuse violation plus a screenful of controls that cannot work, and a user who installs from Play and shares a link gets a share target that fails. This is a shipping blocker for every other item on this list, which is why it is here at all.

*How.* Read origin/cut-download:docs/play-request-install-packages.md before writing a line — it is not visible from main, which is presumably why flavors got proposed. That doc is explicit: 'Status: done (Option A)', where Option A is 'delete the self-updater — this is what was done', and Option B is verbatim this proposal's flavorDimensions/tools:node="remove"/BuildConfig.FLAVOR snippets, filed under 'Only if GitHub Releases stays a real distribution channel alongside Play. Costs two variants forever.' The real question is therefore merge-or-flavor, not add-flavors. The one-variant route also keeps the green gate honest: compileDebugKotlin + testDebugUnitTest covers 100% of what ships, versus 1 of 6 variants.

*The catch.* The flavor route understates itself badly. githubImplementation is not a BuildConfig boolean: Downloader.Quality and Downloader.isQuarantined leak into Prefs, JobController, ShareSheet, MainActivity and FilterWorker, so with the dep off the play classpath all of that fails to compile — 8-12 files of hoisting. intent-filter has no key attribute and cannot be targeted by tools:node="remove", so the text/plain filter must physically move to src/github. CopyNoticeTask is registered once outside onVariants, so per-flavor attribution means registering per variant. And the size arithmetic is wrong: dropping youtubedl removes ~48-51 MB of natives outright, while useLegacyPackaging=false reclaims the remaining ~35 MB of INSTALLED footprint, not APK size. None of it clears the licence question either — NOTICE:16's NOASSERTION NSFW weights still block a clean store release, so this must not be described as a licensing fix.

*Cheaper version.* Redo cut-download on top of main as a single variant: no flavor dimension, GitHub's full build preserved as a tag rather than a build type. The branch is stale (merge-base is Release 1.2, and diffing it against main shows 74 files / +1136 / −3460 including deletions of perf-plan-v5.md, research-perf-2026-08.md and PackNv21GoldenTest.kt that main has since added), so redo, do not merge. Fold in the two stale-doc fixes in the same pass (README's NudeNet claim, store-listing.md's 'Android 8.0' against minSdk 29) and keep INTERNET — dropping it forecloses the named ModelDownloader upgrade for a permission-list talking point.

### 25. Translation harness, then Indonesian and Urdu

`L` · 1 lens

**A unit test that makes en/ar string parity mechanical, three new locales, and an in-app language picker for the API 29-32 devices that currently have none.**

*Why.* The two largest Muslim populations read neither shipped language, and the users most likely to need this app are on the cheapest hardware — where the Language menu item is hidden entirely, because the system per-app picker is API 33+ and PickOpsScreen.kt:199 gates on TIRAMISU, so a minSdk 29 device on an Arabic ROM cannot get English or vice versa. The 163/163 parity is maintained by hand and nothing enforces it, which is exactly when the string-adding candidates above will break it.

*How.* The parity test is ~40 lines of plain JVM with no new dependency: walk File("src/main/res").listFiles { it.name.startsWith("values") }, parse each strings.xml with javax.xml, and fail on a name present in one locale and absent in another and on a format-arg-count mismatch. Gradle's Test.workingDir defaults to the module dir so the relative path resolves. One spec detail that matters: the arg regex must match %1$.1f (jobs_size_gb, strings.xml:182), not just %s and %d, or the two size strings silently pass. Verified today: both locales hold exactly 163 strings with identical name sets, no plurals, no string-arrays — so the harness is a guard locked in while it is cheap, not a bug-finder.

*The catch.* The pre-33 picker is the part that breaks, and not in the way the sketch expects: attachBaseContext gives the Activity a locale, but every notification string resolves off the worker's applicationContext (JobNotifications.kt:41-176 and FilterWorker.kt:1309), so on a 3-hour job the ongoing notification sits in the system language while the app sits in the chosen one — the same 'two things disagree' bug class the candidate warns about, arriving from the other direction. Appcompat would not save it either; its delegate covers activities, not workers, and there is no Application subclass in the manifest to hang an app-wide override on. It is also a written deferral with a trigger that has not fired (PickOpsScreen.kt:188-189, 'add when pre-33 users complain'). And the locale choice is off-doc: store-listing.md:302 names Urdu and Hindi as the future translations and files Indonesia and Malaysia under the English-speaking bucket, so if you cut one, cut Turkish. The vocabulary is religiously and politically loaded, so a careless Urdu string is worse than English.

*Cheaper version.* Ship StringsParityTest.kt alone (XS) and add it to the green gate, so the string-adding candidates above cannot break parity. Defer the locales until those candidates have landed their copy, so translation is paid once instead of five times. For pre-33, the honest three-line version is to keep the Language item visible but point it at Settings.ACTION_LOCALE_SETTINGS rather than shipping an override that can disagree with the 33+ picker and misses every notification.

### 26. Still photos as input

`L` · 1 lens

**Pick a JPEG or HEIC and get a censored copy in Pictures/Naqi in about a second — same face rule, same gate, same models, zero new bytes in the APK.**

*Why.* Every user who filters video for their household also receives photos, and a photo is the one case where the 10.2 s-per-video-minute cost collapses to a single frame and dodges the FGS budget entirely. It also unlocks the app for people with no video to filter — a forwarded image, a screenshot, a downloaded wallpaper — and it is the only way to extend the media type without touching the 221 MB size budget.

*How.* Publish.into really is generic over collection/dir/mime, so Publish.image is the ~8 lines claimed, and the Library query is one extra MEDIA_TYPE plus one extra LIKE arg. But three of the sketch's reuse claims do not hold. RenderEffect.createBlurEffect applies to a RenderNode/View, not a Canvas, so the API-31 path is RenderNode + HardwareRenderer + ImageReader — just do downscale/upscale everywhere and be honest it is not the shipped Gaussian. The 224 gate fill is new code: every existing path fills the tensor from decoder YUV with pinned BT.601 integer maths that the strictness table is QA-tuned against, so photo firings will not match video firings at the same strictness. And Women/Men cannot work as sketched, because cropToTensor reads an NV21 ByteBuffer and a tracking-less detector yields null tracking ids that FaceTracker skips for voting by construction.

*The catch.* A photo has no temporal smoothing, so a single ML Kit false positive is 100% of the output rather than 100 ms of it — MIN_FULL_MS and BRIDGE_MS have no analogue, and the measured fact that ~23% of ML Kit 'faces' are objects (a dog votes male at 1.00) becomes user-visible. 'No FGS, no WorkManager' costs more than it saves: there is no result surface outside WorkInfo, so you inherit a new screen and state, no process-death survival and no cancel. Preflight is MediaExtractor-shaped end to end and returns UNREADABLE on a JPEG, so images need their own branch. EXIF is a trap worth pre-deciding: decode with ImageDecoder (which honours orientation) and encode a fresh JPEG, so no GPS is carried and no exifinterface dependency is added. Animated GIF/WebP and HEIC motion photos must be explicitly out of scope or this becomes a second pipeline.

*Cheaper version.* Ship the photo path as an interactive preview plus Save rather than a fire-and-forget job — a photo is one frame, which makes it the only place in this app where a before/after is nearly free (decode, composite, show, Save). That deletes the whole result-surface problem and directly answers the false-positive risk, because the user SEES the misfire before anything is written. Restrict v1 to solid fill and whole-frame, exactly reproducible from solidRgb with no second blur implementation to drift from CensorEffect's sigma curve, and publish into Pictures/Naqi while skipping Library integration (SavedCard's Open/Share already work off a content:// uri).

### 27. Stop quietly flattening HDR

`M` · 1 lens

**Ask media3 to preserve HDR on the censor path first, and when the device refuses, say so once instead of never.**

*Why.* RenderPipeline.kt:142 reads `if (!passthrough) setHdrMode(HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)`, so the same source gets two different fidelities depending on whether anything was censored. The passthrough half is a deliberate, documented decision — the comment above it says an untouched HDR source now stays HDR 'instead of being tone-mapped into a copy of itself' — but the asymmetry that follows was never weighed. media3 supports HDR_MODE_KEEP_HDR on API 31+ where the device has an HLG/PQ encoder and falls back on its own where it does not. The target device is an S23, which records HDR10+ by default, so the most likely source in the whole product is the one that comes back looking duller, on a job that then invites deleting the original.

*How.* Request HDR_MODE_KEEP_HDR on the non-passthrough path and add a Transformer.Listener.onFallbackApplied override alongside the existing onCompleted/onError (RenderPipeline.kt:193-201) to record what the device actually did — that listener is named in the repo already (video-performance-overhaul-plan.md:506) and was cut in plan-v2:139-145 as part of the 'Preserve/Compatible mode split', on performance grounds, in a document about the other 96% of the wall. Its value was fidelity and honesty and was never weighed on those. Resolve the mode once per job before the segment loop, for the same reason the bitrate is hoisted there: a fallback firing partway through a film would split the encoder configuration and Remux.concat's requireSameFormat would throw at the join, after hours. Then spend the result as one line on the finished job, which lands naturally in the receipt.

*The catch.* Device support is real and the repo has exactly one device, so if the S23's encoder refuses, the feature collapses to the honest sentence — which is still worth shipping and is the part that cannot fail. HDR through a GL effect chain is the least-exercised media3 path here and needs a pixel check, not a stage timer; the shader's blur and solid-fill maths have only ever been looked at in SDR. And it contradicts a written PRD line (prd:73, 'HDR input tonemapped to SDR'), which should be updated with a reason rather than quietly broken. qa-assets contains no phone-recorded HDR10+ clip today, though the S23 produces one on demand.

*Cheaper version.* Ship only the onFallbackApplied listener and the finished-job line, keeping the unconditional tone-map. That is the half that cannot fail on an unknown encoder, it turns 'we silently flattened your HDR' into a stated fact, and it puts the measurement in place so the KEEP_HDR request becomes a decision made with data rather than ahead of it.

### 28. Audition the music removal

`XL` · 2 lenses

**Hear about twenty seconds of your own audio with the music stripped, and A/B 'Voices only' against 'Voices + sounds', before starting a run that costs ~41 s of ORT per video-minute.**

*Why.* The stem choice is the blindest control in the app and the one that decides whether the output is usable: 'Voices only' strips the sound effects out of a kids' cartoon and leaves a lecture eerily dead, while 'Voices + sounds' leaks melodic music. A family filtering the same series weekly makes that bet every week, on the shape where audio is 65% of the wall.

*How.* One genuinely free win the pitch missed: the A/B is not 2x. HtdemucsSession's `keep` only chooses which blocks are copied out of ORT — the graph emits all four stems either way (DemucsSeparator.kt:696-703) — and the driver sums the kept masked spectrograms before a single iSTFT, purely by linearity (:418-423). So one session with keep = [OTHER, VOCALS] yields both mixes from the SAME inference, and the only extra cost is a second iSTFT+OLA per chunk, which lives in olaNs, a minority of the stage next to inferNs. Whoever builds this must not open two sessions. Graph load is also not the horror the pitch implies: sessionCreateMs measured 881 ms on an S23.

*The catch.* The decode seam named in the sketch does not exist in the shape claimed, and using it would ship pitch-shifted garbage: AudioDecoder.decode is private, sampled = true is 20 fixed 2 s stats windows derived from KEY_DURATION (null under 80 s), and the sampled path deliberately bypasses the resampler (useSonic = windows == null && pcmRate != 44100) because mean/std do not care about sample rate — while htdemucs requires 44.1 kHz. So this needs a new public bounded-window decode that seeks, resamples AND flushes Sonic's tail, in the most delicate loop in the audio package. Concurrency is the RAM problem, not the peak: the shipped separate stage already peaks 1.27-1.45 GB with a Compose Activity alive, but a preview under its own unique work name could run alongside a real job for ~2.6 GB, while putting it on FilterWorker.UNIQUE_WORK with KEEP makes it silently no-op and disables Start. And the stated justification is inverted: the owed DILATE2_MIN_SCORE test is about the worst chunk the gate SKIPS, so choosing the window by highest gate score selects chunks the gate always separates and cannot settle it.

*Cheaper version.* Skip the player, the scrubber, the worker and the i18n. Add a DEBUG_HOOKS branch beside MainActivity's maybeAutorun — `-e audition_ms 20000 -e audition_at_ms N` — that runs the existing audio-only shape over a clipped window and publishes both mixes into Music/Naqi through the already-shipped Publish.audio path; the tester plays them in any music app. That is S, it settles the owed listening test open since perf-plan-v4, and if a user-facing audition is ever justified that debug path is already its engine. Point it at the worst SKIPPED chunk, not the highest-scoring one, or it measures the wrong thing.

---

## Considered and dropped

Recorded so nobody re-proposes them without knowing what killed them.

- **Change the look without re-analysing** — The data-model claim is true — a segment checkpoint stores bare tracks and censorSpans rebuilds both the intervals and the whole-frame promotion from (firings, tracks) — but JobStore.delete wipes the work dir on every success path (FilterWorker.kt:457,786,874), the wall-time saving is real only for censor-only, and it buys a cheaper redo of a mistake that a pre-commit sample prevents outright for the same effort.
- **Music map: where the music is, and whether it left** — The load-bearing 'this is free' claim is false against shipped code: AudioDecoder.stats has not walked the whole track since A3 shipped — STATS_WINDOWS = 20 gives 20 x 2 s windows, 0.43% of a feature film — so there is no existing pre-pass to hang YAMNet on, and the new whole-track decode costs roughly what the skip saves.
- **First-run model staging screen** — Both premises are false: ENOSPC during extraction already surfaces as err_out_of_space and Preflight's 2 GiB gate makes it near-unreachable, and extraction is per-model-lazy (four independent trigger points) so a censor-only job materializes 6.4 MB, not 110 — staging all four eagerly would permanently cost censor-only users ~104 MB of filesDir for models they never load, a regression against the size constraint rather than a fix.
- **The proof-frame grid on the receipt** — Cut from the receipt: getFrameAtTime returns FULL-resolution Bitmaps (~8.3 MB per 1080p tile, ~400-500 MB for 40-60 tiles against a 1.5 GB ceiling), OPTION_CLOSEST decodes from the preceding sync sample which this repo's own plan puts ~5 s apart, and it carries the one genuinely dangerous failure mode in the set — a wrong URI showing source frames, i.e. the app displaying exactly what it exists to hide.
- **User-saved named rules** — Cut from presets: Prefs' own KDoc says 'Six scalars do not need a schema. Revisit if this ever has to hold a list', and a rules list triggers a new JSON file, a back-compat read, an active-rule id, name entry, edit/delete UI, RTL and a resolution order in ShareSheet — the entire M-to-L, for a demand nothing in docs/ or the ledger evidences.
- **A learned per-device ETA factor** — stats.finish() runs in a finally in all five shapes so it samples cancelled and failed runs (a job killed at 20% writes a factor 5x too low and poisons the median permanently), JobStats is constructed per doWork so a resumed job contributes only its tail, and against ±32% thermal variance on one identical APK it is a lot of machinery for a pre-Start number that KEY_ETA_MS's live extrapolation supersedes a few percent into the job.
- **PickMultipleVisualMedia for the picker** — Both vet passes rejected it independently: OpenDocument already needs no storage permission so the stated benefit is zero, and photo-picker URIs cannot take a persistable grant, so read access dies with the process — exactly the failure MainActivity.kt:106-109 already documents for shares, and the one a queued item waiting behind a multi-hour job is guaranteed to hit.
- **untrackedCount as an under-coverage signal** — Inverted: an untracked detection gets a synthetic id (FaceTracker.kt:125), casts no vote, and no vote CENSORS (:302), so a high untracked share on fast-cut content means more covering — a warning built on it would fire on exactly the footage that is already over-covered.

---

## Not a feature, but it gates all of them

The Play release itself. `REQUEST_INSTALL_PACKAGES` and the self-updater still ship on `main`,
and `NOTICE:16`'s NOASSERTION NSFW weights are unresolved. Entry 24 is the only item on this list
that is really release engineering rather than a feature, and its cheaper version — redo
`origin/cut-download` on top of `main` as a single variant — is the actual next step.
