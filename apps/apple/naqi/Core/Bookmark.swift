import Foundation

/// Security-scoped bookmarks, in the one shape this app needs them.
///
/// Two callers keep a URL across a relaunch — the queued job's source and
/// destination folder (`Job`), and the remembered export folder
/// (`ExportTarget`) — and each of them had its own `#if os(macOS)` pair. Four
/// blocks, two behaviours, and a platform difference that is genuinely only
/// about one option flag on each side.
extension URL {

    /// macOS requires the security-scope option on both sides of a bookmark and
    /// iOS rejects it, which is the whole platform difference.
    private static var scopeOptions: URL.BookmarkResolutionOptions {
        #if os(macOS)
        .withSecurityScope
        #else
        []
        #endif
    }

    /// A bookmark that survives a relaunch, or nil if the URL cannot make one.
    ///
    /// The scope is opened for the call: a URL that is only reachable inside
    /// one cannot be bookmarked outside it. Opening a scope that a caller
    /// already holds is balanced by the matching close and leaves theirs intact.
    func scopedBookmark() -> Data? {
        let scoped = startAccessingSecurityScopedResource()
        defer { if scoped { stopAccessingSecurityScopedResource() } }
        #if os(macOS)
        return try? bookmarkData(options: .withSecurityScope)
        #else
        return try? bookmarkData()
        #endif
    }

    /// Resolves a bookmark back to a URL, **without** opening its scope — the
    /// callers disagree about who owns that and for how long, so it stays
    /// theirs. A stale-but-resolvable bookmark is still returned: rewriting it
    /// buys nothing until the next save.
    static func resolvingScopedBookmark(_ data: Data?) -> URL? {
        guard let data else { return nil }
        var stale = false
        return try? URL(resolvingBookmarkData: data, options: scopeOptions,
                        relativeTo: nil, bookmarkDataIsStale: &stale)
    }
}
