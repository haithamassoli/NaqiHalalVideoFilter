# Play Console — the REQUEST_INSTALL_PACKAGES declaration

> **Status: done (Option A).** The self-updater is deleted and the permission is out of the manifest,
> so the Console no longer asks. About → Updates links to the Releases page instead. The rest of this
> document is why, and what Play will ask for next.

> Console message: *"يستخدم تطبيقك الأذن android.permission.REQUEST_INSTALL_PACKAGES الذي لم يتم
> تضمينه في نموذج البيان"* — your app uses REQUEST_INSTALL_PACKAGES and has not filled in the
> declaration form.

## Short answer

**Do not fill in the form. Remove the permission from the Play build.**

There is no honest set of boxes to tick, and the dishonest ones are worse than a rejection.

## Why no box fits

The form asks what Naqi's **core purpose** is, from a fixed list. Naqi's core purpose is filtering
video on-device. It holds `REQUEST_INSTALL_PACKAGES` for exactly one reason: `AppUpdate.kt:186-197`
downloads an APK from GitHub Releases and opens the system installer on it. That is a self-update,
and self-update is not on the list because Play forbids it:

> **Device and Network Abuse** — "An app distributed via Google Play may not modify, replace, or
> update itself using any method other than Google Play's update mechanism."

So the two ways to answer both lose:

| Answer | Outcome |
|---|---|
| مشاركة الملفات أو نقلها أو إدارتها (*File sharing / transfer / management*) | **False declaration.** Naqi shares nothing and manages no files. This is not a rejection risk, it is an account risk. |
| غير ذلك (*Other*) + وظائف التطبيق (*App functionality*) | Honest — and it declares the policy violation above in writing. Rejected. |

If Naqi is **not** going to Play and stays a GitHub Releases APK, close the form and change nothing.
The permission is correct and justified there; the comment at `AndroidManifest.xml:14-17` already
says so.

## What to do for a Play build

On Play, **Play is the update channel**, so the whole feature is redundant there, not merely
disallowed. Once the permission is out of the merged manifest the Console stops asking.

### Option A — delete the self-updater — **this is what was done**

| Deleted | |
|---|---|
| `update/AppUpdate.kt` | 252 lines |
| `ui/UpdateCard.kt` | 219 lines |
| `test/.../AppUpdateTest.kt` | 10 tests (128 → 118) |
| `PickOpsScreen.kt` import + call site | 6 lines |
| `Prefs.kt` — 4 `KEY_APP_UPDATE_*`, `APP_UPDATE_INTERVAL_MS`, `NO_DOWNLOAD`, 6 accessors | ~34 lines |
| `AndroidManifest.xml` — the `<uses-permission>` | plus its comment |
| `strings.xml` + `values-ar` — 12 `update_*` keys | −12, +3 (`about_*`) |
| `NaqiIcons.Download` | dead once the card went |

Added in its place: an **About → Updates** row that opens
`https://github.com/haithamassoli/NaqiHalalVideoFilter/releases` in a browser
(`AboutScreen.kt`, ~30 lines).

`INTERNET` stays — `ModelDownloader` still needs it if models are fetched rather than bundled.

The four `app_update_*` keys already written into `naqi_share_prefs` on existing installs are left
there. They are orphan scalars; a migration would cost more than they do.

**One-time upgrade note:** anyone on 1.2 or earlier still has the old updater, so it delivers 1.3 for
them one last time. From 1.3 on, the About row is the only signal a new build exists.

### Option B — keep both channels

Only if GitHub Releases stays a real distribution channel alongside Play. Costs two variants forever.

```kotlin
// app/build.gradle.kts
flavorDimensions += "channel"
productFlavors {
    create("github") { isDefault = true }
    create("play") { }
}
```

```xml
<!-- app/src/play/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" tools:node="remove" />
</manifest>
```

```kotlin
// PickOpsScreen.kt:134 — the card must not render in the Play build either
if (BuildConfig.FLAVOR == "github") UpdateCard(Modifier.padding(bottom = NaqiTokens.space5))
```

`AppUpdate.kt` and `UpdateCard.kt` stay in `main` and simply never run on `play`. Note this renames
the build tasks: `assembleRelease` → `assembleGithubRelease` / `assemblePlayRelease`, and
`bundlePlayRelease` for the AAB.

**Do not** try to keep the update *check* in the Play build and merely link out to the GitHub
release page. Pointing users at a sideloadable APK is the same policy, one step removed.

### Verify either way

```sh
./gradlew :app:processReleaseManifest   # or processPlayReleaseManifest
grep -c REQUEST_INSTALL_PACKAGES app/build/intermediates/merged_manifest/*/*/AndroidManifest.xml
```

Zero is the pass. The Console reads the merged manifest, not the source one.

## Other things Play will stop you on

Not part of this form, listed so they are not discovered one rejection at a time.

1. **App bundle, and its size cap.** Play takes an `.aab`, not an `.apk`, for a new app, and caps the
   base + config **download** size (200 MB at last check — confirm in Console, it moves). The release
   APK is **179.7 MiB**, of which 125.9 MiB is ONNX in `assets/` and compresses badly. This is tight
   enough to check *first*, before any other Play work.
   Free headroom: `assets/models/nsfw_mnv2_140_f32.onnx` (17 MB) is **not** in the `NaqiModel` enum
   and is never loaded — `Models.kt:82` ships the int8 graph. Keep it out of `assets/` but keep it
   somewhere: `scripts/nsfw_int8_quantize.py:40` and `scripts/fetch-models.sh:19` consume it and it
   has no public host.

2. **`NOTICE:12-19` is still unresolved.** The NSFW gate weights (GantMan/nsfw_model) are NOASSERTION
   upstream. This blocks any public distribution, but Play enforces it hardest — you attest that you
   have the rights to everything you ship. Four GitHub releases have shipped past this banner; a Play
   submission is the wrong place to keep doing that.

3. **Licence.** GPL-3.0-or-later was forced by youtubedl-android, which is now gone, so the current
   licence is a free choice (`NOTICE:9-12`). GPLv3 on Play has known friction with the Developer
   Distribution Agreement's additional restrictions. Not a blocker, but relicensing is now on the
   table if it becomes one.

4. **Foreground-service declaration comes next.** targetSdk 36 + `dataSync` and `mediaProcessing`
   means the Console will ask for a use-case description and a **video demonstration link** for each
   type. Prepare a short screen recording of a filter job running.

5. **Data safety form.** Easy and genuinely in Naqi's favour: no data collected, no data shared, all
   processing on-device. Say exactly that.
