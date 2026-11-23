# Releasing

Every platform publishes its GitHub Releases in this repo. Installed Android builds and the website both read that release list, so tags and the "Latest" flag need to follow these rules.

## Tags

| Platform | Tag | Example | Mark as Latest |
| --- | --- | --- | --- |
| Android | `vX.Y.Z` (pre-release: `vX.Y.Z-betaN`) | `v1.9.0` | yes (the default) |
| Windows | `windows-vX.Y.Z`, pre-release | `windows-v1.0.0` | **no**; the Release Windows workflow publishes it |
| Apple (iOS, iPadOS, macOS) | `apple-vX.Y.Z` | `apple-v1.0.0` | **no**, pass `--latest=false` |

```sh
# Android
gh release create v1.10.0 naqi-1.10.0.apk

# Apple
.github/scripts/release-depth.sh 9   # see "Page depth" below
gh release create apple-v1.0.0 --latest=false Naqi-1.0.0.dmg
```

### Windows

Never publish a Windows release with `gh release create` by hand. Bump `appVersion` in `apps/windows/app/build.gradle.kts` on `main`, then run the Release Windows workflow (`.github/workflows/release-windows.yml`) from `main`:

```sh
gh workflow run release-windows.yml -f version=0.1.0   # or Actions > Release Windows > Run workflow
```

It runs, in this order:

1. **Guard.** `release-depth.sh 9` (below): it stops before building anything if one more release would push the newest Android release out of the 10 newest. It also stops if the version is not `appVersion`, the run is not on `main`, or the tag `windows-v<version>` already exists. Then it downloads the GPL sources (step 3) and checks their sha256.
2. **Build.** The Windows workflow (`.github/workflows/windows.yml`) on `windows-latest`: stage the models, `:core:test :app:test`, `:app:packageMsi :app:packagePortableZip`.
3. **Publish.** It runs the page-depth check again, then creates `windows-v<version>` on the commit it built, with `--prerelease --latest=false`. The release carries the MSI, the portable zip and the complete corresponding source of the GPL FFmpeg build bundled inside: the FFmpeg, x264 and x265 tarballs and the JavaCPP Presets build scripts, pinned to exact releases and commits in `.github/scripts/gpl-sources.sh`. `apps/windows/NOTICE` links the same files.

The builds are unsigned while the app is in beta. When the FFmpeg version in `apps/windows/settings.gradle.kts` changes, re-pin the sources in `gpl-sources.sh` (the header says how) and in `NOTICE`; the Windows workflow fails until they agree.

If a desktop release goes out marked Latest by mistake, run `gh release edit v<newest android> --latest` to move the flag back.

## Why

Installed Android builds check for updates themselves (`apps/android/app/src/main/java/com/haithamassoli/naqi/update/AppUpdate.kt`). They fetch `/releases?per_page=N` and only offer a release that:

- is not a draft (pre-releases only reach builds that are pre-releases themselves),
- has a tag that parses as a version, meaning a digit after an optional `v`, and
- has an `.apk` asset.

A `windows-v…` or `apple-v…` tag fails the second check, so builds that have already shipped ignore desktop releases without an app update. `AppUpdateTest.desktopReleasesAreIgnoredAndDoNotHideTheAndroidOne` covers this. **Never give a non-Android release a bare `v…` tag.** An Android build would then treat it as an update, and it would be filtered out only because it has no `.apk`.

The website (`web/src/release.ts`) builds its "Download the APK" link from the newest non-draft, non-pre-release whose tag matches `^v\d` and that has an `.apk`. `--latest=false` keeps GitHub's own "Latest" badge and the `/releases/latest` fallback link on Android too.

## Page depth

Installed Android builds read only the first page of releases: 30 in builds after the monorepo move, and **10** in older builds. `--latest=false` does not change that order, so desktop releases still push the Android one down the page.

**Rule: the newest Android release must stay within the 10 newest releases. Publish no more than 9 non-Android releases after it; otherwise cut an Android release first.** Older builds that can't see it stop offering updates until the next Android release.

The Release Windows workflow checks this before it builds and again before it publishes. Before publishing an Apple release by hand, check it yourself:

```sh
.github/scripts/release-depth.sh 9   # fails if one more release would push the Android one out of the 10 newest
```

Without an argument it checks the current list against 10. The Release depth workflow (`.github/workflows/release-depth.yml`) runs that after every published release and goes red if the rule has been broken, which catches what was published by hand. The website reads 100 releases, so it is not affected.

Each published release, desktop ones included, also triggers a website rebuild (`.github/workflows/web.yml`). That is harmless.

## Remote config

Apple builds refresh their YouTube client list weekly from `apps/apple/config/youtube-clients.json` on `main` of this repo. Builds released before the monorepo move read `config/youtube-clients.json` from the old standalone repo (`haithamassoli/naqi`) instead, so when you change the file, update that copy too for as long as those builds are in use. A missing or invalid file is harmless: the app keeps its cached list or its built-in defaults.
