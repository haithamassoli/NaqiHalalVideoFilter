package com.haithamassoli.naqi.desktop.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** What the binaries must carry: full MIT/Apache texts and a source offer for the exact GPL FFmpeg build. */
class NoticeTest {
    private fun assertHas(text: String) = assertTrue("NOTICE lacks: $text", NOTICE.contains(text))

    @Test
    fun carriesTheFullPermissiveLicenceTexts() {
        // YuNet's LICENSE at the opencv_zoo commit stage-models.sh pins, and ONNX Runtime's.
        assertHas("Copyright (c) 2020 Shiqi Yu <shiqi.yu@gmail.com>")
        assertHas("Copyright (c) Microsoft Corporation.")
        assertHas("The above copyright notice and this permission notice shall be included in all")
        // One Apache-2.0 copy covers JavaCPP, Compose, Kotlin and YAMNet.
        assertHas("Apache License\n                           Version 2.0, January 2004")
        assertHas("END OF TERMS AND CONDITIONS")
    }

    @Test
    fun offersTheSourceOfTheExactFfmpegBuildBundled() {
        // The versions the build resolves, from settings.gradle.kts (the test runs in apps/windows/app).
        val settings = File("../settings.gradle.kts").readText()
        val ffmpeg = Regex("""version\("ffmpeg", "([^"]+)"\)""").find(settings)!!.groupValues[1]
        val javacpp = Regex("""version\("javacpp", "([^"]+)"\)""").find(settings)!!.groupValues[1]
        assertHas("org.bytedeco:ffmpeg:$ffmpeg:windows-x86_64-gpl")
        assertHas("https://github.com/bytedeco/javacpp-presets/tree/$javacpp/ffmpeg")
        assertHas("https://ffmpeg.org/releases/ffmpeg-${ffmpeg.substringBefore('-')}.tar.bz2")
        assertHas("x264")
        assertHas("Written offer: for at least three years")
    }
}
