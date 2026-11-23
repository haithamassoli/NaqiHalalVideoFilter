import CoreML
import CryptoKit
import Foundation

// A real spectrogram from the reference separator, rather than a zero input.
// Input/output are flat little-endian float32 in the model's declared layout.
@main
struct CoreMLAudioBench {
    static func main() async throws {
        let args = CommandLine.arguments
        let units: [String: MLComputeUnits] = ["cpu": .cpuOnly, "gpu": .cpuAndGPU,
                                              "ane": .cpuAndNeuralEngine, "all": .all]
        guard args.count == 6, let compute = units[args[3]] else {
            throw NSError(domain: "modelbench", code: 1, userInfo: [NSLocalizedDescriptionKey:
                "Usage: apple_coreml_audio MODEL.mlpackage INPUT.f32 cpu|gpu|ane|all OUTPUT.f32 RESULTS.jsonl"])
        }
        guard Set([args[2], args[4], args[5]].map { URL(fileURLWithPath: $0).standardizedFileURL.path }).count == 3 else {
            throw NSError(domain: "modelbench", code: 10, userInfo: [NSLocalizedDescriptionKey: "Input, output and results paths must differ"])
        }
        let clock = ContinuousClock()
        func ms(_ start: ContinuousClock.Instant) -> Double {
            let d = clock.now - start
            return Double(d.components.seconds) * 1000 + Double(d.components.attoseconds) / 1e15
        }
        let compileStart = clock.now
        let source = URL(fileURLWithPath: args[1])
        let compiled = source.pathExtension == "mlmodelc" ? source : try await MLModel.compileModel(at: source)
        let compileMs = ms(compileStart)
        let configuration = MLModelConfiguration()
        configuration.computeUnits = compute
        let loadStart = clock.now
        let model = try MLModel(contentsOf: compiled, configuration: configuration)
        let loadMs = ms(loadStart)
        guard let entry = model.modelDescription.inputDescriptionsByName.first,
              let constraint = entry.value.multiArrayConstraint,
              model.modelDescription.inputDescriptionsByName.count == 1 else {
            throw NSError(domain: "modelbench", code: 2)
        }
        let input = try MLMultiArray(shape: constraint.shape, dataType: constraint.dataType)
        let bytes = try Data(contentsOf: URL(fileURLWithPath: args[2]))
        let shape = [1, 4, 3072, 256]
        let denseStrides = [4 * 3072 * 256, 3072 * 256, 256, 1]
        guard bytes.count == input.count * 4, input.shape.map(\.intValue) == shape,
              input.strides.map(\.intValue) == denseStrides else {
            throw NSError(domain: "modelbench", code: 3)
        }
        try bytes.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Float.self)
            guard samples.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 4) }
            switch input.dataType {
            case .float16:
                let pointer = input.dataPointer.assumingMemoryBound(to: Float16.self)
                for i in samples.indices { pointer[i] = Float16(samples[i]) }
            case .float32:
                input.dataPointer.copyMemory(from: samples.baseAddress!, byteCount: bytes.count)
            default:
                throw NSError(domain: "modelbench", code: 5)
            }
        }
        let provider = try MLDictionaryFeatureProvider(dictionary: [entry.key: MLFeatureValue(multiArray: input)])
        var latencies: [Double] = []
        var output: MLMultiArray?
        for _ in 0..<6 {
            let start = clock.now
            let prediction = try await model.prediction(from: provider)
            guard let name = prediction.featureNames.sorted().first,
                  let array = prediction.featureValue(for: name)?.multiArrayValue else {
                throw NSError(domain: "modelbench", code: 6)
            }
            // Materialize the result before stopping the clock.
            _ = array.dataPointer
            latencies.append(ms(start))
            output = array
        }
        guard let output, output.shape.map(\.intValue) == shape else { throw NSError(domain: "modelbench", code: 7) }
        var result = [Float](repeating: 0, count: output.count)
        let strides = output.strides.map(\.intValue)
        for channel in 0..<4 {
            for frequency in 0..<3072 {
                for time in 0..<256 {
                    let logical = (channel * 3072 + frequency) * 256 + time
                    let physical = channel * strides[1] + frequency * strides[2] + time * strides[3]
                    switch output.dataType {
                    case .float16: result[logical] = Float(output.dataPointer.assumingMemoryBound(to: Float16.self)[physical])
                    case .float32: result[logical] = output.dataPointer.assumingMemoryBound(to: Float.self)[physical]
                    default: throw NSError(domain: "modelbench", code: 8)
                    }
                }
            }
        }
        guard result.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 9) }
        try result.withUnsafeBytes { try Data($0).write(to: URL(fileURLWithPath: args[4])) }
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let status = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        let warm = Array(latencies.dropFirst()).sorted()
        var planCounts: [String: Int] = [:]
        var planCosts: [String: Double] = [:]
        var planStatus = "unsupported"
        do {
            let plan = try await MLComputePlan.load(contentsOf: compiled, configuration: configuration)
            func name(_ device: MLComputeDevice?) -> String {
                switch device {
                case .cpu: "CPU"
                case .gpu: "GPU"
                case .neuralEngine: "ANE"
                case nil: "unknown"
                @unknown default: "unknown"
                }
            }
            func visit(_ block: MLModelStructure.Program.Block) {
                for op in block.operations {
                    let device = name(plan.deviceUsage(for: op)?.preferred)
                    planCounts[device, default: 0] += 1
                    if let cost = plan.estimatedCost(of: op) { planCosts[device, default: 0] += cost.weight }
                    for nested in op.blocks { visit(nested) }
                }
            }
            if case .program(let program) = plan.modelStructure {
                for function in program.functions.values { visit(function.block) }
                planStatus = "preferred devices and relative estimated operation costs; not measured execution/rail activity"
            } else if case .neuralNetwork(let network) = plan.modelStructure {
                for layer in network.layers { planCounts[name(plan.deviceUsage(for: layer)?.preferred), default: 0] += 1 }
                planStatus = "preferred devices per layer; costs unavailable; not measured execution/rail activity"
            }
        } catch { planStatus = "unavailable: \(error)" }
        let row: [String: Any] = ["model": source.lastPathComponent, "input": args[2],
            "input_sha256": SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(),
            "measured_at": ISO8601DateFormatter().string(from: Date()),
            "measurement_platform": "native macOS; not physical iPhone", "compute_units": args[3],
            "placement_verified": false, "input_shape": input.shape.map(\.intValue),
            "compute_plan_status": planStatus, "compute_plan_preferred_operation_counts": planCounts,
            "compute_plan_preferred_relative_costs": planCosts,
            "input_data_type": input.dataType.rawValue, "output_shape": output.shape.map(\.intValue),
            "output_data_type": output.dataType.rawValue, "compile_ms": compileMs, "model_load_ms": loadMs,
            "first_prediction_ms": latencies[0], "warm_prediction_samples_ms": Array(latencies.dropFirst()),
            "warm_prediction_p50_ms": warm[warm.count / 2], "non_finite": 0,
            "kernel_peak_phys_footprint_bytes": status == KERN_SUCCESS ? info.ledger_phys_footprint_peak : 0,
            "os": ProcessInfo.processInfo.operatingSystemVersionString,
            "scope": "model-only, real reference input; excludes STFT/ISTFT/chunking/media export"]
        let json = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        let url = URL(fileURLWithPath: args[5])
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !FileManager.default.fileExists(atPath: url.path) { FileManager.default.createFile(atPath: url.path, contents: nil) }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd(); try handle.write(contentsOf: json + Data([10]))
        print("\(args[3]) compile=\(Int(compileMs))ms load=\(Int(loadMs))ms first=\(Int(latencies[0]))ms warm=\(Int(warm[warm.count / 2]))ms peak=\(info.ledger_phys_footprint_peak / 1_048_576)MiB")
    }
}
