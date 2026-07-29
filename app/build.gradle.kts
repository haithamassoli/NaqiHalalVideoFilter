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
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Base URL for the M3 model downloader's *converted* artifacts (the NSFW gate and htdemucs —
        // NudeNet carries its own public release URL). Empty by default: no host is published yet, and
        // ModelDownloader reports "no download source configured" rather than building a bogus request.
        // Set with -PnaqiModelBaseUrl=https://host/path (https only — cleartext is blocked at API 28+).
        buildConfigField("String", "NAQI_MODEL_BASE_URL", "\"${project.findProperty("naqiModelBaseUrl") ?: ""}\"")

        // arm64-v8a only: ONNX Runtime ships a universal AAR (~108 MB of .so across 4 ABIs);
        // restrict to the one ABI we support.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG gates the debug autorun + forced-interval E2E hooks
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
    implementation(libs.androidx.compose.ui.tooling.preview)
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
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
