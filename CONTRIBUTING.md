# Contributing to Naqi

Thanks for looking. Issues and pull requests are both welcome.

This repository holds every Naqi app: Android in `apps/android/`, iPhone, iPad and Mac in
`apps/apple/`, and Windows (in development) in `apps/windows/`. Model tooling shared by the apps is
in `models/`, the optional server in `server/`, and the website in `web/`. The
[README](README.md#repository-layout) has the full layout.

## Before you write code

Read `docs/` and the docs of the app you are changing (`apps/android/docs/`, `apps/apple/docs/`)
first. `apps/android/docs/plan-*.md` and `apps/android/docs/perf-plan-*.md` are committed specs, as
are the `apps/apple/docs/apple-port/spec-*.md` files: they pick the seam a change should land on and
list the alternatives already rejected, with the measurements that rejected them. A change that
reopens a settled decision needs to say why the old measurement no longer holds. This is the single
biggest time-saver in this repo.

## Licences

The root is GPL-3.0-or-later ([`LICENSE`](LICENSE)). `apps/apple/` is not: it carries the Naqi
Personal Non-Commercial License in [`apps/apple/LICENSE`](apps/apple/LICENSE), which overrides the
root licence there. Read the licence of the directory you are changing before you contribute to
it.

## Android

### Setting up

From the repository root (the body-model exporter's virtualenv lives there):

```bash
python3 -m venv .venv-bench
.venv-bench/bin/pip install ultralytics==8.4.171 onnx==1.23.1 torch==2.14.0
apps/android/scripts/fetch-models.sh   # models are gitignored; see apps/android/README.md
cd apps/android
./gradlew assembleDebug
```

Two of the five ONNX artifacts have no public host and the script will tell you so — regenerate them
per [`apps/android/docs/m0-spikes.md`](apps/android/docs/m0-spikes.md), or point the app at your own
host with `-PnaqiModelBaseUrl=...`.

### The build gate

```bash
cd apps/android
./gradlew compileDebugKotlin testDebugUnitTest
```

That is the gate — it must be green. `./gradlew lintDebug` currently reports pre-existing errors;
read its output for your own change, but it is not a gate and fixing all of it is not your problem.

## iPhone, iPad and Mac

Setup (models staged from `apps/android`, the Core ML person model, fonts) is in
[`apps/apple/README.md`](apps/apple/README.md#build-from-source). Build with Xcode or `xcodebuild`
from `apps/apple`; the test target is `naqiTests` in the `naqi` scheme.

## What a good pull request looks like

- **One concern per PR.** A perf change and a UI change in the same diff are two PRs, and so is the
  same change made to two apps.
- **Measure perf claims on a physical device.** The Android emulator lies about ONNX Runtime, and
  analysis wall-clock swings ±30% run to run from SoC heat alone — a single before/after pair proves
  nothing. Say which device, and run the comparison back to back on a cold device.
- **Never lower the safe direction silently.** When an app is unsure, it censors. A change that
  makes it censor less needs to say so in the PR body, in those words.
- **Keep the wire formats.** On Android, `queue.json`, the WorkManager `Data` map, `Prefs` and the
  debug intent all serialize `FilterOps`. Old files must still load.
- **Comments explain why, not what.** Match the density of the file you are editing.

## Model changes

Any new or re-exported model needs: the regeneration pipeline written into
`apps/android/docs/m0-spikes.md`, a fresh `shasum -a 256` in `NaqiModel`, a licence entry in
`NOTICE`, and a parity check against the artifact it replaces. The export and conversion scripts are
in `models/`. fp16 conversions have corrupted the Android app three separate times — verify on
hardware, not on the emulator.

The Apple app builds its models from the Android ones (`apps/apple/scripts/fetch-models.sh`), so a
model change on Android also changes what the Apple build stages. Its licence entries are in
[`apps/apple/NOTICE`](apps/apple/NOTICE).

## Translations

Android strings live in `apps/android/app/src/main/res/values/strings.xml` (English) and
`values-ar/` (Arabic). Apple strings live in `apps/apple/naqi/Resources/Localizable.xcstrings`,
`apps/apple/naqi/Resources/InfoPlist.xcstrings` and `apps/apple/NaqiShare/Localizable.xcstrings`.

Every README is hand-written in English and Arabic, not generated: a change to a `README.md` should
carry the same change into the `README.ar.md` next to it.
