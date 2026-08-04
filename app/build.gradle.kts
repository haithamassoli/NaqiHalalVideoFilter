plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.haithamassoli.naqi"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.haithamassoli.naqi"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "1.2.1"

        // Base URL for the M3 model downloader's *converted* artifacts (the NSFW gate and htdemucs —
        // NudeNet carries its own public release URL). Empty by default: no host is published yet, and
        // ModelDownloader reports "no download source configured" rather than building a bogus request.
        // Set with -PnaqiModelBaseUrl=https://host/path (https only — cleartext is blocked at API 28+).
        buildConfigField("String", "NAQI_MODEL_BASE_URL", "\"${project.findProperty("naqiModelBaseUrl") ?: ""}\"")

        // The adb-only hooks — the soak autorun (`MainActivity.kt`) and the forced-interval E2E hook
        // (`FilterWorker.kt`) — used to be gated on BuildConfig.DEBUG, which tied them to a debuggable
        // build. The `benchmark` type below is non-debuggable *and* has to run them, so they get their
        // own field. Off here so the default for any build type is "no hooks"; the two types that want
        // them opt in explicitly.
        buildConfigField("boolean", "DEBUG_HOOKS", "false")

        // arm64-v8a only: ONNX Runtime ships a universal AAR (~108 MB of .so across 4 ABIs);
        // restrict to the one ABI we support.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing reads four properties from ~/.gradle/gradle.properties (naqiStoreFile,
    // naqiStorePassword, naqiKeyAlias, naqiKeyPassword) so no secret ever lands in the repo.
    // Absent → no signingConfig is attached and assembleRelease produces an unsigned APK, which is
    // the honest outcome: a build that silently fell back to the debug key would be worse.
    val storeFilePath = project.findProperty("naqiStoreFile") as String?
    if (storeFilePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = project.property("naqiStorePassword") as String
                keyAlias = project.property("naqiKeyAlias") as String
                keyPassword = project.property("naqiKeyPassword") as String
            }
        }
    }

    buildTypes {
        // No applicationIdSuffix here on purpose. Debug keeps the plain `com.haithamassoli.naqi` id
        // that every device-verified adb command in the notes targets — the autorun `am start`, the
        // `pm grant`, the `run-as` staging route. Suffixing it would silently retarget all of them, and
        // `benchmark` below already carries its own suffix, so nothing in this change needs debug to move.
        debug {
            buildConfigField("boolean", "DEBUG_HOOKS", "true")
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = false
            }
        }
        // docs/video-performance-plan-v2.md §4.1. Every performance number in the repo was taken from a
        // debuggable build. Native code (ORT, ML Kit, MediaCodec) does not care, but the Kotlin YUV
        // convert loop is 26 of the 70.5 analyze minutes on a film, and a tight numeric loop typically
        // gives back 1.3–2× once ART is allowed to optimise it. Nothing else in the plan can be ranked
        // until that number exists, so this type exists purely to re-take the baseline.
        //
        // Declared *after* `release` on purpose: initWith copies release's state as it stands at this
        // point in the script, so moving this block above it would silently measure a different
        // configuration. What it inherits that matters is `optimization { enable = false }` — benchmark
        // and release must be the same code, that being the entire point.
        create("benchmark") {
            initWith(getByName("release"))
            // Already false via release; stated anyway because it is the one property this build type
            // is for, and a reader must not have to go and check what release does.
            isDebuggable = false
            // Release signing reads four gradle properties that are usually absent, which would leave
            // this unsigned and therefore un-installable. The debug key is always present, so
            // `installBenchmark` always works — and which key signed it cannot affect a timing run.
            signingConfig = signingConfigs.getByName("debug")
            // Installs alongside debug/release, so a measurement run never has to uninstall the build
            // being used for anything else.
            applicationIdSuffix = ".benchmark"
            // The whole instrument. Without the autorun hook there is no way to start a job on a
            // non-debuggable build from adb, and the SOAK log lines never get emitted.
            buildConfigField("boolean", "DEBUG_HOOKS", "true")
        }
    }
    // The autorun hook is handed a path under /sdcard/Download and opens it directly, which needs the
    // READ_MEDIA_VIDEO that only the debug source set declares. The debug build's other route in —
    // `run-as <pkg> cp` into the internal files dir — does not exist on a non-debuggable APK, so
    // /sdcard/Download is the only way to stage a clip here and the permission has to be declared.
    // Pointed at debug's manifest rather than copied into a src/benchmark one: two copies of a
    // permission list is exactly the kind of thing that drifts. Still needs a one-off
    // `adb shell pm grant com.haithamassoli.naqi.benchmark android.permission.READ_MEDIA_VIDEO`.
    sourceSets.getByName("benchmark").manifest.srcFile("src/debug/AndroidManifest.xml")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG_HOOKS gates the debug autorun + forced-interval E2E hooks
    }
    packaging {
        jniLibs {
            // TRUE, forced by youtubedl-android: it reads libpython.zip.so out of
            // applicationInfo.nativeLibraryDir, which holds no real files unless the libs are extracted
            // at install time. It was false for ONNX Runtime, on the belief that 16 KB-page support
            // needed in-APK alignment — it does not: 16 KB alignment is a property of the .so's own ELF
            // LOAD segments, and an extracted lib is mmap'd from the filesystem where APK zip alignment
            // is irrelevant. See docs/m4-packaging-spike.md. Cost: the libs are stored twice on device.
            useLegacyPackaging = true
        }
        resources {
            // youtubedl-android pulls in commons-compress + Jackson, which ship the same LICENSE/NOTICE
            // metadata paths. Nothing in the app reads them; first one wins.
            excludes += setOf("META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*,DEPENDENCIES}")
        }
    }
}

// The in-app "Open source licenses" screen reads the repo's own NOTICE file, copied into assets by the
// build. Deliberately not a second copy pasted into strings.xml: two copies of an attribution list is
// exactly the kind of thing that silently drifts, and the one the user sees is the one that has to be
// right. A typed task rather than a bare Copy because AGP's Variant API needs a DirectoryProperty
// output to wire the task dependency itself.
abstract class CopyNoticeTask : DefaultTask() {
    @get:InputFile
    abstract val notice: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        notice.get().asFile.copyTo(outputDir.get().file("NOTICE").asFile, overwrite = true)
    }
}

val copyNotice = tasks.register<CopyNoticeTask>("copyNotice") {
    notice.set(rootProject.layout.projectDirectory.file("NOTICE"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(copyNotice, CopyNoticeTask::outputDir)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)

    // M0 foundations
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.onnxruntime.android)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.mlkit.face.detection)

    // M4: yt-dlp + a bundled ffmpeg. GPL-3.0 — linking these relicenses the app (see LICENSE).
    implementation(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json impl so Edl JSON round-trip tests run on the JVM
    debugImplementation(libs.androidx.compose.ui.tooling)
}
