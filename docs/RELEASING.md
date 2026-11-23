# Releasing

Every platform publishes its GitHub Releases in this repo. Installed Android builds and the website both read that release list, so tags and the "Latest" flag need to follow these rules.

## Tags

| Platform | Tag | Example | Mark as Latest |
| --- | --- | --- | --- |
| Android | `vX.Y.Z` (pre-release: `vX.Y.Z-betaN`) | `v1.9.0` | yes (the default) |
| Windows | `windows-vX.Y.Z` | `windows-v1.0.0` | **no**, pass `--latest=false` |
| Apple (iOS, iPadOS, macOS) | `apple-vX.Y.Z` | `apple-v1.0.0` | **no**, pass `--latest=false` |

```sh
# Android
gh release create v1.10.0 naqi-1.10.0.apk

# Windows / Apple
gh release create windows-v1.0.0 --latest=false Naqi-1.0.0-x64.msix
gh release create apple-v1.0.0   --latest=false Naqi-1.0.0.dmg
```

If a desktop release goes out marked Latest by mistake, run `gh release edit v<newest android> --latest` to move the flag back.

## Why

Installed Android builds check for updates themselves (`apps/android/app/src/main/java/com/haithamassoli/naqi/update/AppUpdate.kt`). They fetch `/releases?per_page=N` and only offer a release that:

- is not a draft (pre-releases only reach builds that are pre-releases themselves),
- has a tag that parses as a version, meaning a digit after an optional `v`, and
- has an `.apk` asset.

A `windows-v…` or `apple-v…` tag fails the second check, so builds that have already shipped ignore desktop releases without an app update. `AppUpdateTest.desktopReleasesAreIgnoredAndDoNotHideTheAndroidOne` covers this. **Never give a non-Android release a bare `v…` tag.** An Android build would then treat it as an update, and it would be filtered out only because it has no `.apk`.

The website (`web/src/release.ts`) builds its "Download the APK" link from the newest non-draft, non-pre-release whose tag matches `^v\d` and that has an `.apk`. `--latest=false` keeps GitHub's own "Latest" badge and the `/releases/latest` fallback link on Android too.

## Page depth

Only the first page of releases is read. That page holds 30 releases in builds after the monorepo move, and **10** in older builds. If more than 9 desktop releases are published after the newest Android one, older builds can't see it and stop offering updates until the next Android release. Keep desktop releases below that limit, or publish an Android release in between.

Each published release, desktop ones included, also triggers a website rebuild (`.github/workflows/web.yml`). That is harmless.
