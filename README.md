<p align="center">
  <img src="branding/naqi-icon-1024.png" width="128" alt="Naqi app icon: a jade play triangle over the bowl of an Arabic ن">
</p>

<h1 align="center">Naqi — Halal Video Filter</h1>

<p align="center">
  Android app that filters video <b>entirely on-device</b>: removes music from the audio and
  censors faces in the picture.<br>
  No cloud, no accounts, no telemetry. The original file is never modified.
</p>

<p align="center">
  <a href="https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/latest"><img src="https://img.shields.io/github/v/release/haithamassoli/NaqiHalalVideoFilter?include_prereleases&label=release" alt="Latest release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-blue" alt="Licence: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/Android-10%2B%20(API%2029)-3ddc84" alt="Android 10+">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-lightgrey" alt="arm64-v8a only">
</p>

<p align="center">
  <b>English</b> · <a href="README.ar.md">العربية</a>
</p>

*Naqi (نقي) means "pure" in Arabic.*

<p align="center">
  <img src="docs/screenshots/01-home.png" width="19%" alt="Pick a video and choose what to filter">
  <img src="docs/screenshots/02-options.png" width="19%" alt="Censor options: who, strictness, blur">
  <img src="docs/screenshots/03-jobs.png" width="19%" alt="Two-pass progress with a live estimate">
  <img src="docs/screenshots/05-link.png" width="19%" alt="Share a link: download and filter in one step">
  <img src="docs/screenshots/04-about.png" width="19%" alt="About, with the on-device model report">
</p>

## What it does

Two independent operations, run alone or together:

- **Remove music** — htdemucs stem separation splits the audio and keeps only the stems you choose
  (`vocals`, or `vocals + other` to retain sound effects at the cost of some music leakage).
  Drums and bass are never kept. A YAMNet gate answers "is there music in these 2.6 s?" first, so
  music-free stretches skip separation entirely.
- **Censor faces** — ML Kit finds and tracks faces across the video, and an InsightFace `genderage`
  vote resolves each track from up to 5 frontal crops. You pick **who** gets covered: everyone,
  women, or men. A track is censored for its whole span, not frame by frame.

Independently of faces, an optional **NSFW scene gate** covers the entire frame while it fires, with
pre-roll so nothing slips through on the first frame.

Adjustable: who gets censored, blur amount, a solid fill instead of blur (5 colours), grayscale,
whole-frame mode, and the NSFW gate's on/off plus strictness (0–100).

Also included:

- **Download by link** — share or paste a video URL and Naqi fetches it with yt-dlp, then filters
  it. Pick the quality (best / 1080p / 720p / 480p / audio-only). The download lands in a quarantine
  directory and is only published once complete.
- **Share into Naqi** — share a video from any app and it queues straight away with the filters you
  used last time already selected. Share several and they run in order.
- **Audio files too** — MP3 and M4A go through music removal on their own, no video needed.
- **Long videos** — feature-length input checkpoints per segment and survives process death, a
  reboot, and the 6-hour foreground-service cap.
- **English and Arabic**, with per-app language selection on Android 13+.

Input formats: MP4 · MKV · WebM · MP3 · M4A.

## Privacy

All inference runs locally. The app requests `INTERNET` for exactly three things: the optional
one-time model download, the link-download feature you explicitly invoke, and the weekly check for a
new app or yt-dlp version. Nothing is uploaded, there are no analytics SDKs, and no account is
required.

## Install

Grab the APK from [Releases](https://github.com/haithamassoli/NaqiHalalVideoFilter/releases). Once
installed, Naqi checks GitHub for newer releases itself and offers the update in-app — a build
installed from a pre-release stays on the pre-release channel.

**The APK here is the full build.** Download-by-link, share-into-Naqi and the in-app updater ship
only in this one. A Play Store build cannot carry them: yt-dlp fetches and updates executable code at
runtime, which Play's Device and Network Abuse policy does not allow, and Play bans self-updating
apps outright. The filtering itself is the same either way.

**Requirements:** Android 10 (API 29) or newer, **arm64-v8a** only. Free space of roughly
2× the video size. The APK is large because the ONNX models ship inside it.

## Build from source

The models are gitignored (~105 MB of ONNX), so a fresh clone must fetch them first:

```bash
./scripts/fetch-models.sh
```

That script downloads what has a public host (`yamnet`, InsightFace `genderage`) and quantizes the
INT8 NSFW gate locally. `nsfw_mnv2_140_f32.onnx` and `htdemucs_s26_f16.onnx` are locally converted
artifacts with no public host — the regeneration pipelines (venvs, commands, parity checks) are in
[`docs/m0-spikes.md`](docs/m0-spikes.md). Alternatively, host them yourself and pass
`-PnaqiModelBaseUrl=https://your-host/path` so the app downloads them on first use instead.

Then:

```bash
./gradlew assembleDebug
./gradlew compileDebugKotlin testDebugUnitTest   # the green gate
```

Signing a release build needs four Gradle properties — put them in `~/.gradle/gradle.properties`,
never in the repo:

```properties
naqiStoreFile=/absolute/path/to/naqi.jks
naqiStorePassword=...
naqiKeyAlias=...
naqiKeyPassword=...
```

Without them `assembleRelease` still builds, just unsigned.

## How it works

Analysis and rendering are two separate passes, and that is not an optimization — majority-vote
gender needs the complete face track before its first frame can be rendered, and the censor
pre-roll needs the NSFW timeline ahead of the encoder.

**Pass 1 (decode only)** samples the NSFW gate at 5 fps and faces at 10 fps, resolves each face
track's gender from up to 5 frontal crops, and emits an EDL: censor intervals plus per-frame face
regions.

**Pass 2** replays the EDL through a Media3 Transformer GL shader — full-frame effect wins over
face regions — and encodes H.264. Censor-only jobs pass the audio through untouched; music-only
jobs remux the original video samples with no re-encode at all.

Audio decodes to 44.1 kHz f32 stereo, runs htdemucs in overlapping chunks, and sums only the kept
stems per chunk as they resolve, so a 2-hour film never materializes four full stems in memory.

More detail lives in [`docs/prd-video-filter-android.md`](docs/prd-video-filter-android.md) and
[`docs/long-film-plan.md`](docs/long-film-plan.md); the performance work is written up across
[`docs/perf-plan-v5.md`](docs/perf-plan-v5.md) and its predecessors.

### The models

| Model | Job | Size | Licence |
| --- | --- | --- | --- |
| [htdemucs](https://github.com/facebookresearch/demucs) (2.6 s segment, fp16) | music/vocal stem separation | 84 MB | MIT |
| [YAMNet](https://github.com/tensorflow/models/tree/master/research/audioset/yamnet) | "is there music here?" gate before separation | 15 MB | Apache-2.0 |
| [nsfw_model](https://github.com/GantMan/nsfw_model) MobileNetV2 1.4-224, INT8 | NSFW scene gate | 4.9 MB | NOASSERTION — see [`NOTICE`](NOTICE) |
| [InsightFace](https://github.com/deepinsight/insightface) `genderage` (buffalo_l) | per-face-track gender vote | 1.3 MB | code MIT, weights research-use — see [`NOTICE`](NOTICE) |
| ML Kit Face Detection | face detection and tracking | — | proprietary, Google Play services |

Everything runs on [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT) on the CPU.

## Accuracy, honestly

The models are good, not perfect. A face never seen frontally may go unresolved, and the gender vote
was measured at ~92% balanced accuracy on a small internal set — expect mistakes on partial faces,
children and non-faces that ML Kit reports as faces. High strictness deliberately over-censors —
that is the safe direction, and it is the intended behaviour, not a bug. Music removal keeps singing
along with dialogue, because to a stem separator they are the same thing.

If you want the strictest possible result, pick **everyone** rather than a gender, and turn the
whole-frame mode on.

## Contributing

Issues and pull requests are welcome — start with [`CONTRIBUTING.md`](CONTRIBUTING.md). The build
gate is `./gradlew compileDebugKotlin testDebugUnitTest`; `lintDebug` currently reports pre-existing
errors and is not a gate. Security reports go to [`SECURITY.md`](SECURITY.md).

## Licence

**GPL-3.0-or-later.** See [`LICENSE`](LICENSE). Naqi links youtubedl-android (GPL-3.0), which is
what determines the licence of the whole.

⚠️ One bundled model carries terms that are **not yet resolved**: the NSFW gate weights are
NOASSERTION upstream. The details and the reasoning are in [`NOTICE`](NOTICE) — read it before
redistributing this app or shipping it to an app store. Full third-party attribution is in the same
file, and the app surfaces it in-product under About → Open source licenses.
