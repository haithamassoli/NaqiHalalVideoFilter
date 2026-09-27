# Download Speed & Reliability Plan (yt-dlp)

**Date:** 2026-09-27 · **Scope:** `download/Downloader.kt`, `download/DownloadWorker.kt`, `work/JobController.kt`, `work/JobNotifications.kt`, `ui/screen/JobsScreen.kt`, `ui/screen/ShareSheet.kt`
**Reference studied:** [deniscerri/ytdlnis](https://github.com/deniscerri/ytdlnis) (cloned at HEAD, `YTDLPUtil.kt`, `RuntimeManager.kt`, `work/DownloadWorker.kt`)
**Library in use:** `io.github.junkfood02.youtubedl-android` 0.18.1 (latest on Maven Central)

**Status (2026-09-27):** Phases 0–6 shipped on branch `download-speed-reliability`, measured on an S23 — results in §8. Phase 7 (parallel downloads) not started.

---

## 0. TL;DR

Ranked by expected gain per line of code:

| # | Change | Gain | Cost |
|---|---|---|---|
| 1 | **Prefer an MP4-muxable codec the device hardware-decodes** (`-S res:H,+vcodec:avc,+acodec:m4a`, drop AV1 without a HW decoder) | Measured −12 % end-to-end on a music-only job (§8). Larger on devices without HW AV1, where "Best" used to be decoded in software | ~40 lines + 1 test |
| 2 | **Resilience flags**: `--retry-sleep` (exp backoff), `--abort-on-unavailable-fragments`, `--throttled-rate`, `--progress-delta 1` | Fewer failures on flaky mobile networks, no silently gapped files, less CPU wasted on progress spam | ~8 lines |
| 3 | **Error taxonomy + targeted recovery** (update yt-dlp only for extractor errors; IPv4 retry on 403; fail fast on permanent errors) | Fewer wasted updates/retries, clearer messages | ~60 lines + table test |
| 4 | **Live stats in UI**: downloaded / total · speed · ETA, in the Jobs card and the notification | UX; the user asked for it | ~70 lines + parser test |
| 5 | `-N 4` concurrent fragments | Real speedup **only for DASH/HLS sources** (Instagram, X, Facebook, TikTok, some YouTube live/Shorts). No effect on YouTube's normal `https` formats | 1 line |
| 6 | aria2c (spike, measured) | Possible speedup for progressive `https` files; +6.8 MB APK; breaks our progress parsing | Spike first |
| 7 | Parallel yt-dlp processes (2 lanes) | Only helps when filters are off or the network is slower than filtering; filtering is the bottleneck otherwise | Largest change; do last |

Phase 0 (measurement) comes before all of them, so every later phase has a number to beat.

---

## 1. Where we are today

| Area | Current behaviour | Reference |
|---|---|---|
| Format selection | Fixed selectors (`bv*+ba/b`, `bv*[height<=1080]+ba/…`). No codec preference, so YouTube "Best" is usually VP9 or AV1 in WebM | `Downloader.kt:40` |
| yt-dlp args | `-f`, `-o`, `--no-playlist`, `--no-mtime`, audio extraction. Nothing for fragments, retries, backoff, throttling | `Downloader.kt:163` |
| Retries | yt-dlp defaults (`-R 10`, `--fragment-retries 10`, **no sleep between retries**, so 10 retries burn out in seconds during a network blip). App layer: on *any* `YoutubeDLException`, update yt-dlp and retry once | `Downloader.kt:181`, `:250` |
| Fragments | yt-dlp default `--skip-unavailable-fragments`: a DASH/HLS download that loses a fragment **succeeds with a gap**, and the gapped file goes on to filtering | (default) |
| Concurrency | One global `processMutex` across every yt-dlp call, plus one unique WorkManager chain `naqi_download`. Exactly one download at a time | `Downloader.kt:60`, `JobController.kt:96` |
| Progress | Percent + ETA from the library's regex, throttled to 1 Hz in the worker. Speed and bytes are thrown away (third callback arg `line` is ignored) | `Downloader.kt:188`, `DownloadWorker.kt:80-92` |
| Size preflight | `KEY_SIZE_BYTES` is read but **never written**: no caller passes `sizeBytes`, and the share sheet never calls `getInfo`. The "refuse before a byte is fetched" check always sees 0 | `DownloadWorker.kt:56`, `JobController.kt:74` |
| Error mapping | Substring match; `"space" in text` also matches unrelated messages (e.g. "namespace") and mislabels them as low-space | `DownloadWorker.kt:169` |
| JS challenge runtime | Library 0.18.1 bundles QuickJS (`libqjs.so`) and passes `--js-runtimes` itself. QuickJS is the slowest runtime yt-dlp supports (deno > node > quickjs > bun) | AAR inspection |
| aria2c | Not bundled (PRD decision, saves 6.8 MB). The library supports it: artifact `io.github.junkfood02.youtubedl-android:aria2c:0.18.1` exists | `libs.versions.toml:37` |

---

## 2. What ytdlnis does, and what we take

| Feature | ytdlnis | Naqi today | Verdict |
|---|---|---|---|
| Concurrent fragments `-N` | User pref, default 1 | none | **Take**, fixed at 4. No setting |
| `--retries` / `--fragment-retries` | User pref, empty = yt-dlp default | defaults | **Skip the numbers** (defaults are already 10). **Take backoff** via `--retry-sleep`, which ytdlnis doesn't use |
| aria2c | Opt-in switch, `--downloader libaria2c.so` | none | **Spike only** (Phase 6) |
| `--buffer-size` + `--no-resize-buffer` | User pref | none | **Skip**. yt-dlp auto-resizes; no evidence it helps |
| `--socket-timeout` | 5 s for metadata fetch, pref for download | default | **Take for metadata only** (Phase 5 prefetch) |
| `-4` force IPv4 | Global toggle | none | **Take as a 403 fallback**, not a global toggle |
| `-r` limit rate | pref | none | Skip (non-goal) |
| Player client / PO-token UI, BgUtils PO-token server, WebView login | Large settings surface | none | **Skip** (PRD non-goal: cookies/login). Revisit only if "Sign in to confirm you're not a bot" shows up in Phase 0 data |
| JS runtimes: Node, Deno, QuickJS packages | Bundles all three, downloadable at runtime | QuickJS via library | **Measure** challenge-solve time (Phase 0). Deno only if QuickJS proves to be the bottleneck |
| Format sorting `-S` / preferred codec | User-chosen codec list | none | **Take, automatic**: derive from `MediaCodecList`, not a user setting |
| Concurrent downloads | `concurrent_downloads` pref, one worker launching N coroutines | 1 | **Take, gated** (Phase 7) |
| Progress | Posts the raw yt-dlp line to UI and notification | percent + ETA | **Take the idea, not the raw line**: machine-readable `--progress-template` |
| `--newline`, per-item log files | yes | no | Skip logs. `--progress-template` makes `--newline` irrelevant |
| Download delay / sleep between items | pref | none | Skip. Single-user share flow doesn't trip rate limits |

---

## 3. Findings that change the plan

1. **`-N` won't speed up YouTube.** yt-dlp docs: `-N` applies to "fragments of a dash/hlsnative video". YouTube's default formats are plain `https` (range requests), so `-N` has no effect there. It only helps HLS/DASH sites. For YouTube throughput the levers are aria2c (multi-connection) and not being throttled (`--throttled-rate`, JS challenge solved).
2. **The codec choice matters beyond download speed.** A VP9 WebM can't be muxed into MP4 by `MediaMuxer` (`Remux.kt:60`); H.264/HEVC in MP4 passes straight through. *Measured correction:* on the S23 the music-only job did not hit the render-first branch for a WebM, and the gain was a more modest 12 % (§8). The case this protects most is a device without a HW AV1 decoder.
3. **Fragment gaps are silent.** Skipping unavailable fragments means a "successful" download can be missing seconds of video, which then gets filtered and published.
4. **Every failure triggers a yt-dlp self-update.** A network drop leads to a GitHub API call, a zipapp download and a retry, which also fails because the network is still down. Unauthenticated GitHub API is limited to 60 requests/hour per IP.
5. **The size preflight is dead code** (see §1). Phase 5's info prefetch revives it for free.

---

## 4. Phases

### Phase 0 — Measure first (baseline)

**Goal:** a number for every later phase to beat, and data on which errors actually happen.

- Add one summary log line per download in `DownloadWorker` (the same `Log.i` style `FilterWorker` stats use):
  `download url=<host> ms_to_first_progress=… total_ms=… bytes=… avg_bps=… format=<format_id> vcodec=… retries=… outcome=<ok|errorClass>`
  - `ms_to_first_progress` covers extraction + JS challenge solving; it tells us whether QuickJS is the bottleneck.
- Pass `-v` in debug builds only, to confirm in logcat: JS runtime detected (`quickjs`), EJS challenge solver present, chosen formats.
- **Benchmark set** (run each 3×, on the S23 and one low-end device, same Wi-Fi):

| # | Source | Why |
|---|---|---|
| B1 | YouTube 10 min, 1080p | Main path, `https` protocol |
| B2 | YouTube Short | Short item, extraction overhead dominates |
| B3 | YouTube 60+ min | Long transfer, throttling/403 mid-download |
| B4 | Instagram reel / X video | DASH/HLS, `-N` should matter |
| B5 | A plain progressive MP4 site | aria2c candidate |
| B6 | Same as B1 with Wi-Fi toggled off/on mid-download | Resilience |

**Acceptance:** a table in this doc (append as §8) with baseline wall time, avg MB/s and time-to-first-progress per source.

---

### Phase 1 — Resilience and speed flags (smallest diff)

**Change** `Downloader.download` request builder (`Downloader.kt:163`):

```kotlin
fun request() = YoutubeDLRequest(url)
    .addOption("-f", selector)                     // Phase 4 replaces quality.selector
    .addOption("-o", File(dir, "%(title).80B.%(ext)s").absolutePath)
    .addOption("--no-playlist")
    .addOption("--no-mtime")
    // DASH/HLS only (Instagram, X, TikTok…). No effect on YouTube's https formats.
    .addOption("-N", 4)
    // yt-dlp retries 10× by default but back-to-back; spread them so a 30 s network blip survives.
    .addOption("--retry-sleep", "http:exp=1:30")
    .addOption("--retry-sleep", "fragment:exp=1:30")
    // Never publish a file with a hole in it; the .part/.ytdl resume picks up on the next attempt.
    .addOption("--abort-on-unavailable-fragments")
    // Below this yt-dlp assumes throttling (typically an unsolved YouTube n-challenge) and re-extracts.
    .addOption("--throttled-rate", "100K")
    // One progress line per second instead of per network chunk: less Python stdout, less parsing.
    .addOption("--progress-delta", "1")
```

Notes:
- `-N 4`, not higher: some CDNs answer 429 above ~5 parallel fragment requests. No user setting (ponytail: add one only if Phase 0 data shows a site that wants more).
- `--progress-delta 1` makes the worker's own 1 Hz throttle (`PROGRESS_INTERVAL_MS`) redundant but harmless; keep it for safety.
- Watch `--throttled-rate` on genuinely slow networks (< 100 KB/s): it re-extracts repeatedly. If Phase 0 data shows this, lower to `50K`.

**Test:** extend `DownloaderTest` to assert the built command contains these options (build the request through an `internal fun buildRequest(...)` so the test doesn't need a process).

**Acceptance:** B6 completes without user action; B4 faster than baseline; B1 unchanged or better.

---

### Phase 2 — Error taxonomy and targeted recovery

**Shipped.** `Downloader.classify` / `recoveryFor`, table-tested with real yt-dlp messages. Differences from the design below: GEO and UNAVAILABLE fail without retry; NETWORK / RATE_LIMITED retry once only when the first attempt used a shortcut (aria2c or the saved extraction), since either can be the cause; every retry is a plain native run from a fresh extraction. An out-of-space abort is no longer mistaken for a yt-dlp failure and retried. Verified on the S23: removed video → "can't be downloaded" in 3.4 s, no retry, no update; unsupported site → same; unresolvable host → network message, no update.

**Goal:** each error class gets the matching recovery, and none gets the wrong one.

Replace the substring `messageFor` and the blanket `retryOnceAfterYtDlpFailure` with one classifier used by both:

```kotlin
internal enum class DlError { NETWORK, FORBIDDEN, RATE_LIMITED, EXTRACTOR, PERMANENT, GEO, NO_SPACE, UNKNOWN }

internal fun classify(text: String): DlError  // text = all causes' messages, lowercased
```

| Match (lowercased stderr) | Class | Recovery |
|---|---|---|
| `no space left`, `enospc`, `errno 28` | NO_SPACE | Fail, low-space message. (Drop the bare `"space"` match) |
| `http error 403`, `forbidden` | FORBIDDEN | Retry once with fresh extraction + `-4` (IPv6 ranges get 403 more often on some carriers) |
| `http error 429`, `too many requests` | RATE_LIMITED | Fail with a "try again later" message; no update, no immediate retry |
| `requested format is not available`, `unable to extract`, `nsig`, `signature`, `challenge`, `sign in to confirm` | EXTRACTOR | Update yt-dlp (throttled, see below), retry once |
| `private video`, `video unavailable`, `has been removed`, `members-only`, `unsupported url`, `no video formats` | PERMANENT | Fail immediately, specific message, no update |
| `not available in your country`, `geo` | GEO | Fail immediately, geo message |
| `timed out`, `connection reset`, `network is unreachable`, `temporary failure in name resolution`, `unable to download`, `http error 5` | NETWORK | No app-level retry: yt-dlp's backoff (Phase 1) already retried. If the network is gone, WorkManager's `CONNECTED` constraint stops the worker and reschedules it; the `.part` resumes |
| anything else | UNKNOWN | Current behaviour: update + retry once |

- **Throttle recovery updates:** skip the update if the last successful update was < 6 h ago (reuse `Prefs.markUpdateChecked` timestamp). Stops a burst of failures from each hitting the GitHub API.
- New strings (EN + AR): `err_download_forbidden`, `err_download_rate_limited`, `err_download_unavailable`, `err_download_geo`.
- `Queue` still records the outcome as a `@StringRes`; nothing changes there.

**Test:** one table-driven unit test: real yt-dlp error lines (collected in Phase 0) → expected class. This is the file most likely to rot, so the test goes in the same PR.

**Acceptance:** zero yt-dlp updates triggered by B6; a private/removed video fails in < 10 s with the right message.

---

### Phase 3 — Live progress: Downloaded · Speed · ETA

**Shipped.** Verified on the S23: queue row and notification both read `66 MB / 331 MB · 8.1 MB/s · ~32 s remaining`, the row's ring is determinate. Differences from the design below: the stats live in the share-queue row (`QueueSection.kt`), because `JobProgressCard` only ever shows filter jobs; aria2c's own summary line is parsed too (yt-dlp's template does not apply to external downloaders); and the library's aria2c wiring had to be bypassed — it appends two `aria2c:` downloader-args and yt-dlp keeps only the last, which silently dropped `--summary-interval`, so aria2c printed no progress. We now pass aria2c by absolute path with one merged args entry (`Downloader.buildRequest`).

**Goal:** `45.2 MB / 120 MB · 3.1 MB/s · 0:30 left` on the Jobs card and the notification.

**Why a template instead of the raw line:** the yt-dlp README says consumers should not parse normal stdout, and aria2c / fragment downloads print different formats. `--progress-template` gives a stable, machine-readable line.

1. **Request** (`Downloader.kt`):
   ```kotlin
   .addOption(
       "--progress-template",
       // Prefix kept so the library's own "[download] NN.N% … ETA mm:ss" regex still matches; the
       // |naqi| tail is ours and is what the app actually reads.
       "download:[download] %(progress._percent_str)s ETA %(progress._eta_str)s " +
           "|naqi|%(progress.downloaded_bytes)s|%(progress.total_bytes,progress.total_bytes_estimate)s" +
           "|%(progress.speed)s|%(progress.eta)s|%(info.format_id)s",
   )
   ```
2. **Parser:** `internal fun parseProgress(line: String): DlProgress?` in `Downloader.kt` → `DlProgress(done: Long, total: Long?, bps: Double?, etaS: Long?, formatId: String)`. `NA` and `None` become `null`. Pure function, unit-tested with real lines (including `NA` totals and fragment downloads).
3. **Callback:** change `onProgress: (Int, Long) -> Unit` to `onProgress: (DlProgress) -> Unit`. Percent = `done / total` when total is known, else the library's percent.
4. **Two-stream downloads (`bv*+ba`):** yt-dlp downloads video, then audio, and progress restarts at 0 for the second. Detect the switch by `formatId` changing. Keep it minimal: stage text `Downloading video (1/2)` / `Downloading audio (2/2)`, per-stream bytes. (ponytail: no combined byte total; add it in Phase 5 when the prefetched info JSON gives both stream sizes up front.)
5. **Worker → UI:** add `KEY_DL_DONE`, `KEY_DL_TOTAL`, `KEY_DL_BPS` (Long) to `setProgressAsync` in `DownloadWorker`. `KEY_ETA_MS` keeps working unchanged.
6. **JobsScreen** (`JobProgressCard`, `JobsScreen.kt:234`): when `KEY_DL_DONE > 0`, render one extra line using `Formatter.formatShortFileSize` (localized digits and units for Arabic for free) and the existing ETA formatter from `Components.kt:282`.
7. **Notification** (`JobNotifications.downloadForegroundInfo`): same line via `setSubText`. Already limited to 1 Hz, which stays under the system's 5 updates/s cap.

**Test:** `parseProgress` unit test (5–6 real lines).

**Acceptance:** B1 shows bytes/speed/ETA within 2 s of the transfer starting; values match yt-dlp's `-v` output ±5 %.

---

### Phase 4 — Hardware-aware format selection

**Goal:** download what this device decodes in hardware and what MP4 can carry, so the filter pipeline can pass it through or at least decode it fast.

1. **Device probe** (new file `download/DeviceCodecs.kt`, computed once, cached in memory):
   ```kotlin
   /** Max height this device hardware-decodes at 30 fps, per codec; absent = no HW decoder. */
   internal fun hwDecodeHeights(): Map<String, Int>   // keys: avc, hevc, vp9, av1
   ```
   `MediaCodecList(REGULAR_CODECS)`, decoders only, `isHardwareAccelerated` (API 29 = our `minSdk`, so no name heuristics), `videoCapabilities.isSizeSupported` checked at 2160/1440/1080/720.
2. **Selector built at download time**, replacing the fixed strings in `Quality`:
   - `H = min(userCap, maxHwHeight)` where `userCap` comes from `Quality` (BEST = unbounded).
   - Exclude AV1 when there is no HW AV1 decoder, or when the SDK is < 34 (MP4 muxing of AV1 needs 34, `Remux.kt:65`): `[vcodec!^=av01]`.
   - Exclude VP9 above the device's HW VP9 height the same way.
   - `-f "bv*[height<=H]<excl>+ba/b[height<=H]"`
   - `-S "res:H,+vcodec:avc,+acodec:m4a"`. Per the yt-dlp README, `+vcodec:avc` orders **h264 > h265 > vp9 > vp9.2 > av01**. That puts MP4-muxable codecs first at equal resolution; `res` first means we never trade resolution for codec below the cap.
   - `--merge-output-format mp4` when the chosen streams allow it (h264/h265/av1 + aac). yt-dlp falls back to mkv/webm automatically when they don't.
   - `Quality` keeps its enum and wire names (queue JSON and `Prefs` depend on them). Only `selector` becomes a function.
3. **AUDIO** stays `ba/b` + `--extract-audio --audio-format m4a`, but add `-S "+acodec:m4a"` so an AAC stream is taken as-is instead of transcoding Opus → AAC.
4. **Log** chosen `vcodec`/height in the Phase 0 summary line.

**Test:** unit test of the pure selector builder (`hwHeights`, `sdk`, `quality`) → expected `-f`/`-S` strings: no-AV1 device, AV1 device on 33 vs 34, 720p-only HW device.

**Acceptance:** on the S23, B1 "Best" produces an `avc1` MP4 at 1080p; a music-only job on it skips the "not MP4-muxable, rendering first" path (verify via the log line at `FilterWorker.kt:817`).

---

### Phase 5 — YouTube resilience extras

**Shipped (item 1).** `Downloader.probe`: the sheet still opens instantly; the header fills in title · duration · size when the probe lands (~5–7 s), and changing quality or filters re-selects locally from the saved JSON in ~0.3 s, so the size (and the space check) always match the choice. The download starts from that JSON when it is under an hour old. The global mutex became an update gate (any number of yt-dlp runs share it; an update waits for all of them), so the probe runs while another download is in progress — verified. Measured: the same 720p YouTube download took **4.0 s from the sheet vs 11.0–11.5 s** without the prefetch (first byte at 2.5 s vs 9.5–10 s). Caveat found on a Mac: roughly 1 in 4 extractions produced stream URLs that 403 when reused; that costs ~1 s before Phase 2's FORBIDDEN recovery re-extracts.

1. **Info prefetch in the share sheet (revives the size preflight).**
   - Sheet calls `getInfo`-equivalent with `--dump-single-json --socket-timeout 10 -f <selector> -S <sort>`, writes the JSON to `quarantineDir/info.json`, shows title/duration/size, passes `sizeBytes` to `JobController.download` (fixes §1 dead code).
   - `DownloadWorker` downloads with `--load-info-json info.json` **when the file is < 1 h old**; otherwise from URL. On `FORBIDDEN`/`EXTRACTOR` with a loaded JSON, delete it and retry from URL (URLs expire; YouTube's after ~6 h).
   - Gain: skips a second extraction + JS challenge solve (Phase 0's `ms_to_first_progress`, typically 3–10 s per item, more with QuickJS).
   - Blocked on the lock: under today's global `processMutex` the sheet's metadata call waits behind any running download. Metadata still runs the zipapp an update replaces, so it takes the **read** lock from Phase 7. Do this phase after Phase 7.
2. **IPv4 fallback** is in Phase 2 (403 recovery).
3. **JS runtime:** if Phase 0 shows challenge solving > 5 s regularly on low-end devices, evaluate the ytdlnis route (deno as a downloadable package). Otherwise do nothing; QuickJS works.
4. **Player-client overrides / PO tokens:** not planned. Hard-coded client lists rot within weeks; nightly yt-dlp updates (already weekly + on-failure) are the maintained fix.

**Acceptance:** B2 time-to-first-progress drops by the extraction time; size refusal fires before any byte on a video larger than free space.

---

### Phase 6 — aria2c (shipped: every host except YouTube)

**Outcome:** shipped at the user's request regardless of size. Measured (§8): archive.org 4.8× faster on the S23. **YouTube throttles aria2c to ~32 KiB/s** (reproduced on a Mac too: 20.8 s vs 3.1 s native for a 19 s clip), because it doesn't fetch in yt-dlp's ranged chunks. So `Downloader.useAria2` excludes YouTube hosts, and a failed aria2c attempt retries natively after dropping aria2c's non-contiguous `.part`. An interrupted aria2c download resumed byte-identical (md5 match). The original spike notes follow.

**Question to answer:** does aria2c beat yt-dlp's native downloader by ≥ 1.5× median on B1/B3/B5, without more 403s?

- Add `libs.youtubedl.aria2c` (`io.github.junkfood02.youtubedl-android:aria2c:0.18.1`, AAR 20 MB all ABIs, ~6.8 MB arm64) and `Aria2c.getInstance().init(app)` in `ensureInit`.
- Debug-only flag: `--downloader aria2c --downloader-args "aria2c:-x 8 -s 8 -k 1M"`, `https` protocol only (`--downloader "https:aria2c"`), native for DASH/HLS where `-N` already parallelises.
- Known costs: `--progress-template` does not apply to external downloaders, so Phase 3's stats fall back to the library's aria2c regex (percent/ETA only, no speed). Resume semantics differ (`.aria2` control file). APK +6.8 MB, against the PRD's explicit decision.

**Ship only if** the gain is measured on real devices and the progress regression is acceptable. Otherwise record the numbers here and close.

---

### Phase 7 — Parallel yt-dlp processes (gated)

**Honest expectation:** end-to-end time is usually bound by filtering (CPU/GPU), not download. Two parallel downloads split bandwidth for large files. The real gains are (a) many short items (per-item extraction overhead overlaps) and (b) filters-off downloads. Default **2 lanes**, no setting.

1. **Lock:** replace the global `Mutex` with a `ReentrantReadWriteLock`: downloads/metadata take the **read** lock, `update()` takes the **write** lock. Take and release it inside `runInterruptible` so it stays on one thread (RRWL is thread-owned). The recovery path must release the read lock before updating (no upgrade in RRWL): fail → release read → update under write → re-acquire read → retry.
2. **Scheduling:** two unique chains `naqi_download_0`, `naqi_download_1`; `JobController.download` appends to the lane with fewer non-finished items. Each lane keeps FIFO and the "chained failure kills the chain" rule still holds per lane (workers always return success).
3. **Notifications:** `DOWNLOAD_NOTIF_ID + lane` so two foreground workers don't overwrite each other.
4. **Observation:** `observeDownloads` merges both lanes' flows; `NaqiApp.kt:58` and `JobsScreen` render every RUNNING download, not only `currentWork`.
5. **Dedupe** (`isQueued`) already works by tag, lane-agnostic.
6. **Memory:** each process is a CPython + yt-dlp (~60–100 MB). Skip the second lane when `ActivityManager.isLowRamDevice`.

**Test:** unit test lane assignment (pure function over lane counts).

**Acceptance:** 5× B2 queued: total time ≤ 0.65× the single-lane time; no notification flicker; cancelling one item leaves the other lane running.

---

## 5. Not taken from ytdlnis

- Custom command templates, raw format table, cookies, WebView login, PO-token generator, player-client editor, proxy, rate limit, download scheduling/delays, per-item log files, terminal: PRD non-goals, or a large surface for a single-share flow.
- User-visible knobs for `-N`, retries, buffer size: sensible fixed defaults instead. Add a knob only when data shows one value doesn't fit all.
- Room DB queue: our `Queue` JSON is enough (see its own ponytail note).

## 6. Decisions (answered 2026-09-27: 1 yes, 2 yes, 3 ship aria2c regardless of size)

1. **Should "Best" cap at 1080p when a filter is on?** Filter time scales with pixel count, so a 4K source takes roughly 4× as long as 1080p. → **Yes, cap at 1080p when filters are on; uncapped when all filters are off.**
2. **Prefer ≤ 30 fps when a filter is on?** (`-S fps:30`): halves frames to analyse/encode for 60 fps sources. → **Yes**, same rule as (1).
3. **aria2c APK size (+6.8 MB):** → **Only if Phase 6 shows ≥ 1.5×.**
4. **Parallel lanes:** → **2, off on low-RAM devices.**

## 7. Order and effort

| Order | Phase | Effort | Ships independently |
|---|---|---|---|
| 1 | 0 Measure | 0.5 day | yes (log line only) |
| 2 | 1 Flags | 0.5 day | yes |
| 3 | 4 HW codec selection | 1–1.5 days | yes |
| 4 | 2 Error taxonomy | 1 day | yes |
| 5 | 3 Progress stats UI | 1 day | yes |
| 6 | 7 Parallel lanes (+ RW lock) | 2 days | yes |
| 7 | 5 Info prefetch | 1 day | after 7 (lock) |
| 8 | 6 aria2c spike | 0.5 day + measuring | decision only |

Phase 4 moves ahead of 2 and 3 because it has the largest end-to-end effect (it saves filter time, not just network time).

---

## Appendix A — Full yt-dlp argument list after Phases 1–4 (video, filters on)

```
-f  "bv*+ba/b"                      ("bv*[vcodec!^=av01]+ba/b/bv*+ba" without a HW AV1 decoder)
-S  "res:1080,fps:30,+vcodec:avc,+acodec:m4a"
-o  <quarantine>/%(title).80B.%(ext)s
--no-playlist --no-mtime
--downloader libaria2c.so --downloader dash,m3u8:native   (not for YouTube, not on the retry)
-N 4
--retry-sleep http:exp=1:30 --retry-sleep fragment:exp=1:30
--abort-on-unavailable-fragments
--throttled-rate 100K
--progress-delta 1
--progress-template …   (Phase 3, not shipped yet)
(added by the library: --ffmpeg-location, --js-runtimes quickjs:…, --cache-dir)
```

## Appendix B — Things to verify during Phase 0 (not assumed)

- The library's `--js-runtimes` line actually enables QuickJS (look for the JS runtime in `-v` output).
- The nightly zipapp the updater installs bundles the EJS challenge scripts (README: "not needed if you are using an official executable"). If not, add `--remote-components ejs:github`.
- `%(a,b)s` alternation and `info.*` fields work inside `--progress-template` on the installed version.
- `--merge-output-format mp4` with an Opus audio stream: confirm yt-dlp picks a compatible container instead of failing (the `+acodec:m4a` sort should make this rare).

## 8. Results — S23 (SM-S911U1), Wi-Fi 5 GHz, yt-dlp nightly 2026.09.16, benchmark build

Download-only (no filters), wall time from `am start` to the "downloaded" log line:

| Source | Baseline | New | Change | Notes |
|---|---|---|---|---|
| B2 YouTube 19 s clip | 5.7 s (webm) | 4.6–5.6 s (mp4/avc) | same | aria2c on YouTube made this 23.8 s → excluded |
| B1 YouTube 10 min, 1080p | 11.2–21.4 s, 134 MB VP9 webm | 14.1–15.4 s, 268 MB H.264 mp4 | same wall time, **2× the bytes** | H.264 1080p60 is twice VP9's size; same time because YouTube served ~20 MB/s |
| B4 Dailymotion HLS 720p | 21.2 s (1.1 MB/s) | 8.7 s (3.6 MB/s) | **2.4× faster** | `-N 4` |
| B5 archive.org progressive | 79.4 s (0.78 MB/s) | 16.5 s (4.3 MB/s) | **4.8× faster** | aria2c |

Music-only job (download + separate + mux), Caminandes 2 (146 s), quality Best:

| | Baseline | New |
|---|---|---|
| Downloaded file | VP9 webm, 28.7 MB | H.264 mp4 1080p, 55.1 MB |
| Separate stage | 155.5 s | 138.8 s |
| Total wall | 173.9 s | 153.8 s (**−12 %**) |

Network outage (airplane mode 15 s, 5 s into the download): baseline and new both finished (48–50 s). WorkManager's `CONNECTED` constraint stops the worker and reschedules it; the `.part` resumes. The resumed aria2c file was md5-identical to a clean download. The `--retry-sleep` backoff targets "connected but failing" networks, which this test does not reproduce.

**Correction:** the `first_progress_ms` values in the rows above (1.2–2.8 s) measured the first output line of any kind, not the first progress line — the library calls back on every line. Fixed with Phase 3's parser. Re-measured: **YouTube 5.2 s**, archive.org 4.5 s from start to the first byte of progress. On YouTube that is extraction + QuickJS challenge solving, about a third of a 14 s 1080p download, so Phase 5's info prefetch (and measuring deno vs QuickJS) is worth doing, particularly for short videos.

**Trade-off to watch:** preferring H.264 roughly doubles the bytes for YouTube 1080p60 compared with VP9. Filtered jobs are capped at 1080p, so the worst case is the one above. If mobile-data cost matters more than decode speed, restrict `+vcodec:avc` to filtered jobs.
