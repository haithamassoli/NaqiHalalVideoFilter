import Foundation

enum AudioBenchIO {
    static let maxFrames = 44_100 * 120

    static func read(_ url: URL) throws -> [Float] {
        // Core Audio's FLOAT-WAV read on this host omitted tail frames from
        // libsndfile control files. These benchmarks accept one exact format,
        // so read its declared PCM payload rather than hide decoder truncation.
        try decode(Data(contentsOf: url))
    }

    static func decode(_ data: Data) throws -> [Float] {
        func u16(_ p: Int) -> Int { Int(data[p]) | Int(data[p + 1]) << 8 }
        func u32(_ p: Int) -> Int { u16(p) | u16(p + 2) << 16 }
        func tag(_ p: Int) -> String { String(decoding: data[p..<(p + 4)], as: UTF8.self) }
        guard data.count >= 12, tag(0) == "RIFF", tag(8) == "WAVE", u32(4) + 8 <= data.count else {
            throw NSError(domain: "modelbench", code: 2, userInfo: [NSLocalizedDescriptionKey: "Supply a complete little-endian RIFF WAV"])
        }
        let riffEnd = u32(4) + 8
        guard riffEnd >= 12 else { throw NSError(domain: "modelbench", code: 3) }
        var cursor = 12, validFormat = false, hasFormat = false
        var payload: Range<Int>?
        while cursor + 8 <= riffEnd {
            let size = u32(cursor + 4), start = cursor + 8
            guard size <= riffEnd - start else { throw NSError(domain: "modelbench", code: 3) }
            if tag(cursor) == "fmt " {
                guard size >= 16, !hasFormat else { throw NSError(domain: "modelbench", code: 3) }
                hasFormat = true
                let guid: [UInt8] = [3, 0, 0, 0, 0, 0, 16, 0, 128, 0, 0, 170, 0, 56, 155, 113]
                let floating = u16(start) == 3 || (u16(start) == 65534 && size >= 40
                    && u16(start + 16) >= 22 && Array(data[(start + 24)..<(start + 40)]) == guid)
                validFormat = floating && u16(start + 2) == 2 && u32(start + 4) == 44_100
                    && u32(start + 8) == 44_100 * 8 && u16(start + 12) == 8 && u16(start + 14) == 32
            } else if tag(cursor) == "data" {
                guard payload == nil else { throw NSError(domain: "modelbench", code: 3) }
                payload = start..<(start + size)
            }
            cursor = start + size + size % 2
        }
        guard cursor == riffEnd, validFormat, let payload, payload.count > 0, payload.count % 8 == 0,
              payload.count / 8 <= maxFrames else {
            throw NSError(domain: "modelbench", code: 3, userInfo: [NSLocalizedDescriptionKey: "Supply nonempty stereo float32 44.1 kHz WAV at most 120 s"])
        }
        var samples = [Float](repeating: 0, count: payload.count / 4)
        samples.withUnsafeMutableBytes { target in
            data.withUnsafeBytes { raw in target.copyMemory(from: UnsafeRawBufferPointer(rebasing: raw[payload])) }
        }
        guard !samples.isEmpty, samples.allSatisfy(\.isFinite) else { throw NSError(domain: "modelbench", code: 4) }
        return samples
    }

    static func selfCheck() throws {
        var wav = Data("RIFF".utf8)
        func u16(_ value: UInt16) { var n = value.littleEndian; withUnsafeBytes(of: &n) { wav.append(contentsOf: $0) } }
        func u32(_ value: UInt32) { var n = value.littleEndian; withUnsafeBytes(of: &n) { wav.append(contentsOf: $0) } }
        let samples: [Float] = [0, 0.25, -0.5, 1]
        u32(36 + 16); wav.append(Data("WAVEfmt ".utf8)); u32(16)
        u16(3); u16(2); u32(44_100); u32(44_100 * 8); u16(8); u16(32)
        wav.append(Data("data".utf8)); u32(16)
        samples.withUnsafeBytes { wav.append(contentsOf: $0) }
        guard try decode(wav) == samples else { throw NSError(domain: "modelbench", code: 5) }
        var rejected = false
        do { _ = try decode(Data(wav.dropLast())) } catch { rejected = true }
        guard rejected else { throw NSError(domain: "modelbench", code: 6) }
    }
}
