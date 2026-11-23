import Testing
import Foundation
@testable import naqi

/// The M0 gate. If any of these fail, no downstream stage can be trusted:
/// the graphs derive reproducibly from Android's source artifacts, so a
/// shape/finiteness disagreement means the Apple runtime is doing something different.
@Suite("Model contracts", .serialized)
struct ModelContractTests {

    @Test("all bundled models are present")
    func modelsPresent() throws {
        for m in Models.bundled {
            #expect(Models.url(m) != nil, "missing \(m).onnx — run scripts/fetch-models.sh")
        }
    }

    @Test("smoke: every graph loads and runs on CPU")
    func smokeCPU() throws {
        for r in ModelSmoke.runAll(compute: .cpu) {
            #expect(r.ok, "\(r.model): \(r.error ?? "")")
            print("[smoke cpu] \(r.model) load=\(Int(r.loadMs))ms infer=\(Int(r.inferMs))ms \(r.detail)")
        }
    }

    @Test("simulator normalizes CoreML requests to the CPU reference")
    func simulatorProviderPolicy() {
        #if targetEnvironment(simulator)
        #expect(Ort.effectiveCompute(.coreMLGPU) == .cpu)
        #expect(Ort.effectiveCompute(.coreMLNeuralNetwork) == .cpu)
        #expect(Ort.effectiveCompute(.xnnpack) == .xnnpack)
        #else
        #expect(Ort.effectiveCompute(.coreMLGPU) == .coreMLGPU)
        #endif
    }

    @Test("production provider configuration smoke-loads every graph")
    func smokeProductionProviders() {
        for result in ModelSmoke.runAll() {
            #expect(result.ok == true, "\(result.model): \(result.error ?? "")")
        }
    }

    @Test("NSFW gate: 5 classes, softmax sums to 1")
    func nsfwContract() throws {
        let m = try ModelRegistry.model( Models.Nsfw.file)
        #expect(m.inputNames == [Models.Nsfw.input])
        #expect(m.outputNames == [Models.Nsfw.output])
        let side = Models.Nsfw.side
        let out = try m.run([Models.Nsfw.input: .zeros(shape: [1, 3, side, side])])
        let y = try #require(out[Models.Nsfw.output])
        #expect(y.shape == [1, 5])
        let p = try y.floats()
        #expect(abs(p.reduce(0, +) - 1.0) < 1e-4, "not a softmax: \(p)")
        #expect(p.allSatisfy { $0.isFinite })
    }

    @Test("genderage: [1,3] output")
    func genderAgeContract() throws {
        let m = try ModelRegistry.model( Models.GenderAge.file)
        #expect(m.inputNames == [Models.GenderAge.input])
        let side = Models.GenderAge.side
        let out = try m.run([Models.GenderAge.input: .zeros(shape: [1, 3, side, side])])
        let y = try #require(out[Models.GenderAge.output])
        #expect(y.shape == [1, 3])
        #expect(try y.floats().allSatisfy { $0.isFinite })
    }

    @Test("YAMNet: fixed waveform produces 521 finite scores")
    func yamNetContract() throws {
        let Y = Models.YamNet.self
        let model = try ModelRegistry.model(Y.file, compute: .xnnpack)
        #expect(model.executionCompute == .xnnpack)
        #expect(model.inputNames == [Y.input])
        #expect(model.outputNames == [Y.output])
        let out = try model.run([Y.input: .zeros(shape: [Y.frameSamples])])
        let scores = try #require(out[Y.output])
        #expect(scores.shape == [1, Y.classes])
        #expect(try scores.floats().allSatisfy { $0.isFinite })
    }

    @Test("htdemucs: fp32 graph produces finite output")
    func demucsFinite() throws {
        let D = Models.Demucs.self
        let m = try ModelRegistry.model( D.file)
        #expect(Set(m.inputNames) == [D.waveInput, D.specInput])

        // Non-zero input: zeros can be finite through a graph that still NaNs
        // on real signal, so drive it with a deterministic tone + noise-free ramp.
        var wave = [Float](repeating: 0, count: 2 * D.segmentFrames)
        for i in 0..<D.segmentFrames {
            let s = sinf(2 * .pi * 440 * Float(i) / Float(D.sampleRate)) * 0.5
            wave[i] = s
            wave[D.segmentFrames + i] = s
        }
        var spec = [Float](repeating: 0, count: 4 * D.specBins * D.specFrames)
        for i in stride(from: 0, to: spec.count, by: 7) { spec[i] = 0.01 }

        let out = try m.run([
            D.waveInput: .float(wave, shape: [1, D.channels, D.segmentFrames]),
            D.specInput: .float(spec, shape: [1, 4, D.specBins, D.specFrames]),
        ])
        let w = try #require(out[D.waveOutput])
        let s = try #require(out[D.specOutput])
        #expect(w.shape == [1, 4, D.channels, D.segmentFrames])
        #expect(s.shape == [1, 4, 4, D.specBins, D.specFrames])

        let wf = try w.floats()
        #expect(wf.allSatisfy { $0.isFinite }, "htdemucs wave output has NaN/Inf — the fp16 risk fired")
        let sf = try s.floats()
        #expect(sf.allSatisfy { $0.isFinite }, "htdemucs spec output has NaN/Inf")
        // A real separation must not be silent.
        #expect(wf.contains { abs($0) > 1e-6 }, "htdemucs produced digital silence")
    }
}
