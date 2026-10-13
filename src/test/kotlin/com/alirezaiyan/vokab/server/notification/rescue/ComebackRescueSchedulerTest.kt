package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.notification.NotificationSchedule
import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import com.alirezaiyan.vokab.server.user.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test

class ComebackRescueSchedulerTest {

    private val planner: ComebackRescuePlanner = mockk()
    private val repository: ComebackRescueRepository = mockk()
    private val scheduleRepository: NotificationScheduleRepository = mockk()
    private val scheduler = ComebackRescueScheduler(planner, repository, scheduleRepository, fixedClock())

    private fun user(id: Long) = User(id = id, email = "u$id@example.com", name = "U$id")

    private fun candidates(vararg ids: Long) {
        every { scheduleRepository.findRescuePlanningCandidates(ComebackRescueScheduler.MIN_SEND_HOUR) } returns
            ids.map { NotificationSchedule(user = user(it)) }
    }

    @Test
    fun `expires yesterday's unsent plans, then plans every reachable learner in send-hour order`() {
        every { repository.expireStale(TEST_TODAY, RescueStatus.PLANNED, RescueStatus.EXPIRED) } returns 2
        every { planner.planIfEligible(any()) } returns null
        candidates(2, 1)

        scheduler.planAll()

        verifyOrder {
            repository.expireStale(TEST_TODAY, RescueStatus.PLANNED, RescueStatus.EXPIRED)
            planner.planIfEligible(match { it.id == 2L })
            planner.planIfEligible(match { it.id == 1L })
        }
    }

    @Test
    fun `one user's failure does not stop the batch`() {
        every { repository.expireStale(any(), any(), any()) } returns 0
        every { planner.planIfEligible(match { it.id == 1L }) } throws IllegalStateException("boom")
        every { planner.planIfEligible(match { it.id == 2L }) } returns null
        candidates(1, 2)

        scheduler.planAll()

        verify(exactly = 1) { planner.planIfEligible(match { it.id == 2L }) }
    }
}
