import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Step 3. One card — the pass strip — carrying the stage label, the percent,
/// the wavy bar and Cancel.
struct ProgressScreen: View {
    @Bindable var flow: Flow

    @State private var showCancelConfirm = false
    #if os(iOS)
    @Environment(\.openURL) private var openURL
    #endif

    var body: some View {
        ScrollView {
            ReadableColumn {
                VStack(alignment: .leading, spacing: Naqi.S.s5) {
                    if let name = flow.source?.name {
                        Text(name)
                            .font(Naqi.F.bodyMedium)
                            .foregroundStyle(Naqi.C.onSurfaceVariant)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }

                    if let failure = flow.monitor.failure {
                        failureCard(failure)
                    } else {
                        passStrip
                        // iOS has no WorkManager: the job stops when the app
                        // does. Saying so is the honest version of Android's
                        // foreground-service notification.
                        NoteLine(icon: .shield, text: .progressKeepOpen)
                        NoteLine(icon: .check, text: .progressBackgroundResume)
                    }

                    // Outside the failure branch: a job that failed does not
                    // take the rest of the queue with it (spec §9 carry-over
                    // 7), so the ones still waiting are exactly as real then.
                    if flow.monitor.othersQueued > 0 {
                        Text(.progressMoreQueued(Int32(flow.monitor.othersQueued)))
                            .font(Naqi.F.bodySmall)
                            .monospacedDigit()
                            .foregroundStyle(Naqi.C.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .transition(.opacity)
                    }
                }
            }
            .padding(.horizontal, Naqi.S.gutter)
            .padding(.top, Naqi.S.s4)
            .padding(.bottom, Naqi.S.s5)
        }
        .animation(Naqi.spring, value: flow.monitor.othersQueued)
        .background(Naqi.C.background)
        .navigationTitle(Text(.progressTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
        // Cancel is the only way back. A back button here would leave a job
        // running with nothing on screen that could stop it.
        .navigationBarBackButtonHidden(true)
        // `task(id:)` and not `onChange`: a short clip can finish before this
        // screen ever appears, and an onChange that never fires would strand
        // the user on a bar reading 100 %.
        .task(id: flow.monitor.isDone) {
            if flow.monitor.isDone { flow.path = [.done] }
        }
        .sensoryFeedback(.success, trigger: flow.monitor.isDone) { old, new in
            !old && new
        }
        // Replacing the pass strip with the failure card is a silent change:
        // VoiceOver keeps its focus on an element that no longer exists and
        // nothing says why. Same `task(id:)` reasoning as above — a job can
        // fail before this screen appears — and it runs once per transition
        // rather than on every redraw.
        .task(id: flow.monitor.failure) {
            guard let failure = flow.monitor.failure else { return }
            AccessibilityNotification.Announcement(String(localized: failureText(failure))).post()
        }
        // The same warning starting a long job gets (`OptionsScreen`), for the
        // step that throws the hours away: `JobRunner` deletes the work
        // directory on cancel, so there is nothing to undo it with.
        .confirmationDialog(Text(.dlgCancelTitle),
                            isPresented: $showCancelConfirm,
                            titleVisibility: .visible) {
            Button(role: .destructive) { Task { await flow.cancelJob() } } label: {
                Text(.dlgCancelConfirm)
            }
            Button(role: .cancel) {} label: { Text(.dlgCancelKeep) }
        } message: {
            Text(.dlgCancelBody)
        }
    }

    private var passStrip: some View {
        NaqiCard {
            // Stage, percent and bar are one statement about one thing, and
            // VoiceOver read them as three. Cancel stays outside the merge so
            // it is still an element that can be activated, and the bar's own
            // value and `updatesFrequently` trait carry into the group.
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: Naqi.S.s3) {
                    Text(flow.monitor.stage?.label ?? .jobsStageStarting)
                        .font(Naqi.F.titleMedium)
                        .foregroundStyle(Naqi.C.onSurface)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(.jobsProgressPercent(Int32(flow.monitor.percent)))
                        .font(Naqi.F.titleMedium)
                        .monospacedDigit()
                        .foregroundStyle(Naqi.C.primary)
                        // The bar says the same number with a noun in front of
                        // it. Two elements reading "37 %" is one too many.
                        .accessibilityHidden(true)
                }
                .padding(.bottom, Naqi.S.s3)

                WavyProgress(value: Double(flow.monitor.percent) / 100,
                             animating: flow.monitor.isRunning)
                    .padding(.bottom, Naqi.S.s3)
            }
            .accessibilityElement(children: .combine)
            // Merging drops the children's identifiers, and the bar's is the
            // documented handle on this row, so it moves up to the group.
            .accessibilityIdentifier("progress.wavy")

            HStack {
                if let stats = flow.monitor.progress?.download {
                    Text(downloadStatsText(stats))
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                        .monospacedDigit()
                } else if flow.monitor.etaMs > 0 {
                    Text(.jobsEtaRemaining(String(localized: durationText(ms: flow.monitor.etaMs))))
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                }
                Spacer(minLength: 0)
                Button { showCancelConfirm = true } label: {
                    Text(.actionCancel)
                        .font(Naqi.F.labelLarge)
                        .foregroundStyle(Naqi.C.primary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    /// The sentence the card shows, and the one VoiceOver announces.
    ///
    /// `.lowSpace` is the one failure whose fixed wording says nothing the user
    /// can act on. The job records what the preflight wanted against what the
    /// volume had, so say those instead when it has them.
    private func failureText(_ failure: JobFailure) -> LocalizedStringResource {
        failure == .lowSpace ? lowSpaceText(flow.monitor.shortfall) : failure.sentence
    }

    private func failureCard(_ failure: JobFailure) -> some View {
        // `.interrupted` is not a failure: the OS took the app away and the
        // work is sitting in the work directory. In error red it headlined
        // "broken" directly above a button offering to carry on. It gets the
        // paused title and ordinary body colour; every other cause keeps red.
        let paused = failure == .interrupted
        return NaqiCard {
            if paused {
                Text(.progressPausedTitle)
                    .font(Naqi.F.titleMedium)
                    .foregroundStyle(Naqi.C.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, Naqi.S.s2)
            }

            Text(failureText(failure))
                .font(Naqi.F.bodyMedium)
                .foregroundStyle(paused ? Naqi.C.onSurfaceVariant : Naqi.C.error)
                .frame(maxWidth: .infinity, alignment: .leading)

            #if os(iOS)
            // The one failure with a fix the user can reach. Sending them to it
            // beats a sentence that names Settings and leaves them to find it.
            // macOS has no equivalent URL, so the sentence stands alone there.
            if failure == .photosDenied,
               let settings = URL(string: UIApplication.openSettingsURLString) {
                Button { openURL(settings) } label: {
                    Text(.actionOpenSettings)
                        .font(Naqi.F.labelLarge)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(NaqiOutlineButtonStyle())
                .padding(.top, Naqi.S.s4)
            }
            #endif

            // The hint and the button appear together or not at all: offering
            // Resume when nothing was checkpointed would restart the film from
            // zero while promising the opposite.
            if flow.monitor.isResumable {
                Text(.jobsResumeHint)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .padding(.top, Naqi.S.s2)
                Button { Task { await flow.monitor.resume() } } label: {
                    Text(.actionResume)
                        .font(Naqi.F.labelLarge)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(NaqiPrimaryButtonStyle())
                .padding(.top, Naqi.S.s4)
            }

            Button { Task { await flow.finishAndPickAnother() } } label: {
                Text(.jobsNewJob)
                    .font(Naqi.F.labelLarge)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(NaqiOutlineButtonStyle())
            .padding(.top, Naqi.S.s3)
        }
    }
}
