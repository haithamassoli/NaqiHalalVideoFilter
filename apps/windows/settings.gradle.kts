pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google() // Compose Multiplatform resolves its androidx.* artifacts from here
    }
    versionCatalogs {
        // Android's catalog, so Kotlin, ONNX Runtime, coroutines and org.json cannot drift between the two
        // apps (and :core reads `libs` whichever build includes it). The Windows-only entries are added here
        // rather than in that file, which stays Android's.
        create("libs") {
            from(files("../android/gradle/libs.versions.toml"))
            library("onnxruntime", "com.microsoft.onnxruntime", "onnxruntime").versionRef("onnxruntime")
            library("kotlinx-coroutines-swing", "org.jetbrains.kotlinx", "kotlinx-coroutines-swing").versionRef("coroutines")
            // A Kotlin 2.2-era release, to match the 2.2.10 compiler this build shares with Android.
            version("composeMultiplatform", "1.9.3")
            plugin("compose-multiplatform", "org.jetbrains.compose").versionRef("composeMultiplatform")
            // JavaCPP presets: <ffmpeg version>-<javacpp version>; the two must move together.
            version("javacpp", "1.5.14")
            version("ffmpeg", "8.1.2-1.5.14")
        }
    }
}

rootProject.name = "Naqi Windows"
include(":app")
// Pure-Kotlin code shared with apps/android, which includes the same directory the same way.
include(":core")
project(":core").projectDir = file("../../core")
