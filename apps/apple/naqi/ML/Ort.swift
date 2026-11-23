import CryptoKit
import Foundation
import os
import OnnxRuntimeBindings

/// Which execution provider a session should try. Falling back to CPU is always
/// allowed — ORT partitions the graph and leaves unsupported nodes on CPU.
enum ComputeUnit: Sendable {
    /// CPU only. The reference path: matches Android numerics exactly.
    case cpu
    /// htdemucs: CoreML MLProgram on CPU+GPU. ANE compilation fails this graph.
    case coreMLGPU
    /// NSFW: CoreML NeuralNetwork with all compute units; MLProgram cannot parse it.
    case coreMLNeuralNetwork
    /// Small models only. XNNPACK is forbidden for htdemucs' spectral branch.
    case xnnpack
}

enum OrtError: Error, CustomStringConvertible {
    case modelMissing(String)
    case providerUnavailable(String)
    case shapeMismatch(expected: [Int], got: [Int])
    case outputMissing(String)

    var description: String {
        switch self {
        case .modelMissing(let n): "model not found in bundle: \(n)"
        case .providerUnavailable(let n): "execution provider unavailable: \(n)"
        case .shapeMismatch(let e, let g): "tensor shape mismatch: expected \(e), got \(g)"
        case .outputMissing(let n): "session produced no output named \(n)"
        }
    }
}

/// Process-wide ORT environment. ORT wants exactly one.
enum Ort {
    nonisolated(unsafe) static let env: ORTEnv = {
        // swiftlint:disable:next force_try — a failure here means the binary is broken.
        try! ORTEnv(loggingLevel: .warning)
    }()

    static var coreMLAvailable: Bool { ORTIsCoreMLExecutionProviderAvailable() }
    static let xnnpackThreads = 4

    static func effectiveCompute(_ requested: ComputeUnit) -> ComputeUnit {
        #if targetEnvironment(simulator)
        // CoreML registers in Simulator but has no ANE, its GPU path throws
        // Espresso/MPSGraph errors, and every measured case was slower than CPU.
        switch requested {
        case .coreMLGPU, .coreMLNeuralNetwork: .cpu
        case .cpu, .xnnpack: requested
        }
        #else
        requested
        #endif
    }

    /// Logical CPUs at the highest performance level.
    ///
    /// **Never size intra-op threads from `activeProcessorCount`.** It returns 6
    /// on an A19 Pro (2 P + 4 E) and 10–16 on an M-series, and every intra-op
    /// barrier then waits on the slowest thread in it. Android swept this and
    /// found 6 threads beat 8 by ~5 % on an S23 for exactly that reason; the
    /// P/E gap on Apple silicon is wider, so an E-core inside a barrier costs
    /// more here, not less.
    static let performanceCores: Int = {
        var n: Int32 = 0
        var size = MemoryLayout<Int32>.size
        if sysctlbyname("hw.perflevel0.logicalcpu", &n, &size, nil, 0) == 0, n > 0 { return Int(n) }
        return max(1, ProcessInfo.processInfo.activeProcessorCount / 2)
    }()

    /// Intra-op threads for a compute-heavy graph, clamped to the range the
    /// Android sweep found useful.
    static var computeThreads: Int { threadOverride ?? min(max(performanceCores, 2), 6) }

    /// The calibration knob for the sweep above.
    ///
    /// Thread count is not only a speed dial: ORT's memory-pattern planner
    /// allocates per intra-op thread, and htdemucs peaks **over** the 1.5 GB
    /// budget (`BenchTests.demucsFootprint`). Whether fewer threads buys that
    /// back is a question about this machine's memory hierarchy, which no
    /// amount of reading answers — so the knob exists to be measured through,
    /// on hardware, rather than reasoned about.
    ///
    /// ponytail: plain global, no lock. Only the bench sweep writes it, and
    /// `BenchTests` is `.serialized`. If production ever sets it, this needs to
    /// become a real setting.
    nonisolated(unsafe) static var threadOverride: Int?
}

/// A loaded ONNX graph plus its IO names. Not an actor: ORT sessions are
/// thread-safe for concurrent `Run` calls, and making this an actor would
/// serialize inference that we explicitly want to overlap with decode.
final class OrtModel: @unchecked Sendable {
    private static let hashCache = OSAllocatedUnfairLock(initialState: [String: String]())
    private static let coreMLCacheGeneration = "ort-1.24.2-v1"
    let name: String
    let session: ORTSession
    let inputNames: [String]
    let outputNames: [String]
    let compute: ComputeUnit
    let executionCompute: ComputeUnit
    let threads: Int

    /// - Parameters:
    ///   - threads: intra-op threads. 1 matches Android (`ml/Models.kt` pins it
    ///     to 1 with spinning disabled) and keeps N concurrent sessions from
    ///     oversubscribing the P-cores.
    convenience init(bundledModel name: String, compute: ComputeUnit = .cpu, threads: Int = 1,
                     disableArena: Bool = false) throws {
        guard let path = Bundle.main.path(forResource: name, ofType: "onnx", inDirectory: "Models")
                ?? Bundle.main.path(forResource: name, ofType: "onnx") else {
            throw OrtError.modelMissing(name)
        }
        try self.init(name: name, path: path, compute: compute, threads: threads,
                      disableArena: disableArena)
    }

    /// - Parameter disableArena: drops ORT's CPU arena and memory-pattern
    ///   planner. **Off by default and it should stay that way** — the arena is
    ///   what makes repeated allocation cheap, and the per-frame graphs (nsfw,
    ///   genderage) run thousands of times per job where htdemucs runs once per
    ///   chunk. Only the graph that actually breaks the memory budget pays the
    ///   allocator cost. See `NaqiOrtArena.h`.
    init(name: String, path: String, compute: ComputeUnit, threads: Int = 1,
         disableArena: Bool = false) throws {
        let loadStarted = ContinuousClock.now
        self.name = name
        let requested = Ort.effectiveCompute(compute)
        self.compute = requested
        self.threads = threads

        var resolved = requested
        let opts: ORTSessionOptions
        do {
            opts = try Self.sessionOptions(name: name, path: path, compute: requested,
                                           threads: threads, disableArena: disableArena)
        } catch {
            guard requested != .cpu else { throw error }
            Log.ml.warning("""
                \(name, privacy: .public): \(String(describing: requested), privacy: .public) EP rejected \
                (\(error.localizedDescription, privacy: .public)); CPU
                """)
            resolved = .cpu
            opts = try Self.sessionOptions(name: name, path: path, compute: .cpu,
                                           threads: threads, disableArena: disableArena)
        }

        let loaded: ORTSession
        do {
            loaded = try ORTSession(env: Ort.env, modelPath: path, sessionOptions: opts)
        } catch {
            guard resolved != .cpu else { throw error }
            // CoreML often accepts its provider options and fails later while
            // compiling ORTSession. The fallback has to cover that point too.
            Log.ml.warning("""
                \(name, privacy: .public): \(String(describing: resolved), privacy: .public) session failed \
                (\(error.localizedDescription, privacy: .public)); CPU
                """)
            resolved = .cpu
            let cpu = try Self.sessionOptions(name: name, path: path, compute: .cpu,
                                              threads: threads, disableArena: disableArena)
            loaded = try ORTSession(env: Ort.env, modelPath: path, sessionOptions: cpu)
        }
        self.executionCompute = resolved
        self.session = loaded
        self.inputNames = try loaded.inputNames()
        self.outputNames = try loaded.outputNames()
        Log.ml.info("loaded \(name, privacy: .public) requested=\(String(describing: requested), privacy: .public) resolved=\(String(describing: resolved), privacy: .public) wall=\(Int(msSince(loadStarted)))ms in=\(self.inputNames, privacy: .public) out=\(self.outputNames, privacy: .public)")
    }

    private static func sessionOptions(name: String, path: String, compute: ComputeUnit,
                                       threads: Int, disableArena: Bool) throws -> ORTSessionOptions {
        let opts = try ORTSessionOptions()
        try opts.setLogSeverityLevel(.warning)
        try opts.setGraphOptimizationLevel(.all)
        try opts.setIntraOpNumThreads(Int32(threads))
        // A spinning worker on Apple silicon *holds* a P-core between chunks,
        // which matters more here than the battery cost did on Android.
        try opts.addConfigEntry(withKey: "session.intra_op.allow_spinning", value: "0")
        // Keeps htdemucs' 173 MB of initializers out of the arena.
        try opts.addConfigEntry(withKey: "session.use_device_allocator_for_initializers", value: "1")

        // ...and for htdemucs that is not enough. Android had to disable the
        // CPU arena and the memory-pattern planner outright on this same graph
        // or lmkd killed it at 5.6 GB RSS; the iOS equivalent is a jetsam kill
        // with no warning. The comment that used to live here said Apple's
        // footprint "is fine so far" and deferred the shim — that was M0
        // reading its own `phys_footprint` as unreadable. It was not, and this
        // graph peaks well over the budget. `NaqiOrtArena` is that shim.
        if disableArena, !NaqiOrtDisableArena(opts) {
            Log.ml.warning("""
                could not disable the ORT arena for \(name, privacy: .public) — \
                the session is valid but will exceed the memory budget
                """)
        }

        switch compute {
        case .cpu:
            break
        case .xnnpack:
            try opts.appendExecutionProvider(
                "XNNPACK", providerOptions: ["intra_op_num_threads": "\(Ort.xnnpackThreads)"])
        case .coreMLGPU, .coreMLNeuralNetwork:
            guard Ort.coreMLAvailable else {
                throw OrtError.providerUnavailable("CoreML")
            }
            let cache = try coreMLCacheDirectory(modelPath: path)
            let providerOptions = [
                // htdemucs must never attempt ANE. The small NSFW graph won as
                // NeuralNetwork/ALL in the measured provider sweep.
                "MLComputeUnits": compute == .coreMLGPU ? "CPUAndGPU" : "ALL",
                "ModelFormat": compute == .coreMLNeuralNetwork ? "NeuralNetwork" : "MLProgram",
                "RequireStaticInputShapes": "1",
                "ModelCacheDirectory": cache.path,
                "AllowLowPrecisionAccumulationOnGPU": "0",
            ]
            try opts.appendCoreMLExecutionProvider(withOptionsV2: providerOptions)
        }
        return opts
    }

    private static func coreMLCacheDirectory(modelPath: String) throws -> URL {
        let sha = try sha256(modelPath)
        let support = try FileManager.default.url(for: .applicationSupportDirectory,
                                                  in: .userDomainMask,
                                                  appropriateFor: nil, create: true)
        var cache = support.appendingPathComponent(
            "CoreMLCache/\(coreMLCacheGeneration)/\(sha)", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? cache.setResourceValues(values)
        return cache
    }

    private static func sha256(_ path: String) throws -> String {
        if let cached = hashCache.withLock({ $0[path] }) { return cached }
        let started = ContinuousClock.now
        let handle = try FileHandle(forReadingFrom: URL(fileURLWithPath: path))
        defer { try? handle.close() }
        var hasher = SHA256()
        while let block = try handle.read(upToCount: 1024 * 1024), !block.isEmpty {
            hasher.update(data: block)
        }
        let value = hasher.finalize().map { String(format: "%02x", $0) }.joined()
        hashCache.withLock { $0[path] = value }
        Log.ml.info("model hash \((path as NSString).lastPathComponent, privacy: .public) \(Int(msSince(started)))ms")
        return value
    }

    func run(_ inputs: [String: ORTValue], outputs: Set<String>? = nil) throws -> [String: ORTValue] {
        try session.run(withInputs: inputs,
                        outputNames: outputs ?? Set(outputNames),
                        runOptions: nil)
    }
}

/// Process-wide cache of loaded graphs.
///
/// htdemucs alone is 173 MB on disk and roughly 1.3 GB of working set once
/// resident, so loading it twice is not a slow path — it is an out-of-memory
/// kill on a phone. Every consumer goes through here.
enum ModelRegistry {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var cache: [String: OrtModel] = [:]

    /// The lock is held across construction, not just the dictionary access.
    /// Two threads calling `CreateSession` on the same graph concurrently
    /// segfaults inside ORT, and the cost of serialising is one 300 ms load per
    /// model per process.
    ///
    /// **One resident session per file, never per configuration.** Keying the
    /// cache on `(file, compute, threads)` looks harmless and is not: callers
    /// that disagreed about `threads` produced three concurrent htdemucs
    /// sessions and a 6.4 GB footprint against a 1.5 GB budget, which SIGKILLed
    /// the test host. A request with a different configuration replaces the
    /// resident one rather than joining it.
    static func model(_ file: String, compute: ComputeUnit = .cpu, threads: Int = 1) throws -> OrtModel {
        let compute = Ort.effectiveCompute(compute)
        lock.lock()
        defer { lock.unlock() }
        if let m = cache[file] {
            if m.compute == compute && m.threads == threads { return m }
            Log.ml.notice("""
                \(file, privacy: .public): reconfiguring \
                \(String(describing: m.compute), privacy: .public)/\(m.threads) -> \
                \(String(describing: compute), privacy: .public)/\(threads); evicting the old session
                """)
            cache[file] = nil
        }
        // Keyed on the graph, not passed by the caller: "this graph's arena
        // exceeds the memory budget" is a fact about htdemucs, not about who is
        // loading it. A parameter would be one call site away from being
        // forgotten, and the symptom would be a jetsam kill on a long job.
        let built = try OrtModel(bundledModel: file, compute: compute, threads: threads,
                                 disableArena: file == Models.Demucs.file)
        cache[file] = built
        return built
    }

    /// Drops one graph. Call after a job finishes with htdemucs so its ~1.3 GB
    /// arena is not held while the user is just browsing — `AudioPipeline`
    /// does exactly that at its shared lifetime boundary.
    static func evict(_ file: String) {
        lock.lock(); defer { lock.unlock() }
        cache[file] = nil
    }

    /// Drops everything. Only `BenchTests` uses this, to get a clean baseline
    /// before measuring a footprint; production evicts by file.
    static func evictAll() {
        lock.lock(); defer { lock.unlock() }
        cache.removeAll()
    }
}

// MARK: - Tensor helpers

extension ORTValue {
    /// Wraps a float buffer as a tensor. `data` is copied into the NSMutableData
    /// ORT takes ownership of, so the caller's storage can be reused immediately.
    static func float(_ data: [Float], shape: [Int]) throws -> ORTValue {
        let count = shape.reduce(1, *)
        precondition(data.count == count, "float tensor: \(data.count) values for shape \(shape)")
        let bytes = data.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress!, length: $0.count * 4) }
        return try ORTValue(tensorData: bytes,
                            elementType: .float,
                            shape: shape.map(NSNumber.init(value:)))
    }

    /// Zero tensor of the given shape — the smoke-test input.
    static func zeros(shape: [Int]) throws -> ORTValue {
        try .float([Float](repeating: 0, count: shape.reduce(1, *)), shape: shape)
    }

    var shape: [Int] {
        (try? tensorTypeAndShapeInfo().shape.map(\.intValue)) ?? []
    }

    /// Copies the tensor payload out as floats.
    func floats() throws -> [Float] {
        let d = try tensorData() as Data
        return d.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }
}
