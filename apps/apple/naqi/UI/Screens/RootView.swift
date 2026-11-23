import SwiftUI

/// Pick → Options → Progress → Done, plus leaf screens off the overflow
/// menu. A straight line does not need a route graph; `path` is an array so a
/// step can replace the stack rather than push onto it — starting a job must
/// not leave Options behind a back button that would re-enqueue it.
struct RootView: View {
    @State private var flow = Flow()
    @State private var loadedResumable = false
    @AppStorage("naqi.onboarded") private var onboarded = false
    /// The language picked on the first onboarding page. `AppLanguage.save`
    /// only takes effect at the next launch, so this session applies it
    /// through the environment instead of asking for a relaunch.
    @State private var language = AppLanguage.current
    @AppStorage(AppTheme.key) private var theme = AppTheme.system
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if onboarded { stack } else {
                OnboardingScreen(flow: flow, language: $language) {
                    withAnimation(.smooth) { onboarded = true }
                }
            }
        }
        .environment(\.locale, Locale(identifier: language))
        .environment(\.layoutDirection, language == "ar" ? .rightToLeft : .leftToRight)
        .tint(Naqi.C.primary)
        .onAppear { theme.apply() }
        // The share extension leaves manifests in the App Group container; the
        // app is the only thing that can enqueue them. Draining on every
        // activation and not only at launch is what makes a share that arrives
        // while the app is already open show up without a relaunch.
        //
        // The destination comes from the flow's `export`, not from `Publish`'s
        // `.photos` default: a shared-in video that ignored the folder the user
        // picked would be the only route that saves somewhere they did not
        // choose — and an audio-only share bound for Photos would fail at
        // publish outright.
        //
        // Survivors are read first and in the same task, not in a second one:
        // `drainSharedIn` enqueues rows that are `.pending` for the moment
        // before the queue starts them, so a drain that won the race would make
        // a share that has just arrived look like a job that died with the app.
        .task(id: scenePhase) {
            guard scenePhase == .active else { return }
            // Off the critical path: a slow network must not delay shared links.
            Task { await Downloader.updateIfDue() }
            await JobQueue.shared.continueInForeground()
            if !loadedResumable {
                await JobQueue.shared.sweepStorage()
                await flow.loadResumable()
                loadedResumable = true
            }
            if await flow.drainSharedIn() > 0 {
                flow.revealSharedIn()
            }
        }
        #if DEBUG
        .task { flow.seedFromLaunchArguments() }
        #endif
        // Last-used options are what the next run opens with, so every change
        // is persisted as it happens rather than only on Start — a user who
        // backs out of Options still changed their mind.
        .onChange(of: flow.ops) { flow.ops.saveAsLastUsed() }
    }

    private var stack: some View {
        NavigationStack(path: $flow.path) {
            PickScreen(flow: flow)
                .navigationDestination(for: Flow.Step.self) { step in
                    switch step {
                    case .options: OptionsScreen(flow: flow)
                    case .progress: ProgressScreen(flow: flow)
                    case .done: DoneScreen(flow: flow)
                    case .jobs: JobsScreen(flow: flow)
                    case .settings: SettingsScreen(flow: flow, language: $language)
                    case .about: AboutScreen()
                    case .licenses: ThirdPartyLicensesScreen()
                    case .diagnostics: DeviceRuntimeView()
                    }
                }
        }
    }
}

#Preview { RootView() }
