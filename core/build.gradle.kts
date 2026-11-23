// Shared by apps/android and apps/windows. Each includes this directory as ":core" and supplies the
// version catalog (`libs`), so the Kotlin version is whatever apps/android/gradle/libs.versions.toml says.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Java 11 bytecode, the same target the Android app compiles to (app/build.gradle.kts compileOptions).
// No toolchain: like the app, this compiles with the JDK running Gradle.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    implementation(libs.json) // Edl's JSON form; Android excludes it (the framework has org.json)
    testImplementation(libs.junit)
}
