import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// No toolchain, like apps/android: this compiles for, and jpackage bundles, the JDK running Gradle, which
// gradle/gradle-daemon-jvm.properties pins to 21.

val appVersion = "0.1.0"

// GPL FFmpeg (it includes libx264), with natives for only the platforms in `javacpp.platform`
// (gradle.properties) instead of the ~15 that ffmpeg-platform-gpl drags in.
val javacppPlatforms = providers.gradleProperty("javacpp.platform").get().split(',').map { it.trim() }
val javacpp = libs.versions.javacpp.get()
val ffmpeg = libs.versions.ffmpeg.get()

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing) // Dispatchers.Main on the AWT thread
    implementation(libs.onnxruntime)
    implementation("org.bytedeco:ffmpeg:$ffmpeg")
    for (platform in javacppPlatforms) {
        runtimeOnly("org.bytedeco:javacpp:$javacpp:$platform")
        runtimeOnly("org.bytedeco:ffmpeg:$ffmpeg:$platform-gpl")
    }
    testImplementation(libs.junit)
    // runComposeUiTest for the UI test (headless, offscreen); same Compose version as the app.
    testImplementation(compose.desktop.uiTestJUnit4)
}

// Git's bash on Windows, by full path: a bare "bash" there can resolve to System32's WSL launcher, which
// Windows searches before PATH.
val bash = File(System.getenv("ProgramFiles") ?: "", "Git/bin/bash.exe").takeIf { it.isFile }?.path ?: "bash"

// The models are not in git. stage-models.sh copies or downloads them into apps/windows/models and checks
// their hashes; the Sync puts them where Compose expects app resources (<root>/common/...), so they ship
// inside the distribution and the runtime finds them via `compose.application.resources.dir`.
val stageModels = tasks.register<Exec>("stageModels") {
    workingDir = rootDir
    commandLine(bash, "scripts/stage-models.sh")
    inputs.file(rootProject.file("scripts/stage-models.sh"))
    outputs.dir(rootProject.file("models"))
}
val appResources = layout.buildDirectory.dir("app-resources")
val bundleModels = tasks.register<Sync>("bundleModels") {
    from(stageModels)
    into(appResources.map { it.dir("common/models") })
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(bundleModels) }

tasks.test {
    // Tests that need a model read it from the staged dir and skip when it is absent (see Models.kt).
    systemProperty("naqi.models.dir", rootProject.file("models").absolutePath)
}

compose.desktop {
    application {
        mainClass = "com.haithamassoli.naqi.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Naqi"
            packageVersion = appVersion
            vendor = "Haitham Assoli"
            description = "Removes music and censors faces in local video files, offline."
            licenseFile.set(rootProject.file("../../LICENSE"))
            appResourcesRootDir.set(appResources)
            // What `./gradlew :app:suggestRuntimeModules` reports; rerun it when a dependency changes.
            modules("java.instrument", "jdk.unsupported")
            windows {
                // Fixed forever: MSI matches it to upgrade an installed copy in place.
                upgradeUuid = "424D4E19-8C72-4EB6-A89A-B86F2CF128FD"
                perUserInstall = true // no UAC prompt
                menuGroup = "Naqi"
                shortcut = true
                // branding/naqi-icon-512.png at 256 px (jpackage on Windows takes only .ico):
                // ffmpeg -i branding/naqi-icon-512.png -vf scale=256:256 -c:v png -pix_fmt rgba naqi.ico
                iconFile.set(project.file("naqi.ico"))
            }
            // jpackage on macOS rejects a 0.x version. Mac builds are local test runs and never ship.
            macOS { packageVersion = "1.0.0" }
        }
    }
}

// The portable build: the self-contained app image (createDistributable) zipped, for users who cannot or
// will not run an installer.
tasks.register<Zip>("packagePortableZip") {
    from(tasks.named("createDistributable"))
    archiveFileName.set("Naqi-$appVersion-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main/zip"))
}
