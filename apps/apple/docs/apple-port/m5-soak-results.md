# M5 exit criterion — 90-minute film, forced kill, resume

> **Exit:** 90-min film survives a forced kill + relaunch and resumes to a playable output.

Run on the **iPhone 17 Pro simulator** against `qa-assets/long-film.mp4` — 90.2 min (5410.463 s),
480×854, 30/1 fps, H.264 + AAC, 584 MB, built by `scripts/fetch-models.sh`. Censor-only, default
options, driven through the real app (`-naqiScreen run`), no debug hook.

**Why a synthesized asset.** The Android repo's 155-minute `movie-test.mp4` is referenced in its
`long-film-plan.md` but is not in its `qa-assets/`. This test needs duration and segment boundaries,
not shot diversity — the gate and face-detection parity work runs against the real clip. It is
deliberately 480×854 at ~750 kbps because `EncodeSettings` targets `min(source × 1.3, tier cap)`, so a
1080p source would have written ~13 GB of output onto a disk with 13 GiB free.

## The route, from the app's own log

```
[job] start segmented key=07e5b5bc1295f9c8 dur=5410463ms music=false censor=true
```

The real 30-minute gate, not `forcedSegmentMs`. 18 segments of 5 minutes.

## The kill

SIGKILL — not `simctl terminate`, which sends SIGTERM and lets the app flush, i.e. the opposite of
what this tests. Killed mid-render with one segment complete and one in flight:

| before the kill | |
|---|---|
| `analysis.json` | 3.0 MB, written 21:48 |
| `seg-000.mp4` | 35 MB, complete |
| `seg-001.mp4.part` | 8.7 MB, **mid-write** |

## What the resume did

| | result |
|---|---|
| `analysis.json` | **not re-run.** mtime unchanged. The ~20-minute analyze pass was skipped whole |
| `seg-000.mp4` | **not re-rendered.** mtime and SHA-256 unchanged |
| `seg-001.mp4.part` | stale partial discarded and restarted — the `.part`-then-rename rule means a file under its final name *is* complete |
| queue | `enqueue ignored, already queued` — the persisted row drained itself on launch |
| completion | 18/18 rendered → `concat.mp4` (635 MB) → segments deleted → mux → `out.mp4` (702 MB) → published |
| work dir | cleared on success |

`IMG_0017.MP4`, 701 636 067 bytes, in the photo library.

## Output integrity — 18 segments, 17 seams

| | source | output | delta |
|---|---:|---:|---:|
| video duration | 5410.441016 s | 5410.442969 s | **+1.95 ms** |
| video frames | 162 048 | 162 049 | **+1** |
| audio duration | 5410.483667 s | 5410.483667 s | **0** |
| container duration | 5410.483667 s | 5410.483667 s | **0** |

**Duplicate-PTS frames across the whole output: 0.** That is the direct check on the seam bug — it
wrote the straddling frame into *both* neighbouring segments, which shows up as duplicate PTS and
nothing else. Scanning all 162 049 frames finds none.

Against hazard 11: the concat advances by a running cursor, which is the accumulating form Android
measured at ~1 s of drift over 31 joins. Here it is **1.95 ms over 17**, 25× inside the PRD's 50 ms
budget, and the audio track's duration is bit-identical. Against hazard 12: Android lost ~2 frames per
seam (−34 over this many joins) and accepted a ~100 ms freeze at each. This path gains **one** frame
carrying 1.95 ms, so it is a sub-frame tail artifact rather than a per-seam cost.

**What this run does NOT prove.** At 30/1 fps every 300 000 ms cut is an exact frame time, so this
content never exercised the non-frame-aligned path the reader head-guard exists for. That case is
covered at unit level — `RenderTests` cuts at 3010/7010 ms and the assertion is mutation-verified —
and by `tv1-h264.mp4` at 29.97 fps, which is below the 30-minute gate and therefore runs unsegmented.
A >30-minute 29.97 fps end-to-end soak is still unrun.

## Memory

Sampled externally with `footprint -p` every 60 s for the whole run:

| | |
|---|---|
| steady state, render | 39–160 MB |
| **peak** | **236 MB** of the 1536 MB budget |
| shape across 18 segments | **flat** — 226 MB at segment 2, 236 MB at segment 18 |

Flatness across segments is the anti-leak evidence; the absolute is a simulator figure.

> **This 236 MB is NOT a proof of the 1.5 GB budget, and must not be read as one.** The asset is
> 480×854, chosen so 90 minutes of output would fit the disk. Footprint scales with **frame area**,
> not duration: the same build peaks at **471 MB** on a *12.8-second* 1080×1920 clip — twice this
> figure at 1/400th the length. What this run proves is that memory is **flat across segments**,
> i.e. no per-segment leak over 90 minutes. The budget question is answered at full resolution in
> `m7-perf-results.md`.

> **`ps -o rss` is the wrong tool and reads 10× high here** — 2700 MB against 236 MB of real
> footprint, because a simulator app is a host process whose RSS includes shared mappings and the
> simulator's own address space. `phys_footprint` is what jetsam measures.

`MemoryFootprint.note`/`logPeak` are now wired into `JobRunner`, so the same number can be read off a
*device* from a log line — `footprint -p` and Instruments both need a Mac attached, and M7 asks for
peak RAM after a 90-minute job nobody watched.

## One thing that was not a product bug

The first publish attempt sat at "Finishing up 99 %" for eight minutes. The soak's own
`simctl uninstall`/`install` had reset the Photos add-only authorization, and the job was blocked on
the permission alert. Granting it and relaunching completed the publish — and exercised a further
resume path, since `concat.mp4` is itself a checkpoint: the relaunch skipped all 18 segments and went
straight to the mux.
