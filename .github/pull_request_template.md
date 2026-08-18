## What and why

<!-- One concern per PR. If a docs/plan-*.md covers this seam, name it. -->

## How it was checked

- [ ] `./gradlew compileDebugKotlin testDebugUnitTest` is green
- [ ] Tested on a physical device (which one: ______ ) — the emulator lies about ONNX Runtime
- [ ] Perf claims, if any, are back-to-back runs on a cold phone, not a single before/after pair

## Behaviour

- [ ] This does **not** make the app censor less. If it does, say so here in those words:
- [ ] `FilterOps` wire formats (`queue.json`, WorkManager `Data`, `Prefs`, debug intent) still load old files
- [ ] New or re-exported model? Then: regeneration steps in `docs/m0-spikes.md`, new SHA-256 in `NaqiModel`, entry in `NOTICE`
- [ ] Touched `README.md`? The same change is in `README.ar.md`
