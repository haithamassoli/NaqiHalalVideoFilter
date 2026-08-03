# Plan — whole-frame blur instead of the face rect

**Goal:** an opt-in mode where a censored face blurs the **entire frame** for as long as it is on
screen, HaramBlur-style, instead of blurring only its padded rect.

Written 2026-07-30 against `main` @ `07f0057`.

---

## 1. The decision: convert in the EDL, not in the shader

The renderer already has a whole-frame path — `uCensorAll` in `COMPOSITE_FRAGMENT`
(`CensorEffect.kt:333`), driven by `Edl.fullFrameAt` (`Edl.kt:19`), used today for NSFW spans. And
`Edl.regionsAt` already returns empty under full-frame precedence (`Edl.kt:29`).

So the whole feature is: **turn each censored face track into a censor interval at EDL-build time.**
Everything downstream is unchanged.

| Approach | Touches | Testable without a device | Verdict |
|---|---|---|---|
| **Promote tracks → intervals in the EDL builder** | `FilterWorker` + one pure helper | yes (`EdlTest`, JVM) | **chosen** |
| New `wholeFrame` flag threaded to the shader | `FilterWorker` → `RenderPipeline` → `CensorGlEffect` → `CensorShaderProgram` → `drawFrame` | no (needs GL) | rejected |

The shader route adds 3 more hops of pass-through for one boolean and can only be verified on a
device. The EDL route records the decision in `edl.json`, so a checkpoint resume and a QA diff both
reproduce it, and the logic lands in a pure function a unit test can pin.

**Not changed by this work:** `CensorEffect.kt`, `RenderPipeline.kt`, `Edl`'s existing methods,
`toJson`/`fromJson`. `faceTracks` stays in the EDL — the new intervals suppress it via existing
precedence, and keeping it leaves the JSON diffable against pre-change runs.

## 2. The change, file by file

Seven files. Six are one-or-two-line plumbing for a boolean; the seventh is the only real logic.

### 2.1 `edl/Edl.kt` — the only logic (new code, no existing behavior touched)

```kotlin
/**
 * Gap under which two whole-frame spans merge rather than strobe. A face track ends 50 ms after its
 * last sample (SPAN_PAD_MS) and a re-detect a few frames later would otherwise unblur and re-blur the
 * whole frame — invisible when it is a rect, very visible when it is the picture.
 *
 * ponytail: one fixed bridge for every video. Tune in QA against a fast-cut clip; make it
 * shot-length-aware only if a real asset strobes.
 */
private const val BRIDGE_MS = 400L

/** Merge overlapping or near-adjacent ranges. Input need not be sorted; output is, and is disjoint. */
internal fun mergeRanges(ranges: List<LongRange>, bridgeMs: Long = BRIDGE_MS): List<LongRange> {
    if (ranges.size <= 1) return ranges
    val sorted = ranges.sortedBy { it.first }
    val out = ArrayList<LongRange>(sorted.size)
    var start = sorted[0].first
    var end = sorted[0].last
    for (i in 1 until sorted.size) {
        val r = sorted[i]
        if (r.first <= end + bridgeMs) { if (r.last > end) end = r.last }
        else { out.add(start..end); start = r.first; end = r.last }
    }
    out.add(start..end)
    return out
}

/** HaramBlur-style: every censored face span becomes a whole-frame span, merged into [intervals]. */
internal fun promoteFacesToFullFrame(intervals: List<LongRange>, tracks: List<FaceTrackEdl>): List<LongRange> =
    mergeRanges(intervals + tracks.map { it.startMs..it.endMs })
```

Merging is not cosmetic. The 155-min film in `long-film-plan.md` produced **3 362 tracks**;
`fullFrameAt` is a linear scan per rendered frame, so unmerged that is ~3 362 × ~232 k frames of
comparisons in pass 2. Merged, a film with people in most shots collapses to a few dozen ranges.

### 2.2 `work/FilterWorker.kt` — call it at both EDL build sites

```kotlin
// with the other KEY_* consts (~:811)
const val KEY_WHOLE_FRAME = "wholeFrame"

// with the other inputData reads (~:68)
private val wholeFrameBlur = inputData.getBoolean(KEY_WHOLE_FRAME, false)

// :501 (segmented) and :715 (single-pass) — wrap the interval list, nothing else moves
val finalIntervals = if (wholeFrameBlur) promoteFacesToFullFrame(intervals, faceTracks) else intervals
return Edl(finalIntervals, faceTracks.sortedBy { it.startMs })
```

Do it **after** the timeline-wide `intervalsFor` merge at `:498`, not per segment — segment
checkpoints at `:486` must keep their rects so a resume under a different flag still works.

### 2.3 Plumbing (mechanical, follow `blurUnknownFaces` exactly)

| File | Line | Change |
|---|---|---|
| `model/FilterOps.kt` | 16 | `val wholeFrameBlur: Boolean = false,` + a clause in the KDoc |
| `work/Queue.kt` | 145, 170 | `put("wholeFrameBlur", …)` / `optBoolean("wholeFrameBlur", false)` |
| `work/QueuedWorker.kt` | 63, 74 | `KEY_WHOLE_FRAME to wholeFrameBlur` / `getBoolean(KEY_WHOLE_FRAME, false)` |
| `ui/screen/OptionsScreen.kt` | ~208 | a switch next to `blurUnknownFaces`, inside the same censor-options block |
| `res/values*/strings.xml` | — | `opt_whole_frame_title` / `_desc`, EN + AR |

Missing any of Queue/QueuedWorker is a silent fail-open: the toggle appears to work in the UI and the
job renders rects. Land all four together.

## 3. Tests

One new JVM test file section in `app/src/test/java/com/haithamassoli/naqi/edl/EdlTest.kt`:

- `mergeRanges` — disjoint stay disjoint; overlapping merge; two spans `BRIDGE_MS - 1` apart merge,
  `BRIDGE_MS + 1` apart do not; unsorted input yields sorted output; empty and single-element pass through.
- `promoteFacesToFullFrame` — a track span becomes an interval; `fullFrameAt` is true inside it;
  `regionsAt` returns empty there (precedence still holds); with the flag off, `regionsAt` is unchanged.

Gate: `compileDebugKotlin` + `testDebugUnitTest` (per `naqi-build-gates` — do not gate on `lintDebug`).

Device QA, one clip each: `women-music-3min` (the workhorse — confirm the whole frame blurs and the
audio path is untouched) and a fast-cut clip to check `BRIDGE_MS` does not strobe.

## 4. What this costs the user — say it in the UI

Face tracks span as long as the face is visible. On ordinary footage that means **most of the
runtime is blurred**, and the output is a re-encoded file, so it is not reversible like HaramBlur's
CSS filter is. The switch description has to say so plainly; this is a mode, not a better default.

Keep the default `false`. `blurAmount`/`grayscale` already style it — HaramBlur's look is roughly
`blurAmount = 50, grayscale = true` (sigma 20 px at 1080p, `CensorEffect.kt:108`).

## 5. Not doing

- **No shader change.** If a future mode needs per-frame whole-frame decisions that the EDL cannot
  express, revisit §1 — until then the EDL is the cheaper seam.
- **No render-time cost change.** The blur passes already run over the full texture into scratch
  either way (`CensorEffect.kt:160-161`); only the composite branch differs.
- **No live/playback blur.** Different feature, different plan.
