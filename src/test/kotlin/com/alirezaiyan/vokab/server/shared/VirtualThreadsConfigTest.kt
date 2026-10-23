package com.alirezaiyan.vokab.server.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.SimpleAsyncTaskExecutor
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.TimeUnit

@SpringBootTest
@ActiveProfiles("test")
class VirtualThreadsConfigTest {

    @Autowired lateinit var applicationTaskExecutor: AsyncTaskExecutor
    @Autowired lateinit var taskScheduler: TaskScheduler

    @Test
    fun `async work runs on virtual threads`() {
        val isVirtual = applicationTaskExecutor.submit<Boolean> { Thread.currentThread().isVirtual }
            .get(5, TimeUnit.SECONDS)

        assertTrue(isVirtual)
    }

    @Test
    fun `async work is capped so it cannot flood the connection pool`() {
        val executor = applicationTaskExecutor as SimpleAsyncTaskExecutor

        assertTrue(executor.isThrottleActive)
    }

    @Test
    fun `scheduled jobs never overlap a still-running previous run`() {
        // The virtual-thread scheduler starts each cron run on a fresh thread; without a limit a slow
        // notification dispatch could run twice at once and double-send.
        assertEquals(1, (taskScheduler as SimpleAsyncTaskScheduler).concurrencyLimit)
    }
}
