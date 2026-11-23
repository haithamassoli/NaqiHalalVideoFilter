import CoreMedia
import Foundation

/// One platform's rules for "prepare to post". Every difference between
/// platforms lives in these fields: the splitter and the sheet never ask
/// *which* platform it is, so a changed limit is a one-line edit to `all`.
///
/// iOS cannot aim a share at one app (`docs/publish-presets-plan-ios.md` §3.1),
/// so a preset decides only how the video is prepared; the system share sheet
/// does the rest.
struct PublishPreset: Sendable, Equatable, Identifiable {
    /// Stable; part files are named with it, which is how parts are found again.
    let id: String
    let label: LocalizedStringResource
    /// Longest part the platform accepts. Nil = no limit: shared as is, never copied.
    let maxSegment: Duration?
    /// Can one share carry every part? False = each part is shared on its own.
    let supportsMultipleSegments: Bool
    /// Most movies the platform's share extension takes at once; the sheet
    /// drops the app entirely past it (§3.5). Nil = no cap.
    var maxItemsPerShare: Int? = nil
    /// URL schemes the app registers, to tell whether it is installed. Each
    /// must also be listed under `LSApplicationQueriesSchemes`.
    var schemes: [String] = []

    /// Derived, never stored: a stored flag could contradict `maxSegment`.
    func requiresSplitting(_ duration: Duration) -> Bool {
        maxSegment.map { duration > $0 } ?? false
    }

    // ponytail: numbers are the Android S23 measurements; the iOS share-extension
    // limits are unverified (plan Phase 0 / Appendix B). Re-measure, edit here.
    static let all: [PublishPreset] = [
        PublishPreset(id: "whatsapp-status", label: .presetWhatsappStatus, maxSegment: .seconds(90),
                      supportsMultipleSegments: true, maxItemsPerShare: 30, schemes: ["whatsapp"]),
        PublishPreset(id: "x-free", label: .presetXFree, maxSegment: .seconds(140),
                      supportsMultipleSegments: false, schemes: ["twitter"]),
        // Instagram splits long Stories itself (Decision 3), so Story is pass-through.
        PublishPreset(id: "instagram-story", label: .presetInstagramStory, maxSegment: nil,
                      supportsMultipleSegments: false, schemes: ["instagram"]),
        PublishPreset(id: "instagram-reels", label: .presetInstagramReels, maxSegment: .seconds(180),
                      supportsMultipleSegments: false, schemes: ["instagram"]),
        PublishPreset(id: "snapchat-story", label: .presetSnapchatStory, maxSegment: .seconds(120),
                      supportsMultipleSegments: true, schemes: ["snapchat"]),
        PublishPreset(id: "telegram", label: .presetTelegram, maxSegment: nil,
                      supportsMultipleSegments: true, schemes: ["tg"]),
        PublishPreset(id: "messenger", label: .presetMessenger, maxSegment: nil,
                      supportsMultipleSegments: true, schemes: ["fb-messenger"]),
        // Both schemes unverified; either one found counts as installed.
        PublishPreset(id: "tiktok", label: .presetTiktok, maxSegment: nil,
                      supportsMultipleSegments: false, schemes: ["tiktok", "snssdk1233"]),
    ]

    /// Custom's chip, counted and ordered with the platforms; its parts use
    /// `custom(seconds:)`'s own id.
    static let customID = "custom"

    /// The seconds are in the id so parts made at 60 s and at 30 s never mix.
    static func custom(seconds: Int) -> PublishPreset {
        PublishPreset(id: "custom-\(seconds)s", label: .presetCustom,
                      maxSegment: .seconds(seconds), supportsMultipleSegments: true)
    }
}

/// Part starts: 0, then the latest keyframe that keeps each part within `max`.
/// Cutting only on keyframes is what keeps a split a copy, not a re-encode.
///
/// ponytail: a keyframe gap longer than `max` cannot be honoured — that part
/// runs long and the platform trims it. Only an exact cut fixes it, and that
/// means re-encoding.
func cutPoints(keyframes: [CMTime], duration: CMTime, max: CMTime) -> [CMTime] {
    var starts = [CMTime.zero]
    while let start = starts.last, duration - start > max {
        guard let next = keyframes.last(where: { $0 > start && $0 <= start + max })
                ?? keyframes.first(where: { $0 > start })
        else { break }
        starts.append(next)
    }
    return starts
}

/// `items` in runs a share extension will accept: 35 parts under WhatsApp's
/// 30 are "1–30" and "31–35", because 35 in one share drops WhatsApp from the sheet.
func shareBatches<T>(_ items: [T], max: Int?) -> [ArraySlice<T>] {
    let size = Swift.max(1, max ?? items.count)
    return stride(from: 0, to: items.count, by: size).map { items[$0..<Swift.min($0 + size, items.count)] }
}

/// How often each option was used, so the user's usual platform comes first.
enum PublishUsage {
    private static var store: UserDefaults { AppGroup.defaults ?? .standard }
    private static func key(_ id: String) -> String { "naqi.presetUses.\(id)" }

    static func count(_ id: String) -> Int { store.integer(forKey: key(id)) }
    static func record(_ id: String) { store.set(count(id) + 1, forKey: key(id)) }

    /// Most used first, ties in `ids` order (a stable sort).
    static func ordered(_ ids: [String]) -> [String] {
        ids.enumerated()
            .sorted { (count($0.element), -$0.offset) > (count($1.element), -$1.offset) }
            .map(\.element)
    }
}
