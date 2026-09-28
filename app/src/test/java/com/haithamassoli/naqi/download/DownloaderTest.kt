package com.haithamassoli.naqi.download

import com.haithamassoli.naqi.model.FilterOps
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
    fun formatChoiceFollowsWhatTheFiltersDoToTheFrames() {
        val s23 = mapOf("avc" to 2160, "hevc" to 2160, "vp9" to 2160, "av1" to 2160)
        val old = mapOf("avc" to 1080, "hevc" to 1080, "vp9" to 1080) // no AV1 decoder, 1080p max
        val none = Downloader.Processing.NONE
        val music = Downloader.Processing.MUSIC
        val visual = Downloader.Processing.VISUAL
        fun f(q: Downloader.Quality, p: Downloader.Processing, hw: Map<String, Int>, sdk: Int = 36) =
            Downloader.formatArgs(q, p, hw, sdk).joinToString(" ")

        // No filters: yt-dlp's own best, capped only by the hardware.
        assertEquals("-f bv*+ba/b -S res:2160", f(Downloader.Quality.BEST, none, s23))
        assertEquals(
            "-f bv*[vcodec!^=?av01]+ba/b[vcodec!^=?av01]/bv*+ba/b -S res:1080",
            f(Downloader.Quality.BEST, none, old),
        )
        // Music only: full resolution and fps kept; never VP9; H.264 first at equal resolution.
        assertEquals(
            "-f bv*[vcodec!^=?vp09][vcodec!^=?vp9]+ba/b[vcodec!^=?vp09][vcodec!^=?vp9]/bv*+ba/b -S res:2160,+vcodec:avc,+acodec:m4a",
            f(Downloader.Quality.BEST, music, s23),
        )
        // ...and not AV1 either below Android 14, where MediaMuxer can't carry it.
        assertEquals(
            "-f bv*[vcodec!^=?av01][vcodec!^=?vp09][vcodec!^=?vp9]+ba/b[vcodec!^=?av01][vcodec!^=?vp09][vcodec!^=?vp9]/bv*+ba/b " +
                "-S res:2160,+vcodec:avc,+acodec:m4a",
            f(Downloader.Quality.BEST, music, s23, sdk = 33),
        )
        // Visual filter (with or without music): ≤1080p, ≤30 fps, SDR, most efficient HW codec (default order).
        assertEquals("-f bv*+ba/b -S res:1080,fps:30,hdr:sdr,+acodec:m4a", f(Downloader.Quality.BEST, visual, s23))
        assertEquals("-f bv*+ba/b -S res:720,fps:30,hdr:sdr,+acodec:m4a", f(Downloader.Quality.P720, visual, s23))
        assertEquals(visual, Downloader.Processing.of(FilterOps(removeMusic = true, censorWho = FilterOps.DEFAULT_WHO)))
        assertEquals(music, Downloader.Processing.of(FilterOps(removeMusic = true)))
        assertEquals(none, Downloader.Processing.of(FilterOps()))
        // Probe failed: exclude nothing for hardware reasons, cap only by the user.
        assertEquals("-f bv*+ba/b", f(Downloader.Quality.BEST, none, emptyMap()))
        assertEquals("-f ba/b -S +acodec:m4a", f(Downloader.Quality.AUDIO, visual, s23))
    }

    @Test
    fun requestCarriesSpeedAndResilienceFlags() {
        val cmd = Downloader.buildRequest("https://x.test/v", "/q/%(title)s.%(ext)s", Downloader.Quality.BEST, Downloader.Processing.VISUAL, emptyMap(),
            Downloader.Attempt(Downloader.Aria2("/lib/libaria2c.so", "/py/cert.pem"), infoJson = "/q/info.json", ipv4 = false),
        ).buildCommand()
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
        assertEquals(listOf("/q/info.json"), values("--load-info-json"))
        assert("--force-ipv4" !in cmd)

        val native = Downloader.buildRequest("https://x.test/v", "/q/o", Downloader.Quality.BEST, Downloader.Processing.VISUAL, emptyMap(),
            Downloader.Attempt(aria2 = null, infoJson = null, ipv4 = true),
        ).buildCommand()
        assert("--downloader" !in native)
        assert("--load-info-json" !in native)
        assert("--force-ipv4" in native)
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
    fun classifiesRealYtDlpErrors() {
        val cases = mapOf(
            // Real messages (yt-dlp 2026.09, S23 logcat and Mac runs), including the warnings that precede them.
            "WARNING: [vimeo] The extractor is attempting impersonation, but no impersonate target is available. If you encounter errors, then see ...\n" +
                "ERROR: [vimeo] 1084537: The web client only works when logged-in. Use --cookies, --cookies-from-browser" to Downloader.DlError.UNAVAILABLE,
            "ERROR: [youtube] aaaaaaaaaaa: Video unavailable. This video is not available" to Downloader.DlError.UNAVAILABLE,
            // Exactly what the S23 got for a non-existent id on 2026-09-27.
            "ERROR: [youtube] aaaaaaaaaaa: This video is unavailable" to Downloader.DlError.UNAVAILABLE,
            "ERROR: [youtube] x: Private video. Sign in if you've been granted access to this video" to Downloader.DlError.UNAVAILABLE,
            "ERROR: Unsupported URL: https://example.com/" to Downloader.DlError.UNAVAILABLE,
            "ERROR: [youtube] x: Sign in to confirm you're not a bot. Use --cookies-from-browser" to Downloader.DlError.EXTRACTOR,
            "ERROR: [youtube] x: The uploader has not made this video available in your country" to Downloader.DlError.GEO,
            "ERROR: unable to download video data: HTTP Error 403: Forbidden" to Downloader.DlError.FORBIDDEN,
            "ERROR: [youtube] x: HTTP Error 429: Too Many Requests" to Downloader.DlError.RATE_LIMITED,
            "ERROR: [youtube] x: Requested format is not available. Use --list-formats" to Downloader.DlError.EXTRACTOR,
            "ERROR: [generic] Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>" to Downloader.DlError.NETWORK,
            "ERROR: unable to download video data: <urlopen error _ssl.c:1000: The handshake operation timed out>" to Downloader.DlError.NETWORK,
            "No space left on device (download aborted)" to Downloader.DlError.NO_SPACE,
            // A warning about a retried fragment must not decide the class of an unrelated error.
            "WARNING: [download] Got error: Connection reset. Retrying fragment 3\nERROR: [youtube] x: Video unavailable" to Downloader.DlError.UNAVAILABLE,
            "something new and strange" to Downloader.DlError.UNKNOWN,
        )
        for ((message, expected) in cases) assertEquals(message, expected, Downloader.classify(message))
    }

    @Test
    fun eachFailureGetsItsOwnRecovery() {
        val aria = Downloader.Attempt(Downloader.Aria2("/a", "/c"), infoJson = null, ipv4 = false)
        val plain = Downloader.Attempt(aria2 = null, infoJson = null, ipv4 = false)
        val reused = plain.copy(infoJson = "/q/info.json")
        fun r(e: Downloader.DlError, first: Downloader.Attempt, update: Boolean = true) = Downloader.recoveryFor(e, first, update)

        // Retrying cannot help these.
        for (e in listOf(Downloader.DlError.UNAVAILABLE, Downloader.DlError.GEO, Downloader.DlError.NO_SPACE)) {
            assertEquals(null, r(e, aria))
        }
        // Stale extractor: update (when allowed) and retry natively.
        assertEquals(Downloader.Retry(update = true, attempt = plain), r(Downloader.DlError.EXTRACTOR, aria))
        assertEquals(Downloader.Retry(update = false, attempt = plain), r(Downloader.DlError.UNKNOWN, plain, update = false))
        // 403: fresh extraction over IPv4, never an update.
        assertEquals(Downloader.Retry(update = false, attempt = plain.copy(ipv4 = true)), r(Downloader.DlError.FORBIDDEN, reused))
        // Network / rate limit: yt-dlp already retried — only worth another go without the shortcuts.
        assertEquals(null, r(Downloader.DlError.NETWORK, plain))
        assertEquals(null, r(Downloader.DlError.RATE_LIMITED, plain))
        assertEquals(Downloader.Retry(update = false, attempt = plain), r(Downloader.DlError.RATE_LIMITED, aria))
        assertEquals(Downloader.Retry(update = false, attempt = plain), r(Downloader.DlError.NETWORK, reused))
    }

    @Test
    fun parsesProbeJson() {
        // Trimmed from real `-J` output: merged selections report filesize_approx, single streams filesize.
        assertEquals(
            Downloader.Info("\"Caminandes 2: Gran Dillama\" - Blender Animated Short", 146, 55105605),
            Downloader.parseInfo("""{"title": "\"Caminandes 2: Gran Dillama\" - Blender Animated Short", "duration": 146, "filesize": null, "filesize_approx": 55105605}"""),
        )
        assertEquals(Downloader.Info("a", 146, 2365262), Downloader.parseInfo("""{"title": "a", "duration": 146.0, "filesize": 2365262, "filesize_approx": 2365251}"""))
        assertEquals(Downloader.Info(null, -1, -1), Downloader.parseInfo("""{"title": ""}"""))
    }
}
