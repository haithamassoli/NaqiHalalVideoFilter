# Naqi monorepo

Naqi filters video on the device: it removes music and censors faces or bodies. One repository
holds every app. Read the README of the directory you work in before changing it.

## Where things are

| Path | What | Licence |
| --- | --- | --- |
| `apps/android/` | Android app: Kotlin, Gradle, Compose; its docs in `apps/android/docs/` | GPL-3.0-or-later (root `LICENSE`) |
| `apps/apple/` | iPhone, iPad and Mac app: Swift 6, Xcode; its docs in `apps/apple/docs/` | Naqi Personal Non-Commercial License (`apps/apple/LICENSE`) |
| `apps/windows/` | Windows app (in development) | root licence unless it adds its own `LICENSE` |
| `models/` | model export, conversion and quantization scripts; benchmarks in `models/bench/` | GPL-3.0-or-later |
| `server/` | optional Modal server for videos of 10 minutes or more | GPL-3.0-or-later |
| `web/` | Astro website; Vercel builds from `web/`, so it must stay there | GPL-3.0-or-later |
| `docs/` | cross-platform docs: model benchmarks, screenshots, `docs/RELEASING.md` | — |

The licences differ, so ask before moving code between `apps/apple/` and the rest of the tree.

## Build and test

Android, from `apps/android` (needs a JDK; Android Studio's bundled JBR works). The model fetch
needs the root `.venv-bench` described in `apps/android/README.md`:

```sh
./scripts/fetch-models.sh                        # gitignored ONNX models, checked by SHA-256
./gradlew assembleDebug
./gradlew compileDebugKotlin testDebugUnitTest   # the gate; lintDebug is not a gate
```

Apple, from `apps/apple` (after the Android models are fetched; fonts and the Core ML person model
are extra steps in `apps/apple/README.md`):

```sh
./scripts/fetch-models.sh
xcodebuild -project naqi.xcodeproj -scheme naqi -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build.noindex/agent CODE_SIGNING_ALLOWED=NO build
xcodebuild test -project naqi.xcodeproj -scheme naqi -destination 'platform=macOS'  # naqiTests
```

Website, from `web`: `pnpm install && pnpm build`.

Paths inside an app are relative to that app's directory. `qa-assets/` and `.venv-bench/` live at
the repository root and are gitignored.

## Releases

Each app is tagged separately. Android: `vX.Y.Z` (the in-app updater and `web/src/release.ts` parse
these). Apple: `apple-vX.Y.Z`, Windows: `windows-vX.Y.Z`, both published with `--latest=false`.
Never give a non-Android release a bare `v…` tag. Rules in `docs/RELEASING.md`.

## Rules

- When an app is unsure, it censors. Never make it censor less without saying so.
- Every README has an Arabic twin (`README.ar.md`); change both.
- Never commit models, signing keys, `local.properties` or `NaqiCloud.plist`.
