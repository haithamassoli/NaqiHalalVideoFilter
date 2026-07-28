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
            // Keep native .so uncompressed + page-aligned (required for ONNX Runtime's 16 KB-page libs).
            useLegacyPackaging = false
        }
    }
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

    testImplementation(libs.junit)
    testImplementation(libs.json) // real org.json impl so Edl JSON round-trip tests run on the JVM
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
