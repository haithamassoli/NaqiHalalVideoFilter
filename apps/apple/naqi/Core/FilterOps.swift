import Foundation

/// The user-facing job options. Semantics are identical to Android's
/// `model/FilterOps.kt` — see `docs/apple-port/spec-jobs-ui.md`.
struct FilterOps: Codable, Sendable, Equatable {
    var removeMusic: Bool = false
    var censor: Bool = true
    /// Output policy selected by the user. Fast keeps analysis and audio
    /// semantics unchanged, but caps rendered video to a 720-pixel short side.
    var processingMode: ProcessingMode = .current

    /// Which people's regions get censored.
    var who: Who = .women
    /// The face toggle wins over a legacy off selection that the picker no
    /// longer offers. Keep the stored value so censor-off jobs retain it.
    var resolvedWho: Who { censor && who == .none ? .women : who }
    var censorMode: CensorMode = .regions
    /// Both body targets run the person detector on every frame, so they are
    /// opt-in; `bodyPrecise` also blurs the body's outline instead of its box.
    var censorTarget: CensorTarget = .face
    /// Whether the NSFW model may add whole-frame censor spans. Face tracks
    /// remain independent, so turning this off also removes the gate's model
    /// session and tensor lane from analyze.
    var censorNsfw: Bool = true
    /// 0–100. Feeds the NSFW gate thresholds only — never face blur.
    /// `40` is Android's `DEFAULT_STRICTNESS` (`spec-jobs-ui.md` §1.1 row 4,
    /// `spec-analyze.md` §0.20). The gate's thresholds are interpolated from
    /// this, so any other default silently censors differently than Android.
    var strictness: Int = 40
    /// 0–100, mapped to a Gaussian sigma scaled to the output's short side.
    var blurAmount: Int = 60
    var grayscale: Bool = false
    /// Raw value `0` means blur. A selected opaque colour replaces the blurred
    /// image, so `blurAmount` and `grayscale` are ignored until blur is selected
    /// again. One field therefore carries both mode and colour on disk.
    var solidColor: SolidColor = .blur
    var keepStems: KeepStems = .vocals
    /// Appends the two-second Naqi card after the last frame. Only a render
    /// that re-encodes the picture can add it: compressed passthrough leaves
    /// the video exactly as it came, card included or not.
    var endCard: Bool = true

    /// `none` is the step-1 toggle rather than a picker option. `everyone`
    /// remains reachable because it is both the strictest choice and the one
    /// that makes every gender crop, tensor and model call unnecessary.
    enum Who: String, Codable, Sendable, CaseIterable {
        case women, men, everyone, none

        /// The three the picker offers. Off stays the step-1 toggle.
        static var userSelectable: [Who] { [.everyone, .women, .men] }

        /// True when the verdict is known without running genderage.
        var skipsGenderVote: Bool { self == .everyone || self == .none }
    }
    enum CensorMode: String, Codable, Sendable, CaseIterable { case regions, wholeFrame }
    /// `person` was the removed whole-body value. New raw names keep a stored
    /// `person` (once the default) from silently selecting the slower path.
    enum CensorTarget: String, Codable, Sendable, CaseIterable { case face, body, bodyPrecise }
    enum SolidColor: UInt32, Codable, Sendable, CaseIterable {
        case blur = 0
        case gray = 0xFF9E9E9E
        case black = 0xFF000000
        case white = 0xFFFFFFFF
        case navy = 0xFF2C3E50
        case green = 0xFF1E3A2F

        static var swatches: [SolidColor] { [.gray, .black, .white, .navy, .green] }
        var isSolid: Bool { self != .blur }

        var rgb: (red: Double, green: Double, blue: Double) {
            (Double((rawValue >> 16) & 0xFF) / 255,
             Double((rawValue >> 8) & 0xFF) / 255,
             Double(rawValue & 0xFF) / 255)
        }
    }
    enum KeepStems: String, Codable, Sendable, CaseIterable {
        case vocals, vocalsAndOther

        /// Stems written into the output. drums/bass are never kept.
        var stems: [Models.Demucs.Stem] {
            switch self {
            case .vocals: [.vocals]
            case .vocalsAndOther: [.vocals, .other]
            }
        }
    }

    enum ProcessingMode: String, Codable, Sendable, CaseIterable {
        case current, fast

        var outputShortSideCap: Int? { self == .fast ? 720 : nil }
    }

    /// At least one operation must be selected.
    var isValid: Bool { removeMusic || censor }
    var visualEffectIsNoop: Bool {
        solidColor == .blur && blurAmount == 0 && grayscale == false
    }

    /// Drops what a source with no picture cannot do. Censoring has nothing to
    /// work on and the job would die at `Preflight` with `noVideoTrack`, so
    /// removing music — the one operation left — is the one that goes on.
    /// `nil` (nothing could read the file) leaves the options alone: "could not
    /// read" is not "has no picture", and `Preflight` tells that story with the
    /// right error.
    mutating func fit(hasVideo: Bool?) {
        guard hasVideo == false else { return }
        censor = false
        removeMusic = true
    }

    /// The three job shapes have disjoint performance walls, so the pipeline
    /// branches on this rather than on the two flags (`perf-plan-v4.md` §1).
    enum Shape: Sendable, Equatable {
        /// Audio wall. Video is passthrough.
        case musicOnly
        /// Analyze wall. Audio is passthrough.
        case censorOnly
        /// Audio wall (~65 % of total).
        case both
    }

    var shape: Shape {
        switch (removeMusic, censor) {
        case (true, false): .musicOnly
        case (false, true): .censorOnly
        default: .both
        }
    }
}

// A queue file written before `censorNsfw`, `solidColor` and `endCard` existed
// must decode to the defaults: gate on, Gaussian blur, card on.
extension FilterOps {
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        removeMusic = try values.decode(Bool.self, forKey: .removeMusic)
        censor = try values.decode(Bool.self, forKey: .censor)
        processingMode = try values.decodeIfPresent(ProcessingMode.self, forKey: .processingMode) ?? .current
        who = try values.decode(Who.self, forKey: .who)
        censorMode = try values.decode(CensorMode.self, forKey: .censorMode)
        // The legacy `person` value, like any unknown one, falls back to face.
        censorTarget = try values.decodeIfPresent(String.self, forKey: .censorTarget)
            .flatMap(CensorTarget.init(rawValue:)) ?? .face
        censorNsfw = try values.decodeIfPresent(Bool.self, forKey: .censorNsfw) ?? true
        strictness = try values.decode(Int.self, forKey: .strictness)
        blurAmount = try values.decode(Int.self, forKey: .blurAmount)
        grayscale = try values.decode(Bool.self, forKey: .grayscale)
        solidColor = try values.decodeIfPresent(SolidColor.self, forKey: .solidColor) ?? .blur
        keepStems = try values.decode(KeepStems.self, forKey: .keepStems)
        endCard = try values.decodeIfPresent(Bool.self, forKey: .endCard) ?? true
    }
}

/// Last-used options, preselected on the next run (PRD user flow).
extension FilterOps {
    private static let key = "naqi.filterOps"

    /// App Group suite, not `.standard`: a shared-in video inherits these, and
    /// the share extension is a different process with a different standard
    /// suite. Falls back to `.standard` only where the group is unavailable
    /// (macOS without the entitlement, unit tests), so options still persist.
    private static var store: UserDefaults { AppGroup.defaults ?? .standard }

    static func loadLastUsed() -> FilterOps {
        guard let d = store.data(forKey: key),
              let ops = try? JSONDecoder().decode(FilterOps.self, from: d)
        else { return FilterOps() }
        return ops
    }

    func saveAsLastUsed() {
        guard let d = try? JSONEncoder().encode(self) else { return }
        Self.store.set(d, forKey: Self.key)
    }
}
