import Foundation

/// Join a video-only file and an audio-only file into one mp4. yt-dlp would
/// call ffmpeg for `bv*+ba`; AVFoundation is the Apple equivalent and keeps
/// ffmpeg out of the bundle.
enum MediaMux {
    static func merge(video: URL, audio: URL, into output: URL) async throws {
        try await Remux.mux(video: video, audio: audio, to: output)
    }
}
