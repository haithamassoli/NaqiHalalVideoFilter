package com.haithamassoli.naqi.update

import com.haithamassoli.naqi.update.AppUpdate.parseVersion
import com.haithamassoli.naqi.update.AppUpdate.pickRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The version compare and the channel rule are the whole feature: get either wrong and the app
 * offers its users a *downgrade*. Both are pure functions for exactly this reason, so the trap can
 * be pinned here rather than found on someone's phone.
 *
 * [REAL] is the live `/releases` response, trimmed to the fields [pickRelease] reads. Its shape is
 * the point: every 1.2 build is tagged `prerelease`, so the obvious implementation — GitHub's
 * `/releases/latest`, which skips pre-releases — answers `v1.1`.
 */
class AppUpdateTest {

    private fun v(s: String) = requireNotNull(parseVersion(s))

    @Test
    fun numbersOrderBeforeAnythingElse() {
        assertTrue(v("1.1") < v("1.2"))
        // Not a string compare here either, and a short version is not secretly larger for being short.
        assertTrue(v("1.9") < v("1.10"))
        assertTrue(v("1.2") < v("1.2.1"))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
    }

    @Test
    fun aReleaseOutranksItsOwnPreReleases() {
        assertTrue(v("1.2-beta2") < v("1.2"))
        assertTrue(v("1.2-beta1") < v("1.2-beta2"))
        // Not a string compare: beta10 comes after beta2, not before it.
        assertTrue(v("1.2-beta2") < v("1.2-beta10"))
        // A pre-release of the next version still beats the current release.
        assertTrue(v("1.1") < v("1.2-beta1"))
        assertTrue(v("1.2-alpha9") < v("1.2-beta1"))
    }

    @Test
    fun tagsAndJunkParseTheWayReleasesAreNamed() {
        assertEquals(0, v("v1.2-beta2").compareTo(v("1.2-beta2")))
        assertEquals("1.2-beta2", v("v1.2-beta2").toString())
        assertEquals("1.1", v("v1.1").toString())
        assertNull(parseVersion("latest"))
        assertNull(parseVersion(""))
    }

    @Test
    fun theCurrentBetaIsNeverOfferedTheOlderStable() {
        // The regression this feature exists to avoid: /releases/latest returns v1.1 here.
        assertNull(pickRelease(REAL, v("1.2-beta2")))
    }

    @Test
    fun aStableBuildIsNeverPushedOntoABeta() {
        // Someone on 1.1 sees nothing at all, because 1.2-beta1/2 are pre-releases.
        assertNull(pickRelease(REAL, v("1.1")))
        // ...but a published stable reaches them.
        assertEquals("1.3", pickRelease(merge(REAL, stable("v1.3")), v("1.1"))?.version?.toString())
    }

    @Test
    fun aBetaBuildTakesBetasAndStablesAlike() {
        assertEquals("1.3", pickRelease(merge(REAL, stable("v1.3")), v("1.2-beta2"))?.version?.toString())
        assertEquals(
            "1.4-beta1",
            pickRelease(merge(REAL, stable("v1.3"), pre("v1.4-beta1")), v("1.2-beta2"))?.version?.toString(),
        )
    }

    @Test
    fun theNewestReleaseWinsRegardlessOfListOrder() {
        // GitHub orders by creation date; a re-published older tag must not win on position.
        val found = pickRelease(merge(pre("v1.4-beta1"), REAL), v("1.2-beta2"))
        assertEquals("1.4-beta1", found?.version?.toString())
    }

    @Test
    fun draftsAreNeverOffered() {
        val draft = """[{"tag_name":"v9.0","draft":true,"prerelease":false,
            "assets":[{"name":"naqi-9.0.apk","size":1,"browser_download_url":"https://x/naqi-9.0.apk"}]}]"""
        assertNull(pickRelease(draft, v("1.1")))
    }

    @Test
    fun theNamedApkWinsOverABareBuildOutput() {
        // v1.2-beta1 shipped both app-debug.apk and naqi-1.2-beta1-arm64-v8a-debug.apk. Installed is
        // a pre-release, so the pre-release tags are in play — from a stable 1.0 only v1.1 would be.
        assertEquals("1.2-beta2", pickRelease(REAL, v("1.0-beta1"))?.version?.toString())
        assertEquals("1.1", pickRelease(REAL, v("1.0"))?.version?.toString())
        val beta1 = pickRelease(ONLY_BETA1, v("1.0-beta1"))
        assertTrue(beta1?.apkUrl.orEmpty().endsWith("naqi-1.2-beta1-arm64-v8a-debug.apk"))
        assertEquals(224823084L, beta1?.sizeBytes)
    }

    @Test
    fun aReleaseWithNoApkIsSkippedRatherThanOffered() {
        val sourceOnly = """[{"tag_name":"v2.0","draft":false,"prerelease":false,
            "assets":[{"name":"sources.zip","size":9,"browser_download_url":"https://x/sources.zip"}]}]"""
        assertNull(pickRelease(sourceOnly, v("1.1")))
        // ...and it does not hide a good release published alongside it.
        assertEquals("1.3", pickRelease(merge(sourceOnly, stable("v1.3")), v("1.1"))?.version?.toString())
    }
}

private fun stable(tag: String) = release(tag, pre = false)
private fun pre(tag: String) = release(tag, pre = true)

private fun release(tag: String, pre: Boolean): String {
    val name = "naqi-${tag.removePrefix("v")}.apk"
    return """[{"tag_name":"$tag","draft":false,"prerelease":$pre,
        "assets":[{"name":"$name","size":220000000,"browser_download_url":"https://x/$name"}]}]"""
}

/** Splice JSON arrays into one: `[a]` + `[b]` → `[a,b]`. Keeps the fixtures readable. */
private fun merge(vararg arrays: String) =
    arrays.joinToString(",") { it.trim().removePrefix("[").removeSuffix("]") }.let { "[$it]" }

private const val ONLY_BETA1 = """[
  {"tag_name":"v1.2-beta1","draft":false,"prerelease":true,"assets":[
    {"name":"app-debug.apk","size":224823084,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.2-beta1/app-debug.apk"},
    {"name":"naqi-1.2-beta1-arm64-v8a-debug.apk","size":224823084,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.2-beta1/naqi-1.2-beta1-arm64-v8a-debug.apk"}]}
]"""

/** The live response on 2026-08-03, trimmed to the fields we read. */
private const val REAL = """[
  {"tag_name":"v1.2-beta2","draft":false,"prerelease":true,"assets":[
    {"name":"naqi-1.2-beta2.apk","size":219579313,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.2-beta2/naqi-1.2-beta2.apk"}]},
  {"tag_name":"v1.2-beta1","draft":false,"prerelease":true,"assets":[
    {"name":"app-debug.apk","size":224823084,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.2-beta1/app-debug.apk"},
    {"name":"naqi-1.2-beta1-arm64-v8a-debug.apk","size":224823084,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.2-beta1/naqi-1.2-beta1-arm64-v8a-debug.apk"}]},
  {"tag_name":"v1.1","draft":false,"prerelease":false,"assets":[
    {"name":"naqi-1.1-arm64-v8a.apk","size":210719592,
     "browser_download_url":"https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.1/naqi-1.1-arm64-v8a.apk"}]}
]"""
