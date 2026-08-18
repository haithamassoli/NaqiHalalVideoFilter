package com.haithamassoli.naqi.download

import com.yausername.youtubedl_android.YoutubeDLException
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloaderTest {

    @Test
    fun qualitiesKeepTheirWireNamesAndResolutionCaps() {
        assertEquals(
            listOf(
                "BEST" to "bv*+ba/b",
                "P1080" to "bv*[height<=1080]+ba/b[height<=1080]",
                "P720" to "bv*[height<=720]+ba/b[height<=720]",
                "P480" to "bv*[height<=480]+ba/b[height<=480]",
                "AUDIO" to "ba/b",
            ),
            Downloader.Quality.entries.map { it.name to it.selector },
        )
        assertEquals(Downloader.Quality.P1080, Downloader.Quality.of("P1080"))
        assertEquals(Downloader.Quality.P480, Downloader.Quality.of("P480"))
        assertEquals(Downloader.Quality.BEST, Downloader.Quality.of(null))
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
