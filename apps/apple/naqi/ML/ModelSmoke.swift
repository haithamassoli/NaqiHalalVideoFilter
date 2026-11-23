import Foundation
import os
import OnnxRuntimeBindings

/// Loads every bundled graph and runs one zero-tensor inference through it,
/// asserting the IO contract in `Models`. This is the Apple equivalent of
/// Android's `ModelSmoke.run()` and is the M0 gate: if the shapes here do not
/// match, nothing downstream can be trusted.
enum ModelSmoke {

    struct Result: Sendable, Identifiable {
        var id: String { model }
        let model: String
        let compute: String
        let loadMs: Double
        let inferMs: Double
        let detail: String
        let error: String?
        var ok: Bool { error == nil }
    }

    /// Nil runs every graph under its production configuration. Diagnostics can
    /// still force one provider across all graphs for a manual A/B.
    static func runAll(compute: ComputeUnit? = nil) -> [Result] {
        if let compute {
            return [nsfw(compute), genderAge(compute), yamNet(compute), demucs(compute)]
        }
        return [nsfw(.coreMLNeuralNetwork), genderAge(.xnnpack),
                yamNet(.xnnpack), demucs(.coreMLGPU)]
    }

    private static func timed(_ model: String, _ compute: ComputeUnit,
                              _ body: () throws -> (load: Double, infer: Double, detail: String)) -> Result {
        do {
            let r = try body()
            Log.ml.info("smoke \(model, privacy: .public): \(r.detail, privacy: .public)")
            return Result(model: model, compute: "\(compute)", loadMs: r.load,
                          inferMs: r.infer, detail: r.detail, error: nil)
        } catch {
            Log.ml.error("smoke \(model, privacy: .public) FAILED: \(error.localizedDescription, privacy: .public)")
            return Result(model: model, compute: "\(compute)", loadMs: 0, inferMs: 0,
                          detail: "", error: "\(error)")
        }
    }

    private static func ms(_ from: ContinuousClock.Instant) -> Double {
        from.duration(to: .now).milliseconds
    }

    static func nsfw(_ compute: ComputeUnit) -> Result {
        timed(Models.Nsfw.file, compute) {
            var t = ContinuousClock.now
            let m = try ModelRegistry.model(Models.Nsfw.file, compute: compute)
            let load = ms(t)
            let side = Models.Nsfw.side
            let x = try ORTValue.zeros(shape: [1, 3, side, side])
            t = ContinuousClock.now
            let out = try m.run([Models.Nsfw.input: x])
            let infer = ms(t)
            guard let y = out[Models.Nsfw.output] else { throw OrtError.outputMissing(Models.Nsfw.output) }
            let shape = y.shape
            guard shape == [1, 5] else { throw OrtError.shapeMismatch(expected: [1, 5], got: shape) }
            let probs = try y.floats()
            let sum = probs.reduce(0, +)
            return (load, infer, "\(shape) softmax-sum=\(String(format: "%.4f", sum))")
        }
    }

    static func genderAge(_ compute: ComputeUnit) -> Result {
        timed(Models.GenderAge.file, compute) {
            var t = ContinuousClock.now
            let m = try ModelRegistry.model(Models.GenderAge.file, compute: compute)
            let load = ms(t)
            let side = Models.GenderAge.side
            let x = try ORTValue.zeros(shape: [1, 3, side, side])
            t = ContinuousClock.now
            let out = try m.run([Models.GenderAge.input: x])
            let infer = ms(t)
            guard let y = out[Models.GenderAge.output] else { throw OrtError.outputMissing(Models.GenderAge.output) }
            let shape = y.shape
            guard shape == [1, 3] else { throw OrtError.shapeMismatch(expected: [1, 3], got: shape) }
            return (load, infer, "\(shape) \(try y.floats().map { String(format: "%.3f", $0) }.joined(separator: ","))")
        }
    }

    static func yamNet(_ compute: ComputeUnit) -> Result {
        timed(Models.YamNet.file, compute) {
            let Y = Models.YamNet.self
            var t = ContinuousClock.now
            let model = try ModelRegistry.model(Y.file, compute: compute)
            let load = ms(t)
            let input = try ORTValue.zeros(shape: [Y.frameSamples])
            t = ContinuousClock.now
            let out = try model.run([Y.input: input])
            let infer = ms(t)
            guard let scores = out[Y.output] else { throw OrtError.outputMissing(Y.output) }
            guard scores.shape == [1, Y.classes] else {
                throw OrtError.shapeMismatch(expected: [1, Y.classes], got: scores.shape)
            }
            return (load, infer, "\(scores.shape) finite=\(try scores.floats().allSatisfy { $0.isFinite })")
        }
    }

    /// htdemucs is the expensive one. The shipped fp32 graph is the parity path
    /// selected by D3; a zero input must still produce all-finite output.
    static func demucs(_ compute: ComputeUnit) -> Result {
        timed(Models.Demucs.file, compute) {
            let D = Models.Demucs.self
            var t = ContinuousClock.now
            let m = try ModelRegistry.model(D.file, compute: compute, threads: Ort.computeThreads)
            let load = ms(t)
            let wave = try ORTValue.zeros(shape: [1, D.channels, D.segmentFrames])
            let spec = try ORTValue.zeros(shape: [1, 4, D.specBins, D.specFrames])
            t = ContinuousClock.now
            let out = try m.run([D.waveInput: wave, D.specInput: spec])
            let infer = ms(t)
            guard let ws = out[D.waveOutput], let ss = out[D.specOutput] else {
                throw OrtError.outputMissing("\(D.waveOutput)/\(D.specOutput)")
            }
            let wShape = ws.shape, sShape = ss.shape
            guard wShape == [1, 4, D.channels, D.segmentFrames] else {
                throw OrtError.shapeMismatch(expected: [1, 4, D.channels, D.segmentFrames], got: wShape)
            }
            guard sShape == [1, 4, 4, D.specBins, D.specFrames] else {
                throw OrtError.shapeMismatch(expected: [1, 4, 4, D.specBins, D.specFrames], got: sShape)
            }
            let w = try ws.floats()
            let bad = w.contains { !$0.isFinite }
            // xRealtime > 1 means faster than playback. Android S23 baseline: 0.55x.
            let xRealtime = (Double(D.segmentFrames) / Double(D.sampleRate)) / (infer / 1000)
            return (load, infer,
                    "wave\(wShape) spec\(sShape) finite=\(!bad) \(String(format: "%.2f", xRealtime))x-realtime")
        }
    }
}
