#if DEBUG
import Foundation

/// Screenshot harness. `-naqiScreen picked|options|progress|done` puts the flow
/// on one screen with plausible state so every step can be captured — including
/// in Arabic — without a running job and without a tap.
///
/// DEBUG only, and deliberately not a preview trait: the screens have to be
/// audited in the real app, in the real locale, at the real device size. It
/// fabricates a job row that no user action produced, so nothing outside a
/// debug build may be able to reach it.
extension Flow {

    func seedFromLaunchArguments() {
        guard let i = CommandLine.arguments.firstIndex(of: "-naqiScreen"),
              i + 1 < CommandLine.arguments.count
        else { return }
        let screen = CommandLine.arguments[i + 1]
        guard screen != "pick" else { return }

        // A real clip staged into Documents by the capture script takes
        // precedence, so `-naqiScreen run` drives a genuine job through the
        // same screens the other modes only pose.
        let staged = URL.documentsDirectory.appendingPathComponent("screenshot-source.mp4")
        let url = FileManager.default.fileExists(atPath: staged.path)
            ? staged
            : FileManager.default.temporaryDirectory.appendingPathComponent("holiday-in-tabuk.mp4")
        if url != staged { FileManager.default.createFile(atPath: url.path, contents: Data()) }
        // 43 min: past the 30-minute confirm threshold, so the ETA line and the
        // long-job dialog are both live.
        //
        // `optionsAudio` poses the one source shape Photos cannot take — a
        // container with no video track. Posed rather than staged because
        // saying so for real needs an actual audio-only movie on disk for
        // AVFoundation to probe, and the screen under audit is the same either
        // way.
        seed(source: PickedSource(url: url, name: url.lastPathComponent, securityScoped: true),
             durationMs: 43 * 60 * 1000,
             hasVideo: screen != "optionsAudio")
        ops.removeMusic = true
        ops.censor = screen != "optionsAudio"

        switch screen {
        case "options", "optionsAudio":
            path = [.options]
        case "progress", "queued":
            path = [.progress]
            var bar = JobProgress(shape: .combined, removeMusic: true)
            bar.post(.analyze, 0.72)
            bar.post(.separate, 0.35)
            // `queued` poses what a four-file share-in looks like. The number
            // is seeded here and observed for real by `runQueue` below; both
            // exist because the pose is the only one that can be captured in
            // Arabic without waiting out three real jobs.
            monitor.seed(state: .running, progress: bar,
                         othersQueued: screen == "queued" ? 3 : 0)
        case "done":
            path = [.done]
            // A real publish now keeps a file on both destinations, so Play,
            // Share and Save are all live on this screen.
            monitor.seed(state: .done(Published(name: "holiday-in-tabuk-naqi-1754320000000.mp4",
                                                url: url, assetID: nil)),
                         progress: nil)
        case "about":
            path = [.about]
        case "settings":
            path = [.settings]
        case "run", "runQueue":
            // Censor-only: the same four screens, real work behind them, and
            // no 88 MB htdemucs graph to load on a simulator.
            ops.removeMusic = false
            ops.censor = true
            // `-naqiCensorTarget body|bodyPrecise` drives the slower coverage paths.
            if let j = CommandLine.arguments.firstIndex(of: "-naqiCensorTarget"), j + 1 < CommandLine.arguments.count,
               let target = FilterOps.CensorTarget(rawValue: CommandLine.arguments[j + 1]) {
                ops.censorTarget = target
            }
            Task {
                await start()
                guard screen == "runQueue" else { return }
                // Two more rows through the real queue, so the "N more queued"
                // line can be seen coming from `JobQueue.observe()` and not
                // from a number someone typed. A different `strictness` is a
                // different `Checkpoint.key`, which is what stops `enqueue`'s
                // KEEP rule from collapsing all three into one row.
                for strictness in [41, 42] {
                    var extra = ops
                    extra.strictness = strictness
                    await JobQueue.shared.enqueue(
                        Job.capture(source: url, ops: extra, destination: .photos,
                                    title: url.lastPathComponent))
                }
            }
        default:
            break
        }
    }
}
#endif
