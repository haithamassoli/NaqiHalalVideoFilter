<p align="center">
  <img src="../../branding/naqi-icon-1024.png" width="128" alt="Naqi app icon: a jade play triangle over the bowl of an Arabic ن">
</p>

<h1 align="center">Naqi Web</h1>

<p align="center">
  A web page that filters a video file <b>in your browser</b>: it removes music from the audio and
  censors faces, bodies or scenes in the picture. Nothing is uploaded, and the original file is never modified.
</p>

<p align="center">
  <a href="../../LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-blue" alt="Licence: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/status-in%20development-orange" alt="Status: in development">
  <img src="https://img.shields.io/badge/browser-desktop%20Chrome%20%2F%20Edge-4285f4" alt="Desktop Chrome or Edge">
</p>

<p align="center">
  <b>English</b> · <a href="README.ar.md">العربية</a>
</p>

This is the web app in the Naqi monorepo. What Naqi does, the privacy statement and the other
platforms are in the [main README](../../README.md). It follows the Android app
([`../android`](../android)), which stays the reference: the decision logic of [`core/`](../../core)
(EDL, NSFW gate, face track rules, body tracker) is ported to TypeScript with its tests. Music removal
is ported from [Musicless](https://github.com/ieasybooks/musicless) (MIT), whose notice is kept in the
ported files.

**Status: in development.** It is not deployed yet. Run it from source.

## No backend, nothing uploaded

The app is static files: an HTML page, its scripts, a WebAssembly runtime and the ONNX models. There is
no server code. The video is read from your disk, decoded, analysed and re-encoded inside the browser
tab, and the result is written back to your disk. No video, frame, sound or result leaves the computer.

## What works

- **Input:** a local video file the browser can decode with WebCodecs: MP4, MOV, WebM or MKV, with
  H.264, VP9 or AV1 video (HEVC where the system has a decoder) and AAC, Opus or MP3 audio.
- **Output:** an MP4 with H.264 video and AAC audio (`<name>-naqi.mp4`). Where the browser lacks an
  encoder, it falls back to VP9 or AV1 and Opus, and says so. In Chrome and Edge the output streams
  straight to the file you choose in the save dialog; elsewhere it is built in memory (up to 2 GiB of
  input) and offered as a download.
- **Remove music:** Spleeter 2-stem (Deezer's weights, through Musicless) keeps the vocals. The
  separation runs on the whole file, about 12 s at a time with a crossfade between windows.
- **Censor faces:** who gets covered is everyone, women or men. Faces are found with YuNet and tracked
  across frames, and each track gets up to 5 InsightFace `genderage` votes. A tie, an abstention or a
  face too small to classify (under 80 px) means the face is censored.
- **Bodies (optional):** a box or an outline around each person whose face is censored, from
  YOLO11n-seg. The model is downloaded only when this is on. Unknown people are covered, and the whole
  picture is covered while a censored face has no body around it.
- **NSFW scene gate:** covers the whole frame while it fires, with strictness 0–100.
- **Style:** blur, grayscale blur or a solid fill, with an amount.
- **Timing:** when the picture does not change, the video packets are copied and only the audio is
  replaced. Otherwise the whole video is re-encoded.
- **Fail-safe:** a model that does not load, a frame that cannot be decoded or a codec the browser
  lacks stops the job with a message. It never writes a video that was meant to be censored and was not.
- Progress, an estimate and cancel.

## Browser support

Desktop **Chrome or Edge**, current stable. The app needs WebCodecs, module workers and WebAssembly,
and saves straight to disk with `showSaveFilePicker`. Safari, Firefox and phones may work for small
files, but are not supported: codec support differs and the output must fit in memory.

## Build and run

You need Node.js and pnpm, and the models. Everything below runs from `apps/web`.

```sh
pnpm install
./scripts/fetch-models.sh     # the vision models into public/models (gitignored), sha256-checked
./scripts/build-spleeter.sh   # the music model into public/models, sha256-checked
pnpm dev                      # dev server at http://localhost:5173
pnpm test                     # the tests (Vitest)
pnpm build                    # type check and the static build in dist/
pnpm preview                  # serve dist/
```

**Models.** `fetch-models.sh` copies YuNet, `genderage`, the NSFW model and YOLO11n-seg from the other
apps' staged folders: `../windows/models` (run `./gradlew :app:stageModels` there, see
[`apps/windows/README.md`](../windows/README.md)) or `../android/app/src/main/assets/models` (run
`./scripts/fetch-models.sh` there, see [`apps/android/README.md`](../android/README.md);
`yolo11n-seg.onnx` comes from there). `build-spleeter.sh` needs `curl`, `tar` and
[uv](https://docs.astral.sh/uv/). It downloads sherpa-onnx's Spleeter 2-stem export, merges its two
networks into one mask model with `scripts/build_model.py` (from Musicless, pinned `onnx` and `numpy`)
and checks the result against Musicless' tested hash. Never commit the models.

A missing model stops the job that needs it with a message that names it.

**End-to-end test clip.** `./scripts/make-fixture.sh` (needs `ffmpeg`; uses macOS `say` for the voice when
it is there) writes `fixtures/fixture.mp4` (gitignored): 15 s, 1280x720, H.264 + AAC, music throughout,
speech from 5 s, and a public-domain NASA portrait from 3 s to 10 s. Open the app with `?e2e=1` to get a
download link instead of the save dialog, which browser automation cannot answer.

## Models and licences

The app is GPL-3.0-or-later, like the rest of the repository outside `apps/apple`. It uses:

| Component | Used for | Size | Licence |
| --- | --- | --- | --- |
| Spleeter 2-stem (Deezer weights, sherpa-onnx export, merged by Musicless) | music removal | 37.5 MiB | MIT (weights and Musicless code) |
| YuNet (`face_detection_yunet_2023mar.onnx`, opencv_zoo) | face detection | 233 kB | MIT |
| InsightFace `genderage` | women / men votes | 1.3 MB | code MIT; research-use weights, restriction waived by the owner (see root [`NOTICE`](../../NOTICE)) |
| NSFW model (GantMan `nsfw_model`, MobileNetV2 INT8) | NSFW scene gate | 4.9 MB | **NOASSERTION upstream, unresolved**: read root [`NOTICE`](../../NOTICE) before redistributing |
| YOLO11n-seg (Ultralytics) | body mode | 12 MB | AGPL-3.0 |
| ONNX Runtime Web | inference (WebAssembly) | | MIT |
| Mediabunny | demux, decode, encode, mux | | MPL-2.0 (unmodified) |

The `genderage` and NSFW terms are why the app is for personal use for now: check them again before
any public deployment.

## Limitations

- **Spleeter keeps singing.** It separates vocals from accompaniment, so singing stays in the output
  with the speech. It is also weaker than the htdemucs model the Android and Windows apps use: under
  dense music some of it leaks through.
- **10 fps sampling.** Faces are looked for 10 times a second and the NSFW gate 5 times a second, as on
  Android. A face that appears only between two samples, or right at a cut, can show for a moment.
- **Desktop Chrome and Edge only**, as above.
- Video up to 4K; above 1080p censoring is slow and needs much memory.
- Inference is single-threaded WebAssembly (no WebGPU, no cross-origin isolation headers), so long
  videos take a while.
- When anything in the picture is censored, the whole video is re-encoded, not only the censored parts.
