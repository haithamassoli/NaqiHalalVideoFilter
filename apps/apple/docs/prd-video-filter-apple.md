# PRD — Naqi Halal Video Filter (Apple: iPhone / iPad / Mac, Swift)

## Summary
Native Apple port of Naqi (shipped Android app). Filters a locally selected video entirely on-device and saves a filtered copy; the original file is never modified. Two independent operations, run alone or together: (1) **remove music** — stem separation keeps `vocals` or `vocals + other`; drums/bass never kept; (2) **censor** — faces of the chosen gender (Who = women | men) blurred for their whole on-screen span, plus whole-frame censoring while an NSFW classifier gate fires, with pre-roll so nothing slips through. Censor style: blur amount + grayscale, region or whole-frame mode. No cloud, no accounts, no telemetry; network only for optional model download. v1 = feature parity with Android 1.3 as a full Swift rewrite — the Android pipeline is MediaCodec/GLES/ORT-Android-bound; none of it ports.

**Reference implementation:** `github.com/haithamassoli/NaqiHalalVideoFilter`. Where this doc under-specifies behavior (thresholds, hysteresis, EDL rules), the Android code + `docs/prd-video-filter-android.md` are the spec. Its `qa-assets` clips + Android outputs are the parity suite.

## Scope
- Input: one local video via Photos picker, Files, share sheet, or drag-drop (Mac). MP4/MOV, H.264/H.265, AAC/MP3 audio. MKV/Opus: AVFoundation cannot demux it — M0 spike decides drop-in-v1 vs embedded demuxer. Feature-length input supported.
- Output: new file (Photos or user folder), source resolution/fps preserved, HDR tonemapped to SDR.
- Processing: local job queue, per-stage progress, cancellable, checkpointed per segment — survives app kill and reboot, resumes where it stopped.
- Localization: English + Arabic (RTL).
- Distribution: App Store. Models bundled in the app (as on Android; ~170 MB).

## Non-goals (v1)
URL/link download (cut on Android too); realtime/streaming/overlay filtering; DRM content; cloud processing; batch on iPhone; watchOS/tvOS/visionOS; Catalyst; dialogue/music/effects (Bandit-class) model upgrade.

## User flow
Pick video → choose ops `[Remove music] [Censor] (≥1 required)` → options (last-used preselected) → start → progress (Live Activity on iOS) → done: saved copy + Open / Share / Delete-original. Share-in queues immediately with last-used options; multiple shares run in order.

## Options (user-facing — semantics identical to Android)
| Option | Control | Applies to |
|---|---|---|
| Who | Women (default) \| Men | Which gender's face tracks are censored |
| Censor mode | Regions (default) \| Whole frame | Whole frame promotes region censors to full-frame at EDL build; a min-duration floor kills sub-second full-screen flashes from detector false positives |
| Strictness | Slider 0–100 | NSFW gate thresholds only, never face blur |
| Blur amount | Slider 0–100 → Gaussian sigma scaled to resolution | Faces + full-frame censor |
| Grayscale | Toggle, combinable with blur | Faces + full-frame censor |
| Keep stems | `vocals` (default) \| `vocals + other` | Music removal |

## Models
| Model | Job | Runtime (v1) | Size |
|---|---|---|---|
| NSFW 5-class MobileNet (Porn/Sexy/Hentai/Neutral/Drawing) | Whole-frame gate @ 5 fps sampled | ONNX Runtime | ~10 MB |
| Vision face detection + tracking (OS-provided) | Face boxes + track IDs @ 10 fps sampled | Vision | — |
| genderage | Gender vote per face track, ≤5 frontal crops | ONNX Runtime | small |
| htdemucs 4-stem, STFT/iSTFT outside graph, 2.6 s segments | Stem separation, chunked overlap-add | ONNX Runtime | ~160 MB f16 |

- Same `.onnx` artifacts as Android via the ORT iOS/macOS pod first — bit-parity with Android outputs, zero conversion risk. Core ML/ANE conversion is a post-v1 perf spike, taken only if measured wall demands it.
- Do **not** reintroduce NudeNet: removed from Android for AGPL licensing and misfires; genderage is the gender source.

## Pipeline — two-pass (architecture identical to Android)
Pass 1 analyze (decode only): NSFW gate with strictness-interpolated per-class thresholds and merge hysteresis `[t−0.5 s, t+1.5 s]`; face boxes interpolated to full fps, padded 25 %; gender majority vote per track; emit EDL (censor intervals + per-frame regions). Pass 2 render: apply EDL, replace audio with kept stems, encode.

Apple mapping:
| Concern | Android (shipped) | Apple |
|---|---|---|
| Decode/encode | MediaCodec + MediaMuxer | AVAssetReader + AVAssetWriter (VideoToolbox HW) |
| Fast paths | Track remux: censor-only → audio passthrough; music-only → video passthrough | AVAssetWriterInput with `outputSettings: nil` (compressed sample append) |
| Face detect | ML Kit (no macOS support) | Vision — recall/track behavior differs; re-tune sampling fps, padding, vote count against qa-assets |
| Censor effect | GLES shaders | Core Image (`CIGaussianBlur` + mono) into the writer; downscale→blur→upscale for large sigma |
| >4 GiB output | MediaMuxer co64 | AVAssetWriter — free |
| Background job | Foreground service, 6 h cap | iOS has no equivalent: job runs in foreground with keep-awake toggle; suspension/kill is handled by the existing checkpoint-resume design. macOS: unconstrained |
| Share-in | Share target → queue | Share Extension → App Group handoff to main app. Never process in the extension (~120 MB memory cap) |

## Performance budgets (Android S23 measured → Apple targets)
- Three job shapes with disjoint walls: music-only → audio wall; censor-only → analyze wall; both → audio wall (~65 % of total). Optimize per shape.
- htdemucs is the single dominant cost (~0.55× realtime on S23 CPU). Target: ≥ parity on A16/M1. Only this model justifies a Core ML/ANE spike.
- Analyze is producer-bound (decode + pixel convert), not model-bound (detect ≈ 1.6 ms/frame). Keep decoder-native 4:2:0 `CVPixelBuffer`s end-to-end, pooled; no CPU format hops.
- Render is encoder-paced; whole-frame blur is ≈ free; GPU micro-opts are noise.
- Peak RAM ≤ 1.5 GB on iPhone (jetsam headroom); re-run the htdemucs segment-size sweep on-device before changing the 2.6 s dial.
- A 90-min film completes on a passively cooled iPhone despite thermal throttling (checkpoints make slow acceptable; dying is not).

## Risks
- f16 htdemucs produced NaN on Android's fp16 execution path — pin fp32 execution; validate stems against Android reference outputs before any UI work.
- Vision ≠ ML Kit: gate/censor QA must re-run per platform; expect threshold re-tuning, not code parity.
- iOS foregrounding: a 2 h film may need the phone open ~2 h or multiple resume sessions. UI copy must say so honestly.
- App Review: emphasize all inference on-device, nothing collected (privacy label: no data).

## V1 acceptance
- One multiplatform SwiftUI target builds and runs on iPhone, iPad, Mac.
- qa-assets clips produce EDLs and stems within agreed tolerance of Android outputs.
- 90-min film finishes with a forced kill + resume mid-job; output plays end-to-end, original untouched.
- Share-in queue, EN/AR, options persistence all work as on Android.

## Open questions
1. Device/OS floor — proposal iOS 17 / macOS 14; validate htdemucs peak RAM on a 4 GB iPhone before committing, else floor at 6 GB devices.
2. Is Mac a parity checkbox or first-class? It has no suspension problem and drag-drop batch — arguably Naqi's best home for long films. Does batch enter v1 on Mac only?
3. Is "keep the app open for a feature film" acceptable v1 UX on iPhone, or do long films get positioned as iPad/Mac features?
4. Pricing: Android is free; App Store — free, paid, or one-time IAP?
