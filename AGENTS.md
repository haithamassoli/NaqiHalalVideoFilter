# Naqi monorepo

Naqi filters video on the device: it removes music and censors faces or bodies. One repository
holds every app. Read the README of the directory you work in before changing it.

## Where things are

| Path | What | Licence |
| --- | --- | --- |
| `apps/android/` | Android app: Kotlin, Gradle, Compose; its docs in `apps/android/docs/` | GPL-3.0-or-later (root `LICENSE`) |
| `apps/apple/` | iPhone, iPad and Mac app: Swift 6, Xcode; its docs in `apps/apple/docs/` | Naqi Personal Non-Commercial License (`apps/apple/LICENSE`) |
| `apps/windows/` | Windows app (beta): Kotlin/JVM 21, Compose Desktop, JavaCPP FFmpeg, ONNX Runtime on the CPU; package contract in `apps/windows/SEAMS.md` | GPL-3.0-or-later (root `LICENSE`) |
| `apps/web/` | Naqi Web: in-browser filter, no backend; vanilla TypeScript, Vite, WebCodecs (Mediabunny), ONNX Runtime Web on WebAssembly; desktop Chrome/Edge | GPL-3.0-or-later (root `LICENSE`) |
| `core/` | pure-Kotlin logic shared by `apps/android` and `apps/windows` (EDL, NSFW gate, face-track rules, demucs chunking, DSP); both builds include it as `:core` | GPL-3.0-or-later |
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

Windows, from `apps/windows` (JDK 21; runs on macOS too; the models come from Android's folder or
the release APK, see `apps/windows/README.md`):

```sh
./gradlew :app:stageModels                      # gitignored ONNX models into apps/windows/models, sha256-checked
./gradlew :app:compileKotlin :core:test :app:test   # the gate; model tests skip without the models
./gradlew :app:run                              # the window; with --args="--input … --output …" the CLI
```

Web, from `apps/web` (Node.js and pnpm; the vision models come from both Windows' and Android's
staged folders, YuNet from Windows and YOLO11n-seg from Android's exporter, see `apps/web/README.md`):

```sh
./scripts/fetch-models.sh                       # vision models into public/models (gitignored), sha256-checked
./scripts/build-spleeter.sh                     # the Spleeter music model (needs uv), sha256-checked
pnpm install && pnpm test && pnpm build         # the gate
pnpm dev                                        # the page at http://localhost:5173
```

A change to `core/` must pass both Android's and Windows' tests. Windows is ported from
`apps/android`; never port from `apps/apple` (different licence). `apps/web` ports `core/` to
TypeScript, so a change to the Kotlin rules needs the same change and test there.

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
