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

### 1. A real film's audio has no resume path

**Exit:** a 155-min AC-3 film runs segmented and resumable.

`Remux.concat` copies the source audio track sample-for-sample, and framework `MediaMuxer` accepts only
`audio/mp4a-latm`, `audio/3gpp`, `audio/amr-wb` (mirrored by media3's own `FrameworkMuxer`). Films carry
AC-3 / E-AC-3 / DTS. So `Remux.canCopyAudio` returns false and the job falls back to the unsegmented route:
**correct output, no resume, and for a feature film that is a ~3 h job with nothing to fall back on** — exactly
the failure Phase 2 exists to remove.

Note what this means for the soak below: **`movie-test.mp4` is AAC 44.1 kHz stereo**, so it takes the
segmented path and *will not exercise this gap at all*. The gap is invisible to every asset in `qa-assets/`.
That is the main reason it is first here rather than filed as a nice-to-have.

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
  - Needs an AC-3 asset. There is none in `qa-assets/` — this is blocked on one file, not on design.

## Confidence, not correctness

### 2. The seam drops frames, and there is now a hypothesis worth testing

**Exit:** output frame count equals source frame count, or a residual that is named with a number and a cause.

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

- [ ] **Diagnose before fixing.** `SegmentConcatSpike` already prints per-segment `firstPts`/`maxPts`/
  `samples`; point it at the 23.976 asset and compare each segment against its intended window and frame
  period. The question it answers: is segment N+1's first frame at ~0 (so the loss is at the *end* of N) or
  ~2 frame periods in (so media3 drops leading frames)? A concrete suspect for the latter is
  `ExoAssetLoaderVideoRenderer`'s `shouldDropFrameToMaintainTargetFrameRate`, whose expected-timestamp state
  resets per export.
- [ ] **If it is boundary rounding: snap segment boundaries to frame times** rather than to round
  milliseconds — `startMs = round(k * segmentFrames / fps)`. This is strictly lazier than the overlap+drop
  machinery the Phase 2 review rejected: it changes only the arithmetic in `Checkpoint.plan`, and adds no
  per-part end time, no drop rule and no new monotonicity invariant.
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

- [ ] **Log per-chunk wall time** in `AudioPipeline`'s `onChunk` (it already logs `chunk N/total`; add the
  delta). One line, and it makes every future run self-diagnosing.
- [ ] **Run one uninterrupted combined-segmented job to completion** on the 193 s asset, screen off, no other
  jobs, and plot chunk time against chunk index. If the uninterrupted curve also reaches ~11 s, close this.
- [ ] Only if the curves differ, look at what resume actually adds per chunk: the temp-write-plus-rename
  checkpoint (×100), the PCM append, and the fact that `feed()` runs ahead through skipped chunks. All three
  are cheap on paper, which is why the control comes first.

### 4. Feature length has still never been run to completion

**Exit:** one completed combined run on `movie-test.mp4`, with per-stage `SOAK` numbers.

Every Phase 2 number in `tasks.md` M5 comes from a 193 s clip with 60 s segments forced by a debug flag. The
Phase 0 soak was stopped by hand after render. So **"a 2 h job finishes" is still not proven**, and it is the
one claim the whole plan is about.

- [ ] Run it. ~3.1 h projected, on charger, `--el segment_ms` unset so it uses the real 5-min segments.
- [ ] It also closes two things nothing else can:
  - the **`separate` number at feature length**, which is what Phase 0's ETA ceiling is blocked on — the live
    ETA extrapolates over overall percent while the bands are not proportional to real cost (analyze+vote
    spend 25 points on 73 min, render 25 points on ~10 min), and reweighting them was deliberately left
    rather than re-guessed;
  - whether **30 segment seams** on a 23.976 film are noticeable in practice, which decides how much item 2
    is worth.
- [ ] Watch for the 6 h FGS cap. At ~3.1 h one film is comfortable; a second film the same day is not, and
  that is the case the Resume button exists for and which has never actually been triggered by the cap.

## Blocked on things that are not code

Unchanged, and restated because they gate acceptance criteria rather than features:

- [ ] **QA sets** — beach/gym/lingerie, cartoon/illustration, profile-face. Blocks the strictness 100/0 and
  female-face acceptance criteria, and the constant-freezing tuning pass. Open since M1. Also worth adding:
  an **AC-3 film** (item 1) and a **fragmented-MP4-without-`sidx`** clip, which is the one input class that
  makes a clipped export throw `REASON_NOT_SEEKABLE_TO_START` instead of falling back.
- [ ] **An SD 778G-class device.** Every number in this repo is a Galaxy S23 and reads optimistically fast.
  Open since M0. The segment length, the ETA factors and the FGS-cap headroom all inherit that bias.
- [ ] **Open question 5 from `long-film-plan.md`:** `vocals`-only silences sound effects. Accepted for clips —
  is it still accepted across a 2 h film, or does long-form make `vocals+other` the honest default? Answerable
  only by watching one, which item 4 produces.
- [ ] **Open question 2:** does the user watch, or leave it overnight? If overnight-plugged-in is the real
  usage, the wall-clock number matters far less than survival, and survival is now built.

## Small and known

- Pre-API-31 cannot distinguish a user cancel from a system stop, so it **keeps** the work directory and lets
  the 7-day sweep collect it. Deliberate: an orphan is a cheaper mistake than deleting three hours of work.
- `models/` (~87 MB of downloaded ONNX) still sits in backup-eligible `filesDir`. Pre-existing, not introduced
  by Phase 1, and a one-line `<exclude>` whenever someone cares. `naqi-work` is already exempt by living in
  `noBackupFilesDir`.
- The debug `--el segment_ms` override also switches the resumable separator on, so both halves of Phase 2 can
  be exercised on a short clip. It is the only way to iterate on this; keep it.

## Deliberately not doing

- Making the pipeline faster. Unchanged from `long-film-plan.md`: htdemucs at ~1.3× realtime is this model's
  floor, and a smaller model is a different project.
- Per-segment overlap-and-drop to recover seam frames — superseded by boundary snapping in item 2, which is
  less code for the same or better result.
- Checkpointing mid-segment, or mid-export inside a single Media3 export. Transformer has no such API and the
  segment is already the unit.
- A background queue, multi-job scheduling, or per-segment previews. Still not the problem being solved.
