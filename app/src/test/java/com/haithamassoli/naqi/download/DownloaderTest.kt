package com.haithamassoli.naqi.download

import com.yausername.youtubedl_android.YoutubeDLException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderTest {

    @Test
    fun qualitiesKeepTheirWireNamesAndResolutionCaps() {
        assertEquals(
            listOf("BEST" to null, "P1080" to 1080, "P720" to 720, "P480" to 480, "AUDIO" to null),
            Downloader.Quality.entries.map { it.name to it.maxHeight },
        )
        assertEquals(Downloader.Quality.P1080, Downloader.Quality.of("P1080"))
        assertEquals(Downloader.Quality.P480, Downloader.Quality.of("P480"))
        assertEquals(Downloader.Quality.BEST, Downloader.Quality.of(null))
    }

    @Test
    fun formatChoiceFollowsFiltersAndHardware() {
        val s23 = mapOf("avc" to 2160, "hevc" to 2160, "vp9" to 2160, "av1" to 2160)
        val noAv1 = mapOf("avc" to 1080, "vp9" to 1080)
        fun f(q: Downloader.Quality, filtered: Boolean, hw: Map<String, Int>) =
            Downloader.formatArgs(q, filtered, hw).let { it[1] to it[3] }

        // Filtered "Best": capped at 1080p, ≤30 fps preferred, h264 first.
        assertEquals("bv*+ba/b" to "res:1080,fps:30,+vcodec:avc,+acodec:m4a", f(Downloader.Quality.BEST, true, s23))
        // Unfiltered "Best": only the hardware limits it.
        assertEquals("bv*+ba/b" to "res:2160,+vcodec:avc,+acodec:m4a", f(Downloader.Quality.BEST, false, s23))
        // No HW AV1 decoder: AV1 excluded, with a fallback for AV1-only sources; HW caps at 1080.
        assertEquals(
            "bv*[vcodec!^=av01]+ba/b/bv*+ba" to "res:1080,+vcodec:avc,+acodec:m4a",
            f(Downloader.Quality.BEST, false, noAv1),
        )
        // An explicit user cap below the hardware wins.
        assertEquals("bv*+ba/b" to "res:720,fps:30,+vcodec:avc,+acodec:m4a", f(Downloader.Quality.P720, true, s23))
        // Probe failed: exclude nothing, cap only by the user's choice.
        assertEquals("bv*+ba/b" to "+vcodec:avc,+acodec:m4a", f(Downloader.Quality.BEST, false, emptyMap()))
        assertEquals("ba/b" to "+acodec:m4a", f(Downloader.Quality.AUDIO, true, s23))
    }

    @Test
    fun requestCarriesSpeedAndResilienceFlags() {
        val cmd = Downloader.buildRequest("https://x.test/v", "/q/%(title)s.%(ext)s", Downloader.Quality.BEST, true, emptyMap(), Downloader.Aria2("/lib/libaria2c.so", "/py/cert.pem"))
            .buildCommand()
        fun values(flag: String) = cmd.indices.filter { cmd[it] == flag }.map { cmd[it + 1] }

        // Absolute path, so the library doesn't add its own (overriding) aria2c args; ours carry both.
        assertEquals(listOf("/lib/libaria2c.so", "dash,m3u8:native"), values("--downloader"))
        assertEquals(listOf("aria2c:--summary-interval=1 --ca-certificate=/py/cert.pem"), values("--downloader-args"))
        assertEquals(listOf("4"), values("-N"))
        assertEquals(listOf("http:exp=1:30", "fragment:exp=1:30"), values("--retry-sleep"))
        assertEquals(listOf("100K"), values("--throttled-rate"))
        assert("--abort-on-unavailable-fragments" in cmd)
        assert("--no-playlist" in cmd)
        assert("--extract-audio" !in cmd)

        val native = Downloader.buildRequest("https://x.test/v", "/q/o", Downloader.Quality.BEST, true, emptyMap(), aria2 = null)
            .buildCommand()
        assert("--downloader" !in native)
    }

    @Test
    fun aria2SkipsYouTubeOnly() {
        // YouTube throttles aria2c to ~30 KiB/s; everything else gets it.
        for (u in listOf("https://www.youtube.com/watch?v=x", "https://youtu.be/x", "https://m.youtube.com/shorts/x", "https://music.youtube.com/watch?v=x")) {
            assertEquals(u, false, Downloader.useAria2(u))
        }
        for (u in listOf("https://archive.org/details/x", "https://www.dailymotion.com/video/x", "https://notyoutube.com/v")) {
            assertEquals(u, true, Downloader.useAria2(u))
        }
        assertEquals(false, Downloader.useAria2("not a url"))
    }

    @Test
    fun parsesProgressTemplateAndAria2Lines() {
        // Real lines, captured 2026-09-27 from yt-dlp 2026.08.19 and aria2c 1.37.
        assertEquals(
            Downloader.DlProgress(2096128, 6475268, 2436992, 1, "134"),
            Downloader.parseProgress("[download]  32.4% ETA 00:01 |naqi|2096128|6475268|2436992.2564156153|1|134"),
        )
        // HLS: estimated total as a float, speed not measured yet on the very first fragment.
        assertEquals(
            Downloader.DlProgress(1024, 6475268, -1, -1, "134"),
            Downloader.parseProgress("[download]   0.0% ETA Unknown |naqi|1024|6475268|NA|NA|134"),
        )
        assertEquals(
            Downloader.DlProgress(1517524, 12895169, 823927, 17, "hls-480"),
            Downloader.parseProgress("[download]  11.8% ETA 00:17 |naqi|1517524|12895169.0|823927.7252000046|17.433304199868566|hls-480"),
        )
        // "has already been downloaded" reports no byte count: not progress.
        assertEquals(null, Downloader.parseProgress("[download] 100.0% ETA NA |naqi|NA|61878609|NA|NA|1"))
        assertEquals(
            Downloader.DlProgress(1153433, 61865984, 943718, 60, "642275"),
            Downloader.parseProgress("[#642275 1.1MiB/59MiB(1%) CN:16 DL:0.9MiB ETA:1m]"),
        )
        assertEquals(
            Downloader.DlProgress(16384, 61865984, 162816, 378, "642275"),
            Downloader.parseProgress("[#642275 16KiB/59MiB(0%) CN:16 DL:159KiB ETA:6m18s]"),
        )
        assertEquals(-1L, Downloader.parseProgress("[#642275 0B/0B CN:1 DL:0B]")?.etaSec)
        assertEquals(null, Downloader.parseProgress("[youtube] Extracting URL: https://www.youtube.com/watch?v=x"))
        assertEquals(null, Downloader.parseProgress("[Merger] Merging formats into \"x.mp4\""))
        assertEquals(32, Downloader.parseProgress("[download]  32.4% ETA 00:01 |naqi|2096128|6475268|2436992.2|1|134")?.percent)
    }

    @Test
    fun streamsCarryBytesSoTheCountNeverResets() {
        val t = Downloader.StreamTotals()
        fun p(done: Long, total: Long, stream: String) = Downloader.DlProgress(done, total, 100, 1, stream)
        t.add(p(500, 1000, "137"))
        assertEquals(p(1000, 1000, "137"), t.add(p(1000, 1000, "137")))
        // Audio starts at 0: the video's 1000 bytes carry over.
        assertEquals(p(1000, 1100, "140"), t.add(p(0, 100, "140")))
        assertEquals(p(1100, 1100, "140"), t.add(p(100, 100, "140")))
        // Unknown total stays unknown rather than becoming "carried + -1".
        assertEquals(-1L, t.add(p(5, -1, "hls")).total)
    }

    @Test
    fun ytDlpFailureUpdatesAndRetriesExactlyOnce() {
        var attempts = 0
        var updates = 0

        val result = Downloader.retryOnceAfterYtDlpFailure(
            afterFailure = { updates++ },
        ) {
            if (++attempts == 1) throw YoutubeDLException("stale extractor")
            "downloaded"
        }

        assertEquals("downloaded", result)
        assertEquals(2, attempts)
        assertEquals(1, updates)
    }
}
