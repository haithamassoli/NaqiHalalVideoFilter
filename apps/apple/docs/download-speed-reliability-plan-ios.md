# Download Speed & Reliability Plan — iOS / macOS

**Date:** 2026-09-27 · **Scope:** `naqi/Download/*`, `NaqiShared/DownloadQuality.swift`, `NaqiShare/ShareViewController.swift`, `naqi/Jobs/JobRunner.swift`, `naqi/Jobs/Job.swift`, `naqi/Jobs/JobQueue.swift`, `naqi/Jobs/LiveActivity.swift`, `naqi/UI/Screens/JobsScreen.swift`
**Companion:** the Android plan (`NaqiHalalVideoFilter/docs/download-speed-reliability-plan.md`, Phases 0–6 shipped). This document maps each Android item onto what iOS can actually do, with measurements taken on 2026-09-27.
**Overlaps:** `docs/apple-port/apple-silicon-performance-plan-2026-09.md` §6 (D1–D3, J1). This plan replaces D3's progress and concurrency parts and builds on J1's download record; D1/D2 are unchanged and still worth doing.

**Status:** Phases 0–8 (7.1 only) implemented on `feat/download-speed-reliability-ios`. 7.2, 9, 10 and X stay gated on measurement or a decision, as §7 says. Review notes are in §R.

---

## 0. TL;DR

The Android plan was about tuning yt-dlp. **iOS has no yt-dlp.** An iOS app cannot spawn a process. Each app update must go through the App Store, and 2.5.2 forbids downloading executable code, so a Python yt-dlp could never update itself. The iOS app instead uses a Swift extractor (`NativeExtract`) and URLSession. Measurements taken today show that this path has a much more basic problem than speed.

| # | Change | Gain | Cost |
|---|---|---|---|
| 1 | **Fix the YouTube extractor:** use the `VISIONOS` client with `visitorData` in place of `ANDROID`/`IOS` | **YouTube "Best" goes from 360p to 1080p/4K.** Today `ANDROID` returns a URL only for itag 18 (360p muxed), and `IOS` returns 13 formats with no URLs | ~80 lines + test |
| 2 | **10 MiB ranged chunks** in place of one URLSession request per stream | Unbounded GET measured at **0.8 MB/s**, 10 MiB chunks at **37 MB/s**, about 46× faster. Chunks also make resume, retry and progress work well | ~150 lines + test |
| 3 | **Per-chunk retry with backoff, abort on a missing chunk, error taxonomy** | The iOS form of `--retry-sleep`, `--fragment-retries` and `--abort-on-unavailable-fragments`, plus the right recovery for each failure type | ~120 lines + table test |
| 4 | **Format policy by filter and hardware** (AV1 only with a HW decoder; never WebM/VP9/Opus) | Avoids downloading streams AVFoundation cannot open; applies the same filter-aware policy as Android | ~60 lines + test |
| 5 | **Live stats:** Downloaded · speed · ETA in the Jobs row and the Live Activity | The UX the user asked for | ~80 lines |
| 6 | **Share-extension prefetch:** show title, duration and size before Download, and hand off the extraction | Size preflight before any byte; the app skips re-extraction | ~100 lines |
| 7 | **Keep downloading when the app leaves the foreground:** `beginBackgroundTask`, then optionally a background URLSession | A long download no longer freezes about 30 s after the user switches apps | S, then M |
| 8 | **Pipeline:** download the next queued link while the current job filters | The useful iOS form of "parallel yt-dlp processes" | M |
| 9 | Parallel ranges for non-YouTube hosts (the aria2c analogue) | **Measured no gain** from a Mac (archive.org: 5.9 s single vs 7.2 s with 4 ranges). Spike on the iPhone only | Spike |
| 10 | HLS/DASH fragments (the `-N 4` analogue) | Nothing today: no iOS extractor returns HLS for a site that needs it. Revisit with item X | Deferred |
| X | Extractor coverage beyond YouTube and direct files | yt-dlp covers ~1800 sites; iOS covers YouTube, direct links and `og:video`. **This needs your decision** (§6) | L |

Items 1 and 2 are the core. Without them nothing else matters for YouTube, because the ceiling is 360p.

---

## 1. Where we are today

| Area | Current behaviour | Reference |
|---|---|---|
| Extractor (iOS) | `NativeExtract`: direct media, YouTube via InnerTube, and `og:video` / JSON-LD / `<video src>` scraping | `NativeExtract.swift:10` |
| YouTube clients | `ANDROID 21.26.364`, then `IOS 21.26.4`. No `visitorData` | `NativeExtract.swift:120` |
| YouTube formats with URLs | **ANDROID: 1 of 40 (itag 18, 360p). IOS: 0 of 13.** Formats without a `url` are skipped (`guard let urlStr`), so every quality falls back to itag 18 | measured §3 |
| Codec info | `vcodec` is filled from `f["quality"]` (`"medium"`, `"hd1080"`), not from `mimeType`'s `codecs=`. The codec is lost, so selection can't tell H.264 from AV1 from VP9 | `NativeExtract.swift:176` |
| Format choice | `DownloadQuality.select`: prefers the `mp4` extension, then height, then bitrate. AV1-in-MP4 counts as "mp4", so a device without HW AV1 could be handed an AV1 stream | `MediaFormat.swift:53` |
| Transfer | One `URLSessionDownloadTask` per stream, with video and audio concurrent (`async let`). Resume data is kept on cancel | `Downloader.swift:436`, `:640` |
| HLS/DASH | Skipped in both parsers ("URLSession is a single-file downloader") | `YtDlp.swift:257` |
| Retries | None at the transfer level, apart from one resume-data fallback. On macOS, any yt-dlp failure triggers update + retry once | `Downloader.swift:506`, `YtDlp.swift:109` |
| Progress | Percent only, max 4/s, with fixed ceilings (90 % until merge) | `Downloader.swift:587` |
| Errors | `DownloadError`: `unsupported / network / noSpace / generic`. `classify` treats any `"http error"` as network, so a 403 and a removed video look alike | `MediaFormat.swift:32`, `YtDlp.swift:221` |
| Share sheet | The extension writes a URL manifest and a quality choice only. No title, size or preflight before Download | `ShareViewController.swift:237` |
| Background | Foreground `.ephemeral` session. iOS suspends the app ~30 s after it leaves the foreground, and the transfer stops. The Live Activity shows "paused" | `Downloader.swift:677`, `LiveActivity.swift:66` |
| Concurrency | One job at a time; the download is a stage inside `JobRunner.run` | `JobRunner.swift:54` |
| macOS | Runs the managed `yt-dlp_macos` binary for extraction only. The transfer is still URLSession | `YtDlp.swift:96` |

---

## 2. Android plan → iOS

| Android item | What it is on iOS | Verdict |
|---|---|---|
| Concurrent fragments `-N 4` | There are no HLS/DASH downloads, and YouTube https streams are not fragments | **Replace** with 10 MiB ranged chunks (Phase 2). Real fragments are deferred (Phase 10) |
| `--retries` / `--fragment-retries` / `--retry-sleep` | Nothing | **Take** as a per-chunk retry with exponential backoff 1→30 s, 10 tries, plus `waitsForConnectivity` |
| `--abort-on-unavailable-fragments` | Not needed today. A failed task already fails the stream | **Keep that invariant** when chunks arrive: never stitch a file with a missing chunk |
| `--throttled-rate` | Exists in yt-dlp to re-extract when an unsolved n-challenge throttles the URL | **Not needed**: VISIONOS URLs are not n-throttled; chunking fixes the size-based throttle |
| aria2c | No process spawning. The equivalent is parallel `Range` requests in URLSession | **Spike only** (Phase 9): no gain measured from a Mac |
| JS runtime (QuickJS/deno) | VISIONOS needs no JS player (`REQUIRE_JS_PLAYER: False`) | **Not needed.** If a future client needs one, JavaScriptCore is built in |
| yt-dlp self-update | 2.5.2 forbids downloaded code | **Replace** with a *data-only* client config (name, version, UA, per-client `codecs`) fetched weekly. Unknown fields are ignored; the built-in defaults remain the fallback |
| Hardware-decodable codec | `VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1/HEVC)`. AVFoundation cannot demux WebM at all | **Take**, simpler than Android: VP9 and Opus are never candidates (Phase 4) |
| Downloaded · speed · ETA | URLSession delegate bytes | **Take** (Phase 5), in the Jobs row and the Live Activity |
| Info prefetch in the share sheet | The share extension can make network calls (~120 MB memory limit, which is fine for JSON) | **Take** (Phase 6) |
| Error taxonomy + targeted recovery | `playabilityStatus` + HTTP status carry the same information as yt-dlp's messages | **Take** (Phase 3) |
| Parallel yt-dlp processes | No processes. Filtering is serial and dominates | **Replace** with pipelining (Phase 8) |
| Update gate / RW lock | Nothing to update at runtime | Not applicable |
| `--progress-template` parser | No stdout | Not applicable; the bytes come from delegates |

---

## 3. Measurements (Mac on the same network path as the iPhones, 2026-09-27, video `aqz-KE-bpKQ`, 634 s)

**InnerTube clients, exactly as the app sends them today, plus candidates:**

| Client | Result | Formats with a URL |
|---|---|---|
| `ANDROID 21.26.364` (current, first) | OK | **1 of 40**: itag 18, 360p muxed |
| `IOS 21.26.4` (current, fallback) | OK | **0 of 13** |
| `ANDROID_VR 1.65.10` + visitorData | OK, 29 URLs, but yt-dlp now marks its https formats as PO-token-required | The first 20 MB downloaded, then **403** |
| `VISIONOS 1.02`, no visitorData | `LOGIN_REQUIRED` "Sign in to confirm you're not a bot" | 0 |
| **`VISIONOS 1.02` + visitorData** | **OK in 0.3 s** | **32 of 32**, plain URLs (no signatureCipher), plus `hlsManifestUrl` |

VISIONOS formats include H.264 up to 1080p60 (itag 299), AV1 in MP4 up to 2160p60 (itag 401), VP9 WebM, AAC m4a (itag 140) and Opus. yt-dlp 2026.08.19 itself uses VISIONOS by default (`_DEFAULT_CLIENTS = ('visionos', 'web')`, and `visionos` alone when no JS runtime exists). It downloaded 1080p AV1 + audio (134 MB) in 5.5 s from start.

**Throughput, VISIONOS itag 299 (H.264 1080p60, 257 MB):**

| Method | Result |
|---|---|
| One unbounded GET (today's URLSession behaviour) | **0.8 MB/s**, 16 MB in 20 s. This is the documented googlevideo throttle on responses > ~12 MB |
| 10 MiB chunks, `&range=a-b` query, sequential | **37 MB/s**, 0.21–0.30 s per chunk |
| 10 MiB chunks, 4 in parallel | 41 MB/s (+10 %, not worth the complexity) |
| 10 MiB chunk via a `Range:` header | 206, same speed, so either form works |

yt-dlp does the same thing: `CHUNK_SIZE = 10 << 20`, `http_chunk_size` on every YouTube https format.

**archive.org** (the aria2c case, 61.9 MB, HTTP/2): single request 5.9 s (10.5 MB/s); 4 parallel ranges 7.2 s. On Android the S23 got 4.8× with aria2c, so the gain depends on the path and host, not on the platform. Measure on the iPhone before building it.

**Devices** (from `xcrun devicectl`): iPhone 12 Pro (A14), 14 Pro (A16) and 14 Plus (A15) have **no HW AV1**. iPhone 16 Plus (A18) and 17 Pro (A19 Pro) have it. So the AV1 gate splits the fleet roughly in half.

---

## 4. Phases

### Phase 0 — Measure

One summary log line per download in `Downloader.download`, in the style of Android's `NaqiDl`:

```
download host=<host> client=<VISIONOS|…> extract_ms=… first_byte_ms=… total_ms=… bytes=… MBps=…
         itag=<v>+<a> vcodec=<avc1|av01|…> height=… fps=… chunks=… chunk_retries=… reextracts=… outcome=<ok|class>
```

Benchmark set (each run 3×; one AV1-less device such as the 14 Pro and one AV1 device such as the 16 Plus or 17 Pro; same Wi-Fi):

| # | Source | Why |
|---|---|---|
| B1 | YouTube 10 min, Best | Main path. Baseline today = 360p |
| B2 | YouTube Short | Extraction overhead dominates |
| B3 | YouTube 60+ min | Long transfer; URL expiry; background |
| B4 | archive.org progressive MP4 | Parallel-range candidate |
| B5 | Page with `og:video` | Scraper path |
| B6 | B1 with Wi-Fi off 15 s mid-download | Resilience |
| B7 | B1, then switch to another app for 2 min | Background (Phase 7) |

**Acceptance:** a results table appended as §8.

---

### Phase 1 — YouTube extractor that returns real formats

**Change** `NativeExtract.youtube` (`NativeExtract.swift:117`):

1. **visitorData:** `GET https://www.youtube.com/sw.js_data`, strip the `)]}'` prefix and read `[0][2][0][0][13]`. That call worked in testing; yt-dlp reads the same value from the watch page's ytcfg, which is the fallback. Cache it in the App Group for 12 h and send it in both `context.client.visitorData` and `X-Goog-Visitor-Id`. On `LOGIN_REQUIRED` with "not a bot", refetch once.
2. **Client chain:** `VISIONOS` (with the fields from §3, including `deviceMake`, `deviceModel`, `osName` and `osVersion`), then `ANDROID_VR`, then `ANDROID` as the last resort. `ANDROID_VR` and `ANDROID` can yield 360p only; log it as `degraded` so Phase 0 data shows how often that happens.
3. **Parse the codec properly:** `mimeType` `video/mp4; codecs="av01.0.09M.08"` → `ext = mp4`, `vcodec = av01.0.09M.08`. Audio `audio/mp4; codecs="mp4a.40.2"` → `acodec = mp4a.40.2`, `vcodec = none`. Also read `fps`, `approxDurationMs` (duration), `lastModified` (resume identity, Phase 2) and `colorInfo`/`HDR` quality labels (SDR preference, Phase 4).
4. **Keep the HLS manifest URL** in `ExtractedMedia`, unused for now (Phase 10).
5. **Client config as data:** `config/youtube-clients.json` in this repo, fetched weekly from GitHub raw (same schedule as `YtDlp.updateIfDue`), validated and cached. It holds only name, version, UA and device fields, nothing executable. The compiled-in values are the fallback. This is how the app follows yt-dlp's client bumps between App Store releases.

**Test:** parse a saved VISIONOS player response fixture into the expected formats: codecs, heights, fps, audio flags. Add a live smoke test (`.disabled` by default, run by hand) that extracts one known video and asserts ≥ 1 format ≥ 720p has a URL. This is the test that rots first, so it should be one command away.

**Acceptance:** B1 "Best" downloads ≥ 1080p on both device classes. §8 records the client used for 100 % of runs.

---

### Phase 2 — Chunked transfer (speed, resume, retry, progress in one)

Replace `DownloadTransfer` (`Downloader.swift:640`) for streams with a known length (`contentLength`/`clen`, or HEAD `Content-Length` + `Accept-Ranges: bytes`):

```swift
/// googlevideo throttles any response over ~12 MB to ~0.8 MB/s; 10 MiB ranges run at line speed
/// (measured 37 MB/s vs 0.8). Same size yt-dlp uses.
static let chunkSize: Int64 = 10 << 20
```

- **Per stream, chunks run sequentially.** Each chunk is a `URLSession.data(for:)` / `bytes(for:)` with a `Range: bytes=a-b` header, appended to `<name>.part` with `FileHandle`. Parallel chunks measured only +10 %, so the first version doesn't use them (a `ponytail:` note names the upgrade). Video and audio still run concurrently: two streams, two chunk loops.
- **Session:** a shared `URLSessionConfiguration.default` with `waitsForConnectivity = true`, `timeoutIntervalForRequest = 30`, and HTTP/2 left on.
- **Resume = the file length.** `.part` holds exactly the completed chunks. A small `<name>.part.json` stores `{url, clen, lastModified, itag}`. On restart, re-extract if the URL is older than 5 h (googlevideo `expire` is ~6 h), check that `clen` and `lastModified` still match, and continue from `fileSize`. If they don't match, delete and restart the stream. The existing `.resume` data and `cancel(byProducingResumeData:)` go away for chunked streams.
- **Per-chunk retry:** 10 tries with backoff `min(30, 2^n)` seconds, the equivalent of `--retry-sleep http:exp=1:30`. Any status other than 206/200 is an error. A 200 answered to a range request means the server ignored Range, so fall back to a single request. A **403 on a chunk triggers one re-extraction** (the URL expired or IP-rotated), then the chunk resumes from the same offset.
- **Never stitch a gap:** a chunk that fails after 10 retries fails the stream. The `.part` is kept for the next attempt (the `--abort-on-unavailable-fragments` rule).
- **Unknown length** (a scraped URL without `Content-Length`): one plain request, as today.
- **Space check** before every chunk: `Preflight.availableBytes() > floor`, otherwise stop with `.noSpace` (the Android `onSpaceCheck`).

**Test:** a local HTTP fixture (the existing `DownloadTests` style) that serves a 25 MB body with Range support. Assert:
- three chunks are stitched byte-identical;
- a cancel after chunk 1 followed by a restart fetches only chunks 2–3;
- an injected 500 on chunk 2 retries and succeeds;
- an injected 403 on chunk 2 calls the re-extract hook once;
- a server that ignores Range falls back to a single request.

**Acceptance:** B1 ≥ 10× today's MB/s for the same itag. B6 finishes without user action. An interrupted B3 resumes without re-fetching completed chunks (count bytes).

---

### Phase 3 — Error taxonomy and targeted recovery

The same classes as Android, sourced from structured data rather than stderr:

| Signal | Class | Recovery |
|---|---|---|
| `playabilityStatus.status == LOGIN_REQUIRED` + reason contains "bot" | BOT_CHECK (Android: EXTRACTOR) | Refresh visitorData, then try the next client. Fail with the extractor message |
| `LOGIN_REQUIRED` + "age", "Sign in to confirm your age" | UNAVAILABLE | Fail fast |
| `ERROR` / `UNPLAYABLE`: "unavailable", "private", "removed", "members", "not a valid" | UNAVAILABLE | Fail fast, no retry |
| Reason contains "country" / "location" | GEO | Fail fast |
| HTTP 429 (player or chunk) | RATE_LIMITED | No immediate retry; "try again later" |
| Chunk HTTP 403 | FORBIDDEN | One re-extraction (Phase 2), then fail |
| No video format ≥ 144p with a URL on every client | EXTRACTOR | "YouTube changed; update the app". Also triggers an immediate client-config fetch, the iOS equivalent of Android's recovery update |
| `URLError` `.notConnectedToInternet`, `.timedOut`, `.networkConnectionLost`, `.cannotFindHost`, HTTP 5xx | NETWORK | Backoff retries (Phase 2). The job ends as `interrupted`, which the existing Resume handles |
| `ENOSPC` / space check | NO_SPACE | Existing `.lowSpace` |

- `DownloadError` gains `unavailable`, `geo`, `rateLimited`, `forbidden`, `extractor` (keep `unsupported` for "no extractor for this page"). `JobFailure` gains matching cases, with EN/AR strings in `Localizable.xcstrings`. The Arabic wording can be copied from Android's `err_download_*`.
- The macOS `YtDlp.classify` (`YtDlp.swift:221`) uses the same table over stderr: the Android `classify` ported as-is, since it was tested against real yt-dlp messages. Its blanket `retryingAfterUpdate` becomes "update only for EXTRACTOR/UNKNOWN, at most once per 6 h", as on Android.

**Test:** a table test from real player responses (bot, age, removed, geo) and HTTP/URLError fixtures to class.

**Acceptance:** a removed video fails in < 3 s with the right message and makes no further requests.

---

### Phase 4 — Format policy (hardware- and filter-aware)

iOS constraints that make this simpler than Android:
- AVFoundation cannot demux WebM, so **VP9 and Opus are never candidates** on any device. The existing `mp4` preference already implies this, but it now becomes an explicit filter on `vcodec`/`acodec`.
- AV1 in MP4 decodes only with a hardware decoder: `VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)`, which is true on A17 Pro / M3 and later. Without it, AV1 is excluded, and the device's ceiling is H.264's (YouTube: 1080p).
- HEVC: `VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)` (A9+, so in practice always).

The policy is the table the user approved for Android, adapted:

| Filters | Video | Resolution / fps | Audio |
|---|---|---|---|
| none | highest resolution among HW-decodable MP4 codecs; at equal resolution AV1 > H.264 (fewer bytes) | device limit only | AAC (itag 140) |
| remove music only | highest resolution among passthrough codecs; at equal resolution H.264 > HEVC > AV1 | kept, 60 fps too | AAC |
| visual filter (± music) | most efficient HW-decodable: AV1 > H.264; SDR preferred | ≤ 1080p, ≤ 30 fps preferred | AAC |
| audio only | — | — | AAC m4a as-is |
| Fast mode (existing) | as above | additionally ≤ 720p (`resolved(fast:)`) | — |

- **Implementation:** replace `videoRank` in `DownloadQuality.select` (`MediaFormat.swift:53`) with a pure `select(formats, quality, processing, hw: DeviceCodecs)` → `[MediaFormat]`. `DeviceCodecs` is a struct `{ av1: Bool, hevc: Bool }`, probed once, so the test can pass any device. Add `enum Processing { none, music, visual }` from `FilterOps`, exactly as on Android.
- **Remove** the unused yt-dlp `selector`/`preferredSelector` strings in `DownloadQuality` if the macOS path stops using them. On macOS, yt-dlp only extracts (`-J`) and selection is always Swift, so they are dead code today.
- **To verify first:** that `Remux.mux` / `Remux.passthrough` copy AV1-in-MP4 without re-encoding on an A18/A19 device. If they don't, music-only puts AV1 last (as today's H.264-first rule) and the table notes it.

**Test:** a table test with the VISIONOS fixture: {AV1 device, AV1-less device} × {none, music, visual, fast, audio} → expected itags. For example, an AV1-less device, none, Best → 299 + 140. AV1 device, visual → 399 + 140. AV1-less, visual, 60 fps-only source → 299 (a fps preference, not a filter).

**Acceptance:** no downloaded file on any device fails `Preflight`'s `isPlayable`. A music-only job on an AV1 device publishes without a re-encode (log the path).

---

### Phase 5 — Live progress: Downloaded · speed · ETA

- `Downloader.download`'s `onProgress: (Int) -> Void` becomes `(DownloadStats) -> Void`, with `DownloadStats { done, total?, bytesPerSec, etaSec? }` aggregated over both streams. Total = the sum of the known `clen` values, which is known up front with Phase 1, so there is no stream-switch reset problem as on Android. Speed is an EMA over 3 s. ETA = remaining ÷ speed.
- `DownloadProgress` (`Downloader.swift:587`) keeps its lock and throttle, but at 1 Hz, and it carries the stats.
- `JobProgress` gets an optional `download: DownloadStats?`, set only while `stage == .download`, and persisted like the rest so `JobMonitor` sees it.
- **Jobs row** (`JobsScreen.swift`): `66 MB of 331 MB · 8.1 MB/s · ~32 s left`, using `ByteCountFormatter` (Arabic digits and units come for free) and the existing `durationText`.
- **Live Activity:** the stats replace the ETA in `detail` (`LiveActivity.swift:95`) during download. The caption stays "Downloading". Live Activity updates from the foreground app are not budgeted, and 1 Hz is what the Jobs row uses anyway.
- **Notifications:** iOS has no ongoing notification; the Live Activity is the equivalent, so nothing else is needed.

**Test:** a unit test of the aggregator: two streams, known totals, a monotonic `done`, and an ETA within ±10 % on a synthetic feed.

**Acceptance:** B1 shows bytes/speed/ETA within 2 s of the first byte, in both the row and the Live Activity.

---

### Phase 6 — Share-extension prefetch

- When the shared item is a URL, the extension runs `NativeExtract.extract` (0.3 s player call + cached visitorData). It shows **title · duration · size for the selected quality**, and changing the quality recomputes the size locally with no new request. The subtitle fills in when the extraction lands, and the sheet never waits on it (same as Android).
- **Size preflight** in the sheet: refuse before queueing when the size exceeds the free space (the same `Preflight.requiredBytes` rule).
- **Handoff:** write the player JSON beside the URL manifest in the App Group (`ShareManifest`). `JobRunner` uses it when it is < 1 h old; otherwise it re-extracts. On any FORBIDDEN it drops the JSON and re-extracts.
- The extension needs the extractor sources (`NativeExtract`, `MediaFormat`, the policy) in its target, or moved into `NaqiShared`. Check the extension's memory: the JSON is ~100 KB, which is fine under the ~120 MB limit.

The speed gain is small on iOS (extraction is ~0.6 s, against Android's ~5 s with QuickJS). This phase is mostly about showing the size and title and refusing early.

**Acceptance:** the sheet shows the size within 1 s on Wi-Fi. A video larger than free space is refused before it is queued.

---

### Phase 7 — Keep downloading in the background

iOS suspends the app about 30 s after it leaves the foreground, and today's transfers stop then. Two steps, smallest first:

1. **`UIApplication.beginBackgroundTask`** around the download stage. This is free, and it covers short videos: B1 at ~30 MB/s is done in ~10 s. The expiration handler cancels cleanly; the `.part` survives, and Resume continues from it (Phase 2).
2. **Background `URLSession`** (only if Phase 0/B7 shows that long downloads regularly outlive step 1). While the app is still in the foreground, **enqueue every remaining chunk as a download task at once**. Tasks created in the foreground are not subject to the background resume-rate limiter, and `nsurlsessiond` runs them while the app is suspended (`httpMaximumConnectionsPerHost = 2`). On relaunch (`handleEventsForBackgroundURLSession`), stitch the chunk files in order. Costs: persistent task-to-chunk mapping, delegate reconnection, and no progress while suspended.

Filtering still doesn't run in the background (that is `BGProcessingTask`'s job, already scheduled with `requiresExternalPower`). The gain is that the source is ready when the user comes back.

**Acceptance:** B7 finishes step 1 for a 10-minute video. With step 2, a 60-minute download completes while the app is suspended.

---

### Phase 8 — Pipeline downloads with filtering

The Android idea of "parallel yt-dlp processes" exists to overlap per-item latency. On iOS, filtering is the long pole and is strictly serial, so the useful overlap is to **download the next queued link while the current job analyzes or renders**.

- `JobQueue` starts at most **one** look-ahead download (Phase 2's downloader into its quarantine) when the running job has left the `.download` stage and the next queued job has a remote URL.
- When that job starts, `Downloader.download` finds the completed record (`reusable`, `Downloader.swift:541`) and skips straight to processing. That path already exists.
- Cancelling or removing the queued job cancels its look-ahead and discards its quarantine. Space: the look-ahead counts against `Preflight` like any download.
- Skip it on Low Power Mode (`ProcessInfo.isLowPowerModeEnabled`) and when the thermal state is ≥ `.serious`.

**Acceptance:** 3× B2 queued with a censor filter: total time ≈ sum(filter) + first download, not sum(download + filter).

---

### Phase 9 — Parallel ranges for non-YouTube hosts (aria2c analogue, spike)

Only if Phase 0 shows slow single-connection transfers from real hosts on the iPhone: 4 parallel `Range` requests into a preallocated file (`FileHandle.seek`), for hosts with `Accept-Ranges: bytes` and a length. YouTube stays sequential (§3: +10 %).

**Ship only if** it is ≥ 1.5× on B4 on an iPhone. From a Mac it was 0.8×.

---

### Phase 10 — HLS/DASH fragments (the `-N 4` analogue, deferred)

Only relevant once an extractor returns HLS for content that has no progressive URL. None does today; YouTube's https formats cover everything. When one does, the Apple route is `AVAssetDownloadURLSession` into a `.movpkg`, then `Remux.passthrough` into MP4. That route does its own concurrency, runs in the background, and handles fMP4 and TS. Hand-parsing m3u8 is the fallback only if `.movpkg` export proves unreliable.

---

## 5. Not taken

- **Cookies, login and PO-token generation** (`BgUtils`, a WebView botguard): PRD non-goals, and a large surface. VISIONOS doesn't need them today. If YouTube enforces PO tokens on VISIONOS too, this becomes the next item: a WKWebView-hosted BotGuard is possible on iOS but is a project of its own.
- **User knobs** for chunk size, retries or connections: fixed defaults, as on Android.
- **A server-side extractor:** it contradicts "no cloud".

## 6. Decisions needed

1. **Distribution.** Is the iOS app going to the App Store, or TestFlight / ad-hoc only?
   - App Store: everything above holds. Guideline 5.2.3 (downloading third-party content) remains a review risk for the whole feature, as `improvement-plan-2026-08.md` §1 already notes; this plan doesn't change that.
   - Not App Store: option X below becomes realistic.
2. **Option X: sites beyond YouTube.** Bundle CPython + yt-dlp (BeeWare's Python-Apple-support; about +40–60 MB) and run it in-process for non-YouTube links, keeping the Swift path for YouTube. Without runtime updates, its extractors rot. Non-YouTube extractors rot more slowly than YouTube's, but every fix would need an app release. **My recommendation:** not now. Ship Phases 1–5, measure how often non-YouTube links fail (Phase 0's `outcome`), and decide with that number.
3. **The filter caps** (visual → ≤ 1080p, ≤ 30 fps): the same as your Android answer. I assume yes.
4. **Test device:** the 14 Pro (no AV1) and one of the 16 Plus / 17 Pro (AV1) cover both branches of Phase 4. All of them show as disconnected right now.

## 7. Order and effort

| Order | Phase | Effort | Notes |
|---|---|---|---|
| 1 | 0 Measure | 0.5 day | log line + B1–B7 baseline (expect 360p) |
| 2 | 1 Extractor | 1 day | the unblocker |
| 3 | 2 Chunked transfer | 1.5 days | ships with 1: 1080p at 0.8 MB/s would be worse than 360p fast |
| 4 | 4 Format policy | 1 day | |
| 5 | 3 Error taxonomy | 1 day | |
| 6 | 5 Live stats | 1 day | |
| 7 | 7.1 `beginBackgroundTask` | 0.25 day | |
| 8 | 6 Share prefetch | 1 day | |
| 9 | 8 Pipelining | 1.5 days | |
| 10 | 7.2, 9, 10, X | — | only on measured need or a decision |

## 8. Results (iPhone 18 Pro simulator, Mac network, 2026-09-27)

Physical devices were disconnected, so these runs are not the full B1–B7 matrix. The simulator has no AV1 decoder, so every run below took the AV1-less branch. Each row is one `download …` summary line from the app, driven through the real share-inbox handoff.

| Run | Before (plan §3) | After |
|---|---|---|
| B1 Big Buck Bunny, Best, censor on | ANDROID, itag 18 (360p), 0.8 MB/s | `client=VISIONOS itag=299+140 height=1080 fps=60 bytes=267891149 total_ms=6724 MBps=42.3 chunks=26 chunk_retries=0` |
| B1 repeat | — | `extract_ms=390 first_byte_ms=467 total_ms=6536 MBps=43.6` |
| Sintel (15 min), Best, censor on | — | `itag=137+140 height=818 fps=24 bytes=184583937 MBps=7.1` (curl on the same format: 5–19 MB/s per 10 MiB range, so the host varies) |
| Tears of Steel, **look-ahead** while Sintel filtered (Phase 8) | — | `itag=137+140 bytes=192699608 total_ms=8288 MBps=24.0`, then `look-ahead download done` |

- **Phase 5:** the Jobs row and the progress screen showed `134 MB of 184.6 MB · 7.9 MB/s · ~under a minute` (Arabic digits and units) and updated once a second.
- **Phase 6:** Safari → Share → Naqi on the simulator showed `Big Buck Bunny … · 10:34 · 267.9 MB` for Best, then `160.8 MB` after switching to 720p (298 + 140) with no new request.
- **Resume, retry, 403, Range fallback:** covered by `ChunkedDownloadTests` against a 25 MB `URLProtocol` fixture. B6 (Wi-Fi off) and B7 (backgrounding) still need a device.
- One slow outlier (Sintel at 1.5 MB/s) ran while another job's ML analysis was saturating the simulator. The isolated rerun is the row above.

## R. Review (2026-09-27, before implementation)

Re-measured from the Mac before building anything. The plan holds; these corrections went into the implementation:

- **Confirmed:** VISIONOS 1.02 + `sw.js_data` visitorData → `OK`, 32 of 32 formats with plain URLs, `hlsManifestUrl` present. One 10 MiB `Range` on itag 299 → 206 in 0.41 s (≈ 25 MB/s); the unbounded GET got 6.4 MB in 8 s (≈ 0.8 MB/s).
- **Duplicate itags.** The player response lists itag 140 (and 139, 249–251) twice: one copy has `isDrc: true`, and multi-language videos add `audioTrack` entries. Format ids were assumed unique (`DownloadProgress` keys by id). The parser drops `isDrc` and non-default audio tracks.
- **No URL-age check on resume.** `Downloader.download` re-extracts on every run, so a resumed `.part` always gets a fresh URL. Only `clen` + `lastModified` + itag are compared. The "older than 5 h" rule is dropped.
- **Phase 0 on devices is not possible right now.** Every physical device is disconnected (§6.4). The log line ships, and the benchmark numbers in §8 come from the iPhone 18 Pro simulator on the Mac's network. They are not a substitute for B1–B7 on the 14 Pro / 17 Pro.
- **AV1 on the simulator.** `VTIsHardwareDecodeSupported(AV1)` is false in the simulator, so simulator runs exercise the AV1-less branch only. The AV1 branch is covered by the table test.
- **Extractor in `NaqiShared`.** Phase 6 needs the extractor in the share extension, so `NativeExtract` and `MediaFormat` move to `NaqiShared` first. That also puts them in the widget target, which is harmless.

## Appendix A — VISIONOS request (as measured)

```
GET  https://www.youtube.com/sw.js_data          → strip ")]}'" → json[0][2][0][0][13] = visitorData
POST https://www.youtube.com/youtubei/v1/player?prettyPrint=false
  User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15
  X-Goog-Visitor-Id: <visitorData>
  {"videoId": "…", "contentCheckOk": true, "racyCheckOk": true,
   "context": {"client": {"clientName": "VISIONOS", "clientVersion": "1.02",
     "deviceMake": "Apple", "deviceModel": "RealityDevice17,1",
     "osName": "visionOS", "osVersion": "26.5.23O471",
     "hl": "en", "gl": "US", "visitorData": "<visitorData>"}}}
chunks: GET <format.url> with "Range: bytes=a-b" (or "&range=a-b"), b - a + 1 = 10 MiB
```

Values from yt-dlp 2026.08.19 `yt_dlp/extractor/youtube/_base.py`; they go in `config/youtube-clients.json` (Phase 1.5) so they can change without an app release. "Made for kids" videos are not available on VISIONOS; ANDROID's 360p is the fallback there.

## Appendix B — Verify during Phase 0 (not assumed)

- VISIONOS behaves the same from an iPhone on cellular (carrier NAT/IPv6) as from the Mac on Wi-Fi.
- `sw.js_data` visitorData works from the device and for how long a cached value stays valid.
- `Remux` passthrough of AV1-in-MP4 on A18/A19 (Phase 4).
- The share extension can run the extraction within its time and memory limits (Phase 6).
