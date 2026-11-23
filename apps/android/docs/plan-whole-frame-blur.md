# Plan — whole-frame blur instead of the face rect

**Goal:** an opt-in mode where a censored face blurs the **entire frame** for as long as it is on
screen, HaramBlur-style, instead of blurring only its padded rect.

Written 2026-07-30 against `main` @ `07f0057`.
**Status: implemented and device-verified on an S23, 2026-08-03.** §6 is what was actually built and
measured; read it before trusting §1–§5, which got the seam right and the cost claim wrong in both
directions. §7 is what HaramBlur's *shipping* build actually does, which is not what its public repo
does.

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

---

## 6. What was built and measured (2026-08-03, S23 `R3CW5070LGM`)

### 6.1 Shipped shape

Ten files, 145 lines. The seam is exactly §1's: `promoteFacesToFullFrame` in `edl/Edl.kt`, called once
per EDL build from `FilterWorker.censorSpans` — one function, shared by both pass-1 shapes, so the
segmented and single-pass routes cannot disagree. `CensorEffect`, `RenderPipeline`, the shader and the
checkpoint format are untouched, as promised.

Deviations from §2:

| § | Planned | Shipped | Why |
|---|---|---|---|
| 2.2 | wrap the interval list at both build sites | one `censorSpans(firings, durationMs, tracks)` helper called by both | Both sites already computed `intervalsFor(...) + overflowSpans(...)`; wrapping twice is two places for a future edit to change one of them. |
| 2.3 | `Prefs` unlisted | not persisted | `Prefs` stores only `removeMusic` + `censorWho`; `blurAmount`/`grayscale`/`solidColor` are not persisted either, and this is a style knob like them. |
| 2.3 | — | added to `jobKey` | A rect-blurred segment and a whole-frame one must never resume into each other — the same argument `solidColor` already carries (`FilterWorker.kt:148`). |
| 2.1 | `mergeRanges` alone | `+ MIN_FULL_MS = 500` floor | §6.3. The device found a strobe the plan did not anticipate. |
| — | — | `--ez whole_frame true` debug intent | The only way to A/B it from adb, which is how §6.2 was measured. |

### 6.2 Cost — three runs on `tv1.webm` (643 s, censor-only, `censorWho=everyone`, cooled to ≤32.5 °C between runs)

| run | mode | **render** | analyze | total | spans | coverage | output size |
|---|---|---:|---:|---:|---:|---:|---:|
| A | rect (today's behaviour) | **89 411 ms** | 114 648 ms | 204 752 ms | 74 | 64.8 % | 158.1 MB |
| B | whole-frame, no floor | **89 259 ms** | 121 110 ms | 210 775 ms | 29 | 90.8 % | 154.4 MB |
| C | whole-frame + floor (shipped) | **89 437 ms** | 150 291 ms | 240 643 ms | 24 | 90.8 % | 154.4 MB |

**Render moved 0.20 % across all three runs. That is the answer: whole-frame blur is free at render
time.** And the control is in the same table — analyze varied **31 %** across those same three runs
(`nv21=` went 64.8 → 68.5 → 85.0 s in code the flag cannot reach) as the phone degraded under
back-to-back load, exactly as `naqi-perf-v2-settled` warns. A stage that ignores a 31 % swing in CPU
throughput is not paced by CPU or GPU work; render is paced by the hardware decoder/encoder. So §5's
"no render-time cost change" is right, but not for the reason it gives.

Why it is free, mechanically — and this contradicts the one objection raised against §5:

- The two blur passes were **already whole-frame and geometry-blind**: `blurPass` blurs the entire
  input texture into a downscaled scratch (240×135 at 1080p/blurAmount 60, 1/64 of the pixels), with
  no knowledge of any rect (`CensorEffect.kt:183-184`). Whole-frame mode does not add a pass.
- The composite's whole-frame branch is the **cheap** one: `mask = float(uCensorAll)` is 1 immediately
  and the per-region loop exits at `i = 0` because `regions` is `emptyList()` under full-frame
  precedence — no `smoothstep`×4 per region (`CensorEffect.kt:362-379`).
- CPU per frame gets **cheaper**: `fullFrameAt` returns true and `regionsAt` is skipped entirely, so
  the per-frame scan over every face track, the `rectAt` binary search, the `ArrayList(2)`, the `NRect`
  and the `.map{}` all disappear on covered frames (`CensorEffect.kt:164-167`).
- The one true extra: a frame that had **no** face and now falls inside a bridged span goes from a
  single copy draw to blur+composite. Measured, that is inside the 0.20 % — because §6.3's coverage
  numbers show those frames are rare next to the 64.8 % the NSFW gate already covered.
- Encoding blurred pixels is **cheaper**, not dearer: at a fixed bitrate the output shrank 158.1 → 154.4 MB.

Analyze cost is **zero by construction**: the flag is read once and applied once, as a single merge
over the finished track list after the pass. No per-frame work exists to add.

### 6.3 The strobe the plan missed — and the floor that fixes it

§2.1's `BRIDGE_MS = 400` joins spans that are near each other. It cannot do anything about an
**isolated** short span, and run B produced six of them: **three of exactly 100 ms**, i.e. 2–3 frames
of the entire picture blinking out and back. A one-sample face track spans `first-50 .. last+50`
(`FaceTracker.SPAN_PAD_MS`), and 100 ms is what that is.

Pulling the frame at one of them (485.97 s) settled what they were: **a cardboard box on a floor.** Not
a face — an ML Kit false positive, the same failure `plan-censor-who §9.1` measured at 23 % of
classified crops (a protein tub, a taxi wheel, a dog at p(male) = 1.00). Invisible when it paints a
rect on a box for two frames; a full-screen flash when it does not.

`MIN_FULL_MS = 500`, applied **after** the merge so blips that bridge into a long enough span survive
together. Dropping a span costs **no coverage**: `regionsAt` returns rects wherever no full-frame span
is active, so the promotion falls back to that track's own blurred rect — the face stays censored,
only the flash goes. Confirmed in run C: **0 spans under 500 ms, shortest span 701 ms, shortest clear
window 601 ms, and coverage unchanged at 90.8 %.**

### 6.4 What the user actually gets

**90.8 % of this clip's runtime is covered whole-frame**, against 64.8 % that the NSFW gate covered
already. §4's warning is understated, not overstated: on face-heavy footage this is not "the face,
bigger" — it is a mostly-obscured video. The Options copy says so; keep it saying so.

### 6.5 Still open

- **Fast-cut QA.** §3 asks for a fast-cut clip against `BRIDGE_MS`; `tv1.webm` is a vlog. The 24 spans
  and 601 ms minimum clear window say nothing about a clip that cuts every 300 ms.
- **`MIN_FULL_MS = 500` is one measurement deep.** It was read off six spans on one clip. It is a
  constant in `Edl.kt` with a KDoc; move it if a real asset argues otherwise.
- **`Eta`** still estimates rect-mode cost. Given §6.2 that is correct to within 0.2 %, so this is a
  note, not a task.
- **The share sheet always runs rect mode.** It seeds from `Prefs.ops`, which persists only
  `removeMusic` and `censorWho` — so `wholeFrameBlur` arrives as its `FilterOps` default, exactly like
  `blurAmount`, `grayscale`, `solidColor` and `keepStems` already do (`ShareSheet.kt:96`). Consistent
  with every other style knob and therefore not fixed here, but it means "I turned it on in Options"
  does not survive a share. Persist all five together or none.
- **Segmented (≥30 min) sources were not exercised** in whole-frame mode. `censorSpans` runs once over
  the whole timeline for exactly this reason (a face crossing a seam must bridge), but it is untested.

---

## 7. What HaramBlur actually ships (v0.6.11, recovered from the CRX)

The public repo (`alganzory/HaramBlur`, last commit 2024-07-07, v0.2.6) is **four minor versions
behind** the Chrome Web Store build and contains **no region blur at all** — it only does whole-element
`filter: blur()`. Anything designed against the repo would be designing against the old product.

From the shipping build:

- The toggle is real and is labelled exactly **"Blur Mode: [Specific Blur] [Whole Blur]"**, two
  radio options with thumbnail previews — and **Specific Blur is the default** (`specificBlur: true`).
  Naqi now matches: rect is the default, whole-frame is opt-in. Their defaults moved *away* from
  whole-frame once a box-producing detector existed; `gray` flipped true → false in the same move.
- **Whole-frame is their safe-degradation path**, not their preferred one: it is forced when the TFJS
  backend is `cpu` (`forceWholeBlur: true, skipObjectDetection: true`) and for images under 70 px.
- **A frame-level NSFW hit does not mean a frame-level blur.** In Specific mode they intersect the
  nsfwjs verdict with the detector's boxes (`{label:"nsfw", body: S}`); only in Whole mode is it
  `body: null`. Naqi does the opposite — the NSFW gate is whole-frame and gender-blind by design
  (`plan-censor-who §7`) — and that difference is why 64.8 % of this clip was already whole-frame
  before the feature existed.
- **Their anti-strobe is asymmetric frame-count hysteresis: 1 positive frame to blur ON, 2 consecutive
  clears to blur OFF**, plus `transition: all 0.3s ease` on each rect. Naqi's `MIN_FULL_MS` is the
  same intent from the other end: they cannot fall back to a rect (in Whole mode there is none), so
  they must err toward staying blurred; Naqi can, so it drops the flash and keeps the rect, which
  covers the face *and* removes the strobe. Strictly better here, and only because the EDL is offline.
- Not copied, deliberately: `blurryStartMode` (blur everything until detection runs) has no meaning for
  an offline re-encode; `unblurImages`/`unblurVideos` (unblur on hover) needs a live DOM; their
  per-frame pixel-difference cache is a real idea and belongs in the perf plan, not here.
