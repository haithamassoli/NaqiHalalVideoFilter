# Contributing to Naqi

Thanks for looking. Issues and pull requests are both welcome.

## Before you write code

Read `docs/` first. `docs/plan-*.md` and `docs/perf-plan-*.md` are committed specs: they pick the
seam a change should land on and list the alternatives already rejected, with the measurements that
rejected them. A change that reopens a settled decision needs to say why the old measurement no
longer holds. This is the single biggest time-saver in this repo.

## Setting up

```bash
./scripts/fetch-models.sh          # models are gitignored; see the README
./gradlew assembleDebug
```

Two of the five ONNX artifacts have no public host and the script will tell you so — regenerate them
per [`docs/m0-spikes.md`](docs/m0-spikes.md), or point the app at your own host with
`-PnaqiModelBaseUrl=...`.

## The build gate

```bash
./gradlew compileDebugKotlin testDebugUnitTest
```

That is the gate — it must be green. `./gradlew lintDebug` currently reports pre-existing errors;
read its output for your own change, but it is not a gate and fixing all of it is not your problem.

## What a good pull request looks like

- **One concern per PR.** A perf change and a UI change in the same diff are two PRs.
- **Measure perf claims on a physical device.** The emulator lies about ONNX Runtime, and analysis
  wall-clock swings ±30% run to run from SoC heat alone — a single before/after pair proves nothing.
  Say which device, and run the comparison back to back on a cold phone.
- **Never lower the safe direction silently.** When this app is unsure, it censors. A change that
  makes it censor less needs to say so in the PR body, in those words.
- **Keep the wire formats.** `queue.json`, the WorkManager `Data` map, `Prefs` and the debug intent
  all serialize `FilterOps`. Old files must still load.
- **Comments explain why, not what.** Match the density of the file you are editing.

## Model changes

Any new or re-exported model needs: the regeneration pipeline written into `docs/m0-spikes.md`, a
fresh `shasum -a 256` in `NaqiModel`, a licence entry in `NOTICE`, and a parity check against the
artifact it replaces. fp16 conversions have corrupted this app three separate times — verify on
hardware, not on the emulator.

## Translations

Strings live in `app/src/main/res/values/strings.xml` (English) and `values-ar/` (Arabic). Both
READMEs are hand-written, not generated: a change to `README.md` should carry the same change into
`README.ar.md`.
