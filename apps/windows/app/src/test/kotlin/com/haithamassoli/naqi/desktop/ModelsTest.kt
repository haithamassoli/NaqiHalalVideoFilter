package com.haithamassoli.naqi.desktop

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModelsTest {
    @Test
    fun overridesWinOverTheBundledDir() {
        assertEquals(File("/p"), Models.resolveDir("/p", "/e", "/r"))
        assertEquals(File("/e"), Models.resolveDir(null, "/e", "/r"))
        assertEquals(File("/e"), Models.resolveDir("", "/e", "/r"))
        assertEquals(File("/r", "models"), Models.resolveDir(null, null, "/r"))
    }

    @Test(expected = IllegalStateException::class)
    fun noDirIsAnErrorNotTheWorkingDirectory() {
        Models.resolveDir(null, " ", null)
    }
}
