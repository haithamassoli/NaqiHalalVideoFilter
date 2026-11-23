plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

// core/ is shared with the Android build. Its default build dir (core/build) would then be written by two
// independent Gradle builds that do not know about each other; this one keeps its output under apps/windows.
project(":core") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core"))
}
