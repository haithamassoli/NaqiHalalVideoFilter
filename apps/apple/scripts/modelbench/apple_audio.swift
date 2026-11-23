import AVFoundation
import CryptoKit
import Foundation
import OnnxRuntimeBindings

// Compiled alongside Naqi's actual Demucs/STFT/Ort sources. One process per
// provider and input keeps the kernel memory high-water mark interpretable.
@main
struct AppleAudioBench {
    static func hash(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var sha = SHA256()
        while let bytes = try handle.read(upToCount: 1_048_576), !bytes.isEmpty { sha.update(data: bytes) }
        return sha.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static func peakBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return status == KERN_SUCCESS ? UInt64(max(0, info.ledger_phys_footprint_peak)) : 0
    }

    static func main() throws {
        let args = CommandLine.arguments
        guard args.count == 6 || args.count == 7,
              ["cpu", "coreMLGPU"].contains(args[3]),
              args.count == 6 || args[6] == "gate" else {
            throw NSError(domain: "modelbench", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "Usage: apple_audio MODEL_DIRECTORY INPUT.wav cpu|coreMLGPU OUTPUT.wav RESULTS.jsonl [gate]"])
        }
        guard Set([args[2], args[4], args[5]].map { URL(fileURLWithPath: $0).standardizedFileURL.path }).count == 3 else {
            throw NSError(domain: "modelbench", code: 8, userInfo: [NSLocalizedDescriptionKey: "Input, output and results paths must differ"])
        }
        let modelDirectory = URL(fileURLWithPath: args[1], isDirectory: true)
        let inputURL = URL(fileURLWithPath: args[2])
        let modelURL = modelDirectory.appendingPathComponent(Models.Demucs.file + ".onnx")
        let requested: ComputeUnit = args[3] == "cpu" ? .cpu : .coreMLGPU
        let withGate = args.count == 7
        let fullStart = ContinuousClock.now
        let samples = try AudioBenchIO.read(inputURL)
        let frames = samples.count / 2
        // Match AudioStats: Float stereo fold, Double moments, Bessel variance.
        var sum = 0.0, square = 0.0
        for i in 0..<frames {
            let mono = Double(0.5 * (samples[2 * i] + samples[2 * i + 1]))
            guard mono.isFinite else { throw NSError(domain: "modelbench", code: 4) }
            sum += mono; square += mono * mono
        }
        let mean = sum / Double(frames)
        let std = max(0, (square - sum * sum / Double(frames)) / Double(max(1, frames - 1))).squareRoot()
        let preprocessingMs = msSince(fullStart)
        let loadStart = ContinuousClock.now
        let model = try OrtModel(name: Models.Demucs.file, path: modelURL.path,
                                 compute: requested, threads: Ort.computeThreads, disableArena: true)
        let modelLoadMs = msSince(loadStart)
        let D = Models.Demucs.self
        let wavData = NSMutableData(length: 2 * D.segmentFrames * 4)!
        let specData = NSMutableData(length: Demucs.specSize * 4)!
        let wavValue = try ORTValue(tensorData: wavData, elementType: .float,
                                    shape: [1, 2, D.segmentFrames].map(NSNumber.init(value:)))
        let specValue = try ORTValue(tensorData: specData, elementType: .float,
                                     shape: [1, 4, D.specBins, D.specFrames].map(NSNumber.init(value:)))
        var latencyMs: [Double] = []
        let infer: Demucs.Infer = { wav, spec, specSum, timeSum in
            wavData.mutableBytes.copyMemory(from: wav, byteCount: 2 * D.segmentFrames * 4)
            specData.mutableBytes.copyMemory(from: spec, byteCount: Demucs.specSize * 4)
            let start = ContinuousClock.now
            let outputs = try model.run([D.waveInput: wavValue, D.specInput: specValue])
            latencyMs.append(msSince(start))
            guard let spectral = outputs[D.specOutput], let temporal = outputs[D.waveOutput],
                  spectral.shape == [1, 4, 4, D.specBins, D.specFrames],
                  temporal.shape == [1, 4, 2, D.segmentFrames] else {
                throw OrtError.outputMissing("Demucs output names/shapes")
            }
            let sd = try spectral.tensorData(), td = try temporal.tensorData()
            let sp = sd.bytes.assumingMemoryBound(to: Float.self) + D.Stem.vocals.rawValue * Demucs.specSize
            let tp = td.bytes.assumingMemoryBound(to: Float.self) + D.Stem.vocals.rawValue * 2 * D.segmentFrames
            guard UnsafeBufferPointer(start: sp, count: Demucs.specSize).allSatisfy(\.isFinite),
                  UnsafeBufferPointer(start: tp, count: 2 * D.segmentFrames).allSatisfy(\.isFinite) else {
                throw NSError(domain: "modelbench", code: 5, userInfo: [NSLocalizedDescriptionKey: "Non-finite model output"])
            }
            specSum.update(from: sp, count: Demucs.specSize)
            timeSum.update(from: tp, count: 2 * D.segmentFrames)
        }
        var gateScores: [Float] = []
        var scorer: Demucs.MusicScore?
        if withGate {
            let gateModel = try OrtModel(name: Models.YamNet.file,
                                         path: modelDirectory.appendingPathComponent(Models.YamNet.file + ".onnx").path,
                                         compute: .xnnpack)
            let data = NSMutableData(length: MusicGate.frame * 4)!
            let value = try ORTValue(tensorData: data, elementType: .float,
                                     shape: [MusicGate.frame].map(NSNumber.init(value:)))
            let gate = MusicGate { samples in
                data.mutableBytes.copyMemory(from: samples, byteCount: MusicGate.frame * 4)
                guard let output = try gateModel.run([Models.YamNet.input: value])[Models.YamNet.output]
                else { throw OrtError.outputMissing(Models.YamNet.output) }
                let scores = try output.tensorData()
                return MusicGate.musicScore(scores.bytes.assumingMemoryBound(to: Float.self))
            }
            scorer = { samples, n in
                let score = try gate.score(samples, frames: n)
                gateScores.append(score)
                return score
            }
        }
        let outputURL = URL(fileURLWithPath: args[4])
        try FileManager.default.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let output = try AVAudioFile(forWriting: outputURL, settings: [
            AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 32,
            AVLinearPCMIsFloatKey: true, AVLinearPCMIsNonInterleaved: false
        ], commonFormat: .pcmFormatFloat32, interleaved: true)
        var decisions: [[String: Any]] = []
        var priorInferCount = 0
        let separationStart = ContinuousClock.now
        let separator = Demucs(mean: Float(mean), std: Float(std), estimatedFrames: frames,
                               infer: infer, musicScore: scorer,
                               onChunk: { done, _ in
            let start = max(0, (done - 1) * Demucs.stride - Demucs.maxShift)
            let end = min(frames, (done - 1) * Demucs.stride + Demucs.seg - Demucs.maxShift)
            decisions.append(["chunk": done - 1, "start_s": Double(start) / 44_100,
                              "end_s": Double(max(start, end)) / 44_100,
                              "separated": latencyMs.count > priorInferCount])
            priorInferCount = latencyMs.count
        }, emit: { pcm, n in
            guard let b = AVAudioPCMBuffer(pcmFormat: output.processingFormat,
                                           frameCapacity: AVAudioFrameCount(n)), let p = b.floatChannelData?[0] else {
                throw NSError(domain: "modelbench", code: 6)
            }
            b.frameLength = AVAudioFrameCount(n)
            p.update(from: pcm, count: 2 * n)
            try output.write(from: b)
        })
        try samples.withUnsafeBufferPointer { buffer in
            for start in stride(from: 0, to: frames, by: 4096) {
                try separator.feed(buffer.baseAddress! + 2 * start, frames: min(4096, frames - start))
            }
        }
        try separator.finish()
        let separationMs = msSince(separationStart), fullMs = msSince(fullStart)
        guard separator.emitted == frames, separator.nonFinite == 0 else {
            throw NSError(domain: "modelbench", code: 7, userInfo: [NSLocalizedDescriptionKey: "Invalid output length/finite count"])
        }
        let sorted = latencyMs.sorted()
        let seconds = Double(frames) / 44_100
        let peak = peakBytes()
        let row: [String: Any] = [
            "schema_version": 1, "measured_at": ISO8601DateFormatter().string(from: Date()),
            "model_id": D.file, "model_sha256": try hash(modelURL), "input": inputURL.lastPathComponent,
            "input_sha256": try hash(inputURL), "output": outputURL.lastPathComponent,
            "measurement_platform": "native macOS; not physical iPhone", "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "runtime": "ONNX Runtime 1.24.2", "requested_provider": args[3],
            "resolved_provider_configuration": String(describing: model.executionCompute),
            "placement_verified": false, "threads": Ort.computeThreads,
            "gate_enabled": withGate, "keep_stems": ["vocals"], "frames": frames,
            "duration_s": seconds, "preprocessing_ms": preprocessingMs, "model_load_ms": modelLoadMs,
            "separation_and_pcm_write_ms": separationMs, "complete_audio_stage_ms": fullMs,
            "separation_rtf": separationMs / 1000 / seconds, "complete_audio_stage_rtf": fullMs / 1000 / seconds,
            "stft_ms": separator.stftMs, "inference_ms": separator.inferMs,
            "ola_ms": separator.olaMs, "gate_ms": separator.gateMs,
            "chunks": separator.chunksDone, "skipped_chunks": separator.skippedChunks,
            "non_finite": separator.nonFinite, "emitted_frames": separator.emitted,
            "infer_samples_ms": latencyMs, "infer_p50_ms": sorted.isEmpty ? 0 : sorted[sorted.count / 2],
            "infer_p90_ms": sorted.isEmpty ? 0 : sorted[min(sorted.count - 1, Int(Double(sorted.count - 1) * 0.9))],
            "kernel_peak_phys_footprint_bytes": peak, "gate_scores": gateScores, "chunk_decisions": decisions,
            "thermal_state_end": ProcessInfo.processInfo.thermalState.rawValue,
            "timing_scope": "WAV decode + normalization + model load + separation + PCM WAV write; excludes video decode/render/AAC mux"
        ]
        let json = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        let resultsURL = URL(fileURLWithPath: args[5])
        try FileManager.default.createDirectory(at: resultsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: resultsURL.path) { FileManager.default.createFile(atPath: resultsURL.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: resultsURL)
        defer { try? handle.close() }
        try handle.seekToEnd(); try handle.write(contentsOf: json + Data([10]))
        print("\(args[3])→\(model.executionCompute) \(inputURL.lastPathComponent) gate=\(withGate) RTF=\(String(format: "%.3f", separationMs / 1000 / seconds)) load=\(Int(modelLoadMs))ms peak=\(peak / 1_048_576)MiB skipped=\(separator.skippedChunks)/\(separator.chunksDone)")
    }
}
