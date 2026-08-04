# PRD — Download & Share

> **Status: the download half is removed as of 1.3 (versionCode 8).** Only "share a video file"
> shipped forward — the `ACTION_SEND video/*` entry point, the sheet, and the queue. Everything about
> links, yt-dlp, quality selection, quarantine and audio-only downloads is gone, and so are the
> `youtubedl-android` dependencies (−49 MB of native libs). The code is preserved on
> `origin/feat/download-and-share`.
>
> Why: the bundled ffmpeg cannot link on 16 KB-page devices (five `libwebp*` ELFs inside
> `libffmpeg.zip.so` are `p_align 0x1000` and `libavcodec` needs two of them), 0.18.1 is the newest
> youtubedl-android so no version bump fixes it, and the bundled yt-dlp needs a runtime self-update
> before a single YouTube link resolves. See `docs/plan-16kb-webp.md`.
>
> Everything below documents the removed design. Read it as history.

Naqi gains two entry points it does not have today: share a **link** from any app and Naqi downloads it via yt-dlp; share a **video file** and Naqi filters it directly. Both land in one sheet that asks quality + which filters (remove music / blur women), remembers the last choice, and adds the item to a FIFO queue that survives process death. Downloaded originals are quarantined in app-private storage — only the filtered output reaches the gallery.

Reference product: [Seal](https://github.com/JunkFood02/Seal). Reference library: `io.github.junkfood02.youtubedl-android`.

---

## Decisions

| | |
|---|---|
| Distribution | GitHub APK (primary). F-Droid aspirational — see Risks. **Not Google Play** — Device & Network Abuse policy bans both ToS-violating downloads and runtime executable download |
| License | Repo becomes `GPL-3.0-or-later` (forced by linking youtubedl-android, GPL-3.0). yt-dlp itself is Unlicense, costs nothing |
| Share input | `ACTION_SEND` + `text/plain` (URL) and `ACTION_SEND` + `video/*` (file) |
| Share flow | Always show the sheet, pre-filled from last used. No zero-tap path |
| Quarantine | Download → `noBackupFilesDir/naqi-downloads/`. Published to `Movies/Naqi` only after filtering. Original deleted on success |
| ffmpeg | Bundled. All formats, all qualities, mp3/m4a extraction |
| aria2c | Not bundled. Saves 6.8 MB, yt-dlp's native downloader is sufficient |
| Concurrency | FIFO queue, persisted. Downloads and filtering run concurrently (network-bound vs CPU-bound); one of each at a time. WorkManager chains are the scheduler |
| yt-dlp updates | Weekly auto-check on app open + manual button on the About/licenses screen, `UpdateChannel.STABLE`. F-Droid would tag this a non-free-network anti-feature |
| Trust copy | "Nothing leaves your phone" → "Your videos never leave your phone" (EN + AR) |

## Non-goals

Playlists · subtitles · cookie/WebView login for private videos · custom yt-dlp command templates · SponsorBlock · proxy/rate-limit settings · `ACTION_VIEW` http handling · in-app browser · a separate share activity (Seal's overlay) · x86/armeabi ABIs · Google Play.

Seal's download queue, resume, and format-selection-from-share are **unreleased v2.0.0 code** (newest stable tag is v1.13.1, Oct 2024). Do not spec against them.

---

## Flows

### A. Share a link
1. `ACTION_SEND text/plain` → MainActivity. Regex-scrape the first http(s) URL from `EXTRA_TEXT` (shared text is rarely a bare URL).
2. No match → toast, return. URL matches an existing non-terminal queue item → toast "Already in queue", return.
3. Sheet opens **immediately** with the URL and a loading state; `YoutubeDL.getInfo(url)` runs off the main thread → title, duration, `filesize_approx`. Failure (unsupported site, geo-block, offline) → inline error + Retry.
4. Sheet: title · duration · **Quality** (Best / 720p / Audio only) · **Filters** (Remove music, Blur women) · Download. Duration ≥ 30 min → inline long-job warning (reuse `dlg_long_job_body` copy). First Download tap requests POST_NOTIFICATIONS on 33+ (same contract OptionsScreen already uses — without it the FGS notifications are invisible).
5. Free-space check, then append to queue as `PENDING_DOWNLOAD`. Sheet dismisses.

### B. Share a video file
`ACTION_SEND video/*` → `EXTRA_STREAM` content URI. Share grants die with the receiving task and are **not persistable** — so immediately `grantUriPermission(packageName, uri, FLAG_GRANT_READ_URI_PERMISSION)` (survives until reboot; revoke after publish). Same sheet minus Quality, primary button **Filter**, disabled when both filters are off (nothing to do — the file already exists). Queue as `PENDING_FILTER`; the filter request is appended immediately. No quarantine copy — the pipeline reads `content://` natively, source untouched as today. If the worker can no longer open the URI (reboot, provider quirk) → item FAILED "Re-share the file"; the rest of the queue proceeds.

### C. Queue drain
`PENDING_DOWNLOAD` → DownloadWorker → `PENDING_FILTER` → FilterWorker → `Movies/Naqi`, quarantine deleted.
Filters all off (link flow) → DownloadWorker publishes directly to `Movies/Naqi`, no filter job.

### Sheet
```
Title of the video
12:34 · youtube.com                    (link only)

Quality   ( Best ▾ )                   (link only)
Filters   [x] Remove music
          [x] Blur women

                    [ Cancel ]  [ Download ]
```
Quality maps to three format selectors — never render yt-dlp's raw format table:

| Choice | Selector |
|---|---|
| Best | `bv*+ba/b` |
| 720p | `bv*[height<=720]+ba/b[height<=720]` |
| Audio only | `ba/b` + `--extract-audio --audio-format m4a` |

Audio-only items: Blur women is hidden, Remove music still applies (see Audio-only jobs).

---

## Architecture

### The seam
`JobController.start(context, ops, inputUri, forceIntervalsMs, segmentMs): UUID` — `work/JobController.kt:21`. Every job in the app is created here. `file://` URIs already flow end-to-end (the debug autorun path proves it, `MainActivity.kt:145`).

### New components

| File | Purpose |
|---|---|
| `download/Downloader.kt` | Thin wrapper: `getInfo(url)`, `download(url, selector, destDir, onProgress)`. Owns `YoutubeDL.init()` |
| `download/DownloadWorker.kt` | CoroutineWorker, unique work `naqi_download`, notification id 1002, FGS type `dataSync`, constraint `NetworkType.CONNECTED` |
| `work/Queue.kt` | Single JSON file, `@Synchronized`, temp+rename write. Owns cancel-repair (below) |
| `ui/screen/ShareSheet.kt` | The sheet in flows A and B |
| `data/Prefs.kt` | Last-used `FilterOps` + quality. One JSON file |

### Entry point
Two intent-filters added to the existing `MainActivity` plus `android:launchMode="singleTask"`. Parsing goes in the existing `onCreate`/`onNewIntent` block that already handles `CONFIRM_DELETE_ORIGINAL` (`MainActivity.kt:66`). A shared payload sets a new `Step.Sheet` in the `NaqiApp` step machine (`ui/NaqiApp.kt:21`).

Accepted cost of `singleTask`: a share pulls the user into the Naqi task, and Back does not return to the source app. The fix is Seal's separate dialog-themed activity — cut for v1, revisit if it grates.

<!-- ponytail: no separate QuickDownloadActivity. Seal needs one for its transparent overlay; Naqi does not. Split it out only if the singleTask back-stack behavior proves confusing in practice. -->

### Scheduling
WorkManager chains are the queue's scheduler — no self-chaining, no polling. (Enqueuing your own unique name with `KEEP` from inside `doWork` is a silent no-op — the worker is still RUNNING — and would wedge the queue on the first item.)

- Every queue item = one WorkRequest, enqueued with `ExistingWorkPolicy.APPEND_OR_REPLACE` on `naqi_download` (downloads) or `naqi_filter_job` (filters). Chains persist across process death; FIFO for free.
- DownloadWorker appends the item's filter request to `naqi_filter_job` when its download completes (cross-name append, no race).
- **Queue-driven runs always return `Result.success()`** and record the real per-item outcome in `queue.json`. A chain must never carry a failure — WorkManager fails every request chained behind a failed one, so one bad download would kill the rest of the queue. Retry = re-append the same (uri, ops) → same jobKey → existing scratch/checkpoints found → effective resume.
- Per-item cancel of a running item: `cancelWorkById` cascades to dependents, so after cancelling, re-append all still-pending items of that chain (cancel-repair, ~5 lines in `Queue.kt`). Cancelled items are removed from `queue.json`.
- The legacy manual path (OptionsScreen → `KEEP` → failure + resumable semantics) is unchanged; the existing UI already disables Start while anything runs.

### Queue schema
`filesDir/queue.json`
```json
[{ "id": "…", "url": "…", "sourceUri": "…", "title": "…",
   "state": "PENDING_DOWNLOAD|DOWNLOADING|PENDING_FILTER|FILTERING|DONE|FAILED",
   "ops": { "removeMusic": true, "censorWomen": true, "…": null },
   "selector": "bv*+ba/b", "quarantinePath": "…", "error": null }]
```
<!-- ponytail: one JSON file, not Room. Both workers are in the same process; a @Synchronized object plus atomic rename covers it. Move to Room if the queue ever needs querying, migration, or cross-process access. -->

### Audio-only jobs
Net-new pipeline scope, not a freebie: `Preflight` refuses `NO_VIDEO` today, and every existing job shape assumes a video track (music-only *copies* it sample-for-sample). Audio-only is a fifth shape: relax Preflight for audio items → separator → AAC encode → publish `.m4a` via a `MediaStore.Audio` variant of `publish()` to `Music/Naqi`. Lands in M4.1.

---

## Prerequisite fixes

Two silent degradations on `file://` sources that today only the debug path hits. Both become user-facing the moment downloads land.

| File | Bug | Fix |
|---|---|---|
| `work/FilterWorker.kt:741` | Output name comes from `OpenableColumns.DISPLAY_NAME`, which returns null for `file://` → every downloaded video is published as `video-naqi-<ts>.mp4` | Fall back to `File(uri.path).name` before the literal `"video"` |
| `work/Preflight.kt:124` | `OpenableColumns.SIZE` returns null for `file://` → `getOrDefault(0L)` → the space check degrades to only the 2 GB slack | Fall back to `File(uri.path).length()` when scheme is `file` |

Also: `publish()` (`FilterWorker.kt:696`) must be extracted so DownloadWorker can reuse it for the no-filters case, and grow the `MediaStore.Audio` variant for audio-only jobs.

## Downloads

- Quarantine filename = sanitized, truncated video title via yt-dlp output template (id suffix on collision) — so the existing naming seam yields `<title>-naqi-<ts>.mp4` with zero seam changes.
- Failed downloads keep their `.part` file; yt-dlp resumes it automatically on retry.
- Orphaned quarantine files (item gone, filter never ran) are swept on the same 7-day rule as `naqi-work` (`JobStore.kt:61`).

## Storage

Preflight already demands `(tempCopies + 1) × source + extraScratch + 2 GB` (`work/Preflight.kt:45`). The quarantined download is one more full copy nobody budgets for today.

Before enqueueing a download, require:
```
filesize_approx × (tempCopies + 2) + 2 GB free
```
Refuse with a specific message, not a generic failure. `filesize_approx` is often null — then skip the precheck and have DownloadWorker abort early once yt-dlp reports total bytes. Segmented jobs already reach 3× source; a 1.3 GB film needs ~7 GB free before the download starts.

---

## Packaging

| | Bytes (arm64, uncompressed) |
|---|---|
| `youtubedl-android:library` (CPython + QuickJS + yt-dlp zipapp) | 15,226,392 |
| `youtubedl-android:ffmpeg` | 36,175,715 |
| `res/raw/ytdlp` | 3,170,726 |
| **Total added** | **~54.6 MB** |

These are already-compressed zip blobs, so the APK grows by roughly the full amount: **134 MB → ~186 MB.**

**Installed footprint is the real problem.** Today the app already copies its 117 MB of ONNX assets into `filesDir/models` (`ml/Models.kt:168`) — so a 134 MB APK is already ~250 MB installed. Adding extracted native libs plus Python/ffmpeg unzipping themselves at first run (`YoutubeDL.init()`) puts the install near **350–400 MB**.

Mitigation, independent of this PRD and already written: activate `ModelDownloader` (currently dead code, zero call sites, `NAQI_MODEL_BASE_URL` empty). Removing models from assets cuts ~105 MB from the APK and stops the double-store — installed drops to ~145 MB before the downloader is added. **Requires publishing a host for htdemucs and the NSFW gate, which do not have one.**

### The gate: `useLegacyPackaging`
`app/build.gradle.kts:51` sets `jniLibs { useLegacyPackaging = false }` because ONNX Runtime needs 16 KB-page libs. youtubedl-android requires `extractNativeLibs = true` — it reads `libpython.zip.so` out of `applicationInfo.nativeLibraryDir`, which contains no real files when libs stay in the APK.

Flipping to `true` extracts both and should satisfy ORT (16 KB alignment is a property of the `.so` ELF segments, not the APK layout), at the cost of ~54 MB of on-device duplication. **Unverified.** This is M4.0 and it gates everything below it.

Also unset: `packagingOptions` has no `pickFirst`/`exclude` rules, so any `.so` or `META-INF` collision between the ONNX Runtime AAR and youtubedl-android fails the build with nothing configured to resolve it.

`minSdk` is unaffected — youtubedl-android needs 24, the app is already 26.

---

## Cost

Measured on a Galaxy S23 (SD 8 Gen 2). Every number in the repo is from that device; the acceptance device is SD 778G-class and has never been available, so these read optimistically fast.

| Op | × realtime | 3-min clip | 2-h film |
|---|---|---|---|
| Blur women only | ~0.2–0.3 (0.45 at film length, pre-optimization) | ~1 min | ~55 min |
| Remove music only | ~1.1–1.15 | ~3.5 min | ~2.3 h |
| Both | ~1.2 | ~4 min | ~2.5–3 h |

Download time is negligible against this. **The filter is the product; the download is the cheap half.**

Two live hazards:
- `Eta.kt:38` still carries `CENSOR = 0.54` from before the −61% analyze win — the app over-quotes blur-only jobs by ~2×. Recalibrate or the queue's ETAs are wrong from day one.
- Foreground-service caps are 6 h per 24 h cumulative per app, **tracked per FGS type**. On API 35+ filtering (`mediaProcessing`) and downloads (`dataSync`) draw from separate budgets; on API 34 both run as `dataSync` and share one. Either way two films in one day cannot complete, and the `onTimeout` path has never been exercised.

---

## Trust & licensing

`res/values/strings.xml:13,14,21` and the Arabic parallel carry `On-device`, `Offline`, and *"Filter video on your device.\nNothing leaves your phone."* — rendered by `TrustSeal()` (`ui/screen/PickOpsScreen.kt:172`). The GitHub repo description says "Fully offline." `docs/store-listing.md:109` promises "No network usage."

New wording, EN + AR:
- Seal chips: `On-device` · `Private` (drop `Offline`)
- Tagline: *"Filtering happens on your device. Your videos never leave your phone."*
- Store listing: replace "No network usage" with "Downloads fetch from the source you chose. Nothing is uploaded, no accounts, no analytics."

Required, and currently missing entirely:
- `LICENSE` — `GPL-3.0-or-later`. The repo is public with **no license file at all** today, i.e. all-rights-reserved on world-readable source.
- `NOTICE` + an in-app "Open source licenses" screen (doubles as About: yt-dlp version + update button). There is no attribution infrastructure of any kind.
- **Inherited blocker:** NudeNet v3 320n is AGPL-3.0, is already shipping in the built AAB, and is flagged "review before shipping" in `docs/m0-spikes.md:29`, `scripts/fetch-models.sh:10` and `docs/tasks.md:13`. This PRD does not create that problem, but it cannot be shipped around it either.

---

## Milestones

**M4.0 — Packaging spike (gate).** Add `youtubedl-android:library` + `:ffmpeg`, flip `useLegacyPackaging = true`, run `ModelSmoke` on a 16 KB-page device (Pixel 8+/Android 15). ORT loads and htdemucs output is uncorrupted → proceed. It doesn't → the feature needs a different engine, and this milestone is where that's discovered, not M4.3.

**M4.1 — Download core.** `Downloader` + `DownloadWorker` (CONNECTED constraint, title-template quarantine naming) + the audio-only job shape (Preflight relax, `Music/Naqi` publish). No UI: a debug intent that takes a URL, downloads to quarantine, publishes. Verify against YouTube, TikTok, Instagram, X.

**M4.2 — Share entry + sheet.** Two intent-filters, `singleTask`, `Step.Sheet`, `Prefs`, POST_NOTIFICATIONS request, URI self-grant for shared files, dedupe. The prerequisite `file://` fixes land here. End-to-end: share a Reel → filtered file in `Movies/Naqi`.

**M4.3 — Queue.** `Queue.kt`, APPEND_OR_REPLACE chains + always-success rule, cancel-repair, queue screen, per-item retry. Recalibrate `Eta.kt`. Share 3 clips in a row → all 3 land in order.

**M4.4 — Trust, license, updater.** String changes EN + AR, `LICENSE`, `NOTICE`, licenses/About screen, yt-dlp weekly update check + manual button, store listing rewrite.

---

## Acceptance

- Share a 3-min YouTube link with both filters on → filtered `.mp4` in `Movies/Naqi` in **< 8 min** (S23-measured), correctly named after the video title, and **zero** unfiltered files visible to the gallery or any other app at any point.
- Share 3 links back to back → 3 queue entries, processed in order, all 3 complete. Killing the app mid-queue loses nothing. One item failing does not stop the others.
- Share the same URL twice → one queue item.
- Audio-only download of a music video with Remove music on → `.m4a` in `Music/Naqi` with vocals intact.
- Share a local `video/*` file → sheet appears with last-used filters preselected, no download, existing behavior otherwise unchanged. Reboot before its filter runs → that item fails with "Re-share the file", the rest of the queue proceeds.
- Insufficient disk → specific refusal before any bytes are fetched, not a failure mid-download.
- Downloading with all filters off → file published unfiltered, quarantine emptied.
- Installed size measured and recorded on a real device.

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `useLegacyPackaging` conflict (ORT vs youtubedl-android) | Kills the feature | M4.0 gate, before any other work |
| ~186 MB APK / ~350–400 MB installed | Adoption barrier in the target market | Activate `ModelDownloader` (−105 MB) — needs a model host that does not exist |
| yt-dlp extractors break weekly | Downloads silently stop working | Runtime update channel + manual button |
| F-Droid inclusion | Blocked today: prebuilt CPython/ffmpeg blobs, AGPL/NOASSERTION models, 117 MB of assets outside git break build-from-source | GitHub APK is the v1 channel; F-Droid is its own project |
| FGS 6 h/24 h budgets (per type) | Long jobs fail to complete | Measure; consider refusing sources above a duration cap |
| AGPL-3.0 NudeNet unresolved | Cannot ship compliantly | Pre-existing. Resolve, replace the model, or accept AGPL |
| `Eta.kt` censor factor is stale | Queue ETAs wrong by ~2× | Recalibrate in M4.3 |
| Queue JSON written by two workers | Corruption | Same process, `@Synchronized` + atomic rename. Room if it ever outgrows that |
| Platform ToS violation is the user's action, on the user's device | Legal exposure | No server-side component. App is not on any app store |
