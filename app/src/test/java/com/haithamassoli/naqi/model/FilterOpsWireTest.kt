package com.haithamassoli.naqi.model

import androidx.work.workDataOf
import com.haithamassoli.naqi.work.Queue
import com.haithamassoli.naqi.work.filterOps
import com.haithamassoli.naqi.work.pairs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FilterOps.censorWho] is a wire format, not just a field: it is persisted in `queue.json` and in the
 * input `Data` of jobs already enqueued in WorkManager's database. Both were written by builds that
 * only knew the `censorWomen` boolean, so every reader falls back to it when the new key is absent
 * (plan-censor-who §1.1). That fallback is the half of this rename that can rot silently — lose it and
 * an in-flight job re-defaults to "do not censor", i.e. a full render that hands back the video
 * uncensored, which is the worst failure this app has.
 *
 * The legacy key names are literals here on purpose: they are what is already sitting in a file or a
 * database row, so renaming the constant has to fail in this file rather than quietly orphan them.
 *
 * `Prefs.kt:54` and the `MainActivity.kt:208` debug intent take the same fallback but need a
 * `Context`/`Intent`; they are not reachable from a JVM test and are unasserted.
 */
class FilterOpsWireTest {

    @Test
    fun everyStateSurvivesTheWire() {
        for (who in listOf(FilterOps.NONE, FilterOps.EVERYONE, FilterOps.WOMEN, FilterOps.MEN)) {
            assertEquals(who, FilterOps.whoOrNull(who))
        }
        // Hand-typed at an adb `--es` extra, so a valid choice must survive the shape it gets typed in.
        assertEquals(FilterOps.WOMEN, FilterOps.whoOrNull(" Women "))
    }

    /**
     * Absent has to read as null rather than as a state: null is the signal that sends each reader to
     * the legacy boolean. The readers disagree on what absent looks like — `JSONObject.optString`
     * returns "", the other three return null — so both spellings mean the same thing here.
     */
    @Test
    fun anAbsentKeyReadsAsAbsentInBothSpellings() {
        assertNull(FilterOps.whoOrNull(null))
        assertNull(FilterOps.whoOrNull(""))
        assertNull(FilterOps.whoOrNull("   "))
    }

    /** Unrecognised resolves toward censoring: a typo that quietly stopped is the failure nobody sees. */
    @Test
    fun anUnrecognisedValueCensors() {
        assertEquals(FilterOps.EVERYONE, FilterOps.whoOrNull("true"))
        assertEquals(FilterOps.EVERYONE, FilterOps.whoOrNull("female"))
    }

    /**
     * The product defaults, pinned: `DEFAULT_WHO` is what every entry point opens on when nothing was
     * ever picked, and it must be a real choice rather than [FilterOps.NONE] — a default of "off" would
     * ship an app that censors nothing. `censorWho`'s own default stays [FilterOps.NONE] regardless
     * (`FilterOps.kt:30-34`): `FilterOps()` means "nothing picked yet".
     */
    @Test
    fun theDefaultsAreTheOnesTheUiOpensOn() {
        assertEquals(FilterOps.WOMEN, FilterOps.DEFAULT_WHO)
        assertEquals(FilterOps.NONE, FilterOps().censorWho)
        assertTrue(FilterOps().censorNsfw)
        assertEquals(40, FilterOps().strictness)
    }

    @Test
    fun theLegacyBooleanMapsToTheTwoStatesItCouldExpress() {
        assertEquals(FilterOps.EVERYONE, FilterOps.whoFromLegacy(true))
        assertEquals(FilterOps.NONE, FilterOps.whoFromLegacy(false))
    }

    /**
     * The pipeline branches on `censorFaces`, never on which faces (plan-censor-who §1.2), so the three
     * censoring states must be indistinguishable from the old `censorWomen = true` at every one of them.
     */
    @Test
    fun onlyNoneMeansNoFaceCensoring() {
        assertFalse(FilterOps(censorWho = FilterOps.NONE).censorFaces)
        for (who in listOf(FilterOps.EVERYONE, FilterOps.WOMEN, FilterOps.MEN)) {
            assertTrue(who, FilterOps(censorWho = who).censorFaces)
            assertTrue(who, FilterOps(censorWho = who).any)
        }
        assertFalse(FilterOps().any) // nothing picked yet — Queue.kt:56 and NaqiApp.kt:37 start here
        assertTrue(FilterOps(removeMusic = true).any)
    }

    /**
     * A `queue.json` written before the rename, with an item still mid-flight when the app updated
     * under it. Only the read side is asserted — `Queue.toJson` is private, and it is the read that
     * carries the compatibility.
     */
    @Test
    fun aQueueFileFromTheOldBuildKeepsItsCensorSetting() {
        assertEquals(FilterOps.EVERYONE, Queue.opsFromJson(JSONObject().put("censorWomen", true)).censorWho)
        assertEquals(FilterOps.NONE, Queue.opsFromJson(JSONObject().put("censorWomen", false)).censorWho)
    }

    /** Phase A's UI cannot produce "women", but the format carries it so it never changes again. */
    @Test
    fun aQueueFileCarriesTheStatesTheUiCannotYetProduce() {
        assertEquals(FilterOps.WOMEN, Queue.opsFromJson(JSONObject().put("censorWho", "women")).censorWho)
        assertEquals(FilterOps.MEN, Queue.opsFromJson(JSONObject().put("censorWho", "men")).censorWho)
    }

    @Test
    fun theNsfwChoiceSurvivesOldAndNewQueueItems() {
        assertTrue(Queue.opsFromJson(JSONObject()).censorNsfw)
        assertFalse(Queue.opsFromJson(JSONObject().put("censorNsfw", false)).censorNsfw)
    }

    /** The same fallback for a job enqueued by the old build and run after the update. */
    @Test
    fun aJobEnqueuedByTheOldBuildKeepsItsCensorSetting() {
        assertEquals(FilterOps.EVERYONE, workDataOf("censor_women" to true).filterOps().censorWho)
        assertEquals(FilterOps.NONE, workDataOf("censor_women" to false).filterOps().censorWho)
    }

    /**
     * The writer emits the new key only (`QueuedWorker.kt:59-61`): writing both would leave two sources
     * of truth for one option, one of which cannot say "women". `JobController.kt:39` builds its input
     * data exactly like this.
     */
    @Test
    fun theWorkDataWriterEmitsOnlyTheNewKey() {
        val data = workDataOf(*FilterOps(censorWho = FilterOps.WOMEN, censorNsfw = false).pairs())
        assertEquals(FilterOps.WOMEN, data.getString("censor_who"))
        assertFalse(data.keyValueMap.containsKey("censor_women"))
        assertEquals(FilterOps.WOMEN, data.filterOps().censorWho)
        assertFalse(data.filterOps().censorNsfw)
        assertTrue(workDataOf().filterOps().censorNsfw)
    }
}
