# Perf research pass — 2026-08-05

Deep-research run against the question *"how can Naqi significantly improve
performance, even if it means changing everything?"*, scoped to **whole
architecture** and **on-device only** (no cloud/hybrid offload).

- 105 agents, ~4.0M tokens, 56 min wall.
- 5 search angles → 23 sources fetched → 115 claims extracted → 25 adversarially
  verified (3 votes each, 2/3 refutes to kill).
- **Result: 8 confirmed, 17 refuted, 3 of 5 sub-questions returned nothing.**

## Verdict

The web could not answer this question. Naqi's bottleneck is a local, measured,
codebase-specific CPU loop; public sources have nothing to say about it. The
report's own words: *"no plan should assume a number for deleting Naqi's NV21
pack; the spike has to produce it."*

The single actionable recommendation the research produced — route audio-only
jobs to a video stream copy — **is already shipped**, and our code comment
documents the ceiling more precisely than the research did. See
[Already shipped](#already-shipped).

Six of the eight surviving claims trace to Google/Media3 material, and five of
those describe **one feature** (trim optimization) from different angles. They
corroborate each other's mechanism but are not independent evidence. There is no
third-party benchmark of Android re-encode avoidance in the set.

---

## Confirmed

### 1. Re-encode avoidance is the biggest documented lever in production Android

Measured 2.8×–4.8× in Google Photos' fleet telemetry, 6.5× in Media3's published
benchmark.

> "By adopting Transformer APIs for rotating videos, median save latency was
> reduced by 79% for applicable videos... observed video save latency decrease by
> 64%"

Media3's table, Pixel 9 Pro XL, same 10s 720p H264+AAC input: transcode to
H265+AAC ~1300ms vs muting audio ~200ms. 200ms for 10s of 720p is ~50× realtime —
arithmetically impossible for decode+encode, which corroborates transmux.

Mechanism, from Google's engineering post:

> "A trim is usually performed by re-encoding all the samples in the file... we
> can improve efficiency by only re-encoding the group of pictures (GOP) between
> the start point of the trim and the first keyframes at/after the start point,
> then stream-copying the rest."

Confirmed in shipping code: `ExportResult.getConversionProcess()` returns a
distinct `CONVERSION_PROCESS_TRANSMUXED_AND_TRANSCODED`.

**Rigor caveat.** The 6.5× row is self-admittedly a "rough estimate" — one
device, one run implied, no variance, measured with "the Stopwatch API" — and the
same table shows *trimming* that file at ~2300ms, **slower than fully transcoding
it**. The Photos figures are DevRel adoption advocacy: no methodology, no n, no
device mix, no definition of "applicable videos". Only the 79% is labelled a
median. Treat as order-of-magnitude signals.

Sources:
[Photos/BandLab adoption post](https://android-developers.googleblog.com/2025/01/apps-adopt-transformer-to-support-more-reliable-media-editing-use-cases.html) ·
[Media3 perf benchmarks](https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html) ·
[Transformations doc](https://developer.android.com/media/media3/transformer/transformations)

### 2. That lever is structurally unavailable to an effect-bearing export

Any transformation requiring transcoding — a blur shader is exactly that —
abandons the optimization and falls back to a full re-encode. There is a named
enum constant for precisely this:

```
ExportResult.OPTIMIZATION_ABANDONED_TRIM_AND_TRANSCODING_TRANSFORMATION_REQUESTED = 3
  "Trim optimization was requested, but it would not improve performance because
   another transformation that requires transcoding was also requested. The
   optimization was abandoned and normal export proceeded."
```

`Transformer.java` javadoc adds *"Not guaranteed to work with any effects."*

The obvious workaround is closed too. `Composition.java`: with multiple media
items *"They are all transmuxed if transmuxVideo is true... Any transcoding
effects requested will be ignored"*, and `SequenceAssetLoader` enforces one output
type (DECODED vs ENCODED) across the whole sequence — **one blurred item forces
the entire sequence to decode.** Mixed transmux/transcode within a sequence is
not supported.

Scope limit: even in its supported case the optimization only handles trims from
the **start** of the item (one leading partial GOP), never arbitrary interior
spans — precisely the shape a per-segment blur needs.

"Falls back silently" means no exception and no listener callback;
`ExportResult.optimizationResult` is populated only at completion.

### 3. A hand-built GOP splicer has a byte-level bitstream ceiling

The doc's wording is soft ("compatible level and profile"). The shipping code is
harder. In `MUXER_MODE_APPEND`, `MuxerWrapper.addTrackFormat` throws
`AppendTrackFormatException` on mismatched `sampleMimeType`, `width`, `height`,
`rotationDegrees`, or initialization data. `getMostCompatibleInitializationData()`
is the only leniency and requires:

- both streams H.264 (any other MIME returns `null` immediately — HEVC needs
  byte-identical csd),
- exactly 2 csd entries each,
- **PPS byte-identical** via `Arrays.equals`,
- SPS identical length,
- SPS byte-for-byte equal at every index **except** `spsLevelIndex =
  NAL_START_CODE.length + 3` (`level_idc`).

`profile_idc` sits at `NAL_START_CODE+1` and is **not** exempted. Byte-identical
SPS/PPS across encoder vendors and versions (VUI, `num_ref_frames`,
`pic_order_cnt_type`, cropping, CABAC/CAVLC) is effectively unattainable for
arbitrary user files. Only one relaxation has ever shipped (1.4, "Relax trim
optimization H.264 level checks" — that single `level_idc` exemption); nothing
through 1.10.1 loosens it.

Failure is graceful — `OPTIMIZATION_FAILED_FORMAT_MISMATCH`,
`OPTIMIZATION_FAILED_EXTRACTION_FAILED`, all documented "Normal export
proceeded." So the risk is **"no speedup on most real files"**, not corruption.

Honest qualification: this ceiling is a property of Android muxing, not of H.264.
MP4 permits multiple `stsd` entries; `MediaMuxer` fixes a track's format at
`addTrack()`.

### 4. Both re-encode-avoidance flags are on our classpath today, and both are unstable

`gradle/libs.versions.toml:10` pins `media3 = "1.10.1"`;
`app/build.gradle.kts:169-171` already pulls transformer/effect/common. A
verifier ran `javap` against the artifact in the Gradle cache and confirmed
`experimentalSetTrimOptimizationEnabled(boolean)`,
`experimentalSetMp4EditListTrimEnabled(boolean)`, and an undocumented
`experimentalSetMaxFramesInEncoder(int)` are present in the shipped binary.

Both are `@ExperimentalApi` inside an `@UnstableApi` class — non-stable at two
tiers, roughly two years after introduction, with no promotion, rename, or
deprecation through `1.11.0-rc01` (2026-07-22). A spike costs no dependency work;
a dependency on the signatures is not safe.

The edit-list path also carries an official privacy warning: "deleted" frames
remain in the file and will play on players that ignore the pre-roll.

### 5. The zero-CPU-pixel loop is validated, but only for the render shape we already have

LiTr (LinkedIn-authored, actively maintained, v1.5.7 added Android 15 support)
runs both decoder and encoder in Surface mode with OpenGL between them, ~40 GPU
filters applied inside `drawFrame`, no `glReadPixels` anywhere in
`GlVideoRenderer`. Per-frame effects and a CPU-pixel-free loop are not in tension.

Three corrections carried by the verifier:

- **"Shipped in the LinkedIn app" appears nowhere** in the post or README.
  Downgrade to "LinkedIn-authored, maintained open source."
- **"Zero-copy" is loose.** gralloc buffer-to-buffer traffic remains; compressed
  samples still cross a CPU `ByteBuffer` via `readSampleData`. What is eliminated
  is CPU *readback*.
- Surface-mode GL transcode has documented colour-space/HDR hazards (SDR washout
  reports, driver-dependent RGB↔YUV conversion points, HDR metadata must be
  supplied up front).

This validates the shape of our pass 2, which is already encoder-paced. Its
transferable value is as an on-ramp for **pass 1**, not a pass-2 speedup.

### 6. Nobody has published a number for zero-copy GPU inference input

Google's own LiteRT page, fetched live 2026-08-05:

> "Using zero-copy enables a GPU to access data directly in its own memory
> without the need for the CPU to explicitly copy that data. By not copying data
> to and from CPU memory, zero-copy **can significantly reduce** end-to-end
> latency."

Hedged modal, unquantified intensifier, no percentage, no delta, no with/without
pair. The page's only quantified content is a table of absolute LiteRT GPU
execution times on a Galaxy S24 (2.3 / 6.9 / 98.3 ms) with **no paired baseline**,
so the cost of the eliminated copy cannot be backed out of it.

### 7. The two-pass vs fused question is not settled by this research

An absence-of-evidence finding, reported as such. Both claims that would have
answered it fell: that Media3 models "analyze" as the identical decode+effects
graph minus the encoder (1-2), and that Media3's effects chain accepts a MediaPipe
graph as a `GlEffect` so ML runs on the decoded GPU texture (0-3). No production
write-up surfaced that fuses analysis and rendering in one decode.

What the surviving evidence establishes *indirectly* is that Google's own library
treats per-segment mixed transmux/transcode as unsupported — the incremental-render
direction has no first-party support to lean on.

---

## Already shipped

The research's one "do this now" item — route audio-only jobs to a video stream
copy and skip the render pass — is `render/RenderPipeline.kt:112`:

```kotlin
val passthrough = segment == null && edl.censorIntervalsMs.isEmpty() && edl.faceTracks.isEmpty()
```

The comment above it is **more precise than the research output**. It names
`TransformerUtil.shouldTranscodeVideo()` as the actual mechanism, explains why an
idle `CensorGlEffect` still costs a full decode→GL→encode (the effect must be
*absent*, not idle), notes that the H.264 mime type, HDR tone-map mode, and
encoder factory each force a transcode independently, and independently derives
the per-segment ceiling:

> a transmuxed segment carries the SOURCE codec configuration and a re-encoded one
> carries the encoder's, and `Remux.concat` can only write one track format —
> `MediaMuxer` exposes no way to put a second `stsd` entry in a single track.

That is the same wall as finding 3, reached from our own side, and stated in terms
of the concrete failure (a file whose second sample entry is silently wrong)
rather than as an abstract compatibility caveat.

**Do not extend the passthrough per segment.** Both the code comment and the
research agree, for the same reason.

---

## The three empty holes — where the leverage actually is

| Sub-question | Result | Why it matters |
|---|---|---|
| Audio separation alternatives (BS-RoFormer, SCNet, MDX-Net, band-split/distilled Demucs, music-presence gating) | **zero surviving claims** | ~65% of wall on music jobs. Largest unexamined lever in the report, by a wide margin. |
| Thermal throttling / DVFS / core affinity on 8 Gen 2 | **zero surviving claims** | The measured ~10% producer-contention regression is unexplained without it. Our mental model of the phone over a multi-minute run is unvalidated. |
| Zero-copy GPU→inference input path | only qualitative (finding 6) | The only route to deleting pass-1's NV21 pack. No API-level route survived verification. |
| Decode parallelism (multi-instance MediaCodec) | both CDD concurrency claims killed 0-3 | No verified floor exists for decode fan-out. |

---

## Refuted — read with suspicion

17 of 25 claims were killed. **Refuted here means "this pass did not establish
it", not "it is false."** Several read as sourcing overreach rather than factual
error, and are plausible on their face. Do not conclude these techniques don't
exist:

- LiteRT Next `TensorBuffer::CreateFromGlBuffer` / `CreateFromAhwb` wrapping an
  existing GPU buffer as model input (1-2 and 0-3)
- `AHardwareBuffer` as the GL/CL interop hub (0-3)
- `SurfaceTexture.updateTexImage()` as the documented zero-CPU-copy on-ramp (0-3)
- External OES textures needing an FBO round-trip for full `GL_TEXTURE_2D` use (1-2)
- ONNX Runtime QNN EP requiring integer quantization / rejecting dynamic shapes
  (0-3 and 1-2) — directly relevant to any NPU attempt on INT8 htdemucs
- MediaCodec's ByteBuffer-vs-Surface access/speed tradeoff (0-3)
- Android 16 CDD floors of 6 concurrent decoder sessions and ≤2 encoders (0-3 both)
- BandLab's 12-day migration off hand-rolled MediaCodec (0-3)

These need a targeted re-run against primary API docs, or a local spike.

---

## Open questions

1. **What is pass-1's producer actually spending 94.3s on**, once the `nv21=`
   timer is split from `gateFill`? We already know `nv21=` is not `packNv21`'s
   cost. Nothing can be targeted until it is attributed.
2. **Can a decoder-Surface → external-OES-texture → GPU-resident tensor path
   reach ML Kit and the ONNX classifier on an S23 at all?** No verified API-level
   route survived. Needs a throwaway spike that produces a number.
3. **What is the quality-per-millisecond frontier for on-device music separation
   beyond INT8 htdemucs**, and how cheap can a music-presence gate get before
   separation is attempted at all? 65% of wall, zero coverage.
4. **How much of a multi-minute job is lost to thermal throttling on an 8 Gen 2**,
   and does the ~10% producer/consumer contention regression change under explicit
   big-core pinning, sustained-performance mode, or deliberate pacing?
5. **Is our measured 1.9% smart-rendering yield a property of the content or of
   the stitching constraint?** Opposite implications: the first kills the idea,
   the second says a looser muxer might rescue it.

---

## Recommendation

Stop researching the video side. Two moves, in order:

1. **Split the `nv21=` timer from `gateFill`.** 94.3s of 114.6s is unattributed;
   everything downstream is guesswork until it isn't.
2. **Re-run research narrowly on audio only.** It is 65% of wall on music jobs,
   it came back completely empty, and it is the one area where a genuinely
   different model — not a faster runtime — could halve the job.

## Prior measurements used as bounds (not verified by this research)

The 94.3s/114.6s producer split, 1.59ms/frame detection, 0.20% render-pass
movement, 1.9% smart-rendering yield, 90.8% whole-frame-blur coverage, the ~10%
producer-contention regression, and INT8 htdemucs at 2.30×/99.20% all come from
prior project measurement carried into the question. Nothing here verified or
challenged them; they bounded applicability only.

## Sources

Primary, in rough order of usefulness:

- <https://developer.android.com/media/media3/transformer/transformations>
- <https://github.com/androidx/media/blob/release/libraries/transformer/src/main/java/androidx/media3/transformer/MuxerWrapper.java>
- <https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html>
- <https://android-developers.googleblog.com/2025/01/apps-adopt-transformer-to-support-more-reliable-media-editing-use-cases.html>
- <https://github.com/androidx/media/blob/release/demos/transformer/src/main/java/androidx/media3/demo/transformer/TransformerActivity.java>
- <https://engineering.linkedin.com/blog/2019/litr-a-lightweight-video-audio-transcoder-for-android> · <https://github.com/linkedin/LiTr>
- <https://developers.google.com/edge/litert/next/gpu> · <https://developers.google.com/edge/litert/next/cpp>
- <https://source.android.com/docs/core/graphics/arch-st>
- <https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html>
- <https://source.android.com/docs/compatibility/16/android-16-cdd>
- <https://developer.android.com/games/optimize/adpf/thermal> · <https://source.android.com/docs/core/power/performance>
- <https://bigflake.com/mediacodec/>
