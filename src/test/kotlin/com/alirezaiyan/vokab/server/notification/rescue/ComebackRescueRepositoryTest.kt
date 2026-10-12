package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ComebackRescueRepositoryTest {

    @Autowired lateinit var repository: ComebackRescueRepository
    @Autowired lateinit var userRepository: UserRepository

    private val today = LocalDate.of(2026, 6, 17)

    private fun user(): User =
        userRepository.save(User(email = "rescue-${System.nanoTime()}@example.com", name = "Rescue User"))

    private fun rescue(userId: Long, day: LocalDate, status: RescueStatus) = ComebackRescue(
        userId = userId,
        plannedFor = day,
        wordIds = ComebackRescue.encodeWordIds(listOf(3L, 1L, 2L)),
        title = "t",
        body = "b",
        angle = "rematch",
        source = RescueSource.FALLBACK,
        status = status,
    )

    @Test
    fun `stores the ordered word ids and reads them back`() {
        val userId = requireNotNull(user().id)
        repository.saveAndFlush(rescue(userId, today, RescueStatus.PLANNED))

        val found = requireNotNull(repository.findByUserIdAndPlannedFor(userId, today))

        assertEquals(listOf(3L, 1L, 2L), found.wordIdList)
    }

    @Test
    fun `one rescue per lapse counts only sent, completed and holdout plans`() {
        val userId = requireNotNull(user().id)
        val lastStudy = today.minusDays(5)
        repository.saveAndFlush(rescue(userId, today.minusDays(1), RescueStatus.EXPIRED))

        assertFalse(repository.existsByUserIdAndPlannedForAfterAndStatusIn(userId, lastStudy, RescueStatus.CONSUMES_LAPSE))

        repository.saveAndFlush(rescue(userId, today.minusDays(2), RescueStatus.SENT))

        assertTrue(repository.existsByUserIdAndPlannedForAfterAndStatusIn(userId, lastStudy, RescueStatus.CONSUMES_LAPSE))
    }

    @Test
    fun `expires planned rescues from earlier days only`() {
        val userId = requireNotNull(user().id)
        repository.saveAndFlush(rescue(userId, today.minusDays(1), RescueStatus.PLANNED))
        repository.saveAndFlush(rescue(userId, today, RescueStatus.PLANNED))

        val expired = repository.expireStale(today, RescueStatus.PLANNED, RescueStatus.EXPIRED)

        assertEquals(1, expired)
        assertEquals(RescueStatus.PLANNED, repository.findByUserIdAndPlannedFor(userId, today)?.status)
    }
}
