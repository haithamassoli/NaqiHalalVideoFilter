import AVKit
import SwiftUI

/// The live queue and saved results in one place.
struct JobsScreen: View {
    @Bindable var flow: Flow

    @State private var playback: PlaybackItem?
    @State private var scopedFolder: URL?
    @State private var publishing: PublishTarget?

    /// The newest finished video, shown as the full saved card. The rest stay
    /// compact rows; each can still open the publish sheet from its menu.
    private var latest: (job: Job, url: URL)? {
        guard let job = flow.monitor.finishedJobs.first, case .done(let published) = job.state,
              let url = published.existingURL else { return nil }
        return (job, url)
    }

    private var olderJobs: [Job] {
        latest == nil ? flow.monitor.finishedJobs : Array(flow.monitor.finishedJobs.dropFirst())
    }

    var body: some View {
        ScrollView {
            ReadableColumn {
                VStack(alignment: .leading, spacing: Naqi.S.s5) {
                    if flow.monitor.activeJobs.isEmpty && flow.monitor.finishedJobs.isEmpty {
                        ContentUnavailableView(.jobsNoneRunning,
                                               systemImage: "clock",
                                               description: Text(.jobsLibraryEmpty))
                    }
                    if !flow.monitor.activeJobs.isEmpty {
                        jobSection(.jobsTitle, jobs: flow.monitor.activeJobs)
                    }
                    if let latest, case .done(let published) = latest.job.state {
                        SavedCard(url: latest.url, name: published.name,
                                  play: { open(latest.job) },
                                  publish: { publishing = $0 })
                    }
                    if !flow.monitor.finishedJobs.isEmpty {
                        VStack(alignment: .leading, spacing: 0) {
                            SectionHeader(title: .jobsLibrary) {
                                Button { Task { await flow.monitor.clearFinished() } } label: {
                                    Text(.jobsClearFinished)
                                }
                                .font(Naqi.F.labelMedium)
                            }
                            if !olderJobs.isEmpty {
                                NaqiCard(padding: 0) {
                                    ForEach(olderJobs) { job in
                                        JobRow(job: job,
                                               progress: nil,
                                               open: { open(job) },
                                               resume: { Task { await flow.resumeJob(job) } },
                                               cancel: { Task { await flow.monitor.discard(job.id) } },
                                               publish: { publishing = $0 })
                                        if job.id != olderJobs.last?.id { NaqiRowDivider() }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, Naqi.S.gutter)
            .padding(.vertical, Naqi.S.s4)
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.jobsTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(Naqi.C.background, for: .navigationBar)
        #endif
        .sensoryFeedback(.success, trigger: flow.monitor.runningID) { old, new in
            guard let old, new == nil,
                  let job = flow.monitor.finishedJobs.first(where: { $0.id == old }) else { return false }
            if case .done = job.state { return true }
            return false
        }
        .sheet(item: $playback, onDismiss: closePlayer) { MediaPlayerSheet(item: $0) }
        .sheet(item: $publishing) { PublishSheet(target: $0) }
    }

    private func jobSection(_ title: LocalizedStringResource, jobs: [Job]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title)
            NaqiCard(padding: 0) {
                ForEach(jobs) { job in
                    JobRow(job: job,
                           progress: flow.monitor.runningID == job.id ? flow.monitor.queueProgress : nil,
                           open: {},
                           resume: { Task { await flow.resumeJob(job) } },
                           cancel: { Task { await flow.monitor.cancel(job.id) } })
                    if job.id != jobs.last?.id { NaqiRowDivider() }
                }
            }
        }
    }

    private func open(_ job: Job) {
        guard case .done(let published) = job.state else { return }
        let title = published.name
        if let url = published.existingURL {
            playback = .file(url, title: title)
            return
        }
        if job.destination == .userFolder, let folder = job.resolvedFolder {
            let scoped = folder.startAccessingSecurityScopedResource()
            let url = folder.appendingPathComponent(published.name)
            if FileManager.default.fileExists(atPath: url.path) {
                scopedFolder = scoped ? folder : nil
                playback = .file(url, title: title)
                return
            }
            if scoped { folder.stopAccessingSecurityScopedResource() }
        }
        Task {
            if let asset = await DoneScreen.libraryVideo(published.assetID) {
                playback = .library(asset, title: title)
            }
        }
    }

    private func closePlayer() {
        scopedFolder?.stopAccessingSecurityScopedResource()
        scopedFolder = nil
    }
}

private struct JobRow: View {
    let job: Job
    let progress: JobProgress?
    let open: () -> Void
    let resume: () -> Void
    let cancel: () -> Void
    var publish: (PublishTarget) -> Void = { _ in }

    @State private var scopedFolder: URL?

    private var title: String {
        if case .done(let published) = job.state { return published.name }
        return job.title
    }

    private var status: String {
        if case .running = job.state, progress?.stage == .download, let stats = progress?.download {
            return downloadStatsText(stats)
        }
        return String(localized: statusResource)
    }

    private var statusResource: LocalizedStringResource {
        switch job.state {
        case .pending: .jobsStatusQueued
        case .running: progress.map { .jobsProgressPercent(Int32($0.pct.rounded())) } ?? .jobsStageStarting
        case .done: .jobsSavedLabel
        case .failed(let failure, _): failure == .interrupted ? .progressPausedTitle : failure.sentence
        case .cancelled: .jobsStatusCancelled
        }
    }

    private var glyph: String {
        switch job.state {
        case .pending: "clock"
        case .running: "arrow.trianglehead.2.clockwise.rotate.90"
        case .done: "checkmark.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        case .cancelled: "xmark.circle"
        }
    }

    var body: some View {
        HStack(spacing: Naqi.S.s3) {
            Image(systemName: glyph)
                .foregroundStyle(job.state.isTerminal ? Naqi.C.onSurfaceVariant : Naqi.C.primary)
                .accessibilityHidden(true)
            Button(action: open) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Naqi.F.titleSmall)
                        .foregroundStyle(Naqi.C.onSurface)
                        .lineLimit(1)
                    Text(verbatim: status)
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .disabled({ if case .done = job.state { false } else { true } }())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(.jobsRowA11Y(title, status)))

            switch job.state {
            case .pending, .running:
                Button(action: cancel) { Text(.actionCancel) }
            case .done:
                if let url = shareURL {
                    ShareLink(item: url) { Text(.actionShare) }
                }
            case .failed(_, let resumable):
                Button(action: resumable ? resume : cancel) {
                    Text(resumable ? .actionResume : .actionDiscard)
                }
            case .cancelled:
                Button(action: cancel) { Text(.actionDiscard) }
            }
        }
        .font(Naqi.F.labelMedium)
        .foregroundStyle(Naqi.C.primary)
        .frame(minHeight: 56)
        .padding(.horizontal, Naqi.S.s4)
        .contextMenu {
            if let url = shareURL, MediaKind.of(url) == .video {
                Button { publish(PublishTarget(url: url, name: title)) } label: {
                    Label { Text(.publishTitle) } icon: { Image(systemName: "rectangle.split.3x1") }
                }
            }
        }
        .onAppear { openFolderIfNeeded() }
        .onDisappear { closeFolder() }
    }

    /// After a relaunch the sandbox will not see a user-folder file until the
    /// bookmark is opened. Play already does this; Share has to as well.
    private var shareURL: URL? {
        guard case .done(let published) = job.state else { return nil }
        if let url = published.existingURL {
            return url
        }
        if let folder = job.resolvedFolder {
            let url = folder.appendingPathComponent(published.name)
            if FileManager.default.fileExists(atPath: url.path) { return url }
        }
        return nil
    }

    private func openFolderIfNeeded() {
        guard job.destination == .userFolder, let folder = job.resolvedFolder else { return }
        if folder.startAccessingSecurityScopedResource() { scopedFolder = folder }
    }

    private func closeFolder() {
        scopedFolder?.stopAccessingSecurityScopedResource()
        scopedFolder = nil
    }
}
