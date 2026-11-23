import AVFoundation
import CoreMedia
import Foundation
import os
#if os(iOS)
import UIKit
#endif

/// Cuts a finished video into parts a `PublishPreset` accepts.
///
/// Parts are passthrough copies cut on keyframes (seconds for a film, no
/// quality loss), so a part usually ends a little before the limit. They live
/// in `Documents/Parts/`, named `<stem>-<preset id>-<n>.mp4`, which is how
/// `existingParts` finds them again after the sheet closes. Not Photos: the app
/// holds add-only access there and could never delete them (plan §3.4).
enum Splitter {

    static var root: URL { OutputLibrary.root.appendingPathComponent("Parts", isDirectory: true) }

    /// Parts aim this far under the limit. A passthrough part's tracks run a
    /// hair past its edit list (measured 10.067 s for a 10 s cut), and a
    /// receiver that reads the tracks rather than the edit list sees that:
    /// a 90.001 s part made WhatsApp trim to 90 s on Android.
    static let headroom = CMTime(value: 1, timescale: 2)

    /// Parts already made for `stem` under `preset`, in order; empty when none.
    static func existingParts(stem: String, preset: PublishPreset) -> [URL] {
        let prefix = "\(stem)-\(preset.id)-"
        let files = (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: nil)) ?? []
        return files
            .compactMap { url -> (Int, URL)? in
                let name = url.lastPathComponent
                guard name.hasPrefix(prefix), name.hasSuffix(".mp4"),
                      let n = Int(name.dropFirst(prefix.count).dropLast(4)) else { return nil }
                return (n, url)
            }
            .sorted { $0.0 < $1.0 }
            .map(\.1)
    }

    /// Writes every part of `source` for `preset`. All or nothing: a failure
    /// or cancel part-way deletes the parts already written, so `Parts/` never
    /// holds half a set.
    @concurrent
    static func split(_ source: URL, stem: String, preset: PublishPreset) async throws -> [URL] {
        guard let max = preset.maxSegment else { return [] }
        let asset = AVURLAsset(url: source)
        // Without this the parts cannot be written without a re-encode — the
        // -11838 case — and a re-encode is what this feature exists to avoid.
        guard await AVAssetExportSession.compatibility(ofExportPreset: AVAssetExportPresetPassthrough,
                                                       with: asset, outputFileType: .mp4)
        else { throw MediaError.writerFailed("passthrough cannot write this source to MP4") }
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            throw MediaError.noVideoTrack
        }
        let duration = try await asset.load(.duration)
        let limit = CMTime(seconds: Double(max.components.seconds), preferredTimescale: 600) - headroom
        let starts = cutPoints(keyframes: try await keyframes(track), duration: duration, max: limit)

        let end = await beginBackgroundTask()
        defer { Task { @MainActor in end() } }

        delete(existingParts(stem: stem, preset: preset))
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        var made: [URL] = []
        do {
            for (i, start) in starts.enumerated() {
                try Task.checkCancellation()
                let stop = i + 1 < starts.count ? starts[i + 1] : duration
                let temp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("part-\(UUID().uuidString).mp4")
                try await Remux.copy(source, range: CMTimeRange(start: start, end: stop), to: temp)
                let dest = root.appendingPathComponent("\(stem)-\(preset.id)-\(i + 1).mp4")
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.moveItem(at: temp, to: dest)
                made.append(dest)
            }
        } catch {
            delete(made)
            throw error
        }
        Log.media.info("split into \(made.count) parts for \(preset.id, privacy: .public)")
        return made
    }

    /// Instant and promptless — they are our own files. The caller re-lists
    /// afterwards, so whatever could not be deleted stays visible.
    static func delete(_ parts: [URL]) {
        parts.forEach { try? FileManager.default.removeItem(at: $0) }
    }

    /// Every video keyframe's presentation time, ascending.
    ///
    /// Cursor timestamps ignore the file's own edit list: with B-frames the
    /// keyframes read 0.067 s, 2.067 s … instead of 0, 2 …, so each goes
    /// through the track's segments before it becomes a cut point (plan §3.3).
    static func keyframes(_ track: AVAssetTrack) async throws -> [CMTime] {
        // ponytail: sample cursors only. A track that cannot provide them
        // fails the split; add an AVAssetReader scan if that shows up.
        guard try await track.load(.canProvideSampleCursors),
              let cursor = track.makeSampleCursorAtFirstSampleInDecodeOrder()
        else { throw MediaError.readerFailed("no sample cursor for the video track") }
        let segments = try await track.load(.segments).filter { !$0.isEmpty }.map(\.timeMapping)

        var times: [CMTime] = []
        repeat {
            guard cursor.currentSampleSyncInfo.sampleIsFullSync.boolValue else { continue }
            let pts = cursor.presentationTimeStamp
            if segments.isEmpty {
                times.append(pts)
            } else if let map = segments.first(where: { $0.source.containsTime(pts) }) {
                times.append(map.target.start + (pts - map.source.start))
            }
        } while cursor.stepInDecodeOrder(byCount: 1) == 1
        return times.sorted()
    }

    /// A split takes seconds; the ~30 s background grant covers leaving the app mid-way.
    @MainActor
    private static func beginBackgroundTask() -> @MainActor () -> Void {
        #if os(iOS)
        let id = UIApplication.shared.beginBackgroundTask(withName: "split")
        return { UIApplication.shared.endBackgroundTask(id) }
        #else
        return {}
        #endif
    }
}
