package com.haithamassoli.naqi.work

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

class JobControllerTest {
    @Test
    fun currentWorkPrefersActiveWorkThenNewestResult() {
        val old = work(WorkInfo.State.SUCCEEDED)
        val queued = work(WorkInfo.State.ENQUEUED)
        val running = work(WorkInfo.State.RUNNING)

        assertSame(running, JobController.currentWork(listOf(old, queued, running)))
        assertSame(queued, JobController.currentWork(listOf(old, queued)))
        assertSame(old, JobController.currentWork(listOf(work(WorkInfo.State.CANCELLED), old)))
        assertEquals("video-2", JobController.queueIdOf(work(WorkInfo.State.RUNNING, "naqi_item_video-2")))
    }

    private fun work(state: WorkInfo.State, vararg tags: String) = WorkInfo(UUID.randomUUID(), state, tags.toSet())
}
