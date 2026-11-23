import Foundation
import os

/// Where finished copies go, remembered between runs.
///
/// Deliberately **not** a field on `FilterOps`. The ops tuple is exactly what
/// `Checkpoint.key(source:ops:)` hashes, so folding the destination into it
/// would mean "save this one somewhere else" throws away a half-finished film's
/// checkpointed work — the destination is not read until `publish`, the last
/// stage of every shape (`spec-jobs-ui.md` §5.1).
struct ExportTarget: Sendable, Equatable {
    var destination: Destination = .photos
    /// The folder `.userFolder` publishes into, security-scoped and open.
    /// `nil` until the user has picked one, which is why choosing `.userFolder`
    /// is not by itself enough to start a job.
    var folder: URL?

    var folderName: String? { folder?.lastPathComponent }
}

extension ExportTarget {
    private static let destinationKey = "naqi.destination"
    private static let folderKey = "naqi.destinationFolder"

    /// App Group suite for the same reason `FilterOps` uses it: a shared-in
    /// video inherits the last-used settings and the share extension is a
    /// different process with a different standard suite. Falls back to
    /// `.standard` where the group is unavailable (macOS without the
    /// entitlement, unit tests).
    private static var store: UserDefaults { AppGroup.defaults ?? .standard }

    /// Resolves the remembered folder and **opens its security scope without
    /// ever closing it**.
    ///
    /// A consumed sandbox extension is process-wide, not URL-wide, and that is
    /// load-bearing here: the folder travels to `Publish` inside a `Job` that
    /// round-trips through `naqi-queue.json`, and a decoded `URL` carries no
    /// scope of its own. Holding this one open for the life of the process is
    /// what lets a job resumed after a relaunch still write where it promised.
    /// Called once, from `Flow.init`.
    static func loadLastUsed() -> ExportTarget {
        let saved = store.string(forKey: destinationKey)
            .flatMap(Destination.init(rawValue:)) ?? .photos
        guard let data = store.data(forKey: folderKey) else {
            // A `.userFolder` choice with no bookmark cannot publish anywhere,
            // so it is not a choice worth restoring.
            return ExportTarget()
        }
        guard let url = URL.resolvingScopedBookmark(data) else {
            // The folder was deleted or moved off a volume we can reach.
            // Falling back to Photos rather than keeping a dead folder
            // selected: Start would otherwise be enabled for a job that dies at
            // publish, which is the one failure the user cannot act on.
            Log.app.notice("export folder bookmark no longer resolves")
            return ExportTarget()
        }
        _ = url.startAccessingSecurityScopedResource()
        return ExportTarget(destination: saved, folder: url)
    }

    func saveAsLastUsed() {
        Self.store.set(destination.rawValue, forKey: Self.destinationKey)
        guard let folder else {
            Self.store.removeObject(forKey: Self.folderKey)
            return
        }
        // A folder we cannot bookmark still works for this run; it simply is
        // not remembered. Dropping the whole pick on the floor because the
        // *memory* failed would be the worse trade.
        if let data = folder.scopedBookmark() {
            Self.store.set(data, forKey: Self.folderKey)
        } else {
            Log.app.notice("export folder could not be bookmarked")
        }
    }
}
