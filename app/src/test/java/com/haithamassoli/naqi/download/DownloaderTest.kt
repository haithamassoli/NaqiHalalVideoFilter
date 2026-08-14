package com.haithamassoli.naqi.download

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
}
