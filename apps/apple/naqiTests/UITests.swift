import Foundation
import Testing
import UniformTypeIdentifiers
@testable import naqi

/// M6 exit criteria, at the logic level. Three things can quietly break the UI
/// without breaking the build: an options default drifting from the spec, a
/// string that resolves in English and falls back to its key in Arabic, and a
/// slider handing the pipeline a value outside 0…100.
@Suite("UI")
struct UITests {

    // MARK: - FilterOps

    @Test("At least one operation is required")
    func atLeastOneOp() {
        var ops = FilterOps()
        ops.removeMusic = false
        ops.censor = false
        #expect(!ops.isValid)

        ops.censor = true
        #expect(ops.isValid)
        #expect(ops.shape == .censorOnly)

        ops.censor = false
        ops.removeMusic = true
        #expect(ops.isValid)
        #expect(ops.shape == .musicOnly)

        ops.censor = true
        #expect(ops.shape == .both)
    }

    @Test("Defaults are the ones the options screen opens with")
    func defaults() {
        let d = FilterOps()
        #expect(d.removeMusic == false)
        #expect(d.censor == true)
        #expect(d.who == .women)
        #expect(d.censorMode == .regions)
        #expect(d.censorNsfw == true)
        #expect(d.blurAmount == 60)
        #expect(d.grayscale == false)
        #expect(d.solidColor == .blur)
        #expect(d.keepStems == .vocals)
        #expect(d.endCard == true)
        // Spec §1.1 row 4 / analyze §0.20 — Android's `DEFAULT_STRICTNESS`.
        // The gate interpolates its thresholds from this, so a drift here
        // censors differently than Android at default settings.
        #expect(d.strictness == 40)

        // `none` remains the step-1 toggle, so the picker offers the other
        // three states and leads with the strictest one.
        #expect(FilterOps.Who.userSelectable == [.everyone, .women, .men])
    }

    @Test("Old persisted options default new fields to the old behavior")
    func oldOptionsDecode() throws {
        let data = Data(#"{"removeMusic":false,"censor":true,"who":"women","censorMode":"regions","strictness":40,"blurAmount":60,"grayscale":false,"keepStems":"vocals"}"#.utf8)
        let ops = try JSONDecoder().decode(FilterOps.self, from: data)
        #expect(ops.censorNsfw == true)
        #expect(ops.solidColor == .blur)
        #expect(ops.processingMode == .current)
        #expect(ops.endCard == true)
    }

    @Test("Last-used options round-trip through UserDefaults")
    func roundTrip() {
        let saved = FilterOps.loadLastUsed()
        defer { saved.saveAsLastUsed() }

        var ops = FilterOps()
        ops.removeMusic = true
        ops.censor = true
        ops.who = .men
        ops.censorMode = .wholeFrame
        ops.censorNsfw = false
        ops.strictness = 17
        ops.blurAmount = 83
        ops.grayscale = true
        ops.solidColor = .navy
        ops.keepStems = .vocalsAndOther
        ops.processingMode = .fast
        ops.saveAsLastUsed()

        #expect(FilterOps.loadLastUsed() == ops)
    }

    // MARK: - Export destination

    /// `.serialized` because every test in here writes the same two
    /// `UserDefaults` keys: run in parallel they would restore each other's
    /// "before" value and the round-trip assertions would flake.
    @Suite("Export destination", .serialized)
    struct ExportTests {

        @Test("Last-used destination round-trips, and a folderless one does not")
        func destinationRoundTrip() {
            let saved = ExportTarget.loadLastUsed()
            defer { saved.saveAsLastUsed() }

            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent("naqi-export-\(UUID().uuidString)", isDirectory: true)
            try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }

            ExportTarget(destination: .userFolder, folder: folder).saveAsLastUsed()
            let loaded = ExportTarget.loadLastUsed()
            #expect(loaded.destination == .userFolder)
            #expect(loaded.folder?.standardizedFileURL == folder.standardizedFileURL)
            #expect(loaded.folderName == folder.lastPathComponent)

            // `.userFolder` with nothing to write into is not a state worth
            // restoring: it would enable Start for a job certain to die at
            // publish, hours in, on a film.
            ExportTarget(destination: .userFolder, folder: nil).saveAsLastUsed()
            #expect(ExportTarget.loadLastUsed().destination == .photos)
        }

        /// Photos will not take a bare audio file (the runner falls back to the
        /// app's own Documents for one that slips through), so the picker has
        /// to *force* the folder, not merely prefer it, and Start has to stay
        /// disabled until there is a folder to force it into.
        // `Flow.seed` is the `#if DEBUG` screenshot harness, so the three tests
        // that pose flow state cannot exist in a Release test bundle — which
        // `BenchTests.tv1EndToEnd` needs, since the app's own `-naqiScreen`
        // hook is DEBUG-only and a Debug binary can only measure `-Onone`.
        // Their absence in Release is the harness being unreachable there,
        // which is the whole point of it.
        #if DEBUG
        @Test("An audio-only source forces the folder destination and gates Start")
        @MainActor
        func audioOnlyForcesFolder() throws {
            let saved = ExportTarget.loadLastUsed()
            defer { saved.saveAsLastUsed() }
            ExportTarget(destination: .photos, folder: nil).saveAsLastUsed()

            let flow = Flow()
            // The censoring default the picker starts on, so the coercion below
            // is asserted against a state that really had to change.
            flow.ops = FilterOps(removeMusic: false, censor: true)
            let clip = FileManager.default.temporaryDirectory.appendingPathComponent("clip.m4a")
            flow.seed(source: PickedSource(url: clip, name: "clip.m4a"),
                      durationMs: 60_000, hasVideo: false)

            #expect(flow.isAudioOnly)
            // There is no picture to censor and exactly one operation left, so
            // the file arrives on Options as a music-removal job rather than as
            // one that dies at preflight with `noVideoTrack`.
            #expect(!flow.ops.censor)
            #expect(flow.ops.removeMusic)
            #expect(Job.shape(ops: flow.ops, hasVideoTrack: false, segmented: false) == .audioOnly)
            #expect(flow.destination == .userFolder)
            // Continue must stay live: Options is the only screen that can pick
            // the folder, so gating Pick on it would strand the source.
            #expect(flow.canContinue)
            #expect(!flow.canStart)

            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent("naqi-export-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            flow.setFolder(folder)
            #expect(flow.canStart)

            // Photos is not reachable for this source even by asking for it.
            flow.setDestination(.photos)
            #expect(flow.destination == .userFolder)
        }
        #endif

        /// The share extension carries no options, so the app decides where a
        /// shared-in video lands. It used to decide `.photos` unconditionally —
        /// `ShareInbox.drain`'s default — which was the one route in the app
        /// that saved somewhere the user had not chosen.
        @Test("A shared-in video inherits the chosen folder, not the Photos default")
        @MainActor
        func sharedInInheritsTheDestination() async throws {
            // Write probe, not a nil check: on macOS the container URL resolves
            // without the App Group entitlement and only the write fails. Share-in
            // is iOS-only — see `JobTests.usableInbox`.
            guard let dir = JobTests.usableInbox() else {
                #if os(iOS)
                Issue.record("App Group container unusable — the entitlement or the group id regressed")
                #endif
                return
            }
            let saved = ExportTarget.loadLastUsed()
            defer { saved.saveAsLastUsed() }

            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent("naqi-sharein-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            ExportTarget(destination: .userFolder, folder: folder).saveAsLastUsed()

            let id = UUID()
            try Data("not really a movie".utf8)
                .write(to: ShareManifest.mediaURL(dir, id: id, ext: "mp4"))
            try JSONEncoder().encode(ShareManifest(id: id, fileName: "shared.mp4",
                                                   receivedAt: Date()))
                .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)

            let flow = Flow()
            let queue = JobQueue(storeURL: Fixtures.scratch("ui-sharein.json"))
            #expect(await flow.drainSharedIn(into: queue) == 1)

            let job = try #require(await queue.jobs.first)
            #expect(job.destination == .userFolder,
                    "a shared-in video went to \(job.destination) instead of the chosen folder")
            #expect(job.folder?.standardizedFileURL == folder.standardizedFileURL)

            // 18 bytes of text: the job dies at preflight, so stop it rather
            // than leave it racing the next test.
            await queue.cancel(job.id)
            try? FileManager.default.removeItem(at: job.source)
        }

        /// The share sheet admits audio, and the extension carries no options —
        /// so a song shared in while "Censor faces" was the last-used setting
        /// would be queued as a censor job, reported as added, and then die at
        /// `Preflight` with `noVideoTrack`. `Flow` coerces a *picked* audio
        /// file; nothing coerced a shared-in one.
        @Test("A shared-in audio file is queued as a music-removal job, not a censor job")
        @MainActor
        func sharedInAudioDropsCensoring() async throws {
            guard let dir = JobTests.usableInbox() else {
                #if os(iOS)
                Issue.record("App Group container unusable — the entitlement or the group id regressed")
                #endif
                return
            }
            let savedOps = FilterOps.loadLastUsed()
            defer { savedOps.saveAsLastUsed() }
            let savedDest = ExportTarget.loadLastUsed()
            defer { savedDest.saveAsLastUsed() }
            // The combination that produces the failure: censoring on, music
            // removal off, carried over from the user's last video.
            FilterOps(removeMusic: false, censor: true).saveAsLastUsed()
            ExportTarget(destination: .photos, folder: nil).saveAsLastUsed()

            let id = UUID()
            let song = try Fixtures.audioClip("sharein-song.m4a", seconds: 1)
            try FileManager.default.copyItem(at: song, to: ShareManifest.mediaURL(dir, id: id, ext: "m4a"))
            try JSONEncoder().encode(ShareManifest(id: id, fileName: "song.m4a", receivedAt: Date()))
                .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)

            let flow = Flow()
            let queue = JobQueue(storeURL: Fixtures.scratch("ui-sharein-audio.json"))
            #expect(await flow.drainSharedIn(into: queue) == 1)

            let job = try #require(await queue.jobs.first)
            #expect(!job.ops.censor, "a file with no picture was queued to be censored")
            #expect(job.ops.removeMusic)
            #expect(Job.shape(ops: job.ops, hasVideoTrack: false, segmented: false) == .audioOnly)
            #expect(job.destination == .userFolder,
                    "a shared-in audio file was queued for Photos, which cannot take it")
            #expect(job.folder?.standardizedFileURL == OutputLibrary.root.standardizedFileURL)

            await queue.cancel(job.id)
            try? FileManager.default.removeItem(at: job.source)
        }

        /// The other half of the same rule: a shared-in *video* must keep the
        /// options the user last chose. A coercion that fired on everything
        /// would silently turn censoring off for every share.
        @Test("A shared-in video keeps the last-used options untouched")
        @MainActor
        func sharedInVideoKeepsOps() async throws {
            guard let dir = JobTests.usableInbox() else { return }
            let savedOps = FilterOps.loadLastUsed()
            defer { savedOps.saveAsLastUsed() }
            FilterOps(removeMusic: false, censor: true).saveAsLastUsed()

            let source = try requireQAVideo()
            let id = UUID()
            try FileManager.default.copyItem(at: source, to: ShareManifest.mediaURL(dir, id: id, ext: "mp4"))
            try JSONEncoder().encode(ShareManifest(id: id, fileName: "clip.mp4", receivedAt: Date()))
                .write(to: ShareManifest.manifestURL(dir, id: id), options: .atomic)

            let flow = Flow()
            let queue = JobQueue(storeURL: Fixtures.scratch("ui-sharein-video.json"))
            #expect(await flow.drainSharedIn(into: queue) == 1)

            let job = try #require(await queue.jobs.first)
            #expect(job.ops.censor)
            #expect(!job.ops.removeMusic)

            await queue.cancel(job.id)
            try? FileManager.default.removeItem(at: job.source)
        }

        /// The hop between the picker and the runner. Every pipeline test hands
        /// `Publish` a destination directly, so all of them would still pass if
        /// `Flow.start` dropped the folder on the floor and the job published to
        /// Photos — the exact class of bug the last integration pass found twice.
        #if DEBUG
        @Test("Start puts the chosen destination and folder on the job it enqueues")
        @MainActor
        func startCarriesTheDestination() async throws {
            let saved = ExportTarget.loadLastUsed()
            defer { saved.saveAsLastUsed() }
            ExportTarget(destination: .photos, folder: nil).saveAsLastUsed()

            let queue = JobQueue(storeURL: Fixtures.scratch("ui-start.json"))
            let flow = Flow(monitor: JobMonitor(queue: queue))
            // 18 bytes of text under an .mp4 name: enough to be captured and
            // enqueued, and it dies at preflight instead of running for real.
            let clip = Fixtures.scratch("ui-start-clip.mp4")
            try Data("not really a movie".utf8).write(to: clip)
            flow.seed(source: PickedSource(url: clip, name: "ui-start-clip.mp4"),
                      durationMs: 60_000)

            let folder = FileManager.default.temporaryDirectory
                .appendingPathComponent("naqi-start-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            flow.setFolder(folder)
            #expect(flow.destination == .userFolder)

            await flow.start()
            let job = try #require(await queue.jobs.first, "Start enqueued nothing")
            #expect(job.destination == .userFolder,
                    "the job went to \(job.destination), not the folder the user picked")
            #expect(job.folder?.standardizedFileURL == folder.standardizedFileURL,
                    "the job carries \(String(describing: job.folder))")

            await queue.cancel(job.id)
        }

        @Test("A source with video keeps Photos and needs no folder")
        @MainActor
        func videoSourceKeepsPhotos() {
            let saved = ExportTarget.loadLastUsed()
            defer { saved.saveAsLastUsed() }
            ExportTarget(destination: .photos, folder: nil).saveAsLastUsed()

            let flow = Flow()
            let clip = FileManager.default.temporaryDirectory.appendingPathComponent("clip.mp4")
            flow.seed(source: PickedSource(url: clip, name: "clip.mp4"), durationMs: 60_000)
            #expect(!flow.isAudioOnly)
            #expect(flow.destination == .photos)
            #expect(flow.canStart)
        }
        #endif
    }

    // MARK: - Drag and drop

    /// A drop hands over any file URL — `.dropDestination(for: URL.self)` has no
    /// `allowedContentTypes` — so this is the filter the Files importer gets for
    /// free. Without it a dropped PDF becomes a job that dies at preflight and
    /// blames the pipeline for a mis-drop.
    @Test("Only movie and audio files are accepted from a drop")
    func dropFiltersByType() {
        let tmp = FileManager.default.temporaryDirectory
        for name in ["clip.mp4", "clip.mov", "clip.m4v", "CLIP.MP4", "clip.webm", "CLIP.WEBM",
                     "song.mp3", "song.m4a", "song.wav", "song.aiff", "SONG.MP3"] {
            #expect(isDroppableSource(tmp.appendingPathComponent(name)), "\(name)")
        }
        for name in ["notes.pdf", "poster.jpg", "readme.txt", "noextension"] {
            #expect(!isDroppableSource(tmp.appendingPathComponent(name)), "\(name)")
        }
        // A directory is a URL a Finder drag produces constantly.
        let dir = tmp.appendingPathComponent("naqi-drop-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(!isDroppableSource(dir))

        // The file on disk wins over the extension, which is how an extensionless
        // export from another app still gets in.
        let real = tmp.appendingPathComponent("naqi-drop-\(UUID().uuidString).mov")
        FileManager.default.createFile(atPath: real.path, contents: Data())
        defer { try? FileManager.default.removeItem(at: real) }
        #expect(isDroppableSource(real))
    }

    // MARK: - Sliders

    @Test("Strictness and blur clamp to 0…100")
    func sliderClamp() {
        #expect(clampedSliderValue(-40) == 0)
        #expect(clampedSliderValue(0) == 0)
        #expect(clampedSliderValue(49.4) == 49)
        #expect(clampedSliderValue(49.6) == 50)
        #expect(clampedSliderValue(100) == 100)
        #expect(clampedSliderValue(140) == 100)
        #expect(clampedSliderValue(.nan) == 0)
        #expect(clampedSliderValue(.infinity) == 100)
    }

    // MARK: - Queue binding

    /// The progress and done screens read nothing but `JobMonitor`, so the one
    /// thing that can silently break them is the snapshot stream not reaching
    /// it. A source that cannot be opened fails inside a second and exercises
    /// the whole path: enqueue → run → queue row → observer → `failure`.
    @Test("A failed job reaches the screens through the queue snapshot")
    @MainActor
    func failureReachesTheUI() async throws {
        let store = FileManager.default.temporaryDirectory
            .appendingPathComponent("naqi-ui-test-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: store) }

        let monitor = JobMonitor(queue: JobQueue(storeURL: store))
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("no-such-video-\(UUID().uuidString).mp4")
        await monitor.start(source: PickedSource(url: missing, name: missing.lastPathComponent),
                            ops: FilterOps())

        var waited = 0
        while monitor.failure == nil, waited < 100 {
            try await Task.sleep(for: .milliseconds(50))
            waited += 1
        }
        #expect(monitor.failure == .sourceUnreadable)
        #expect(monitor.isDone == false)
        #expect(monitor.output == nil)
        // The sentence the failure card puts on screen, not a developer string.
        #expect(String(localized: try #require(monitor.failure).sentence) != "sourceUnreadable")
    }

    /// The "N more queued" line is all that stands between a multi-file
    /// share-in and N−1 invisible jobs, and there are exactly two ways to get
    /// the number wrong: count the job the screen is already showing, or count
    /// rows that are finished. Terminal rows sit in `naqi-queue.json` until
    /// something clears them, so the second one reads as a line that appears
    /// after the first job ever runs and then never leaves.
    @Test("The queued-others count skips this job and every finished one")
    func queuedOthersCount() {
        let mine = Self.row(.running)
        let snapshot = JobQueue.Snapshot(
            jobs: [mine,
                   Self.row(.pending),
                   Self.row(.running),
                   Self.row(.done(Published(name: "a.mp4", url: nil, assetID: nil))),
                   Self.row(.failed(.generic, resumable: true)),
                   Self.row(.cancelled)],
            running: mine.id, progress: nil)

        #expect(JobMonitor.othersQueued(in: snapshot, besides: mine.id) == 2)
        // Nothing of ours in the queue: every unfinished row is somebody else's.
        #expect(JobMonitor.othersQueued(in: snapshot, besides: nil) == 3)
        #expect(JobMonitor.othersQueued(in: JobQueue.Snapshot(jobs: [], running: nil, progress: nil),
                                        besides: mine.id) == 0)
    }

    /// The count has to come off the live stream, not off a number the screen
    /// keeps for itself. Two finished rows are seeded into the store — the
    /// shape the queue file has after any two jobs — and the queue never drains
    /// them, so a count that forgot to filter terminal rows shows "2 more in
    /// the queue" for a queue that is empty.
    @Test("Finished rows in the real queue never reach the progress line")
    @MainActor
    func queuedOthersFromTheRealStream() async throws {
        let store = FileManager.default.temporaryDirectory
            .appendingPathComponent("naqi-ui-test-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: store) }
        try JSONEncoder()
            .encode([Self.row(.done(Published(name: "a.mp4", url: nil, assetID: nil))),
                     Self.row(.cancelled)])
            .write(to: store)

        let monitor = JobMonitor(queue: JobQueue(storeURL: store))
        let missing = FileManager.default.temporaryDirectory
            .appendingPathComponent("no-such-video-\(UUID().uuidString).mp4")
        await monitor.start(source: PickedSource(url: missing, name: missing.lastPathComponent),
                            ops: FilterOps())

        // Sampled while the job is still alive as well as after it dies: our own
        // row is non-terminal for that window, so a count that forgot to
        // exclude it reads 1 here.
        var peak = 0
        var waited = 0
        while monitor.failure == nil, waited < 100 {
            peak = max(peak, monitor.othersQueued)
            try await Task.sleep(for: .milliseconds(50))
            waited += 1
        }
        #expect(monitor.failure != nil)
        #expect(peak == 0)
        #expect(monitor.othersQueued == 0)
    }

    /// A queue row in a given state, with a unique source so no two share a
    /// `Checkpoint.key`.
    private static func row(_ state: Job.State) -> Job {
        var job = Job(source: URL(fileURLWithPath: "/tmp/naqi-\(UUID().uuidString).mp4"),
                      title: "queued", ops: FilterOps(), destination: .photos)
        job.state = state
        return job
    }

    // MARK: - Localization

    /// Both localizations must be in the built product. `ar` is missing from
    /// the project's `knownRegions`, and this is the assertion that catches it
    /// if the string catalog ever stops compensating.
    @Test("The app bundle carries both localizations")
    func bundleHasBothLanguages() {
        let langs = Bundle.main.localizations
        #expect(langs.contains("en"))
        #expect(langs.contains("ar"))
    }

    @Test("Every string the UI shows resolves in English and in Arabic",
          arguments: ["en", "ar"])
    func stringsResolve(_ language: String) {
        for resource in Self.everyUIString {
            var localized = resource
            localized.locale = Locale(identifier: language)
            let value = String(localized: localized)
            // A missing key resolves to the key itself, which is the exact
            // failure that made the Arabic translations of Android's eight
            // error sentences unreachable for a whole release.
            #expect(value != resource.key, "\(resource.key) is missing in \(language)")
            #expect(!value.isEmpty, "\(resource.key) is empty in \(language)")
        }
    }

    @Test("About states the current personal licence, never the inherited GPL claim",
          arguments: ["en", "ar"])
    func aboutUsesCurrentLicense(_ language: String) {
        var resource = LocalizedStringResource.aboutLicense
        resource.locale = Locale(identifier: language)
        let value = String(localized: resource)
        #expect(value.contains("GPL") == false)
        #expect(value.contains(language == "ar" ? "جميع الحقوق محفوظة" : "All rights reserved"))
    }

    @Test("Every pipeline stage has a user-visible name", arguments: ["en", "ar"])
    func stageLabels(_ language: String) {
        for stage in Job.Stage.allCases {
            var r = stage.label
            r.locale = Locale(identifier: language)
            #expect(String(localized: r) != r.key)
        }
    }

    @Test("Every failure has a sentence", arguments: ["en", "ar"])
    func failureSentences(_ language: String) {
        let all: [JobFailure] = [.nothingSelected, .drmProtected, .noVideoTrack, .noAudioTrack,
                                 .unsupportedContainer, .unsupportedCodec, .lowSpace,
                                 .outOfSpace, .sourceUnreadable, .publishFailed, .generic,
                                 .downloadUnsupported, .downloadNetwork, .downloadGeneric,
                                 .downloadUnavailable, .downloadGeo, .downloadRateLimited,
                                 .downloadForbidden, .downloadExtractor]
        for failure in all {
            var r = failure.sentence
            r.locale = Locale(identifier: language)
            #expect(String(localized: r) != r.key)
        }
    }

    /// Onboarding offers exactly the languages the bundle carries, and the
    /// pick lands where iOS reads the per-app language on the next launch.
    @Test("Onboarding languages match the bundle and persist")
    func onboardingLanguage() {
        #expect(Set(AppLanguage.all.map(\.code)) == Set(Bundle.main.localizations).subtracting(["Base"]))
        #expect(AppLanguage.all.map(\.code).contains(AppLanguage.current))

        let saved = UserDefaults.standard.object(forKey: "AppleLanguages")
        defer { UserDefaults.standard.set(saved, forKey: "AppleLanguages") }
        AppLanguage.save("ar")
        #expect(UserDefaults.standard.stringArray(forKey: "AppleLanguages") == ["ar"])
    }

    /// One of three whole sentences, never a number glued to a unit.
    @Test("durationText picks the right sentence")
    func duration() {
        #expect(durationText(ms: 0).key == "dur_under_min")
        #expect(durationText(ms: 59_000).key == "dur_under_min")
        #expect(durationText(ms: 60_000).key == "dur_min")
        #expect(durationText(ms: 59 * 60_000).key == "dur_min")
        #expect(durationText(ms: 60 * 60_000).key == "dur_h_min")
        #expect(durationText(ms: 155 * 60_000).key == "dur_h_min")
    }

    @Test("the native player loads a real video and an audio clip")
    @MainActor
    func playbackSessionLoadsMedia() async throws {
        let video = try requireQAVideo()
        let videoSession = PlaybackSession(item: .file(video, title: "clip.mp4"))
        let videoItem = try #require(videoSession.player.currentItem)
        #expect(try await videoItem.asset.load(.isPlayable))
        videoSession.stop()

        let audio = try Fixtures.audioClip("player-\(UUID().uuidString).m4a")
        defer { try? FileManager.default.removeItem(at: audio) }
        #expect(MediaKind.of(audio) == .audio)
        let audioSession = PlaybackSession(item: .file(audio, title: "clip.m4a"))
        let audioItem = try #require(audioSession.player.currentItem)
        #expect(try await audioItem.asset.load(.isPlayable))
        audioSession.stop()
    }

    @Test("Audio files play as audio, video files as video")
    func mediaKind() {
        #expect(MediaKind.of(URL(fileURLWithPath: "/tmp/clip.m4a")) == .audio)
        #expect(MediaKind.of(URL(fileURLWithPath: "/tmp/clip.mp3")) == .audio)
        #expect(MediaKind.of(URL(fileURLWithPath: "/tmp/clip.mp4")) == .video)
        #expect(MediaKind.of(URL(fileURLWithPath: "/tmp/clip.mov")) == .video)
        #expect(MediaKind.utType(of: URL(fileURLWithPath: "/tmp/clip.m4a")).conforms(to: .audio))
        #expect(MediaKind.utType(of: URL(fileURLWithPath: "/tmp/clip.mp4")).conforms(to: .movie))
    }

    @Test("File sizes are decimal, not binary")
    func sizes() {
        #expect(fileSizeText(bytes: 999_000_000).key == "jobs_size_mb")
        #expect(fileSizeText(bytes: 1_000_000_000).key == "jobs_size_gb")
    }

    /// Every resource a screen can put on glass. Listed by hand because the
    /// point of the test is the set the UI *uses*, not the set the catalog
    /// happens to contain — an unused key is untidy, a used one that is missing
    /// is a bug on screen.
    static let everyUIString: [LocalizedStringResource] = [
        // Pick
        .appName, .actionMore, .pickSealOnDevice, .pickSealPrivate,
        .pickVideoNone, .pickVideoSelected, .pickVideoChange, .pickVideoFormats,
        .pickLinkHint, .pickLinkAction,
        .pickSourcePhotos, .pickSourceFiles, .pickDropHint,
        .pickEyebrowChoose, .pickOpMusicTitle, .pickOpMusicDesc,
        .pickOpFacesTitle, .pickOpFacesDesc("Women"), .pickOpFacesDescOff,
        .actionContinue, .pickReassurance,
        .pickWordmarkAr, .pickWordmarkLatin, .pickTagline,
        // Options
        .optTitle, .actionBack, .optSectionCensorFaces,
        .optWhoTitle, .optWhoDesc, .optWhoEveryone, .optWhoWomen, .optWhoMen,
        .optWholeFrameTitle, .optWholeFrameDesc,
        .optPerformanceTitle, .optPerformanceCurrent, .optPerformanceFast,
        .optPerformanceCurrentDesc, .optPerformanceFastDesc,
        .optNsfwTitle, .optNsfwDesc,
        .optStrictnessTitle, .optStrictnessDesc,
        .optCensorStyleTitle, .optCensorStyleDesc, .optStyleBlur, .optStyleSolid,
        .optSolidGray, .optSolidBlack, .optSolidWhite, .optSolidNavy, .optSolidGreen,
        .optBlurAmountTitle, .optBlurAmountDesc,
        .optGrayscaleTitle, .optGrayscaleDesc,
        .optSectionRemoveMusic,
        .optKeepVocalsTitle, .optKeepVocalsDesc,
        .optKeepVocalsOtherTitle, .optKeepVocalsOtherDesc,
        .optSectionSaveTo, .optDestPhotosTitle, .optDestPhotosDesc,
        .optDestFolderTitle, .optDestFolderDesc, .optDestFolderChosen("Movies"),
        .optDestAudioOnly,
        .optSliderValue(50), .optEtaFloor("43 min"), .actionStart,
        .dlgLongJobTitle, .dlgLongJobBody("43 min"),
        // Progress
        .progressTitle, .progressKeepOpen, .jobsStageStarting,
        .progressMoreQueued(3),
        .jobsProgressPercent(33), .jobsEtaRemaining("22 min"), .actionCancel,
        .jobsResumeHint, .actionResume, .jobsNewJob,
        .durUnderMin, .durMin(43), .durHMin(2, 35),
        // Done
        .doneTitle, .jobsSavedLabel, .jobsSavedPhotos, .jobsSavedFolder("Movies"),
        .actionPlay, .actionShare, .actionSave, .actionDone, .actionDeleteOriginal,
        .jobsRowA11Y("clip.mp4", "Saved"),
        .dlgDeleteOriginalTitle, .dlgDeleteOriginalBody("clip.mp4"),
        .dlgDeleteOriginalFallbackName, .actionDelete, .actionKeep,
        .dlgOriginalDeleted, .dlgOriginalKept, .dlgDeleteOriginalFailed,
        // About + diagnostics
        .aboutOpen, .aboutTitle, .aboutVersion("1.0", 1), .aboutLicense,
        .aboutEyebrowPrivacy, .aboutPrivacyTitle, .aboutPrivacyBody, .aboutPrivacyBodyShare,
        .aboutEyebrowUpdates, .aboutReleasesTitle, .aboutReleasesDesc,
        .aboutEyebrowLicenses, .aboutNoticesTitle, .aboutNoticesDesc,
        .licensesTitle, .licensesIntro, .licensesSource, .licensesPersonalOnly,
        .licensesOnnxTitle, .licensesOnnxTerms,
        .licensesDemucsTitle, .licensesDemucsTerms,
        .licensesNsfwTitle, .licensesNsfwTerms,
        .licensesYamnetTitle, .licensesYamnetTerms,
        .licensesInsightFaceTitle, .licensesInsightFaceTerms,
        .pickDiagTitle, .pickDiagRunning, .diagRun, .diagNotRun,
        .diagCores, .diagMemory, .diagCompute,
        // Failure sentences
        .errDrm, .errUnreadable, .errNoVideo, .errNoAudio,
        .errLowSpace, .errUnsupportedCodec, .errOutOfSpace, .errGeneric,
        .errDownloadUnsupported, .errDownloadNetwork, .errDownloadGeneric,
        .shareUntitled, .shareEyebrowQuality, .shareEyebrowFilters,
        .shareQualityBest, .shareQuality1080, .shareQuality720, .shareQuality480,
        .shareQualityAudio, .actionDownload, .actionFilter, .shareNoUrl,
        .shareAlreadyQueued, .stageDownloading,
        .aboutEyebrowDownloader, .aboutYtdlpVersion("2026.08.19"),
        .aboutYtdlpUnknown, .aboutYtdlpDesc, .aboutUpdate, .aboutUpdating,
        .aboutUpdateOk, .aboutUpdateFailed,
        .licensesYtdlpTitle, .licensesYtdlpTerms,
        // Stage names
        .stagePreparing, .stageAnalyzing, .stageRendering,
        .stageSeparating, .stageMuxing,
        // Library
        .jobsLibrary, .jobsLibraryEmpty, .jobsNoneRunning, .jobsTitle,
        .jobsSizeMb(120), .jobsSizeGb(1.7),
        // Settings, and the unfinished-job card that offers a survivor back
        .settingsTitle, .settingsDefaultsNote,
        .pickResumeTitle, .pickResumeBody("clip.mp4"), .actionDiscard,
        // Failures that used to share the generic sentence, and the import
        // errors that used to be silent
        .errPhotosDenied, .errInterrupted, .errImportFailed,
        .errLowSpaceGb(14.2, 1.5), .actionOpenSettings, .progressPausedTitle,
        // Cancel confirmation
        .dlgCancelTitle, .dlgCancelBody, .dlgCancelConfirm, .dlgCancelKeep,
        // Notifications — not on screen, but the same failure mode
        .notifDoneTitle, .notifDoneBody("clip.mp4"), .notifFailedTitle,
        // Spoken, never drawn: a missing translation here is invisible until
        // someone turns VoiceOver on in Arabic.
        .progressBarLabel, .optStrictnessHint, .optBlurHint,
        // Onboarding
        .onbGetStarted, .onbWelcomeTitle, .onbWelcomeBody,
        .onbFeatBlur, .onbFeatMusic, .onbFeatPrivate,
        .onbBlurTitle, .onbBlurBody, .onbMusicTitle, .onbMusicBody,
        .onbReadyTitle, .onbReadyBody,
    ]
}
