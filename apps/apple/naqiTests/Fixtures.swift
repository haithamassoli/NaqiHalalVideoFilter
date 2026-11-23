import AVFoundation
import Foundation
import Testing

/// Test media. Staged by `scripts/fetch-models.sh` from the Android repo's
/// `qa-assets/`, gitignored the same way the models are.
enum Fixtures {
    static var qaVideo: URL? {
        Bundle(for: BundleToken.self).url(forResource: "test-video", withExtension: "mp4")
    }

    /// A real, decodable `.m4a` with no video track — synthesized rather than
    /// staged, because the audio-only route has to be testable on a fresh clone
    /// that has no `qa-assets/`.
    ///
    /// A tone rather than silence: the separator clamps the normalizing standard
    /// deviation with `max(std, 1e-8)`, so a silent track takes a branch no real
    /// song does and would prove nothing about the one that matters. Mono on
    /// purpose too — that is `AudioDecoder.fold`'s duplicate-to-stereo case, and
    /// the format most shared audio arrives in.
    static func audioClip(_ name: String, seconds: Double = 3) throws -> URL {
        let rate = 44_100.0
        let url = scratch(name)
        let format = try #require(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1))
        let file = try AVAudioFile(forWriting: url, settings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: rate,
            AVNumberOfChannelsKey: 1,
        ])
        let frames = AVAudioFrameCount(rate * seconds)
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        let samples = try #require(buffer.floatChannelData?[0])
        for i in 0..<Int(frames) {
            samples[i] = 0.25 * sinf(2 * .pi * 440 * Float(i) / Float(rate))
        }
        try file.write(from: buffer)
        return url
    }

    /// Scratch directory for pipeline outputs, wiped per call.
    static func scratch(_ name: String) -> URL {
        let d = FileManager.default.temporaryDirectory.appendingPathComponent("naqi-tests", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        let u = d.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: u)
        return u
    }

    private final class BundleToken {}
}

/// Skips a test when the QA media has not been staged, instead of failing it —
/// the fixtures are gitignored, so a fresh clone legitimately lacks them.
func requireQAVideo() throws -> URL {
    try #require(Fixtures.qaVideo, "run scripts/fetch-models.sh to stage qa-assets")
}
