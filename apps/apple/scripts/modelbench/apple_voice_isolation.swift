import AVFoundation
import AudioToolbox
import Foundation

// Native iOS 18+ candidate; run on the Mac for screening, not phone timing.
// Build with apple_audio_io.swift, then run --self-check before measuring.
@main
struct VoiceIsolationBench {
    static func run(input: URL, output: URL) throws -> [String: Any] {
        let fullStarted = ContinuousClock.now
        guard input.standardizedFileURL != output.standardizedFileURL else {
            throw NSError(domain: "VoiceIsolation", code: 4,
                          userInfo: [NSLocalizedDescriptionKey: "Input and output must differ"])
        }
        let pcm = try AudioBenchIO.read(input)
        let frames = AVAudioFramePosition(pcm.count / 2)
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 2)!
        let sourceBuffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames))!
        sourceBuffer.frameLength = sourceBuffer.frameCapacity
        for channel in 0..<2 {
            for frame in 0..<Int(frames) { sourceBuffer.floatChannelData![channel][frame] = pcm[2 * frame + channel] }
        }
        var description = AudioComponentDescription(
            componentType: kAudioUnitType_Effect,
            componentSubType: kAudioUnitSubType_AUSoundIsolation,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0, componentFlagsMask: 0)
        guard AudioComponentFindNext(nil, &description) != nil else {
            throw NSError(domain: "VoiceIsolation", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "AUSoundIsolation unavailable"])
        }
        let started = ContinuousClock.now
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let effect = AVAudioUnitEffect(audioComponentDescription: description)
        for (address, value) in [(kAUSoundIsolationParam_WetDryMixPercent, Float(100)),
                                 (kAUSoundIsolationParam_SoundToIsolate, Float(kAUSoundIsolationSoundType_HighQualityVoice))] {
            let status = AudioUnitSetParameter(effect.audioUnit, address,
                                              kAudioUnitScope_Global, 0, value, 0)
            guard status == noErr else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        }
        engine.attach(player)
        engine.attach(effect)
        engine.connect(player, to: effect, format: format)
        engine.connect(effect, to: engine.mainMixerNode, format: format)
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        player.scheduleBuffer(sourceBuffer, at: nil)
        try engine.start()
        defer { engine.stop() }
        player.play()
        let loadMs = milliseconds(started.duration(to: .now))
        let delay = AVAudioFramePosition((effect.auAudioUnit.latency * format.sampleRate).rounded())
        var outputSettings = format.settings
        outputSettings[AVLinearPCMIsNonInterleaved] = false
        let sink = try AVAudioFile(forWriting: output, settings: outputSettings,
                                  commonFormat: .pcmFormatFloat32, interleaved: false)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4096)!
        let renderStarted = ContinuousClock.now
        var rendered: AVAudioFramePosition = 0
        var written: AVAudioFramePosition = 0
        var nonFinite = 0
        while rendered < frames + delay {
            let count = AVAudioFrameCount(min(4096, frames + delay - rendered))
            let status = try engine.renderOffline(count, to: buffer)
            guard status == .success else {
                throw NSError(domain: "VoiceIsolation.render", code: Int(status.rawValue))
            }
            let skip = Int(max(0, min(Int64(buffer.frameLength), delay - rendered)))
            let kept = Int(buffer.frameLength) - skip
            for channel in 0..<Int(format.channelCount) {
                let samples = buffer.floatChannelData![channel]
                memmove(samples, samples + skip, kept * MemoryLayout<Float>.size)
                nonFinite += (0..<kept).reduce(0) { $0 + (samples[$1].isFinite ? 0 : 1) }
            }
            rendered += Int64(buffer.frameLength)
            buffer.frameLength = AVAudioFrameCount(kept)
            if kept > 0 { try sink.write(from: buffer) }
            written += Int64(kept)
        }
        let renderMs = milliseconds(renderStarted.duration(to: .now))
        guard written == frames, nonFinite == 0 else {
            throw NSError(domain: "VoiceIsolation.integrity", code: 2)
        }
        let fullMs = milliseconds(fullStarted.duration(to: .now))
        var usage = rusage()
        let memoryStatus = getrusage(RUSAGE_SELF, &usage)
        return ["candidate": "Apple AUSoundIsolation HighQualityVoice", "platform": "macOS",
                "host": "Apple M3 24GB", "os": ProcessInfo.processInfo.operatingSystemVersionString,
                "measured_at": ISO8601DateFormatter().string(from: Date()),
                "runtime": "Apple AudioUnit", "compute_placement_verified": false,
                "peak_rss_bytes": memoryStatus == 0 ? usage.ru_maxrss : -1,
                "memory_definition": "fresh-process getrusage ru_maxrss bytes on macOS; differs from physical footprint",
                "input": input.lastPathComponent, "sample_rate": format.sampleRate,
                "channels": format.channelCount, "input_frames": frames,
                "input_decode": "exact RIFF stereo float32 PCM payload",
                "output_frames": written, "non_finite_samples": nonFinite,
                "reported_latency_seconds": effect.auAudioUnit.latency,
                "reported_tail_seconds": effect.auAudioUnit.tailTime,
                "reported_latency_frames_trimmed": delay, "load_ms": loadMs,
                "render_ms": renderMs, "complete_audio_stage_ms": fullMs,
                "complete_audio_stage_rtf": fullMs / 1000 / (Double(frames) / format.sampleRate),
                "rtf": renderMs / 1000 / (Double(frames) / format.sampleRate),
                "output": output.lastPathComponent]
    }

    static func milliseconds(_ duration: Duration) -> Double {
        Double(duration.components.seconds) * 1000 + Double(duration.components.attoseconds) / 1e15
    }

    static func main() throws {
        let args = CommandLine.arguments
        if args.count == 2, args[1] == "--self-check" {
            let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            let input = folder.appendingPathComponent("silence.wav")
            let output = folder.appendingPathComponent("output.wav")
            let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 2)!
            let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 44100 + 257)!
            buffer.frameLength = buffer.frameCapacity
            for channel in 0..<2 { buffer.floatChannelData![channel].initialize(repeating: 0, count: Int(buffer.frameLength)) }
            do {
                var settings = format.settings
                settings[AVLinearPCMIsNonInterleaved] = false
                let writer = try AVAudioFile(forWriting: input, settings: settings,
                                            commonFormat: .pcmFormatFloat32, interleaved: false)
                try writer.write(from: buffer)
            }
            _ = try run(input: input, output: output)
            let result = try AVAudioFile(forReading: output)
            precondition(result.length == 44100 + 257)
            print("PASS silence, nonfinite guard and final partial buffer")
            return
        }
        guard args.count == 3 else {
            throw NSError(domain: "VoiceIsolation", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "Usage: voice_isolation input.wav output.wav"])
        }
        let row = try run(input: URL(fileURLWithPath: args[1]), output: URL(fileURLWithPath: args[2]))
        let data = try JSONSerialization.data(withJSONObject: row, options: [.sortedKeys])
        print(String(decoding: data, as: UTF8.self))
    }
}
