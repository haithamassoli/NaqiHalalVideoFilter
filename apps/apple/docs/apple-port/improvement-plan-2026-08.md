# Improvement plan — 2026-08

What the Android build has that this one does not, what to take, what to refuse, and where the
performance actually is.

Scope: `/Users/goldentik/Documents/naqi` (Swift, 9 808 lines) against
`/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter` (Kotlin, 12 934 lines). Every claim
below cites a `file:line` in one of the two trees or a measurement already checked in under
`docs/apple-port/`.

---

## 0. The verdict

The port is ahead of Android on correctness — it has the gender vote Android deleted
(`plan-censor-who` §3 never cleared its go/no-go bar; `GenderVote.swift` ships), the two branches
already run concurrently (`JobRunner.swift:263`), and the checkpoint/resume story survived a SIGKILL
soak. It is behind on three things, and only one of them is a port:

1. **The biggest perf win in the repo is already measured and not wired.**
   `spec-inference-apple.md` D2 measured htdemucs at **128.8 ms/chunk on the CoreML EP against
   554.8 ms on the best CPU config — 4.31×**. `Demucs.swift:323` opens it with `compute: .cpu`.
   Same for D6 (NSFW gate, 0.667 ms vs 2.27 ms) and D7 (genderage, XNNPACK 4 threads).
   `AnalyzePass.swift:68,71` take the `.cpu` default. Nothing on Android is competing for this
   lever — Android has no ANE and no CoreML — so it is pure Apple-side headroom sitting unclaimed.

2. **The YAMNet music gate was never ported.** `Demucs.swift:35` reserves the ±2-chunk dilation
   window in the ring geometry and says so: *"No gate is wired yet (yamnet is not bundled)."*
   Separation is **82.7 %** of the wall on a music job (`perf-plan-v5.md` §8). The gate costs ~1 %
   of the separator and skips whole runs of music-free chunks.

3. **The user cannot see the queue, and the job dies when the app leaves the foreground.**
   `JobMonitor.swift:22` is explicit: *"ponytail: a count, not a queue screen."* `ProgressScreen`
   tells the user to keep the app open. Android answers both with `JobsScreen.kt` and WorkManager;
   iOS has `BGProcessingTaskRequest`, which is Apple's sanctioned answer and fits the existing
   checkpoint design exactly.

Everything else is small.

---

## 1. Do not port

Four Android features are load-bearing there and are submission-enders here.

| Android | Where | Why it cannot ship on the App Store | Instead |
|---|---|---|---|
| **Download by link (yt-dlp)** | `download/Downloader.kt`, `DownloadWorker.kt` (437 lines) | 2.5.2 — yt-dlp fetches and executes updated Python at runtime. Also 5.2.3 (third-party content) and 4.7's interpreted-code carve-out does not cover a downloader. Android's own README says Play bans it too. | Nothing. The share extension already covers "get a video in". A user who wants a YouTube video downloads it with something else and shares it in. |
| **In-app updater** | `update/AppUpdate.kt`, `ui/UpdateCard.kt` (491 lines) | 2.4.5(iv) / 3.2.2(v) — apps must update through the App Store, full stop. Downloading an APK-equivalent is an automatic rejection. | TestFlight for betas. Nothing in-product. |
| **GPL-3.0-or-later** | Android `README.md`, forced by linking youtubedl-android | The port already claims it: `Localizable.xcstrings` `about_license` reads *"Licensed under GPL-3.0-or-later."* in EN and AR — **and there is no `LICENSE` file in this repo at all.** GPL-3's anti-tivoization terms conflict with the App Store's usage terms; this is the VLC removal, and it is a live takedown vector, not a theory. The port links no GPL code — ONNX Runtime is MIT, Vision and AVFoundation are Apple's. | Pick a licence that is actually true of this tree, add the `LICENSE` file, and fix the About string. The GPL was inherited from a dependency that does not exist here. |
| **A background mode to keep jobs running** | — (Android uses a foreground service, which has no iOS analogue) | Declaring `audio` or `location` in `UIBackgroundModes` to keep a transcode alive is 2.5.4, and it is one of the most reliably caught rejections there is. | `BGProcessingTaskRequest` — see U2. |

### The one that is not about Apple's rules but will still stop a submission

**Two of the three bundled model weights are not licensed for commercial distribution.**

- InsightFace `genderage` (buffalo_l) — code MIT, **weights are research-use only**.
- `nsfw_model` MobileNetV2 — **NOASSERTION** upstream.

Android's `NOTICE` flags this and its README says outright: *"read it before redistributing this app
or shipping it to an app store."* Android ships as an APK on GitHub Releases; the App Store is
commercial distribution and a different question. This tree has no `NOTICE` and no third-party
licence screen — Android surfaces one under About → Open source licenses, `AboutScreen.swift` does
not.

This is a ship blocker with three exits, in order of preference:

1. Replace `genderage` with permissively-licensed weights, and re-run the vote's accuracy bar.
2. Drop women/men and ship **everyone** only (see U4) — deletes the genderage dependency entirely,
   costs 1.3 MB and the whole `GenderVote.swift` code path, and is the *stricter* product anyway.
3. Get written permission from both upstreams.

Do this before M7's submission checklist, not during it.

---

## 2. Performance

Ranked by measured or bounded value. Items marked **[measured]** have a number in a checked-in
document; **[bounded]** means the ceiling is known but the delivery is not.

### P1 — Wire the CoreML EP. **[measured, 4.31× on htdemucs]**

`spec-inference-apple.md` §0 already decided this and §4 already measured it. The code does not do
any of it.

| Decision | Spec | Code today |
|---|---|---|
| D2 htdemucs: CoreML EP, `MLComputeUnits=CPUAndGPU`, `ModelFormat=MLProgram`, `RequireStaticInputShapes=1` | 128.8 ms/chunk vs 554.8 ms — 4.31×, 18.2× realtime | `Demucs.swift:323` → `compute: .cpu` |
| D4 `ModelCacheDirectory` in Application Support, excluded from backup | Cold compile 33–72 s, warm 10–24 s. Without it, every session create pays full compile | not implemented |
| D5 never `MLComputeUnits=ALL` | ANE compile fails; `ALL` is 2.3× slower than `CPUAndGPU` | `ComputeUnit.coreML` is ANE+GPU+CPU, i.e. exactly what D5 forbids (`Ort.swift:10`) |
| D6 NSFW gate: CoreML EP, `ModelFormat=NeuralNetwork`, batch frozen to 1 | 0.667 ms vs 2.27 ms — **inverts** Android's INT8-on-CPU choice | `AnalyzePass.swift:68` → CPU, 2 threads; graph's batch dim is still dynamic (`Models.swift:22`) |
| D7 genderage + YAMNet: XNNPACK EP, `intra_op_num_threads=4`, batch frozen to 1 | 0.263 ms / 1.50 ms. CoreML's 120–1000 ms compile is not repayable at these run times | `AnalyzePass.swift:71` → CPU default, 1 thread |
| D9 disable CoreML on the simulator | CoreML EP registers there, has no ANE, and is 2–5× slower in every config | `ComputeUnit` has no simulator gate |

`Ort.swift` exposes only `useCPUOnly` / `useCPUAndGPU` style flags (V1
`ORTCoreMLExecutionProviderOptions`). D2 needs the **V2 options dictionary** —
`appendCoreMLExecutionProviderWithOptionsV2:error:` — which is where `ModelFormat`,
`RequireStaticInputShapes` and `ModelCacheDirectory` live. That plumbing is the actual work; the
call sites are one line each.

**Gate before building on it.** `spec-inference-apple.md` names its own riskiest open item: D2/D3
were validated on an M3 Mac, not an iPhone. `tasks.md:42` still carries `[~] device run
outstanding`. Re-run `docs/apple-port/bench/parity.cc` on a physical iPhone **first**. Everything
else in this plan degrades gracefully; this one does not, and the 443 MB compiled cache and 33–72 s
compile are both worse on device than on the Mac.

Two guards that must survive the change:

- `integration-hazards.md` §10 and `spec-audio.md` §8/§10: XNNPACK's fp16 kernels corrupted
  htdemucs' spectral branch on Android, and **CoreML is another fp16 path**. The measured parity is
  46.3/61.1 dB for fp16-on-GPU against 86.6/112.5 dB for fp32 (D3). Re-validate the spectral branch
  with the existing parity harness, not by ear.
- Keep the CPU EP as the correctness reference (D9) and keep `ModelSmoke` load-checking each model
  under the exact options it will infer under — `Ort.swift:175` already documents why the session
  cache keys on `(file, compute, threads)`.

### P2 — Port the YAMNet music gate. **[measured on Android; ~1 % of separator cost]**

Straight port of `audio/MusicGate.kt` (198 lines) plus the two-tier dilation in
`DemucsSeparator.separateChunk`. The Swift side is already shaped for it: `Demucs.swift:36-38`
reserves `lookahead = 2 * stride` in `inCap` specifically so the gate's ±2-chunk window exists.

Carry these across unchanged, because each one is a decision and not an implementation detail:

- Threshold **0.15**, and the reason it is not delicate: measured silence 0.0000, white noise
  0.0240, synthesized chord 0.9880 — 0.15 sits in the empty middle of a bimodal distribution.
- **MAX over frames, not mean.** Frames tiled at 15 600 samples with the last frame flush against
  the end, so a 2.6 s chunk has no unscored tail.
- AudioSet class ranges `132...276` and `24...32`, inclusive both ends. The second range is vocal
  music and is not optional — it is the only thing that catches a-cappella singing.
- Silence floor at −60 dBFS peak, scored 0 without a model run.
- **Fail open.** `open()` returning nil means "separate everything", i.e. exactly today's behaviour.
  A gate that guesses when it cannot load is the one failure mode the user cannot fix.
- Linear-interpolation 44 100 → 16 000. Android measured this against soxr: max score delta 0.13,
  one frame flips at a 0.5 threshold, nothing flips at 0.15.

iOS specifics: run it under D7 (XNNPACK, 4 threads, batch frozen to 1) at 1.50 ms — not CoreML,
whose compile cost is not repayable at that run time. +15 MB bundle (`spec-inference-apple.md` D8
sizes the bundle at ~122 MB against a 4 GB cap, so there is room).

Reuse `STFT.swift`'s resampling arithmetic if it fits; otherwise the interpolation is ~15 lines.

### P3 — Freeze the NSFW graph's batch dim to 1. **[measured, 0.667 ms vs 2.27 ms]**

Prerequisite for D6 — the CoreML EP wants static shapes. `Models.swift:22` documents the dynamic
batch dim as a batching opportunity; that opportunity is **dead** (see §5) and the dynamic dim is
now only a cost. Re-export with the batch frozen, drop `maxBatch`, delete `GateBatch`'s unused
batching mechanism or leave the three lines — either is fine, but the graph must change.

### P4 — Do not open genderage when the vote cannot run. **[free]**

`AnalyzePass.swift:71` opens the genderage session unconditionally. `FilterOps.Who.skipsGenderVote`
(`FilterOps.swift:34`) already exists and already answers "the verdict is known without running
genderage" for `.everyone` and `.none`. Guard the open on it. Saves a session, 1.3 MB resident, and
every crop/tensor/inference on a job that picked `everyone`. Currently invisible because the picker
does not offer `everyone` — it becomes real the moment U4 lands, and it is the free half of U4.

### P5 — An on/off for the NSFW gate. **[bounded: all of the gate's 5 fps]**

Android has `FilterOps.censorNsfw`; this tree does not — `FilterOps.swift` has `strictness` and no
way to turn the gate off, so it runs at 5 fps across every frame of every censor job whether or not
the user wants whole-frame censoring. Add the `Bool`, default `true` (Android's default), gate the
`GateBatch` construction and the sampler's gate lane on it.

This is a perf item *and* a UX item: "cover faces, don't cover scenes" is a coherent thing to want
and there is currently no way to ask for it.

### P6 — Solid fill instead of blur. **[bounded: skips the blur entirely]**

Android's `FilterOps.solidColor` with five opaque swatches (gray, black, white, navy, green) and
`0` meaning blur. `CensorEffect.swift` has blur + optional grayscale and no solid path.

On this side it is *cheaper* than what it replaces: the composite already exists, so a solid fill is
`CIConstantColorGenerator` in place of the blurred image, and it skips `BlurPlan`'s
downscale → Gaussian → magnify path per frame. Roughly ten lines in `CensorEffect`, one field in
`FilterOps`, one swatch row in `CensorSection`.

### P7 — Overlap analyze and render. **[bounded: −46 % on censor-only; unbuilt on both platforms]**

`perf-plan-v5.md` §4.2 is the largest remaining lever on either platform: render is 89 202 ms and
immovable on its own, so the only way past it is to stop running it *after* analyze. The horizon
proof survived all three skeptics.

**Do not implement it. Build the throwaway spike first, with the decision rule fixed in advance** —
that is Android's own protocol and it is the right one. Render a forced full-frame EDL concurrently
with the real pass 1, log `analyzeDone` and `wall`. Ship it if `analyzeDone ≤ 115 000` **and**
`wall ≤ 150 000`; record it dead if `wall ≥ 175 000`.

Four correctness fixes are mandatory before the real thing, and Android's list transfers because the
same shapes exist here:

1. **`RenderPass` resolves passthrough once, against the EDL it was handed.** A mechanical
   `edl: () -> Edl` conversion evaluates it against an empty live EDL and **publishes the source
   uncensored.** This is severe and it is the reason the spike must be throwaway.
2. The horizon is **2450 ms, not 2050** — a live track's span grows on its own, so `MIN_FULL_MS` and
   the eviction window compose.
3. Guard `who == .everyone`: the per-track gender vote is non-monotone mid-track, so a track can
   flip toward *less* censoring after frames are already rendered.
4. Guard `!removeMusic`: audio is the wall on that shape and this would steal cores from it.

Also note `AnalyzePass.swift:20-34`'s standing rule — analyze is deliberately not segmented and must
not become so. The horizon approach does not violate it; a segmented analyze would.

---

## 3. Ease of use

### U1 — A jobs screen

`JobMonitor.swift:22` already names the gap. Steal Android's `JobsScreen.kt` + `QueueSection.kt`
layout decisions verbatim, because they are the output of one round of "this was worse before":

- **One screen, not two.** The queue and the running job answer the same question — "what is Naqi
  doing?" — and splitting them makes the user pick which of two places to look.
- **One card, one 56 pt row per item.** It was a bordered card per item with title, state, error and
  a button row; three queued shares filled the screen before the running job was visible.
- **A status glyph carries the state** that used to need its own line.
- **Exactly one action per row.** Tapping the row opens a finished item, which frees the one action
  slot for Share.
- **"Clear finished" lives in the section header**, where it reads as a list action rather than a
  stray button under the last item.

Plus the half this tree has nowhere: **the list of what Naqi has already saved.** `DoneScreen` shows
the most recent output and nothing else, so a user who filtered three videos yesterday has no route
back to any of them from inside the app.

The data is already there — `JobQueue.Snapshot` carries `jobs`, `running` and `progress`, and
`JobQueue` already streams snapshots to observers. This is a view, not a subsystem.

Reachability: `RootView`'s `Step` enum takes one more case. Do not put it behind the overflow menu
with About and Diagnostics — it is the second most important screen in the app.

### U2 — Keep working when the app is backgrounded

Today: `Lifecycle.swift:11` is honest that there is no background mode for an hours-long transcode,
so it spends the ~30 s grace on a checkpoint and stops. `ProgressScreen.swift:35` tells the user to
keep the app open. On a 90-minute film that is the app's worst moment.

`BGProcessingTaskRequest` is Apple's answer and it is **not** a background mode — it is a scheduled
task, and long ML/media work is the use case it was built for. It lands cleanly on machinery that
already exists:

- The runner already checkpoints per segment (`Checkpoint.swift`), and `JobRunner.hasResumableWork`
  already answers whether there is anything to resume.
- `Flow.loadResumable()` already reads survivors out of the queue file and `PickScreen` already
  offers them.
- `Lifecycle.expire()` is already the exact place to submit the request: it fires when the grace runs
  out, which is when there is something worth scheduling.

Set `requiresExternalPower = true` (matches the existing "keep the phone plugged in" copy in
`dlg_long_job_body`) and `requiresNetworkConnectivity = false`. Add `BGTaskSchedulerPermittedIdentifiers`
to Info.plist. On wake, pull the head of the queue and resume from the checkpoint — the same path a
relaunch takes today.

Be honest in the UI about what this does and does not promise: iOS decides when the task runs and can
end it, so the copy is "Naqi will pick this up when your phone is charging and idle", not "it keeps
running". The existing `progress_keep_open` note stays for the foreground case.

### U3 — Options in the share extension

`RootView.swift` documents the current design: *"the extension deliberately carries no options"*, so
a shared-in video inherits last-used ops. Android's `ShareSheet.kt` opens with last-used ops **and
lets you change them before queueing** — and its own comment says why: *"the sheet always opens —
there is no zero-tap path — so the only thing that makes repeated shares bearable is that it opens
already set the way the user last left it."*

Inheriting silently is the wrong half of that. On an hour-long job, "I shared it with last week's
settings" is discovered an hour later. The extension already reads the App Group defaults
(`FilterOps.store` is the App Group suite), so a compact three-row sheet — the two op toggles and
Who — is a small addition to `ShareViewController` and removes the failure entirely.

Keep the destination out of it. That decision needs `Flow`'s probe (`sourceHasVideo`) and the
extension does not have one.

### U4 — Offer "Everyone" in the Who picker

`FilterOps.Who` has `.everyone` with `skipsGenderVote` wired, `Localized.swift:40` maps it to a
label, and `OptionsScreen.swift:160` iterates `userSelectable` which is `[.women, .men]`. Android's
README tells users in plain text: *"If you want the strictest possible result, pick everyone rather
than a gender."* On this build that answer is unreachable.

Three segments instead of two. It is the strictest option, it is the fastest option (P4 makes the
genderage session disappear), and it is the option that does not depend on a classifier measured at
~92 % balanced accuracy on a small internal set. It is also exit #2 in §1 if the genderage licence
does not resolve.

### U5 — Solid-fill swatches

The UX half of P6. Blur can be reversed by eye on a low-amount setting; a solid fill cannot. Five
swatches in a row under the blur slider, with the slider and grayscale going dead while a fill is
selected (Android's rule — one field carries both the mode and the colour).

### U6 — Accessibility and haptics

The whole UI has 16 accessibility modifiers. Some of it is genuinely good — `SliderRow` carries a
hint because *"Strictness, 50" on its own gives a direction to nothing*, and the Who segments were
raised to a 44 pt floor after they were found under the tap target at the *default* text size. The
gaps are the dynamic parts:

- The pass strip and `WavyProgress` need a label and a value, or VoiceOver gets a progress bar with
  no reading. `accessibilityValue` on a percentage that updates every second also wants
  `.updatesFrequently`.
- The job/queue rows from U1 need a combined label per row, not four separately-focusable fragments.
- `NaqiIcons` used decoratively next to a text label should be `.accessibilityHidden(true)`.

Zero `sensoryFeedback` in the tree. Two modifiers: `.success` when a job finishes, `.impact` on
Start. On a job whose result arrives an hour later, the finish deserves more than a silent screen
change — `Notify.done` covers the backgrounded case, this covers the watching case.

---

## 4. Order of work

Each phase has a gate. Do not start the next one until the gate is green.

**Phase 0 — unblock the measurement (do this first, it gates everything in §2)**
- Re-run `docs/apple-port/bench/parity.cc` on a physical iPhone. Record it in `m0-results.md`.
- Gate: D2's 4.31× either holds within ~30 % on device, or it does not and P1 gets rewritten around
  what does.

**Phase 1 — licensing (do this in parallel; it gates submission, not code)**
- Resolve `genderage` and `nsfw_model`, by the §1 exits in order.
- Add `LICENSE` and `NOTICE`. Fix the `about_license` string in both languages.
- Add a third-party licence screen under About, matching Android's.
- Gate: every bundled artifact has a licence that permits commercial distribution, in writing.

**Phase 2 — perf, in order**
- P1 (CoreML EP + V2 options + cache dir + simulator gate), guarded by the spectral-branch parity
  harness.
- P3 (freeze the NSFW batch dim) — prerequisite for D6, so it lands inside P1.
- P2 (YAMNet gate).
- P4 (genderage open guard) — lands with U4.
- Gate: `BenchTests` and the M5 SIGKILL/resume soak both still pass, and the parity harness reports
  no spectral regression.

**Phase 3 — UX**
- U1 (jobs screen), U2 (BGProcessingTask). These two are the ones users will notice.
- U4 (everyone), P5 (NSFW toggle), P6/U5 (solid fill) — three small `FilterOps` changes, one
  migration story. Do them in one pass: `FilterOps` is `Codable` and the queue file, the App Group
  defaults and the checkpoint all read it, so three separate migrations is three times the risk of
  one.
- U3 (share options), U6 (a11y + haptics).

**Phase 4 — the big lever, only if Phase 2 landed**
- P7's spike, with the decision rule written down before the spike runs.

---

## 5. Not doing, with the reason

Recording these so they are not re-proposed.

| Item | Why not |
|---|---|
| **Batch the NSFW gate** | Measured and rejected on both platforms. `AnalyzePass.swift:255` records the Apple measurement agreeing with Android's §10.16; a batch of 8 skipped the gate on seven frames out of eight, paid 8× on the ninth, and parked 4.8 MB of tensors meanwhile. `size` ships at 1. |
| **INT8 htdemucs** | `spec-inference-apple.md` F4 — Android measured **0.55× and 0.44×, i.e. slower**. No reason it inverts on Apple. |
| **INT8 NSFW model** (Android ships 4.9 MB, this ships 17.3 MB fp32) | D6 **inverts** the Android decision on purpose: fp32-on-CoreML is 0.667 ms against INT8-on-CPU's 2.27 ms. The 12.4 MB is bought back in speed. Revisit only if P1 dies on device. |
| **Concurrent htdemucs sessions** | F5 — Android measured +1.5 % for 2× RSS. |
| **coremltools → `.mlpackage`** | F1 — needs a PyTorch re-export; coremltools 9 has no ONNX converter. Weeks of work. Revisit only if CoreML-EP compile latency or the 443 MB cache proves unacceptable on device. |
| **On-Demand Resources / Background Assets for the models** | D8 — ~122 MB against a 4 GB cap. ODR is deprecated. Background Assets is the v2 lever and only becomes interesting if the fp32 htdemucs (D3, ~207 MB) crosses the 200 MB cellular threshold. |
| **Segmenting the analyze pass** | `AnalyzePass.swift:20` — a 5-minute cut lands mid-track, the two halves vote separately and can reach opposite verdicts, so the same face is censored either side of a seam and bare in between. Stage-level resume is the right trade and is what ships. |
| **Room/SwiftData for the queue** | Android's `Queue.kt` ponytail note applies unchanged: no query, no migration, no cross-process reader. `naqi-queue.json` rewritten whole is the whole concurrency story. |
| **A nav library** | `RootView`'s `Step` enum is a straight line. U1 adds one case to it. |

---

## 6. What this plan does not touch

- **The two-pass architecture.** Majority-vote gender needs the complete face track before its first
  frame can be rendered, and the censor pre-roll needs the NSFW timeline ahead of the encoder. P7 is
  the only item that goes near it, and it goes near it with a spike and a kill rule.
- **The concurrent branch schedule.** `JobRunner.bothBranches` already does what Android's
  `FilterWorker.branches` does.
- **Localization.** 150 keys, EN + AR, two untranslated strings and both are diagnostics literals
  (`""` and `"CoreML EP"`). Anything added by this plan needs both languages.
- **Eta.** Already ported with Android's bands. Carry over one honest note if anyone complains about
  the remainder: under the concurrent schedule the video branch hides inside the separator, so its
  honest share is ~7 points rather than `analyze + render`, and the bar runs ~13 points ahead
  mid-job. That is against ~43 before the bands were sized off measured wall share, so it is a
  known, bounded lie and not a bug.
