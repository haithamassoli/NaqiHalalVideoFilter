import SwiftUI

/// Project terms and third-party model restrictions have to be reachable from
/// the app itself, not only from the repository.
struct AboutScreen: View {
    var body: some View {
        ScrollView {
            ReadableColumn {
                VStack(alignment: .leading, spacing: Naqi.S.s5) {
                    wordmark
                    NaqiCard {
                        Text(.aboutVersion(Self.shortVersion, Self.build))
                            .font(Naqi.F.bodyMedium)
                            .foregroundStyle(Naqi.C.onSurface)
                        Text(.aboutLicense)
                            .font(Naqi.F.bodySmall)
                            .foregroundStyle(Naqi.C.onSurfaceVariant)
                            .padding(.top, Naqi.S.s2)
                    }
                    // App Review expects the privacy policy to be reachable
                    // from inside the app, not only from the App Store listing.
                    // For an app that collects nothing and has no networking
                    // code at all, the whole policy fits on a card — so it is
                    // stated here rather than linked to a page that could rot.
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.aboutEyebrowPrivacy)
                        NaqiCard {
                            Text(.aboutPrivacyTitle)
                                .font(Naqi.F.titleSmall)
                                .foregroundStyle(Naqi.C.onSurface)
                            Text(LinkPaste.isOffered ? .aboutPrivacyBody : .aboutPrivacyBodyShare)
                                .font(Naqi.F.bodySmall)
                                .foregroundStyle(Naqi.C.onSurfaceVariant)
                                .padding(.top, 2)
                        }
                    }
                    // Same gate as the paste field; the weekly auto-update in
                    // RootView keeps running while the card is hidden.
                    if LinkPaste.isOffered {
                        VStack(alignment: .leading, spacing: 0) {
                            SectionHeader(.aboutEyebrowDownloader)
                            YtDlpCard()
                        }
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.aboutEyebrowUpdates)
                        NaqiCard {
                            Text(.aboutReleasesTitle)
                                .font(Naqi.F.titleSmall)
                                .foregroundStyle(Naqi.C.onSurface)
                            Text(.aboutReleasesDesc)
                                .font(Naqi.F.bodySmall)
                                .foregroundStyle(Naqi.C.onSurfaceVariant)
                                .padding(.top, 2)
                        }
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.aboutEyebrowFeedback)
                        FeedbackCard()
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.aboutEyebrowLicenses)
                        NavigationLink(value: Flow.Step.licenses) {
                            NaqiCard {
                                Text(.aboutNoticesTitle)
                                    .font(Naqi.F.titleSmall)
                                    .foregroundStyle(Naqi.C.primary)
                                Text(.aboutNoticesDesc)
                                    .font(Naqi.F.bodySmall)
                                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                                    .padding(.top, 2)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        SectionHeader(.pickDiagTitle)
                        NavigationLink(value: Flow.Step.diagnostics) {
                            NaqiCard {
                                Text(.diagRun)
                                    .font(Naqi.F.titleSmall)
                                    .foregroundStyle(Naqi.C.primary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, Naqi.S.gutter)
            .padding(.vertical, Naqi.S.s5)
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.aboutTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var wordmark: some View {
        VStack(spacing: Naqi.S.s2) {
            NaqiMark().fill(Naqi.C.primary).frame(width: 56, height: 56)
            Text(.pickWordmarkAr)
                .font(Naqi.F.display)
                .foregroundStyle(Naqi.C.primary)
            Text(.pickWordmarkLatin)
                .font(Naqi.F.titleMedium)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
            Text(.pickTagline)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .padding(.top, Naqi.S.s2)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }

    static var shortVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }
    static var build: Int32 {
        Int32(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "") ?? 0
    }
}

/// Opens a pre-filled mail. With no mail account set up the system has nothing
/// to hand a `mailto:` to and the tap would silently do nothing, so the address
/// is copied instead and the card says so.
private struct FeedbackCard: View {
    static let address = "haitham.b.assoli@gmail.com"
    @Environment(\.openURL) private var openURL
    @State private var copied = false

    var body: some View {
        Button {
            openURL(Self.url) { accepted in
                guard !accepted else { return }
                #if os(macOS)
                NSPasteboard.general.clearContents()
                NSPasteboard.general.setString(Self.address, forType: .string)
                #else
                UIPasteboard.general.string = Self.address
                #endif
                copied = true
            }
        } label: {
            NaqiCard {
                Text(.aboutFeedbackTitle)
                    .font(Naqi.F.titleSmall)
                    .foregroundStyle(Naqi.C.primary)
                Text(copied ? .aboutFeedbackCopied(Self.address) : .aboutFeedbackDesc)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .padding(.top, 2)
            }
        }
        .buttonStyle(.plain)
    }

    /// Version and OS go in the body so a bug report arrives with the two
    /// facts that are always asked for first. Nothing else is attached — the
    /// privacy card promises the app collects nothing, and the user sees and
    /// can delete this line before sending.
    static var url: URL {
        #if os(macOS)
        let os = "macOS"
        #else
        let os = "iOS"
        #endif
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = address
        c.queryItems = [
            URLQueryItem(name: "subject", value: String(localized: .aboutFeedbackSubject)),
            URLQueryItem(name: "body", value: "\n\n—\nNaqi \(AboutScreen.shortVersion) (\(AboutScreen.build)) · \(os) \(ProcessInfo.processInfo.operatingSystemVersionString)"),
        ]
        return c.url!
    }
}

/// Weekly auto-check is fire-and-forget in RootView; this is the manual button
/// for when a link stops working before the week is up.
private struct YtDlpCard: View {
    @State private var version: String?
    @State private var status: LocalizedStringResource?
    @State private var busy = false

    var body: some View {
        NaqiCard {
            Text(.aboutYtdlpVersion(version ?? String(localized: .aboutYtdlpUnknown)))
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
            Text(.aboutYtdlpDesc)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .padding(.top, 2)
            Button {
                Task { await update() }
            } label: {
                Text(busy ? .aboutUpdating : .aboutUpdate)
                    .font(Naqi.F.labelLarge)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(NaqiOutlineButtonStyle())
            .disabled(busy)
            .padding(.top, Naqi.S.s3)
            if let status {
                Text(status)
                    .font(Naqi.F.bodySmall)
                    .foregroundStyle(Naqi.C.onSurfaceVariant)
                    .padding(.top, Naqi.S.s2)
            }
        }
        .task { version = await Downloader.version() }
    }

    private func update() async {
        busy = true
        defer { busy = false }
        do {
            version = try await Downloader.update()
            status = .aboutUpdateOk
        } catch {
            status = .aboutUpdateFailed
        }
    }
}

/// Terms for exactly the five non-Apple artifacts in this build. Links point
/// at upstream terms; Naqi's own licence cannot broaden what their owners
/// permit.
struct ThirdPartyLicensesScreen: View {
    var body: some View {
        ScrollView {
            ReadableColumn {
                VStack(alignment: .leading, spacing: Naqi.S.s4) {
                    Text(.licensesIntro)
                        .font(Naqi.F.bodyMedium)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)

                    notice(title: .licensesYtdlpTitle,
                           terms: .licensesYtdlpTerms,
                           source: "https://github.com/yt-dlp/yt-dlp")
                    notice(title: .licensesOnnxTitle,
                           terms: .licensesOnnxTerms,
                           source: "https://github.com/microsoft/onnxruntime-swift-package-manager/tree/1.24.2")
                    notice(title: .licensesFfmpegTitle,
                           terms: .licensesFfmpegTerms,
                           source: "https://github.com/tylerjonesio/ffmpeg-kit-spm/tree/6053b0e4f8607314ff5e14e0b18fc250c0f87c9b")
                    notice(title: .licensesDemucsTitle,
                           terms: .licensesDemucsTerms,
                           source: "https://github.com/facebookresearch/demucs")
                    notice(title: .licensesNsfwTitle,
                           terms: .licensesNsfwTerms,
                           source: "https://github.com/GantMan/nsfw_model")
                    notice(title: .licensesYamnetTitle,
                           terms: .licensesYamnetTerms,
                           source: "https://github.com/tensorflow/models/tree/master/research/audioset/yamnet")
                    notice(title: .licensesInsightFaceTitle,
                           terms: .licensesInsightFaceTerms,
                           source: "https://github.com/deepinsight/insightface/tree/master/model_zoo")
                    notice(title: .licensesPersonTitle,
                           terms: .licensesPersonTerms,
                           source: "https://github.com/ultralytics/ultralytics/blob/v8.4.29/LICENSE")

                    Text(.licensesPersonalOnly)
                        .font(Naqi.F.bodySmall)
                        .foregroundStyle(Naqi.C.onSurfaceVariant)
                }
            }
            .padding(.horizontal, Naqi.S.gutter)
            .padding(.vertical, Naqi.S.s5)
        }
        .background(Naqi.C.background)
        .navigationTitle(Text(.licensesTitle))
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private func notice(title: LocalizedStringResource,
                        terms: LocalizedStringResource,
                        source: String) -> some View {
        NaqiCard {
            Text(title)
                .font(Naqi.F.titleSmall)
                .foregroundStyle(Naqi.C.onSurface)
            Text(terms)
                .font(Naqi.F.bodySmall)
                .foregroundStyle(Naqi.C.onSurfaceVariant)
                .padding(.top, 2)
            if let url = URL(string: source) {
                Link(destination: url) {
                    HStack(spacing: Naqi.S.s2) {
                        Text(.licensesSource)
                        Image(systemName: "arrow.up.right.square")
                            .accessibilityHidden(true)
                    }
                    .font(Naqi.F.labelMedium)
                    .foregroundStyle(Naqi.C.primary)
                    .padding(.top, Naqi.S.s3)
                }
            }
        }
    }
}

/// The M0 device-runtime panel: the only way to run the model smoke test on a
/// real phone. It stays reachable from the overflow menu and from About.
struct DeviceRuntimeView: View {
    @State private var results: [ModelSmoke.Result] = []
    @State private var running = false

    var body: some View {
        List {
            Section {
                LabeledContent("CoreML EP", value: Ort.coreMLAvailable ? "available" : "unavailable")
                LabeledContent(String(localized: .diagCores),
                               value: "\(ProcessInfo.processInfo.activeProcessorCount)")
                LabeledContent(String(localized: .diagMemory),
                               value: ByteCountFormatter.string(
                                fromByteCount: Int64(ProcessInfo.processInfo.physicalMemory),
                                countStyle: .memory))
            }

            Section {
                ForEach(results, id: \.model) { (r: ModelSmoke.Result) in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Image(systemName: r.ok ? "checkmark.seal.fill" : "xmark.octagon.fill")
                                .foregroundStyle(r.ok ? Naqi.C.primary : Naqi.C.error)
                            Text(r.model).font(Naqi.F.titleSmall)
                            Spacer()
                            Text(verbatim: "\(Int(r.loadMs))ms load · \(Int(r.inferMs))ms run")
                                .font(Naqi.F.bodySmall).foregroundStyle(.secondary)
                        }
                        Text(r.error ?? r.detail)
                            .font(Naqi.F.bodySmall)
                            .foregroundStyle(r.ok ? AnyShapeStyle(.secondary) : AnyShapeStyle(Naqi.C.error))
                    }
                    .padding(.vertical, 2)
                }
                if results.isEmpty {
                    Text(running ? .pickDiagRunning : .diagNotRun).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(Text(.pickDiagTitle))
        .toolbar {
            Button { run() } label: { Text(running ? .pickDiagRunning : .diagRun) }
                .disabled(running)
        }
        // Deliberately NOT `.task { run() }`. The smoke loads htdemucs, whose
        // session is ~1.3 GB resident for the process lifetime — held while the
        // user is only browsing, and overlapping the first real job's use of
        // the same graph. It also made every media test suite flaky, because
        // the test host runs this view. It stays behind the button.
    }

    private func run() {
        guard !running else { return }
        running = true
        results = []
        Task.detached(priority: .userInitiated) {
            let r = ModelSmoke.runAll()
            // The panel is diagnostics, not a job: give the memory straight back.
            ModelRegistry.evict(Models.Demucs.file)
            await MainActor.run { results = r; running = false }
        }
    }
}

extension ComputeUnit: Hashable {}
