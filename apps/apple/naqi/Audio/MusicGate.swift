import Foundation
import OnnxRuntimeBindings
import os

/// YAMNet's conservative answer to "is there music in this 2.6 s window?".
/// Serialized with `Demucs`: the input buffers are reused between calls.
final class MusicGate {
    typealias Infer = (UnsafePointer<Float>) throws -> Float

    static let threshold: Float = 0.15
    static let frame = Models.YamNet.frameSamples
    static let classes = Models.YamNet.classes
    private static let silencePeak: Float = 0.001 // -60 dBFS
    private static let ratio = Double(Models.Demucs.sampleRate) / Double(Models.YamNet.sampleRate)

    private let mono16k = UnsafeMutablePointer<Float>.zeroed(out16kLength(Demucs.seg) + 1)
    private let input = UnsafeMutablePointer<Float>.zeroed(frame)
    private let infer: Infer

    init(infer: @escaping Infer) {
        self.infer = infer
    }

    private convenience init(model: OrtModel) throws {
        let inputName = Models.YamNet.input, outputName = Models.YamNet.output
        guard model.inputNames.contains(inputName), model.outputNames.contains(outputName) else {
            throw OrtError.outputMissing("yamnet input/output")
        }
        let data = NSMutableData(length: Self.frame * MemoryLayout<Float>.size)!
        let value = try ORTValue(tensorData: data, elementType: .float,
                                 shape: [Self.frame].map(NSNumber.init(value:)))
        self.init { frame in
            data.mutableBytes.copyMemory(from: frame,
                                         byteCount: Self.frame * MemoryLayout<Float>.size)
            guard let output = try model.run([inputName: value], outputs: [outputName])[outputName]
            else { throw OrtError.outputMissing(outputName) }
            let scores = try output.tensorData().bytes.assumingMemoryBound(to: Float.self)
            return Self.musicScore(scores)
        }
    }

    deinit {
        mono16k.deallocate()
        input.deallocate()
    }

    /// Nil disables the gate and keeps the pre-gate behavior: separate every chunk.
    static func open() -> MusicGate? {
        do {
            return try MusicGate(model: ModelRegistry.model(
                Models.YamNet.file, compute: .xnnpack))
        } catch {
            ModelRegistry.evict(Models.YamNet.file)
            Log.audio.warning("yamnet unavailable; separating every chunk: \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    /// Maximum music score across tiled YAMNet frames. The last frame is flush
    /// with the end, so no tail is left unscored.
    func score(_ mono44k: UnsafePointer<Float>, frames: Int) throws -> Float {
        precondition(frames <= Demucs.seg)
        let n = Self.resampleTo16k(mono44k, frames: frames, into: mono16k)
        guard n > 0 else { return 0 }

        var peak: Float = 0
        for i in 0..<n { peak = max(peak, abs(mono16k[i])) }
        guard peak >= Self.silencePeak else { return 0 }

        var best: Float = 0
        var start = 0
        while true {
            let available = min(Self.frame, n - start)
            input.update(from: mono16k + start, count: available)
            for i in available..<Self.frame { input[i] = 0 }
            best = max(best, try infer(input))
            if best >= Self.threshold || start + Self.frame >= n { return best }
            start = min(start + Self.frame, n - Self.frame)
        }
    }

    /// The inclusive AudioSet music blocks: vocal music, then the main music block.
    static func musicScore(_ scores: UnsafePointer<Float>) -> Float {
        var best: Float = 0
        for i in 24...32 { best = max(best, scores[i]) }
        for i in 132...276 { best = max(best, scores[i]) }
        return best
    }

    static func out16kLength(_ frames: Int) -> Int {
        guard frames > 1 else { return 0 }
        return Int(Double(frames - 1) / ratio) + 1
    }

    @discardableResult
    static func resampleTo16k(_ src: UnsafePointer<Float>, frames: Int,
                              into dst: UnsafeMutablePointer<Float>) -> Int {
        let n = out16kLength(frames)
        for i in 0..<n {
            let x = Double(i) * ratio
            let i0 = Int(x)
            let f = Float(x - Double(i0))
            let a = src[i0]
            let b = i0 + 1 < frames ? src[i0 + 1] : a
            dst[i] = a + (b - a) * f
        }
        return n
    }
}
