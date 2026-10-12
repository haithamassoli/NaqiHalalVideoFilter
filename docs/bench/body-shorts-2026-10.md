# Optional body censoring — Shorts, 2026-10-02

Implemented **Face / Body / Frame** coverage in Android. Face remains the default.
Body covers person rectangles, including faces, and shows the Arabic/English warning that processing
may take longer. The warning also remains visible in the share sheet when advanced options are closed.
Numeric ETAs are withheld for body mode until physical-phone timings exist.

## Requested clips and playback

| Source | Video duration | Original / face / body comparison |
|---|---:|---|
| [How I fake having a cameraman](https://youtube.com/shorts/rX6wXhLqOIQ) | 53.267 s | [comparison](../../qa-assets/bench-out/body-shorts/rX6wXhLqOIQ/comparison.mp4) |
| [Mystery jewelry bag](https://youtube.com/shorts/-dQJ3djthDc) | 54.200 s | [comparison](../../qa-assets/bench-out/body-shorts/-dQJ3djthDc/comparison.mp4) |

Comparison columns run **left to right: original, face, body**. Full-size body outputs:
[first](../../qa-assets/bench-out/body-shorts/rX6wXhLqOIQ/body.mp4),
[second](../../qa-assets/bench-out/body-shorts/-dQJ3djthDc/body.mp4).
UI: [options](../../qa-assets/bench-out/body-shorts/options-ar.png),
[collapsed share-sheet warning](../../qa-assets/bench-out/body-shorts/share-body-warning-ar.png).

The YouTube sources are 720×1280 AV1 + Opus. Functional Android tests use H.264 + AAC copies made
with FFmpeg. Both face/body runs select **Women**, blur 60, and **NSFW off** to isolate region coverage.
The exported videos come from the actual Android app on the local Pixel_9a ARM64 emulator,
installed as the isolated `com.haithamassoli.naqi.bodyqa` package. The pre-existing app was retained.
Final body exports use the non-debuggable benchmark build. An intermediate debug run exceeded the
10-minute harness timeout and was stopped; its partial output is not a final result. Emulator job
times are diagnostic records, not phone-performance evidence.

## Mac CPU cost

Apple M3, macOS 27.0.1, ONNX Runtime 1.27.0 CPU EP, four intra-op threads, spinning off,
one warmup, one full pass per clip, serial runs with no Android processing job running.

| Clip | Frames | Decode + letterbox + person inference | Inference alone | p50 / p90 per inference |
|---|---:|---:|---:|---:|
| rX6wXhLqOIQ | 1,598 | 56.30 s | 51.99 s | 27.74 / 42.18 ms |
| -dQJ3djthDc | 1,626 | 55.96 s | 51.42 s | 29.12 / 39.77 ms |

These measurements exclude face detection, gender classification, person tracking, rendering and
session creation. They establish substantial extra computation when analyzing every frame; they
do not establish an Android end-to-end multiplier. Mac preprocessing uses OpenCV RGB rather than
the Android NV21 walk. The first clip includes camera/product/B-roll shots; frames with no detected
person are not automatically missed people.

Raw cost and per-frame detections live in each clip directory as `mac-cost.json` and
`mac-detections.json`. `body-edl.json`, `face-edl.json`, `*-emulator.jsonl` and `*-logcat.txt`
record actual Android analysis and exports. [Validation](../../qa-assets/bench-out/body-shorts/validation.json).

## Model and implementation

- [YOLO26n](https://docs.ultralytics.com/models/yolo26), COCO person class 0, FP32, opset 17,
  fixed RGB `/255` letterboxed `[1,3,640,640]`; end-to-end output `[1,300,6]`, confidence floor 0.25.
- Checkpoint SHA-256: `9b09cc8bf347f0fc8a5f7657480587f25db09b34bf33b0652110fb03a8ad4fef`.
- ONNX SHA-256: `35519e399739f1d02821f6479f5a804c5da64b4f1dc20fa0db6f9822c4417265`.
  `scripts/export_person.py` pins the checkpoint, checks the graph and artifact hash, and removes
  the export timestamp for reproducibility. Two exports reproduced the same hash.
- Person detections on the two sample images matched PyTorch: 2/2 and 1/1 boxes; maximum coordinate/
  confidence difference `6.1035e-5`. This is export parity, not a detection-accuracy evaluation.
- Android reuses ONNX Runtime, ML Kit and the existing rectangle renderer. No Android dependency added.
- Person tracking uses reciprocal IoU matching; ambiguous continuations restart classification
  evidence. Coarse appearance changes reset tracks. Brief missing detections retain the last box
  for up to 300 ms. This is a basic tracker without re-identification.
- Classification uses the existing InsightFace voter with at most five face observations per track
  (minimum 80 px on the longest side), separated by at least 100 ms. A selective mode spares a person only after at least two
  votes for the other class and a strict majority. Unknown people remain covered.
- Rectangles use the existing 25% padding helper. Missing face-to-body association and more than
  eight active regions trigger whole-frame coverage. This can cover substantial background.
- Body analysis processes every decoded frame. Face-only remains at 10 fps; the NSFW gate retains
  approximately 5 fps when enabled. Body checkpoints use a separate `body-rect-v3` job generation.

## Findings corrected during this trial

1. Ambiguous detections retained old candidate boxes, manufacturing additional ambiguities and
   overflowing the renderer. Overlapping replacement boxes now close abandoned tracks immediately.
2. One incorrect face vote spared a person in the first clip at 12–13 s. Multiple observations and
   the two-vote minimum corrected the sampled 12.0, 12.5 and 13.0 s intervals.
   [Before](../../qa-assets/bench-out/body-shorts/rX6wXhLqOIQ/regression-before.jpg) /
   [after](../../qa-assets/bench-out/body-shorts/rX6wXhLqOIQ/regression-after.jpg).
3. The 5% body margin left little buffer for outstretched hands in the jewelry clip. Reusing the
   existing 25% margin increases coverage at the cost of more background blur.
   [Before](../../qa-assets/bench-out/body-shorts/-dQJ3djthDc/hands-before.jpg) /
   [after](../../qa-assets/bench-out/body-shorts/-dQJ3djthDc/hands-after.jpg).
4. Long Arabic segment labels clipped. Short labels and a full-width row fit correctly.

Coverage still needs a larger annotated set, especially crossings, fast movement, crowds and
gradual transitions. Rectangles are the initial implementation; silhouette masks and policy-independent
analysis are still open. This two-clip trial does not establish complete body-pixel coverage.

Validation passed: **160 JVM tests**, debug/benchmark builds and the benchmark's required lint check.
All six input/face/body videos decoded fully. Frame counts remain 1,598 and 1,626; AAC payloads
are byte-identical within each trio. Export duration differs from the input by 19 ms and 16.3 ms.

## Music clip

The requested [EnBXjdgQ9X0](https://youtu.be/EnBXjdgQ9X0) is the same source already compared on
this Mac today. Its full-length original / Demucs / MDX files and two recorded runs per model are
reused; no identical benchmark is presented as a new measurement.
[Audio report](sep-EnBXjdgQ9X0.md): Demucs median **36.92 s**, MDX **37.45 s** for 191.460 s of audio;
no resolved Mac speed advantage, about 18% lower MDX peak process memory. Singing remains wanted
content, and no reference stems are available for this song.

## Reproduce

```sh
.venv-bench/bin/python scripts/export_person.py
./scripts/fetch-models.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleBenchmark
.venv-bench/bin/python scripts/bench/score_body.py qa-assets/bench-out/body-shorts/rX6wXhLqOIQ/input.mp4 --out qa-assets/bench-out/body-shorts/rX6wXhLqOIQ
```

The Android debug hook accepts `--ez body_blur true --es censor_who women --ez censor_nsfw false`
alongside `--es autorun_path <device-file>`. `files/bench/last-edl.json` records the latest analysis
in debug/benchmark builds, mirrored to `getExternalFilesDir("bench")` for non-debuggable runs.
Models and media remain gitignored; no release or PR was published.
