# long-film-followups.md — what Phase 1 + Phase 2 left open

Source: `long-film-plan.md` (Phases 0–2, all items closed), `tasks.md` M5. Written 2026-07-28, straight after
the Phase 1 + Phase 2 device verification, while the gaps are still fresh and honest.

Phase 2's exit criterion is met: a job survives being killed at any point and resumes within one segment's
worth of work, verified by SIGKILL at all three interruptible stages. What follows is **not** that criterion
being re-litigated. It is the four things that verification either exposed or could not reach, plus the
long-standing blockers restated so there is one place to look.

## The one that matters

Everything else here is quality or confidence. This one is the difference between Phase 2 working for the use
case it was built for and Phase 2 working for the test asset.

### 1. A non-AAC source has no resume path — **DONE 2026-07-28**

**Exit:** a 155-min AC-3 film runs segmented and resumable — and until it does, the user is told before Start.

> **CLOSED by the capable half.** A non-AAC source now takes the segmented route, and it resumes. Verified
> on device (S23) against assets built for this, not against the film — per instruction, no feature-length
> run was made, so the claim is "193 s of AC-3/Opus", not "155 min".
>
> | case | before | after |
> |---|---|---|
> | AC-3 stereo MKV, 4 forced segments | unsegmented, no resume | segmented, **4625/4625 frames**, AAC out |
> | AC-3 **5.1** MKV | same | segmented, 4625/4625 |
> | Opus MKV | same | segmented, 4625/4625 |
> | AC-3, SIGKILL mid-render → restart | n/a | **resumes**: 4 analyses reused, 2 renders skipped, 7.9 s |
> | **no audio track at all** | **threw after the whole pass** | segmented, succeeds |
>
> Three things the plan below did not anticipate:
>
> 1. **`canCopyAudio`'s Boolean was hiding a bug.** It answered `true` for "no audio track" *and* for
>    "copyable", but the caller then passed `TrackSource.FromUri` unconditionally and `Remux.concat` calls
>    `requireTrackIndex("audio/")` — so a long **silent** source (screen recording, dashcam) threw after
>    analyze+render, reported as the generic "Filtering failed". It is now a 3-state `ConcatAudio`
>    (`COPY | TRANSCODE | NONE`), and `NONE` passes the null audio `concat` already accepted. Pre-existing,
>    fixed because the fix was two lines inside the branch this item rewrites anyway.
> 2. **The soft clip is load-bearing and the plan never mentioned it.** `AudioDecoder`'s >2-channel BS.775
>    downmix is deliberately un-normalized ("level is irrelevant downstream — the separator divides and
>    re-multiplies by the same std"). Delete the separator and that licence is void: a 5.1 asset measured
>    **+10.7 dBFS** on the raw downmix, which `AacWriter`'s int16 quantizer would have hard-clipped into a
>    square wave. `softclip` moved from a `DemucsSeparator` member to file level and the transcode's sink
>    applies it. Honest ceiling, recorded at the call site: loud 5.1 passages are **limited**, not merely
>    guarded.
> 3. **The AAC writer was 10x slower than it needed to be, and it was nobody's item.** The first transcode
>    took **135 s for a 193 s track (1.43x realtime)** — which would have been ~110 min on a film and would
>    have made this whole item a bad trade. A pure decode of the same track was 6.1 s, so it was not the
>    AC-3 decoder. Cause: `AacWriter.drainEncoder` is called after every input buffer and returns on the
>    first `TRY_AGAIN_LATER`, so it paid a **blocking 10 ms `dequeueOutputBuffer`** ~10 000 times per pass.
>    Non-blocking mid-stream (still blocking on the EOS drain, where the tail is what we are waiting for):
>    **135 s → 12.9 s**, byte-identical output. This also speeds up the resumable music path's `encodePcm`
>    AAC pass, which shares the loop.
>
> **Bullet 1 (warn before Start) was deliberately NOT built**, and the reasoning is worth keeping because
> it is not "the other bullet covers it": the dialog is gated on `Eta.estimateMs > CONFIRM_THRESHOLD_MS`,
> and the one path that still runs a film unsegmented — `FrameSampler.probe` failing to report a duration,
> so `Checkpoint.plan` returns empty — produces `etaMs == 0` and therefore **suppresses that very dialog**.
> The warning could not have fired for the case that survives. Recorded as open at the end of this file
> rather than papered over with a string.

`Remux.concat` copies the source audio track sample-for-sample, and framework `MediaMuxer` accepts only
`audio/mp4a-latm`, `audio/3gpp`, `audio/amr-wb` (`Remux.kt:30`, mirrored by media3's own `FrameworkMuxer`).
That rejects AC-3 / E-AC-3 / DTS — what a film carries — **and Opus / Vorbis, i.e. every MKV/WebM source**, so
this is wider than the film case that names it. `Remux.canCopyAudio` returns false and the job falls back to
the unsegmented route: **correct output, no resume, and for a feature film that is a ~3 h job with nothing to
fall back on** — exactly the failure Phase 2 exists to remove.

Note what this means for the soak below: **`movie-test.mp4` is AAC 44.1 kHz stereo**, so it takes the
segmented path and *will not exercise this gap at all*. The gap is invisible to every asset in `qa-assets/`.
That is the main reason it is first here rather than filed as a nice-to-have.

The honest half and the capable half are separable, and only the second waits on anything:

- [x] **NOT DONE, DELIBERATELY — it could not have fired for the case that survives.** See the box above:
  the dialog is gated on an ETA that is 0 exactly when the duration probe fails, which is the one remaining
  silent non-resumable path. The AC-3/Opus case it was written for no longer needs a warning because it now
  resumes. Original note follows:
- [ ] **Say so before Start.** Today the fallback is a `Log.w` (`FilterWorker.kt:104`) and nothing else — the
  user begins a 3 h non-resumable job and finds out only if it dies. The >30 min confirm dialog already exists
  (`Eta.CONFIRM_THRESHOLD_MS`), so this is a string plus a boolean: "this file can't be resumed if
  interrupted". It turns the opening sentence of `long-film-plan.md` back into a choice the user made, and it
  ships today without waiting on anything below.
- [x] **DONE.** `AudioPipeline.transcodeToAac` — `AudioDecoder.stream` → `softclip` → `AacWriter`, one decode
  pass, ~35 lines as predicted. It runs first thing inside `runSegmented` (after `setForeground`, so it does
  not spend the pre-foreground budget) because a device with no decoder for the codec cannot do the job at
  all, and failing in seconds beats failing after hours. `firstAudioPtsUs` reads the anchor off a bare
  extractor rather than running a second full decode for one `Long`. Details and the three surprises in the
  box above. Original note follows:
- [ ] **Transcode the source audio to AAC once, up front, for a segmented censor-only job.** Then the concat
  copies *that*, and every film can resume. The pieces already exist and already compose this way:
  `AudioDecoder.stream` decodes any codec MediaCodec can open (AC-3 included) and `AacWriter` encodes+muxes —
  it is `AudioPipeline.removeMusic` with the separator deleted, so on the order of 30 lines.
  - **The quality argument is free**, and worth writing down because it looks like a regression and isn't:
    bit-identical audio passthrough was *never* available for a non-AAC source. Today Transformer silently
    transcodes it inside each export. So this replaces one transcode with one transcode. An AAC source still
    gets copied verbatim by the concat and stays bit-identical, which is the case the PRD's passthrough
    criterion is actually about.
  - Do **not** checkpoint the transcode. Decode+encode of a film's audio is 1–2 min against the ~3 h job;
    redoing it after a kill is cheaper than the state to avoid redoing it.
    - **Followed, but sharpened:** there is no checkpoint — no state file, nothing to keep in sync — and yet
      a resume does not redo it. The transcode writes `audio.m4a.part` and renames, exactly as
      `renderSegments` commits a segment, so the final name exists only over a complete file and
      `audioTemp.length() == 0L` is a safe skip. That is an atomic write, not a checkpoint. Measured: the
      resumed AC-3 run dropped from 19.9 s to **7.9 s** because the 13.4 s transcode was skipped; on a film
      it saves ~10 min of redoing work on every resume. The `finally` must NOT delete `audio.m4a` — the only
      state in which it survives is a resumable failure, which is exactly when both writers want it kept
      (the separator's copy is rebuilt from 1.6 GB of PCM otherwise).
  - **Count it in `Preflight`.** The transcoded track is another ~150–220 MB file with the same lifetime as
    the PCM scratch, and `Preflight.check` already takes `extraScratchBytes` for exactly this
    (`FilterWorker.kt:128`). One addend — otherwise a full disk finds it before the plan does.
  - **Not blocked.** No AC-3 or Opus file exists in `qa-assets/`, but unlike the QA sets below, one does not
    have to exist in the world first: `ffmpeg -i movie-test.mp4 -c:v copy -c:a ac3 movie-ac3.mkv` makes one in
    seconds from an asset already on disk. An MKV+Opus clip — already on the QA manifest (`tasks.md:17`) —
    exercises the identical branch.

## Confidence, not correctness

### 2. The seam drops frames — **DONE 2026-07-28, but the hypothesis below was wrong**

**Exit:** output frame count equals source frame count, or a residual that is named with a number and a cause.

> **CLOSED on the first half: output frame count now equals source frame count.**
>
> | asset | before | after |
> |---|---|---|
> | `women-music-3min-video.mp4` 24000/1001, 4 segments | 4619 / 4625, worst gap **148.5 ms** | **4625 / 4625**, worst gap **41.8 ms** (one frame period) |
> | `test-video.mp4` 30/1, forced segments | 383 / 384 | **384 / 384** |
>
> **The cause is not boundary rounding, and the fix below is not the one this item proposed.**
>
> media3 ends a clipped read at the first sample **in decode order** whose pts reaches the clip end
> (1.10.1 `ClippingMediaPeriod.java:430-438` — it converts that sample to EOS and stops the stream). On any
> B-frame source the frames that *display* before the boundary but *decode* after that sample are therefore
> never read. Loss is at the **tail** of segment N, never the head of N+1 — confirmed on device, every
> segment reports `firstPts=0` and `REBASED=true`. Count per seam is 0–3 and is a pure function of where the
> cut lands inside the B-pyramid. Simulating that rule against the real packet tables reproduces every
> measured number exactly: 299/300, 4619/4625, and the 148.5 ms gap to four significant figures.
>
> **The item's own suspect is dead.** `shouldDropFrameToMaintainTargetFrameRate` is gated on
> `expectedTimestampDeltaUs != TIME_UNSET`, set only from `EditedMediaItem.frameRate`, which defaults to
> `RATE_UNSET_INT` and which `RenderPipeline` never sets. It returns false unconditionally in this app.
>
> **Mid-interval snapping — the fix proposed below — was simulated and does not work.** It only moves the
> cut inside the B-pyramid, so the loss reshuffles rather than disappearing: 30 fps `1 → 1`, 23.976
> `6 → 4`, and the 6→4 is luck. It also cannot be aimed: `MediaFormat` reports **24** for 24000/1001
> content on the S23 (measured, `fps=24.0`), and `FrameSampler.probe` falls back to `30f` when the key is
> absent — at which point the "snap" lands at an arbitrary phase and 23.976 gets *worse* (7 lost vs 6).
> **Delete that bullet; it is disproved, not deferred.**
>
> **What actually works: snap interior boundaries to the next SYNC SAMPLE.** Nothing decoded before an IDR
> displays after it, so the decode-order EOS fires exactly at the IDR and the previous segment reads every
> frame it owns. `Checkpoint.plan` grew a `cutAtMs` hook (identity by default, so an unopenable source
> plans exactly as before) and `FilterWorker.planFor` supplies it from one `MediaExtractor` with
> `SEEK_TO_NEXT_SYNC`. Three edge cases cost more than the snap itself:
> - **No sync sample at or after the cut** → answer `durationMs`, collapsing that cut and every later one
>   into the final segment. Answering the nominal ms instead puts the loss straight back — that is exactly
>   why `test-video.mp4` (whose only sync samples are at 0 s and 8.3 s) measured 383/384 on the first try.
> - **A seek that does not move forward** → keep the nominal cut. Otherwise a source that cannot be seeked
>   by time answers the same instant for every cut, `distinct()` collapses them, and a 2.6 h film silently
>   runs as ONE segment with no resume.
> - **Planning throws** → return `emptyList()` (run unsegmented), *not* an unsnapped plan. `jobKey` does not
>   encode the plan, so a first run that snapped and a resume that fell back would share a work directory and
>   place rendered segments at wrong absolute times — silently corrupt output, worse than dropped frames.
>   `jobKey` also gained a literal `"plan2"` so pre-snap work directories orphan instead of being resumed into.
>
> Boundaries stay whole ms on purpose: `CensorGlEffect` reconstructs absolute time as
> `presentationTimeUs / 1000 + startMs`, which only equals `floor(absoluteUs / 1000)` for a whole-ms offset.
> The price is that both passes seek to the sync sample *before* the boundary and decode ~5 s per segment
> they discard, up from ~2.6 s. Under 1 % of decode work, and removing it means plumbing µs through
> `RenderSegment`, `Remux.concat` and the EDL lookup.
>
> **Caveat to keep:** measured on H.264 closed-GOP sources. `SAMPLE_FLAG_SYNC` comes from `stss`, and for
> HEVC that legitimately lists CRA pictures, whose RASL leading pictures decode after the CRA and display
> before it — the identical failure mode. Worst case is today's behaviour, so this is a caveat, not a blocker.
>
> **Correction to the table below:** `test-video.mp4`'s "1 frame per seam" was an artifact of reading the
> probe's own tail as a seam. `SegmentConcatSpike` exports `[0,5000)` and `[5000,10000)`; its only seam is at
> 5 s and loses **0**. The lost frame is at the 10 s cut, which is the probe's end, not a join. There was
> never a second effect to stack.

Measured on the 193 s asset, 4 segments: **4 619 frames out of 4 625**, and the largest inter-frame gap is
**148.5 ms at the 60 s seam** against a normal 41.7 ms. Reads as a ~100 ms freeze per seam. No drift, no
desync — the offsets are the intended segment starts, so error cannot accumulate.

The frame rates make this testable rather than mysterious:

| asset | fps | seam behaviour |
|---|---|---|
| `test-video.mp4` (CSD spike) | **30/1**, integer | seg-0 150 samples, seg-1 **149**, total 299 vs 300 → **1 frame per seam** |
| `women-music-3min-video.mp4` | **24000/1001**, non-integer | **~2 frames per seam** |
| `movie-test.mp4` | **2997/125**, non-integer | untested — same family as the 3-min asset |

So there are plausibly two effects stacked: a boundary that is not a frame time (at 23.976 a 60 000 ms
boundary lands mid-interval), *and* something that drops a frame even when the boundary is exact (30 fps
already loses one). Boundary rounding alone does not explain 148 ms.

- [x] **DONE — and it answered the question it was written to ask.** Pointed at the 23.976 asset on device:
  `fps=24.0` (so the snapping premise was already dead), seg-0 `samples=117` of 120 with `maxPts=4838166`,
  seg-1 `samples=119` with `firstPts=0`. Segment N+1's first frame is at ~0 on every run, so the loss is at
  the **end of N**, not the head of N+1 — the second of the two possibilities this item lists. Original note
  follows:
- [ ] **Diagnose before fixing.** `SegmentConcatSpike` already prints per-segment `firstPts`/`maxPts`/
  `samples`; point it at the 23.976 asset and compare each segment against its intended window and frame
  period. The question it answers: is segment N+1's first frame at ~0 (so the loss is at the *end* of N) or
  ~2 frame periods in (so media3 drops leading frames)? A concrete suspect for the latter is
  `ExoAssetLoaderVideoRenderer`'s `shouldDropFrameToMaintainTargetFrameRate`, whose expected-timestamp state
  resets per export.
- [x] **DISPROVED, not deferred.** It was not boundary rounding. Simulated against the real packet tables:
  mid-interval snapping leaves 30 fps at 1 lost and takes 23.976 from 6 to 4 by luck, and it cannot be aimed
  because the device reports fps as an integer. Superseded by the sync-sample snap in the box above.
  Original note follows:
- [ ] **If it is boundary rounding: snap boundaries to the MIDDLE of a frame interval**, not to a frame time.
  `startMs = round(k * segmentFrames / fps)` still rounds to whole ms, and at 23.976 the frame period is
  41.7083 ms — so a snapped boundary lands up to 0.5 ms either side of the frame's own timestamp, which is
  exactly the sub-frame ambiguity deciding whether that frame belongs to segment N or N+1. Put the boundary
  halfway between two frame times instead and there is ~20.85 ms of margin either way, so no rounding wobble
  can change the answer and the diagnosis never has to ask which way it rounded. Same one-line arithmetic
  change in `Checkpoint.plan`, and still strictly lazier than the overlap+drop machinery the Phase 2 review
  rejected: no per-part end time, no drop rule, no new monotonicity invariant.
- [ ] If it is leading-frame dropping inside media3, that is upstream. Record it, keep the boundary snapping
  for whatever it does buy, and stop.

### 3. The resumed separator ran 5× slower, and my comparison was not like-for-like

**Exit:** per-chunk timings from one uninterrupted run and one resumed run over the same source, on a quiet
device — after which this is either a real regression with a cause or a governor artefact with a curve.

Measured: the resumed run averaged **~11.2 s/chunk** over chunks 19–100 (909 s), against **~1.9 s/chunk** over
chunks 1–20 (38 s) before the kill. Thermal status 0 throughout, peak RSS 1.18 GB.

**The honest problem is the measurement, not just the number.** I compared the *first* 20 chunks of one run
against the *last* 81 of another. If the separator simply gets slower as a run proceeds — governor settling,
sustained-load clocks, page-cache pressure from the growing scratch file — then both numbers are right and
nothing is wrong. I never took the control.

- [x] **DONE 2026-07-28**, landed by `perf-plan.md` Phase 3 at both separator sites — `chunk N/total NNNNms`.
  The timer is reset *after* `thermalYield`, so a throttle pause is not billed to the next chunk, and the
  first line of a RESUMED run is garbage (skipped chunks report nothing, so it carries the whole skip-phase
  re-decode). Every future run is now self-diagnosing, which is what makes the control below free.
  **Partial data, nowhere near enough to close this item:** an uninterrupted 7-chunk run measured
  2516/2331/2244/2250/2349/2228/2143 ms — drifting *down* (warm-up), not up. But 7 chunks over 14 s cannot
  speak to a 909 s run, so the governor-settling hypothesis is still untested. Take the control from item 4.
- [ ] **Take the control from item 4's film run, not from a dedicated one.** That run is already uninterrupted
  and goes to completion, so with the line above it yields a ~3 000-chunk curve at feature length on the real
  asset instead of ~100 chunks on a clip. Plot chunk time against chunk index; if the uninterrupted curve also
  climbs to ~11 s, close this with no extra run at all.
- [ ] Only if the curve stays flat, look at what resume actually adds per chunk: the temp-write-plus-rename
  checkpoint (×100), the PCM append, and the fact that `feed()` runs ahead through skipped chunks. All three
  are cheap on paper, which is why the control comes first.

### 4. Feature length has still never been run to completion — **STILL OPEN, deliberately not attempted**

**Exit:** one completed combined run on `movie-test.mp4`, with per-stage `SOAK` numbers.

> **Not attempted 2026-07-28 by instruction** ("don't test long videos; it will take too long"). Items 1 and
> 2 were therefore verified on the 12.8 s and 193 s assets only, and every film-length number in this file
> stays a projection. Item 3 is blocked on this run and is likewise untouched.
>
> Two things this round changes about the projection, both in its favour: analyze is unchanged, but the AAC
> writer fix takes ~10 min off any non-AAC film's transcode, and the sync snap makes ~5 s/segment of extra
> decode-and-discard (~2.5 min over 31 segments) that was not there before. Net still well inside the noise
> of a ~2.3 h job.

Every Phase 2 number in `tasks.md` M5 comes from a 193 s clip with 60 s segments forced by a debug flag. The
Phase 0 soak was stopped by hand after render. So **"a 2 h job finishes" is still not proven**, and it is the
one claim the whole plan is about.

- [ ] Run it. ~3.1 h projected — **now materially less**: `perf-plan.md` cut analyze by ~61 %, which on the
  Phase 0 numbers is roughly 70.5 min → ~27 min, so a combined film should land nearer ~2.3 h. That projection
  is itself unverified, which is more reason to run this, not less.
- [ ] **This item is now the single biggest open question in BOTH plans.** `perf-plan.md` closes with the same
  gap: every number in it comes from a 12.8 s and a 193 s asset, so the 61 % is a per-frame effect that
  *should* scale but has never been seen at film length — nor has the segmented path under 1.3b's new
  producer/consumer channel over thousands of segments. One run closes both files' largest hole.
- [ ] It also closes two things nothing else can:
  - the **`separate` number at feature length**, which is what Phase 0's ETA ceiling is blocked on — the live
    ETA extrapolates over overall percent while the bands are not proportional to real cost (analyze+vote
    spend 25 points on 73 min, render 25 points on ~10 min), and reweighting them was deliberately left
    rather than re-guessed;
  - whether **30 segment seams** on a 23.976 film are noticeable in practice, which decides how much item 2
    is worth.
- [ ] Note the 6 h FGS cap, but do **not** schedule a second film just to watch it fire. At ~3.1 h one film is
  comfortable; a second the same day is not, and that is the case the Resume button exists for. The resume
  *mechanism* is already proven by SIGKILL at all three stages — the only untested part is whether `onTimeout`
  arrives as a graceful WorkManager stop rather than an ANR, and that path belongs to WorkManager, not to us.
  Worth 3 h of soak only if something suggests it misbehaves.

## Blocked on things that are not code

Unchanged, and restated because they gate acceptance criteria rather than features. **Genuinely blocked** —
these need content or hardware that does not exist here, and no command produces them:

- [ ] **QA sets** — beach/gym/lingerie, cartoon/illustration, profile-face. Blocks the strictness 100/0 and
  female-face acceptance criteria, and the constant-freezing tuning pass. Open since M1.
- [ ] **An SD 778G-class device.** Every number in this repo is a Galaxy S23 and reads optimistically fast.
  Open since M0. The segment length, the ETA factors and the FGS-cap headroom all inherit that bias.
- [ ] **Open question 5 from `long-film-plan.md`:** `vocals`-only silences sound effects. Accepted for clips —
  is it still accepted across a 2 h film, or does long-form make `vocals+other` the honest default? Answerable
  only by watching one, which item 4 produces.
- [ ] **Open question 2:** does the user watch, or leave it overnight? If overnight-plugged-in is the real
  usage, the wall-clock number matters far less than survival, and survival is now built.

**Not blocked**, listed apart so it does not inherit the word — one `ffmpeg` invocation each, from assets
already on disk:

- [x] **DONE.** All built from `women-music-3min-video.mp4` (not the 155-min file) and staged on the S23.
  `qa-assets/` is gitignored, so these are local: `women-ac3.mkv`, `women-ac3.mp4` (same branch, MP4
  container, so a failure cannot be blamed on Matroska), `women-ac3-51.mkv` (**5.1** — the stereo variants
  do not exercise the un-normalized downmix at all), `women-opus.mkv`, `women-frag-nosidx.mp4`.
- [x] **The fragmented MP4 is now characterized, and it is worse than "fails with a message".** Verified
  2026-07-28: it plans 4 segments correctly (`MediaExtractor.seekTo` works on it, so the snap is fine), then
  **`render seg-1` throws `ExportException: Asset loader error`** — seg-0 renders because it starts at 0 and
  needs no seek. The same file on the **unsegmented route succeeds** in 52.8 s. So a clipped export is the
  only thing that fails, `Preflight.messageFor` maps it to the generic "Filtering failed", and it is flagged
  `KEY_RESUMABLE` so Resume will fail identically forever.
  **Not fixed — pre-existing and outside items 1 and 2**, but the measurement makes the fix cheap and
  obvious if anyone wants it: catch `ExportException` out of `renderSegments` and re-run the job
  unsegmented, which is proven to work on this input.

## Small and known

- Pre-API-31 cannot distinguish a user cancel from a system stop, so it **keeps** the work directory and lets
  the 7-day sweep collect it. Deliberate: an orphan is a cheaper mistake than deleting three hours of work.
- `models/` (~87 MB of downloaded ONNX) still sits in backup-eligible `filesDir`. Pre-existing, not introduced
  by Phase 1, and a one-line `<exclude>` whenever someone cares. `naqi-work` is already exempt by living in
  `noBackupFilesDir`.
- The debug `--el segment_ms` override also switches the resumable separator on, so both halves of Phase 2 can
  be exercised on a short clip. It is the only way to iterate on this; keep it.

## Opened by this round (2026-07-28)

- **A source whose duration cannot be probed still runs a film unsegmented and non-resumable, silently.**
  `FrameSampler.probe` failing gives `durationMs = 0`, `Checkpoint.plan` returns empty, and `Preflight`
  never looks at duration. This is the hole item 1's bullet 1 was aimed at, and the warning as designed
  could not have covered it — the same missing duration makes `Eta.estimateMs` return 0, which suppresses
  the confirm dialog the warning would have ridden on. Fixing it properly means a duration fallback (count
  samples, or trust the container), not a string.
- **A clipped export on a fragmented MP4 without `sidx` fails the whole segmented job** where the
  unsegmented route succeeds — measured, see "Not blocked" above.
- **The sync snap is verified on H.264 closed-GOP only.** HEVC `stss` may list CRA pictures, whose RASL
  leading pictures reproduce the exact failure the snap removes. Worst case is today's behaviour.
- **`AacWriter`'s blocking drain was costing ~10x on every AAC encode** and is now fixed, but nothing
  measures the resumable music path's `encodePcm` pass since. It should have improved by the same factor;
  unverified.
- **Pixel-validated, and it found a bigger bug than it was looking for.** `perf-plan.md`'s rule (counts
  cannot detect a censoring regression, only pixels can) applies here because moving a boundary moves the
  analyze grid with it. The straight frame-by-frame recipe does **not** work across this change — the two
  outputs have different frame counts, so everything after the first seam misaligns by index — so each
  output was instead compared **against the source at matched timestamps** (mean abs gray difference on a
  64×36 downscale; blur reads 0–3, a wrong scene reads 25+):

  | build / source | worst mismatch over 12 sampled timestamps |
  |---|---|
  | current, MP4 (`women-music-3min-video.mp4`) | **3.0** — all blur |
  | current, MKV (`women-ac3.mkv`) | **3.0** — all blur |
  | **pre-snap, MKV** | **77.7 at t=179 s** |

  Chased down: the pre-snap output's frame at 179.0 s is the source's **180 s** content. So segment 2's
  clipped export lost ~1 s at its **head**, and because `Remux.concat` rebases each part to its *intended*
  start, that shifted the segment's whole content ~1 s early — a second of desync, silently, on an MKV
  source. Nominal boundaries land mid-GOP and Matroska's cues are coarse; a sync-sample cut makes the
  rebase exact, which is why the snap fixes it. **Pre-existing at HEAD, not introduced here**, and it never
  showed up on the MP4 asset every prior measurement used — one more reason the item-2 table below was
  reading a container-specific effect as a general one. Single observation, mechanism inferred: worth
  re-checking if an MKV job ever looks out of sync again.

## Deliberately not doing

- ~~Making the pipeline faster.~~ **SUPERSEDED 2026-07-28 — see `docs/perf-plan.md`, which did it.** Analyze
  is **61 % faster** with byte-identical EDL output (the win is decode/inference overlap, not a quality
  trade). Two claims in the original line were also wrong: htdemucs measured **1.15×** realtime, not 1.3×,
  and it was not at its floor — ORT thread count alone bought another 4.8 %. A smaller model remains a
  different project. **Nothing in that plan touched this file's items 1, 2 or 4**, which are all still open.
- Per-segment overlap-and-drop to recover seam frames — superseded by boundary snapping in item 2, which is
  less code for the same or better result.
- Checkpointing mid-segment, or mid-export inside a single Media3 export. Transformer has no such API and the
  segment is already the unit.
- A background queue, multi-job scheduling, or per-segment previews. Still not the problem being solved.
