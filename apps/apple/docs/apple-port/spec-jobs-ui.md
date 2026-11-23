# Naqi — Apple port spec: jobs / orchestration, options model, design language

Extracted from the shipped Android app at `/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter`.
Every number below is quoted from Kotlin with `file:line`. Paths are relative to
`app/src/main/java/com/haithamassoli/naqi/` unless prefixed with `docs/` or `app/src/main/res/`.

Scope: the job/orchestration layer, the options model, the design language, and the screen flow with real
copy. **Not** in scope here: ML models, the sampler, the GL shader, htdemucs internals, the muxer.

---

## 0. Vocabulary

| term | meaning |
|---|---|
| **op** | one of the two user-selectable operations: remove music, censor faces |
| **shape** | one of the five job shapes `doWork` dispatches to (`work/FilterWorker.kt:263-269`) |
| **branch** | the video branch (analyze→render) or the audio branch (separate); they may run concurrently |
| **segment** | a 5-minute slice of the source; the checkpoint unit (`work/Checkpoint.kt:18`) |
| **pass 1 / pass 2** | analyze (build EDL) / render (apply EDL). User-visible as "Pass 1 — analyzing" / "Pass 2 — rendering" |
| **job key** | SHA-256-derived directory name for one (source, options) pair (`work/JobStore.kt:45-50`) |

---

## 1. `FilterOps` — the complete options model

### 1.1 Fields

`model/FilterOps.kt:23-56`. Kotlin `data class`, `java.io.Serializable` (`:57`) so it survives rotation and
process death inside `ui/NaqiApp.kt`'s `rememberSaveable`.

| # | field | type | default | valid range / values | drives |
|---|---|---|---|---|---|
| 1 | `removeMusic` | `Bool` | `false` (`:24`) | — | audio branch on/off |
| 2 | `censorWho` | `String` | `NONE` (`:35`) | `"none"`, `"everyone"`, `"women"`, `"men"` (`:66-69`) | which faces get covered |
| 3 | `wholeFrameBlur` | `Bool` | `false` (`:45`) | — | promote every censored face span to a full-frame span at EDL build time |
| 4 | `strictness` | `Int` | `40` = `DEFAULT_STRICTNESS` (`:46`, `:80`) | `0…100` (slider `valueRange = 0f..100f`, `ui/screen/OptionsScreen.kt:392`) | NSFW gate only — **never** face blurring |
| 5 | `blurAmount` | `Int` | `60` (`:47`) | `0…100` (same slider) | blur strength on faces + censored scenes |
| 6 | `grayscale` | `Bool` | `false` (`:48`) | — | also drain colour from censored areas |
| 7 | `solidColor` | `Int` (ARGB) | `0` = `BLUR` (`:55`, `:105`) | `0` **means blur**; otherwise an opaque ARGB int | solid fill instead of blur |
| 8 | `keepStems` | `String` | `"vocals"` (`:56`) | `"vocals"`, `"vocals_other"` | which demucs stems survive; drums/bass never kept (`:8`) |

Derived, not stored:

| derived | definition | source |
|---|---|---|
| `censorFaces: Bool` | `censorWho != NONE` | `:60` |
| `any: Bool` | `removeMusic || censorFaces` | `:62` |

**Contract 1.1.1 — `FilterOps()` must keep `any == false`.** `censorWho` defaults to `NONE`, *not* to
`DEFAULT_WHO`. `FilterOps()` means "nothing picked yet". Three call sites depend on it: `work/Queue.kt:53`
(an item's default ops), `ui/NaqiApp.kt:37` (the pick screen's seed), and `EtaTest.kt:62` (a no-op job
estimates 0 ms). Entry points that *should* open with censoring on default it themselves
(`data/Prefs.kt:38`, `MainActivity.kt:173-177`). — `model/FilterOps.kt:30-34`

**Contract 1.1.2 — `DEFAULT_WHO = WOMEN`** (`:77`). This is the fresh-install answer only; the last real
pick is remembered in `Prefs.lastWho`.

**Contract 1.1.3 — `solidColor == 0` is the mode flag, not a colour.** Every offered swatch is opaque, so a
zero alpha cannot be a real choice; one field carries both the mode and the colour across all four
serializers. `blurAmount`/`grayscale` are dead while a solid colour is set (`ui/screen/OptionsScreen.kt:227`
hides both controls). — `model/FilterOps.kt:49-55`

**Contract 1.1.4 — `censorWho` is a String, not an enum.** All four serializers take it unchanged. The four
literals are a wire format and must never change (`:65`, `:14-16`). `WOMEN`/`MEN` need a face classifier
that has not passed its go/no-go bar; they parse and round-trip from day one but nothing in the UI's
default path offers them differently from `EVERYONE`.

**Removed field — do not reintroduce:** `blurUnknownFaces` was deleted with the gender vote. Readers of
`queue.json` and WorkManager `Data` silently ignore the key if present. — `:18-21`, `work/Queue.kt:177`,
`work/FilterWorker.kt:1297-1299`

### 1.2 The five solid-fill swatches

`model/FilterOps.kt:108-114`, **in swatch order** (the UI renders them left-to-right in this order,
`ui/screen/OptionsScreen.kt:340`):

| index | ARGB | hex | TalkBack label res | EN | AR |
|---|---|---|---|---|---|
| 0 | `0xFF9E9E9E` | `#9E9E9E` | `opt_solid_gray` | Gray | رمادي |
| 1 | `0xFF000000` | `#000000` | `opt_solid_black` | Black | أسود |
| 2 | `0xFFFFFFFF` | `#FFFFFF` | `opt_solid_white` | White | أبيض |
| 3 | `0xFF2C3E50` | `#2C3E50` | `opt_solid_navy` | Navy | كحلي |
| 4 | `0xFF1E3A2F` | `#1E3A2F` | `opt_solid_green` | Green | أخضر |

`DEFAULT_SOLID = SOLID_COLORS[1]` (black) — what tapping the "Solid" segment picks on a first visit only;
an already-chosen colour is kept. — `ui/screen/OptionsScreen.kt:84`, `:331-334`

### 1.3 Parsing `censorWho` from untrusted input

```
whoOrNull(raw: String?) -> String?          // model/FilterOps.kt:98-102
  who = raw?.trim()?.lowercase() ?: ""
  ""                            -> null      // "absent" — readers disagree on ""/nil, so both mean absent
  "none"|"everyone"|"women"|"men" -> who
  anything else                 -> "everyone"  // censoring is the safe direction

whoFromLegacy(censorWomen: Bool) -> String   // :83
  true -> "everyone"   // the old boolean censored EVERY detected face
  false -> "none"
```

**Contract 1.3.1 — unrecognised input resolves to `EVERYONE`, never to `NONE`.** A typo that silently
stopped censoring is the one failure the user would not see. — `:94-96`

### 1.4 The four serializers

`censorWho` exists as a `String` precisely so all four take it unchanged (`:26-28`).

| # | serializer | writer | reader | absent-key behaviour |
|---|---|---|---|---|
| 1 | `queue.json` | `work/Queue.kt:120-140` | `work/Queue.kt:167-179` | `optString` → `""` |
| 2 | WorkManager `Data` | `work/QueuedWorker.kt:52-63` (`FilterOps.pairs()`) | `work/QueuedWorker.kt:66-78` (`Data.filterOps()`) | `getString` → `nil` |
| 3 | `Prefs` (SharedPreferences) | `data/Prefs.kt:56-61` — **only 2 of 8 fields** | `data/Prefs.kt:33-40` | `getString` → `nil` |
| 4 | debug `Intent` extras | — | `MainActivity.kt:167-190` | `getStringExtra` → `nil` |

Apple equivalent: one `Codable` struct is enough for 1, 2 and 3 (`UserDefaults`/App Group plist), plus a
share-extension handoff for the queue.

#### 1.4.1 WorkManager `Data` keys (`work/FilterWorker.kt:1274-1326`)

| key literal | field | read default |
|---|---|---|
| `remove_music` | `removeMusic` | `false` |
| `censor_who` | `censorWho` | falls back to `censor_women` |
| `censor_women` | *legacy read-only* | `false` |
| `whole_frame` | `wholeFrameBlur` | `false` |
| `strictness` | `strictness` | `40` |
| `blur_amount` | `blurAmount` | `60` |
| `grayscale` | `grayscale` | `false` |
| `solid_color` | `solidColor` | `0` |
| `keep_stems` | `keepStems` | `"vocals"` |
| `input_uri` | source | — (absent ⇒ `Result.failure()`, `:185`) |
| `force_intervals` | debug censor spans `"startMs-endMs,…"` | `nil` |
| `segment_ms` | debug segment-length override | `0` |
| `queue_id` | links the run to its `Queue.Item` | `nil` |

Output / progress keys (same companion object):
`progress` (Int 0-100), `stage` (String), `eta_ms` (**Long** — reading it as Int silently yields 0 forever,
`ui/screen/JobsScreen.kt:94-96`), `output_name`, `output_uri`, `output_message` (a string-resource id, Int),
`resumable` (Bool). Unique work name `naqi_filter_job` (`:1323`).

**Contract 1.4.2 — the legacy `censor_women` key must keep being read.** Jobs enqueued before the rename sit
in WorkManager's DB carrying only the boolean; dropping the fallback re-defaults them to "do not censor" —
a full render that silently returns the input. — `:1282-1288`

**Contract 1.4.3 — only the new key is written.** Emitting both would leave two sources of truth, one of
which cannot say "women". — `work/QueuedWorker.kt:54-56`

### 1.5 `Prefs` — persisted keys

`data/Prefs.kt`. File name `naqi_share_prefs` (`:18`), `Context.MODE_PRIVATE`.

| key literal | type | written by | read default | line |
|---|---|---|---|---|
| `remove_music` | Bool | `save()` | **`true`** | `:20`, `:35` |
| `censor_who` | String | `save()`, `saveWho()` | *(see below)* | `:21`, `:36` |
| `censor_women` | Bool | **never** (read-only legacy) | `true` when present | `:24`, `:37` |

Read algorithm — `ops(context)` (`:33-40`):

```
removeMusic = bool("remove_music", default: true)          // NOT FilterOps' own default
censorWho   = whoOrNull(string("censor_who"))
              ?? (contains("censor_women") ? whoFromLegacy(bool("censor_women", true))
                                            : FilterOps.DEFAULT_WHO)   // = "women"
// every other field takes FilterOps' own default
```

`lastWho(context)` (`:47-49`): `whoOrNull(string("censor_who"))` filtered to `!= NONE`, else `DEFAULT_WHO`.
Never returns `NONE` — "off" is the toggle's state, not a choice of whom to cover.

`saveWho(context, who)` (`:52-54`): **no-op when `who == NONE`.**

**Contract 1.5.1 — both keys default to "on".** Both filters on is the reason someone installed Naqi, which
is why neither key falls back to `FilterOps`' own defaults. — `:26-31`

**Contract 1.5.2 — six scalars, no schema.** Deliberately `SharedPreferences` and not the PRD's "one JSON
file". Apple equivalent: `UserDefaults` in the App Group suite (the share extension must read it).
— `:11-14`

---

## 2. Job state machine

### 2.1 Two independent state machines, deliberately

| machine | states | authority | consumed by |
|---|---|---|---|
| **WorkManager `WorkInfo.State`** | `ENQUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `BLOCKED` | the scheduler | `JobsScreen`'s single-job card, `OptionsScreen`'s Start-disabled test |
| **`Queue.State`** | `PENDING_FILTER`, `FILTERING`, `DONE`, `FAILED` | `queue.json` | `QueueCard`'s per-item rows |

**Contract 2.1.1 — a queue-driven run NEVER returns failure.** WorkManager fails every request chained
behind a failed one, so one unfilterable item would kill the rest of the queue. `WorkInfo.State` is
therefore useless as an outcome for queued items — every one reads `SUCCEEDED`. The real outcome lives in
`queue.json`. — `work/Queue.kt:18-26`, `work/QueuedWorker.kt:36-43`

```
fail(message, resumable) ->                       // work/QueuedWorker.kt:36-43
   Queue.update(id) { state = FAILED, error = message }
   data = { output_message: message, resumable: resumable }
   return queueId != nil ? Result.success(data) : Result.failure(data)
```

`Queue.State.isTerminal = (DONE || FAILED)` (`work/Queue.kt:40`) — what "Clear finished" clears.

### 2.2 `Queue.Item` schema (`work/Queue.kt:48-56`, JSON at `:120-140`)

| json key | type | default on read | note |
|---|---|---|---|
| `sourceUri` | String | **required** — `nil` ⇒ the whole item is dropped (`:147-148`) | the `content://` shared in |
| `id` | String (UUID) | fresh UUID when blank (`:151`) | |
| `title` | String? | `nil` | display name |
| `state` | enum name | `FAILED` if the name is unknown (`:155`) | never crash the screen |
| `error` | Int? | `nil` unless the JSON value is an Int (`:157`) | a **string-resource id**, never a message — so it re-localizes if the app language changes after the failure (`:45-46`) |
| `outputUri` | String? | `nil` | |
| `ops` | object | `FilterOps()` when absent (`:158`) | field defaults per §1.4 |

Persistence: one JSON array in `filesDir/queue.json`, whole-file rewrite on every change, `@Synchronized`,
temp+rename commit (`:107-118`). A truncated/unparseable file starts the queue empty rather than bricking
the screen (`:71-74`).

Queue mutations: `add`, `update(id, transform)` (no-op if the item is gone), `remove`, `clearTerminal`,
`pending` (`:79-105`).

### 2.3 Enqueue / retry / cancel (`work/JobController.kt`)

| operation | policy | lines |
|---|---|---|
| picker path `start()` | `ExistingWorkPolicy.KEEP` | `:28`, `:43-50` |
| queue path `submit()` | `ExistingWorkPolicy.APPEND_OR_REPLACE` | `:114-117` |
| `retry(item)` | resets to `PENDING_FILTER`, clears `error`, re-`submit`s the **same (uri, ops)** | `:84-88` |
| `cancelItem(item)` | `cancelAllWorkByTag("naqi_item_<id>")`, `Queue.remove`, then **re-append every still-pending survivor** | `:96-105` |

**Contract 2.3.1 — KEEP, not REPLACE.** REPLACE meant one stray tap on Start cancelled a job that could be
four hours in. KEEP makes that tap a no-op. The UI also disables Start while a job runs; KEEP is the half
that cannot be raced. — `:43-47`

**Contract 2.3.2 — cancel-repair is mandatory.** WorkManager cascades a cancellation to everything chained
*behind* the cancelled request, so cancelling item 2 of 5 silently kills 3, 4 and 5. They are re-submitted.
— `:90-105`

**Contract 2.3.3 — retry is not a special code path.** It re-submits the same (uri, ops), which lands on the
same job key, finds whatever checkpoints the last attempt left, and resumes. — `:77-83`

### 2.4 The five job shapes

Dispatch at `work/FilterWorker.kt:263-269`, in this exact order:

```
audioOnly           -> runAudioOnly      // removeMusic && source has no video track (:208)
segmented           -> runSegmented      // censorFaces && plan.isNotEmpty()          (:212)
removeMusic && censorFaces -> runCombined
removeMusic         -> runMusicOnly
else                -> runCensorOnly
```

Guard: `!removeMusic && !censorFaces` ⇒ `Result.failure()` immediately (`:184`). Missing `input_uri` ⇒
`Result.failure()` (`:185`).

`audioOnly` is **detected, not flagged** — the source itself is the only trustworthy statement about which
tracks it has (`:205-208`, `hasVideoTrack` at `:306-316`; undecidable ⇒ assume video and let Preflight
produce the message).

### 2.5 Stage ordering per shape

| shape | stage sequence | `JobStats.stage()` names | ends with |
|---|---|---|---|
| censor-only | analyze → render → publish | `analyze`, `render`, `publish` | `Publish.video` from a temp |
| music-only | separate → mux | `separate`, `mux` | `Publish.muxedVideo` (in-place) |
| combined | *(analyze → render)* ‖ separate → mux | `analyze`, `render`, `separate`, `mux` | `Publish.muxedVideo` |
| segmented | [transcode] → *(analyzeSegments → renderSegments)* ‖ [separate] → concat | `transcode`, `analyze`, `render`, `separate`, `concat` | `Publish.muxedVideo` |
| audio-only | separate → publish | `separate`, `publish` | `Publish.audio` into `Music/Naqi` |

`‖` = concurrent (see §2.6). The optional `transcode` stage only runs when
`audioPlan == ConcatAudio.TRANSCODE && audio.m4a` is absent (`:393-405`); it is written to `audio.m4a.part`
and renamed — an atomic write, not a checkpoint.

`ConcatAudio` (segmented, censor-only) is a 3-state decision (`:222`): `COPY` (source audio is
muxer-copyable), `TRANSCODE` (AC-3 / E-AC-3 / DTS / Opus / Vorbis → AAC once up front), `NONE` (no audio
track at all). **`NONE` exists because a Boolean `canCopyAudio` hid a bug**: a long *silent* source threw
`requireTrackIndex("audio/")` after the whole pass and reported "Filtering failed"
(`docs/long-film-followups.md` item 1).

Stage labels shown to the user (`res/values/strings.xml`): `stage_preparing`, `stage_analyzing`,
`stage_rendering`, `stage_separating`, `stage_muxing`. `getForegroundInfo()` opens on `stage_analyzing` at 0
(`:1264-1265`).

### 2.6 `branches()` — the concurrency contract

`work/FilterWorker.kt:845-872`.

```
branches(audio: ((demoteWhile: () -> Bool) -> Void)?, video: () -> Void)
  stageLabel = stage_analyzing
  if audio == nil            { video(); return }                      // censor-only / segmented no-music
  if !concurrentBranches()   { video()
                               stageLabel = stage_separating
                               stats.stage("separate")
                               audio { false }                         // sequential: nothing to demote from
                               return }
  coroutineScope {
    separating = async { audio { !videoDone } }
    try   { video() }
    finally { videoDone = true }          // MUST be finally — releases an audio branch parked in a
                                          // thermal sleep even when the video branch throws
    stageLabel = stage_separating
    stats.stage("separate")
    separating.await()
  }
```

**Contract 2.6.1 — failure semantics come from the structure.** Video throws ⇒ the scope cancels the audio
child and rethrows the *video* exception. Audio throws ⇒ the scope is cancelled, the video body's next
suspension point sees a `CancellationException`, and the scope rethrows the **child's original cause**.
Either way `Preflight.messageFor` sees the real failure. — `:832-841`

**Contract 2.6.2 — device gate.** `concurrentBranches()` (`:296-303`):

| test | value | source |
|---|---|---|
| `ActivityManager != nil` | — | `:299` |
| `!am.isLowRamDevice` | — | `:299` |
| `MemoryInfo.totalMem >= CONCURRENT_MIN_TOTAL_MEM` | **`6_656 MiB` = 6.5 GiB = 6 979 321 856 bytes** | `:1369` |

`totalMem`, not `availMem`: available memory swings by hundreds of MB minute to minute and this decision has
to hold for three hours (`:286-288`).

**Measured workaround — do not re-derive the threshold.** It was 7 GiB, written against a carveout figure
quoted in decimal GB, so the bar sat *above* the 8 GB class and the concurrent schedule never fired.
Measured `totalMem` on the S23 it was designed for is **7072 MiB**; the 6 GB class reports ~5.2-5.3 GiB.
— `:1360-1368`

**Measured numbers behind concurrency** (physical S23, 2026-08-03, 643 s source, both schedules from a
rebooted and cooled device): sequential **616.1 s** vs concurrent **549.7 s** = **1.12×**; peak RSS is the
same either way (**1294 MB vs 1287 MB**). Concurrent peak ≈ **1.82 GB** (1.29 separate + 0.53 segmented
censor) — fine on 8 GB, **unproven on the 6 GB minimum spec**. — `:274-294`

The real reason for the win is *not* "different silicon": render is 4 % of the wall. It is that `separate`
holds only 6 of 8 cores for 55-65 % of the job and **cannot use more** — 8 intra-op threads measured
*slower* than 6 (2244 vs 2136 ms/chunk). — `:824-830`

**Beware when comparing SOAK lines:** under the concurrent schedule `stats.stage("separate")` only starts
after `video()` returns, so that line is the *tail alone* and the overlapped audio work is billed into
analyze/render. **Total wall is the only valid comparison.** — `:280-283`

### 2.7 Progress weighting

Two `@Volatile` shares are summed; neither branch may post an absolute overall percent or one would stomp
the other and the bar would jump backwards (`:890-900`).

```
videoPct, audioPct : Int          // :901, :903
overall = videoPct + audioPct

reportVideo(stage, pct)      { stageLabel = stage; videoPct = pct; report(stage, pct + audioPct) }        // :911-915 (awaited)
reportVideoAsync(stage, pct) { stageLabel = stage; videoPct = pct; reportAsync(stage, pct + audioPct) }   // :917-921
reportAudio(sub, span)       { audioPct = sub * span / 100
                               reportAsync(stageLabel ?? stage_separating, videoPct + audioPct) }         // :924-927
reportBand(stage, sub, base, span) = reportAsync(stage, clamp(base + sub*span/100, 0, 100))               // :887-888
```

`reportAsync` = `stats.tick()` + `setProgressAsync{progress, stage, eta_ms}` + `setForegroundAsync`
(`:880-884`). Every `onProgress` callback in the file is non-suspend (they run on the transformer's Looper
or `Dispatchers.Default`) so they **must** use the async variants; `report` is the awaited twin (`:1258-1262`).

#### Bands per shape

| shape | analyze | render | separate (audio share) | mux / concat | publish |
|---|---|---|---|---|---|
| **censor-only** | `0…50` (`:1069`) | `50…100` (`:1134`) | — | — | none |
| **music-only** | — | — | `1…93` (`reportBand(p,1,92)`, `:747`) | `93…99` (`reportBand(p,93,6)`, `:757`) | none |
| **combined** | `0…25` (`:793`) | `25…50` (`:798`) | share `0…43` (`reportAudio(p,43)`, `:787`) | `93…99` (`:806`) | none |
| **segmented, music on** | `0…25` | `25…50` | share `0…40` (`reportAudio(p,40)`, `:414`) | `90…99` (`reportBand(p,90,9)`, `:448`) | none |
| **segmented, censor only** | `0…40` | `40…90` | — | `90…99` | none |
| **audio-only** | — | — | `1…99` (`reportBand(p,1,98)`, `:339`) | — | none |

`renderBase = removeMusic ? 25 : 40`, `renderSpan = removeMusic ? 25 : 50` (`:386-387`).
Note the arithmetic closes: combined video tops at 50 + audio share 43 = 93, then mux 93→99. Segmented
with music: 50 + 40 = 90, then concat 90→99. Segmented censor-only: video alone reaches 90.

**Contract 2.7.1 — the sequential schedule produces identical numbers.** Under sequential, the audio branch
starts with `videoPct` already at its ceiling, so `videoPct + audioPct` equals the old absolute posts.
Shapes with only one branch leave the other share at 0. — `:896-899`

#### Sub-progress formulas

Per-segment analyze (`:631-633`):
```
within = clamp((ptsMs - seg.startMs) / max(1, seg.endMs - seg.startMs), 0, 1)
pct    = clamp(base + Int((seg.index + within) * span / plan.count), base, base + span)
```
Resumed-from-checkpoint segment (`:592`): `pct = base + (seg.index + 1) * span / plan.count`.

Per-segment render (`:697`): `pct = base + (seg.index*100 + p) * span / (plan.count*100)`.
Already-rendered segment (`:687`): `pct = base + (seg.index + 1) * span / plan.count`.

Unsegmented analyze (`:1099-1100`):
`pct = clamp(base + Int(Float(ptsMs)/max(1,durationMs) * span), base, base+span)`.

Separator internals (`audio/AudioPipeline.kt:410`, `:468`): chunk loop posts `2 + 88*done/total` (⇒ 2…90),
then the single AAC encode carries 90…100. A **resumed** run seeds the bar at
`clamp(2 + 88*written/estFrames, 2, 90)` before the skip phase, because the skip re-decode reports nothing
and from 2 % it would read as a hang (`:377-382`).

**Progress is only posted on a real change.** `lastPct` is hoisted *outside* the segment loop: the sampler
calls back ~93 000 times over a film for ~40 distinct values, and each post is an awaited Room write plus a
`setForeground` binder IPC (`:581-584`).

### 2.8 ETA

Two ETAs coexist deliberately — one before there is any evidence, one as soon as there is (`work/Eta.kt:10-19`).

**Up-front floor** — `Eta.estimateMs(durationMs, ops)` (`work/Eta.kt:84-93`):

| shape | factor | provenance |
|---|---|---|
| censor only | **0.28** | re-measured 2026-07-29, S23, `wm3.mp4` 192.9 s: analyze 36.9 + vote 0.001 + render 13.5 + publish 0.4 = 51.1 s ⇒ 0.265, rounded **up** to keep it a floor (`:45-59`) |
| music only | **0.68** | 5-min clip → 3.4 min after the M3 re-export (`:62`) |
| combined | **1.0** | the other two sum to 0.96; rounded up because combined already sits on the 6 h cap (`:65-75`) |
| neither | returns `0` | `:90` |

`durationMs <= 0` ⇒ `0` (`:85`). Result is `Int64(durationMs * factor)`.

**Known error:** the factors are asymptotes. Fixed cost (loading an 88 MB htdemucs graph, standing up ORT
sessions) is not modelled, so a clip under ~2 min is quoted low — a 1-minute video estimated at 1 minute
really takes two. Deliberately uncorrected. Measured: 81.9 s source → separate 93.6 s = 1.14×; 634 s →
390 s = 0.62×. — `:30-43`

**Live ETA** — `JobStats.etaMs(pct)` (`work/JobStats.kt:54-55`):
```
pct < MIN_PCT_FOR_ETA (=3)  -> 0        // :90
else                        -> elapsedMs * (100 - pct) / pct
```
Straight-line extrapolation over *overall* percent. **Known ceiling, measured:** analyze+vote spend 25
progress points on 73 min while render spends 25 points on ~10 min, so the ETA over-promises the moment
render ends and htdemucs starts (`docs/long-film-plan.md` Phase 0). Left as-is rather than re-guessed.

`0` means "too early to say" and every surface hides the line entirely rather than showing a number
(`ui/screen/JobsScreen.kt:226-236`, `ui/screen/OptionsScreen.kt:173`).

### 2.9 The 30-minute threshold — one notion of "long"

`Eta.CONFIRM_THRESHOLD_MS = 30L * 60 * 1000` = **1 800 000 ms** (`work/Eta.kt:27`). Five consumers:

| # | consumer | test | effect |
|---|---|---|---|
| 1 | `Checkpoint.plan` | `durationMs < CONFIRM_THRESHOLD_MS` ⇒ `[]` | no segmenting, no per-segment resume (`work/Checkpoint.kt:68`) |
| 2 | `FilterWorker.resumableAudio` | `durationMs >= CONFIRM_THRESHOLD_MS \|\| forcedSegments` | the separator gets `jobDir` and becomes resumable (`:228`) |
| 3 | `runMusicOnly.resumable` | `durationMs >= CONFIRM_THRESHOLD_MS` | same, for the music-only shape (`:741`) |
| 4 | `OptionsScreen.onStart` | `etaMs > CONFIRM_THRESHOLD_MS` | opens the confirm dialog **before** the notification-permission dance (`:159-161`) |
| 5 | `ShareSheet` | `etaMs > CONFIRM_THRESHOLD_MS` | shows `dlg_long_job_body` inline, no dialog (`:162-170`) |

Note 1-3 test the **source duration**; 4-5 test the **estimated wall clock**. That is deliberate and must be
preserved.

**Contract 2.9.1 — it is a warning, never a cap.** Confirming lands in exactly the same place a short job's
Start does. — `ui/screen/OptionsScreen.kt:156-158`

**Known hole (recorded, not fixed):** if `FrameSampler.probe` cannot report a duration, `Checkpoint.plan`
returns empty *and* `etaMs == 0`, so the film runs unsegmented **and** the warning is suppressed. The one
path that still runs a film without resume is the one that cannot warn. — `docs/long-film-followups.md` item 1

### 2.10 Cancellation vs. system stop

`work/FilterWorker.kt:535-543`:

```
userCancelled() = (SDK >= 31) && stopReason == STOP_REASON_CANCELLED_BY_APP
```

**Contract 2.10.1 — a stop is not automatically a cancel.** The 6 h foreground-service cap, an lmkd kill and
a reboot all arrive as `CancellationException` too, and those are exactly the cases resume exists to
survive. Pre-31 has no stop reason, so it answers `false` and **keeps** the work — an orphan the 7-day sweep
collects is a far cheaper mistake than losing three hours. — `:452-460`, `:529-534`

| shape | on user cancel | on system stop | on throw |
|---|---|---|---|
| censor-only | delete work dir (`finally`, `:730`) | same | same, `fail(messageFor(t))` |
| combined | delete work dir (`finally`, `:818`) | same | same |
| music-only, `resumable == false` | delete | delete | delete + fail |
| music-only, `resumable == true` | delete (`:764`) | **keep** `audio.pcm`/`audio.json` | **keep** (`:768`) |
| segmented | delete (`:459`) | **keep** (`:456-459`) | **keep**, `fail(msg, resumable = true)` (`:464`) |
| audio-only | delete (`:349`) | delete | delete |

`audioTemp` is deliberately **not** deleted in `runSegmented`'s `finally`: on success and user cancel
`JobStore.delete` takes the whole directory anyway; what is left is a resumable failure, which is exactly
when both its writers want it kept — the transcode to skip redoing ~10 min, and `removeMusic` to skip
re-encoding 1.6 GB of PCM (`:468-471`).

`Infer.close()` and `faceTracker.closeDetector()` always run in `finally` (`:467`, `:728-729`, `:816-817`).

### 2.11 Foreground execution (Android-bound — needs an Apple answer)

| Android mechanism | detail | Apple equivalent needed |
|---|---|---|
| `WorkManager` + `CoroutineWorker` | survives process death, FIFO chain, unique work `naqi_filter_job` | no OS equivalent; the app itself owns a serial queue + the checkpoint layer |
| Foreground service, type `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (API 35+), `DATA_SYNC` (API 34), untyped below | `work/JobNotifications.kt:88-94` | iOS: run in-foreground with a keep-awake toggle; macOS: unconstrained |
| **6 h / 24 h cumulative per-app cap** on `MEDIA_PROCESSING` | measured: combined on a 155-min film projects to ~3.1 h on S23, so ~half the budget; a second film the same day still hits it (`docs/long-film-plan.md` wall 1) | iOS suspension/kill handled entirely by checkpoint-resume |
| Ongoing notification with a Cancel action + determinate 0-100 bar | `work/JobNotifications.kt:55-95`; text = `job_notif_stage_eta` when `etaMs > 0`, else the bare stage | Live Activity (iOS) / progress window (macOS) |
| Done notification, id 1002, with Open / Share / Delete-original actions | `:109-154`; **only when the output uri is `content://`** | user notification with the same three actions |
| `POST_NOTIFICATIONS` runtime permission (API 33+) | asked on the first primary tap, and the job **starts either way** (`ui/screen/OptionsScreen.kt:144-153`, `ui/screen/ShareSheet.kt:105-113`) | `UNUserNotificationCenter` authorization, same "start either way" rule |

Channel `filter_jobs`, `IMPORTANCE_LOW`, ongoing notif id **1001**, done notif id **1002**
(`work/JobNotifications.kt:22-26`).

---

## 3. Checkpoint schema

`work/Checkpoint.kt`. All of this is Phase 2 of `docs/long-film-plan.md`.

### 3.1 The unit and the atomicity story

**Contract 3.1.1 — the checkpoint unit is one COMPLETED segment.** Mid-segment state — the gate firings so
far, the live `FaceTracker` map — is deliberately never persisted. An interruption costs the segment in
flight and nothing more. — `:16-19`

**Contract 3.1.2 — every file is written to `<name>.tmp` and renamed.** A file existing under its final name
*means* it is complete. That is the whole atomicity story: no manifest to keep in sync with the files it
describes, and no way for a checkpoint to reference a half-written segment. `renameTo` won't overwrite on
some filesystems, so a rewrite deletes the target first and re-checks (`:163-171`).

### 3.2 Working directory

`work/JobStore.kt`.

| aspect | value | line |
|---|---|---|
| root | `noBackupFilesDir/naqi-work/<key>/` | `:34`, `:53-54` |
| why `noBackupFilesDir` | several GB of job-local scratch keyed to a source Uri that need not exist on a restoring device has no business in a cloud backup; the platform excludes it by construction, which beats an `<exclude>` in two backup XMLs a later edit can forget | `:20-23` |
| why not `cacheDir` | the system may reclaim it under storage pressure — during a multi-hour job that is itself filling the disk | `:11-14` |
| stale sweep | age-based, entries whose **newest FILE** is older than `STALE_MS = 7 days` (`604 800 000 ms`) | `:37`, `:65-77` |
| when the sweep runs | at the head of every `doWork` — the only entry point that always runs (WorkManager can restart a persisted job after a reboot with the app never opened) | `work/FilterWorker.kt:188-192` |

**Contract 3.2.1 — the sweep is age-based on purpose.** "Delete every temp at startup" is the obvious
reading and it is the one change that could silently destroy hours of work. The sweep descends only into
`naqi-work`, and only into entries untouched for 7 days. A directory's own mtime does not track writes to
files inside it on every filesystem, so a job is aged by its newest *file*. — `:24-30`, `:70-72`

### 3.3 The job key

`JobStore.keyOf(vararg parts)` (`:45-50`):

```
digest = SHA256()
for p in parts: digest.update(utf8("\(String(describing: p).count):\(p)|"))   // length-delimited
key = digest.finalize().prefix(8).map { hex2(it) }.joined()                    // 16 lowercase hex chars
```

Length-delimited so `("a","bc")` and `("ab","c")` cannot hash alike (`:47`). A real digest and not
`hashCode`: a collision here would resume the wrong job's segments into a user's video (`:41-44`).

Hashed parts, **in this exact order** (`work/FilterWorker.kt:131-172`):

| # | part | note |
|---|---|---|
| 1 | `input_uri` (String?) | |
| 2 | `remove_music` (Bool) | |
| 3 | `whoOrNull(censor_who) ?? whoFromLegacy(censor_women)` | hashed as the **String** since Phase C |
| 4 | `strictness` (Int) | |
| 5 | `blur_amount` (Int) | |
| 6 | `grayscale` (Bool) | |
| 7 | `solid_color` (Int) | |
| 8 | `whole_frame` (Bool) | |
| 9 | `keep_stems` (String?) | **raw `getString`**, so an absent key hashes as `"null"` |
| 10 | `force_intervals` (String?) | debug hook, but it does change the output |
| 11 | `"plan4"` | a **plan generation**, not an input |

**Contract 3.3.1 — these reads stay inline, raw, and must not be folded into the parsed properties.** This
hash is a persisted-state contract: it names a directory that may hold hours of rendered segments.
`getString(keep_stems)` hashes an absent key as `"null"` where the parsed property substitutes `"vocals"`,
so folding them in would change the key for that one input and orphan exactly the work the key exists to
find again. — `:121-128`

**Contract 3.3.2 — bump the plan generation whenever the meaning of an `an-NNN.json` or `seg-NNN.mp4`
changes.** The recorded history (`:157-171`): `plan2 → plan3` when the gender vote was dropped (an old
`an-NNN.json` holds only tracks that voted FEMALE, a new one holds every face — mixing them would leave half
the film censored under the old semantics); `plan3 → plan4` when the two-tier music guard changed *which*
chunks a resumed `audio.json` says were separated. Bumping orphans stale directories and the 7-day sweep
collects them. Explicitly *not* bumped for perf-plan-v4 A1, which left the tensor bit-identical.

### 3.4 The segment plan

`Checkpoint.plan(durationMs, forcedSegmentMs = 0, cutAtMs = { $0 })` (`:65-80`):

```
segmentMs = forcedSegmentMs > 0 ? forcedSegmentMs : SEGMENT_MS       // SEGMENT_MS = 5*60*1000 = 300_000 (:37)
if durationMs <= 0                                          -> []
if forcedSegmentMs <= 0 && durationMs < 1_800_000           -> []     // the 30-min gate
if durationMs <= segmentMs                                  -> []
count = ceil(durationMs / segmentMs)                                  // ((d + s - 1) / s)
cuts  = ([0] + (1..<count).map { clamp(cutAtMs(i*segmentMs), 0, durationMs) } + [durationMs])
          .distinct().sorted()
return cuts.adjacentPairs().enumerated().map { RenderSegment(index: $0, startMs: from, endMs: to) }
```

`RenderSegment(index: Int, startMs: Int64, endMs: Int64)` — `render/RenderPipeline.kt:54`.

**Empty means "run the whole timeline in one pass"** — the already-device-verified unsegmented route, which
stays byte-for-byte unchanged for ordinary clips (`:40-42`).

**Why 5 minutes:** the per-export fixed cost (Transformer + encoder init) measured **~0.5-0.7 s** on the S23
spike, so at 5 min a 155-min film pays ~19 s of overhead across 31 segments — irrelevant next to the ~83 min
the passes take. What 5 min really buys is the interruption cost: analyze runs at ~**0.45× realtime**, so one
lost segment is ~2.3 min of work. Deliberately one fixed length for every device. — `:27-36`

**`distinct()` is load-bearing:** it collapses two cuts a sparse-keyframe source snapped onto the same
sample, so such a source gets fewer, longer segments rather than an empty one — which would make the clipper
throw on an inverted clip (`:72-73`).

#### 3.4.1 Sync-sample snapping — a measured workaround, do not drop

`FilterWorker.planFor` (`:492-527`) supplies `cutAtMs`. It opens **one** extractor for the whole plan,
seeks `SEEK_TO_NEXT_SYNC`, reads the sample's own time, and truncates µs → ms:

```
seekTo(ms * 1000, .nextSync); t = sampleTime
t < 0        -> durationMs   // no sync sample at or after ms; collapse this and every later cut
t < ms*1000  -> ms           // source cannot be seeked by time (fragmented mp4 with no sidx)
else         -> t / 1000
```

**The finding:** media3 ends a clipped read at the first sample **in decode order** whose pts reaches the
clip end (1.10.1 `ClippingMediaPeriod.java:430`), so on any B-frame stream the frames that DISPLAY before the
boundary but DECODE after that sample are never read — 1-3 per seam, 49 frames over 31 seams on a 2.6 h film.
Measured against real packet tables: un-snapped loses **1** frame on `test-video.mp4` and **6** on
`women-music-3min-video.mp4`; snapping to the next sync sample loses **0** on both. The alternative proposal
(snap to the middle of a frame interval) was simulated and does **not** work (1 and 4) — and cannot even be
aimed, because `MediaFormat` reports 24 for 24000/1001 content on this device. — `work/Checkpoint.kt:47-63`

**Boundaries stay whole milliseconds** even though a µs API exists: the shader reconstructs absolute time as
`presentationTimeUs / 1000 + startMs`, which only equals `floor(absoluteUs / 1000)` when the offset is a whole
ms. The price is one extra discarded GOP per segment (~5 s on a film, <1 % of decode work). — `:481-490`

**On a snapping failure the plan is abandoned entirely, not recomputed unsnapped** — a first run that snapped
and a resume that fell back would share a work directory (the key does not encode the plan), folding analysis
into the wrong window and placing rendered frames at the wrong absolute time, silently. Giving up
segmentation is correct output with no resume. — `:516-523`

### 3.5 Files inside the work directory

| file | written by | content | line |
|---|---|---|---|
| `an-%03d.json` | `writeAnalysis` | one segment's analysis | `:87`, `:99-104` |
| `seg-%03d.mp4` | `renderSegments` (`.part` + rename) | one segment's **video-only** render | `:108`, `work/FilterWorker.kt:691-701` |
| `audio.pcm` | separator, append-only | int16 LE **stereo 44.1 kHz** raw PCM | `audio/AudioPipeline.kt:362`, `:396` |
| `audio.json` | `writeAudio` | separator progress + whole-track scalars | `:130`, `:146-157` |
| `audio.m4a` | separator / transcode (`.part` + rename) | the finished AAC track | `work/FilterWorker.kt:383`, `:401-404` |
| `render.mp4` | unsegmented render | pass-2 temp (censor-only, combined) | `work/FilterWorker.kt:710`, `:779` |

#### `an-NNN.json`

```json
{ "firingsMs": [<Int64>, …], "edl": { "censorIntervalsMs": [[first,last], …],
                                      "faceTracks": [{ "startMs":…, "endMs":…,
                                                       "keyframes": [[tMs, l, t, r, b], …] }, …] } }
```
`Checkpoint.kt:99-104`; EDL serialization at `edl/Edl.kt:41-57`. Rect components are normalized `Float`s
written as JSON doubles. **Times are absolute source ms, never segment-relative** (`:84`). The per-segment
checkpoint stores `Edl(emptyList(), segTracks)` — bare tracks, **no intervals** (`work/FilterWorker.kt:643`).

`readAnalysis` returns `nil` on any throw — "not analyzed yet, or its write never completed" (`:89-97`).

#### `seg-NNN.mp4`

`isRendered(dir, index) = segmentFile.length() > 0` (`:111`). No sidecar state.

#### `audio.json`

```json
{ "framesEmitted": <Int64>, "frames": <Int64>, "mean": <Double>, "std": <Double>, "firstPtsUs": <Int64> }
```
`Checkpoint.kt:146-157`. `mean`/`std` are `Float`s written via `Double` — `getDouble().toFloat()`
round-trips a `Float` exactly, so the scalars survive verbatim (`:136`).

**Contract 3.5.1 — `mean`/`std` must NOT be recomputed on resume.** They normalize on feed and are inverted
on emit, so re-deriving them from a re-decode that differs by one frame would step the level in the middle
of the film. — `:115-119`, `audio/AudioPipeline.kt:342-343`

**Contract 3.5.2 — `frames` is the COMPLETION MARKER, not a declared total.** `0` while the separator still
owes work; the measured frame count once a full run finished. Reading 0 means "re-run" — re-running a
finished separator costs one audio decode with every chunk skipped, while skipping an unfinished one
truncates the user's film. The estimate is never used for that decision. — `:120-124`,
`audio/AudioPipeline.kt:345-350`

### 3.6 Exactly how resume reconstructs state

Resume is not a code path. `JobController.retry` / the Resume button re-enqueue the **same (source, options)**,
which lands on the same job key, which finds whatever the last attempt left.

**Video side** (`work/FilterWorker.kt`):

1. `analyzeSegments` iterates the plan. For each segment, `Checkpoint.readAnalysis(dir, index)`; non-nil ⇒
   accumulate `firingsMs` and `edl.faceTracks`, post progress, `continue` (`:586-594`). Nil ⇒ build a
   **fresh `FaceTracker`** for that segment, sample it, then `writeAnalysis` **only once both halves exist**
   (`:602`, `:643`).
2. After the loop, the hysteresis runs **ONCE over the whole accumulated firing list**, never per segment
   (`:657-660`). Building intervals per segment would clip up to 1.5 s of censoring at every seam
   (`:364-367`). The 7.4 overflow promotion and the `wholeFrameBlur` promotion also run once, here, for the
   same reason (`:1157-1168`).
3. `renderSegments` skips any segment where `isRendered` is true (`:685-689`); otherwise it exports to
   `seg-NNN.mp4.part` and renames (`:691-701`).
4. Bitrate is resolved **once** for the whole job and hoisted out of the loop — `Remux.concat` can only write
   one track format, so every segment must be encoded identically (`:678-683`).

**Audio side** (`audio/AudioPipeline.kt:352-461`):

```
saved   = Checkpoint.readAudio(jobDir)
stats   = saved?.stats ?? AudioDecoder.stats(...)   // and immediately writeAudio(0, stats)
written = min(saved?.framesEmitted ?? 0, pcm.byteLength / 4)          // :369
if pcm.byteLength != written*4 { truncate(pcm, to: written*4) }        // :370-372
estFrames = stats.frames > 0 ? stats.frames : AudioDecoder.estimateFrames(...)   // cosmetic only
if stats.frames == 0 || written < stats.frames {
    run the separator with resumeFrames = written                      // :422
    …per chunk: after the flush, if !isCancelled -> writeAudio(written, stats)   // :416
    at the end:  writeAudio(written, Stats(total = separator.framesFed, mean, std, firstPtsUs))  // :457
}
precondition(pcm.byteLength == total * 4)                              // :461
encodePcm(pcm -> AAC) once, over the whole scratch                     // :463-468
```

Key facts:

- **4 bytes per frame** (int16 × 2 channels), so `framesEmitted * 4` is an **exact byte offset** and the
  resume seam is a plain file append. This works only because the scratch is 44.1 kHz — the separator's own
  rate, unconverted end to end. — `:335-337`
- A kill loses **at most one htdemucs chunk**: on resume the separator re-runs exactly one chunk before
  `written` to rebuild its overlap-add ring, and everything from there is bit-identical to an uninterrupted
  run. — `:332-334`
- `min(checkpoint, fileLength/4)` then truncate: the checkpoint is authoritative but must never claim more
  than the file durably holds (a power loss can leave a size that outran its data). One `min` is correct in
  both directions. — `:367-369`
- The per-chunk checkpoint write is **skipped while stopping**, so a cancel racing `JobStore.delete` cannot
  re-create the file it just removed. — `:412-416`
- int16 costs **635 MB per hour** of source; measured **87.3 dB** round-trip SNR, ~50 dB below what AAC-LC at
  192 kbps discards. f32 would double the disk for nothing. — `:339-340`

**Resume UI gate:** `fail(msg, resumable = true)` sets `KEY_RESUMABLE`; `JobsScreen` shows the hint +
Resume button only when `resumable && onResume != nil` (`ui/screen/JobsScreen.kt:91`, `:152-165`).
`onResume` is nil when the app's saved state has lost the picked video even though the segments survive
(`ui/NaqiApp.kt:85`).

---

## 4. Preflight

`work/Preflight.kt`. Runs **before any foreground work** so a doomed job never promotes to a foreground
service (`:20-21`), and for **every** shape, not just music removal.

### 4.1 Free-space formula

```
SLACK_BYTES   = 2 * 1024 * 1024 * 1024        = 2_147_483_648        // :47  (2 GiB)
requiredBytes = (tempCopies + 1) * sourceBytes + extraScratchBytes + SLACK_BYTES   // :53-54
                //  +1 = the published copy
pass  <=>  filesDir.usableSpace >= requiredBytes                     // :113
else  ->  LOW_SPACE
```

`tempCopies` as passed by the worker (`work/FilterWorker.kt:241-245`):

| shape | tempCopies | required | why |
|---|---|---|---|
| segmented | **2** | `3×source + scratch + 2 GiB` | every rendered segment (~1× source) AND the concat output coexist |
| combined (music + censor) | **2** | `3×source + 2 GiB` | render temp AND published output coexist |
| censor-only, music-only, audio-only | **1** | `2×source + 2 GiB` | one temp + the published copy |

(`Preflight.tempCopiesFor` at `:60` encodes the same rule for the two single-op shapes but is *not* the
function the worker calls — the worker passes the value inline so it can add the `segmented` case.)

`extraScratchBytes` (`work/FilterWorker.kt:251-252`):

```
extraScratch = (resumableAudio || (segmented && removeMusic) ? (durationMs / 1000) * 176_400 : 0)
             + (audioPlan == .TRANSCODE                      ? (durationMs / 1000) *  24_000 : 0)
```

| term | rate | note |
|---|---|---|
| separated-audio PCM | **176 400 B/s** of source | int16 stereo 44.1 kHz; ~**1.6 GB** on a 155-min film. Scales with **duration**, not source size, so it cannot be folded into `tempCopies` (`work/Preflight.kt:71-74`) |
| AAC transcode | **24 000 B/s** of source | 192 000 bit/s stereo; ~**220 MB** on a 155-min film, same lifetime as the PCM |

The two are mutually exclusive (`TRANSCODE` is only chosen when `!removeMusic`); they are **added rather
than branched** so a later edit does not have to re-prove that exclusivity (`work/FilterWorker.kt:249-250`).
Note `durationMs / 1000` is integer division.

**Measured finding — the full-size mux copy is why the formula counts the published copy.** The "<2 GB temp
on 2 h input" claim was never true: *the mux temp is a full-size copy of the source, so a 2 h movie exceeds
2 GB by construction*. The preflight now **sizes for that instead of asserting it**. — `docs/tasks.md:50`.
The related PRD figure "2× source + 2 GB" (`docs/prd-video-filter-android.md:91`) is exactly the one-temp
case; combined correctly reserves 3× (`docs/tasks.md:54`). A later optimisation (plan-v2 §5.9 S5) removed
the *separate* `mux.mp4` by muxing straight into the output row, but the two surviving copies still coexist,
so `tempCopies = 2` for combined stands (`work/FilterWorker.kt:236-239`).

**Measured finding — `file://` sources.** `OpenableColumns.SIZE` returns null for `file://` (no provider
behind it), so the whole space check silently degraded to the bare 2 GiB slack. Fixed by asking the
filesystem: `if scheme == "file" { File(path).length() }` else the provider query, `0` on any failure
(`:143-156`; `docs/prd-download-share.md:137`). **Apple note:** the equivalent trap is a security-scoped
`PHAsset`/`fileURL` with no size attribute — resolve the size before trusting the check.

### 4.2 The other guards, in evaluation order

`Preflight.check(context, uri, needsAudio, tempCopies, extraScratchBytes = 0, allowNoVideo = false) -> Int?`
(`:78-114`). Returns a **string-resource id**, or nil when the job may proceed.

| # | test | result | line |
|---|---|---|---|
| 1 | `extractor.psshInfo?.isNotEmpty()` — non-empty PSSH = the container carries DRM init data. **Checked before codec lookup**, because a protected track otherwise fails later with an opaque crypto error | `DRM` | `:91-93` |
| 2 | `IOException` while opening | `UNREADABLE` — "Failed to instantiate extractor": unsupported container or damaged file | `:96-97` |
| 3 | `IllegalArgumentException` | `UNREADABLE` — malformed/unresolvable Uri | `:98-99` |
| 4 | `SecurityException` | `UNREADABLE` — the read permission grant expired since the pick | `:100-101` |
| 5 | `!hasVideo && !allowNoVideo` | `NO_VIDEO` | `:106` |
| 6 | `needsAudio && !hasAudio` | `NO_AUDIO` | `:107` |
| 7 | `usableSpace < required` | `LOW_SPACE` | `:113` |

The extractor is always released in `finally` (`:102-104`).

**Contract 4.2.1 — Preflight opens the source itself rather than trusting the picker.** An unreadable, DRM'd
or exotic-container file only reveals itself when the extractor touches it, and that throw used to escape
`doWork` entirely: WorkManager logged "Failed to instantiate extractor" and the UI showed **nothing at all**
(observed on device, 2026-07-26). — `:18-23`

**Contract 4.2.2 — every cause is a resource id, never a String.** It used to be a String while the UI read
the key with `getInt`, so `Data.getInt` fell to its default on *every* failure and the UI showed only
"Filtering failed" — making the Arabic translations of these eight sentences unreachable. Ids also
re-localize if the app language changes after the job failed. — `:28-33`

**Contract 4.2.3 — the geometry probe is a Preflight-adjacent guard.** `FrameSampler.probe` runs **once for
the whole job** (it used to run 4× unsegmented and N+3 segmented — 35-70 container opens per film), before
Preflight because the segment plan changes how much scratch is needed, and a nil probe on a non-audio-only
shape fails the job with a per-cause message at the same moment as every other check.
— `work/FilterWorker.kt:194-201`, `:256-260`

### 4.3 Mid-pipeline failure taxonomy

`Preflight.messageFor(t: Throwable) -> Int` (`:125-141`). Concatenates the whole cause chain's messages,
lowercases, then matches **in this order** (specific cases shadow `GENERIC`):

| # | substring test | result |
|---|---|---|
| 1 | `"enospc"` or `"no space left"` | `OUT_OF_SPACE` |
| 2 | `"crypto"` or `"drm"` | `DRM` |
| 3 | `"codec"`, `"decoder"`, `"encoder"`, or **`"failed to initialize"`** | `UNSUPPORTED_CODEC` |
| 4 | `t is FileNotFoundException` | `UNREADABLE` |
| 5 | `t is IOException` | `UNREADABLE` |
| 6 | otherwise | `GENERIC` |

`"failed to initialize"` is `MediaCodec.createDecoderByType`'s own wording when the device has no codec for
a mime ("Failed to initialize audio/ac3, error 0x80001001") — it names neither "codec" nor "decoder", so it
used to fall through to `GENERIC`, and it is the single most likely failure of the AAC transcode a segmented
AC-3/DTS job now runs (`:131-136`).

**Contract 4.3.1 — an unrecognized cause resolves to `GENERIC`, never to the throwable's own message.** That
message used to reach the screen verbatim — untranslated developer text like "separator emitted 3 of 4
frames". It is still logged with the full stack by every caller. — `:118-122`

### 4.4 Other guards outside Preflight

| guard | rule | line |
|---|---|---|
| no-op job | `!removeMusic && !censorFaces` ⇒ `Result.failure()` | `work/FilterWorker.kt:184` |
| no-op **render** | `blurAmount == 0 && !grayscale && solidColor == BLUR` is coerced to `MIN_EFFECTIVE_BLUR = 25`, **not** failed | `:103-105`, `:1330-1336` |
| renderer region overflow | more than `RENDERER_MAX_REGIONS = 8` faces on screen ⇒ those spans are promoted to whole-frame censor intervals (the shader silently drops the *smallest* past 8, i.e. it fails **open**, on the frames with the most people in them) | `:1178-1215`, `:1350-1358` |
| unknown duration | `NsfwGate.intervals(firings, durationMs > 0 ? durationMs : Int64.max)` — an unknown duration must mean "do not clamp the far end", not "clamp to nothing". Passing `coerceAtLeast(1)` collapsed every censor interval to `[0,1 ms]`: the gate fires, the EDL says so, and **nothing is censored** | `:1147-1155`, `:1172` |

`MIN_EFFECTIVE_BLUR = 25` gives `sigma = 25/100 * 40 * (min(w,h)/1080)` = 10 px at 1080p, 4.4 px at 480p.
Coerced rather than failed because a per-cause failure needs a sentence the user can act on and there is
none for this; coercion delivers what the job promised. Read in the **worker**, not the UI, because a queued
job carries its own input data and must not be able to bypass the guard. — `:89-102`

---

## 5. Publish

`work/Publish.kt`. Every job shape ends here.

### 5.1 Destinations

| shape | collection | `RELATIVE_PATH` | mime | function |
|---|---|---|---|---|
| every video shape | `MediaStore.Video`, `VOLUME_EXTERNAL_PRIMARY` | `Movies/Naqi` | `video/mp4` | `video()` (from a temp) / `muxedVideo()` (in place) |
| audio-only | `MediaStore.Audio`, `VOLUME_EXTERNAL_PRIMARY` | `Music/Naqi` | **`audio/mp4`** | `audio()` |

`RELATIVE_PATH` is literally `"$publicDir/Naqi"` (`:133`). `audio/mp4`, not `audio/m4a`: an `.m4a` published
into the Video collection is invisible to every music player, which is the only app that would want it
(`:35`, `:81-84`).

Apple equivalent: `PHPhotoLibrary` (video) with an album named **Naqi**, or a user-chosen folder; audio-only
output has no Photos equivalent and needs a Files destination.

### 5.2 The pending-row dance (the cancellation contract)

```
values = { DISPLAY_NAME, MIME_TYPE, RELATIVE_PATH, IS_PENDING: 1 }      // :130-135
item   = resolver.insert(collection, values)                            // fail -> error
try {
    …write bytes…
    values = { IS_PENDING: 0 }; resolver.update(item, values)            // finalize
    return item
} catch {
    resolver.delete(item)      // drop the un-finalized (still-pending) row
    rethrow
}
```

**Contract 5.2.1 — a cancelled or failed job must NEVER leave output in the gallery.** That is the whole
quarantine promise, and it is enforced here rather than by the callers. — `:19-21`

**Contract 5.2.2 — cancellation is polled by hand, once per buffer.** The copy has no suspension point.
`isStopped` is checked while copying **and again before finalizing** (`:110`, `:117`). `COPY_BUFFER = 1 shl
20` = **1 MiB** (`:31`): `copyTo`'s 8 KiB default means ~200 000 read/write pairs for a 1.7 GB film; 1 MiB is
one page-cache batch per iteration and gives cancellation a granularity a user can feel. At 8 KiB the poll
would be free but pointless; at 16 MiB the copy would be uncancellable for seconds at a time. A film is
~1 700 buffers here, and the copy used to run to completion after a cancel before anyone noticed.

**Contract 5.2.3 — `muxedVideo` opens the descriptor `"rw"`, not `"w"`.** `MediaMuxer` seeks back over the
file to write `moov`, and a write-only descriptor fails at `stop()` — after the whole remux. — `:59-70`

`muxedVideo` exists because every muxing shape used to write `mux.mp4` and then copy it: ~1.7 GB written and
read again per film, for bytes the muxer could have put in the right place first time. It is strictly
stronger than the temp path it replaced — a crash between mux and publish used to leave an orphan on disk
(`:44-57`).

### 5.3 Naming scheme

`work/FilterWorker.outputName(uri, ext = "mp4")` (`:1251-1256`):

```
source = displayName(uri)                              // provider query
      ?? (uri.scheme == "file" ? File(uri.path).name : nil)
      ?? "video"
return "\(source.substringBeforeLast("."))-naqi-\(Date().millisecondsSince1970).\(ext)"
```

⇒ `<sourceNameNoExt>-naqi-<epochMillis>.mp4` (or `.m4a` for audio-only, `:343`).

**The `file://` fallback is what makes a downloaded video keep its title.** There is no provider behind
`file://`, so `DISPLAY_NAME` returns null and every quarantined download would have been published as
`video-naqi-<ts>.mp4` — the title thrown away one step before the user sees it. — `:1242-1250`

The epoch-millis suffix is the only collision defence; there is no dedupe query.

### 5.4 The original-untouched guarantee

| # | statement | evidence |
|---|---|---|
| 1 | The source is opened **read-only, ever**. No shape writes to it; music removal *copies* the video track sample-for-sample. | `work/Publish.kt` writes only into new MediaStore rows; `work/FilterWorker.kt:206-207` |
| 2 | All scratch lives in `noBackupFilesDir/naqi-work/<key>/`, never next to the source. | `work/JobStore.kt:53-54` |
| 3 | The user-facing promise is a first-class line under the primary CTA on step 1: `pick_reassurance` = "Original file is never changed." / "لا يُمسّ الملف الأصلي أبدًا." | `ui/screen/PickOpsScreen.kt:115` |
| 4 | Deleting the original is **explicitly opt-in, two-step, and never automatic**: a "Delete original" notification action that only *opens the app*, then a confirm dialog. "Deleting the user's only copy of a video is unrecoverable, and a notification action is one stray tap." | `work/JobNotifications.kt:100-104`, `:144-151`; `MainActivity.kt:110-116`, `:206-220` |
| 5 | The delete itself is best-effort and **always reports failure**: direct delete → `DocumentsContract.deleteDocument` → `contentResolver.delete` → API 30+ `MediaStore.createDeleteRequest` (the system asks the user) → toast `dlg_delete_original_failed`. Silently keeping a file the user asked to delete is worse than saying we couldn't. | `MainActivity.kt:118-141` |
| 6 | Ordering: the delete runs **only after** `Publish` has finalized the output. | `work/FilterWorker.kt:1226-1227` |

(6) refers to the quarantined-download path, which is cut on Apple; (1)-(5) all port.

---

## 6. Design language

### 6.1 Raw brand palette

`ui/theme/Color.kt:7-11`. Concept: "filtered water / clarity" — jade for trust + purity, ink/paper for a
clean, calm reading surface (`:5-6`).

| token | hex | role in the shipped Android app |
|---|---|---|
| `NaqiJade` | **`#1F6E5A`** | light `primary`, dark `inversePrimary` — every interactive accent, every trust mark |
| `NaqiJadeBright` | **`#55C3A1`** | dark `primary`, light `inversePrimary`; the droplet in the app icon |
| `NaqiDeep` | **`#0C1512`** | dark `background` + `surface`; `values-night` window background |
| `NaqiInk` | **`#10201C`** | light `onBackground` + `onSurface` — body text on paper |
| `NaqiPaper` | **`#F5F7F3`** | light `background` + `surface`; window background |

Also shipped, from the icon generator (`branding/generate.js:39`, `:52-54`) — the launcher-icon radial
gradient, needed for an App Icon that matches: `#24805F` (0 %) → `#123A2F` (55 %) → `#08110E` (100 %),
centred at (54, 36) with r = 80 in a 108×108 viewport; cup in `#F5F7F3`, droplet in `#55C3A1`.

**⚠ Naming conflict to resolve before writing Swift.** The Apple task list says "ink = interaction / jade =
video-truth" (`/Users/goldentik/Documents/naqi/docs/tasks.md:80`). The shipped Android code assigns the
**opposite** roles: **jade is the interaction colour** (`primary`, every button, switch, selected state,
progress bar, trust seal) and **ink is the text colour** on paper (`onBackground`/`onSurface`). Nothing in
the Android source or `docs/` uses "video-truth". Recommendation: keep the shipped semantics (jade =
interaction/trust, ink = text) and treat the PRD line as loose shorthand — flipping them would recolour
every control in the app.

### 6.2 Full colour scheme — light

`ui/theme/Color.kt:13-47`. Material 3 role names; the Apple port needs the same 30 semantic slots whatever
they are called.

| role | hex | role | hex |
|---|---|---|---|
| `primary` | `#1F6E5A` | `surfaceVariant` | `#DBE5DF` |
| `onPrimary` | `#FFFFFF` | `onSurfaceVariant` | `#3F4A45` |
| `primaryContainer` | `#A8E9D3` | `surfaceContainerLowest` | `#FFFFFF` |
| `onPrimaryContainer` | `#00251A` | `surfaceContainerLow` | `#EFF4F0` |
| `secondary` | `#4B635A` | `surfaceContainer` | `#ECF1ED` |
| `onSecondary` | `#FFFFFF` | `surfaceContainerHigh` | `#E6ECE8` |
| `secondaryContainer` | `#CDE9DC` | `surfaceContainerHighest` | `#E0E7E2` |
| `onSecondaryContainer` | `#072019` | `outline` | `#6F7A74` |
| `tertiary` | `#7C5A34` | `outlineVariant` | `#BFC9C3` |
| `onTertiary` | `#FFFFFF` | `inverseSurface` | `#2B322F` |
| `tertiaryContainer` | `#F5DDBB` | `inverseOnSurface` | `#ECF1ED` |
| `onTertiaryContainer` | `#2A1800` | `inversePrimary` | `#55C3A1` |
| `error` | `#BA1A1A` | `scrim` | `#000000` |
| `onError` | `#FFFFFF` | `background` | `#F5F7F3` |
| `errorContainer` | `#FFDAD6` | `onBackground` | `#10201C` |
| `onErrorContainer` | `#410002` | `surface` | `#F5F7F3` |
| | | `onSurface` | `#10201C` |

### 6.3 Full colour scheme — dark

`ui/theme/Color.kt:49-83`.

| role | hex | role | hex |
|---|---|---|---|
| `primary` | `#55C3A1` | `surfaceVariant` | `#3F4A45` |
| `onPrimary` | `#00382A` | `onSurfaceVariant` | `#BEC9C2` |
| `primaryContainer` | `#005440` | `surfaceContainerLowest` | `#070E0C` |
| `onPrimaryContainer` | `#A8E9D3` | `surfaceContainerLow` | `#141D19` |
| `secondary` | `#B1CCBF` | `surfaceContainer` | `#182420` |
| `onSecondary` | `#1D352C` | `surfaceContainerHigh` | `#222E2A` |
| `secondaryContainer` | `#344B42` | `surfaceContainerHighest` | `#2D3935` |
| `onSecondaryContainer` | `#CDE9DC` | `outline` | `#89948D` |
| `tertiary` | `#E7C08C` | `outlineVariant` | `#3F4A45` |
| `onTertiary` | `#452B08` | `inverseSurface` | `#DEE8E2` |
| `tertiaryContainer` | `#5F421F` | `inverseOnSurface` | `#2B322F` |
| `onTertiaryContainer` | `#F5DDBB` | `inversePrimary` | `#1F6E5A` |
| `error` | `#FFB4AB` | `scrim` | `#000000` |
| `onError` | `#690005` | `background` | `#0C1512` |
| `errorContainer` | `#93000A` | `onBackground` | `#DEE8E2` |
| `onErrorContainer` | `#FFDAD6` | `surface` | `#0C1512` |
| | | `onSurface` | `#DEE8E2` |

**Contract 6.3.1 — dynamic colour is deliberately NOT offered.** The brand identity must win over the
wallpaper palette. — `ui/theme/Theme.kt:9`. Apple equivalent: do **not** adopt system accent tinting.

### 6.4 Spacing, radius, motion, gutter

`ui/theme/Tokens.kt`. Base unit **4 dp**.

| token | value | line |
|---|---|---|
| `space1` | 4 dp | `:12` |
| `space2` | 8 dp | `:13` |
| `space3` | 12 dp | `:14` |
| `space4` | 16 dp | `:15` |
| `space5` | 24 dp | `:16` |
| `space6` | 32 dp | `:17` |
| `space7` | 48 dp | `:18` |
| `gutter` (screen edge padding) | **20 dp** | `:41` |
| `radiusExtraSmall` | 8 dp | `:21` |
| `radiusSmall` | 12 dp | `:22` |
| `radiusMedium` | 16 dp | `:23` |
| `radiusCard` | **24 dp** | `:24` |
| `radiusLarge` | 28 dp | `:25` |
| `radiusButton` | **20 dp** | `:26` |
| `radiusPill` | 999 dp (capsule) | `:27` |

Shapes: `shapeCard` = 24 dp, `shapeButton` = 20 dp, `shapeTile` = 16 dp, `shapePill` = capsule (`:29-32`).

Motion — one spring, used for **every** state transition in the app (`:35-38`):

```
expressiveSpring = spring(stiffness: 400.0, dampingRatio: 0.5)
// Spring.StiffnessMediumLow = 400f, Spring.DampingRatioMediumBouncy = 0.5f
// (androidx.compose.animation.core.VectorizedAnimationSpec.kt:804, :823)
```

SwiftUI: `.animation(.interpolatingSpring(stiffness: 400, damping: …))` — or, matching the ratio directly,
a critically-under-damped spring with `dampingFraction ≈ 0.5`. Every `animateColorAsState` /
`animateFloatAsState` in the codebase passes this same spec.

**No elevation tokens exist.** Depth is expressed entirely as `surfaceContainer` fill + a **1 dp
`outlineVariant` border** (`ui/Components.kt:192`), never a shadow. Two exceptions use a heavier
**1.5 dp** border: `PickVideoCard` (`ui/screen/PickOpsScreen.kt:259`) and `SelectDot`
(`ui/Components.kt:170`). Dividers are `outlineVariant` at **α 0.7** (`ui/Components.kt:203`).

The theme is `MaterialExpressiveTheme` with `MotionScheme.expressive()` (`ui/theme/Theme.kt:19-24`).

### 6.5 Typography

`ui/theme/Type.kt`. Family: **Thmanyah Sans** — Arabic + Latin including both digit sets, `~` and `—`,
bundled for offline use (`:10`). Three weights bundled: Regular (400), Medium (500), Bold (700) (`:14-18`).

**Contract 6.5.1 — there is no 600 weight, so every "SemiBold" slot below actually resolves to Bold (700).**
— `:12-13`

Base is Material 3's default type scale with per-slot family/weight/tracking overrides. Sizes and line
heights are Material3 1.4.0/1.5.0 `TypeScaleTokens`.

| slot | size | line height | weight (as written / as resolved) | letter spacing |
|---|---|---|---|---|
| `displayLarge` | 57 | 64 | SemiBold → **Bold 700** | **−0.5** (override, `:23`) |
| `displayMedium` | 45 | 52 | SemiBold → Bold 700 | **−0.25** (override, `:24`) |
| `displaySmall` | 36 | 44 | SemiBold → Bold 700 | 0 |
| `headlineLarge` | 32 | 40 | SemiBold → Bold 700 | 0 |
| `headlineMedium` | 28 | 36 | SemiBold → Bold 700 | 0 |
| `headlineSmall` | 24 | 32 | Medium 500 | 0 |
| `titleLarge` | 22 | 28 | SemiBold → Bold 700 | 0 |
| `titleMedium` | 16 | 24 | Medium 500 | 0.2 |
| `titleSmall` | 14 | 20 | Medium 500 | 0.1 |
| `bodyLarge` | 16 | 24 | Regular 400 | 0.5 |
| `bodyMedium` | 14 | 20 | Regular 400 | 0.2 |
| `bodySmall` | 12 | 16 | Regular 400 | 0.4 |
| `labelLarge` | 14 | 20 | SemiBold → Bold 700 | 0.1 |
| `labelMedium` | 12 | 16 | Medium 500 | **0.8** (override, `:36`) |
| `labelSmall` | 11 | 16 | Medium 500 | **0.8** (override, `:37`) |

Units are `sp` (scale-independent) — the Apple equivalent is Dynamic Type; nothing in the app opts out of
scaling.

Which slot each surface uses:

| slot | used for |
|---|---|
| `displaySmall` | the Arabic wordmark نقي (`PickOpsScreen.kt:315`) |
| `titleLarge` | top-bar title (`Components.kt:76`) |
| `titleMedium` | section header, pick-card title, progress stage, "Saved", Latin wordmark, share-sheet title |
| `titleSmall` | every card row title (ToggleTile, WhoRow, SliderRow, KeepStemsOption) |
| `bodyMedium` | queue row title, library row name, failure sentence, dialog body |
| `bodySmall` | every row description, ETA lines, file size, note lines |
| `labelLarge` | primary button label, "Clear finished" |
| `labelMedium` | trust-seal text, slider value pill |

### 6.6 The icon set

`ui/NaqiIcons.kt` — hand-built, 24×24 viewport, `defaultWidth/Height = 24.dp`, filled paths, tinted at the
call site (`:75-85`). Avoids the material-icons-extended dependency.

| icon | shape | note |
|---|---|---|
| `Droplet` | the brand mark; a teardrop from (12,2) | `:13-20`; the app-icon droplet is a scaled copy of this exact path (`branding/generate.js:24-30`) |
| `Video` | camera body (rounded rect) + lens trapezoid | `:22-33` |
| `MusicOff` | three bars + a slash from (4,18.6) to (18.6,4) | `:35-42` |
| `Shield` | pentagon-ish shield | `:44-49` |
| `Check` | tick, stroke-as-path | `:51-54` |
| `ArrowBack` | **`autoMirror = true`** — must point at the *start* edge, which is the right one in Arabic | `:56-61` |
| `Close` | X | `:63-67` |
| `More` | vertical kebab, three r=1.9 dots at cy 5.2 / 12 / 18.8 | `:69-72` |

**Apple note:** SF Symbols cover all eight, but `Droplet` is the brand mark and must stay the custom path.
`ArrowBack` should use a mirroring-aware symbol / `.flipsForRightToLeftLayoutDirection(true)`.

The brand mark proper (`res/drawable/ic_naqi_mark`, `branding/naqi-mark.svg`) is the **bowl of ن holding a
droplet** — it rhymes with the نـ of the wordmark, so the two read as one lockup rather than a logo parked
above a title (`ui/screen/PickOpsScreen.kt:304-306`). ViewBox `28 29.8 52 50.6`. Rendered at **56 dp** in
the wordmark, **22 dp** in the top bar.

### 6.7 Signature components

#### 6.7.1 `TrustSeal` — the pill (`ui/screen/PickOpsScreen.kt:207-232`)

A centred capsule at the very top of step 1, above everything else.

```
HStack(spacing: space2 = 8)                              // Row, Arrangement.spacedBy
  Text(pick_seal_on_device)  labelMedium, color: primary
  Text("·")                  labelMedium, color: primary.opacity(0.55)
  Text(pick_seal_private)    labelMedium, color: primary
.padding(horizontal: space4 = 16, vertical: space2 = 8)
.background(primary.opacity(0.08), in: Capsule())
.overlay(Capsule().stroke(primary.opacity(0.22), lineWidth: 1))
.frame(maxWidth: .infinity, alignment: .center)          // the outer Row centres it
```
The "·" is drawn **in code**, not baked into either string — the two halves are separate resources.
Followed by `space5 = 24 dp` before the pick card.

#### 6.7.2 `PickVideoCard` (`ui/screen/PickOpsScreen.kt:236-295`)

"A wide, unmistakable target that also reports what is picked." Every colour is spring-animated on the
`picked` flag.

```
HStack(alignment: .center)
  ZStack {                                        // icon plate
     RoundedRectangle(cornerRadius: 20)           // radiusButton
       .fill(picked ? primary : surfaceContainerHighest)      // animated
     Icon(picked ? .check : .video)
       .frame(24×24 → rendered 26×26)
       .foregroundStyle(picked ? onPrimary : onSurfaceVariant)
  }.frame(width: 52, height: 52)
  Spacer().frame(width: space4 = 16)
  VStack(alignment: .leading) {                   // weight(1f)
     Text(fileName ?? (picked ? pick_video_selected : pick_video_none))
        titleMedium, onSurface, 1 line, .tail truncation
     Text(picked ? pick_video_change : pick_video_formats)
        bodySmall, onSurfaceVariant
  }
.padding(space4 = 16)
.background(picked ? primary.opacity(0.08) : surfaceContainer, in: RoundedRectangle(24))  // animated
.overlay(RoundedRectangle(24).stroke(picked ? primary : outlineVariant, lineWidth: 1.5))  // animated
.contentShape(Rectangle()).onTapGesture { openPicker() }
```
The filename falls back to a *selected* label, not the *unpicked* one: a provider may not expose a display
name and the video is still picked (`:281-282`).

#### 6.7.3 `OperationCard` = `NaqiCard(contentPadding: 0)` + `ToggleTile` rows

Named in `docs/m0-spikes.md:21` alongside `TrustSeal` and `PickVideoCard`. It is a composition, not a type:
**one card, two rows** — the pair is a single decision about what this run does
(`ui/screen/PickOpsScreen.kt:136-160`).

`NaqiCard` (`ui/Components.kt:181-196`):
```
VStack(alignment: .leading, content)
  .frame(maxWidth: .infinity)
  .padding(contentPadding)                        // default space4 = 16; the row-card variant passes 0
  .background(surfaceContainer, in: RoundedRectangle(24))
  .overlay(RoundedRectangle(24).stroke(outlineVariant, lineWidth: 1))
  .clipShape(RoundedRectangle(24))
```

`NaqiRowDivider` (`ui/Components.kt:200-205`): a 1-px rule inset **16 dp** on both sides, colour
`outlineVariant.opacity(0.7)` — "inset so it reads as a grouping, not a cut".

`ToggleTile` (`ui/Components.kt:211-264`):
```
HStack(alignment: .center)
  if icon != nil {
     ZStack { RoundedRectangle(20).fill(checked ? primary.opacity(0.16) : surfaceContainerHighest)   // animated
              Icon(icon).frame(22×22).foregroundStyle(checked ? primary : onSurfaceVariant) }        // animated
       .frame(width: 42, height: 42)
     Spacer().frame(width: space3 = 12)
  }
  VStack(alignment: .leading) {                   // weight(1f)
     Text(title) titleSmall, onSurface
     if desc != nil { Text(desc) bodySmall, onSurfaceVariant }
  }
  Spacer().frame(width: space3 = 12)
  Toggle("", isOn: …).labelsHidden()              // Switch, onCheckedChange = nil — the ROW is the target
.padding(horizontal: space4 = 16, vertical: space3 = 12)
.accessibilityAddTraits(.isToggle)                // Role.Switch on the whole row
```
**The whole row is the toggle target**, not just the switch (`toggleable` on the Row, the `Switch` gets
`onCheckedChange = null`).

**Contract 6.7.3.1 — `desc` is rendered at full emphasis whether checked or not.** That is why the faces row
has *two* description strings: an off row saying "Everyone · and flagged scenes." would assert censoring
that is not running. — `ui/screen/PickOpsScreen.kt:147-153`

#### 6.7.4 The pass strip — `JobProgressCard` (`ui/screen/JobsScreen.kt:197-240`)

The running-job card, named for the "Pass 1 / Pass 2" stage labels it carries.

```
NaqiCard {
  HStack {
     Text(stage.isEmpty ? jobs_stage_starting : stage)   titleMedium, onSurface, weight(1f)
     Text(jobs_progress_percent(progress))               titleMedium, primary
  }
  Spacer().frame(height: space3 = 12)
  LinearWavyProgressIndicator(value: progress/100)       // M3 Expressive: a SINE-WAVE track, not a bar
     .frame(maxWidth: .infinity, height: 10)
     .tint(primary); trackColor = surfaceContainerHighest
  HStack {
     if etaMs > 0 { Text(jobs_eta_remaining(durationText(etaMs))) bodySmall, onSurfaceVariant, weight(1f) }
     else         { Spacer().frame(maxWidth: .infinity) }
     TextButton(action_cancel) { cancel() }
  }
}
```
**Apple note:** `LinearWavyProgressIndicator` has no SwiftUI equivalent — it needs a custom `Shape`
(animated sine along the filled portion, flat along the track) at **10 pt** height. This is the single most
distinctive moving element in the app; a plain `ProgressView` loses the signature.

`durationText(ms)` (`ui/Components.kt:290-297`) picks **one of three whole strings**, never a number glued
to a unit:
```
minutes = ms / 60_000
minutes < 1   -> dur_under_min
minutes < 60  -> dur_min(minutes)
else          -> dur_h_min(minutes / 60, minutes % 60)
```
Same rule for file sizes (`JobsScreen.kt:387-389`): `bytes >= 1_000_000_000` ⇒ `jobs_size_gb(bytes/1e9)`
(1 dp) else `jobs_size_mb(bytes/1e6)` (0 dp) — decimal, not binary.

**Contract 6.7.4.1 — never format `"\(n) min"` in code.** Arabic keeps its own digits, word order and unit
words, which `"%.1f GB".format(...)` cannot produce. Apple equivalent: `Measurement`/`FormatStyle` or three
separate `.xcstrings` keys — not string concatenation.

#### 6.7.5 Supporting components

| component | spec | line |
|---|---|---|
| `NaqiTopBar` | title (titleLarge, 1 line, ellipsis) with an optional 22 dp `primary`-tinted brand icon at `space1 = 4` spacing; back arrow is `ArrowBack` in an IconButton with `action_back` as its label; container colour = `background` (**not** a raised surface) | `ui/Components.kt:60-89` |
| `NaqiBottomAction` | pinned bottom column on `background`, `navigationBarsPadding`, padding `gutter = 20` horizontal / `space3 = 12` vertical; optional `above` slot then `space2 = 8`; the button is full-width, **56 dp tall**, `shapeButton` (20 dp), label in `labelLarge` | `ui/Components.kt:96-121` |
| `SectionHeader` | `titleMedium`, `onSurfaceVariant`, padding `start = space1 = 4`, `bottom = space2 = 8`, optional trailing slot | `ui/Components.kt:127-142` |
| `SelectDot` | 24 dp circle; selected ⇒ `primary` fill + 15 dp `onPrimary` check, unselected ⇒ transparent fill + 1.5 dp `outline` ring; **scale 1.0 ↔ 0.85** and both colours spring-animated | `ui/Components.kt:146-175` |
| `NoteLine` | centred row: 15 dp `primary` icon, `space1 = 4`, `bodySmall` `onSurfaceVariant` text | `ui/Components.kt:268-279` |
| `Swatch` | 38 dp box; **the selection ring sits OUTSIDE the swatch with a gap** so it reads on black and on white alike: outer `Circle().stroke(selected ? primary : .clear, 2)`, then 4 dp padding, then the fill circle with a 1 dp `outlineVariant` ring; `accessibilityLabel` = the colour name | `ui/screen/OptionsScreen.kt:350-364` |
| `SliderRow` | title `titleSmall` + a **value pill**: `labelMedium` `primary` text on `primary.opacity(0.12)`, capsule, padding `space3 = 12` × 2 dp. Then `bodySmall` desc, then a `0…100` slider that rounds to Int | `ui/screen/OptionsScreen.kt:367-395` |
| `StatusGlyph` | every variant inside one fixed **20 dp** box (a spinner brings its own padding and would otherwise land a couple of dp off the column): FILTERING = 18 dp spinner, `primary`, 2.5 dp stroke · DONE = 20 dp `primary` circle + 13 dp `onPrimary` check · FAILED = 20 dp `error` circle + 13 dp `onError` cross · PENDING = **empty 20 dp ring**, 2 dp `outlineVariant` ("nothing is happening to this yet") | `ui/screen/QueueSection.kt:134-166` |

---

## 7. Screen-by-screen flow, with the real copy

### 7.1 Navigation

`ui/NaqiApp.kt`. Four steps in a `when`, **no nav library** — a straight line does not need a route DSL
(`:24-28`).

```
enum Step { Pick, Options, Jobs, About }        // :22
state: step, pickedUri, pickedName, ops         // all rememberSaveable (:34-37)
```

| transition | trigger | line |
|---|---|---|
| Pick → Options | `onContinue` (enabled only when `pickedUri != nil && ops.any`) | `:64`, `PickOpsScreen.kt:113` |
| Pick → About | overflow menu "About & licenses" | `:65` |
| Options → Jobs | `onStarted`, after the job is enqueued | `:74` |
| Options → Pick | back arrow | `:73` |
| Jobs → Pick | "Filter another video" / back arrow | `:84` |
| any → Pick | **system back**, whenever `step != Pick` (both later steps go back to the start: Options is a detour off Pick, Jobs ends the flow) | `:53` |
| *(cold start)* → Jobs | **auto-attach**: if any observed WorkInfo is unfinished on first emission, jump to Jobs, once (`attached` latch) | `:41-50` |

**Contract 7.1.1 — state must be saveable, not just remembered.** A filter job runs for minutes, so rotation
and process death are both likely mid-job. Plain in-memory state would drop the user back on Pick with no
route to the running job — and the only way forward there (Start) would replace the very job they were
watching. — `:31-33`

**Contract 7.1.2 — re-attach on cold start.** Process death loses even saved state, so a live job is
detected from the scheduler; otherwise a share leaves the user staring at the pick screen with no sign that
anything happened. — `:39-40`

Entry points other than the launcher:

| entry | handler | behaviour |
|---|---|---|
| `ACTION_SEND` with `type` starting `video/` | `MainActivity.sharedOf` (`:97-108`) | opens `ShareSheet` over whatever screen is showing; the extra is **consumed** (`removeExtra`) so a configuration change cannot re-open it; the read grant is **re-granted to ourselves** immediately because a share grant dies with the receiving task and cannot be persisted |
| "Delete original" notification action | `MainActivity.deleteTargetOf` (`:110-116`) | opens `ConfirmDeleteDialog`; distinct intent action so the launcher intent is not reused |
| debug `am start` extras | `maybeAutorun` (`:154-203`) | `autorun_path`, `autorun_cancel`, `censor_who`, `censor_women`, `remove_music`, `whole_frame`, `strictness`, `blur`, `grayscale`, `solid`, `keep_stems`, `force_intervals_ms`, `segment_ms`, `segment_concat_probe` |

Activity is `singleTask` (`:78`).

### 7.2 Step 1 — Pick (`ui/screen/PickOpsScreen.kt`)

Layout top → bottom, scrollable, gutter 20 dp, `top = space2 = 8`, `bottom = space5 = 24`:

1. `TrustSeal` pill · `space5 = 24`
2. `PickVideoCard` · `space5 = 24`
3. `SectionHeader(pick_eyebrow_choose)`
4. `OperationCard`: ToggleTile(music) / divider / ToggleTile(faces)

Top bar: `app_name` + `Droplet` brand icon + overflow (`⋮`). Bottom bar: `NoteLine(Check, pick_reassurance)`
then the `action_continue` button.

**The faces toggle remembers the last real Who.** Off is `NONE`, which erases *which* faces were picked, so
the toggle holds `Prefs.lastWho(context)` and turning it back on restores that — otherwise off-then-on would
silently change a "Women" run to something else. Seeded from `Prefs`, not a constant, so the pick survives
the Options detour, the back button and the process (`:83-89`, `:156-158`).

Overflow menu (`:172-204`): "Language" (API 33+ only — opens the **system per-app language settings**, no
in-app switcher) and "About & licenses" (attribution has to be reachable from the app itself, not only from
the repository — GPL-3.0 and an AGPL-3.0 model are not obligations a README discharges).

### 7.3 Step 2 — Options (`ui/screen/OptionsScreen.kt`)

**Contract 7.3.1 — every control is shown only when the op it applies to is on.** An option that cannot
affect the output would be a lie on screen. — `:100-105`

Censor-faces card (only when `ops.censorFaces`), rows in this order (`:200-247`):

| # | row | shown when | note |
|---|---|---|---|
| 1 | `WhoRow` — 3 segments Everyone / Women / Men | always | **three segments, not four**: `NONE` is the step-1 toggle, and a fourth "Off" segment would be a control that turns off the card containing it (`:285-289`). Saved to `Prefs` **on pick**, not on Start (`:207`) |
| 2 | `ToggleTile` "Cover the whole frame" | always | directly under Who: the other "how much gets covered" decision |
| 3 | `SliderRow` Strictness | always | |
| 4 | `CensorStyleRow` — Blur / Solid + 5 swatches | always | picking a swatch **is** choosing Solid, so a user reaching straight for a colour never has to notice the segmented control (`:309-315`) |
| 5 | `SliderRow` Blur amount | only `solidColor == BLUR` | a solid fill has no blur to style |
| 6 | `ToggleTile` Grayscale | only `solidColor == BLUR` | |

Remove-music card (only when `ops.removeMusic`): two `KeepStemsOption` rows with a `SelectDot`, wire values
`"vocals"` / `"vocals_other"` — **never localize or rename them** (`:397`).

Bottom bar: `opt_eta_floor` when `etaMs > 0`, `opt_job_running` in `error` colour when a job is running, then
`action_start` (disabled while a job runs).

Start sequence (`:139-161`):
```
onStart():  etaMs > CONFIRM_THRESHOLD_MS ? showConfirmDialog() : startWithPermissions()
startWithPermissions():  needsNotifPermission ? requestThenStartRegardless() : startJob()
startJob():  JobController.start(ops, inputUri); goToJobs()
```
The confirm dialog is placed **in front of** the permission dance so the user isn't asked for notifications
only to then back out (`:156-158`). Its confirm button is `action_start`, its dismiss is `action_cancel`.

Duration probe: off the main thread, `0` means "no estimate" (still probing *or* the probe threw), and a
failure is **deliberately silent** — an unreadable source is Preflight's story to tell, and a broken probe
must never stand between the user and Start (`:118-127`).

### 7.4 Step 3 — Jobs / Activity (`ui/screen/JobsScreen.kt`)

Order top → bottom:

1. `QueueCard` — only when `queue.isNotEmpty()` · then `space5 = 24`
2. exactly one of: `JobProgressCard` (running) · `SavedCard` (succeeded) · failure card (failed) ·
   `jobs_none_running` line (nothing, and the queue is also empty)
3. `space6 = 32` · `SectionHeader(jobs_library)` · library rows or `jobs_library_empty`

Failure card: the resolved error sentence in `error` colour, and — only when `resumable && onResume != nil` —
`jobs_resume_hint` plus a full-width `action_resume` button (`:144-166`).

`SavedCard`: 36 dp `primary` plate + `onPrimary` check, `jobs_saved_label` / `jobs_saved_path(name)`, then
side-by-side `action_open` (filled) and `action_share` (outlined) — **only when a shareable uri exists**
(`:243-288`).

Library: `MediaStore.Video` where `RELATIVE_PATH LIKE 'Movies/Naqi/%'`, `DATE_ADDED DESC` (`:324-339`).
"Our own contributions need no permission to read back."

`QueueCard` (`ui/screen/QueueSection.kt:54-73`): section header `queue_eyebrow` with a trailing
`queue_clear_finished` TextButton (only when any item is terminal), then one card with one **~56 dp** row per
item. Rows: `StatusGlyph` · `space3` · title (`bodyMedium`, 1 line) over either the error sentence
(`error` colour, 2 lines) or the state label · `space2` · **exactly one action**:

| item state | action |
|---|---|
| FAILED | TextButton `action_retry` → `JobController.retry` |
| DONE | IconButton `Close`, label `queue_dismiss` → `Queue.remove` |
| PENDING_FILTER / FILTERING | IconButton `Close`, label `action_cancel` → `JobController.cancelItem` |

**Contract 7.4.1 — the queue is not its own screen.** The queue and the running job are the same question
("what is Naqi doing?"); splitting them would mean the user has to know which of two places to look. It used
to be a full bordered card per item (~130 dp each) so three shared links filled the screen before the
running job was visible. — `ui/screen/QueueSection.kt:37-51`

### 7.5 Share sheet (`ui/screen/ShareSheet.kt`)

A modal bottom sheet with `skipPartiallyExpanded = true`, shown over whatever is on screen.

```
Text(title ?? share_untitled)  titleMedium, 2 lines
if durationMs > 0 { Text(durationText(durationMs)) bodySmall, onSurfaceVariant }
space5 = 24
SectionHeader(share_eyebrow_filters)
NaqiCard(contentPadding: 0) { ToggleTile(music, icon: MusicOff)      // NO desc — icon + title only
                              divider
                              ToggleTile(faces, icon: Shield) }
if etaMs > CONFIRM_THRESHOLD_MS { space3; Text(dlg_long_job_body(durationText(etaMs))) bodySmall, onSurfaceVariant }
space5 = 24
HStack { TextButton(action_cancel); space3; Button(action_filter).weight(1).height(52).cornerRadius(20) }
```

**Contract 7.5.1 — the sheet asks the one question that cannot be defaulted and answers everything else from
`Prefs`.** It appears mid-share, on top of another app; the user is not here to configure anything. The full
control set lives on the Options screen. — `:55-65`

**Contract 7.5.2 — no Who control here, but it must not silently ANSWER either.** Off-then-on restores
`Prefs.lastWho`, never a hard-coded Everyone that would then be persisted as a downgrade. — `:76-79`

**Contract 7.5.3 — the primary is disabled when both filters are off.** Filtering nothing is a no-op the
user should not be able to queue (`:179`).

Queueing (`:92-101`): `Prefs.save(ops)` then `JobController.enqueue` — **through the queue**, never straight
to `start`, so there is one list, one place the user looks, and one set of retry/cancel rules however many
videos get shared in a row. Apple equivalent: Share Extension → App Group → main-app queue.

### 7.6 About (`ui/screen/AboutScreen.kt`)

`Wordmark` (56 dp mark, `pick_wordmark_ar` in `displaySmall` `primary`, `space2`, `pick_wordmark_latin` in
`titleMedium` `onSurfaceVariant` — the `space2` gap exists because the ن of نقي drops a dot below its
baseline and the Latin line would otherwise sit in it, `PickOpsScreen.kt:320-321`), then `pick_tagline`,
`about_version`, `about_license`, an Updates section, the device-runtime diagnostics, and a collapsible
open-source notices block read from the `NOTICE` asset.

### 7.7 String table — every user-visible string, EN and AR

`res/values/strings.xml` and `res/values-ar/strings.xml`. `%1$s` etc. are positional format args and must
survive translation. `\'` is an escaped apostrophe; `%%` is a literal `%`; `\n` is a real line break.
**The AR file contains U+200F RIGHT-TO-LEFT MARK characters immediately before some `%1$s`/`%2$s`
placeholders** (`opt_eta_floor`, `dlg_long_job_body`, `jobs_eta_remaining`, `job_notif_stage_eta`,
`about_version`) — they are load-bearing bidi control characters and must be copied verbatim into the
`.xcstrings`.

| name | EN | AR |
|---|---|---|
| `app_name` | Naqi | نقي |
| `pick_seal_on_device` | On-device | على الجهاز |
| `pick_seal_private` | Private | خصوصية تامة |
| `pick_wordmark_ar` | نقي | نقي |
| `pick_wordmark_latin` | Naqi | Naqi |
| `pick_tagline` | Filtering happens on your device.\nYour videos never leave your phone. | تتم الفلترة على جهازك.\nلا تغادر مقاطعك هاتفك أبدًا. |
| `pick_video_none` | Pick a video | اختر فيديو |
| `pick_video_selected` | Video selected | تم اختيار الفيديو |
| `pick_video_change` | Tap to change | انقر للتغيير |
| `pick_video_formats` | MP4 · MKV · WebM | MP4 · MKV · WebM |
| `pick_eyebrow_choose` | Choose what to filter | اختر ما تريد فلترته |
| `pick_op_music_title` | Remove music | إزالة الموسيقى |
| `pick_op_music_desc` | Strip the soundtrack, keep dialogue. | تُزيل الموسيقى التصويرية وتُبقي الحوار. |
| `pick_op_faces_title` | Censor faces | حجب الوجوه |
| `pick_op_faces_desc` | %1$s · and flagged scenes. | %1$s · والمشاهد المُخالفة. |
| `pick_op_faces_desc_off` | Blur faces and flagged scenes. | تُموّه الوجوه والمشاهد المُخالفة. |
| `action_more` | More | المزيد |
| `action_continue` | Continue | متابعة |
| `pick_reassurance` | Original file is never changed. | لا يُمسّ الملف الأصلي أبدًا. |
| `pick_diag_title` | Device runtime | بيئة التشغيل على الجهاز |
| `pick_diag_running` | Running model smoke… | جارٍ اختبار النماذج… |
| `pick_diag_eps` | EPs: %1$s | موفّرات التنفيذ: %1$s |
| `pick_diag_model_line` | %1$s %2$s · %3$s | %1$s %2$s · %3$s |
| `dur_under_min` | under a minute | أقل من دقيقة |
| `dur_min` | %1$d min | %1$d دقيقة |
| `dur_h_min` | %1$d h %2$d min | %1$d ساعة و%2$d دقيقة |
| `action_back` | Back | رجوع |
| `opt_title` | Options | الخيارات |
| `opt_section_censor_faces` | Censor faces | حجب الوجوه |
| `opt_who_title` | Who | مَن يُحجب |
| `opt_who_desc` | Which faces get covered. Flagged scenes are censored either way. | أيّ الوجوه تُحجب. المشاهد المُخالفة تُحجب في كل الأحوال. |
| `opt_who_everyone` | Everyone | الجميع |
| `opt_who_women` | Women | النساء |
| `opt_who_men` | Men | الرجال |
| `opt_whole_frame_title` | Cover the whole frame | حجب الشاشة كاملة |
| `opt_whole_frame_desc` | Covers the entire picture while a censored face is on screen, not just the face. Most of the video usually ends up covered. | يحجب الصورة كاملة طوال ظهور وجه محجوب، لا الوجه وحده. غالبًا ما يُحجب معظم الفيديو بهذه الطريقة. |
| `opt_strictness_title` | Strictness | الصرامة |
| `opt_strictness_desc` | How eagerly whole scenes are censored. Face blurring is never affected. | مدى سهولة حجب المشهد كاملًا. لا يتأثّر تمويه الوجوه بهذا الإعداد. |
| `opt_censor_style_title` | Censor style | أسلوب الحجب |
| `opt_censor_style_desc` | Blur the area, or cover it with a solid color. | تمويه المنطقة أو تغطيتها بلون كامل. |
| `opt_style_blur` | Blur | تمويه |
| `opt_style_solid` | Solid | لون |
| `opt_solid_gray` | Gray | رمادي |
| `opt_solid_black` | Black | أسود |
| `opt_solid_white` | White | أبيض |
| `opt_solid_navy` | Navy | كحلي |
| `opt_solid_green` | Green | أخضر |
| `opt_blur_amount_title` | Blur amount | شدّة التمويه |
| `opt_blur_amount_desc` | How heavy the blur is on faces and censored scenes. | مدى قوّة التمويه على الوجوه والمشاهد المحجوبة. |
| `opt_grayscale_title` | Grayscale | تدرّج الرمادي |
| `opt_grayscale_desc` | Also drains the color out of censored areas. | يسحب الألوان أيضًا من المناطق المحجوبة. |
| `opt_section_remove_music` | Remove music | إزالة الموسيقى |
| `opt_keep_vocals_title` | Voices only | الأصوات البشرية فقط |
| `opt_keep_vocals_desc` | Keeps dialogue and singing; drops music and sound effects. | يُبقي الحوار والغناء؛ ويحذف الموسيقى والمؤثرات الصوتية. |
| `opt_keep_vocals_other_title` | Voices + sounds | الأصوات البشرية + المؤثرات |
| `opt_keep_vocals_other_desc` | Also keeps sound effects and ambience; some music may leak. | يُبقي أيضًا المؤثرات الصوتية وأصوات المحيط؛ وقد يتسرّب شيء من الموسيقى. |
| `action_hide` | Hide | إخفاء |
| `action_show` | Show | إظهار |
| `opt_language` | Language | اللغة |
| `action_start` | Start | بدء |
| `opt_slider_value` | %1$d | %1$d |
| `opt_eta_floor` | Estimated at least %1$s on this phone. | المدة المتوقّعة على هذا الهاتف: ‏%1$s على الأقل. |
| `opt_job_running` | A job is already running. Wait for it to finish, or cancel it first. | هناك مهمة قيد التنفيذ بالفعل. انتظر حتى تنتهي أو ألغِها أوّلًا. |
| `dlg_long_job_title` | This will take a while | ستستغرق هذه العملية وقتًا طويلًا |
| `dlg_long_job_body` | This video is estimated at ~%1$s at least. Keep the phone plugged in — Naqi keeps working in the background, but the phone will be busy the whole time and the job can't be paused, only cancelled. | تُقدَّر مدة هذا الفيديو بنحو ‏%1$s على الأقل. أوصِل الهاتف بالشاحن — يواصل نقي العمل في الخلفية، لكن الهاتف سيبقى مشغولًا طوال هذه المدة، ولا يمكن إيقاف المهمة مؤقتًا، بل إلغاؤها فقط. |
| `jobs_new_job` | Filter another video | فلترة فيديو آخر |
| `jobs_title` | Activity | النشاط |
| `jobs_resume_hint` | The finished parts of this job were kept. Resuming picks up where it stopped. | تم الاحتفاظ بالأجزاء المكتملة من هذه المهمة. الاستئناف يكمل من حيث توقّفت. |
| `action_resume` | Resume | استئناف |
| `jobs_none_running` | No job running. | لا توجد مهمة قيد التنفيذ. |
| `jobs_library` | Library | المكتبة |
| `jobs_library_empty` | Filtered videos are saved to Movies/Naqi. | تُحفظ مقاطع الفيديو المُفلترة في Movies/Naqi. |
| `jobs_stage_starting` | Starting… | جارٍ البدء… |
| `jobs_progress_percent` | %1$d%% | %1$d٪ |
| `jobs_eta_remaining` | ~%1$s remaining | يتبقّى نحو ‏%1$s |
| `jobs_saved_label` | Saved | تم الحفظ |
| `jobs_saved_path` | Movies/Naqi/%1$s | Movies/Naqi/%1$s |
| `jobs_share_chooser_title` | Share video | مشاركة الفيديو |
| `jobs_size_gb` | %1$.1f GB | %1$.1f غيغابايت |
| `jobs_size_mb` | %1$.0f MB | %1$.0f ميغابايت |
| `dlg_original_deleted` | Original deleted | تم حذف الملف الأصلي |
| `dlg_original_kept` | Original kept | تم الإبقاء على الملف الأصلي |
| `dlg_delete_original_failed` | Couldn't delete the original — remove it from your gallery instead. | تعذّر حذف الملف الأصلي — احذفه من المعرض بدلًا من ذلك. |
| `dlg_delete_original_title` | Delete the original? | حذف الملف الأصلي؟ |
| `dlg_delete_original_body` | The filtered copy (%1$s) stays in Movies/Naqi. The original video will be removed from this device and can't be recovered. | تبقى النسخة المُفلترة (%1$s) في Movies/Naqi. أمّا الفيديو الأصلي فسيُحذف من هذا الجهاز ولا يمكن استرجاعه. |
| `dlg_delete_original_fallback_name` | this video | هذا الفيديو |
| `action_delete` | Delete | حذف |
| `action_keep` | Keep | إبقاء |
| `err_drm` | This video is copy-protected (DRM), so it can't be filtered. | هذا الفيديو محمي ضد النسخ (DRM)، لذا لا يمكن فلترته. |
| `err_unreadable` | This file couldn't be opened. It may be damaged or in a format this device doesn't support. | تعذّر فتح هذا الملف. قد يكون تالفًا أو بصيغة لا يدعمها هذا الجهاز. |
| `err_no_video` | This file has no video track. | لا يحتوي هذا الملف على مسار فيديو. |
| `err_no_audio` | This video has no audio track, so there's no music to remove. | لا يحتوي هذا الفيديو على مسار صوتي، فلا توجد موسيقى لإزالتها. |
| `err_low_space` | Not enough free space. Filtering needs room for a temporary copy plus about 2 GB. | لا توجد مساحة خالية كافية. تحتاج الفلترة إلى مساحة لنسخة مؤقتة إضافةً إلى نحو ٢ غيغابايت. |
| `err_unsupported_codec` | This video uses a codec this device can't decode. | يستخدم هذا الفيديو ترميزًا لا يستطيع هذا الجهاز فكّه. |
| `err_out_of_space` | The device ran out of space while saving the filtered copy. | نفدت مساحة التخزين في الجهاز أثناء حفظ النسخة المُفلترة. |
| `err_generic` | Filtering failed. | فشلت الفلترة. |
| `job_channel_name` | Video filtering | فلترة الفيديو |
| `job_channel_desc` | Progress for on-device filtering jobs | تقدّم مهام الفلترة على الجهاز |
| `job_notif_title` | Filtering video | جارٍ فلترة الفيديو |
| `action_cancel` | Cancel | إلغاء |
| `job_notif_stage_eta` | %1$s · ~%2$s remaining | %1$s · يتبقّى نحو ‏%2$s |
| `stage_preparing` | Preparing audio | تجهيز الصوت |
| `stage_analyzing` | Pass 1 — analyzing | المرحلة ١ — التحليل |
| `stage_rendering` | Pass 2 — rendering | المرحلة ٢ — المعالجة |
| `stage_separating` | Removing music | إزالة الموسيقى |
| `stage_muxing` | Finishing up | اللمسات الأخيرة |
| `share_untitled` | Shared video | فيديو مُشارَك |
| `share_eyebrow_filters` | Filters | الفلاتر |
| `action_filter` | Filter | فلترة |
| `action_retry` | Retry | إعادة المحاولة |
| `queue_eyebrow` | Queue | القائمة |
| `queue_state_pending_filter` | Waiting to filter | في انتظار الفلترة |
| `queue_state_filtering` | Filtering | جارٍ الفلترة |
| `queue_state_done` | Saved | تم الحفظ |
| `queue_state_failed` | Failed | فشل |
| `queue_dismiss` | Dismiss | إخفاء |
| `queue_clear_finished` | Clear finished | مسح المكتملة |
| `about_title` | About Naqi | عن نقي |
| `about_version` | Version %1$s (%2$d) | الإصدار %1$s ‏(%2$d) |
| `about_license` | Licensed under GPL-3.0-or-later. | مرخّص بموجب GPL-3.0-or-later. |
| `about_open` | About & licenses | عن التطبيق والتراخيص |
| `about_eyebrow_updates` | Updates | التحديثات |
| `about_releases_title` | Releases on GitHub | الإصدارات على GitHub |
| `about_releases_desc` | New versions are published here. Naqi does not update itself. | تُنشر النسخ الجديدة هناك. نقي لا يحدّث نفسه. |
| `about_eyebrow_licenses` | Open source | المصادر المفتوحة |
| `about_notices_title` | Open-source notices | إشعارات المصادر المفتوحة |
| `about_notice_missing` | Open-source notices are unavailable in this build. | إشعارات المصادر المفتوحة غير متوفّرة في هذه النسخة. |
| `done_notif_title` | Filtered copy saved | تم حفظ النسخة المُفلترة |
| `action_open` | Open | فتح |
| `action_share` | Share | مشاركة |
| `action_delete_original` | Delete original | حذف الأصلي |

**Copy notes that are decisions, not accidents:**

1. `pick_seal_private` was "Offline" until link downloads landed, which made "Offline" a false claim
   (`res/values/strings.xml:14-16`). Links are cut on Apple — the string may go back to "Offline" only if
   the Apple build genuinely never touches the network after model install.
2. `pick_op_faces_desc` vs `_desc_off`: the substituted line **asserts what IS happening**; the off line
   describes what turning it on *would* do. Never merge them (`:39-44`).
3. `pick_video_formats`, `jobs_saved_path`, `jobs_library_empty`'s `Movies/Naqi`, `about_license`'s SPDX id
   and "GitHub" are **proper nouns / real paths and stay Latin in every locale** (marked in both files).
   `Movies/Naqi` must be re-pointed for Apple.
4. `err_low_space`'s "about 2 GB" is a **fixed constant in the copy, not a format arg** (`:208-209`). If
   `SLACK_BYTES` changes, both strings change.
5. `jobs_progress_percent` uses `٪` (U+066A, ARABIC PERCENT SIGN) in AR, so no `%%` escape is needed there.
6. `opt_who_title` in AR is `مَن يُحجب`, not a bare `مَن` — every sibling title in that card is a noun
   phrase, and the bare interrogative reads as a dangling question; the fatha disambiguates مَن (who) from
   مِن (from) (`res/values-ar/strings.xml:73-74`).
7. The `action_*` keys are shared between the notification and the screens and must keep exactly these names
   (`res/values/strings.xml:216-218`).

### 7.8 Store listing

`docs/store-listing.md` carries the shipped EN + AR listing. Two things to fix before reusing it on the App
Store: the short description says "censor women" (the product now says *Who*: Everyone / Women / Men), and
the requirements line says "2x video size + 2GB" (correct only for single-op shapes — combined and segmented
need 3×; §4.1).

---

## 8. Android-bound surfaces that need an Apple decision

| # | Android mechanism | where | Apple equivalent |
|---|---|---|---|
| 1 | WorkManager unique work + chain (FIFO, survives process death) | `work/JobController.kt` | app-owned serial queue; persistence is already covered by `queue.json` + the checkpoint layer |
| 2 | Foreground service + 6 h/24 h `MEDIA_PROCESSING` cap | `work/JobNotifications.kt:88-94` | iOS: foreground + keep-awake toggle, honest "phone must stay open" copy; macOS: unconstrained |
| 3 | `WorkInfo.stopReason == CANCELLED_BY_APP` to distinguish user-cancel from system-kill | `work/FilterWorker.kt:535-536` | **no direct equivalent.** Must be modelled explicitly: set a "user cancelled" flag before tearing down, and treat every other termination as resumable |
| 4 | `MediaStore` pending rows (`IS_PENDING` 1→0) | `work/Publish.kt` | `PHPhotoLibrary` change request is atomic; the equivalent guarantee is "do not create the asset until the file is complete" |
| 5 | `noBackupFilesDir` | `work/JobStore.kt:54` | `Application Support` with `isExcludedFromBackup = true`, or `Caches` **plus** the age-based sweep (Caches is reclaimable — the same trap the Android app already hit) |
| 6 | `SharedPreferences` | `data/Prefs.kt` | `UserDefaults` in the **App Group** suite (the share extension reads it) |
| 7 | `ACTION_SEND` + a re-granted read permission | `MainActivity.kt:97-108` | Share Extension → App Group container; the extension must never touch models or video bytes (~120 MB memory cap) |
| 8 | `POST_NOTIFICATIONS` runtime permission, job starts either way | `OptionsScreen.kt:144-153` | `UNUserNotificationCenter.requestAuthorization`, same rule |
| 9 | System per-app language settings (API 33+) | `PickOpsScreen.kt:181-196` | iOS per-app language in Settings; nothing in-app |
| 10 | `LinearWavyProgressIndicator` (M3 Expressive) | `JobsScreen.kt:217` | custom SwiftUI `Shape` — see §6.7.4 |
| 11 | `ActivityManager.MemoryInfo.totalMem` / `isLowRamDevice` for the concurrency gate | `FilterWorker.kt:296-303` | `ProcessInfo.physicalMemory`; **the 6.5 GiB threshold is an Android carveout number and must be re-measured on Apple silicon** |
| 12 | `Thread`-level thermal status (`PowerManager.currentThermalStatus`) driving `thermalYield` | `work/JobStats.kt:41-44` | `ProcessInfo.thermalState` (`.nominal/.fair/.serious/.critical`) |
| 13 | `/proc/self/status` `VmHWM` peak RSS instrumentation | `work/JobStats.kt:76-80` | `task_vm_info.phys_footprint` / `os_proc_available_memory()` |

---

## 9. Riskiest carry-overs — do not silently drop

1. **The 6.5 GiB concurrency threshold is an Android-specific carveout number** and already shipped wrong
   once (7 GiB excluded the exact device class it was sized for). Re-measure; do not port the constant.
2. **`plan4` — the job-key plan generation.** Any change to what an `an-NNN.json` or `seg-NNN.mp4` *means*
   must bump it, or a resume mixes incompatible semantics silently. The Apple port starts a fresh generation.
3. **`stats.frames == 0` is the audio completion marker.** Treating it as a real frame count truncates the
   user's film.
4. **Sync-sample snapping of interior segment boundaries.** Without it, an AVFoundation-side equivalent of
   the decode-order clip-end bug will drop 1-3 frames per seam. Verify the Apple clipper's behaviour before
   deciding this can be dropped.
5. **`mean`/`std` must survive a resume unchanged**, or the audio level steps mid-film.
6. **Preflight must open the source itself** — a picker that hands back a URL says nothing about DRM,
   container support or track presence.
7. **A queued run must never surface a hard failure to the queue driver**, or one bad item kills the rest.
8. **`durationText` / `formatSize` never concatenate a number and a unit** — Arabic needs its own digits,
   word order and unit words.
9. **`whoOrNull` resolves anything unrecognised to `everyone`.** Censoring is the safe direction; a typo that
   stopped censoring is the one failure the user would not see.
10. **`blurAmount == 0 && !grayscale && solidColor == BLUR` must be coerced to 25**, or a full render
    produces a byte-for-byte copy of the input while telling the user it censored it.
