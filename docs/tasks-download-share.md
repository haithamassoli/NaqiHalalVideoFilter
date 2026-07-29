# tasks-download-share.md — Download & Share

Source: `prd-download-share.md`. Milestones are dependency-ordered; M4.0 is a hard gate — nothing below it starts until it passes. Naming note: `tasks.md` already uses M4/M5 for the long-film phases; the M4.x names here follow the PRD and are unrelated to those.

## M4.0 — Packaging spike (gate) — **PASSED 2026-07-29** (`docs/m4-packaging-spike.md`)
**Exit:** with both new AARs in the build and `useLegacyPackaging = true`, ORT loads and htdemucs output is uncorrupted on a 16 KB-page device, and `YoutubeDL.init()` succeeds. Fails → the feature needs a different engine; stop here.

- [x] Add `io.github.junkfood02.youtubedl-android:library` + `:ffmpeg` deps (aria2c excluded per PRD) — 0.18.1
- [x] Flip `jniLibs { useLegacyPackaging = true }`; no `.so`/`META-INF` collisions surfaced, one pre-emptive `resources.excludes` rule added for commons-compress/Jackson metadata
- [x] Build + install on a 16 KB-page device — **not a hardware blocker after all**: the Android 15 `google_apis_playstore_ps16k` arm64 emulator (`PAGESIZE=16384`) was already installed locally. Installs, launches, no `dlopen`/`UnsatisfiedLinkError`. Also verified statically: every real ELF in the APK, including all four youtubedl-android libs, has `LOAD` alignment `0x4000`
- [x] Verify ORT: real separation job A/B'd against a worktree build of `8257f63` — **audio stream MD5 identical** (`123d5055…`), same chunk timings. The packaging flip is provably numerically inert
- [x] Verify yt-dlp: `YoutubeDL.init()` + `FFmpeg.init()` succeed, real 102 MB download round-trips, version prints **after** a self-update
- [x] Measure and record APK size + installed size on device — 164→196 MB APK, ~391 MB installed (PRD predicted ~186 MB / 350–400 MB; held)
- [x] Write go/no-go + findings → `docs/m4-packaging-spike.md`

**Gate finding that changed the plan:** the bundled yt-dlp (`2025.11.12`) fails *every* YouTube link today — YouTube's n-challenge defeats it. `updateYoutubeDL(STABLE)` fixes it (`→ 2026.07.04`). The runtime updater is a **functional prerequisite, not M4.4 polish**, and was pulled forward into `Downloader.update()`.

## M4.1 — Download core
**Exit:** a debug intent with a URL downloads to quarantine and publishes to `Movies/Naqi`, correctly title-named, verified against YouTube/TikTok/Instagram/X; audio-only jobs produce `.m4a` in `Music/Naqi`.

- [ ] `download/Downloader.kt`: `getInfo(url)`, `download(url, selector, destDir, onProgress)`; owns `YoutubeDL.init()`
- [ ] `download/DownloadWorker.kt`: CoroutineWorker, unique work `naqi_download`, notification id 1002, FGS type `dataSync`, constraint `NetworkType.CONNECTED`
- [ ] Quarantine: download to `noBackupFilesDir/naqi-downloads/`; filename = sanitized truncated title via yt-dlp output template (id suffix on collision) so the existing naming seam yields `<title>-naqi-<ts>.mp4` unchanged
- [ ] Free-space precheck: `filesize_approx × (tempCopies + 2) + 2 GB` before enqueue, specific refusal message; `filesize_approx` null → skip precheck, DownloadWorker aborts early once yt-dlp reports total bytes
- [ ] Failed download keeps `.part`; retry resumes it (yt-dlp native behavior — verify, don't build)
- [ ] Orphaned quarantine files swept on the existing 7-day rule (`JobStore.kt:61`)
- [ ] Extract `publish()` from `FilterWorker.kt:696` so DownloadWorker can publish directly when all filters are off
- [ ] Audio-only job shape: Preflight accepts `NO_VIDEO` for audio items → separator → AAC encode → `.m4a` via new `MediaStore.Audio` publish variant to `Music/Naqi`
- [ ] Debug intent (URL + selector extras) → download → publish, no UI
- [ ] Milestone check: one real download from each of YouTube, TikTok, Instagram, X; audio-only with Remove music on → `.m4a` with vocals intact

## M4.2 — Share entry + sheet
**Exit:** share a Reel from Instagram → sheet → filtered file in `Movies/Naqi`; share a local file → sheet with last-used filters, existing behavior otherwise unchanged.

- [ ] Prerequisite fix: `FilterWorker.kt:741` — output name falls back to `File(uri.path).name` when `DISPLAY_NAME` is null (`file://`), before the `"video"` literal
- [ ] Prerequisite fix: `Preflight.kt:124` — size falls back to `File(uri.path).length()` when scheme is `file`
- [ ] Two intent-filters on MainActivity (`ACTION_SEND text/plain`, `ACTION_SEND video/*`) + `android:launchMode="singleTask"`; parse in the existing `onCreate`/`onNewIntent` block (`MainActivity.kt:66`)
- [ ] Link flow: regex-scrape first http(s) URL from `EXTRA_TEXT`; no match → toast, return; matches existing non-terminal queue item → "Already in queue" toast, return
- [ ] File flow: immediately `grantUriPermission(packageName, uri, FLAG_GRANT_READ_URI_PERMISSION)`; revoke after publish; worker can't open URI → item FAILED "Re-share the file", rest of queue proceeds
- [ ] `Step.Sheet` in the `NaqiApp` step machine (`ui/NaqiApp.kt:21`)
- [ ] `ui/screen/ShareSheet.kt`: opens immediately with loading state; `getInfo` off-main → title · duration · domain; inline error + Retry on failure; Quality (Best/720p/Audio only → the three fixed selectors, never the raw format table) link-only; Filters checkboxes; ≥30 min → reuse `dlg_long_job_body` warning; file flow drops Quality, primary button Filter, disabled when both filters off; audio-only hides Blur women
- [ ] `data/Prefs.kt`: last-used `FilterOps` + quality, one JSON file, pre-fills the sheet
- [ ] POST_NOTIFICATIONS request on first Download tap (33+, same contract as OptionsScreen)
- [ ] Milestone check: share a Reel end-to-end → filtered in `Movies/Naqi`; share a local `video/*` → filtered, no download, no regression to the picker path; reboot before a shared file's filter runs → that item fails with "Re-share the file", queue proceeds

## M4.3 — Queue
**Exit:** share 3 links back to back → 3 items processed FIFO, all complete; app kill mid-queue loses nothing; one failure doesn't stop the rest; ETAs plausible.

- [ ] `work/Queue.kt`: single `filesDir/queue.json` per PRD schema, `@Synchronized`, temp+rename write
- [ ] Every item = one WorkRequest via `ExistingWorkPolicy.APPEND_OR_REPLACE` on `naqi_download` / `naqi_filter_job`; DownloadWorker appends the filter request cross-name on completion; no self-chaining with `KEEP` from inside `doWork` (silent no-op, wedges the queue)
- [ ] Queue-driven runs always return `Result.success()`; real per-item outcome recorded in `queue.json` (a chained failure would kill everything behind it)
- [ ] Retry = re-append same (uri, ops) → same jobKey → existing scratch/checkpoints → effective resume
- [ ] Per-item cancel: `cancelWorkById`, then cancel-repair — re-append all still-pending items of that chain; cancelled items removed from `queue.json`
- [ ] Queue screen: item states, per-item retry/cancel; legacy OptionsScreen path untouched
- [ ] Recalibrate `Eta.kt:38` — `CENSOR = 0.54` predates the −61% analyze win, over-quotes blur-only ~2×
- [ ] Milestone check: 3 shares → 3 in order, all land; SIGKILL mid-queue → nothing lost; force one item to fail → others complete; same URL shared twice → one item

## M4.4 — Trust, license, updater
**Exit:** copy truthful in EN+AR, repo licensed, attribution screen shipped, yt-dlp updatable without an app release.

- [ ] Strings EN+AR: seal chips → `On-device` · `Private` (drop `Offline`); tagline → "Filtering happens on your device. Your videos never leave your phone." (`res/values/strings.xml:13,14,21` + Arabic parallel, rendered by `TrustSeal()`)
- [ ] `LICENSE` — `GPL-3.0-or-later` at repo root (repo is currently all-rights-reserved world-readable source; forced by linking youtubedl-android)
- [ ] `NOTICE` + in-app "Open source licenses" screen; doubles as About with yt-dlp version + Update button
- [ ] yt-dlp update: weekly auto-check on app open + manual button, `UpdateChannel.STABLE`
- [ ] Store listing (`docs/store-listing.md:109`) + GitHub repo description: replace "No network usage"/"Fully offline" with "Downloads fetch from the source you chose. Nothing is uploaded, no accounts, no analytics."
- [ ] BLOCKED (pre-existing, inherited): NudeNet v3 AGPL-3.0 — resolve, replace, or accept AGPL before any public release; this file cannot close it but release cannot happen around it

## Acceptance sweep (post-M4.4, PRD §Acceptance)
- [ ] 3-min YouTube link, both filters → filtered `.mp4` in `Movies/Naqi` < 8 min (S23), title-named, zero unfiltered files ever visible to gallery/other apps
- [ ] 3 links back to back → in order, all complete; kill mid-queue loses nothing; one failure doesn't stop others
- [ ] Same URL twice → one queue item
- [ ] Audio-only music video + Remove music → `.m4a` in `Music/Naqi`, vocals intact
- [ ] Local file share → sheet pre-filled, no download, unchanged otherwise; reboot before filter → "Re-share the file", queue proceeds
- [ ] Insufficient disk → specific refusal before any bytes fetched
- [ ] All filters off → published unfiltered, quarantine emptied
- [ ] Installed size measured and recorded on a real device
