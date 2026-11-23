import Foundation
import os

/// Peak-memory instrumentation for the 1.5 GB budget.
///
/// `phys_footprint` is the figure iOS jetsam actually measures. `resident_size`
/// is the usual mistake: it excludes compressed and IOSurface-backed pages, so
/// a pipeline holding pixel buffers reads far lower than the number that gets
/// it killed.
enum MemoryFootprint {

    /// Bytes, or 0 when the query fails.
    static func current() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? info.phys_footprint : 0
    }

    static var currentMB: Double { Double(current()) / 1_048_576 }

    /// PRD budget: peak RAM must stay under this on iPhone.
    static let budgetBytes: UInt64 = 1_536 * 1_048_576

    /// High-water mark across every `note` since the last `resetPeak`.
    ///
    /// Tracked in-process because that is the only way to get the number off a
    /// *device*: `footprint -p` and Instruments need a Mac attached, and M7's
    /// "peak RAM ≤ 1.5 GB on a passively cooled iPhone" has to be answerable
    /// from a log line after a 90-minute job that nobody watched.
    private static let high = OSAllocatedUnfairLock(initialState: UInt64(0))

    static func resetPeak() { high.withLock { $0 = 0 } }
    static var peakBytes: UInt64 { high.withLock { $0 } }

    /// Samples the footprint at a named point, records it against the peak, and
    /// warns once past the budget so a long job leaves evidence in the log
    /// *before* jetsam takes it — after the kill there is nothing to read.
    static func note(_ label: String) {
        let b = current()
        high.withLock { $0 = max($0, b) }
        if b > budgetBytes {
            Log.perf.warning("footprint \(label, privacy: .public): \(Double(b) / 1_048_576, format: .fixed(precision: 0)) MB OVER BUDGET")
        } else {
            Log.perf.debug("footprint \(label, privacy: .public): \(Double(b) / 1_048_576, format: .fixed(precision: 0)) MB")
        }
    }

    /// One line at the end of a job, at `info` so it survives a release build's
    /// log level. `note` is `debug` and is dropped there.
    static func logPeak(_ label: String) {
        let mb = Double(peakBytes) / 1_048_576
        Log.perf.info("""
            peak footprint \(label, privacy: .public): \(mb, format: .fixed(precision: 0)) MB \
            of \(Double(budgetBytes) / 1_048_576, format: .fixed(precision: 0)) MB budget
            """)
    }
}
