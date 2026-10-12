package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ComebackRescueSchedulerTest {

    private val planner: ComebackRescuePlanner = mockk()
    private val repository: ComebackRescueRepository = mockk()
    private val scheduler = ComebackRescueScheduler(planner, repository, fixedClock())

    private fun user(id: Long) = User(id = id, email = "u$id@example.com", name = "U$id")

    @Test
    fun `expires yesterday's unsent plans, then plans every user`() {
        every { repository.expireStale(TEST_TODAY, RescueStatus.PLANNED, RescueStatus.EXPIRED) } returns 2
        every { planner.planIfEligible(any()) } returns null

        scheduler.planAll(listOf(user(1), user(2)))

        verify(exactly = 1) { repository.expireStale(TEST_TODAY, RescueStatus.PLANNED, RescueStatus.EXPIRED) }
        verify(exactly = 2) { planner.planIfEligible(any()) }
    }

    @Test
    fun `one user's failure does not stop the batch`() {
        every { repository.expireStale(any(), any(), any()) } returns 0
        every { planner.planIfEligible(match { it.id == 1L }) } throws IllegalStateException("boom")
        every { planner.planIfEligible(match { it.id == 2L }) } returns null

        scheduler.planAll(listOf(user(1), user(2)))

        verify(exactly = 1) { planner.planIfEligible(match { it.id == 2L }) }
    }
}
