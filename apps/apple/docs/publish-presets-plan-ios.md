# Prepare to Post (Publish Presets) Plan — iOS / macOS

**Date:** 2026-09-28 · **Scope:** new `naqi/Publish/*`, `naqi/UI/Screens/DoneScreen.swift`, `naqi/UI/Screens/JobsScreen.swift`, `naqi/Media/Remux.swift`, `naqi/Resources/Localizable.xcstrings`, `naqiTests/`
**Companion:** the Android implementation on branch `publish-presets` of `NaqiHalalVideoFilter` (device-tested on an S23 on 2026-09-28; file map in Appendix C). This document maps each Android piece onto what iOS allows.
**Research:** two research passes on 2026-09-28, one on platform share routes and one on AVFoundation/Photos. The second measured passthrough splitting on an M3 Mac. Claims carry a source in §9 or are marked **UNVERIFIED**.

**Status:** Phases 1–4 implemented 2026-09-28 (`naqi/Publish/*`, `PublishSheet.swift`, `SavedCard.swift`), tested on the iOS simulator and built for macOS. Phase 0 (device limits) and Phase 5 (direct routes) still open; the numbers in `PublishPreset.all` are Android's until Phase 0 runs.
Beyond the plan: platforms show as brand tiles directly on the saved card (Done and the newest Activity item), matching Android `5f54b08`. A tile opens the share sheet straight away when the video fits, otherwise the sheet opens on that platform and splits without a second tap. Installed apps are found via `LSApplicationQueriesSchemes`.

---

## 0. TL;DR

The feature: after a filter finishes, the user picks a platform ("WhatsApp Status", "X", …). The app splits the video into parts that fit that platform's per-video limit, or passes it through untouched when it already fits. The user shares the parts from one sheet and deletes them afterwards. Every platform difference lives in data (`PublishPreset`); no code asks which platform it is.

| # | Change | Gain | Cost |
|---|---|---|---|
| 1 | **`PublishPreset` + `cutPoints`** (pure Swift, unit-tested) | The whole rule set is one list; changing a limit is a one-line edit | ~80 lines + tests |
| 2 | **Keyframe-aligned passthrough split** (`AVAssetExportSession` passthrough + `timeRange`) into `Documents/Parts/` | Parts in about a second, no re-encode, no quality loss | ~120 lines + tests |
| 3 | **"Prepare to post" sheet** on the Done screen and on every finished job row, with per-part Share, Share all and Delete parts | The user-facing feature | ~250 lines |
| 4 | **Order options by use** (App Group `UserDefaults`) | The user's usual platform is first and preselected | ~20 lines |
| 5 | **Direct-open routes** (Snapchat Creative Kit Lite, TikTok Share Kit, Instagram Reels) | Skip the share sheet for three apps | Deferred: each needs a developer registration (§6) |

Unlike Android, iOS cannot aim a share at one app. `UIActivityViewController` can't be pre-targeted or reordered, and third-party extensions can't be excluded. So v1 uses the system share sheet for every platform, and a preset decides only *how the video is prepared*: limit, part count, and whether all parts go in one share.

---

## 1. Where we are today

| Area | Current behaviour | Reference |
|---|---|---|
| Deployment | iOS 18.0, native macOS 15 (`SDKROOT = auto`, `SUPPORTED_PLATFORMS = iphoneos iphonesimulator macosx`) | `project.pbxproj:445-451` |
| Finished file | Always kept in the app's Documents (`OutputLibrary`). A Photos publish copies into the library with **add-only** access and keeps the local file | `Publish.swift:60-99`, `Preflight.swift:168` |
| Share | `ShareLink(item: url)`, one file, system sheet | `DoneScreen.swift:168`, `JobsScreen.swift:179` |
| Library | The job list (`JobQueue`), not a folder listing. A done row has `Published(name, url, assetID)` | `JobsScreen.swift:109`, `Publish.swift:33` |
| Passthrough | `Remux.export`: `AVMutableComposition` + `AVAssetExportPresetPassthrough`, async `export(to:as:)`, cancel polling, and a check against a short output | `Remux.swift:150-204` |
| Formats | Downloader never takes WebM/VP9/Opus; AV1 only with a hardware decoder | `MediaFormat.swift:103`, `:161` |
| Audio-only outputs | `.m4a`, published to a folder | `Flow.swift:117-119`, `Job.swift:124` |
| Shared settings | App Group `UserDefaults` (`AppGroup.defaults ?? .standard`) | `ExportTarget.swift:30`, `AppGroup.swift:24` |
| App schemes | No `LSApplicationQueriesSchemes` in `Info.plist` | `naqi/Info.plist` |

---

## 2. Android → iOS

| Android piece | iOS equivalent | Verdict |
|---|---|---|
| `PublishPreset(id, labelRes, maxSegmentMs, supportsMultipleSegments, targets, mime)` | Same struct. `targets` becomes `route`, `.shareSheet` in v1 | **Take**, plus one field: `maxItemsPerShare` (§3.5) |
| `requiresSplitting(duration)` computed, not stored | Same | **Take** |
| `targets = ["pkg/Activity", "pkg"]`: open one app, skipping its picker | Not possible with the share sheet. Only SDK/URL-scheme routes open an app directly | **Replace** with `.shareSheet`; direct routes are Phase 5 |
| `ACTION_SEND_MULTIPLE` for "Share all" | `ShareLink(items:)` / `UIActivityViewController` with several file URLs. Each extension declares a max item count, and the sheet offers it only when the count fits | **Take**, batched by `maxItemsPerShare` |
| `MediaExtractor` scan of sync samples + `cutPoints` | `AVSampleCursor` sync info, mapped through `AVAssetTrack.segments` (§3.2) + the same `cutPoints` | **Port `cutPoints` verbatim** (and its 6 tests) |
| `Remux.copyRange` (sample copy with `MediaMuxer`) | `AVAssetExportSession` passthrough with a keyframe-aligned `timeRange` | **Replace**. Measured 0.74 s for a 378 MB, 10-min file cut into seven parts on an M3 |
| `HEADROOM_US = 500_000`: parts aim 0.5 s under the limit | Same, for a stronger reason on iOS (§3.2) | **Take** |
| Opus → AAC transcode before the split (AV1+Opus WebM downloads) | The iOS downloader never fetches WebM/Opus; AVFoundation can't open WebM at all | **Drop** |
| WebM parts for a WebM source | Same reason | **Drop** |
| Parts in the gallery (`Movies/Naqi/Parts`) | App Documents `Parts/` (Decision 2). Visible in Files › On My iPhone › Naqi | **Change** (§3.4) |
| Library hides the Parts folder | The iOS library is the job list, which never contains parts | **Nothing to do** |
| `existingParts` found again by name after the sheet closes | Same, by listing `Documents/Parts/` | **Take** |
| All-or-nothing split: failure or cancel deletes written parts | Same | **Take** |
| Usage counter in `Prefs`, once per option per sheet session; stable sort | App Group `UserDefaults` | **Take** |
| Per-platform limits measured on the S23 | Several differ on iOS, or are unverified for the share-extension route | **Re-verify in Phase 0** (Appendix B) |

---

## 3. Findings that change the plan

1. **The share sheet can't be aimed at one app.** `excludedActivityTypes` covers only Apple's built-in activities, and an Apple engineer confirmed apps may not exclude other apps' extensions [23][24]. No API pre-selects or reorders. So on iOS, "WhatsApp Status" means *parts sized for WhatsApp Status*, and the user taps WhatsApp in the sheet. Since WhatsApp iOS 25.22.83 (Aug 2025), its share extension offers **My Status** directly [3].
2. **Passthrough cuts keep hidden extra frames, and some receivers see them.**
   - A passthrough `timeRange` that starts mid-GOP copies samples from the previous keyframe and hides them with an edit list. Measured: a 10 s cut at 3.3 s copied 11.37 s of video.
   - Even with a keyframe-aligned start, the tracks' own durations came out slightly longer: video 10.067 s, audio 10.048 s against the 10.000 s edit-list duration.
   - AVFoundation, FFmpeg and ExoPlayer honour edit lists. Android's `MPEG4Extractor` seems to report `mdhd`, the longer copied span, which matches the "90.001 s, trimmed to 90 s" failure we hit on WhatsApp Android.
   - Rule: **always cut on keyframes, and aim 0.5 s under the limit.** That covers the ~0.13 s overhang with room to spare.
3. **One more timestamp trap.** `AVSampleCursor` and raw-reader timestamps ignore the *source* file's own edit list. With B-frames, keyframes showed up at 0.067 s, 2.067 s … instead of 0, 2 …. Keyframe times must be mapped through `AVAssetTrack.segments` before they become cut points, or every cut is off by the priming offset.
4. **Photos is the wrong home for parts** (Decision 2).
   - `PHAssetChangeRequest.deleteAssets` shows a system confirmation even for assets the app created (widely reported; Apple's docs don't state it).
   - The app only holds **add-only** access, which can't fetch or delete anything.
   - Parts therefore live in `Documents/Parts/` and are deleted with `FileManager`, instantly and with no prompt.
5. **Too many parts make the app disappear from the share sheet.** Each share extension declares how many movies it accepts (`NSExtensionActivationSupportsMovieWithMaxCount`), and the sheet offers it only when the item count fits [25][26]. WhatsApp accepts **30** [1]. A 50-minute video at 88 s parts is 35 parts, so a single "Share all" would silently drop WhatsApp from the sheet. So a preset carries `maxItemsPerShare`, and Share all becomes "Share 1–30", "Share 31–35".
6. **The iOS share-sheet limits aren't Android's.** The Android limits were measured through Android intents. On iOS the share extension is a different code path:
   - **Snapchat:** Android rejected >120 s. Creative Kit documents 5 min but cuts clips over 10 s into 10 s Snaps [15]. Its share-extension limit on iOS is **UNVERIFIED**.
   - **Instagram:** the direct Stories route caps at **20 s** [4] and Reels at **3–60 s** [5]. The share-extension limits are **UNVERIFIED**. With Decision 3 we don't pre-split for Instagram and rely on Instagram splitting long Stories itself, as it did on Android. Phase 0 checks this.
7. **macOS gets the same sheet for free.** `ShareLink` uses `NSSharingServicePicker` on the Mac. The presets still size the parts; the direct routes of Phase 5 are iOS-only.
8. **Direct routes cost registrations, not code.**
   - Instagram Reels/Stories need a Meta App ID [4][5].
   - Snapchat needs a Snap Kit Client ID [14].
   - TikTok needs a client key and a universal-link redirect URI, and wants the media as **Photos `PHAsset` identifiers** [16][17], which would bring back the Photos problem from finding 4.
   - None of that is worth it before v1 shows people use the feature (Decision 1).

---

## 4. Phases

### Phase 0 — Verify limits on a device (before code)

On a real iPhone, share a 2:30 and a 10:00 MP4 through the system sheet to each app, and record for each one:
- whether it is offered at all;
- whether it trims, splits, or rejects;
- the error text;
- whether several videos can go in one share.

Apps and questions (Appendix B lists them in full):
- WhatsApp: My Status, and chat.
- X: composer vs Chat.
- Instagram: Story, Reels, Feed.
- Snapchat, TikTok, Telegram, Messenger.

Also time a split of the 10-min file on the device (the M3 took 0.74 s; iPhone is **UNVERIFIED**).

**Output:** the numbers that go into `PublishPreset.all`. A limit nobody measured stays out of the list rather than going in as a guess.

### Phase 1 — `PublishPreset` and `cutPoints`

New `naqi/Publish/PublishPreset.swift`:

```swift
struct PublishPreset: Sendable, Equatable, Identifiable {
    let id: String                        // stable; names the part files
    let label: LocalizedStringResource
    let maxSegment: Duration?             // nil = pass-through, never copied
    let supportsMultipleSegments: Bool    // false = one share per part
    let maxItemsPerShare: Int?            // share-extension cap; nil = no cap
    let route: Route = .shareSheet        // Phase 5 adds the direct routes
    let contentType: UTType = .mpeg4Movie

    enum Route: Sendable, Equatable { case shareSheet }

    /// Derived, never stored: a stored flag could contradict `maxSegment`.
    func requiresSplitting(_ duration: Duration) -> Bool

    static let all: [PublishPreset]       // Appendix A
    static let customID = "custom"
    static func custom(seconds: Int) -> PublishPreset   // id "custom-\(seconds)s"
}

/// Part starts: 0, then the latest keyframe that keeps each part ≤ max.
/// A keyframe gap longer than `max` runs long (the platform trims it).
func cutPoints(keyframes: [CMTime], duration: CMTime, max: CMTime) -> [CMTime]
```

- `cutPoints` is a straight port of the Android function, and so are its six tests (`CutPointsTest.kt`), into `naqiTests/PublishPresetTests.swift` (Swift Testing):
  - a short video is one part;
  - cuts land on the latest keyframe inside the limit;
  - an exact multiple needs no extra part;
  - a keyframe gap longer than the limit runs long instead of looping;
  - no keyframe after the start stops;
  - `requiresSplitting` follows the limit.
- Add one test for `maxItemsPerShare` batching (`[35 parts] → [1…30, 31…35]`).

### Phase 2 — `Splitter`

New `naqi/Publish/Splitter.swift`:

1. **Keyframes.** Walk the video track with `AVSampleCursor`, keeping each sample whose `currentSampleSyncInfo.sampleIsFullSync` is true. Map each time through `AVAssetTrack.segments` into presentation time (finding 3). Measured at 11 ms for 900 samples.
2. **Cuts.** `cutPoints(keyframes, duration, max - 0.5 s)`.
3. **Compatibility gate.** Call `AVAssetExportSession.compatibility(ofExportPreset: AVAssetExportPresetPassthrough, with: asset, outputFileType: .mp4)` once. False means the parts can't be written without a re-encode: show "Couldn't prepare the parts" and write nothing. This is the -11838 case, and possibly AV1 on a device without an AV1 decoder (**UNVERIFIED**).
4. **Export each part.** Passthrough, `timeRange = [cut_i, cut_i+1)`, to a temp file, then move to `Documents/Parts/<stem>-<presetID>-<n>.mp4`.
   - Reuse `Remux.export`: give it `(asset, timeRange)` instead of only a composition.
   - Its short-output check compares against `timeRange.duration`.
   - Keep its cancel polling as it is.
5. **All or nothing.** Any throw or cancel deletes every part already written, then rethrows. Same contract as Android and as `Publish.saveToPhotos`: a half set never survives.
6. **Background.** Wrap the split in `beginBackgroundTask`. A split takes seconds and the grant is about 30 s [research §7]. No `BGContinuedProcessingTask`: the split is too short to need one.
7. **`existingParts(stem, preset)`** lists `Documents/Parts/`, matches the name, and sorts by `n`. That is how parts made yesterday show up again today.
8. **`delete(parts)`** uses `FileManager.removeItem` and re-lists afterwards, so whatever could not be deleted stays visible.

The part folder is excluded from backup like `OutputLibrary.root`. It sits under Documents, so the user can also see and remove parts in Files.

Tests (`naqiTests/SplitterTests.swift`, on the QA clip already used by `PassthroughTests`):
- parts are contiguous;
- every part ≤ max, checking `AVAsset.duration` **and** the track `timeRange`;
- the sum of the parts equals the source within one frame;
- a cancel leaves `Parts/` empty;
- a pass-through preset writes nothing.

### Phase 3 — "Prepare to post" sheet

New `naqi/UI/Screens/PublishSheet.swift`, presented with `.sheet`:

- **Entry points:**
  - A full-width outline button under Share/Save in `DoneScreen.savedCard`.
  - A "Prepare to post" item in each done `JobRow` (a context menu next to its `ShareLink`, `JobsScreen.swift:179`).
  - Both are hidden for audio-only outputs.
- **Options:** a horizontal row of chips (Android found that segmented controls clip "WhatsApp Status"), with Custom last in the default order. Custom shows a slider from 15 s to 10 min in 15 s steps; its id carries the seconds so parts made at different limits never mix.
- **Status line:**
  - "Fits as is. It will be shared without changes."
  - "Longer than %@, so it will be split into parts, cut at the nearest keyframe."
  - "Parts are saved in Files › Naqi › Parts."
- **Parts list:** "Part n · m:ss", each with its own `ShareLink(item:)`.
- **Primary action:**
  - Pass-through: **Share** (`ShareLink(item: url)`).
  - Needs a split: **Prepare parts**, with a progress view while it runs.
  - Parts exist: **Share all** (`ShareLink(items:)`), batched by `maxItemsPerShare`. Hidden when `supportsMultipleSegments` is false; the per-part buttons cover that case.
- **Delete parts:** red text button, no confirmation. Parts are copies that can be remade in seconds; the filtered video is untouched.
- **Closing the sheet mid-split** cancels the task, which triggers the Phase 2 cleanup.
- **Strings:** en + ar in `naqi/Resources/Localizable.xcstrings`. The Android strings already exist in both languages (`values/strings.xml` and `values-ar/strings.xml`, keys `preset_*` and `publish_*`), and the text is the same apart from the Files path.

UI test: open the sheet from a seeded done job (`ScreenshotSeed`), pick a preset, prepare, and check that the parts list appears. Share sheets can't be driven by UI tests; that part stays in Phase 0's manual pass.

### Phase 4 — Order by use

- `PublishUsage` in App Group `UserDefaults`, key `naqi.presetUses.<id>`.
- Count once per option per sheet session. Sharing eight parts one by one is still one use.
- The sheet computes the order once when it opens: most used first, ties in `PublishPreset.all` order (stable sort), Custom included. The first chip is preselected.
- The order doesn't change while the sheet is open, so a chip never moves under the user's finger.
- Tested on Android: after one Messenger share, reopening put Messenger first and selected.

### Phase 5 — Direct-open routes (deferred; each needs a registration)

`Route` gains cases. The presets stay data, and each route is one small adapter:

| Route | Mechanism | Multiple items | Limit | Needs |
|---|---|---|---|---|
| `.snapchatCreativeKit` | Pasteboard `com.snapchat.creativekit.backgroundVideo` + `snapchat://creativekit/preview/1?clientId=…` ("Lite", no SDK) [13][14] | No, one per flow | ≤ 5 min, ≤ 300 MB; clips over 10 s become 10 s Snaps [15] | Snap Kit Client ID, `snapchat` query scheme |
| `.tiktokShareKit` | `TikTokOpenShareSDK` (SPM) [16] | Yes, up to 12 [17] | Total > 3 s; about 10 min (**UNVERIFIED**) [18] | Client key, universal-link redirect (Associated Domains), **parts saved to Photos first** (PHAsset ids) |
| `.instagramReels` | `instagram-reels://share` + pasteboard `backgroundVideo` + `appID` [5] | No | 3–60 s | Meta App ID ("Go Live"), `instagram-reels` scheme |
| `.instagramStories` | `instagram-stories://share?source_application=…` [4] | No | **20 s** | Meta App ID, `instagram-stories` scheme |

- **Fallback:** a direct route that can't open (app missing, key rejected) falls back to the share sheet. Same shape as Android's target list ending in the plain package.
- **Query schemes:** apps linked on or after iOS 27 get only 25 `LSApplicationQueriesSchemes` entries [27]; all of Phase 5 needs 7. Apple now recommends calling `open(_:)` and handling failure rather than probing with `canOpenURL`.

---

## 5. Not taken

- **9:16 cropping.** Not wanted for now (same decision as Android).
- **Opus → AAC transcode, WebM parts.** No iOS source produces them (§2).
- **Parts in Photos.** Decision 2, finding 4.
- **`UIDocumentInteractionController` with `net.whatsapp.movie`.** WhatsApp removed it from its iOS FAQ; undocumented [1].
- **Facebook SDK `ShareDialog` / Messenger `MessageDialog`.** One video each, and Messenger documents no video share [10][12]. The share sheet covers both.
- **X direct share.** Twitter Kit is gone; the web intent carries text only (**UNVERIFIED**, developer.x.com returned 402).
- **Instagram Stories direct route in v1.** Its 20 s cap turns 2:30 into eight parts posted one at a time (Decision 3).

---

## 6. Decisions

Answered 2026-09-28:

1. **Direct-open routes:** share sheet first; direct routes are a later, optional phase once registrations exist. → Phase 5 deferred.
2. **Where parts live:** inside the app (`Documents/Parts/`), not Photos.
3. **Instagram Stories:** through the share sheet, without pre-splitting; verify on a device that Instagram splits long Stories itself.

Open, for Phase 5 only:

4. Which registrations to create: Meta App ID (Instagram Reels/Stories), Snap Kit Client ID, TikTok client key plus a universal-link domain.
5. For TikTok: accept saving parts into Photos (add-only, never deleted by the app), since Share Kit only takes PHAssets.

---

## 7. Order and effort

| Order | Phase | Size | Blocks |
|---|---|---|---|
| 1 | 0 — device verification | S (manual, one sitting) | the numbers in Appendix A |
| 2 | 1 — `PublishPreset` + `cutPoints` + tests | S | — |
| 3 | 2 — `Splitter` + tests | M | 1 |
| 4 | 3 — sheet + entry points + strings | M | 2 |
| 5 | 4 — order by use | XS | 3 |
| 6 | 5 — direct routes | M each | Decisions 4–5 |

Phases 1–4 are the Android feature at parity. Phase 1 can start before Phase 0 ends: only the numbers in `PublishPreset.all` wait for it.

---

## 8. Risks

- **A receiver reads the copied span, not the edit list** (finding 2). Mitigation: keyframe cuts plus 0.5 s headroom. Phase 0 checks WhatsApp Status on iOS with a part at 89.5 s.
- **Share-extension limits change without notice.** They're data in one list; Phase 0's checklist (Appendix B) is the re-test.
- **A keyframe gap longer than the limit** (rare; screen recordings) gives a part that runs long, and the platform trims it. That is the same known ceiling as Android, marked `ponytail:` in `cutPoints`. Fixing it means re-encoding, which the feature avoids by design.
- **Instagram's share extension has broken on file URLs before** [thread 659326]. If Phase 0 reproduces it, the Instagram presets fall back to "Save to Photos, then share from Instagram"; the plan doesn't assume that.

---

## Appendix A — `PublishPreset.all` for v1 (numbers pending Phase 0)

| id | Label (en / ar) | maxSegment | Multi | maxItemsPerShare | Source |
|---|---|---|---|---|---|
| `whatsapp-status` | WhatsApp Status / حالة واتساب | 90 s | yes | 30 | WhatsApp FAQ [1][2]; S23 measured |
| `x-free` | X (free) / X (مجاني) | 140 s | no | — | X help [19]; S23 measured |
| `instagram-story` | Instagram Story / ستوري إنستغرام | nil | no | — | Decision 3; Phase 0 |
| `instagram-reels` | Instagram Reels / ريلز إنستغرام | 180 s? | no | — | Android value; **UNVERIFIED** for the iOS extension |
| `snapchat-story` | Snapchat Story / ستوري سناب شات | 120 s? | yes? | — | S23 measured on Android; **UNVERIFIED** on iOS |
| `telegram` | Telegram / تيليجرام | nil | yes | — | 2 GB/file [21]; extension has no count cap [20] |
| `messenger` | Messenger / ماسنجر | nil | yes? | — | S23 share screen took 2:30 and several videos; story not confirmed |
| `tiktok` | TikTok / تيك توك | nil | no | — | ~10 min (**UNVERIFIED**) [18] |
| `custom` | Custom / مخصص | user, 15 s–10 min | yes | — | — |

## Appendix B — Verify on a device (Phase 0)

1. WhatsApp: My Status appears in the extension; 2:30 in one part is trimmed at 90 s; a part at 89.5 s is **not** trimmed; 3 parts in one share all reach Status; 31 items → WhatsApp is not offered.
2. X: the extension lands in the post composer, not Chat; a 2:18 part posts on a free account.
3. Instagram: extension options (Story / Reels / Feed / Messages); 2:30 to Story, is it split?; 2:30 to Reels, is it accepted?
4. Snapchat: extension limit (Android says 120 s); several videos in one share.
5. TikTok: the extension accepts a file URL; the limit.
6. Telegram, Messenger: several videos in one share.
7. Split time for a 10-min / 375 MB file on the iPhone; a cancel mid-split leaves `Parts/` empty.
8. An AV1 output on a device *without* an AV1 decoder: does `compatibility(...)` pass?

## Appendix C — Android reference (branch `publish-presets`)

| Piece | Android file |
|---|---|
| Preset model, list, `requiresSplitting` | `publish/PublishPreset.kt` |
| Keyframe scan, `cutPoints`, split, `existingParts`, delete, share | `publish/Splitter.kt` |
| Part copy (`copyRange`) | `audio/Remux.kt` |
| Sheet, usage ordering | `ui/screen/PublishSheet.kt` |
| Entry points, library hides Parts | `ui/screen/JobsScreen.kt` |
| Usage counter | `data/Prefs.kt` (`presetUses`, `countPresetUse`) |
| Strings (en/ar) | `res/values*/strings.xml` (`preset_*`, `publish_*`) |
| Tests | `test/.../publish/CutPointsTest.kt` |

## 9. Sources

1. WhatsApp iOS sharing FAQ — https://faq.whatsapp.com/425247423114725/?cms_platform=iphone&locale=en_US
2. WhatsApp Status video limits — https://faq.whatsapp.com/454876960047011/?cms_platform=iphone&locale=en_US
3. WhatsApp iOS 25.22.83 (share to Status) — https://wabetainfo.com/whatsapp-for-ios-25-22-83-whats-new/
4. Instagram Sharing to Stories — https://developers.facebook.com/docs/instagram-platform/sharing-to-stories/
5. Instagram Sharing to Reels (iOS) — https://developers.facebook.com/documentation/ios/sharing-to-reels-instagram
10. Facebook Sharing on iOS — https://developers.facebook.com/docs/sharing/ios
12. Messenger sharing — https://developers.facebook.com/docs/sharing/messenger
13. Snapchat Creative Kit README — https://github.com/Snapchat/creative-kit/blob/main/README.md
14. Creative Kit Lite sample — https://github.com/Snapchat/creative-kit/tree/main/ios/CKLiteSample/CKLiteSample
15. Creative Kit overview — https://developers.snap.com/snap-kit/creative-kit/overview
16. TikTok OpenSDK iOS — https://github.com/tiktok/tiktok-opensdk-ios
17. `TikTokShareRequest.swift` — https://github.com/tiktok/tiktok-opensdk-ios/blob/main/Sources/TikTokOpenShareSDK/Public/TikTokShareRequest.swift
18. TikTok Share Kit quickstart (search excerpt only) — https://developers.tiktok.com/doc/share-kit-ios-quickstart-v2
19. X video limits — https://help.x.com/en/using-x/x-videos
20. Telegram share extension `Info.plist` — https://github.com/TelegramMessenger/Telegram-iOS/blob/master/Telegram/Share/Info.plist
21. Telegram FAQ — https://telegram.org/faq
23. `excludedActivityTypes` — https://developer.apple.com/documentation/uikit/uiactivityviewcontroller/excludedactivitytypes
24. Apple Developer Forums 115735 — https://developer.apple.com/forums/thread/115735
25. `NSExtensionActivationSupportsMovieWithMaxCount` — https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension/nsextensionattributes/nsextensionactivationrule/nsextensionactivationsupportsmoviewithmaxcount
26. App Extension Programming Guide — https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ExtensionScenarios.html
27. `canOpenURL(_:)` — https://developer.apple.com/documentation/uikit/uiapplication/canopenurl(_:)

AVFoundation and Photos: `AVAssetExportSession` `export(to:as:isolation:)`, `states(updateInterval:)`, `compatibility(ofExportPreset:with:outputFileType:)`; `AVSampleCursor`; `AVAssetWriter` `startSession`/`endSession`; `PHAccessLevel.addOnly`; `PHAssetChangeRequest.deleteAssets`; Apple Developer Forums threads 50358, 739067, 739953, 775937, 659326, 85066; FFmpeg formats (edit lists); Android `MPEG4Extractor.cpp`; ExoPlayer #10503. Measurements on an M3 Mac with the iOS 27 SDK, 2026-09-28 (synthetic H.264/HEVC/AV1/Opus clips; scripts were throwaway).
