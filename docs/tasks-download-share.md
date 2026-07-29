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

## M4.1 — Download core — **DONE 2026-07-29**
**Exit:** a debug intent with a URL downloads to quarantine and publishes to `Movies/Naqi`, correctly title-named, verified against YouTube/TikTok/Instagram/X; audio-only jobs produce `.m4a` in `Music/Naqi`.

- [x] `download/Downloader.kt`: `getInfo(url)`, `download(url, selector, destDir, onProgress)`; owns `YoutubeDL.init()` + `FFmpeg.init()`, plus `update()` (see the M4.0 finding — without it every YouTube link fails)
- [x] `download/DownloadWorker.kt`: CoroutineWorker, unique work `naqi_download`, FGS type `dataSync`, constraint `NetworkType.CONNECTED`. **Notification id 1003, not the PRD's 1002** — 1002 was already the "Saved" notification
- [x] Quarantine: `noBackupFilesDir/naqi-downloads/<urlKey>/`, filename `%(title).80B.%(ext)s`. One directory per URL rather than the PRD's "id suffix on collision": collisions become impossible by construction, and a retry finds its own `.part`
- [x] Free-space precheck `filesize_approx × (tempCopies + 2) + 2 GB`, checked in the sheet *and* in the worker; `filesize_approx` null → mid-download abort on a headroom test
- [x] Failed download keeps `.part` (yt-dlp native; the worker deliberately does not delete on cancel)
- [x] Orphaned quarantine dirs swept on the same 7-day rule as `naqi-work`
- [x] `publish()` extracted → `work/Publish.kt`, with `video()` and `audio()` variants
- [x] Audio-only job shape: `Preflight(allowNoVideo)` → `runAudioOnly` → `.m4a` to `Music/Naqi`. Detected from the source's tracks, not a flag
- [x] Debug intent (`-e download_url`, `-e quality`, `--ez ytdlp_update`)
- [x] Milestone check — **3 of 4 platforms verified on an S23:**

| Source | Result |
|---|---|
| YouTube | ✅ 102 MB, `Big Buck Bunny 60fps 4K …-naqi-<ts>.mp4` |
| X | ✅ 9.9 MB, `NASA - We're thinking about it.-naqi-<ts>.mp4` |
| Instagram | ✅ 1.3 MB, `Video by nasa-naqi-<ts>.mp4` (worked logged-out; no cookies needed) |
| TikTok | ❌ **unverified** — two independent causes, neither ours |
| audio-only + Remove music | ✅ routes to `runAudioOnly`, publishes `.m4a` to `Music/Naqi` |

**TikTok, honestly:** `_ssl.c:993: The handshake operation timed out`, reproducible, plus `[TikTok] The extractor is attempting impersonation, but no impersonate target is available`. TikTok is unreachable from this network (independently confirmed from the host machine), **and** yt-dlp needs TLS impersonation (`curl_cffi`) for TikTok that youtubedl-android does not bundle. Re-test from another network before assuming it works; if the impersonation warning persists, it is a library limitation, not a Naqi defect.

**Bug found and fixed while verifying (pre-existing, not introduced by M4):** the audio-only run died after 6.5 minutes of separation with `IllegalArgumentException: Cannot round NaN value` from `AacWriter`'s int16 quantizer. The shipped graph is fp16 and the input is normalized by whole-track std, so a passage far louder than the track average can push activations past fp16's range and a chunk returns non-finite. `DemucsSeparator` now silences and **counts** those samples (`nonFinite`, logged), and `AacWriter` guards the quantizer boundary. This would equally have hit a loud video picked through the normal picker.

## M4.2 — Share entry + sheet — **DONE 2026-07-29**
**Exit:** share a Reel from Instagram → sheet → filtered file in `Movies/Naqi`; share a local file → sheet with last-used filters, existing behavior otherwise unchanged.

- [x] Prerequisite fix: `FilterWorker.outputName` falls back to `File(uri.path).name` for `file://` — *landed in M4.1, and the M4.0 baseline A/B demonstrates the old bug directly (pre-change build published `video-naqi-…`)*
- [x] Prerequisite fix: `Preflight.sourceSize` falls back to `File(uri.path).length()` — landed in M4.1
- [x] Two intent-filters on MainActivity + `android:launchMode="singleTask"`, parsed in the existing `onCreate`/`onNewIntent` block
- [x] Link flow: regex-scrapes the first http(s) URL out of wrapped text (verified with *"Check this out &lt;url&gt; via the X app"*); no match → toast; already queued → "Already in the queue" toast
- [x] File flow: `grantUriPermission` to ourselves on receipt; a worker that cannot open the URI fails that item only
- [x] ~~`Step.Sheet` in the `NaqiApp` step machine~~ — **deliberately not done.** The sheet is a `ModalBottomSheet`, i.e. a modal *over* whatever step is showing, not a step of its own. Making it a step would replace the screen underneath and break Back. It is rendered as an overlay in `MainActivity`, the same pattern the existing delete-confirm dialog already uses
- [x] `ui/screen/ShareSheet.kt` — opens immediately with a spinner, `getInfo` off-main, inline error + Retry, Quality link-only, ≥30 min warning, file flow drops Quality and says **Filter**, audio-only hides Blur women
- [x] `data/Prefs.kt` — last-used ops + quality. **`SharedPreferences`, not the PRD's JSON file**: it is one file, the platform does the atomic write, and six scalars do not need a schema
- [x] POST_NOTIFICATIONS on first primary tap (33+), same contract as OptionsScreen
- [x] Milestone check — verified on an S23 with screenshots:

| Check | Result |
|---|---|
| Share wrapped link → sheet | ✅ title, `1 min · x.com`, Quality, filters pre-filled from Prefs |
| Sheet → Download → published | ✅ `DOWNLOADING → FILTERING → DONE`, both filters, `Movies/Naqi` |
| Share same URL while queued | ✅ one item; `queueActive=true workQueued=true` → toast |
| Share local `content://` video | ✅ no Quality section, primary reads **Filter**, **disabled** with both filters off |
| Local file → filter → publish | ✅ queued as a `file`-sourced item and drained by the same chain |

## M4.3 — Queue — **DONE 2026-07-29**
**Exit:** share 3 links back to back → 3 items processed FIFO, all complete; app kill mid-queue loses nothing; one failure doesn't stop the rest; ETAs plausible.

- [x] `work/Queue.kt` — one `filesDir/queue.json`, `@Synchronized`, temp+rename. A `StateFlow` drives the UI
- [x] `ExistingWorkPolicy.APPEND_OR_REPLACE` on both `naqi_download` and `naqi_filter_job`; DownloadWorker appends the filter request cross-name; no self-chaining
- [x] Queue-driven runs always return `Result.success()`; the real outcome goes to `queue.json`. The picker path still returns real failures — nothing is chained behind it to kill
- [x] Retry re-submits the same (uri, ops) → same `JobStore` key → existing scratch/checkpoints; a failed download still has its `.part`
- [x] Per-item cancel by tag + cancel-repair, restricted to the chain that was actually cancelled (repairing both would double-queue the untouched one)
- [x] Queue rendered on the jobs screen, not as a separate screen — the queue and the running job are the same question. Legacy picker path renders exactly as before
- [x] Recalibrated `Eta.kt` — see below
- [x] Milestone check: 3 links shared back to back → **all 3 DONE in share order**, all published, quarantine empty; same URL twice → one item

**Eta recalibration, measured not derived.** `CENSOR` 0.54 → **0.28** and `COMBINED` 1.3 → **1.0**. The 0.54 was a real Phase-0 soak number, just stale: `perf-plan.md` item 1.3 landed the next day and cut analyze 61 %. A censor-only run on `wm3.mp4` (192.9 s) took 51.1 s ⇒ **0.265**, and deriving 0.54 forward through the 61 % independently gives 0.257 — two routes, one answer. Also documented in `Eta.kt`: the factors are **asymptotes**. Fixed model-load cost dominates below ~2 min (an 81.9 s combined job measured 1.41×, of which 93.6 s was the separator), so short clips are quoted low. Not corrected for — a constant term would distort the long jobs these numbers exist to warn about.

**Still open:** SIGKILL-mid-queue and force-one-item-to-fail were not exercised on device. The always-success rule and the WorkManager chain persistence are what make them work, and both are load-bearing enough to deserve a real test.

## M4.4 — Trust, license, updater — **DONE 2026-07-29**
**Exit:** copy truthful in EN+AR, repo licensed, attribution screen shipped, yt-dlp updatable without an app release.

- [x] Strings EN+AR: seal chips now `On-device · Private` (`Offline` dropped — it became a false claim the moment Naqi fetches a link); tagline → "Filtering happens on your device. Your videos never leave your phone." Verified on device
- [x] `LICENSE` — full GPL-3.0 text at the repo root. The repo was previously **world-readable source with no licence at all**, i.e. all-rights-reserved
- [x] `NOTICE` at the repo root + an in-app **About & licenses** screen that renders it. The build copies `NOTICE` into assets (`CopyNoticeTask` + the AGP Variant API) so the file the user reads and the file in the repo cannot drift
- [x] About screen doubles as the About page: version, licence, live yt-dlp version, **Check for update** button
- [x] yt-dlp update: manual button + weekly auto-check on app open (`Downloader.updateIfDue`, `UpdateChannel.STABLE`). The clock only advances on success, so a week offline does not silently consume the interval
- [x] Store listing (`docs/store-listing.md`) — "No network usage" replaced
- [ ] **GitHub repo description — NOT DONE, needs the owner.** Still reads "Fully offline." `gh repo edit` returns HTTP 404 for this account: read access only. Set it manually to:
  > On-device Android video filter (Kotlin): stem-based music removal + face/NSFW censoring. Filtering happens on your device; downloads fetch only from the source you chose.
- [ ] **BLOCKED (pre-existing, inherited): NudeNet v3 is AGPL-3.0.** Not closable here. It is now stated in `NOTICE` under a heading that cannot be missed, and the same review is flagged for the NOASSERTION NSFW gate. **Release is blocked on this**, not on this file.

## Acceptance sweep (post-M4.4, PRD §Acceptance)
- [x] 3-min link, both filters → title-named `.mp4` in `Movies/Naqi`, zero unfiltered files ever visible. *Measured on an 82 s X link: download + combined filter = 1.9 min of pipeline. The 8-min budget is for a 3-min clip and was not exceeded; a true 3-min source was not run end-to-end through the sheet*
- [x] 3 links back to back → in order, all complete
- [x] Same URL twice → one queue item
- [x] Audio-only + Remove music → `.m4a` in `Music/Naqi` (this is what surfaced the fp16 NaN bug)
- [x] Local file share → sheet pre-filled, no download, picker path unchanged
- [x] All filters off → published unfiltered, quarantine emptied
- [x] Installed size measured on a real device — 196 MB APK, ~391 MB installed
- [ ] **Kill mid-queue loses nothing** — not exercised on device
- [ ] **One item failing does not stop the others** — not exercised on device
- [ ] **Insufficient disk → specific refusal before any bytes fetched** — code paths exist in both the sheet and the worker, neither triggered on a real full disk
- [ ] **Reboot before a shared file's filter runs → "Re-share the file"** — not exercised
- [ ] Vocals-intact check on the audio-only output was not listened to

## What is left

Three device tests and one owner action:

1. **SIGKILL mid-queue**, **force one item to fail**, and **reboot before a shared file is filtered**. These are the load-bearing claims of the always-success rule and the WorkManager chain, and they are exactly the kind of thing that is fine in theory and broken in practice.
2. **Fill the disk** and confirm the refusal arrives before any bytes are fetched.
3. **GitHub repo description** (above) — needs write access.

And the two that predate this work and gate any release: **NudeNet's AGPL-3.0**, and the absence of an SD 778G-class device to verify any timing claim on.
