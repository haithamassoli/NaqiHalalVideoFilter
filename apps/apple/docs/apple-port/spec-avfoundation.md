# Naqi — AVFoundation + Vision layer: exact porting spec (Android → Apple)

Target: **iOS 26 / macOS 26, Swift 6, Xcode 26.6 (17F113), iPhoneOS SDK 26.5**.
Extracted from the shipped Android app at `/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter`
(commit-state 2026-08-04) and verified against the installed SDK headers and against code actually
compiled and run on this machine on 2026-08-04.

## 0. Citation shorthand

| Tag | Path |
|---|---|
| `FS` | Android `app/src/main/java/com/haithamassoli/naqi/analysis/FrameSampler.kt` |
| `FT` | Android `.../analysis/FaceTracker.kt` |
| `CT` | Android `.../analysis/Contracts.kt` |
| `CE` | Android `.../render/CensorEffect.kt` |
| `RP` | Android `.../render/RenderPipeline.kt` |
| `RX` | Android `.../audio/Remux.kt` |
| `AW` | Android `.../audio/AacWriter.kt` |
| `AD` | Android `.../audio/AudioDecoder.kt` |
| `ED` | Android `.../edl/Edl.kt` |
| `CP` | Android `.../work/Checkpoint.kt` |
| `FW` | Android `.../work/FilterWorker.kt` |
| `SDK` | `/Applications/Xcode.app/Contents/Developer/Platforms/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk` |
| `AVFH` | `$SDK/System/Library/Frameworks/AVFoundation.framework/Headers` |
| `AVFSI` | `$SDK/usr/lib/swift/AVFoundation.swiftmodule/arm64e-apple-ios.swiftinterface` |
| `VNSI` | `$SDK/System/Library/Frameworks/Vision.framework/Modules/Vision.swiftmodule/arm64e-apple-ios.swiftinterface` |
| `CIH` | `$SDK/System/Library/Frameworks/CoreImage.framework/Headers` |
| `CMH` | `$SDK/System/Library/Frameworks/CoreMedia.framework/Headers` |
| `CVH` | `$SDK/System/Library/Frameworks/CoreVideo.framework/Headers` |
| `M#n` | Measurement n from §11 — code I compiled and ran on this machine, 2026-08-04 |

Everything marked `M#n` is a number this document produced by running code, not by reading docs.
`qa-assets/test-video.mp4` (1080×1920, H.264 `avc1`, 30 fps, 12.8 s, AAC 48 kHz) is the probe asset;
it is the same file the Android docs measure against (`CP:57`, `FW:505`, `AD:268`).

## 0.1 One-line answers to the nine questions

| # | Question | Answer |
|---|---|---|
| 1 | decoder-native 4:2:0, no CPU hop | `AVAssetReaderTrackOutput(outputSettings:)` with `kCVPixelBufferPixelFormatTypeKey = '420v'` + `IOSurfaceProperties` + `alwaysCopiesSampleData = false`. **Verified**: decoded buffers come back `'420v'`, IOSurface-backed, 1080×1920, no scaling (M#4) |
| 2 | encode | `AVVideoCompressionPropertiesKey` dictionaries below; both H.264 and HEVC accepted by a real MP4 `AVAssetWriter` (M#3). >4 GiB works — writer emits 64-bit `mdat` + `co64` automatically (M#7) |
| 3 | passthrough bit-identity | `outputSettings: nil` + **mandatory** `sourceFormatHint` for MP4. **Verified bit-identical** SHA-256 over all 384 video samples (M#5); without the hint `addInput:` throws (M#6) |
| 4 | HDR→SDR | iOS 26: `CIFilter.systemToneMap()` (new in 26). iOS 18 floor: `CIFilter.toneMapHeadroom()`. Tag output SDR via `AVVideoColorPropertiesKey` and/or `CVBufferSetAttachment` |
| 5 | rotation | `preferredTransform` on the track; write it back onto `AVAssetWriterInput.transform`. Rect mapping table in §5.4 — direct port of `CT:21-26` |
| 6 | Vision tracking | Vision gives **stable UUIDs per `TrackObjectRequest` instance** (M#4b), but **no cross-detection identity**. You must hold one `TrackObjectRequest` per track and IoU-match new detections yourself |
| 7 | CI blur | `CIContext(mtlCommandQueue:options:)`, working space `extendedLinearSRGB`, render into `AVAssetWriterInputPixelBufferAdaptor.pixelBufferPool` |
| 8 | audio | `AVAssetReaderAudioMixOutput` with LPCM f32; AAC-LC out via `AVAssetWriterInput`. Encoder delay is carried as `kCMSampleBufferAttachmentKey_TrimDurationAtStart` and AVFoundation both **produces and consumes** it (M#2) |
| 9 | MKV/WebM | **Not supported.** `AVURLAsset.audiovisualTypes()` (104 entries) contains no Matroska/WebM UTI (M#1a); opening a real `.webm` fails `AVError -11828 / OSStatus -12847` (M#1b). Needs a third-party demuxer |

---

# 1. Decode — `AVAssetReader`

## 1.1 What the Android side does, so the Apple side matches bit for bit

| Property | Android value | Citation |
|---|---:|---|
| Sample rate of pass 1 | **10 fps** | `FS:121` |
| Downscale for the detector | longest side **640 px**, downscale only | `FS:122`, `FS:356` |
| Decoder colour format request | `COLOR_FormatYUV420Flexible` (never a concrete layout) | `FS:201-206` |
| Decoder dequeue timeout | 10 000 µs on output, **0 (non-blocking) on input** | `FS:57`, `FS:224`, `FS:237` |
| Producer/consumer queue depth | `QUEUE = 2`, ring `RING = QUEUE + 2 = 4` | `FS:67-68` |
| Slot interval | `(1_000_000f / fps).toLong().coerceAtLeast(1)` µs = **100 000 µs** | `FS:133` |
| Slot anchor | window start when windowed, else the **first decoded frame's pts** | `FS:218` |
| Slot resync after a gap | `if (nextSlotUs <= ptsUs) nextSlotUs = ptsUs + slotIntervalUs` | `FS:278-279` |
| Rotation clamp | non-multiple-of-90 degrades to 0, never throws | `FS:131` |
| Even output dims | `maxOf(2, round(w*scale)) and 1.inv()` | `FS:359-360` |
| `KEY_OPERATING_RATE = MAX_VALUE` | **tried and removed** — measured −0.5 % (9 985 → 9 938 ms), i.e. noise | `FS:207-209` |

**Do not port** `packNv21` / `gatherGate` / `convertToTensor` shape-shuffling into the Apple decode
path. Those exist because ML Kit wanted NV21 and the ONNX gate wanted NCHW RGB out of the same
gralloc map. On Apple, Vision consumes the `CVPixelBuffer` directly (§6) and the ONNX gate reads it
via `vImage`/Metal — see `spec-analyze.md`. The **sampling grid, the 640-px cap, the rotation
handling and the 224²/96² tensor addressing** are the parts that must survive; §10 tabulates them.

## 1.2 Output settings — the exact dictionary

```swift
import AVFoundation
import CoreVideo

/// Decoder-native 4:2:0 with zero CPU format conversion.
/// - 8-bit SDR H.264/HEVC decode natively to '420v' (video range).
/// - 10-bit HDR (HLG/PQ) HEVC decodes natively to 'x420'.
/// Requesting anything else inserts a VideoToolbox pixel-transfer pass.
enum NaqiDecodeFormat {
    static let sdr8  = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange   // '420v'  CVH/CVPixelBuffer.h:79
    static let hdr10 = kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange  // 'x420'  CVH/CVPixelBuffer.h:111

    static func outputSettings(tenBit: Bool) -> [String: Any] {
        [
            kCVPixelBufferPixelFormatTypeKey as String: tenBit ? hdr10 : sdr8,   // CVH/CVPixelBuffer.h:232
            // Presence of this key requests IOSurface-backed allocation. Empty dict = defaults.
            kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),      // CVH/CVPixelBuffer.h:245
            kCVPixelBufferMetalCompatibilityKey as String: true,                  // CVH/CVPixelBuffer.h:247
            // DELIBERATELY ABSENT: kCVPixelBufferWidthKey / kCVPixelBufferHeightKey.
            // Adding them makes AVAssetReader scale, which is a second GPU/CPU pass.
        ]
    }
}
```

Rules, each load-bearing:

1. `outputSettings: nil` on a video track means **compressed samples, no decode**
   (`AVFH/AVAssetReaderOutput.h:223`, `:258`). That is the passthrough path (§3), not the decode path.
2. `alwaysCopiesSampleData = false` — the header calls this an "IMPORTANT PERFORMANCE NOTE"
   (`AVFH/AVAssetReaderOutput.h:39`, property at `:71`). The buffer is then owned by the reader; you
   must finish with it before the next `next()`/`copyNextSampleBuffer()`. This is exactly the Android
   ring-buffer contract (`FS:102-104`) and has the same failure mode.
3. When `outputSettings` is nil the reader can hand back **marker-only sample buffers**
   (`CMSampleBufferGetNumSamples == 0`) — skip them (`AVFH/AVAssetReaderOutput.h:91`). Android's
   equivalent is `info.size > 0` (`FS:244`, `FS:248`). **Measured**: reading `test-video.mp4`'s video
   track with `outputSettings: nil` yields 388 buffers of which 384 carry samples (M#7 preamble).
4. Do **not** set `kCVPixelBufferWidthKey`/`HeightKey` to get the Android 640-px downscale. Do the
   downscale where you consume it (Vision has `regionOfInterest`; Core Image has a transform). The
   reader's scaler is a full extra conversion pass and it is not what the Android code did either —
   Android's downscale is fused into the nearest-neighbour gather (`FS:369-370`).

## 1.3 The 10 fps slot grid — direct port of `FS:240-280`

```swift
struct SlotGrid {
    let interval: CMTime
    private(set) var next: CMTime = .invalid

    /// fps = 10 in production (FS:121). interval = 1/10 s exactly, in a rational timescale,
    /// so the grid never drifts the way `(1_000_000f / fps).toLong()` µs truncation can.
    init(fps: Int32) { interval = CMTime(value: 1, timescale: fps) }

    /// Windowed segments anchor to the window start so a segment is reproducible across a
    /// resume — FS:216-218. Unwindowed anchors to the first decoded frame.
    mutating func anchor(_ t: CMTime) { if !next.isValid { next = t } }

    mutating func shouldEmit(_ pts: CMTime) -> Bool {
        anchor(pts)
        guard pts >= next else { return false }
        next = next + interval
        if next <= pts { next = pts + interval }   // resync after a gap — FS:279
        return true
    }
}
```

## 1.4 One sequential pass — legacy loop (iOS 18 floor)

```swift
func sampleAt10fps(
    asset: AVURLAsset,
    window: CMTimeRange?,
    tenBit: Bool,
    onFrame: (CVPixelBuffer, CMTime) async throws -> Void
) async throws {
    guard let track = try await asset.loadTracks(withMediaType: .video).first else { return }
    let reader = try AVAssetReader(asset: asset)
    if let w = window { reader.timeRange = w }               // AVFH/AVAssetReader.h:141
    let out = AVAssetReaderTrackOutput(
        track: track,
        outputSettings: NaqiDecodeFormat.outputSettings(tenBit: tenBit))
    out.alwaysCopiesSampleData = false
    guard reader.canAdd(out) else { throw NaqiError.readerRejectedOutput }
    reader.add(out)
    guard reader.startReading() else { throw reader.error! }

    var grid = SlotGrid(fps: 10)
    while let sb = out.copyNextSampleBuffer() {              // soft-deprecated in Swift, see §1.5
        guard CMSampleBufferGetNumSamples(sb) > 0,
              let pb = CMSampleBufferGetImageBuffer(sb) else { continue }
        let pts = CMSampleBufferGetPresentationTimeStamp(sb)
        guard grid.shouldEmit(pts) else { continue }
        try await onFrame(pb, pts)
    }
    if reader.status == .failed { throw reader.error! }
}
```

## 1.5 One sequential pass — iOS 26 API (**use this**)

`copyNextSampleBuffer()` is **soft-deprecated for Swift** in iOS 26:
`API_DEPRECATED("Use AVAssetReaderOutput.Provider.next() instead", …, ios(4.1, API_TO_BE_DEPRECATED))`
(`AVFH/AVAssetReaderOutput.h:97`). The replacement is a `sending` provider with an `async` `next()`
(`AVFSI:92-111`), which is what makes the read loop Swift-6-clean without `@unchecked Sendable`
wrappers:

```swift
@available(iOS 26.0, macOS 26.0, *)
func sampleAt10fps26(
    asset: AVURLAsset, window: CMTimeRange?, tenBit: Bool,
    onFrame: (CVReadOnlyPixelBuffer, CMTime) async throws -> Void
) async throws {
    guard let track = try await asset.loadTracks(withMediaType: .video).first else { return }
    let reader = try AVAssetReader(asset: asset)
    if let w = window { reader.timeRange = w }
    let out = AVAssetReaderTrackOutput(track: track,
                                       outputSettings: NaqiDecodeFormat.outputSettings(tenBit: tenBit))
    out.alwaysCopiesSampleData = false
    reader.add(out)
    let provider = reader.outputProvider(for: out)           // AVFSI:109
    guard reader.startReading() else { throw reader.error! }

    var grid = SlotGrid(fps: 10)
    while let ready = try await provider.next() {            // AVFSI:97
        guard grid.shouldEmit(ready.presentationTimeStamp) else { continue }
        guard case .pixelBuffer(let ro) = ready.content else { continue }   // CoreMedia CMSampleBuffer.DynamicContent
        try await onFrame(ro, ready.presentationTimeStamp)
    }
}
```

`CMReadySampleBuffer<CMSampleBuffer.DynamicContent>` is `Sendable`; `.content` is an enum with
`.markerOnly / .sampleReference / .dataBuffer / .pixelBuffer / .taggedBuffers`, so the marker-only
case from §1.2 rule 3 becomes a compile-checked branch instead of a `numSamples == 0` test.

## 1.6 Sequential pass vs `AVAssetImageGenerator` — settled

| | one `AVAssetReader` pass | `AVAssetImageGenerator.images(for:)` |
|---|---|---|
| Work per 10 fps sample on a 30 fps source | decode 3 frames, hand over 1 | seek + decode from preceding sync sample |
| Cost on a sparse-keyframe source | linear in frames | **quadratic-ish**: `test-video.mp4` has sync samples only at 0.0667 s and 8.4 s (M#8), so a sample at 8.3 s re-decodes ~248 frames |
| Output | `CVPixelBuffer`, IOSurface-backed, decoder-native `'420v'` | `CGImage` (RGB, CPU-side) unless you route through `CIImage` |
| Rotation | stored orientation, you apply `preferredTransform` | `appliesPreferredTrackTransform` bakes it in |
| Verdict | **Use this.** Matches `FS`'s "ONE sequential decode-only pass (no per-frame seeking)" (`FS:26`) | Only for thumbnails / the poster frame |

Measured on `test-video.mp4`: the sequential pass decoded **384** frames and emitted **128** at 10 fps
over 12.8 s — exactly `12.8 × 10` (M#4).

Do **not** try to build the 10 fps grid with `AVAssetReaderOutput.supportsRandomAccess` +
`resetForReadingTimeRanges:` (`AVFH/AVAssetReaderOutput.h:120`, `:158`; iOS 26 Swift form
`AVAssetReaderOutput.RandomAccessController.resetForReading(timeRanges:)`, `AVFSI:84-88`). Each range
still starts decoding at the preceding sync sample, so on a 8.3-s-GOP source you pay the whole GOP per
100 ms sample. That API exists for `AVAssetWriterInput` multi-pass, per its own docs
(`AVFH/AVAssetReaderOutput.h:140`).

## 1.7 Segment windows — the Apple form of the Android sync-snap landmine

Android had to snap interior segment boundaries to sync samples because media3 ends a clipped read at
the first sample **in decode order** whose pts reaches the clip end, silently dropping 1–3 frames per
seam — 49 frames over 31 seams on a 2.6 h film, and on an MKV source it shifted a whole segment ~1 s
early (`CP:48-63`, `FW:476-528`, `docs/long-film-followups.md` §"Pixel-validated": worst mismatch
**77.7** pre-snap vs **3.0** post-snap).

**On Apple the two read modes behave differently and you must know which you are in** (M#8):

| Read mode | `reader.timeRange = [5.0 s, 7.0 s)` on `test-video.mp4` (syncs at 0.0667 s, 8.4 s) |
|---|---|
| Decompressed (`outputSettings` = pixel format) | **Frame-accurate**: first PTS `5.000000`, exactly **60** frames. The reader decodes from the preceding sync sample internally and discards. No snapping needed. |
| Passthrough (`outputSettings: nil`) | **GOP-granular at the head**: first PTS `0.0667` (the preceding sync sample), **212** samples delivered. End is honoured, start is not. |

Contracts:

1. **Re-encode segments** (`renderCensor(segment:)` equivalent) — set `reader.timeRange` to the
   nominal boundary. No sync-sample snapping is required. This deletes `FW.planFor`'s entire
   `seekTo(SEEK_TO_NEXT_SYNC)` dance.
2. **Passthrough segments / any sample-copy concat** — you must either snap the segment start to a
   sync sample yourself, or read from the sync sample and drop samples whose PTS is below the
   boundary. Snapping is what Android does and is cheaper.
3. Whatever you choose, keep `CP:65-80`'s invariant: **interior boundaries are shared by both passes**,
   and the analyze grid is anchored to the boundary (`FS:216-218`), or a resumed job re-samples at
   different timestamps and the checkpoint stops being reproducible.
4. Keep boundaries at **whole milliseconds** (`FW:481-489`) only if you keep the Android EDL's
   millisecond timeline. If you move the EDL to `CMTime`, drop that constraint — it exists purely
   because `CensorEffect` reconstructs absolute time as `presentationTimeUs / 1000 + startMs`
   (`CE:173`).

## 1.8 Frame timing and pixel-buffer pools on the read side

- `AVAssetReader` hands you buffers from its own pool. You do not create it and you cannot size it.
  The only knob is `alwaysCopiesSampleData`.
- PTS from a track output are **asset-timeline** values, not rebased, even with `reader.timeRange` set
  (measured: first PTS is `5.000000`, not `0`, M#8). This is the **opposite** of media3, which rebases
  a clipped export to 0 and forced `CE:173`'s `+ timeOffsetMs` correction
  (`ExoAssetLoaderVideoRenderer.java:185`). **The Apple port must delete that offset**, or every
  segment past the first censors the wrong moments in the other direction.
- Sample buffers arrive in **decode order**, so PTS is **not monotonic** on any B-frame stream.
  Measured first five video PTS of `test-video.mp4`: `0.0667, 0.200, 0.133, 0.100, 0.167` (M#5).
  This is the same fact `RX:315` records for `MediaExtractor`. Anything that assumes increasing PTS
  (progress bars, "past the window: stop") must use `max()` or DTS, exactly as `FS:244` does.

---

# 2. Encode — `AVAssetWriter`

## 2.1 Bitrate policy to preserve (Android `RP`)

| Rule | Value | Citation |
|---|---:|---|
| Second-generation headroom over source bitrate | **×1.3** | `RP:64`, `RP:266` |
| Effective bitrate | `min(sourceBitrate × 1.3, tierCap)` | `RP:266` |
| Cap ≤ 854×480 | 4 000 000 | `RP:276` |
| Cap ≤ 1280×720 | 10 000 000 | `RP:277` |
| Cap ≤ 1920×1080 | 16 000 000 | `RP:278` |
| Cap ≤ 2560×1440 | 24 000 000 | `RP:279` |
| Cap otherwise (4K) | 45 000 000 | `RP:280` |
| Keyframe interval | **2.0 s** (media3 default is 1 s) | `RP:158` |
| Codec | H.264 forced, so every segment shares one track format | `RP:176` |
| Bitrate resolution | **once per job**, never per segment (35–70 container opens on a film) | `RP:224` |
| Overflow guard | multiply in `Float`, not `Int` — `it * 1.3` overflows `Int` above ~165 Mbps | `RP:264-266` |

Swift port of the tier table, unchanged semantics:

```swift
enum Bitrate {
    static let gen2Headroom: Double = 1.3            // RP:64

    static func cap(pixels: Int64) -> Int {          // RP:275-281
        switch pixels {
        case ...(854 * 480):    return  4_000_000
        case ...(1280 * 720):   return 10_000_000
        case ...(1920 * 1080):  return 16_000_000
        case ...(2560 * 1440):  return 24_000_000
        default:                return 45_000_000
        }
    }

    /// One call per job. AVAssetTrack.estimatedDataRate is bits/second for that track alone,
    /// which is the same thing MediaFormat.KEY_BIT_RATE gives on Android (RP:237).
    static func resolve(track: AVAssetTrack) async throws -> Int {
        let size = try await track.load(.naturalSize)
        let cap = cap(pixels: Int64(size.width) * Int64(size.height))
        let src = Double(try await track.load(.estimatedDataRate))       // Float bits/s, 0 if unknown
        guard src > 0 else { return cap }
        return min(Int((src * gen2Headroom).rounded()), cap)             // Double math: no Int overflow
    }
}
```

`AVAssetTrack.estimatedDataRate` is the Apple analogue of `KEY_BIT_RATE`; there is no
`MediaMetadataRetriever.METADATA_KEY_BITRATE` fallback needed because `estimatedDataRate` is derived
from the track's own sample table.

## 2.2 The settings dictionaries

```swift
import AVFoundation
import VideoToolbox

enum NaqiVideoSettings {
    /// SDR BT.709 tagging triple. All three keys are REQUIRED together — AVFH/AVVideoSettings.h:108,
    /// and the "HD" recipe is exactly this triple (AVFH/AVVideoSettings.h:111-113).
    static let bt709: [String: Any] = [
        AVVideoColorPrimariesKey:   AVVideoColorPrimaries_ITU_R_709_2,     // AVVideoSettings.h:155
        AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,   // AVVideoSettings.h:161
        AVVideoYCbCrMatrixKey:      AVVideoYCbCrMatrix_ITU_R_709_2,        // AVVideoSettings.h:168
    ]

    static func make(
        codec: AVVideoCodecType,      // .h264 (AVVideoSettings.h:44) or .hevc (:43)
        width: Int, height: Int,      // MUST be even for 4:2:0 — AVVideoSettings.h:65
        bitrate: Int,
        fps: Float,
        keyFrameSeconds: Double = 2.0 // RP:158
    ) -> [String: Any] {
        var compression: [String: Any] = [
            AVVideoAverageBitRateKey: bitrate,                          // AVVideoSettings.h:193
            AVVideoMaxKeyFrameIntervalDurationKey: keyFrameSeconds,     // AVVideoSettings.h:196
            AVVideoExpectedSourceFrameRateKey: Int(fps.rounded()),      // AVVideoSettings.h:247
            AVVideoAllowFrameReorderingKey: true,                       // AVVideoSettings.h:210
        ]
        switch codec {
        case .hevc:
            // HEVC profile/level constants live in VideoToolbox — AVVideoSettings.h:214
            compression[AVVideoProfileLevelKey] = kVTProfileLevel_HEVC_Main_AutoLevel
        default:
            compression[AVVideoProfileLevelKey] = AVVideoProfileLevelH264HighAutoLevel  // :227
            compression[AVVideoH264EntropyModeKey] = AVVideoH264EntropyModeCABAC        // :238
        }
        return [
            AVVideoCodecKey:  codec,          // AVVideoSettings.h:35
            AVVideoWidthKey:  width,          // AVVideoSettings.h:66
            AVVideoHeightKey: height,         // AVVideoSettings.h:67
            AVVideoColorPropertiesKey: bt709, // AVVideoSettings.h:153
            AVVideoCompressionPropertiesKey: compression,  // AVVideoSettings.h:192
        ]
    }
}
```

**Both dictionaries are accepted by a real MP4 `AVAssetWriter`** — `canApply(outputSettings:forMediaType:)`
returned `true` for H.264 and for HEVC, plus for `nil` (passthrough) and for the AAC dictionary in §8
(M#3). Note `AVFH/AVAssetWriterInput.h:54` still claims *"On iOS, the only values currently supported
for AVVideoCodecKey are AVVideoCodecTypeH264 and AVVideoCodecTypeJPEG"* — **that comment is stale**
(HEVC has shipped since iOS 11, `AVVideoSettings.h:43`) and `canApply` disagrees with it.

### 2.2.1 Key reference

| Key | Type | Header | Notes |
|---|---|---|---|
| `AVVideoAverageBitRateKey` | `NSNumber` bits/s | `AVVideoSettings.h:193` | Comment says "H.264 only"; accepted for HEVC in practice (M#3). This is a **long-term average**, not a cap |
| `AVVideoMaxKeyFrameIntervalKey` | frames | `AVVideoSettings.h:195` | `1` = all-I. Prefer the duration form so a VFR source behaves |
| `AVVideoMaxKeyFrameIntervalDurationKey` | seconds, `0.0` = no limit | `AVVideoSettings.h:196` | **Set to 2.0** to match `RP:158` |
| `AVVideoExpectedSourceFrameRateKey` | fps hint | `AVVideoSettings.h:247` | Header: *"should be set if an AutoLevel AVVideoProfileLevelKey is used"* — we always use AutoLevel, so this is **required**, not optional |
| `AVVideoAllowFrameReorderingKey` | `BOOL` | `AVVideoSettings.h:210` | `true` = B-frames. Android left B-frames off (`RP:159-160` "no B-frames (setMaxBFrames) yet"). Turning them on is a quality/size win at the cost of decode-order PTS in the output |
| `AVVideoProfileLevelKey` | `NSString` | `AVVideoSettings.h:212` | H.264 constants `:216-227`; HEVC constants come from `VTCompressionProperties.h` |
| `AVVideoH264EntropyModeKey` | CABAC/CAVLC | `AVVideoSettings.h:236-238` | CABAC unless you need Baseline |
| `AVVideoAverageNonDroppableFrameRateKey` | fps | `AVVideoSettings.h:260` | Only for temporal-layer encodes; not used here |
| `AVVideoAllowWideColorKey` | `BOOL` | `AVVideoSettings.h:181` | Leave unset when you set `AVVideoColorPropertiesKey` |
| `kVTCompressionPropertyKey_ConstantBitRate` | bits/s | VideoToolbox | Accepted (M#3). **Do not use** — CBR wastes bits on a censor job whose content is mostly untouched |
| `kVTCompressionPropertyKey_DataRateLimits` | `[bytes, seconds]` | VideoToolbox | Accepted (M#3). Useful as a hard peak cap alongside the average |
| `kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality` | `BOOL` | VideoToolbox | Accepted. The Apple analogue of `RP:164`'s `operatingRate=1000, priority=1`. Leave `false` for an offline job |

**Colour tagging semantics** (`AVVideoSettings.h:141-149`), which decide whether an SDR output is
correctly tagged:

- Key **set**, source tagged → source buffers are **colour-converted** to match, output tagged as set.
- Key **set**, source untagged → output tagged as set, no conversion.
- Key **absent**, source tagged → output inherits the source tags.
- Key **absent**, source untagged → **output carries no colour tags at all** (the "washed out on some
  players" bug). Always set the triple.

## 2.3 Preserving fps, transform, timescale

```swift
let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
input.expectsMediaDataInRealTime = false          // AVFH/AVAssetWriterInput.h:174 — offline job
input.transform = sourcePreferredTransform        // AVFH/AVAssetWriterInput.h:317 — see §5
input.mediaTimeScale = sourceNaturalTimeScale     // AVFH/AVAssetWriterInput.h:352
writer.add(input)
```

- **fps is not a setting.** The output frame rate is entirely determined by the PTS you append.
  `AVVideoExpectedSourceFrameRateKey` is a hint to the encoder's rate controller only
  (`AVVideoSettings.h:245`: *"This is not used to control the frame rate"*). To preserve source
  timing exactly, append each frame with the source PTS you read.
- **Sample duration** for non-audio tracks is derived from the DTS delta to the next appended sample;
  the *last* sample's duration comes from its own duration, or from the second-to-last sample, or from
  a `kCMSampleBufferAttachmentKey_EndsPreviousSampleDuration` marker
  (`AVFH/AVAssetWriterInput.h:229-233`). If the tail frame's duration matters, append the marker.
- `mediaTimeScale` must be set **before** `startWriting()` and cannot be changed after
  (`AVAssetWriterInput.h:349-352`). Copy the source's `naturalTimeScale` (measured `15360` for
  `test-video.mp4`, M#5) so PTS round-trip without rescaling error.
- `transform` cannot be set after writing starts (`AVAssetWriterInput.h:316`).

## 2.4 Feeding the writer

Legacy: `requestMediaDataWhenReady(on:using:)` — **soft-deprecated for Swift** in iOS 26,
`API_DEPRECATED("Use the input receiver's async append(...) method on its own task instead", …)`
(`AVFH/AVAssetWriterInput.h:216-218`). Same for `append(_:)` (`:160`) and `isReadyForMoreMediaData`
consumers (`:176`).

iOS 26 replacement (`AVFSI:516-531`, `AVFSI:1084-1107`):

```swift
@available(iOS 26.0, macOS 26.0, *)
func encodeFrames(writer: AVAssetWriter, input: AVAssetWriterInput,
                  attrs: CVPixelBufferCreationAttributes) async throws {
    let receiver = writer.inputPixelBufferReceiver(for: input, pixelBufferAttributes: attrs)  // AVFSI:1105
    let pool = receiver.pixelBufferPool                                                       // AVFSI:1091
    // … render into a buffer from `pool`, then:
    // try await receiver.append(readOnlyBuffer, with: pts)     // AVFSI:1096 — applies backpressure
    receiver.finish()                                                                          // AVFSI:1098
}

@available(iOS 26.0, macOS 26.0, *)
func copySamples(writer: AVAssetWriter, input: AVAssetWriterInput,
                 provider: AVAssetReaderOutput.Provider<CMReadySampleBuffer<CMSampleBuffer.DynamicContent>>
) async throws {
    let receiver = writer.inputReceiver(for: input)     // AVFSI:529
    while let sb = try await provider.next() {
        try await receiver.append(sb)                   // AVFSI:519 — suspends instead of spinning
    }
    receiver.finish()
}
```

The `async append` **is** the backpressure — it suspends while `isReadyForMoreMediaData` is false.
That removes the `while !input.isReadyForMoreMediaData { usleep() }` spin and the whole
`requestMediaDataWhenReady` callback-pyramid, and it is why the loop is Swift-6-clean:
`AVAssetWriterInput` and `AVAssetReaderTrackOutput` are `NS_SWIFT_NONSENDABLE`
(`AVFH/AVAssetWriterInput.h:37`), so the callback form warns under strict concurrency (observed while
building M#5).

## 2.5 Output larger than 4 GiB — **measured, it just works**

`AVAssetWriter` writing `.mp4` handles the 32-bit `stco` / 4 GiB `mdat` limits automatically.

**Measured (M#7)**: appended 369 024 passthrough H.264 samples to one MP4 track.

| Fact | Value |
|---|---:|
| Output size | **4 606 381 723 B = 4.290 GiB** |
| `writer.status` after `finishWriting()` | `.completed` (2), `error == nil` |
| `mdat` box | `size == 1` extended-size header, `largesize = 4 601 984 922` |
| Chunk-offset box | **`co64` × 1, `stco` × 0** |
| `edts` (edit list) | present |
| Read-back | duration 12 300.87 s, **369 029** samples, reader status `.completed` |

Contract: no client-side action is needed for >4 GiB MP4. Do **not** enable
`movieFragmentInterval` "to be safe" (`AVFH/AVAssetWriter.h:423`) — fragmented MP4 is the container
Android measured as *worse* (`docs/long-film-followups.md`: a fragmented MP4 without `sidx` throws
`ExportException: Asset loader error` on any clipped segment while the unsegmented route succeeds).

Related knobs:

| Property | Header | Use |
|---|---|---|
| `shouldOptimizeForNetworkUse` | `AVAssetWriter.h:202` | **Leave false.** It moves `moov` to the front, which means a second full pass over a 4 GiB file at `finishWriting()` |
| `directoryForTemporaryFiles` | `AVAssetWriter.h:216` | Point at the job scratch dir so the `moov` staging file lands on the same volume |
| `overallDurationHint` | `AVAssetWriter.h:479` | Ignored unless fragments are on |
| `finishWriting()` | `AVAssetWriter.h:384` | Blocking form must not run on the main thread; use the async form |

---

# 3. PASSTHROUGH — bit-identical video track copy

This replaces `Remux.mux` / `Remux.concat` (`RX:108`, `RX:184`), whose whole contract is
"ZERO re-encode — CSD rides in the extractor's track format so copied samples stay bit-identical"
(`RX:93-96`).

## 3.1 The mechanism

```swift
func passthroughPair(track: AVAssetTrack, reader: AVAssetReader, writer: AVAssetWriter)
async throws -> (AVAssetReaderTrackOutput, AVAssetWriterInput) {
    // READ SIDE: nil settings = original stored format, no decode.  AVFH/AVAssetReaderOutput.h:223
    let out = AVAssetReaderTrackOutput(track: track, outputSettings: nil)
    out.alwaysCopiesSampleData = false
    reader.add(out)

    // WRITE SIDE: nil settings = pass through.  AVFH/AVAssetWriterInput.h:130
    // The format hint is MANDATORY for MP4 — AVFH/AVAssetWriterInput.h:85.
    guard let hint = try await track.load(.formatDescriptions).first else {
        throw NaqiError.noFormatDescription
    }
    let input = AVAssetWriterInput(mediaType: track.mediaType,
                                   outputSettings: nil,
                                   sourceFormatHint: hint)   // AVFH/AVAssetWriterInput.h:121
    input.expectsMediaDataInRealTime = false
    if track.mediaType == .video {
        input.transform = try await track.load(.preferredTransform)
        input.mediaTimeScale = try await track.load(.naturalTimeScale)
    }
    writer.add(input)
    return (out, input)
}
```

## 3.2 Is the format hint required? **Yes, and it throws, not returns nil**

Header (`AVFH/AVAssetWriterInput.h:50`, repeated at `:85`):

> Passing nil for output settings instructs the input to pass through appended samples… However, if
> not writing to a QuickTime Movie file … AVAssetWriter only supports passing through a **restricted
> set of media types and subtypes**. In order to pass through media data to files other than
> `AVFileTypeQuickTimeMovie`, **a non-NULL format hint must be provided**.

**Measured (M#6)** with `fileType: .mp4` and `AVAssetWriterInput(mediaType:.video, outputSettings:nil)`
(no hint):

```
canAdd(input) == false
addInput: raises NSInvalidArgumentException:
  "In order to perform passthrough to file type public.mpeg-4,
   please provide a format hint in the AVAssetWriterInput initializer"
```

So: **always** call `canAdd(_:)` before `add(_:)`, or an unusual source kills the job with an
uncatchable ObjC exception rather than an `NSError`. `AVFileTypeQuickTimeMovie` (`.mov`) is the escape
hatch for codecs MP4 will not carry — the Apple analogue of Android's
`MUXABLE_AUDIO = {audio/mp4a-latm, audio/3gpp, audio/amr-wb}` gate (`RX:52`) and its
`ConcatAudio.COPY / TRANSCODE / NONE` decision (`RX:55-64`, `RX:79-90`).

## 3.3 Bit-identity — **measured**

M#5: copy `test-video.mp4` → MP4 with both tracks in passthrough, then SHA-256 the concatenated
`CMBlockBuffer` payloads of every sample read back with `outputSettings: nil`.

| Track | source | destination | identical |
|---|---|---|---|
| Video `avc1` | `sha256 492f70f3a799e36d…`, **384** samples, **4 788 746 B** | `sha256 492f70f3a799e36d…`, **384** samples, **4 788 746 B** | **yes** |
| Audio `aac ` | `sha256 930b1207356cb652…`, 383 buffers, **207 483 B** | `sha256 930b1207356cb652…`, **23** buffers, **207 483 B** | payload **yes**, buffer grouping **no** |

The audio row is the gotcha: the elementary stream is byte-identical but AVFoundation **re-groups AAC
access units into different `CMSampleBuffer`s on read-back** (383 → 23). Any verification must hash
the concatenated payload, never compare `CMSampleBufferGetNumSamples` or buffer counts.

## 3.4 Gotchas, each measured or header-cited

1. **Edit lists survive and they are not zero.** Measured on `test-video.mp4` (M#5):

   | Track | source segment `src.start + src.duration → tgt.start + tgt.duration` | copy |
   |---|---|---|
   | video | `0.06667 + 12.800 → 0.0 + 12.800` | `0.06667 + 12.800 → 0.0 + 12.800` |
   | audio | `0.02133 + 12.817 → 0.0 + 12.817` | `0.02133 + 12.8167 → 0.0 + 12.8167` |

   The video edit list starts **2 frames** (0.0667 s at 30 fps) into the media, and the audio edit list
   starts **1024 samples** at 48 kHz (0.021333 s) in — the AAC encoder delay. Both survived the copy.
   The audio duration rounded by 0.3 ms. **Never assume `mediaTime == 0`**: sample PTS from the reader
   are media-time, and the first video sample's PTS is `0.06667`, not `0`.

2. **Timescale.** Set `input.mediaTimeScale` from the source (`15360` here). If you leave it at the
   writer default the muxer rescales every PTS and you get sub-frame jitter across a concat — the
   Apple form of the drift `RX:174-181` warns about.

3. **PTS/DTS.** Append in the order the reader hands them (decode order). PTS is non-monotonic on
   B-frame streams (measured `0.0667, 0.200, 0.133, 0.100, 0.167`, M#5). `AVAssetWriterInput` derives
   sample durations from **DTS** deltas (`AVFH/AVAssetWriterInput.h:229`), so it is DTS, not PTS, that
   must be non-decreasing. `CMSampleBufferCreateCopyWithNewTiming` is the only supported way to shift
   both for a concat; shift PTS **and** DTS by the same `CMTime`, exactly as `RX:317-327` does with a
   single `offsetUs`.

4. **Trailing partial GOPs.** `AVAssetReader.timeRange` with `outputSettings: nil` honours the range
   *end* but rewinds the *start* to the preceding sync sample (M#8, §1.7). A passthrough segment
   therefore contains a **leading** partial GOP, never a trailing one. Two legal fixes: snap the
   boundary to a sync sample (Android's choice), or read from the sync sample and drop samples with
   `pts < boundary` — but dropping breaks decodability if the dropped samples are references, so
   **snap**.

5. **Concat needs one format per track.** `AVAssetWriterInput` takes one `sourceFormatHint`; there is
   no way to add a second `stsd` entry. This is the same constraint `RX:282-291` enforces by comparing
   `{MIME, width, height, csd-0, csd-1}` and throwing on mismatch. On Apple compare
   `CMFormatDescriptionEqual` (or the `avcC`/`hvcC` extension blob) across segments before starting,
   and fail loudly — `RP:105-110` documents what a mixed concat produces: "a file whose second sample
   entry is silently wrong".

6. **`.mov` vs `.mp4`.** Only `AVFileTypeQuickTimeMovie` allows unrestricted passthrough and
   non-self-contained tracks (`AVFH/AVAssetWriterInput.h:36`, `:50`). If a job's source carries a codec
   MP4 rejects, writing `.mov` is a smaller change than transcoding.

---

# 4. HDR → SDR tone mapping

## 4.1 What Android does

`Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL`, applied **only when transcoding**
(`RP:141`); on the passthrough path media3 is deliberately left at `HDR_MODE_KEEP_HDR` because
"it is the only mode that does not force a transcode, so an untouched HDR source now stays HDR
instead of being tone-mapped into a copy of itself" (`RP:138-141`). Known defect carried forward:
under `useHdr` the composite is linear so a solid fill lands darker than the swatch (`CE:60-66`).

**Port that gate exactly**: if the EDL is empty, do not tone-map — passthrough and keep HDR.

## 4.2 Detecting HDR

```swift
import CoreMedia

enum SourceDynamicRange { case sdr, hlg, pq }

func dynamicRange(of fd: CMFormatDescription) -> SourceDynamicRange {
    let tf = CMFormatDescriptionGetExtension(
        fd, extensionKey: kCMFormatDescriptionExtension_TransferFunction) as? String   // CMH/CMFormatDescription.h:760
    switch tf {
    case kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ as String: return .pq   // :772
    case kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG as String:   return .hlg  // :774
    default: return .sdr
    }
}
```

Measured on `test-video.mp4`: `transfer = ITU_R_709_2`, subtype `avc1` → `.sdr` (M#4).

## 4.3 The recommended iOS 26 path: `CISystemToneMap`

New in iOS 26 / macOS 26: `+ (CIFilter<CISystemToneMap>*) systemToneMapFilter NS_AVAILABLE(16_0, 19_0)`
(`CIH/CIFilterBuiltins.h:2553`; the `16_0, 19_0` pair is the pre-rename macOS 16 / iOS 19 numbering,
i.e. macOS 26 / iOS 26). Protocol at `CIH/CIFilterBuiltins.h:708-716`:

> Apply a global tone curve to an image that reduces colors of the input image to a desired dynamic
> range **consistent with other frameworks**.

```swift
import CoreImage
import CoreImage.CIFilterBuiltins

@available(iOS 26.0, macOS 26.0, *)
func toneMapToSDR(_ src: CIImage, sourceHeadroom: Float) -> CIImage {
    let f = CIFilter.systemToneMap()
    // contentHeadroom drives both tone-map filters — CIH/CIImage.h:534.
    // 0.0 = unknown, 1.0 = SDR, >1.0 = HDR.  settingContentHeadroom is iOS 26 (CIH/CIImage.h:542).
    f.inputImage = sourceHeadroom > 0 ? src.settingContentHeadroom(sourceHeadroom) : src
    f.displayHeadroom = 1.0                       // 1.0 == SDR target
    f.preferredDynamicRange = .standard           // CIDynamicRangeOption
    return f.outputImage ?? src
}
```

iOS 18 floor (`CIFilter.toneMapHeadroom()`, `CIH/CIFilterBuiltins.h:2556`, protocol at `:752-760`):

```swift
func toneMapToSDR18(_ src: CIImage, sourceHeadroom: Float) -> CIImage {
    let f = CIFilter.toneMapHeadroom()
    f.inputImage = src
    f.sourceHeadroom = sourceHeadroom > 0 ? sourceHeadroom : src.contentHeadroom  // CIH/CIImage.h:614
    f.targetHeadroom = 1.0
    return f.outputImage ?? src
}
```

Third option, cheapest to wire but least controllable — ask Core Image to tone-map at image-creation
time: `CIImage(cvPixelBuffer:options:[.toneMapHDRtoSDR: true])`
(`kCIImageToneMapHDRtoSDR`, `CIH/CIImage.h:125`; only has an effect when the image's `CGColorSpace` is
HDR). Use it only if you are not already running a filter chain.

## 4.4 Tagging the output SDR

Two places, and you want both:

```swift
// 1. Writer settings — forces conversion + tagging (AVFH/AVVideoSettings.h:144).
settings[AVVideoColorPropertiesKey] = NaqiVideoSettings.bt709

// 2. The pixel buffer itself, so CIContext.render and the encoder agree.
func tagSDR(_ pb: CVPixelBuffer) {
    CVBufferSetAttachment(pb, kCVImageBufferColorPrimariesKey,
                          kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
    CVBufferSetAttachment(pb, kCVImageBufferTransferFunctionKey,
                          kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
    CVBufferSetAttachment(pb, kCVImageBufferYCbCrMatrixKey,
                          kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
}
```

`kCMFormatDescriptionExtension_*` and `kCVImageBuffer*Key` are the **same constants** — the CoreMedia
names are `#define`s onto the CoreVideo ones (`CMH/CMFormatDescription.h:747`, `:764`, `:788`).

## 4.5 If you use `AVVideoComposition` instead (playback preview, or `AVAssetExportSession`)

`AVMutableVideoComposition` is **deprecated in iOS 26**:
`API_DEPRECATED("Use AVVideoComposition.Configuration instead", …, ios(4.0, 26.0))`
(`AVFH/AVVideoComposition.h:263`). Same for `AVVideoCompositionInstruction` (`:558`) and
`AVVideoCompositionLayerInstruction` (`:654`). `AVAssetExportSession.exportAsynchronously` is likewise
deprecated for `export(to:as:)` (`AVFH/AVAssetExportSession.h:254`).

iOS 26 form (typechecked, `AVFSI:838-905`):

```swift
@available(iOS 26.0, macOS 26.0, *)
func sdrComposition(asset: AVAsset, ctx: CIContext) async throws -> AVVideoComposition {
    // AVFSI:840 — async CI applier, replaces videoCompositionWithAsset:applyingCIFiltersWithHandler:
    let filtered = try await AVVideoComposition(applyingFiltersTo: asset) { params in
        let f = CIFilter.systemToneMap()
        f.inputImage = params.sourceImage
        f.displayHeadroom = 1.0
        f.preferredDynamicRange = .standard
        return AVCIImageFilteringResult(resultImage: f.outputImage ?? params.sourceImage,
                                        ciContext: ctx)                        // AVFSI:827-831
    }
    var cfg = try await AVVideoComposition.Configuration(for: asset)            // AVFSI:901
    cfg.instructions           = filtered.instructions
    cfg.frameDuration          = filtered.frameDuration
    cfg.renderSize             = filtered.renderSize
    cfg.colorPrimaries         = AVVideoColorPrimaries_ITU_R_709_2              // AVFSI:847
    cfg.colorTransferFunction  = AVVideoTransferFunction_ITU_R_709_2            // AVFSI:851
    cfg.colorYCbCrMatrix       = AVVideoYCbCrMatrix_ITU_R_709_2                 // AVFSI:855
    cfg.perFrameHDRDisplayMetadataPolicy = .propagate                           // AVFSI:880
    return AVVideoComposition(configuration: cfg)                               // AVFSI:905
}
```

Setting the colour triple on the composition is what makes the CI path correct:
*"Setting these properties will cause source frames to be converted into the specified color space and
tagged as such. The source frames provided as CIImages will have the appropriate CGColorSpace applied."*
(`AVFH/AVVideoComposition.h:140-142`).

Also set `perFrameHDRDisplayMetadataPolicy`. Default is `.propagate`
(`AVFH/AVVideoComposition.h:165-167`), which forwards **HDR mastering metadata onto an SDR frame** —
harmless in most players, wrong in some. For a deliberate SDR output there is no `.none`, so strip
`kCVImageBufferMasteringDisplayColorVolumeKey` / `…ContentLightLevelInfoKey` from the buffer yourself
if a QA player complains.

**For the Naqi render pass, do not use `AVVideoComposition` at all.** The pass is
reader → CIContext → writer (§7), which gives per-frame EDL control that a composition's instruction
list cannot express, and skips a whole compositor. `AVVideoComposition` is for the in-app preview.

---

# 5. Rotation — `preferredTransform` and the four-case rect map

This was the Android landmine: media3 1.10 hands effects **either** pre-rotated upright frames **or**
stored-orientation frames, decoder-dependent, and the shipped code detects which by comparing frame
dimensions to the probed stored dimensions (`CE:40-46`, `CE:123-136`), logging a warning it cannot
resolve for 180° (`CE:128`) or for square rotated sources (`CE:132`).

**On Apple that ambiguity does not exist.** `AVAssetReaderTrackOutput` always vends frames in **stored
orientation**; `preferredTransform` is metadata that is never applied on the read side. That kills
`CE:123-136` outright. Keep the rect mapping; delete the detection.

## 5.1 `preferredTransform` semantics

`AVAssetTrack.preferredTransform` — *"the transform specified in the track's storage container as the
preferred transformation of the visual media data for display purposes; its value is often but not
always CGAffineTransformIdentity"* (`AVFH/AVAssetTrack.h:120`). The ObjC property is
`AVF_DEPRECATED_FOR_SWIFT_ONLY("Use load(.preferredTransform) instead", …, ios(4.0, 16.0))`
(`AVFH/AVAssetTrack.h:121`) — always use the async `load(_:)` form.

It maps **stored pixel coordinates → display coordinates**, in a top-left-origin, y-down space. On
`test-video.mp4` it is identity and `naturalSize` is `(1080, 1920)` (M#0).

Recovering the rotation:

```swift
enum Rotation: Int, Sendable {
    case deg0 = 0, deg90 = 90, deg180 = 180, deg270 = 270

    /// atan2(b, a) is the rotation encoded in the 2×2 block. Mirroring (a*d - b*c < 0) is
    /// legal in the container and is NOT handled here — see §5.5.
    static func from(_ t: CGAffineTransform) -> Rotation {
        let degrees = atan2(t.b, t.a) * 180 / .pi
        let n = Int((degrees < 0 ? degrees + 360 : degrees).rounded()) % 360
        return Rotation(rawValue: n) ?? .deg0     // FS:131 — a bizarre value degrades to 0, never throws
    }
}

/// Upright display size for a stored WxH under `rotation`. Direct port of FS:457-458.
func uprightSize(_ stored: CGSize, _ r: Rotation) -> CGSize {
    (r == .deg90 || r == .deg270) ? CGSize(width: stored.height, height: stored.width) : stored
}
```

## 5.2 The transform for the four rotations

`naturalSize = (W, H)` stored. Canonical `preferredTransform` values as written by iOS capture:

| Rotation | `preferredTransform` `(a, b, c, d, tx, ty)` | Upright display size |
|---|---|---|
| 0° | `( 1,  0,  0,  1,  0,  0)` | `W × H` |
| 90° CW | `( 0,  1, -1,  0,  H,  0)` | `H × W` |
| 180° | `(-1,  0,  0, -1,  W,  H)` | `W × H` |
| 270° CW | `( 0, -1,  1,  0,  0,  W)` | `H × W` |

Write the source transform straight back onto `AVAssetWriterInput.transform`
(`AVFH/AVAssetWriterInput.h:317`) — never bake rotation into pixels. That is what Android does with
`MediaMuxer.setOrientationHint` (`RX:136`, `RX:352-368`) and it is why re-encoding a portrait video
does not rotate it.

## 5.3 Coordinate spaces in play

| Space | Origin | Units | Who lives here |
|---|---|---|---|
| **Upright normalized** | top-left, y-down | `[0,1]` | Every `NRect` in the EDL (`CT:3-9`). The one space pass 1 and pass 2 share |
| **Stored normalized** | top-left, y-down | `[0,1]` | Shader/CIFilter region uniforms — what `NRect.toStoredSpace` produces (`CT:21-26`) |
| **Vision normalized** | **bottom-left**, y-up | `[0,1]` | `NormalizedRect.origin` is documented as *"The lower left-hand corner"* (`VNSI:2580`) |
| **CoreVideo pixel** | top-left, y-down | px | `CVPixelBuffer`, `CGImage` |
| **Core Image** | **bottom-left**, y-up | px | `CIImage.extent` |

## 5.4 Upright → stored, all four cases

Exact port of `NRect.toStoredSpace` (`CT:21-26`). Both spaces are normalized `[0,1]` with a
**top-left** origin, so this is pure algebra, no image size needed.

| Rotation (stored must be rotated this many degrees CW to display) | left' | top' | right' | bottom' | width'×height' |
|---|---|---|---|---|---|
| **0** | `left` | `top` | `right` | `bottom` | `w × h` |
| **90** | `top` | `1 − right` | `bottom` | `1 − left` | `h × w` |
| **180** | `1 − right` | `1 − bottom` | `1 − left` | `1 − top` | `w × h` |
| **270** | `1 − bottom` | `left` | `1 − top` | `right` | `h × w` |

```swift
/// `r` is normalized [0,1] in UPRIGHT display space, TOP-LEFT origin.
/// Returns normalized [0,1] in STORED buffer space, TOP-LEFT origin.
/// Bit-for-bit port of Contracts.kt:21-26.
func toStoredSpace(_ r: CGRect, rotation: Rotation) -> CGRect {
    let l = r.minX, t = r.minY, rt = r.maxX, b = r.maxY
    switch rotation {
    case .deg0:   return CGRect(x: l,      y: t,      width: rt - l, height: b - t)
    case .deg90:  return CGRect(x: t,      y: 1 - rt, width: b - t,  height: rt - l)
    case .deg180: return CGRect(x: 1 - rt, y: 1 - b,  width: rt - l, height: b - t)
    case .deg270: return CGRect(x: 1 - b,  y: l,      width: b - t,  height: rt - l)
    }
}

/// Stored-normalized (top-left) -> Core Image pixel rect (bottom-left) on a WxH stored buffer.
/// This is CE:231-236's `1 - bottom` / `1 - top` flip, scaled to pixels.
func toCIRect(_ stored: CGRect, storedSize: CGSize) -> CGRect {
    CGRect(x: stored.minX * storedSize.width,
           y: (1 - stored.maxY) * storedSize.height,
           width:  stored.width  * storedSize.width,
           height: stored.height * storedSize.height)
}
```

### 5.4.1 Round-trip self-test (put this in the test target)

```swift
func inverseOfStoredSpace(_ r: CGRect, rotation: Rotation) -> CGRect {
    switch rotation {
    case .deg0:   return toStoredSpace(r, rotation: .deg0)
    case .deg90:  return toStoredSpace(r, rotation: .deg270)   // 90 and 270 are mutual inverses
    case .deg180: return toStoredSpace(r, rotation: .deg180)   // 180 is an involution
    case .deg270: return toStoredSpace(r, rotation: .deg90)
    }
}
// assert: inverseOfStoredSpace(toStoredSpace(r, rotation: k), rotation: k) ≈ r, for all four k
```

## 5.5 Mirrored transforms

`preferredTransform` may encode a flip (front-camera captures, some editors): `a*d − b*c < 0`.
`Rotation.from` above **silently misreads** those — `atan2(b, a)` cannot distinguish a rotation from a
rotation-plus-flip. Android had the identical hole (`FS:131` clamps to a multiple of 90 and calls it a
day). Detect it and refuse to censor rather than censor the wrong rectangle:

```swift
func isMirrored(_ t: CGAffineTransform) -> Bool { (t.a * t.d - t.b * t.c) < 0 }
```

At minimum log it. This is a **new** defect class the Apple port can close cheaply and Android never did.

---

# 6. Vision — face detection + tracking on iOS 26

## 6.1 Two APIs, both current

| | Classic (`VN*`) | Swift (iOS 18+) |
|---|---|---|
| Detect | `VNDetectFaceRectanglesRequest` | `DetectFaceRectanglesRequest` (`VNSI:1549`) |
| Track | `VNTrackObjectRequest` + `VNSequenceRequestHandler` | `TrackObjectRequest` (`VNSI:828`) + `ImageRequestHandler` |
| Deprecated in iOS 26? | **No.** No `API_DEPRECATED` on `VNTrackObjectRequest.h`, `VNSequenceRequestHandler.h`, or `VNDetectFaceRectanglesRequest` (only `…Revision1` is, `VNDetectFaceRectanglesRequest.h:31`) | n/a |
| `trackingLevel` | exists (`VNTrackingRequest.h:58`) but *"has no effect on general purpose object tracker (VNTrackObjectRequest) **revision 2**"* (`VNTrackingRequest.h:56`) | **absent** |
| Concurrency | callback/`results` array, `@unchecked Sendable` glue needed | `async throws`, `Sendable` observations |

`TrackObjectRequest.supportedRevisions` contains **only `.revision2`** (`VNSI:830-831`), so
`trackingLevel` is dead weight in both APIs. **Use the Swift API.**

## 6.2 The exact surface (from `VNSI`, all verified by typecheck against iOS 26 in Swift 6 mode)

```swift
// VNSI:1549-1571
public struct DetectFaceRectanglesRequest: ImageProcessingRequest {
    public typealias Result = [FaceObservation]
    public enum Revision { case revision3 }           // only value
    public init(_ revision: Revision? = nil)          // `revision` is a LET; set it here, not after
    public var regionOfInterest: NormalizedRect
}

// VNSI:2976-3001
public struct FaceObservation: VisionObservation, BoundingBoxProviding {
    public var boundingBox: NormalizedRect            // bottom-left origin
    public let roll:  Measurement<UnitAngle>          // NON-optional
    public let yaw:   Measurement<UnitAngle>
    public let pitch: Measurement<UnitAngle>
    public var landmarks: FaceObservation.Landmarks2D?
    public var captureQuality: FaceObservation.CaptureQuality?   // .score: Float
    public let uuid: UUID
    public let confidence: Float
    public let timeRange: CMTimeRange?
}

// VNSI:828-858
public final class TrackObjectRequest: ImageProcessingRequest, StatefulRequest {
    public typealias Result = DetectedObjectObservation?          // OPTIONAL
    public enum Revision { case revision2 }
    public init(detectedObject: any BoundingBoxProviding & VisionObservation,
                _ revision: Revision? = nil,
                frameAnalysisSpacing: CMTime? = nil)
    public let inputObservation: any BoundingBoxProviding & VisionObservation
    public var regionOfInterest: NormalizedRect
    public let frameAnalysisSpacing: CMTime
}

// VNSI:167-179
public final class ImageRequestHandler: @unchecked Sendable {
    convenience init(_ pixelBuffer: CVPixelBuffer, depthData: AVDepthData? = nil,
                     orientation: CGImagePropertyOrientation? = nil)
    convenience init(_ sampleBuffer: CMSampleBuffer, depthData: AVDepthData? = nil,
                     orientation: CGImagePropertyOrientation? = nil)
    func perform<T: VisionRequest>(_ request: T) async throws -> T.Result
    func perform<each T: VisionRequest>(_ request: repeat each T) async throws -> (repeat (each T).Result)
    func performAll(_ requests: some Collection<any VisionRequest>) -> some AsyncSequence<VisionResult, Never>
}
```

Two API traps that only show up at compile time (both hit while writing this spec):

1. `DetectFaceRectanglesRequest.revision` is a **`let`**. `request.revision = .revision3` does not
   compile; pass it to `init`.
2. `TrackObjectRequest.Result` is **`DetectedObjectObservation?`**, not `DetectedObjectObservation`.
   `nil` means the tracker lost the object on that frame — that is your `misses += 1` signal.

## 6.3 Does Vision give stable track IDs? — **measured answer**

| Claim | Verdict |
|---|---|
| `DetectFaceRectanglesRequest` gives stable IDs across frames | **No.** Each detection is a fresh `FaceObservation` with a fresh `uuid`. There is no detection-level tracker. This is the one thing ML Kit did give Android (`FT:105`, `FaceDetectorOptions.enableTracking()`, `FT:206`) and Vision does not |
| `TrackObjectRequest` preserves the seeded observation's `uuid` across frames | **Yes.** Measured (M#4b): seeded from a `FaceObservation` with `uuid = 7674B771-D3D4-4F08-AD12-1FDC578F2465`, stepped the same request instance over 128 frames at 10 fps of `test-video.mp4`, and every returned `DetectedObjectObservation.uuid` was that same UUID. No change was ever observed |
| Therefore identity is free | **No.** The UUID is stable **per request instance**. A `TrackObjectRequest` you construct is one track. Matching a *new* detection to an *existing* track is entirely yours |

So the shape is: **one `TrackObjectRequest` instance == one track**, and IoU matching bridges the
detector to the trackers. `TrackObjectRequest` is a `final class` conforming to `StatefulRequest`
(`VNSI:828`) — the state lives in the instance, so you re-perform *the same object* on each new
`ImageRequestHandler`. There is no `VNSequenceRequestHandler` equivalent and none is needed.

`StatefulRequest` requires `frameAnalysisSpacing: CMTime` (*"the reciprocal of the maximum rate to
process buffers"*) and `minimumLatencyFrameCount: Int`. For a 10 fps offline pass pass
`frameAnalysisSpacing: CMTime(value: 1, timescale: 10)` so Vision's internal rate limiter agrees with
the sampling grid.

## 6.4 Coordinates — Vision is bottom-left, everything else is top-left

`NormalizedRect.origin` is *"The lower left-hand corner of the rectangle"* (`VNSI:2580`). Conversions:

```swift
// Vision normalized (bottom-left) -> upright normalized, TOP-LEFT origin. This is EDL space (CT:3-9).
func toEDLSpace(_ n: NormalizedRect) -> CGRect {
    let r = n.cgRect
    return CGRect(x: r.minX, y: 1 - r.maxY, width: r.width, height: r.height)
}
// Equivalent, using Vision's own helper:
func toEDLSpaceAlt(_ n: NormalizedRect) -> CGRect { n.verticallyFlipped().cgRect }   // VNSI:2591

// Vision normalized -> CoreVideo pixel rect (top-left origin), if you need pixels:
func toPixels(_ n: NormalizedRect, size: CGSize) -> CGRect {
    n.toImageCoordinates(size, origin: .upperLeft)          // VNSI:2589, CoordinateOrigin VNSI:3116-3118
}
```

`toImageCoordinates(_:origin:)` **defaults to `.lowerLeft`** (`VNSI:2589`). Passing `.upperLeft`
explicitly, every time, is the whole defence.

## 6.5 Orientation — pass it to Vision, do not rotate pixels

Android hands ML Kit an **unrotated** buffer plus a `rotationDegrees` and gets boxes back in **upright**
space (`FS:46-50`, `FS:381-387`). Vision works the same way through `CGImagePropertyOrientation`:

| Track rotation (stored → display, CW) | `CGImagePropertyOrientation` to pass | Vision reports boxes in |
|---|---|---|
| 0° | `.up` | upright = stored |
| 90° | `.right` | upright (axes swapped vs stored) |
| 180° | `.down` | upright |
| 270° | `.left` | upright |

```swift
extension CGImagePropertyOrientation {
    init(rotation: Rotation) {
        switch rotation {
        case .deg0:   self = .up
        case .deg90:  self = .right
        case .deg180: self = .down
        case .deg270: self = .left
        }
    }
}
```

With this, the EDL stays normalized against `uprightSize(...)` exactly as `FS:454-458` requires, and
`toStoredSpace` (§5.4) is still the only mapping the render pass needs. **Verify this table against a
real rotated asset before shipping** — the Android QA manifest already calls for a
"rotated/portrait clip" (`docs/m0-spikes.md:58`) and it has never been run; this is the highest-value
single test in the port.

## 6.6 Complete pattern: detect at 10 fps, keep identity across a whole clip

Reproduces `FaceTracker` (`FT`) with Vision doing what ML Kit's tracker did. Constants carried over
verbatim; see §10.2.

```swift
import Vision
import AVFoundation

@available(iOS 18.0, macOS 15.0, *)
struct FaceTrackEDL: Sendable {
    let startMs: Int64
    let endMs: Int64
    let keyframes: [(Int64, CGRect)]     // upright normalized, top-left, already 25 %-padded
}

@available(iOS 18.0, macOS 15.0, *)
final class VisionFaceTracker {

    // ---- constants ported verbatim ----
    static let evictAfter  = CMTime(value: 2, timescale: 1)   // FT:264  EVICT_AFTER_MS = 2000
    static let spanPadMs:  Int64 = 50                         // FT:267  SPAN_PAD_MS
    static let keyframePad: CGFloat = 0.25                    // FT:270  KEYFRAME_PAD (=> 1.5x per axis)
    static let minFacePx:   CGFloat = 80                      // FT:253  MIN_FACE_PX (measured, §3.2 pt4)
    static let voteCap = 5                                    // FT:231  VOTE_CAP
    // ---- new, Apple-only ----
    static let iouGate: CGFloat = 0.30      // detection <-> track association
    static let trackConfidenceFloor: Float = 0.30
    static let maxMisses = 3                // drop a tracker Vision keeps losing; detection re-seeds it

    private struct Track {
        let id: Int
        var request: TrackObjectRequest
        var lastSeen: CMTime
        var lastBox: CGRect                 // upright normalized, TOP-LEFT
        var samples: [(CMTime, CGRect)]
        var misses: Int
        // gender vote tallies — FT / Contracts.kt:52-63
        var femaleVotes = 0, maleVotes = 0, classifiedPx: CGFloat = 0, votesTried = 0
    }

    private var live: [Int: Track] = [:]
    private var nextID = 1
    private(set) var emitted: [FaceTrackEDL] = []
    private(set) var trackCount = 0, faceCount = 0          // FT:75-79 soak counters

    private let orientation: CGImagePropertyOrientation
    private let uprightSize: CGSize
    init(rotation: Rotation, storedSize: CGSize) {
        self.orientation = CGImagePropertyOrientation(rotation: rotation)
        self.uprightSize = uprightSizeFor(storedSize, rotation)
    }

    /// Call once per sampled frame, in ascending PTS order, one at a time.
    func consume(_ pixelBuffer: CVPixelBuffer, pts: CMTime) async throws {

        // ---- 1. advance every live tracker on this frame -------------------
        for (id, var t) in live {
            let handler = ImageRequestHandler(pixelBuffer, orientation: orientation)
            if let o = try? await handler.perform(t.request),          // Result is OPTIONAL
               o.confidence >= Self.trackConfidenceFloor {
                t.lastBox = toEDLSpace(o.boundingBox)
                t.misses = 0
            } else {
                t.misses += 1
            }
            live[id] = t
        }

        // ---- 2. detect -----------------------------------------------------
        let detector = DetectFaceRectanglesRequest()
        let faces = try await detector.perform(on: pixelBuffer, orientation: orientation)
        faceCount += faces.count

        // ---- 3. associate: greedy IoU, one detection per track -------------
        var claimed = Set<Int>()
        for f in faces {
            let box = toEDLSpace(f.boundingBox)
            var best: (id: Int, score: CGFloat)?
            for (id, t) in live where !claimed.contains(id) {
                let s = iou(box, t.lastBox)
                if s >= Self.iouGate, best == nil || s > best!.score { best = (id, s) }
            }
            if let hit = best {
                claimed.insert(hit.id)
                live[hit.id]!.lastSeen = pts
                live[hit.id]!.lastBox = box
                live[hit.id]!.samples.append((pts, box))
                // Re-seed the tracker from the fresh detection: a detector box is always better
                // evidence than N frames of drift. Vision keeps no state you lose by doing this.
                live[hit.id]!.request = TrackObjectRequest(
                    detectedObject: f, nil, frameAnalysisSpacing: CMTime(value: 1, timescale: 10))
            } else {
                // New face. FT:125's "untracked detection becomes its own one-frame track"
                // has no analogue here: every detection gets a real track.
                let id = nextID; nextID += 1; trackCount += 1
                live[id] = Track(id: id,
                                 request: TrackObjectRequest(detectedObject: f, nil,
                                              frameAnalysisSpacing: CMTime(value: 1, timescale: 10)),
                                 lastSeen: pts, lastBox: box, samples: [(pts, box)], misses: 0)
                claimed.insert(id)
            }
        }

        // ---- 4. sweep: evict tracks that are over --------------------------  FT:163-174
        for (id, t) in live {
            let stale = (pts - t.lastSeen) >= Self.evictAfter      // FT:310 isStale, >= not >
            if stale || t.misses > Self.maxMisses {
                if stale { emit(t) }                               // a lost-but-recent track is NOT closed
                if stale { live[id] = nil } else { live[id]!.misses = 0 }
            }
        }
    }

    /// Emit whatever is still live, then return every EDL, sorted by start. FT:187-195.
    func finish() -> [FaceTrackEDL] {
        for t in live.values { emit(t) }
        live.removeAll()
        return emitted.sorted { $0.startMs < $1.startMs }
    }

    private func emit(_ t: Track) {
        guard !t.samples.isEmpty else { return }
        guard shouldCensor(female: t.femaleVotes, male: t.maleVotes) else { return }   // FT:302-307
        let start = max(0, ms(t.samples.first!.0) - Self.spanPadMs)                    // FT:285
        let end   = ms(t.samples.last!.0) + Self.spanPadMs                             // FT:286
        emitted.append(FaceTrackEDL(startMs: start, endMs: end,
                                    keyframes: t.samples.map { (ms($0.0), padRect($0.1)) }))
    }

    private func ms(_ t: CMTime) -> Int64 { Int64((t.seconds * 1000).rounded(.down)) }

    /// 25 % per side => 1.5x each dimension, clamped to [0,1]. FT:313-322.
    private func padRect(_ r: CGRect) -> CGRect {
        let dx = r.width * Self.keyframePad, dy = r.height * Self.keyframePad
        let l = min(max(r.minX - dx, 0), 1), t = min(max(r.minY - dy, 0), 1)
        let rt = min(max(r.maxX + dx, 0), 1), b = min(max(r.maxY + dy, 0), 1)
        return CGRect(x: l, y: t, width: rt - l, height: b - t)
    }

    private func iou(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let i = a.intersection(b)
        guard !i.isNull, !i.isEmpty else { return 0 }
        let ia = i.width * i.height
        return ia / (a.width * a.height + b.width * b.height - ia)
    }

    private func toEDLSpace(_ n: NormalizedRect) -> CGRect { n.verticallyFlipped().cgRect }
}
```

### 6.6.1 Behavioural deltas vs Android that a reviewer must sign off

| Android (`FT`) | Apple | Why |
|---|---|---|
| ML Kit assigns tracking ids; ids ML Kit could not assign become **synthetic negative ids**, one-frame tracks, never gender-classified (`FT:66`, `FT:125`, `FT:143`) | Every detection gets a real track; IoU decides continuation | Vision has no detector-level tracker, so there is no "untracked detection" category |
| Track dies purely on a 2 s source-time gap (`FT:264`) | Dies on the same 2 s gap; `misses` only resets the tracker, never closes the track | Keeps span coverage identical: a tracker losing lock must not shorten a censor span |
| A reused ML Kit id after eviction starts a fresh track (`FT:127-129`) | Same by construction | — |
| `PERFORMANCE_MODE_FAST` (`FT:205`) | `DetectFaceRectanglesRequest` has no speed mode; only `.revision3` | Cost must be re-measured, see §12 |
| Detector called once per frame | Detector once **plus one tracker step per live track** | New per-frame cost, linear in live tracks. Eviction (`FT:159-174`) is what bounds it — on the measured film "a handful" of live tracks vs 3 362 without eviction (`FT:41-45`) |

## 6.7 Landmarks and roll/yaw/pitch for FRONTAL crop selection

`roll`/`yaw`/`pitch` are **non-optional** `Measurement<UnitAngle>` on `FaceObservation` (`VNSI:2981-2983`)
— no `NSNumber?` unwrapping, unlike the classic `VNFaceObservation`.

```swift
extension FaceObservation {
    var yawDeg:   Double { yaw.converted(to: .degrees).value }
    var pitchDeg: Double { pitch.converted(to: .degrees).value }
    var rollDeg:  Double { roll.converted(to: .degrees).value }

    /// Frontality score in [0,1]; 1 = dead-on. Use to pick which crop of a track feeds the
    /// gender vote, replacing FT:145-147's "spend the 5 votes on the biggest crops" heuristic.
    var frontality: Double {
        let y = max(0, 1 - abs(yawDeg)   / 45)
        let p = max(0, 1 - abs(pitchDeg) / 30)
        let r = max(0, 1 - abs(rollDeg)  / 30)
        return y * p * r
    }
}
```

Crop-selection policy for the gender vote, keeping the Android budget:

1. Hard floor: crop max side ≥ **80 px** in upright pixels (`FT:253`, measured — the 40–80 px band was
   76.9 % correct against 95.9 %+ for every band above it, `FT:240-251`). Below the floor, **no vote**,
   and no vote already means censor (`FT:302-307`).
2. At most **5** classifications per track (`FT:231`).
3. Android spends them on the biggest crop seen so far (`FT:147`). **Replace that with
   `frontality × size`** — InsightFace `genderage.onnx` is trained on aligned frontal crops, and Vision
   hands you the pose for free where ML Kit did not. Keep the cap at 5 so the cost argument
   (`FT:228-231`: 3 362 tracks × 5 crops × ~1 ms ≈ 17 s on a 155-min film) is unchanged.
4. `landmarks` requires a second request:
   ```swift
   var lm = DetectFaceLandmarksRequest()
   lm.inputFaceObservations = faces                       // seed with the rectangles you already have
   let withLandmarks = try await lm.perform(on: pixelBuffer, orientation: orientation)
   ```
   `Landmarks2D` exposes `allPoints, faceContour, leftEye, rightEye, leftEyebrow, rightEyebrow, nose,
   noseCrest, medianLine, outerLips, innerLips, leftPupil, rightPupil` (`VNSI:3021-3070`).
   **Only run it on the ≤5 crops per track that will actually be classified** — it is a second network
   per frame otherwise.
5. `captureQuality` (`FaceObservation.CaptureQuality.score`, `VNSI:3004-3005`) is a cheaper
   alternative signal; needs `DetectFaceCaptureQualityRequest`. Not required for v1.

## 6.8 Crop geometry for `genderage.onnx` — unchanged from Android

`FS:693-733` (`cropToTensor`) defines the contract and it is **not** the EDL's padded rect:

- Square of side `max(boxW, boxH) × 1.5`, centred on the **raw, unpadded** detector box's centre
  (`FS:697-700`).
- Resized to **96×96**, NCHW RGB, **values 0…255, not scaled by 1/255** — insightface's Attribute
  preprocessing is `input_mean = 0.0, input_std = 1.0` (`FS:679-681`).
- Nearest-neighbour; out-of-frame samples clamp to the edge pixel (`FS:687-691`).
- BT.601 full-range integer YUV→RGB, coefficients `1436 / 352 / 731 / 1815`, `shr 10`, `coerceIn(0,255)`
  (`FS:557-559`) — QA-tuned, do not "improve".

On Apple, feed this from the same `CVPixelBuffer` (`vImage` or a Metal kernel); see `spec-analyze.md`.
The only thing §6 owes it is the **raw** `FaceObservation.boundingBox` in upright normalized space,
before `padRect`.

---

# 7. Core Image blur into an `AVAssetWriter`

## 7.1 What must be reproduced (Android `CE`)

| Parameter | Value | Citation |
|---|---:|---|
| Blur amount default | **60** (0…100) | Android `FilterOps:47` |
| sigma, full-res px | `max(0.1, blurAmount/100 × 40 × (min(W,H)/1080))` — keyed on the **short side** | `CE:140` |
| Downscale factor | smallest `d ∈ {1,2,4,8}` with `sigma/d ≤ 4`, else 8 | `CE:142` |
| Kernel radius | `min(10, ceil(2.5 × sigmaLow))`, floor 1 | `CE:146`, `MAX_RADIUS` `CE:27` |
| Kernel | normalized 1-D Gaussian, `exp(-i²/(2σ²))`, `sum = w0 + 2Σw_i` | `CE:290-300` |
| Passes | two separable, whole-frame, **geometry-blind** | `CE:192-193` |
| Max simultaneous regions | **8**, largest kept by area when it overflows | `CE:30`, `CE:182-187` |
| Feather | `max(regionSize × 0.15, 0.002)`, **outward only** | `CE:382` |
| Grayscale luma | BT.709 `(0.2126, 0.7152, 0.0722)` | `CE:400` |
| Output resolution | **always** the input resolution; no scaling effect exists | `CE:162` |
| Solid fill | wins outright over blur+grayscale | `CE:395-397` |

Whole-frame mode measured **0.20 % render delta over three runs** (89 411 / 89 259 / 89 437 ms,
`docs/plan-whole-frame-blur.md` §6.2) precisely because the blur passes never look at the geometry.
Keep that: blur the whole frame, composite with a mask.

## 7.2 The `CIContext`

```swift
import CoreImage
import Metal

func makeRenderContext() -> CIContext {
    guard let device = MTLCreateSystemDefaultDevice(),
          let queue = device.makeCommandQueue() else { fatalError("no Metal device") }
    return CIContext(mtlCommandQueue: queue, options: [        // CIH/CIContext.h:319-321
        // Linear working space: a Gaussian blur is only correct in linear light.
        // Extended range so HDR sources survive the working space before tone mapping.
        .workingColorSpace: CGColorSpace(name: CGColorSpace.extendedLinearSRGB)!,  // CIH/CIContext.h:105
        .workingFormat: CIFormat.RGBAh,                        // CIH/CIContext.h:122 — half float
        .outputColorSpace: CGColorSpace(name: CGColorSpace.itur_709)!,             // CIH/CIContext.h:87
        .cacheIntermediates: false,                            // CIH/CIContext.h:178 — long batch, no reuse
        .name: "naqi-render",                                  // CIH/CIContext.h:209 — shows in Instruments
    ])
}
```

Use `contextWithMTLCommandQueue:` and not `contextWithMTLDevice:`. The header is explicit
(`CIH/CIContext.h:426`): creating from a device makes CI create its own queue, and
*"To avoid this impact, it is recommended to create a context using [CIContext contextWithMTLCommandQueue:]."*

Create **one** context for the whole job. A `CIContext` compiles and caches kernels; per-frame
construction is the classic Core Image performance bug.

## 7.3 Downscale → blur → upscale

`CIGaussianBlur`'s cost grows with radius. The Android shader avoids that by rendering the blur into a
`W/d × H/d` scratch FBO (`CE:148-160`). The Core Image analogue:

```swift
/// sigmaPx is in FULL-RES pixels — CE:140. d is CE:142's {1,2,4,8} ladder.
func naqiBlur(_ src: CIImage, sigmaPx: CGFloat) -> CIImage {
    let d: CGFloat = [1, 2, 4, 8].first { sigmaPx / $0 <= 4 } ?? 8        // CE:142
    guard d > 1 else {
        let g = CIFilter.gaussianBlur(); g.inputImage = src.clampedToExtent(); g.radius = Float(sigmaPx)
        return (g.outputImage ?? src).cropped(to: src.extent)
    }
    // Lanczos down, blur small, affine up. clampedToExtent stops the edge darkening a plain
    // gaussianBlur produces (CI treats outside-extent as transparent black).
    let down = CIFilter.lanczosScaleTransform()
    down.inputImage = src; down.scale = Float(1 / d); down.aspectRatio = 1
    guard let small = down.outputImage else { return src }

    let g = CIFilter.gaussianBlur()
    g.inputImage = small.clampedToExtent()
    g.radius = Float(sigmaPx / d)
    guard let blurred = g.outputImage?.cropped(to: small.extent) else { return src }

    return blurred
        .transformed(by: CGAffineTransform(scaleX: d, y: d))
        .cropped(to: src.extent)
}
```

`CIGaussianBlur.radius` is a **sigma**, not a kernel half-width, so `sigmaPx` maps straight across;
do **not** apply `CE:146`'s `ceil(2.5σ)` radius — that is a GLSL loop bound, not a filter parameter.
`MAX_RADIUS = 10` (`CE:27`) exists only because the shader's `uWeights[11]` array is fixed-size; Core
Image has no such cap, so **the Apple blur can be strictly stronger than Android's at high
`blurAmount` on large frames**. Clamp to the Android behaviour if bit-comparable output matters, or
accept it and note it in QA.

## 7.4 Compositing regions

```swift
/// Feather 0.15 of the region's own size, floor 0.002, OUTWARD ONLY — CE:377-387.
/// "softening must never uncover a pixel the hard rect covered."
func regionMask(regions: [CGRect], extent: CGRect) -> CIImage {
    var mask = CIImage(color: .black).cropped(to: extent)
    for r in regions.prefix(8) {                                     // MAX_REGIONS = 8, CE:30
        let fx = max(r.width  * 0.15, 0.002) * extent.width
        let fy = max(r.height * 0.15, 0.002) * extent.height
        let inner = CGRect(x: r.minX * extent.width, y: r.minY * extent.height,
                           width: r.width * extent.width, height: r.height * extent.height)
        let outer = inner.insetBy(dx: -fx, dy: -fy)
        // radialGradient/smoothLinearGradient give the smoothstep ramp; a white rect inside
        // `inner` guarantees the hard rect stays fully covered.
        let hard = CIImage(color: .white).cropped(to: inner)
        let soft = CIImage(color: CIColor(red: 1, green: 1, blue: 1, alpha: 0.0)).cropped(to: outer)
        mask = hard.composited(over: soft.composited(over: mask))
    }
    return mask
}
```

When `regions.count > 8`, keep the 8 **largest by area** and log it — never silently
(`CE:182-187`). Region rects here are already in **stored** space via §5.4 and converted to CI's
bottom-left pixel space via `toCIRect`.

## 7.5 Rendering into the writer's pool, no CPU round trip

```swift
func render(_ image: CIImage, ctx: CIContext,
            adaptor: AVAssetWriterInputPixelBufferAdaptor,
            pts: CMTime, output: CGColorSpace) throws {
    // "For maximum efficiency, clients should create CVPixelBuffer objects for
    //  appendPixelBuffer:withPresentationTime: by using this pool" — AVFH/AVAssetWriterInput.h:603
    guard let pool = adaptor.pixelBufferPool else { throw NaqiError.noPool }   // nil before startWriting
    var out: CVPixelBuffer?
    guard CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &out) == kCVReturnSuccess,
          let dst = out else { throw NaqiError.poolExhausted }
    tagSDR(dst)                                              // §4.4, before render
    ctx.render(image, to: dst, bounds: image.extent, colorSpace: output)
    guard adaptor.append(dst, withPresentationTime: pts) else { throw NaqiError.appendFailed }
}
```

Pool attributes — the pool is created by the adaptor from `sourcePixelBufferAttributes`
(`AVFH/AVAssetWriterInput.h:565`, `:610`):

```swift
let adaptor = AVAssetWriterInputPixelBufferAdaptor(
    assetWriterInput: input,
    sourcePixelBufferAttributes: [
        // Match the encoder's native input so VideoToolbox does no conversion —
        // AVFH/AVAssetWriterInput.h:242 names exactly these two formats for H.264/HEVC.
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
        kCVPixelBufferWidthKey  as String: width,
        kCVPixelBufferHeightKey as String: height,
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
        kCVPixelBufferMetalCompatibilityKey  as String: true,
    ])
```

Rules:

1. `pixelBufferPool` is **nil until `AVAssetWriter.startWriting()` has been called**
   (`AVFH/AVAssetWriterInput.h:610`). Do not cache it before then.
2. Render **to a 4:2:0 buffer**, not BGRA. `CIContext.render(_:to:bounds:colorSpace:)` will write
   Y′CbCr directly; going through BGRA adds a full-frame colour conversion on every frame.
3. Never `CVPixelBufferLockBaseAddress` around the render — that is the CPU round trip you are
   avoiding.
4. Bound the pool with `kCVPixelBufferPoolMinimumBufferCountKey` (`CVH/CVPixelBufferPool.h:36`) and,
   if you go concurrent, `kCVPixelBufferPoolAllocationThresholdKey` (`CVH/CVPixelBufferPool.h:129`) so
   an exhausted pool returns `kCVReturnWouldExceedAllocationThreshold` instead of ballooning. Android's
   equivalent bound is `RING = 4` (`FS:68`) and `texturePoolCapacity = 3` (`CE:95`) — note that the
   latter is flagged in-source as an **untested experiment, measurement pending** (`CE:81-86`), so do
   not port `3` as if it were measured.
5. iOS 26 alternative to the adaptor: `AVAssetWriter.inputPixelBufferReceiver(for:pixelBufferAttributes:)`
   (`AVFSI:1105`), whose `append(_:with:)` is `async` and applies backpressure. The adaptor is not
   deprecated, but the receiver is what the async writer loop in §2.4 wants.

---

# 8. Audio

## 8.1 Read PCM float32

Android decodes to PCM16, converts `/32768f`, hand-mixes to stereo with ITU-R BS.775 `0.70710678`
coefficients, and resamples to 44 100 Hz via one `SonicAudioProcessor` session (`AD:117-125`,
`AD:203-229`). On Apple, `AVAssetReaderAudioMixOutput` does the decode, the downmix **and** the
sample-rate conversion in one output — and it does them with a real SRC, not Sonic's 2-tap linear
interpolation (which `AD:20-24` records as costing ~−27 dB of in-band distortion, and which was
deleted from the write side for exactly that reason).

```swift
func pcmFloat32Settings(sampleRate: Double = 44100, channels: Int = 2) -> [String: Any] {
    [
        AVFormatIDKey:              kAudioFormatLinearPCM,   // AVFAudio/AVAudioSettings.h:17
        AVSampleRateKey:            sampleRate,              // :18
        AVNumberOfChannelsKey:      channels,                // :19
        AVLinearPCMBitDepthKey:     32,                      // :22
        AVLinearPCMIsFloatKey:      true,                    // :24
        AVLinearPCMIsBigEndianKey:  false,                   // :23
        AVLinearPCMIsNonInterleaved: false,                  // :26 — interleaved L,R,L,R
    ]
}

func readPCM(asset: AVURLAsset,
             onBatch: (UnsafeBufferPointer<Float>, Int) throws -> Void) async throws {
    let tracks = try await asset.loadTracks(withMediaType: .audio)
    guard !tracks.isEmpty else { throw NaqiError.noAudioTrack }
    let reader = try AVAssetReader(asset: asset)
    let out = AVAssetReaderAudioMixOutput(audioTracks: tracks,
                                          audioSettings: pcmFloat32Settings())
    out.alwaysCopiesSampleData = false
    reader.add(out)
    guard reader.startReading() else { throw reader.error! }

    while let sb = out.copyNextSampleBuffer() {
        let frames = CMSampleBufferGetNumSamples(sb)
        guard frames > 0, let bb = CMSampleBufferGetDataBuffer(sb) else { continue }
        var length = 0
        var ptr: UnsafeMutablePointer<CChar>?
        CMBlockBufferGetDataPointer(bb, atOffset: 0, lengthAtOffsetOut: nil,
                                    totalLengthOut: &length, dataPointerOut: &ptr)
        guard let base = ptr else { continue }
        try base.withMemoryRebound(to: Float.self, capacity: length / 4) {
            try onBatch(UnsafeBufferPointer(start: $0, count: length / 4), frames)
        }
    }
    if reader.status == .failed { throw reader.error! }
}
```

Measured on `test-video.mp4` (M#2): 149 sample buffers, **565 214 frames** at 44 100 Hz = 12.817 s,
first PTS `0.0`.

| Android behaviour | Apple equivalent | Note |
|---|---|---|
| `SAMPLE_RATE = 44100` everywhere; separator, encoder, PTS clock all one number (`AD:30`, `AW:36`) | `AVSampleRateKey: 44100` on the mix output | Keep 44 100 — htdemucs's training rate (`AW:32-36`) |
| Mono duplicated to stereo (`AD:204-208`) | `AVNumberOfChannelsKey: 2` | Mix output handles it |
| >2 ch: ITU-R BS.775 with `0.70710678` on C/Ls/Rs, LFE dropped (`AD:210-228`) | `AVAssetReaderAudioMixOutput` default downmix | **Not the same coefficients.** For >2 ch sources this is a real behaviour change; see §12 risk 4 |
| Authoritative rate/channels come from `INFO_OUTPUT_FORMAT_CHANGED` because HE-AAC/Opus rewrite them (`AD:294-295`) | The mix output's `audioSettings` are authoritative; the reader converts | Simpler, and removes `AD:151-156`'s provisional-then-corrected dance |
| First-audio-PTS anchor, **legitimately negative** — measured `-21333 µs` on `test-video.mp4` (`AD:268`) | Same instant surfaces as `TrimDurationAtStart = 1024/48000 s = 21.333 ms` (M#2) | §8.3 |
| A3 stats pass: 20 windows × 2 s (`AD:44-45`) | Same geometry, using `reader.timeRange` per window, or one `AVAssetReader` per window | `AVAssetReader.timeRange` may only be set before `startReading` (`AVFH/AVAssetReader.h:139`), so it is one reader per window, not one reader re-seeked |

`audioSettings` for `AVAssetReaderAudioMixOutput` must be **linear PCM only**
(`AVFH/AVAssetReaderOutput.h:356`, `:377`). `AVSampleRateConverterAudioQualityKey` is not supported.

## 8.2 Write AAC-LC

Android: AAC-LC, 44 100 Hz, stereo, **192 000 bps**, `KEY_MAX_INPUT_SIZE = 16 384`, PCM16 in
(`AW:49-54`).

```swift
func aacSettings(sampleRate: Double = 44100, channels: Int = 2,
                 bitrate: Int = 192_000) -> [String: Any] {        // AW:51
    [
        AVFormatIDKey:              kAudioFormatMPEG4AAC,           // AAC-LC — AW:50 AACObjectLC
        AVSampleRateKey:            sampleRate,                     // AW:36
        AVNumberOfChannelsKey:      channels,
        AVEncoderBitRateKey:        bitrate,                        // AVFAudio/AVAudioSettings.h:37
        AVEncoderBitRateStrategyKey: AVAudioBitRateStrategy_Constant, // :39, values :60-63
    ]
}
```

Accepted by a real MP4 writer (M#3). Feed it **linear PCM** `CMSampleBuffer`s — *"If the sample buffer
contains audio data and the AVAssetWriterInput was initialized with an outputSettings dictionary then
the format must be linear PCM"* (`AVFH/AVAssetWriterInput.h:238`). You can feed f32 directly; the
`FloatArray → int16` quantisation in `AW:81-98` (including its `isFinite` NaN guard,
`AW:88-93` — one corrupt sample once killed a multi-minute job) becomes **unnecessary**. Keep an
equivalent non-finite guard anyway, because the separator is still the producer.

Things that disappear relative to `AacWriter`:

| Android machinery | Why it goes away |
|---|---|
| `INFO_OUTPUT_FORMAT_CHANGED` → `muxer.addTrack(encoder.outputFormat)` to capture the AudioSpecificConfig, and dropping the standalone `BUFFER_FLAG_CODEC_CONFIG` buffer (`AW:159-170`) | `AVAssetWriter` handles the `esds`/ASC itself |
| Non-blocking `drainEncoder(false)` — the fix worth **135 s → ~6 s** on a 193 s track (`AW:147-154`) | No manual drain loop exists |
| `dequeueInputBuffer(0)` then fall back to blocking (`AW:122-134`, ~7 000 blocking 10 ms waits per job) | `isReadyForMoreMediaData` / `async append` |
| Monotonic `firstPtsUs + samplesOut * 1e6 / RATE` PTS clock (`AW:117`) | Still needed — you construct the PCM `CMSampleBuffer`s, so you own their PTS. **Keep this formula exactly**, anchored at the source's first audio PTS, or the audio track loses the video epoch |

## 8.3 Encoder priming / delay — **measured, and AVFoundation handles it if you let it**

The header is the specification (`AVFH/AVAssetWriterInput.h:238`):

> Note that advanced formats like AAC will have **encoder delay** present in their bitstreams. …
> Clients who provide compressed audio bitstreams **must use
> `kCMSampleBufferAttachmentKey_TrimDurationAtStart`** to mark the encoder delay (generally restricted
> to the first sample buffer). Packetization can cause there to be extra audio frames in the last
> packet … marked with `kCMSampleBufferAttachmentKey_TrimDurationAtEnd`.
> **CMSampleBuffers obtained from AVAssetReader will already have the necessary trim attachments.**

Measured on `test-video.mp4` (M#2):

| Observation | Value |
|---|---|
| First **compressed** audio sample, `outputSettings: nil` | `PTS = 0.0`, `kCMSampleBufferAttachmentKey_TrimDurationAtStart = {value: 1024, timescale: 48000}` = **21.333 ms** |
| Audio track edit list | `src.start = 0.021333 s → tgt.start = 0.0` — the same 1024 samples |
| First **decoded** PCM buffer via `AVAssetReaderAudioMixOutput` | `PTS = 0.0`, **no** trim attachment — already applied |
| Android's view of the same instant | first audio sample PTS `-21333 µs` (`AD:268`) |

Three cases, three contracts:

1. **Passthrough AAC copy** (the `RX:52` `ConcatAudio.COPY` path): append the `CMSampleBuffer`s exactly
   as the reader gave them, attachments intact. `alwaysCopiesSampleData = false` preserves the
   attachments. Do not rebuild the buffers. Verified byte-identical (M#5).
2. **Decode → DSP → re-encode** (the music-removal path): the reader has already applied the delay,
   so your PCM starts at true zero. Anchor the output PTS clock at `CMTime.zero`
   (or at the source's first audio PTS if you want the original epoch) and let `AVAssetWriter`'s AAC
   encoder insert and mark its own new priming. **This is the case Android leaves uncompensated by
   convention** (`AW:25`: "the ~2048-sample encoder priming is left uncompensated"). On Apple it is
   compensated for free, so the Apple output is ~21–46 ms better aligned than Android's. Do not
   "restore" the Android behaviour.
3. **You build compressed AAC yourself** (you will not): you must attach
   `TrimDurationAtStart`/`TrimDurationAtEnd` yourself.

If you ever need to attach one:

```swift
func setTrimAtStart(_ sb: CMSampleBuffer, _ d: CMTime) {
    CMSetAttachment(sb,
        key: kCMSampleBufferAttachmentKey_TrimDurationAtStart,   // CMH/CMSampleBuffer.h:1601
        value: CMTimeCopyAsDictionary(d, allocator: kCFAllocatorDefault),
        attachmentMode: kCMAttachmentMode_ShouldPropagate)
}
```

The effective output duration formula is `(Duration − TrimDurationAtStart − TrimDurationAtEnd) /
SpeedMultiplier` and the effective start is `PresentationTimeStamp + TrimDurationAtStart`
(`CMH/CMSampleBuffer.h:1252`, `:1269`).

Also from the same paragraph: *"if you want your audio to start at time zero in the output file then
make sure that the output PTS of the **first non-fully-trimmed** audio sample buffer is
`kCMTimeZero`"*. A fully-trimmed first buffer is legal and is how a decoder is told to discard priming.

## 8.4 A/V alignment budget

Android's PRD budget is **50 ms** of A/V drift, and `RX:174-181` is the note explaining why segment
offsets must be *intended* starts rather than accumulated measured durations — 31 joins of sub-frame
rounding reach "up to a second". On Apple:

- Copy PTS verbatim in passthrough (§3.4 rule 3).
- Set `input.mediaTimeScale` to the source's (§2.3), so no rescaling error accumulates.
- For a concat, shift by an exact `CMTime` computed from the **intended** segment start, not from the
  measured previous-segment duration. Use `CMTimeConvertScale(_:timescale:method:)` once, at plan time.

---

# 9. MKV / WebM — **AVFoundation cannot demux it, on any Apple OS 26**

The Android app ships MKV/WebM support and advertises it (`docs/store-listing.md:40`,
`docs/prd-video-filter-android.md:7`), and the QA corpus is largely `.webm`
(`qa-assets/tv1.webm`, `test-video-1.webm`, `a week in my life vlog.webm`).

## 9.1 Evidence

1. **`AVURLAsset.audiovisualTypes()` on macOS 26 returns 104 UTIs, none of them Matroska or WebM**
   (M#1a). The declared, non-`dyn.` entries are: QuickTime movie/audio, MPEG-4 family, 3GPP/3GPP2,
   AVI, DV, MPEG-1/2 PS/TS, AVCHD, MP3/AAC/AC-3/E-AC-3/AIFF/AIFC/CAF/WAV/Wave64/AU/FLAC/Ogg-audio,
   Audible, `org.videolan.{mod,mpeg-stream,vob}`, WebVTT, SCC, iTT, M3U/PLS. iOS's list is a subset.
2. **The UTIs are declared by the system but not by AVFoundation** (M#1a): `UTType("org.matroska.mkv")`
   is declared and conforms to `public.movie`/`public.audiovisual-content`; `UTType(filenameExtension:
   "webm")` resolves to `org.webmproject.webm`. So a document picker *will* let the user choose one.
3. **Opening a real `.webm` fails** (M#1b), on `qa-assets/test-video-1.webm`:
   ```
   Error Domain=AVFoundationErrorDomain Code=-11828 "Cannot Open"
     NSUnderlyingError = NSOSStatusErrorDomain Code=-12847
     NSLocalizedFailureReason = "This media format is not supported."
     AVErrorFailedDependenciesKey = (assetProperty_Tracks)
   ```
4. `AVFileType` has no Matroska constant either, so you cannot **write** one
   (`AVFH/AVMediaFormat.h:334-480`).
5. The iOS 26 / iOS 18 Vision-and-AVFoundation release notes list no Matroska work
   (`developer.apple.com/documentation/updates/vision`; AVFoundation's 2025 notes add spatial video,
   ProRes RAW, `AVFileTypeDICOM` — `AVFH/AVMediaFormat.h:480`, `AVVideoSettings.h:53-54`).

**Codecs are a separate question and mostly fine**: VideoToolbox decodes H.264, HEVC, and (A17 Pro /
M3 and later) AV1 in hardware. The blocker is the *container*, not the bitstream.

## 9.2 Options, ranked

| Option | What it costs | Verdict |
|---|---|---|
| **A. Drop MKV/WebM from the Apple v1** | Product scope. Store copy must not claim it (`store-listing.md:40`) | **Recommended for v1.** Ship MP4/MOV/M4V, the formats Photos and Files actually hand you on iOS |
| **B. Remux MKV → MP4 on import with a small Matroska demuxer**, then run the whole existing pipeline | ~2–4 kLOC of EBML parsing, or a vendored MIT/BSD demuxer. Elementary streams are copied, never re-encoded, so it is fast and lossless. Fails on codecs MP4 cannot carry (Vorbis; VP8/VP9 need `vpcC` and iOS will not decode VP9 in hardware) | **Recommended for v2.** Cleanest: everything downstream stays AVFoundation |
| **C. Full custom pipeline** — demux + `VTDecompressionSession` + `AudioConverter` + `AVAssetWriter` | Reimplements §1 and §8 for one container | No |
| **D. FFmpegKit / libavformat** | **LGPL-2.1 at minimum** for a dynamically linked, unmodified build; GPL if you enable the GPL components. ~10–40 MB binary. The Android side already rejected an AGPL dependency in a closed-source app once, on exactly this reasoning (`FT:23-26`) | Only with a deliberate licence decision |
| **E. Ask the user to convert** | Bad UX, and impossible inside the iOS share sheet | No |

Whichever is chosen, **the file picker must reject what the pipeline cannot open, at pick time**, with
a specific message. Today's failure mode is `AVError -11828` surfacing hours in, which is precisely the
class of bug `RX:66-78` was written to kill on Android ("a long silent source … threw after the whole
analyze+render pass, with no per-cause message"). Preflight with:

```swift
func canOpen(_ url: URL) async -> Bool {
    let asset = AVURLAsset(url: url)
    do { return try await asset.load(.isPlayable) && !(try await asset.loadTracks(withMediaType: .video)).isEmpty }
    catch { return false }
}
```

---

# 10. Constants that must survive the port

## 10.1 Decode / sampling

| Constant | Value | Android | Apple home |
|---|---:|---|---|
| Analyze fps | 10 | `FS:121` | `SlotGrid(fps: 10)` |
| Detector input longest side | 640 px, downscale only | `FS:122`, `FS:356` | Vision ROI / CI transform |
| NSFW gate cadence | every 2nd emitted frame (5 fps) | `FS:123` | analyze pass |
| Gate tensor | `[1,3,224,224]`, `/255`, BT.601 full-range `1436/352/731/1815 >> 10` | `FS:60`, `FS:531-566` | `spec-analyze.md` |
| Gender crop tensor | `[1,3,96,96]`, **0…255**, square side `max(w,h)×1.5` on the raw box centre | `FS:63`, `FS:693-733` | `spec-analyze.md` |
| Gate input built over the **crop rect**, never the 640-px picture | — | `FS:396-400` | **Do not "optimise"**: the shortcut measured **91.24 %** censored-timeline recall against a ≥99.20 % bar, 34.5 s under-censored on a 643 s clip (`FS:568-580`) |

## 10.2 Tracking / EDL

| Constant | Value | Android |
|---|---:|---|
| Track eviction gap | **2 000 ms** (`>=`) | `FT:264`, `FT:310` |
| Span pad each side | **50 ms** (half the 10 fps gap) | `FT:267` |
| Keyframe rect pad | **25 % per side** ⇒ 1.5× each axis, clamped `[0,1]` | `FT:270`, `FT:313-322` |
| Gender votes per track | **5** | `FT:231` |
| Min crop side for a vote | **80 px** upright (measured band table) | `FT:240-253` |
| Verdict when no vote cast | **censor** (0/0 censors, a tie censors) | `FT:302-307` |
| Whole-frame span bridge | **400 ms** | `ED:97` |
| Min whole-frame span kept | **500 ms** (applied *after* merge) | `ED:132`, `ED:146-148` |
| Rect interpolation between keyframes | linear, clamped at the ends, binary search | `ED:150-175` |

## 10.3 Render / encode

| Constant | Value | Android |
|---|---:|---|
| Bitrate headroom / tier caps | see §2.1 | `RP:64`, `RP:275-281` |
| Keyframe interval | 2.0 s | `RP:158` |
| Blur sigma | `max(0.1, amt/100 × 40 × min(W,H)/1080)` | `CE:140` |
| Blur downscale ladder | `{1,2,4,8}`, first with `σ/d ≤ 4` | `CE:142` |
| Max regions | 8, largest-by-area kept | `CE:30`, `CE:182-187` |
| Feather | `max(0.15 × regionSize, 0.002)`, outward only | `CE:382` |
| Grayscale luma | BT.709 | `CE:400` |
| Segment length | 5 min, only above the 30-min "long job" threshold | `CP:37`, `CP:68` |

## 10.4 Measured Android workarounds — **do not silently drop**

| # | Workaround | Android citation | Apple status |
|---|---|---|---|
| W1 | Interior segment boundaries snapped to sync samples; un-snapped loses 1–6 frames/seam and shifted an MKV segment ~1 s (mismatch 77.7 → 3.0) | `CP:48-63`, `FW:476-528`, `long-film-followups.md` | **Still needed for passthrough segments only** (§1.7, M#8). Not needed for re-encoded segments |
| W2 | Empty effects list ⇒ container copy; a "draws nothing" effect still costs full decode→GL→encode | `RP:98-111` | Port as: empty EDL ⇒ take the §3 passthrough path. Same reasoning, different mechanism |
| W3 | Passthrough job must **not** request a mime type, an encoder factory, or an HDR mode — each alone forces a transcode | `RP:144-151`, `RP:172-178` | Apple analogue: passthrough input has `outputSettings: nil`; setting *anything* means a re-encode |
| W4 | `KEY_OPERATING_RATE = MAX_VALUE` measured at −0.5 % (noise) and removed | `FS:207-209` | Do not add `PrioritizeEncodingSpeedOverQuality` without measuring |
| W5 | `operatingRate = 1000, priority = 1` pinned to dodge an SM8550 configure-time throw | `RP:159-164` | Android-only; drop |
| W6 | Gate must sample the **source** planes, not the 640-px picture (A4: 91.24 % recall) | `FS:568-580` | Carry into `spec-analyze.md` |
| W7 | AAC encoder priming left uncompensated by convention | `AW:25` | **Apple compensates automatically** (§8.3). Behaviour improves; do not re-break it |
| W8 | Sonic 44.1→48 kHz resampler deleted: ~−27 dB in-band distortion | `AD:20-24` | Keep everything at 44 100 Hz end to end |
| W9 | `readSampleData` grow-on-`IllegalArgumentException`, 1 MiB default buffer | `RX:329-348` | N/A — `CMBlockBuffer` is self-sizing |
| W10 | Muxer takes one format per track; a mixed concat writes a silently wrong second sample entry | `RX:277-291`, `RP:105-110` | **Still true** (§3.4 rule 5): compare `CMFormatDescription` across segments and fail loudly |
| W11 | `texturePoolCapacity = 3` is an **untested** experiment | `CE:81-86` | Do not port the number as if measured |
| W12 | 180°-rotated and square-rotated sources are unresolvable in media3's orientation detection | `CE:124-136` | **Gone on Apple** — reader output is always stored orientation (§5) |
| W13 | Fragmented MP4 without `sidx` breaks clipped exports | `long-film-followups.md` | Do not enable `movieFragmentInterval` (§2.5) |

---

# 11. Verification log — what was actually run, 2026-08-04

Host: macOS 26 (`arm64-apple-macosx26.0`), Xcode 26.6 build 17F113, Swift 6.3.3,
iPhoneOS SDK 26.5, MacOSX SDK 26.5.
Scratch: `/private/tmp/claude-501/-Users-goldentik-Documents-naqi/40c5cb1e-…/scratchpad/`.

| ID | What | Result |
|---|---|---|
| M#0 | `AVURLAsset` probe of `qa-assets/test-video.mp4` | playable, 1 video track, 12.8 s, natural `(1080, 1920)`, `preferredTransform` identity, 30 fps, `'avc1'` |
| M#1a | `AVURLAsset.audiovisualTypes()` + `UTType` lookups | 104 UTIs, **no Matroska/WebM**; `org.matroska.mkv` and `org.webmproject.webm` are declared system-wide |
| M#1b | Open `qa-assets/test-video-1.webm` | `AVError -11828 / OSStatus -12847`, "This media format is not supported." |
| M#2 | Audio probe | LPCM f32 mix output: 149 buffers, 565 214 frames @44.1 k, first PTS 0.0, **no** trim attachment. Compressed track: first PTS 0.0 with `TrimDurationAtStart = 1024/48000`. Edit lists: audio `src 0.021333`, video `src 0.066667` |
| M#3 | `AVAssetWriter.canApply(outputSettings:forMediaType:)` on a real `.mp4` writer | H.264 ✓, HEVC ✓, `nil` ✓, AAC ✓, H.264+`ConstantBitRate` ✓, H.264+`DataRateLimits` ✓ |
| M#4 | 10 fps sequential sample pass over `test-video.mp4` | decoded 384, emitted **128**; first decoded buffer `'420v'`, **IOSurface-backed**, 1080×1920; `transfer = ITU_R_709_2`; 80 face detections |
| M#4b | `TrackObjectRequest` UUID stability | seeded from `FaceObservation uuid 7674B771-…`; every tracked `DetectedObjectObservation.uuid` over 128 frames was the same UUID; zero changes |
| M#5 | Passthrough copy `test-video.mp4` → MP4, both tracks | writer `.completed`; video **SHA-256 identical**, 384 samples, 4 788 746 B; audio payload identical, 383→23 buffers; edit lists and transform preserved; video PTS non-monotonic `0.0667, 0.200, 0.133, 0.100, 0.167` |
| M#6 | MP4 passthrough **without** `sourceFormatHint` | `canAdd == false`; `addInput:` raises `NSInvalidArgumentException` naming the missing hint |
| M#7 | Write a **4.29 GiB** MP4 (369 024 passthrough samples) | `.completed`, `error == nil`; `mdat` uses the 64-bit `largesize` form (4 601 984 922 B); **`co64` × 1, `stco` × 0**; read-back 369 029 samples, duration 12 300.87 s |
| M#8 | `reader.timeRange = [5 s, 7 s)` | Decompressed: first PTS **5.000000**, exactly **60** frames (frame-accurate). Passthrough: first PTS **0.0667**, **212** samples (rewinds to the preceding sync sample). Track sync samples: `[0.0667, 8.4]` only |
| T#1 | `swiftc -swift-version 6 -target arm64-apple-ios26.0 -typecheck` over the Vision + Core Image snippets | clean (after fixing `revision` `let` and the optional `TrackObjectRequest.Result`) |
| T#2 | Same, over the full reader/writer/rotation/audio harness | clean; only diagnostic is `AVMutableVideoComposition deprecated in iOS 26.0: Use AVVideoComposition.Configuration instead` |
| T#3 | Same, over the iOS 26 modern APIs (`outputProvider`, `inputReceiver`, `inputPixelBufferReceiver`, `AVVideoComposition.Configuration`, `AVVideoComposition(applyingFiltersTo:)`, the actor-isolated `TrackBook`) | clean |

## 11.1 Soft-deprecations found (all still function; all warn or will warn)

| API | Replacement | Citation |
|---|---|---|
| `AVAssetReaderOutput.copyNextSampleBuffer()` | `AVAssetReaderOutput.Provider.next()` | `AVFH/AVAssetReaderOutput.h:97` |
| `AVAssetWriterInput.append(_:)` | input receiver's `appendImmediately(...)` | `AVFH/AVAssetWriterInput.h:160`, `:176` |
| `AVAssetWriterInput.requestMediaDataWhenReady(on:using:)` | input receiver's async `append(...)` on its own task | `AVFH/AVAssetWriterInput.h:218` |
| **`AVMutableVideoComposition`** (hard-dated `ios(4.0, 26.0)`) | `AVVideoComposition.Configuration` | `AVFH/AVVideoComposition.h:263` |
| `AVVideoCompositionInstruction`, `AVVideoCompositionLayerInstruction` | their `.Configuration` forms | `AVFH/AVVideoComposition.h:558`, `:654` |
| `videoComposition(with:applyingCIFiltersWithHandler:)` (iOS 18) | `AVVideoComposition(applyingFiltersTo:applier:)` | `AVFH/AVVideoComposition.h:207`; `AVFSI:840` |
| `AVAssetExportSession.exportAsynchronously` / `.progress` / `.cancelExport()` | `export(to:as:)`, `states(updateInterval:)`, `Task.cancel()` | `AVFH/AVAssetExportSession.h:254`, `:261`, `:272` |
| `AVAssetTrack.preferredTransform` / `.naturalSize` / `.nominalFrameRate` (Swift only) | `load(.preferredTransform)` etc. | `AVFH/AVAssetTrack.h:118`, `:121`, `:147` |
| `VNTrackingRequest.trackingLevel` | no effect on `VNTrackObjectRequestRevision2`; absent from the Swift API | `VNTrackingRequest.h:56` |

---

# 12. Risks and open questions, ranked

1. **The rotated-asset table in §6.5 is unverified.** `test-video.mp4` is `preferredTransform`-identity,
   so the `Rotation → CGImagePropertyOrientation` mapping and the whole §5.4 round trip have been
   reasoned, typechecked and self-tested but never run against a 90/180/270 source. This is the exact
   thing that was a landmine on Android (`CE:40-46`, `CE:124-136`) and the Android QA manifest still
   lists a rotated clip as never-gathered (`docs/m0-spikes.md:58`, `docs/tasks.md:17`). **Do this first.**
2. **MKV/WebM is a shipped Android capability with no Apple answer** (§9). It is a product decision, not
   an engineering one, and the QA corpus is mostly `.webm` — which means the Apple port currently has
   *no runnable regression corpus* for the numbers the Android docs measured (`tv1.webm`, 643 s;
   `test-video-1.webm`). Converting the corpus to MP4 changes the bitstreams and therefore the
   comparison baseline.
3. **Vision face-detection recall and cost vs ML Kit `PERFORMANCE_MODE_FAST` are unknown.** Android
   measured ~8.6 ms/frame at 640 px (`FS:382-384`). Vision has no speed knob and one revision, and the
   Apple design adds **one tracker step per live track per frame**. The 91.24 %-recall bar
   (`FS:568-580`) is the acceptance criterion and it has to be re-measured end to end.
4. **>2-channel downmix differs.** Android's ITU-R BS.775 fold with LFE dropped (`AD:210-228`) exists
   because *"a 5.1 film mixes nearly all of its dialogue discretely into center — the vocals stem would
   come back empty on exactly the content this feature exists for."* `AVAssetReaderAudioMixOutput`'s
   default downmix is not documented to be that fold. On a 5.1 source the separator input therefore
   changes. Either verify the coefficients or do the fold yourself from a discrete multichannel PCM read.
5. **`AVVideoComposition.Configuration` + the CI applier is used at one remove.** §4.5 copies
   `filtered.instructions` from a composition built by `AVVideoComposition(applyingFiltersTo:)` into a
   fresh `Configuration` in order to also set the colour triple. That typechecks (T#3) but has not been
   run. If it misbehaves, fall back to the deprecated `AVMutableVideoComposition` — it still works in
   iOS 26 — or avoid compositions entirely in the render path, which §4.5 already recommends.
6. **Core Image blur is not radius-capped** where the GLSL was (`MAX_RADIUS = 10`, `CE:27`). At
   `blurAmount = 100` on a 4K frame the Apple output will be *more* blurred than Android's. Decide
   whether "matches Android pixel-for-pixel" or "looks right" is the bar.
7. **B-frames.** §2.2 sets `AVVideoAllowFrameReorderingKey: true` where Android shipped without B-frames
   (`RP:159-160`). Better quality per bit, but it makes output PTS non-monotonic, which every
   progress/concat path must already tolerate (§1.8, §3.4). Flip to `false` if any downstream consumer
   assumes ordered PTS.
8. **Pool sizing is unmeasured.** `RING = 4` (`FS:68`) and `texturePoolCapacity = 3` (`CE:95`, itself
   untested) are the only prior art. Start at 4 and measure.
