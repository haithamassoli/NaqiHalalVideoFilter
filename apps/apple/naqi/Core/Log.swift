import Foundation
import os

/// One logger per subsystem area. `Log.perf` is also the signpost source, so
/// Instruments picks up the pipeline stages without extra wiring.
enum Log {
    static let subsystem = "com.haithamassoli.naqi"

    static let app = Logger(subsystem: subsystem, category: "app")
    static let ml = Logger(subsystem: subsystem, category: "ml")
    static let media = Logger(subsystem: subsystem, category: "media")
    static let audio = Logger(subsystem: subsystem, category: "audio")
    static let analyze = Logger(subsystem: subsystem, category: "analyze")
    static let render = Logger(subsystem: subsystem, category: "render")
    static let job = Logger(subsystem: subsystem, category: "job")
    static let download = Logger(subsystem: subsystem, category: "download")
    static let perf = Logger(subsystem: subsystem, category: "perf")

    static let signposter = OSSignposter(subsystem: subsystem, category: "perf")
}

extension Duration {
    /// `components` splits into whole seconds plus an attosecond remainder;
    /// reading either alone is a silent truncation — the remainder on its own
    /// reports a 6.07 s render as 70.3 ms.
    var milliseconds: Double {
        let c = components
        return Double(c.seconds) * 1000 + Double(c.attoseconds) / 1e15
    }
}

/// Milliseconds since `t`, and the one place that arithmetic lives. There were
/// three transcriptions of it; the attoseconds-only mistake above was caught
/// twice while they were being written, which is two more reasons than one.
func msSince(_ t: ContinuousClock.Instant) -> Double { t.duration(to: .now).milliseconds }

/// Wall-clock timer that logs on `stop()`. Every pipeline stage reports through
/// this so the per-shape wall breakdown (perf-plan-v4's framing) is always
/// available from a release build, not just under Instruments.
struct Stage: ~Copyable {
    private let name: StaticString
    private let start: ContinuousClock.Instant
    private let state: OSSignpostIntervalState
    private let id: OSSignpostID

    init(_ name: StaticString) {
        self.name = name
        self.start = ContinuousClock.now
        self.id = Log.signposter.makeSignpostID()
        self.state = Log.signposter.beginInterval(name, id: id)
    }

    var elapsedMs: Double { msSince(start) }

    consuming func stop(_ detail: String = "") {
        let ms = elapsedMs
        Log.signposter.endInterval(name, state)
        Log.perf.info("\(String(describing: name), privacy: .public) \(ms, format: .fixed(precision: 1))ms \(detail, privacy: .public)")
    }
}
