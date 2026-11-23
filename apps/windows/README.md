<p align="center">
  <img src="../../branding/naqi-icon-1024.png" width="128" alt="Naqi app icon: a jade play triangle over the bowl of an Arabic ن">
</p>

<h1 align="center">Naqi for Windows</h1>

<p align="center">
  A Windows app that filters a video file <b>on your computer</b>: it removes music from the audio and
  censors faces in the picture. The original file is never modified.
</p>

<p align="center">
  <a href="../../LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-blue" alt="Licence: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/status-beta%2C%20in%20development-orange" alt="Status: beta, in development">
  <img src="https://img.shields.io/badge/Windows-10%2F11%20x64-0078d4" alt="Windows 10 or 11, x64">
</p>

<p align="center">
  <b>English</b> · <a href="README.ar.md">العربية</a>
</p>

This is the Windows app in the Naqi monorepo. What Naqi does, the privacy statement and the other
platforms are in the [main README](../../README.md). It is a port of the Android app
([`../android`](../android)), which stays the reference: the decision logic (EDL, NSFW gate, face track
rules, music-removal chunking) is the same Kotlin, shared through [`core/`](../../core).

**Status: beta, in development.** There is no signed release yet. Build it from source, or take the
unsigned MSI or portable zip from the artifacts of the
[Windows workflow](../../.github/workflows/windows.yml) run on GitHub Actions.

## What works

- **Input:** a local video file (MP4, MOV, M4V, MKV, WebM, AVI, TS and others FFmpeg reads). Pick it
  in the file dialog or drop it on the window.
- **Output:** an MP4 next to the input (`<name>-naqi.mp4`) or wherever you choose. It never overwrites
  the input. It is written to a temp file and renamed only when complete, so a failure or a cancel
  leaves nothing behind.
- **Remove music:** the YAMNet gate (for videos of 10 minutes or more, as on Android) and htdemucs on
  the CPU. Keep `vocals`, or `vocals + other` to retain sound effects at the cost of some music leakage.
- **Censor faces:** who gets covered is everyone, women or men. Faces are found with YuNet and tracked
  across frames, and each track gets up to 5 InsightFace `genderage` votes. A tie, an abstention or a
  face too small to classify means the face is censored.
- **Style:** blur strength, a solid fill instead of blur (5 colours), grayscale, and whole-frame mode
  (the whole picture is covered while a censored face is on screen).
- **NSFW scene gate:** covers the whole frame while it fires, with strictness 0–100.
- **Timing:** every frame keeps its original timestamp, the audio keeps its offset, and rotation
  metadata is carried over. When nothing in the picture changes, the video is copied, not re-encoded.
- **Interface** in English and Arabic (right to left), following the system language. Jobs run one at
  a time with progress, an estimate and cancel.
- **Command line** for scripted use; see below.

## Not yet

These are deferred, not forgotten: body and precise-body coverage, download by link, the end card,
publish presets and the splitter, GPU inference (DirectML), ARM64 Windows, an auto-updater, code
signing and MSIX. Long videos are not checkpointed, so an interrupted job starts again from the
beginning.

## Differences from Android

| | Android | Windows |
| --- | --- | --- |
| Face detector | ML Kit (with its tracking ids) | YuNet (opencv_zoo, MIT), tracked by box overlap |
| Inference | ONNX Runtime on the phone | ONNX Runtime 1.27.0 on the CPU |
| Video | Media3 / MediaCodec | FFmpeg (JavaCPP, GPL build), libx264 CRF 18, AAC 192 kbps |
| Coverage | Face, Body, Precise body, Frame | Face, Frame |

## Build and run

You need JDK 21 (Android Studio's bundled JBR works) and the models. Everything below runs from
`apps/windows`, on Windows and on macOS (which is how it is tested day to day).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS; any JDK 21

./gradlew :app:stageModels         # the ONNX models into apps/windows/models (gitignored), sha256-checked
./gradlew :app:run                 # the window
./gradlew :core:test :app:test     # the tests
./gradlew :app:packagePortableZip  # zipped app image, any OS
./gradlew :app:packageMsi          # the installer, Windows only
```

On Windows, run these from Git Bash (`./gradlew`) or from a Command Prompt (`gradlew.bat`).
`stageModels` needs Git for Windows, because it runs `scripts/stage-models.sh` with Git's bash.

**Models.** `stageModels` copies four models from Android's `../android/app/src/main/assets/models`
and downloads YuNet. If Android's models are not there, it runs Android's `fetch-models.sh`, which
needs the Python setup in [`apps/android/README.md`](../android/README.md). The quicker route, which
CI uses, takes them from a release APK:

```sh
curl -fLo /tmp/naqi.apk https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.9.0/naqi-1.9.0.apk
(cd ../android/app/src/main && jar xf /tmp/naqi.apk assets/models/genderage.onnx \
  assets/models/htdemucs_s26_f16.onnx assets/models/nsfw_mnv2_140_int8.onnx assets/models/yamnet.onnx)
./gradlew :app:stageModels
```

The tests that need a model skip when it is missing. With the models, `PipelineTest` runs whole jobs
end to end on a clip it generates.

## Command line

The same binary runs a single job without a window when it gets arguments:

```
Naqi --input <video> --output <file.mp4> [--music vocals|vocals+other]
     [--faces everyone|women|men] [--style blur[:0-100]|fill:<gray|black|white|navy|green>|gray|whole[,…]]
     [--nsfw 0-100]
```

Progress goes to stdout as `<STAGE> <percent>` lines, then `DONE <path>`. The exit status is 0 when
done, 1 when the job failed and 2 for bad arguments. Ctrl+C cancels and cleans up. From a source
checkout: `./gradlew :app:run --args="--input in.mp4 --output out.mp4 --faces women"`. The installed
`Naqi.exe` is a GUI program, so on Windows its output only shows when redirected
(`Naqi.exe … > log.txt`).

## Installing the beta

The MSI installs per user, with no administrator prompt. The builds are **not signed**, so Windows
SmartScreen warns that the app is from an unknown publisher. Choose **More info**, then **Run anyway**.
Only do that for a file you downloaded from this repository's own GitHub Actions or releases. The
portable zip needs no installer: unzip it and run `Naqi.exe`.

## Performance

Measured on an Apple M3 MacBook Air through the CLI, with a 30-second 720p clip. Each run includes
starting the JVM.

| Job | Time |
| --- | --- |
| Remove music (`vocals`) | 13 s |
| Remove music (`vocals + other`) | 22 s |
| Censor faces (blur, solid fill or grayscale) | 10–16 s |
| Whole-frame mode | 20 s |
| Music and faces together | 42 s |

Two known costs: the macOS arm64 x264 build runs without SIMD, so re-encoding on a Mac is slower than
it will be on Windows x64, and decoding, inference and encoding run one after another, not overlapped
as on Android. Windows timings have not been measured yet.

## Known gaps

- Not yet run on a real Windows machine; CI builds and tests on `windows-latest`.
- The YuNet thresholds (score 0.6, overlap 0.3, 500 ms gap) were picked, not tuned against Android's
  ML Kit results.
- The NSFW gate classifies the 640 px analysis frame, while Android classifies the full source frame.
  Its recall needs checking against Android on real clips.
- Odd frame sizes fail to encode, and 10-bit HDR sources are written as 8-bit without tone mapping.
- The output has no `faststart`, so the MP4 index is at the end of the file.
- The Thmanyah Sans font files are the ones the Android app ships. Their licence is not recorded in
  `NOTICE` yet.

## Licences

The app is GPL-3.0-or-later, like the rest of the repository outside `apps/apple`. It bundles:

| Component | Licence |
| --- | --- |
| YuNet face detector (`face_detection_yunet_2023mar.onnx`, opencv_zoo) | MIT |
| htdemucs (Demucs, re-exported to ONNX) | MIT |
| YAMNet | Apache-2.0 |
| InsightFace `genderage` | code MIT; non-commercial weights, restriction waived by the owner (see root [`NOTICE`](../../NOTICE)) |
| NSFW model (GantMan `nsfw_model`, INT8) | **NOASSERTION upstream, unresolved**: read root [`NOTICE`](../../NOTICE) before redistributing |
| FFmpeg with libx264 (JavaCPP GPL build) | GPL-2.0-or-later |
| JavaCPP and its FFmpeg presets | Apache-2.0 |
| ONNX Runtime | MIT |
| Compose Multiplatform, Kotlin, kotlinx.coroutines | Apache-2.0 |

The About screen lists the same notices.
