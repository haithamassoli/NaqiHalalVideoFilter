import Foundation
import os
#if canImport(ActivityKit) && os(iOS)
import ActivityKit
#endif

/// Start / update / end, guarded so every call is a no-op when the platform or
/// the user's settings cannot support an activity.
///
/// The activity itself is rendered by the `NaqiWidgets` extension;
/// `NaqiJobAttributes` lives in `NaqiShared/` because ActivityKit matches the
/// two processes on that type's encoding, so it has to be one declaration.
///
/// Every string is localized here, not in the extension: the extension has no
/// catalog, and the language is a per-app choice it could not see anyway.
@MainActor
enum LiveActivity {
    #if canImport(ActivityKit) && os(iOS)
    private static var current: Activity<NaqiJobAttributes>?
    private static var last: NaqiJobAttributes.ContentState?
    private static var inBackground = false
    #endif

    /// How the job ended, as far as the card cares.
    enum Outcome: Sendable {
        case done(name: String)
        case paused
        case failed(JobFailure)
        case cancelled
    }

    static func start(title: String) {
        #if canImport(ActivityKit) && os(iOS)
        guard current == nil, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let state = NaqiJobAttributes.ContentState(caption: "", symbol: "hourglass", fraction: 0)
        do {
            current = try Activity.request(
                attributes: NaqiJobAttributes(
                    title: title,
                    rightToLeft: AppLanguage.current == "ar",
                    pausedCaption: String(localized: .progressPausedTitle),
                    pausedDetail: String(localized: .laOpenToContinue)),
                content: ActivityContent(state: state, staleDate: nil))
            last = state
            // Only this job's own path through `start` is in the foreground.
            inBackground = false
            // A finished card from the previous job is still on the lock screen
            // by design; the next job replaces it rather than stacking a pile.
            // After the request, not before: a job the background grant
            // resumes cannot get a card, and must not take the old one away.
            for old in Activity<NaqiJobAttributes>.activities where old.id != current?.id {
                nonisolated(unsafe) let old = old
                Task { await old.end(nil, dismissalPolicy: .immediate) }
            }
        } catch {
            Log.job.notice("live activity unavailable: \(error.localizedDescription, privacy: .public)")
        }
        #endif
    }

    static func update(_ progress: JobProgress, etaMs: Int64 = 0, queued: Int = 0) async {
        #if canImport(ActivityKit) && os(iOS)
        await post(state(progress, etaMs: etaMs, queued: queued))
        #endif
    }

    /// The app just left (or came back to) the foreground. Re-posts the last
    /// state with a stale date that matches: iOS gives ~30 s before it
    /// suspends the app, and the extension draws a stale running card as
    /// paused — the only way the card can stop claiming progress once the
    /// process that would say so is frozen.
    static func setBackground(_ background: Bool) async {
        #if canImport(ActivityKit) && os(iOS)
        inBackground = background
        if let last { await post(last) }
        #endif
    }

    static func end(_ outcome: Outcome) async {
        #if canImport(ActivityKit) && os(iOS)
        guard let activity = current, let last else { return }
        current = nil
        self.last = nil
        let final = final(last, outcome)
        // Everything but a deliberate cancel stays on the lock screen: the end
        // of a 90-minute job is the one update the user actually came back for.
        // The Dynamic Island drops it either way; that is the system's rule.
        let policy: ActivityUIDismissalPolicy = if case .cancelled = outcome { .immediate } else { .default }
        nonisolated(unsafe) let live = activity
        await live.end(ActivityContent(state: final, staleDate: nil), dismissalPolicy: policy)
        #endif
    }

    #if canImport(ActivityKit) && os(iOS)
    static func state(_ progress: JobProgress, etaMs: Int64, queued: Int) -> NaqiJobAttributes.ContentState {
        // `Eta.liveMs` returns 0 early on; print nothing rather than a number
        // that will be wrong. A download has real numbers instead: bytes,
        // speed and its own ETA replace the percent-based guess.
        let head: String? = if progress.stage == .download, let stats = progress.download {
            downloadStatsText(stats)
        } else if etaMs > 0 {
            String(localized: .jobsEtaRemaining(String(localized: durationText(ms: etaMs))))
        } else {
            nil
        }
        let detail = [head, queued > 0 ? String(localized: .progressMoreQueued(Int32(queued))) : nil]
            .compactMap(\.self).joined(separator: " · ")
        return .init(caption: progress.stage.map { String(localized: $0.label) } ?? "",
                     detail: detail,
                     symbol: progress.stage?.symbol ?? "hourglass",
                     fraction: progress.fraction)
    }

    /// The card a job ends on. Keeps the last fraction for paused and failed:
    /// how far it got is the one number still true.
    static func final(_ last: NaqiJobAttributes.ContentState, _ outcome: Outcome) -> NaqiJobAttributes.ContentState {
        var final = last
        switch outcome {
        case .done(let name):
            final = .init(phase: .done, caption: String(localized: .notifDoneTitle), detail: name,
                          symbol: "checkmark", fraction: 1)
        case .paused:
            final.phase = .paused
            final.caption = String(localized: .progressPausedTitle)
            final.detail = String(localized: .laOpenToContinue)
            final.symbol = "pause.fill"
        case .failed(let failure):
            final.phase = .failed
            final.caption = String(localized: .notifFailedTitle)
            final.detail = String(localized: failure.sentence)
            final.symbol = "exclamationmark"
        case .cancelled:
            break
        }
        return final
    }

    private static func post(_ state: NaqiJobAttributes.ContentState) async {
        guard let activity = current else { return }
        last = state
        // `Activity` is not `Sendable` but its mutators are `@concurrent`, so
        // the hand-off off the main actor has to be spelled out. Safe here: the
        // handle is written only by `start`/`end`, both main-actor isolated.
        nonisolated(unsafe) let live = activity
        // Foreground: the bar only moves on a real change, which on a long
        // analyze pass can be minutes apart, so the stale date is generous.
        // ponytail: background uses a flat 30 s, the documented grace; read
        // `backgroundTimeRemaining` if iOS ever makes it longer.
        await live.update(ActivityContent(
            state: state,
            staleDate: staleDate(background: inBackground)))
    }

    static func staleDate(background: Bool, now: Date = .now) -> Date {
        now.addingTimeInterval(background ? 30 : 15 * 60)
    }
    #endif
}

extension Job.Stage {
    /// The glyph the Live Activity draws in its ring.
    var symbol: String {
        switch self {
        case .download: "arrow.down"
        case .convert: "arrow.triangle.2.circlepath"
        case .analyze: "eye"
        case .render: "film"
        case .separate: "waveform"
        case .mux, .concat, .publish: "square.and.arrow.down"
        }
    }
}
