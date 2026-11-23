# Person censoring: production-stage integration checks

The benchmark compiles the real `MediaSource`, `AnalyzePass`, `AudioPipeline`,
`RenderPass` and `Remux` sources into a native macOS bundle. It runs the same
parallel audio/analyze branches and rendering/muxing flow as an unsegmented
combined job. It does not replace DSP with a Python implementation. Checkpoint
UI, publishing and background-task management are outside the measured flow.

Measurements are on the M3 MacBook Air, 24 GB, macOS 27. No physical iPhone was
available. These are full-length native media-processing measurements, not
phone latency, energy, thermals or an iPhone memory allowance.

The prior implementation is the unchanged production source archived from
`e8af47140a48b6221806716bd5b955b0819d7e6a`. The new implementation adds person
analysis/tracking, keeps HTDemucs, and separates every audio window by default.
Each run is a fresh process. Core ML caches were retained. Exact production
source hashes and the compiled executable hash are recorded per result.

## Full exported jobs, cached model state

Default combined jobs select women, apply blur 60, preserve vocals only, keep
the default scene gate, and use the original output resolution. Person analysis
processes every decoded frame; the prior face analysis samples 10 fps.

| Full input | Job configuration | Total s | Analyze s | Audio s | Render/mux s | Kernel peak MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| `-dQJ3djthDc`, 1626 frames | Prior face + gated music | 7.209 | 3.735 | 4.109 | 3.084 | 2540.7 |
| `-dQJ3djthDc` | Final person + every-window music | 25.246 | 22.160 | 5.394 | 3.039 | 2467.4 |
| `rX6wXhLqOIQ`, 1598 frames | Prior face + gated music | 7.420 | 3.797 | 4.423 | 2.984 | 2627.7 |
| `rX6wXhLqOIQ` | Final person + every-window music | 24.300 | 21.213 | 4.874 | 3.046 | 2555.0 |
| `EnBXjdgQ9X0`, 4786 frames | Prior music-only, gate on | 13.559 | — | 13.413 | 0.131 | 3534.0 |
| `EnBXjdgQ9X0` | New music-only, gate off | 13.238 | — | 13.096 | 0.128 | 3054.9 |

Audio and analysis overlap, so their times must not be added. Total includes
probe, branch setup/loading, complete media processing and final file writing;
verification is performed afterward. The two Shorts were about 54.2 and 53.27 s
of video; the third song was processed in full, about 191.44 s video and 191.474 s
audio. No 120-second fixture limit or independent segment cut was used.

Person processing increased the complete job time by about 3.3–3.5 times compared
with the prior face-only task on these Shorts. This measures additional body
coverage work, not a speed improvement. The first song's old gate opened
YAMNet and skipped **0/83** chunks; disabling it removed the classifier cost
without changing which windows were processed on that input. Short-clip gate
skip counts varied with independent AAC/SRC decoding and are not annotated
instrument false-negative rates.

An additional new-code face/gate-on run took 7.796 s with the same 586 region
frames and 1040 whole-frame frames as the cached prior run on the first Short.
The first prior-code run took 33.777 s, including a 26.223 s first observed
Core ML compile; it is retained as a separate startup observation, not used as
the cached comparator.

## Memory correction during verification

The first full person run peaked at 4952.3 MiB. Synchronous Core ML/Core Image
work now runs within an explicit per-frame autorelease pool in `PersonDetector`.
The first repeat peaked at 2691.5 MiB and its entire saved EDL, including all 76 person
records, matched the pre-fix EDL exactly. The pre-fix row remains a diagnostic;
it is excluded from the final performance comparison. Neither figure predicts
an iPhone jetsam threshold. The song-only footprint also demonstrates that
longer audio needs physical-phone memory testing. Final runs after the
classification-rule correction peaked at 2467.4/2555.0 MiB; the earlier
2691.5 MiB run is the unchanged-EDL memory-fix diagnostic, not the final policy row.

## Export integrity and concrete audio limits

All accepted runs preserve the exact source video frame count, presentation
timestamps, resolution and rotation. Compressed media timestamps are mapped
through public `AVAssetTrack.segments.timeMapping` before comparison. The H.264
encoder's legal two-frame media offset is compensated by the movie edit list:
raw compressed timestamps differed by 66.667 ms, but mapped timestamps and an
independent full decoded-frame check on the smoke export matched exactly.
Music-only video elementary streams also hash identically to their sources.

The production Demucs driver asserts samples fed equal samples emitted and
reports zero non-finite model samples. Audio logical track start/end must remain
within the project's existing **50 ms** sync budget. Observed independent PCM
decode counts are recorded, with `audio_sample_counts_exact` false where needed;
these exports are **not** presented as sample-exact source-audio preservation.

Untimed diagnostics established an existing native AAC 48 kHz → 44.1 kHz SRC
count variation. The same first input produced 2391513/2391498/2391513 raw frames
across three reads; the logical audio track ended at 54.214 s while some decoded
buffers ended around 54.229 s. FFmpeg independently decoded 2390794 source
frames. The separated checkpoint had 2391513 frames, and its final mux had
2391470: the 43-frame difference is consistent with the existing 600-timescale
movie edit-list rounding. The observed first output audio end drift was
14.33 ms, the second 16 ms. No PCM padding/trimming was added to hide this
variance; production decoder/container behavior was retained for this scope.

The original downloaded Opus/VP9 files and the full H.264/AAC derivatives are
separate inputs with documented hashes. Audio wave comparisons against the
original downloaded WAV cannot be treated as exact AAC-import parity.

## Scene-gate-off policy exports

Two full-length person analyses disabled the scene gate to prevent NSFW
whole-frame coverage from concealing body-tracking behavior. Their raw geometry
and votes were retained. Final policies require at least two agreeing votes
before allowing a known classification; a single occluded-face vote remains
unknown and receives the conservative censor policy. All six final policy
exports below were rerendered from those raw records with the final rule and
without analysis. Fresh prior-code face-only analyses are the visual comparators;
no NSFW intervals were removed by assumption.

| Clip / target policy | Full export s | Analysis frames | Region frames | Whole-frame fallback frames | Kernel peak MiB |
| --- | ---: | ---: | ---: | ---: | ---: |
| Short 1: prior faces / women | 6.346 | 542 | 1592 | 0 | 105.7 |
| Short 1: final persons / women, saved EDL | 3.167 | 0 | 1522 | 90 | 99.5 |
| Short 1: final persons / men, saved EDL | 3.121 | 0 | 560 | 45 | 98.2 |
| Short 1: final persons / everyone, saved EDL | 3.154 | 0 | 1522 | 90 | 98.5 |
| Short 2: prior faces / women | 6.204 | 533 | 818 | 0 | 109.2 |
| Short 2: final persons / women, saved EDL | 3.186 | 0 | 1123 | 219 | 111.3 |
| Short 2: final persons / men, saved EDL | 3.138 | 0 | 996 | 180 | 113.3 |
| Short 2: final persons / everyone, saved EDL | 3.160 | 0 | 1123 | 219 | 113.6 |

All eight exports passed exact video frame/presentation checks and identical
source-audio compressed-stream hashes. Re-rendered policies retain all 76/433
person records. Counts describe where the renderer applied effects, not human
ground-truth coverage or gender accuracy. Short 2's 433 track fragments and
conservative fallbacks are concrete limitations for the visual review.
The final review identified remaining standalone-hand frames around 5.867–6.3 s
and the tiny camera-screen face at 8.5 s. The native hand-pose probe did not
reliably detect those views, so no unvalidated detector was added. Complete
coverage is not claimed. Earlier person-policy rows are explicitly marked
pre-two-vote diagnostics; their raw geometry/votes remain valid rerender inputs.

## Evidence and reproduction

```sh
bash scripts/modelbench/build_apple_integration.sh \
  /path/to/macOS-DerivedData /path/to/Models build.noindex/integration-current
build.noindex/integration-current/naqi-integration.app/Contents/MacOS/naqi-integration \
  /path/to/full-input.mp4 /path/to/fresh-output.mp4 \
  docs/benchmarks/results/person-integration-native.jsonl \
  women person off combined on
```

The final arguments are policy (`women`, `men`, `everyone`), target (`face`,
`person`), music gate, mode (`combined`, `music`, `visual`, `rerender`) and scene
gate. `rerender` additionally takes a saved EDL and performs no reanalysis.
Input/output/results/derived artifact aliases are rejected, including symlinks;
existing output artifacts are refused. Model bundle lookups use the production
paths. A fourth build-script argument supplies an older source checkout for
the original face/gate-on implementation.

Raw evidence: [`native complete jobs`](results/person-integration-native.jsonl),
[`actual model/gate stage audit`](results/person-integration-stage-audit.jsonl),
[`startup/pre-fix run status`](results/person-integration-run-status.json),
[`decoded/movie timestamp check`](results/person-integration-smoke-video-diagnostics.json),
[`native AAC input diagnostics`](results/person-integration-source-audio-diagnostics.json),
[`native AAC output diagnostics`](results/person-integration-output-audio-diagnostics.json),
[`independent FFmpeg audit`](results/audio-integration-reader-audit.json).
Full media, per-frame regions and saved analysis remain outside Git under
`qa-assets/modelbench/integration`.
