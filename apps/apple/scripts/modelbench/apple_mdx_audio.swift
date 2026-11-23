import Accelerate
import AVFoundation
import CoreML
import CryptoKit
import Foundation

// Fixed Voc_FT frontend, matching audio_compare.py. Benchmark only: bounded
// short WAVs, no production media/job/export integration.
final class MDXTransform {
    static let fft = 7680, bins = 3072, times = 256, hop = 1024
    static let chunk = hop * (times - 1), trim = fft / 2
    static let spectrumCount = 4 * bins * times
    let forwardSetup: vDSP_DFT_Setup
    let inverseSetup: vDSP_DFT_Setup
    let window: [Float]
    let envelope: [Float]
    var real = [Float](repeating: 0, count: fft)
    var imaginary = [Float](repeating: 0, count: fft)
    var transformedReal = [Float](repeating: 0, count: fft)
    var transformedImaginary = [Float](repeating: 0, count: fft)

    init() throws {
        guard let forward = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(Self.fft), .FORWARD) else {
            throw NSError(domain: "modelbench", code: 20, userInfo: [NSLocalizedDescriptionKey: "Accelerate DFT7680 unsupported"])
        }
        guard let inverse = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(Self.fft), .INVERSE) else {
            vDSP_DFT_DestroySetup(forward)
            throw NSError(domain: "modelbench", code: 20, userInfo: [NSLocalizedDescriptionKey: "Accelerate inverse DFT7680 unsupported"])
        }
        forwardSetup = forward; inverseSetup = inverse
        window = (0..<Self.fft).map { Float(0.5 - 0.5 * cos(2 * .pi * Double($0) / Double(Self.fft))) }
        var norm = [Float](repeating: 0, count: Self.fft + Self.chunk)
        for time in 0..<Self.times {
            for i in 0..<Self.fft { norm[time * Self.hop + i] += window[i] * window[i] }
        }
        envelope = norm
    }

    deinit { vDSP_DFT_DestroySetup(forwardSetup); vDSP_DFT_DestroySetup(inverseSetup) }

    func spectrum(_ left: [Float], _ right: [Float], zeroLowBins: Bool = true) -> [Float] {
        precondition(left.count == Self.chunk && right.count == Self.chunk)
        var packed = [Float](repeating: 0, count: Self.spectrumCount)
        for channel in 0..<2 {
            let samples = channel == 0 ? left : right
            for time in 0..<Self.times {
                for i in 0..<Self.fft {
                    let position = time * Self.hop + i - Self.trim
                    let reflected = position < 0 ? -position : (position >= Self.chunk ? 2 * Self.chunk - 2 - position : position)
                    real[i] = samples[reflected] * window[i]
                }
                vDSP_vclr(&imaginary, 1, vDSP_Length(Self.fft))
                vDSP_DFT_Execute(forwardSetup, real, imaginary, &transformedReal, &transformedImaginary)
                for frequency in (zeroLowBins ? 3 : 0)..<Self.bins {
                    packed[((2 * channel) * Self.bins + frequency) * Self.times + time] = transformedReal[frequency]
                    packed[((2 * channel + 1) * Self.bins + frequency) * Self.times + time] = transformedImaginary[frequency]
                }
            }
        }
        return packed
    }

    func inverse(_ packed: [Float]) throws -> (left: [Float], right: [Float]) {
        guard packed.count == Self.spectrumCount, packed.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 21) }
        var channels: [[Float]] = []
        for channel in 0..<2 {
            var accumulated = [Float](repeating: 0, count: envelope.count)
            for time in 0..<Self.times {
                vDSP_vclr(&real, 1, vDSP_Length(Self.fft))
                vDSP_vclr(&imaginary, 1, vDSP_Length(Self.fft))
                for frequency in 0..<Self.bins {
                    let re = packed[((2 * channel) * Self.bins + frequency) * Self.times + time]
                    let im = frequency == 0 ? 0 : packed[((2 * channel + 1) * Self.bins + frequency) * Self.times + time]
                    real[frequency] = re; imaginary[frequency] = im
                    if frequency > 0 { real[Self.fft - frequency] = re; imaginary[Self.fft - frequency] = -im }
                }
                vDSP_DFT_Execute(inverseSetup, real, imaginary, &transformedReal, &transformedImaginary)
                for i in 0..<Self.fft {
                    accumulated[time * Self.hop + i] += transformedReal[i] / Float(Self.fft) * window[i]
                }
            }
            var output = [Float](repeating: 0, count: Self.chunk)
            for i in output.indices {
                let p = i + Self.trim
                guard envelope[p] > 0 else { throw NSError(domain: "modelbench", code: 22) }
                output[i] = accumulated[p] / envelope[p]
            }
            channels.append(output)
        }
        return (channels[0], channels[1])
    }
}

@main
struct NativeMDXAudioBench {
    static func elapsed(_ t: ContinuousClock.Instant) -> Double {
        let d = t.duration(to: .now)
        return Double(d.components.seconds) * 1000 + Double(d.components.attoseconds) / 1e15
    }

    static func separate(_ input: [Float], transform: MDXTransform, compensation: Float,
                         predict: ([Float]) throws -> [Float]) throws -> [Float] {
        let frames = input.count / 2
        let generated = MDXTransform.chunk - 2 * MDXTransform.trim
        let padding = generated + MDXTransform.trim - frames % generated
        let total = frames + MDXTransform.trim + padding
        var result = [Float](repeating: 0, count: 2 * total)
        var divider = [Float](repeating: 0, count: total)
        for offset in stride(from: 0, to: total, by: MDXTransform.chunk * 3 / 4) {
            let length = min(MDXTransform.chunk, total - offset)
            var left = [Float](repeating: 0, count: MDXTransform.chunk)
            var right = left
            for i in 0..<length {
                let original = offset + i - MDXTransform.trim
                if original >= 0 && original < frames {
                    left[i] = input[2 * original]; right[i] = input[2 * original + 1]
                }
            }
            let reconstructed = try transform.inverse(predict(transform.spectrum(left, right)))
            for i in 0..<length {
                let weight = length > 1 ? Float(0.5 - 0.5 * cos(2 * .pi * Double(i) / Double(length - 1))) : 1
                result[2 * (offset + i)] += reconstructed.left[i] * weight
                result[2 * (offset + i) + 1] += reconstructed.right[i] * weight
                divider[offset + i] += weight
            }
        }
        var output = [Float](repeating: 0, count: input.count)
        for i in 0..<frames {
            let p = i + MDXTransform.trim
            guard divider[p] > 0 else { throw NSError(domain: "modelbench", code: 23) }
            output[2 * i] = result[2 * p] / divider[p] * compensation
            output[2 * i + 1] = result[2 * p + 1] / divider[p] * compensation
        }
        guard output.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 24) }
        return output
    }

    static func selfCheck() throws {
        try AudioBenchIO.selfCheck()
        let transform = try MDXTransform()
        let wave = (0..<MDXTransform.fft).map { Float(sin(2 * .pi * 13 * Double($0) / Double(MDXTransform.fft))) }
        var zero = [Float](repeating: 0, count: wave.count), fr = zero, fi = zero, recovered = zero, residual = zero
        vDSP_DFT_Execute(transform.forwardSetup, wave, zero, &fr, &fi)
        vDSP_DFT_Execute(transform.inverseSetup, fr, fi, &recovered, &residual)
        let dftError = zip(wave, recovered).map { abs($0 - $1 / Float(MDXTransform.fft)) }.max()!
        guard dftError < 2e-5 else { throw NSError(domain: "modelbench", code: 25) }
        let sine = (0..<MDXTransform.chunk).map { Float(0.1 * sin(2 * .pi * 77 * Double($0) / Double(MDXTransform.fft))) }
        let reconstructed = try transform.inverse(transform.spectrum(sine, sine, zeroLowBins: false))
        let stftError = (MDXTransform.fft..<(MDXTransform.chunk - MDXTransform.fft)).map { abs(sine[$0] - reconstructed.left[$0]) }.max()!
        guard stftError < 2e-5 else { throw NSError(domain: "modelbench", code: 26) }
        for count in [1, 137, 300001] {
            let input = [Float](repeating: 0, count: 2 * count)
            let output = try separate(input, transform: transform, compensation: 1, predict: { $0 })
            guard output.count == input.count, output.allSatisfy({ $0 == 0 }) else { throw NSError(domain: "modelbench", code: 27) }
        }
        let waveFrames = 300001
        let interleaved = (0..<(2 * waveFrames)).map { Float(0.1 * sin(2 * .pi * 77 * Double($0 / 2) / Double(MDXTransform.fft))) }
        let rebuilt = try separate(interleaved, transform: transform, compensation: 1, predict: { $0 })
        let olaError = (MDXTransform.fft..<(waveFrames - MDXTransform.fft)).map { abs(rebuilt[2 * $0] - interleaved[2 * $0]) }.max()!
        guard olaError < 2e-5 else { throw NSError(domain: "modelbench", code: 33) }
        print("DFT7680 inverse scale / periodic-Hann centered STFT / nonzero OLA identity / silence + short + final-window passed; max errors \(dftError), \(stftError), \(olaError)")
    }

    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 2 && args[1] == "--self-check" { try selfCheck(); return }
        guard args.count == 6 || args.count == 8 else {
            throw NSError(domain: "modelbench", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "Usage: apple_mdx_audio MODEL.mlpackage INPUT.wav cpu|gpu|ane|all OUTPUT.wav RESULTS.jsonl [--export-spectrum OUTPUT.f32.bin]"])
        }
        let units: [String: MLComputeUnits] = ["cpu": .cpuOnly, "gpu": .cpuAndGPU, "ane": .cpuAndNeuralEngine, "all": .all]
        guard let compute = units[args[3]], args.count == 6 || args[6] == "--export-spectrum" else { throw NSError(domain: "modelbench", code: 1) }
        let paths = [args[2], args[4], args[5]] + (args.count == 8 ? [args[7]] : [])
        guard Set(paths.map { URL(fileURLWithPath: $0).standardizedFileURL.path }).count == paths.count else {
            throw NSError(domain: "modelbench", code: 34, userInfo: [NSLocalizedDescriptionKey: "Input, output, results and spectrum paths must differ"])
        }
        let fullStart = ContinuousClock.now
        let inputURL = URL(fileURLWithPath: args[2])
        let input = try AudioBenchIO.read(inputURL)
        let frames = input.count / 2
        let readMs = elapsed(fullStart)
        let loadStart = ContinuousClock.now
        let source = URL(fileURLWithPath: args[1])
        let compiled = source.pathExtension == "mlmodelc" ? source : try MLModel.compileModel(at: source)
        let configuration = MLModelConfiguration(); configuration.computeUnits = compute
        let model = try MLModel(contentsOf: compiled, configuration: configuration)
        let loadMs = elapsed(loadStart)
        let transform = try MDXTransform()
        let value = try MLMultiArray(shape: [1, 4, 3072, 256], dataType: .float16)
        let expectedStrides = [4 * 3072 * 256, 3072 * 256, 256, 1]
        guard value.strides.map(\.intValue) == expectedStrides else { throw NSError(domain: "modelbench", code: 28) }
        let provider = try MLDictionaryFeatureProvider(dictionary: ["input": MLFeatureValue(multiArray: value)])
        var inferenceMs: [Double] = [], firstSpectrumExported = false
        let predict: ([Float]) throws -> [Float] = { spectrum in
            if args.count == 8 && !firstSpectrumExported {
                try spectrum.withUnsafeBytes { try Data($0).write(to: URL(fileURLWithPath: args[7])) }
                firstSpectrumExported = true
            }
            let start = ContinuousClock.now
            let pointer = value.dataPointer.assumingMemoryBound(to: Float16.self)
            for i in spectrum.indices { pointer[i] = Float16(spectrum[i]) }
            let prediction = try model.prediction(from: provider)
            guard let output = prediction.featureValue(for: "output")?.multiArrayValue,
                  output.shape.map(\.intValue) == [1, 4, 3072, 256], output.dataType == .float16 else { throw NSError(domain: "modelbench", code: 29) }
            let strides = output.strides.map(\.intValue), source = output.dataPointer.assumingMemoryBound(to: Float16.self)
            var result = [Float](repeating: 0, count: MDXTransform.spectrumCount)
            for channel in 0..<4 {
                for frequency in 0..<3072 {
                    for time in 0..<256 {
                        result[(channel * 3072 + frequency) * 256 + time] = Float(source[channel * strides[1] + frequency * strides[2] + time * strides[3]])
                    }
                }
            }
            guard result.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 30) }
            inferenceMs.append(elapsed(start))
            return result
        }
        let separationStart = ContinuousClock.now
        let outputSamples = try separate(input, transform: transform, compensation: 1.021, predict: predict)
        let separatorMs = elapsed(separationStart)
        let outputURL = URL(fileURLWithPath: args[4])
        try FileManager.default.createDirectory(at: outputURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Explicit scope closes/finalizes the WAV before external integrity checks.
        try autoreleasepool {
            let output = try AVAudioFile(forWriting: outputURL, settings: [AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 2, AVLinearPCMBitDepthKey: 32,
                AVLinearPCMIsFloatKey: true, AVLinearPCMIsNonInterleaved: false], commonFormat: .pcmFormatFloat32, interleaved: true)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: output.processingFormat, frameCapacity: AVAudioFrameCount(frames)),
                  let pointer = buffer.floatChannelData?[0] else { throw NSError(domain: "modelbench", code: 31) }
            buffer.frameLength = AVAudioFrameCount(frames)
            outputSamples.withUnsafeBufferPointer { pointer.update(from: $0.baseAddress!, count: $0.count) }
            try output.write(from: buffer)
        }
        let completeMs = elapsed(fullStart)
        let reloaded = try AudioBenchIO.read(outputURL)
        guard reloaded.count == input.count else { throw NSError(domain: "modelbench", code: 32) }
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
            task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
        } }
        let sorted = inferenceMs.sorted(), seconds = Double(frames) / 44_100
        let row: [String: Any] = ["candidate": "vocft", "provider": "native-coreml-\(args[3])",
            "device": "Apple M3 MacBook Air 24 GB", "scope": "native Swift/Accelerate/Core ML WAV audio stage; not iPhone or complete video job",
            "input": inputURL.lastPathComponent, "input_sha256": SHA256.hash(data: try Data(contentsOf: inputURL)).map { String(format: "%02x", $0) }.joined(),
            "output": outputURL.lastPathComponent, "source_frames": frames, "output_frames": outputSamples.count / 2,
            "duration_s": seconds, "read_ms": readMs, "compile_and_load_ms": loadMs,
            "separator_ms": separatorMs, "complete_audio_stage_ms": completeMs,
            "separator_rtf": separatorMs / 1000 / seconds, "complete_audio_stage_rtf": completeMs / 1000 / seconds,
            "infer_including_io_samples_ms": inferenceMs, "infer_including_io_p50_ms": sorted[sorted.count / 2],
            "kernel_peak_phys_footprint_bytes": status == KERN_SUCCESS ? info.ledger_phys_footprint_peak : 0,
            "fft": 7680, "bins": 3072, "times": 256, "hop": 1024, "chunk": 261120, "outer_stride": 195840,
            "compensation": 1.021, "gate": false, "stem": "vocals", "non_finite": 0,
            "spectrum_export_in_timing": args.count == 8, "measured_at": ISO8601DateFormatter().string(from: Date()),
            "os": ProcessInfo.processInfo.operatingSystemVersionString]
        let bytes = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        let resultsURL = URL(fileURLWithPath: args[5])
        try FileManager.default.createDirectory(at: resultsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: resultsURL.path) { FileManager.default.createFile(atPath: resultsURL.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: resultsURL); defer { try? handle.close() }
        try handle.seekToEnd(); try handle.write(contentsOf: bytes + Data([10]))
        print("native Voc_FT \(args[3]) \(inputURL.lastPathComponent) frames=\(frames) stage=\(Int(completeMs))ms separatorRTF=\(String(format: "%.3f", separatorMs / 1000 / seconds)) peak=\(info.ledger_phys_footprint_peak / 1_048_576)MiB")
    }
}
