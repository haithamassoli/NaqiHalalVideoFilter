# Plan — who gets censored: women / men / both / none

Status: **Phases A, B and C implemented and device-verified on an S23, 2026-08-03.** Target: the user
picks **Everyone · Women · Men · Off** for face censoring.

**Phase B passed its §3.3 gate**, so Phase C was built. What shipped, and where it deviates from what
this document proposed, is §9. Read that before trusting §3–§5's estimates: several were wrong, and the
measured numbers are in §9, not here.

---

## 0. TL;DR

Two of the four states already ship. `censorWomen` has been a **misnomer since plan-v2 §5.4** — the
gender vote was removed and `FaceTracker` now censors *every* detected face (`FaceTracker.kt:11-36`,
`Contracts.kt:41-49`). So:

| State | Cost today |
|---|---|
| **Off** | toggle off — ships |
| **Everyone** (= "both") | toggle on — ships, this is current behaviour |
| **Women only** | needs a face-gender classifier the app does not have (§3) |
| **Men only** | same classifier |

So this is not a UI feature with a bit of ML behind it. It is **a classifier project with two chips of
UI in front of it**, and the repo has already failed this exact task once: NudeNet's `FACE_FEMALE`
fired 0.69–0.83 on Einstein/Obama/Trump while `FACE_MALE` stayed ≤ 0.07 (`m0-spikes.md:35`), i.e. the
old vote censored ~everyone while claiming to select. It was ripped out for that plus AGPL-3.0 plus
~500 MB of retained crops.

**Therefore the plan is gated:** Phase A ships the picker with only the free states reachable, Phase B
is a go/no-go on a classifier measured *before* any wiring, Phase C wires it. If Phase B fails, Phase C
never happens and the app keeps one honest toggle. Do not ship a Women/Men picker backed by a
classifier that has not passed §4's bar.

---

## 1. Phase A — the picker (no ML, ~1.5 h)

### 1.1 Model

One field replaces the boolean, a String like the existing `keepStems` (`FilterOps.kt:28`) so all four
serializers take it unchanged:

```kotlin
// FilterOps.kt
val censorWho: String = EVERYONE,   // "none" | "everyone" | "women" | "men"
val censorFaces: Boolean get() = censorWho != NONE
val any: Boolean get() = removeMusic || censorFaces
```

`censorWomen` is deleted; the name has been wrong for a milestone and every call site is touched by
this change anyway (`grep -c censorWomen` = 28 across 15 files).

**Back-compat, all four wire formats** — each reader falls back to the old boolean when the new key is
absent, so an in-flight queue file or a stale `adb` script keeps working:

| Where | New | Fallback |
|---|---|---|
| `Queue.kt:141,166` (`queue.json`) | `censorWho` string | `censorWomen` bool → `everyone`/`none` |
| `QueuedWorker.kt:59,70` + `FilterWorker.kt:143` (WorkManager `Data`) | `KEY_CENSOR_WHO` | `KEY_CENSOR_WOMEN` |
| `Prefs.kt:46,56` | `KEY_CENSOR_WHO` | `KEY_CENSOR_WOMEN` |
| `MainActivity.kt:204` (debug intent) | `--es censor_who women` | `--ez censor_women` |

Phase A accepts only `none`/`everyone` from the UI. `women`/`men` parse and round-trip from day one —
they just are not offered until Phase C, so the wire format never changes again.

### 1.2 Behaviour

`censorFaces` substitutes for `censorWomen` verbatim at every branch — `Eta.kt:87,89`,
`Preflight.kt:63`, `FilterWorker.kt:144,172,203,226`, `ShareSheet.kt:123`, `DownloadWorker.kt:41`.
Nothing about the pipeline changes: `everyone` is exactly today's code path.

---

## 2. UI / UX design

Two rules the app already lives by decide this: **step 1 is *what*, step 2 is *how*** (`PickOpsScreen`
holds only `removeMusic`/faces; everything else is `OptionsScreen`), and **one card, two rows** on the
pick screen (`PickOpsScreen.kt:130-147`).

### 2.1 Step 1 — unchanged control, honest subtitle

The faces `ToggleTile` stays a toggle. Off = **none**, on = whatever Who is set to. Its `desc` becomes
the current choice instead of a fixed claim:

```
┌─────────────────────────────────────────────┐
│  🎵  Remove music                      (o )  │
│      Removes the soundtrack, keeps dialogue. │
├─────────────────────────────────────────────┤
│  🛡  Censor faces                      ( o)  │
│      Everyone · and flagged scenes           │   ← "Women only ·", "Men only ·"
└─────────────────────────────────────────────┘
```

Costs one string, adds no control, and step 1 never again states something the settings contradict.
Today's `pick_op_faces_desc` = "Blur every face and flagged scenes" — which becomes a lie the moment
Women/Men exists, so it has to change regardless.

### 2.2 Step 2 — one segmented row, first in the section it belongs to

`OptionsScreen.kt:185` already renders a whole "Censor faces" card only when censoring is on. Add one
row at its top, above Strictness, using the same `SingleChoiceSegmentedButtonRow` as
`CensorStyleRow` (`OptionsScreen.kt:270`) — no new component, no new pattern:

```
Censor faces
┌─────────────────────────────────────────────┐
│  Who                                         │
│  Which faces get covered. Flagged scenes are │
│  censored either way.                        │
│  ┌──────────┬──────────┬──────────┐          │
│  │ Everyone │  Women   │   Men    │          │
│  └──────────┴──────────┴──────────┘          │
├─────────────────────────────────────────────┤
│  Strictness                            50    │
│  ...                                         │
```

Three segments × on/off toggle = the four states, with one new row. Why here and not four chips on
step 1: four chips break the two-row card, and "which faces" is a *setting for* censoring, not a
different job — the same reason Strictness and Censor style are already here.

Ordering: Who is first because it is the largest decision in the section; Strictness/Style/Blur tune
what Who selected.

### 2.3 Share sheet — untouched

`ShareSheet.kt:250` keeps its boolean toggle; Who comes from `Prefs`. The sheet is the fast path, and
adding a three-way there costs vertical space on a bottom sheet to re-ask a question the user answered
once. Change it only if usage shows people flipping Who per-share.

### 2.4 Strings (en + ar, 5 new/changed)

`opt_who_title` "Who" · `opt_who_desc` "Which faces get covered. Flagged scenes are censored either
way." · `opt_who_everyone` "Everyone" · `opt_who_women` "Women" · `opt_who_men` "Men" · and
`pick_op_faces_desc` becomes `"%1$s · and flagged scenes"`.

Arabic must be written, not machine-defaulted — `values-ar` is complete today and this is the primary
audience. The segment labels are two words each; the section is RTL-safe because the segmented row
already is.

**Do not re-add the "Advanced" expander.** It was removed with `blurUnknownFaces` (`OptionsScreen.kt:212`)
and unknown-face handling is now a fixed rule (§5.2), not a control.

---

## 3. Phase B — the classifier gate (go / no-go)

This phase produces **a number, not code.** Nothing in Phase C starts until it passes.

### 3.1 The candidate — InsightFace `genderage.onnx` (decided 2026-08-03)

No native option exists: ML Kit Face API exposes smile/eye-open probabilities and tracking, no gender;
MediaPipe likewise. So this is an ONNX asset in the existing ORT runtime (`ml/Infer.kt`, `ml/Models.kt`)
— no new dependency either way.

**Licence is no longer a gate** (owner's call, 2026-08-03), which was this section's only objection to
the obvious candidate. Measure `genderage.onnx` from the buffalo_l pack — **1.3 MB, input
`[1,3,96,96]`, output `[1,3]`**. Three reasons it and not the others:

1. **Its preprocessing is already in this code.** InsightFace crops `max(w,h) × 1.5` centred on the box,
   no rotation. `KEYFRAME_PAD = 0.25f` ⇒ 1.5× per dimension (`FaceTracker.kt:165`). Same number — square
   the padded rect and the model gets what it trained on.
2. **No landmarks needed.** That centre+scale transform means `PERFORMANCE_MODE_FAST` + `enableTracking()`
   stays exactly as it is (`FaceTracker.kt:143-146`). Any ArcFace-aligned model would force
   `LANDMARK_MODE_ALL` and tax *detection on every frame* — a cost §5 never budgeted.
3. **~1 ms/crop**, anchored to this repo's own S23 number rather than a spec sheet: `gate=26844 ms` over
   643 s at 10 fps = 4.2 ms/frame for MobileNetV2-1.4 @224² INT8 (`Models.kt`, `NSFW_GATE`). genderage is
   ~1/10th those FLOPs at 96².

Contract to lock when it lands: `argmax(out[0..1])` where **1 = male, 0 = female**, and `out[2] × 100`
= age. Verify against insightface's own `attribute.py` on the §3.2 crops before trusting a Kotlin port —
that file also resolves which input normalization the graph wants, which is not guessable from the file.

Rejected, one line each:

| Candidate | Why not |
|---|---|
| `gender_googlenet` (Adience) | 23 MB, 224², ~1.5 GFLOPs ⇒ ~17–25 ms/crop ⇒ **~300–400 s** added to analyze, 6–8× §5's budget. Its 86.8 % is scored on Adience's *own clean portraits*; §3.2's crops are nothing like that. Fails the bar before any code is written. |
| FairFace (ResNet-34) | ~90 MB for a secondary feature, 5× the NSFW gate, plus session load and RSS. |
| MiVOLO | ViT, ~90–200 MB, wants a body crop as well as a face. Out of budget by an order of magnitude. |
| Fine-tune MobileNetV3-Small | Not a competing choice — it is what §3.2's labelled set *enables* if genderage misses the bar. Never start here. |

**On the NudeNet precedent:** `FACE_FEMALE` is a detection class inside a *nudity* detector, trained
where female faces dominate the distribution. 0.69–0.83 on Einstein is what that model is built to do —
it was never trained to discriminate. §3.3 is therefore a gate this repo has not yet attempted, not one
it has already failed.

### 3.2 The measurement that actually matters

The last failure was found because someone fed it three famous portraits. Do better:

1. Build the eval set from **ML Kit crops of real content**, not portraits — run pass 1 over the
   `qa-assets` clips (`women-music-3min` is the workhorse) and dump the padded crops `padRect()`
   produces. That distribution is small, motion-blurred, off-angle, and badly lit; portrait accuracy
   predicts nothing about it.
2. Label per **track**, not per frame — the vote is per track, so the score must be too.
3. Report **balanced accuracy per class plus the confusion matrix**. A single accuracy number hides
   exactly the failure NudeNet had.
4. **Stratify by crop pixel size.** The input is 96²; a 30 px face upsampled to it carries no signal.
   Band the results (<40 px, 40–80, ≥80) — that is what separates "the model is weak" from "half these
   crops were never classifiable", and it is where §4.2's size floor comes from.
5. **Run all of it in Python, off-device** — the same shape as the INT8 validation ("360 real frames
   from `test-video-1.webm`", `Models.kt`). Port to Kotlin only after the number passes; a port written
   before the gate is code written for a model that may not ship.

### 3.3 Pass bar

**Accuracy alone cannot decide this.** §4.2 censors unknowns, so Women and Men blur the same faces
wherever the model abstains — a classifier that abstains on 80 % of tracks and is 99 % accurate on the
rest passes an accuracy bar and ships a picker that does nothing. Measure both halves:

| Metric | Bar |
|---|---|
| Balanced accuracy per class, over tracks that **do** vote | ≥ 90 %, and neither class collapses (the NudeNet failure: `FACE_MALE` ≤ 0.07 on male faces) |
| **Men left visible in Women mode** = `(1 − abstain) × male_accuracy` | **≥ 70 %** |
| **Women left exposed in Women mode** = `(1 − abstain) × (1 − female_accuracy)` | as low as the row above allows |

The middle row is the only one a user can feel. Its shape:

| abstain | accuracy | men visible | women exposed |
|---:|---:|---:|---:|
| 10 % | 95 % | 86 % | 4.5 % |
| 30 % | 90 % | **63 %** | 7 % |
| 60 % | 90 % | 36 % | 4 % |

At 36 % the user flips to Women, sees nearly everyone blurred anyway, and concludes it is broken. That
is Everyone wearing a different label — the exact state this plan exists to fix.

**So Phase B's deliverable is a curve, not a number.** §4.2's confidence floor moves both rows together:
raise it and abstentions rise, both fall toward Everyone; lower it and both rise. Same shape as
Strictness on the NSFW gate. Sweep the floor, pick the point where *women exposed* is acceptably small,
then read off *men visible*. If no point on the curve clears 70 % alongside the 90 %, stop: Phase A's
toggle is a correct product, a broken selector is not.

Also record: ms per crop on the S23, and peak RSS delta. Budget in §5.

---

## 4. Phase C — the vote (only if §3.3 passes)

### 4.1 Where it runs

Inside pass 1, in `FaceTracker.onFaces()`, on the same crop the detector just produced — **never
retained**. The old implementation held crop bitmaps for the whole pass (~500 MB); this one holds
**two ints per live track**:

```kotlin
class FaceTrack(val id: Int) {
    val samples = mutableListOf<FaceSample>()
    var femaleVotes = 0; var maleVotes = 0     // ponytail: two counters, not crops. That is the 500 MB fix.
}
```

Skipped entirely when `censorWho == "everyone"` or `"none"` — the default path costs zero, so no
existing user pays for this feature.

### 4.2 Decision rule

- Classify at most **`VOTE_CAP = 5` samples per track**, then stop — cost becomes per-*track*, not
  per-frame, and a face seen for 90 s costs the same as one seen for 0.5 s.
- **Spend those 5 on the biggest crops**: classify a sample only if it is larger than any already
  classified in that track. ~2 lines, still streaming, and it aims the budget at §3.2's size bands
  instead of at whichever 5 frames the track happened to start with.
- Below §3.2's size floor, do not classify at all — no vote cast, which already means censor.
- Per-crop verdict counts only above a confidence floor; low-confidence crops vote for nobody. **That
  floor is read off §3.3's curve, not guessed** — it is the dial trading "men visible" against "women
  exposed".
- Track verdict = majority of cast votes. **Zero votes cast → censor.** Ambiguous (tie) → censor.
- Censor if `verdict == censorWho || verdict == unknown`.

Uncertainty always resolves toward censoring. That is this app's safe direction and it is what the PRD
already accepted for the old vote — with the difference that here it is a stated rule with a test,
not an accident of a biased model.

### 4.3 Where the verdict lands

`edlFor(track)` returns null for a track that should not be censored — one branch, and the EDL, the
renderer, `CensorEffect` and the whole pass-2 path stay untouched. Sequence: eviction (`sweep`, already
in place) is what closes a track, and the verdict is read there, so no track is judged before it is over.

---

## 5. Cost

Analyze is 27 % of a film's wall and is gate-bound (`naqi-analyze-is-gate-bound`). Adding per-face work
to it is the risk, which is what `VOTE_CAP` bounds.

Order of magnitude on the 155-min film (3 362 tracks measured pre-eviction): 3 362 × 5 crops × ~1 ms
(§3.1) ≈ **~17 s added to ~70 min of analyze**, well under 1 % — but hold the old ~3 ms/crop as the
budget until the S23 measures it, which is ~50 s and still passes. A 90 MB model would additionally cost
session load and RSS — a second reason §3.1 prefers a small one.

`Eta.CENSOR`/`COMBINED` should be re-measured after Phase C, not adjusted by arithmetic
(`Eta.kt:87,89`). If measured cost exceeds ~5 % of analyze, drop `VOTE_CAP` to 3 before anything else.

---

## 6. Tests

One JVM unit test file, alongside `FaceTrackerLogicTest` (`app/src/test/.../analysis/`), on the pure
decision rule extracted as `internal fun shouldCensor(femaleVotes: Int, maleVotes: Int, who: String): Boolean`:

- everyone → true regardless of counters
- women, 4F/1M → true · women, 1F/4M → false · men mirrored
- **0/0 → true** (the fail-safe; this is the one that must never silently flip)
- tie 2/2 → true

Plus one round-trip test that `queue.json` written with the old `censorWomen: true` loads as
`everyone` (§1.1).

Gate as always: `compileDebugKotlin` + `testDebugUnitTest` (`naqi-build-gates`).

---

## 7. Not building

- **Re-adding NudeNet.** AGPL, and it is the model that failed this task.
- **Per-face manual override / tap-to-exclude.** Needs a preview scrubber and a per-track UI; nobody has asked.
- **A "blur unknown faces" control.** Fixed rule now (§4.2). It was an expander over one checkbox before.
- **Who in the share sheet** (§2.3).
- **Gender-aware scene censoring.** The NSFW gate is whole-frame and has no notion of who is in it; Strictness stays gender-blind and the UI says so.

---

## 8. Honest caveats

Classifying a face crop into two classes is a guess about appearance from a few dozen pixels of a
moving, often side-on face. It will be wrong on children, on partial occlusion, at distance, and on
anyone whose presentation does not match the training labels. §4.2's rule means those errors censor
rather than expose, which is the right direction for this app — but "Women" will still cover some men
and, less often, miss someone. The Options copy should not promise more than that.

**The fail-safe is identical in both modes, and that is the risk.** Unknown → censor means Women blurs
female + unknown while Men blurs male + unknown, so on every face the model cannot read, the two modes
do the same thing — and if unknowns dominate, both collapse into Everyone under a different label. That
is the NudeNet outcome arriving through a different door: not a biased model this time, just the
fail-safe eating everything. The rule itself is right — uncertainty should cover, whichever user is
asking — so the answer is not to weaken it but to measure it, which is what §3.3's second row is for.

Faces the detector never finds — back turned, too far, occluded — are censored in **no** mode, Everyone
included. That is a standing limit of a *face*-censoring feature and this plan does not change it;
whole-frame coverage is the NSFW gate's job and it is gender-blind (§7).

The kill criterion is §3.3. Shipping a selector that quietly censors everyone is worse than not
shipping one, because it makes the app lie about what it did — the state this plan exists to fix.

---

## 9. What was actually built and measured (2026-08-03)

### 9.1 Phase B result — the gate passed

§3.2 asks for ML Kit crops of real content, labelled per track, banded by size, run off-device. It was
run **on-device instead**, which is strictly better: `FilterWorker.dumpCrop` writes the exact 96²
tensors the vote classified, named with the model's own p(male), the face's upright pixel size and the
raw ML Kit box. Enabled by `mkdir files/dump-crops` — no intent extra, no `Data` key, no wire change.
**479 crops** from `test-video-1.webm` and `a week in my life vlog.webm`, hand-labelled off contact
sheets.

The floor sweep — §3.3 asked for a curve, so here is the curve:

| CONF_FLOOR | abstain | female acc | male acc | balanced | women exposed | men visible |
|---:|---:|---:|---:|---:|---:|---:|
| 0.50 | 0.0 % | 94.1 % | 80.0 % | 87.1 % | 5.9 % | 80.0 % |
| **0.60** | **2.5 %** | **95.1 %** | **88.9 %** | **92.0 %** | **4.8 %** | **80.0 %** |
| 0.70 | 5.4 % | 96.7 % | 88.9 % | 92.8 % | 3.1 % | 80.0 % |
| 0.80 | 12.0 % | 98.1 % | 100 % | 99.1 % | 1.7 % | 70.0 % |
| 0.95 | 23.2 % | 99.6 % | 100 % | 99.8 % | 0.3 % | 50.0 % |

Against §3.3's bars at the shipped 0.60: balanced accuracy **92.0 % ≥ 90 %** with neither class
collapsed, and men visible **80 % ≥ 70 %**. Both pass. 0.80 is the alternative operating point and is
strictly better on paper — but "men visible" there is 7 of 10 crops against 8 of 10, i.e. one crop, so
0.60 is kept until a bigger male sample can separate them.

**The honest limit: n(male) = 10 crops across 3 tracks.** Everything about the male column rests on
that. The female column (357 crops) is solid; the male one is an indication. This is a source problem,
not a method problem — `qa-assets` is two vlogs by women. §3.3's middle row cannot be called properly
until a clip with several men goes through the same harness.

Two findings the plan did not anticipate:

1. **23 % of everything the vote classified is not a face.** ML Kit false-positives on a protein tub, a
   taxi wheel, earrings, a snack bag, and — confidently, at p(male) = 1.00 — **a dog**. At floor 0.60,
   41 of 112 junk crops vote male, and in Women mode a male vote is what *spares* a track. So `spared`
   is substantially made of objects, and the count cannot be read as "men found". Harmless (nothing is
   left uncovered that a viewer would call a face) but it means §3.3's second row can never be measured
   from `spared` alone — it needs the labelled crops, which is why the dump hook is worth keeping.
2. **ML Kit boxes routinely extend outside the frame** — 147 of 240 crops in one clip had a negative or
   >1 edge, and the median crop had **23.7 % of its 1.5× square outside the frame**, filled by edge
   replication. It does not appear to hurt (those crops still score correctly), but it is the most
   likely explanation for the one male track the model got wrong: an older man in profile whose crop was
   mostly clamped.

### 9.2 Does the selector actually select? Yes — S23, `test-video-1.webm`, one run per mode

The end-to-end proof, on a clip that is predominantly one woman with a few men. `pm clear` between
runs, because the job key includes `censorWho` now, so re-running a finished job **resumes its
checkpoint** instead of re-analyzing (right for the product, useless for measurement — it cost a
misleading `crops=12` reading before this was understood):

| mode | crops | abstained | tracks censored | spared | ms/crop |
|---|---:|---:|---:|---:|---:|
| everyone | 0 | — | 126 / 126 | 0 | — |
| **women** | 281 | 6.8 % | 134 / 151 | **17** | 4.87 |
| **men** | 285 | 3.5 % | 69 / 136 | **67** | 5.01 |

Men spares ~4× what Women spares on female-dominated content, and Everyone censors everything. That is
the feature working, and it is the one result no accuracy table can substitute for. (Track counts differ
run to run because ML Kit is not deterministic — `Models.kt`'s NSFW_GATE KDoc — so read the ratio, not
the absolute.)

### 9.2b Cost — §5 was 5× optimistic per crop, and it does not matter

**4.87–5.01 ms/crop**, 281 crops, **1.4 s against a ~150 s analyze pass = 0.9 %** — well inside §5's
"drop `VOTE_CAP` to 3 above ~5 %" trigger, so `VOTE_CAP` stays 5. §3.1's ~1 ms/crop estimate was wrong
by 5×; `VOTE_CAP` bounding cost per *track* is what makes that irrelevant, which is the thing §5 got
right. The figure includes the crop fill, not just `session.run`.

On the 19-minute vlog the same measurement was 690 crops / 3.46 s against 196 s = 1.8 %. Do **not** read
that run's wall-clock delta (196 s → 278 s) as the feature's cost: `nv21`, `gateFill`, `detect` and
`gate` all rose ~40 % in the same run, which is the back-to-back thermal effect
`naqi-perf-v2-settled` warns about. The directly-measured vote total is the real number.

### 9.3 Deviations from this plan

| § | Planned | Shipped | Why |
|---|---|---|---|
| 1.1 | `censorWho` defaults to `EVERYONE` | **`NONE`** | `Queue.kt:56`, `NaqiApp.kt:37` and `EtaTest.kt:62` all need a bare `FilterOps()` to have `any == false`. Entry points that should open with censoring on default it themselves. |
| 4.2 | `MIN_FACE_PX` unspecified, "read off §3.2" | **80** | The band table in `FaceTracker.MIN_FACE_PX`: 40–80 px is 76.9 % correct against ~96 % above it, and is only ~8 % of crops. |
| 4.2 | — | untracked detections are **never** classified | A detection with no ML Kit tracking id gets a fresh synthetic id every frame, so `VOTE_CAP` cannot bound it and §5's per-track cost argument collapses on fast-cut content. One crop is also the weakest possible evidence, and no vote means censor. |
| 2.1 | toggle-off remembers the pick | screen-local `remember`, share sheet seeds from the loaded ops | Off is `NONE`, which erases *which*, so both toggles restore the last real pick instead of hard-coding Everyone. Known ceiling, commented at both sites: `NaqiApp` swaps screens with a bare `when` and no `SaveableStateHolder`, so a detour through Options while faces are OFF resets it — to Everyone, the safe direction. Hoist into `NaqiApp`'s `ops` state if that detour turns out to be a real path. |
| 2.4 | `pick_op_faces_desc` always substituted | second string for the **off** state | `ToggleTile` renders `desc` at full emphasis when unchecked, so the substituted line would assert censoring that is not running. |
| 6 | — | `dump-crops` hook + `spared`/`spared1`/`abstained` counters | §3.2's measurement has to be repeatable on new content; it is the only way to re-check the male column. |

### 9.4 Still open

- **Male sample size.** The one number this feature lives or dies by rests on 3 tracks. Run the dump
  hook over a clip with several men before believing 80 %.
- **A "needs two concurring votes to spare" rule.** Measured with a temporary counter (not in the
  shipped build): **10 of 17 spares in Women mode rest on a single vote (59 %)**, against 15 of 67 in
  Men mode (22 %). A spare is the only outcome that leaves a face uncovered, and in Women mode the
  majority of them are one crop deep — which is also where §9.1's junk detections land. Requiring two
  concurring votes would remove them, at the cost of censoring men who are only seen briefly. Not
  built: it moves §3.3's headline metric, and with n(male) = 3 tracks there is no way to tell whether
  it helps or hurts. Re-measure it first with the clip §9.4's first bullet asks for.
- **`Eta.CENSOR`/`COMBINED`** still describe Everyone's cost (§5 says re-measure, do not compute).
- **No download path for `genderage.onnx`** — `downloadUrl` is null, so a build shipped without the
  asset can never fetch it. It degrades to "no vote cast" ⇒ censor, i.e. to Everyone, which is safe;
  decide before release whether it belongs on `NAQI_MODEL_BASE_URL`.
