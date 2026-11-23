# Windows app seams

The contract between the packages under `app/src/main/kotlin/com/haithamassoli/naqi/desktop/`, so they can be written in parallel. The stub files hold the same signatures with `TODO()` bodies. Change a signature only together with this file and every caller.

Port from `apps/android` (GPL-3.0, the reference). Never copy or port from `apps/apple`.

## Build

```sh
cd apps/windows
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # any JDK 21
./gradlew :app:compileKotlin :app:test :core:test   # the gate
./gradlew :app:stageModels                          # models into apps/windows/models (gitignored)
./gradlew :app:run                                  # runs on macOS too
./gradlew :app:packageMsi                           # Windows only
./gradlew :app:packagePortableZip                   # zipped app image, any OS
```

`./gradlew :app:test` passes `-Dnaqi.models.dir=apps/windows/models`. A test that needs a model gets it with `Models.fileOrNull(...)` and skips through `Assume` when it is null. A test that needs a video makes one in a temp dir with the bundled `ffmpeg` program: `Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)` returns its path.

## Ownership

| Package | Owner | File today |
| --- | --- | --- |
| `desktop.media` | media agent | `media/Media.kt` |
| `desktop.audio` | audio agent | `audio/MusicRemoval.kt` |
| `desktop.vision` | vision agent | `vision/Vision.kt` |
| `desktop.ui`, `desktop/Main.kt` | UI agent | `ui/App.kt`, `Main.kt` |
| `desktop.pipeline` | integration, later | `pipeline/Pipeline.kt` |
| `desktop/Models.kt`, Gradle files, `scripts/`, this file | scaffold / integration | |

Stay inside your package. Add files there as you like. If you need a new dependency or a change to a shared file, say so in your report instead of editing it.

## Shared rules

- **Time** is milliseconds (`Long`) everywhere above `media`. A frame's `ptsMs` is its own decoded timestamp (`best_effort_timestamp`, rescaled from the stream time base, floored, relative to the video stream's start time). Never derive it from a frame counter. Analysis and rendering use the same clock, so an EDL built from one lines up with the other.
- **Geometry** is core's `NRect`, normalized to [0, 1]. Analysis is in UPRIGHT space (rotation applied). Rendering is in STORED space, so the pipeline maps EDL rects with `NRect.toStoredSpace(meta.rotationDegrees)`, as Android does.
- **Frames** are `media.RgbFrame`: packed RGB24, stride `width * 3`, valid only inside the callback (the decoder may reuse the array).
- **Audio PCM** is 44 100 Hz, stereo, `Float`, interleaved L R L R, nominally in [-1, 1]. Batches are `(interleaved: FloatArray, frames: Int)`, where `frames` counts sample frames (stereo pairs).
- **Options** are core's `FilterOps`. Body modes are deferred and ignored.
- **Cancellation**: long work is `suspend` and checks for cancellation. Cancelling the job's coroutine cancels the job. No separate `isCancelled` callbacks.
- **Progress** is `(Int) -> Unit` with values 0..100 for one stage.
- **Models**: `Models.dir()` resolves `-Dnaqi.models.dir`, then `NAQI_MODELS_DIR`, then `<compose.application.resources.dir>/models` (packaged app and `:app:run`). File names are the `Models.*` constants. ONNX Runtime runs on CPU, one session per model, created by whoever owns that model.
- **Core types to use**: `Edl`, `FaceTrackEdl`, `NRect`, `VideoMeta`, `FaceTrack`, `edlFor`, `isStale`, `VOTE_CAP`, `MIN_FACE_PX`, `NsfwGate`, `NSFW_CLASSES`, `promoteFacesToFullFrame`, `mergeRanges`, `DemucsSeparator`, `MusicGate`, `Stft`.

## media (JavaCPP FFmpeg, GPL build)

```kotlin
class RgbFrame(val width: Int, val height: Int, val rgb: ByteArray, val ptsMs: Long)

object Probe {
    fun probe(input: File): VideoMeta   // core: stored width/height, rotationDegrees (clockwise), durationMs, fps (30 if absent)
}

object VideoDecoder {
    suspend fun sample(input: File, fps: Float = 10f, maxDim: Int = 640, onFrame: suspend (RgbFrame) -> Unit)
}

object AudioDecoder {
    suspend fun decode(input: File, onPcm: suspend (interleaved: FloatArray, frames: Int) -> Unit): Boolean   // false = no audio stream
}

class AacWriter(output: File) : AutoCloseable {   // AAC-LC 192 kbps, 44.1 kHz stereo, .m4a
    fun write(interleaved: FloatArray, frames: Int)
    override fun close()
}

sealed interface AudioOut { data object Keep; data class Replace(val m4a: File); data object Drop }

object Renderer {
    suspend fun render(input: File, output: File, audio: AudioOut, edit: ((RgbFrame) -> Unit)?, onProgress: (Int) -> Unit)
}
```

- `sample` follows Android's `FrameSampler` slot rule. Slots are `1000 / fps` ms apart, anchored to the first decoded frame. A frame is emitted when its pts is at or past the next slot. The slot then advances one interval, or to `pts + interval` if that would leave it at or behind the frame. Frames are UPRIGHT and scaled so the longer side is at most `maxDim`, never upscaled.
- `AudioDecoder.decode` resamples to the PCM format above and starts at the audio stream's first sample. `Renderer` places a `Replace` track at the source audio's start time, so A/V sync survives.
- `render` with `edit == null` stream-copies the video. Otherwise it decodes full-size STORED frames, calls `edit` to change each one in place, and encodes it with libx264 (yuv420p), keeping each frame's original pts. Rotation metadata is carried over either way. Output is MP4.
- Safety: `input` is opened read-only. If `output` has the same canonical path as `input`, it throws `IllegalArgumentException`. Output is written to a temp file in `output`'s directory and atomically renamed only on success. A failure or a cancel leaves no partial file.

## audio (music removal)

```kotlin
object MusicRemoval {
    suspend fun run(input: File, keepStems: String, outM4a: File, durationMs: Long,
                    modelsDir: File = Models.dir(), onProgress: (Int) -> Unit): Boolean   // false = no audio stream, nothing written
}
```

- Port Android's `AudioPipeline.removeMusic`, `HtdemucsSession` and `MusicGateSession` onto core's `DemucsSeparator` and `MusicGate`. That means the mean/std stats pass, the YAMNet gate (`Models.YAMNET`) and CPU htdemucs (`Models.HTDEMUCS`, with Android's session options). Keep `"vocals"` or `"vocals_other"`.
- Read PCM with `media.AudioDecoder`, which is decoded twice (stats pass, then separation). Write the result with `media.AacWriter`. The output has the same sample count as the decoded input.
- If YAMNet is missing, everything is separated. If htdemucs is missing, it throws. `durationMs` only feeds progress and `MusicGate.openIfLong`-style decisions.

## vision (faces, NSFW, compositing)

```kotlin
class FaceAnalyzer(who: String, modelsDir: File = Models.dir()) : AutoCloseable {
    fun onFrame(frame: RgbFrame)        // UPRIGHT analysis frames, pts order, do not retain
    fun finish(): List<FaceTrackEdl>    // censored tracks only, sorted by startMs
}

class NsfwClassifier(modelsDir: File = Models.dir()) : AutoCloseable {
    fun classify(frame: RgbFrame): FloatArray   // softmax in NSFW_CLASSES order; input [1,3,224,224]
}

object Censor {
    fun apply(frame: RgbFrame, regions: List<NRect>, fullFrame: Boolean,
              blurAmount: Int, grayscale: Boolean, solidColor: Int)   // in place
}
```

- Faces: YuNet (`Models.YUNET`, opencv_zoo 2023mar, MIT) replaces ML Kit. Tracks are associated by IoU, because YuNet has no tracking id. `BodyTracker.overlap(a, b)` in core is an IoU. The semantics come from Android's `FaceTracker` and core's `FaceTrackerLogic.kt`. Samples are the raw upright rects, and eviction uses `isStale`. Vote with genderage (`Models.GENDERAGE`, input [1,3,96,96], a 1.5x square crop) up to `VOTE_CAP` per track, and only on crops of at least `MIN_FACE_PX` upright px. Build each finished track's span with `edlFor(track, who)`, which pads the rects and applies the verdict, so a tie or an abstention censors. `who` is `FilterOps.censorWho`. Under `EVERYONE`, skip the genderage model entirely.
- NSFW: the pipeline calls `classify` on every second sampled frame (5 fps) and passes the result to `NsfwGate.fires(probs, ops.strictness)`.
- Censor: port Android's `CensorEffect` to CPU RGB. Rects are normalized to the frame as given, which is STORED space at render time. If `solidColor != FilterOps.BLUR`, fill with that opaque ARGB colour (`FilterOps.SOLID_COLORS`). Otherwise blur at `blurAmount` (0..100) and desaturate when `grayscale` is set. `fullFrame` covers everything. There is no region cap, so Android's overflow spans do not apply.

## pipeline (integration, later)

```kotlin
data class JobOptions(val input: File, val output: File, val ops: FilterOps)
enum class Stage { ANALYZE, MUSIC, RENDER }

object Pipeline {
    suspend fun analyze(input: File, meta: VideoMeta, ops: FilterOps, onProgress: (Int) -> Unit): Edl
    suspend fun render(job: JobOptions, meta: VideoMeta, edl: Edl, music: File?, onProgress: (Int) -> Unit)
    suspend fun run(job: JobOptions, onProgress: (Stage, Int) -> Unit)
}
```

- `analyze` runs `sample` at 10 fps. Every frame goes to `FaceAnalyzer` (when `censorFaces`), and every second frame goes to `NsfwClassifier` (when `censorNsfw`). Then `NsfwGate.intervals(firings, durationMs, or Long.MAX_VALUE when that is unknown)`, plus `promoteFacesToFullFrame` when `wholeFrameBlur`, giving `Edl(intervals, faceTracks)`.
- `render` uses `AudioOut.Replace(music)` when music was removed and `Keep` otherwise. Its edit calls `Censor.apply` with `edl.fullFrameAt(ptsMs)` and `edl.regionsAt(ptsMs).map { it.toStoredSpace(rotation) }`. The edit is null (stream copy) when the EDL is empty.
- `run` probes, analyzes (skipped when there is no face or NSFW work), removes music when `removeMusic` is set, then renders. It reports progress per `Stage`.

## ui (Compose Desktop)

- `Main.kt` opens the window and calls `ui.App()`.
- Flow: pick a local video, then options (`FilterOps`: music + keepStems, censorWho everyone/women/men, blur strength, solid fill with the 5 `SOLID_COLORS`, grayscale, whole-frame, NSFW on/off + strictness 0..100), then choose the output path (default next to the input, never the input itself), then run with per-`Stage` progress and cancel, then open the output.
- Call only `Pipeline.run(JobOptions, onProgress)` from a coroutine. Cancel by cancelling that coroutine.
- Strings come in English and Arabic, chosen by the system locale. Arabic is laid out RTL. Reuse the wording of `apps/android/app/src/main/res/values*/strings.xml`.
- Deferred, so do not build: body modes, link download, end card, publish/splitter, auto-update.
