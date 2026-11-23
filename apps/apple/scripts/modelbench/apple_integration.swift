import AVFoundation
import CoreMedia
import CryptoKit
import Foundation
import os

/// Uses the production media stages, including their streamed DSP/export.
/// No whole-audio fixture buffering and no replacement model implementations.
@main
struct IntegrationBench {
    struct TrackDigest {
        let samples: Int
        let bytes: Int
        let sha256: String
        let presentationTimesUs: [Int64]
        let range: CMTimeRange
    }

    static func sha(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }

    static func track(_ url: URL, kind: AVMediaType) async throws -> TrackDigest {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let source = try await asset.loadTracks(withMediaType: kind).first else {
            throw MediaError.readerFailed("missing \(kind.rawValue) track")
        }
        let mappings = kind == .video ? try await source.load(.segments).filter { !$0.isEmpty }.map(\.timeMapping) : []
        let reader = try TrackReader.compressed(track: source)
        try reader.start()
        var hash = SHA256(), bytes = 0, samples = 0, pts: [Int64] = []
        while let buffer = reader.next() {
            let count = CMSampleBufferGetNumSamples(buffer)
            samples += count
            if kind == .video {
                for i in 0..<count {
                    var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: .invalid, decodeTimeStamp: .invalid)
                    guard CMSampleBufferGetSampleTimingInfo(buffer, at: i, timingInfoOut: &timing) == noErr,
                          timing.presentationTimeStamp.isNumeric else {
                        throw MediaError.readerFailed("per-frame presentation timestamp")
                    }
                    guard let mapping = mappings.first(where: { CMTimeRangeContainsTime($0.source, time: timing.presentationTimeStamp) }) else {
                        throw MediaError.readerFailed("video media timestamp outside edit-list mapping")
                    }
                    let movieTime = CMTimeMapTimeFromRangeToRange(timing.presentationTimeStamp, fromRange: mapping.source, toRange: mapping.target)
                    pts.append(movieTime.convertScale(1_000_000, method: .default).value)
                }
            }
            if let block = CMSampleBufferGetDataBuffer(buffer) {
                let length = CMBlockBufferGetDataLength(block)
                var data = Data(count: length)
                let status = data.withUnsafeMutableBytes { CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: $0.baseAddress!) }
                guard status == kCMBlockBufferNoErr else { throw MediaError.readerFailed("compressed track bytes") }
                hash.update(data: data); bytes += length
            }
        }
        try reader.throwIfFailed()
        return TrackDigest(samples: samples, bytes: bytes,
                           sha256: hash.finalize().map { String(format: "%02x", $0) }.joined(),
                           presentationTimesUs: pts.sorted(), range: try await source.load(.timeRange))
    }

    static func decodedAudioFrames(_ url: URL) async throws -> Int {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { throw MediaError.noAudioTrack }
        let decoder = try AudioDecoder(track: track)
        try decoder.start()
        var frames = 0
        while let (_, count) = try decoder.next() { frames += count }
        return frames
    }

    static func decodedVideoTimes(_ url: URL) async throws -> [Int64] {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { throw MediaError.noVideoTrack }
        let reader = try TrackReader.decodedVideo(track: track)
        try reader.start()
        var times: [Int64] = []
        while let buffer = reader.next() {
            let pts = CMSampleBufferGetPresentationTimeStamp(buffer)
            guard pts.isNumeric else { throw MediaError.readerFailed("decoded frame timestamp invalid") }
            times.append(pts.convertScale(1_000_000, method: .default).value)
        }
        try reader.throwIfFailed()
        return times
    }

    static func kernelPeak() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        } }
        return status == KERN_SUCCESS ? UInt64(max(0, info.ledger_phys_footprint_peak)) : 0
    }

    static func diagnoseAudio(_ url: URL) async throws {
        let asset = AVURLAsset(url: url)
        defer { withExtendedLifetime(asset) {} }
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else { throw MediaError.noAudioTrack }
        let range = try await track.load(.timeRange)
        let duration = try await asset.load(.duration)
        var passes: [[String: Any]] = []
        for _ in 0..<3 {
            let reader = try AVAssetReader(asset: asset)
            let output = AVAssetReaderTrackOutput(track: track, outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: Models.Demucs.sampleRate,
                AVLinearPCMBitDepthKey: 32, AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsBigEndianKey: false, AVLinearPCMIsNonInterleaved: false])
            output.alwaysCopiesSampleData = false
            reader.add(output)
            guard reader.startReading() else { throw MediaError.readerFailed("diagnostic reader start") }
            var total = 0, buffers: [[String: Any]] = []
            while let buffer = output.copyNextSampleBuffer() {
                let frames = CMSampleBufferGetNumSamples(buffer)
                let dataReadyBefore = CMSampleBufferDataIsReady(buffer)
                let pts = CMSampleBufferGetPresentationTimeStamp(buffer)
                let length = CMSampleBufferGetDuration(buffer)
                let computed = CMTimeAdd(pts, CMTime(value: Int64(frames), timescale: 44_100))
                var list = AudioBufferList(), block: CMBlockBuffer?
                let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(buffer,
                    bufferListSizeNeededOut: nil, bufferListOut: &list, bufferListSize: MemoryLayout<AudioBufferList>.size,
                    blockBufferAllocator: nil, blockBufferMemoryAllocator: nil,
                    flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block)
                guard status == noErr else { throw MediaError.readerFailed("diagnostic PCM bytes") }
                let asbd = CMSampleBufferGetFormatDescription(buffer).flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
                buffers.append(["frames": frames, "pts_s": pts.isNumeric ? pts.seconds : -999,
                    "duration_s": length.isNumeric ? length.seconds : -999,
                    "computed_end_s": computed.isNumeric ? computed.seconds : -999,
                    "data_bytes": list.mBuffers.mDataByteSize, "channels": list.mBuffers.mNumberChannels,
                    "rate": asbd?.mSampleRate ?? 0,
                    "num_samples_after_pcm_acquisition": CMSampleBufferGetNumSamples(buffer),
                    "data_ready_before": dataReadyBefore, "data_ready_after": CMSampleBufferDataIsReady(buffer)])
                total += frames
                withExtendedLifetime(block) {}
            }
            guard reader.status != .failed else { throw MediaError.readerFailed(reader.error?.localizedDescription ?? "diagnostic decode") }
            let production = try await decodedAudioFrames(url)
            passes.append(["raw_total_frames": total, "production_decoder_frames": production, "buffers": buffers])
        }
        let result: [String: Any] = ["input": url.path, "asset_duration_s": duration.seconds,
            "audio_track_start_s": range.start.seconds, "audio_track_duration_s": range.duration.seconds,
            "audio_track_end_s": CMTimeRangeGetEnd(range).seconds, "passes": passes]
        print(String(decoding: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), as: UTF8.self))
    }

    static func diagnoseVideo(_ input: URL, _ output: URL) async throws {
        let a = try await track(input, kind: .video), b = try await track(output, kind: .video)
        let differences = zip(a.presentationTimesUs, b.presentationTimesUs).map { $1 - $0 }
        let decodedA = try await decodedVideoTimes(input), decodedB = try await decodedVideoTimes(output)
        func segments(_ url: URL) async throws -> [[String: Any]] {
            let asset = AVURLAsset(url: url)
            defer { withExtendedLifetime(asset) {} }
            guard let video = try await asset.loadTracks(withMediaType: .video).first else { throw MediaError.noVideoTrack }
            return try await video.load(.segments).map {
                let m = $0.timeMapping
                return ["source_start": m.source.start.seconds, "source_duration": m.source.duration.seconds,
                        "target_start": m.target.start.seconds, "target_duration": m.target.duration.seconds]
            }
        }
        let sourceSegments = try await segments(input), outputSegments = try await segments(output)
        let result: [String: Any] = ["source_frames": a.samples, "output_frames": b.samples,
            "source_pts_count": a.presentationTimesUs.count, "output_pts_count": b.presentationTimesUs.count,
            "timestamps_exact": a.presentationTimesUs == b.presentationTimesUs,
            "maximum_pts_difference_us": differences.map(abs).max() ?? 0,
            "source_first_pts": Array(a.presentationTimesUs.prefix(5)), "output_first_pts": Array(b.presentationTimesUs.prefix(5)),
            "source_last_pts": Array(a.presentationTimesUs.suffix(5)), "output_last_pts": Array(b.presentationTimesUs.suffix(5)),
            "source_range": [a.range.start.seconds, a.range.duration.seconds], "output_range": [b.range.start.seconds, b.range.duration.seconds],
            "decoded_timestamps_exact": decodedA == decodedB,
            "decoded_source_first_pts": Array(decodedA.prefix(5)), "decoded_output_first_pts": Array(decodedB.prefix(5)),
            "decoded_source_last_pts": Array(decodedA.suffix(5)), "decoded_output_last_pts": Array(decodedB.suffix(5)),
            "decoded_source_frames": decodedA.count, "decoded_output_frames": decodedB.count,
            "source_segments": sourceSegments, "output_segments": outputSegments]
        print(String(decoding: try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys]), as: UTF8.self))
    }

    static func main() async throws {
        let args = CommandLine.arguments
        if args.count == 3 && args[1] == "--audio-diagnose" {
            try await diagnoseAudio(URL(fileURLWithPath: args[2])); return
        }
        if args.count == 4 && args[1] == "--video-diagnose" {
            try await diagnoseVideo(URL(fileURLWithPath: args[2]), URL(fileURLWithPath: args[3])); return
        }
        guard args.count == 9 || args.count == 10,
              let who = FilterOps.Who(rawValue: args[4]), [.women, .men, .everyone].contains(who),
              ["face", "person"].contains(args[5]), ["on", "off"].contains(args[6]),
              ["combined", "music", "visual", "rerender"].contains(args[7]), ["on", "off"].contains(args[8]),
              (args[7] == "rerender") == (args.count == 10) else {
            throw MediaError.readerFailed("Usage: apple_integration INPUT OUTPUT RESULTS women|men|everyone face|person on|off[music gate] combined|music|visual|rerender on|off[scene gate] [SAVED_EDL]")
        }
        let input = URL(fileURLWithPath: args[1]), output = URL(fileURLWithPath: args[2])
        let audioURL = output.appendingPathExtension("separated.mp4")
        let edlURL = output.appendingPathExtension("analysis.json")
        let regionURL = output.appendingPathExtension("regions.jsonl")
        let paths = [input, output, URL(fileURLWithPath: args[3]), audioURL, edlURL, regionURL]
            + (args.count == 10 ? [URL(fileURLWithPath: args[9])] : [])
        guard Set(paths.map { $0.standardizedFileURL.resolvingSymlinksInPath().path }).count == paths.count else {
            throw MediaError.writerFailed("input, output, results, derived artifacts and saved EDL paths must differ")
        }
        guard [output, audioURL, edlURL, regionURL].allSatisfy({ !FileManager.default.fileExists(atPath: $0.path) }) else {
            throw MediaError.writerFailed("benchmark output/artifact already exists; choose a fresh output path")
        }
        try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        var options = FilterOps()
        options.who = who
        options.removeMusic = args[7] == "combined" || args[7] == "music"
        options.censor = args[7] != "music"
        options.censorNsfw = args[8] == "on"
        options.keepStems = .vocals
        guard args[5] == "face" else {
            throw MediaError.readerFailed("whole-body coverage was removed; pass face")
        }
        options.censorTarget = .face
        #if !NAQI_PERSON
        guard args[6] == "on" else { throw MediaError.readerFailed("baseline supports the original gate-on setting only") }
        #endif
        let ops = options
        let failed = OSAllocatedUnfairLock(initialState: false)
        @Sendable func aborted() -> Bool { failed.withLock { $0 } }
        let analyzed = OSAllocatedUnfairLock<AnalyzeResult?>(initialState: nil)
        let separated = OSAllocatedUnfairLock<AudioPipeline.Result?>(initialState: nil)
        let branchTimes = OSAllocatedUnfairLock(initialState: [String: Double]())
        let wholeStart = ContinuousClock.now
        let source = try await MediaSource.probe(input)
        guard source.video != nil else { throw MediaError.noVideoTrack }
        let probeMs = msSince(wholeStart)
        var saved: Edl?
        if args[7] == "rerender" { saved = try Edl.fromJSONData(Data(contentsOf: URL(fileURLWithPath: args[9]))) }
        let branchesStart = ContinuousClock.now
        try await withThrowingTaskGroup(of: Void.self) { group in
            if ops.removeMusic {
                group.addTask(priority: .userInitiated) {
                    let start = ContinuousClock.now
                    #if NAQI_PERSON
                    let result = try await AudioPipeline.removeMusic(source, to: audioURL, keepStems: ops.keepStems,
                                                                     includeVideo: false, useMusicGate: args[6] == "on", isCancelled: aborted)
                    #else
                    let result = try await AudioPipeline.removeMusic(source, to: audioURL, keepStems: ops.keepStems,
                                                                     includeVideo: false, isCancelled: aborted)
                    #endif
                    separated.withLock { $0 = result }
                    branchTimes.withLock { $0["audio_wall_ms"] = msSince(start) }
                }
            }
            if ops.censor && saved == nil {
                group.addTask(priority: .utility) {
                    let start = ContinuousClock.now
                    let result = try await AnalyzePass.run(source, ops: ops, isCancelled: aborted)
                    analyzed.withLock { $0 = result }
                    branchTimes.withLock { $0["analyze_wall_ms"] = msSince(start) }
                }
            }
            var first: (any Error)?
            while !group.isEmpty {
                do { try await group.next() }
                catch { if first == nil { first = error }; failed.withLock { $0 = true } }
            }
            if let first { throw first }
        }
        let branchesMs = msSince(branchesStart)
        let edl = saved ?? analyzed.withLock { $0?.edl } ?? Edl()
        if ops.censor { try edl.toJSONData().write(to: edlURL, options: .atomic) }
        let renderStart = ContinuousClock.now
        var render: RenderPass.Result?
        var passthrough = false
        if source.video?.isHDR == false && (edl.isEmpty || ops.visualEffectIsNoop) {
            do {
                if ops.removeMusic { try await Remux.mux(video: input, audio: audioURL, to: output) }
                else { try await Remux.passthrough(source: input, to: output) }
                passthrough = true
            } catch {
                try? FileManager.default.removeItem(at: output)
                render = try await RenderPass.run(source: source, edl: edl, ops: ops, output: output,
                                                  replacedAudio: ops.removeMusic ? audioURL : nil)
            }
        } else {
            render = try await RenderPass.run(source: source, edl: edl, ops: ops, output: output,
                                              replacedAudio: ops.removeMusic ? audioURL : nil)
        }
        let renderMs = msSince(renderStart), wholeMs = msSince(wholeStart), peak = kernelPeak()
        // Verification happens after the measured job, avoiding decoder warm-up
        // and extra validation allocations in the job's memory high-water mark.
        let result = try await MediaSource.probe(output)
        let sourceVideo = try await track(input, kind: .video)
        let outputVideo = try await track(output, kind: .video)
        guard sourceVideo.samples == outputVideo.samples,
              sourceVideo.presentationTimesUs == outputVideo.presentationTimesUs,
              source.video?.naturalSize == result.video?.naturalSize,
              source.video?.transform.rotationDegrees == result.video?.transform.rotationDegrees,
              abs(source.duration.seconds - result.duration.seconds) < 0.05 else {
            throw MediaError.writerFailed("video frame/timestamp/geometry/duration integrity failed")
        }
        if passthrough && sourceVideo.sha256 != outputVideo.sha256 { throw MediaError.writerFailed("passthrough video changed") }
        let audioResult = separated.withLock { $0 }
        var decodedSourceFrames = 0, decodedOutputFrames = 0
        var audioStartDriftMs = 0.0, audioEndDriftMs = 0.0
        var audioPassthroughExact = false
        if source.hasAudio {
            decodedSourceFrames = try await decodedAudioFrames(input)
            decodedOutputFrames = try await decodedAudioFrames(output)
            let sourceAudio = try await track(input, kind: .audio), outputAudio = try await track(output, kind: .audio)
            audioStartDriftMs = (outputAudio.range.start.seconds - sourceAudio.range.start.seconds) * 1000
            audioEndDriftMs = (CMTimeRangeGetEnd(outputAudio.range).seconds - CMTimeRangeGetEnd(sourceAudio.range).seconds) * 1000
            guard abs(audioStartDriftMs) <= 50 && abs(audioEndDriftMs) <= 50 else {
                throw MediaError.writerFailed("audio logical start/end drift exceeds project 50ms budget: \(audioStartDriftMs)/\(audioEndDriftMs)")
            }
            if let audioResult {
                // The production driver already asserts fed == emitted. Native
                // AAC 48k→44.1 SRC counts vary and movie edit-list rounding loses
                // sub-ms tails; record those counts without claiming parity.
                guard audioResult.nonFinite == 0 else { throw MediaError.writerFailed("non-finite audio model output") }
            } else {
                guard sourceAudio.sha256 == outputAudio.sha256 else { throw MediaError.writerFailed("audio passthrough changed") }
                audioPassthroughExact = true
            }
        }
        FileManager.default.createFile(atPath: regionURL.path, contents: nil)
        let regionHandle = try FileHandle(forWritingTo: regionURL)
        defer { try? regionHandle.close() }
        var regionFrames = 0, wholeFrameCount = 0, maxRegions = 0
        for pts in sourceVideo.presentationTimesUs {
            let t = pts / 1000, regions = edl.regions(at: t)
            let whole = edl.fullFrame(at: t)
            if !regions.isEmpty { regionFrames += 1 }
            if whole { wholeFrameCount += 1 }
            maxRegions = max(maxRegions, regions.count)
            let row: [String: Any] = ["pts_us": pts, "whole_frame": whole,
                "regions": regions.map { [Double($0.left), Double($0.top), Double($0.right), Double($0.bottom)] }]
            try regionHandle.write(contentsOf: JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) + Data([10]))
        }
        let analysis = analyzed.withLock { $0 }
        let edlObject = try JSONSerialization.jsonObject(with: edl.toJSONData()) as? [String: Any] ?? [:]
        var row: [String: Any] = ["run_utc": ISO8601DateFormatter().string(from: Date()), "input": input.lastPathComponent,
            "output": output.lastPathComponent, "who": args[4], "target": args[5], "music_gate": args[6], "mode": args[7],
            "scene_gate": ops.censorNsfw, "measurement_platform": "native M3 macOS; not physical iPhone",
            "os": ProcessInfo.processInfo.operatingSystemVersionString, "duration_s": source.duration.seconds,
            "probe_ms": probeMs, "branches_ms": branchesMs, "render_or_mux_ms": renderMs,
            "complete_job_ms": wholeMs, "job_rtf": wholeMs / 1000 / source.duration.seconds,
            "kernel_peak_phys_footprint_bytes": peak, "video_frames": sourceVideo.samples,
            "video_timestamps_exact": true, "video_passthrough": passthrough,
            "source_video_sha256": sourceVideo.sha256, "output_video_sha256": outputVideo.sha256,
            "decoded_source_audio_frames_44100": decodedSourceFrames, "decoded_output_audio_frames_44100": decodedOutputFrames,
            "audio_start_drift_ms": audioStartDriftMs, "audio_end_drift_ms": audioEndDriftMs,
            "audio_sample_counts_exact": decodedSourceFrames == decodedOutputFrames && (audioResult?.frames ?? decodedSourceFrames) == decodedSourceFrames,
            "audio_passthrough_compressed_bytes_exact": audioPassthroughExact,
            "audio_integrity_limit": "Native AAC/SRC sample counts vary; logical track start/end checked within existing 50ms budget; no PCM padding/trimming",
            "face_tracks": edl.faceTracks.count, "person_tracks": (edlObject["personTracks"] as? [Any])?.count ?? 0,
            "analysis_decoded_frames": analysis?.decodedFrames ?? 0, "analysis_sampled_frames": analysis?.sampledFrames ?? 0,
            "render_censored_frames": render?.censoredFrames ?? 0, "region_frames": regionFrames,
            "whole_frame_frames": wholeFrameCount, "maximum_simultaneous_regions": maxRegions,
            "source_integrity": "video frame/timestamp/geometry exact; audio logical track drift within 50ms",
            "thermal_state_end": ProcessInfo.processInfo.thermalState.rawValue]
        if let executable = Bundle.main.executableURL { row["compiled_executable_sha256"] = sha(try Data(contentsOf: executable)) }
        if let build = Bundle.main.url(forResource: "integration-build", withExtension: "json"),
           let provenance = try JSONSerialization.jsonObject(with: Data(contentsOf: build)) as? [String: Any] {
            row["production_sources"] = provenance
        }
        for (key, value) in branchTimes.withLock({ $0 }) { row[key] = value }
        if let audioResult { row["audio_separate_ms"] = audioResult.separateMs; row["audio_non_finite"] = audioResult.nonFinite; row["separator_emitted_frames"] = audioResult.frames }
        let url = URL(fileURLWithPath: args[3])
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }
        try handle.seekToEnd(); try handle.write(contentsOf: JSONSerialization.data(withJSONObject: row, options: [.sortedKeys]) + Data([10]))
        print("integration \(args[7]) \(args[5])/\(args[4]) gate=\(args[6]) \(input.lastPathComponent): \(String(format: "%.2f", wholeMs / 1000))s RTF=\(String(format: "%.3f", wholeMs / 1000 / source.duration.seconds)) peak=\(peak / 1_048_576)MiB frames=\(sourceVideo.samples) regions=\(regionFrames) whole=\(wholeFrameCount)")
    }
}
