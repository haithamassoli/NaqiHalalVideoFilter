## What and why

<!-- One concern per PR. If a committed plan or spec covers this seam
     (apps/android/docs/plan-*.md, apps/apple/docs/apple-port/spec-*.md), name it. -->

**Touches:** <!-- apps/android, apps/apple, apps/windows, models, server, web, docs -->

## How it was checked

Android (`apps/android`):
- [ ] `./gradlew compileDebugKotlin testDebugUnitTest` is green
- [ ] Tested on a physical device (which one: ______ ) — the emulator lies about ONNX Runtime

iPhone, iPad, Mac (`apps/apple`):
- [ ] Builds in Xcode, and the `naqiTests` tests I touched pass
- [ ] Tested on (device or Mac, OS version: ______ )

All:
- [ ] Perf claims, if any, are back-to-back runs on a cold device, not a single before/after pair

## Behaviour

- [ ] This does **not** make the app censor less. If it does, say so here in those words:
- [ ] Android: `FilterOps` wire formats (`queue.json`, WorkManager `Data`, `Prefs`, debug intent) still load old files
- [ ] New or re-exported model? Then: regeneration steps in `apps/android/docs/m0-spikes.md`, new SHA-256 in `NaqiModel`, entry in `NOTICE` (and `apps/apple/NOTICE` if the Apple app bundles it)
- [ ] Touched a `README.md`? The same change is in the `README.ar.md` next to it
- [ ] Code under `apps/apple/` is under its own licence (`apps/apple/LICENSE`), not GPL-3.0
