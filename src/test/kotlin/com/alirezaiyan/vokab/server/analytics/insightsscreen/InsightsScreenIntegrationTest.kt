package com.alirezaiyan.vokab.server.analytics.insightsscreen

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.analytics.ReviewEvent
import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.analytics.StudySession
import com.alirezaiyan.vokab.server.analytics.StudySessionRepository
import com.alirezaiyan.vokab.server.user.User
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsScreenIntegrationTest {

    @Autowired lateinit var service: InsightsScreenService
    @Autowired lateinit var studySessionRepository: StudySessionRepository
    @Autowired lateinit var reviewEventRepository: ReviewEventRepository
    @Autowired lateinit var testUserHelper: TestUserHelper

    lateinit var user: User
    lateinit var otherUser: User

    @BeforeAll
    fun setupUser() {
        user = testUserHelper.saveAndCommit(User(email = "insights-screen@test.com", name = "Insights Screen"))
        otherUser = testUserHelper.saveAndCommit(User(email = "insights-screen-other@test.com", name = "Other"))
    }

    @AfterAll
    fun teardownUser() {
        testUserHelper.clearUserSessions(user.id!!)
        testUserHelper.deleteByEmail("insights-screen@test.com")
        testUserHelper.clearUserSessions(otherUser.id!!)
        testUserHelper.deleteByEmail("insights-screen-other@test.com")
    }

    @Test
    fun `builds a screen from real review events`() {
        val now = System.currentTimeMillis()
        val session = studySessionRepository.save(
            StudySession(user = user, clientSessionId = "is-1", startedAt = now, totalCards = 3, correctCount = 2, incorrectCount = 1)
        )
        reviewEventRepository.saveAll((1L..3L).map { id ->
            ReviewEvent(
                session = session, user = user, wordId = id, wordText = "w$id",
                rating = if (id < 3) 1 else 0, previousLevel = 1, newLevel = if (id < 3) 2 else 0, reviewedAt = now,
            )
        })
        val otherSession = studySessionRepository.save(
            StudySession(user = otherUser, clientSessionId = "is-other", startedAt = now, totalCards = 5, correctCount = 5, incorrectCount = 0)
        )
        reviewEventRepository.saveAll((11L..15L).map { id ->
            ReviewEvent(
                session = otherSession, user = otherUser, wordId = id, wordText = "o$id",
                rating = 1, previousLevel = 1, newLevel = 2, reviewedAt = now,
            )
        })
        // The read-only service joins this transaction with flush mode MANUAL; flush so its queries see the rows.
        reviewEventRepository.flush()

        val response = service.build(user.id!!, ZoneOffset.UTC)

        assertEquals(3, response.hero.reviews.value)
        assertEquals(2, response.hero.leveledUp.value)
        assertEquals(3L, response.totalReviews)
        assertTrue(response.coach.isNotEmpty(), "fallback WEEK_TREND card always present")
        assertEquals(SectionKey.HABITS, response.locked.single().section)

        val tokyo = service.build(user.id!!, ZoneId.of("Asia/Tokyo"))
        assertEquals(3, tokyo.hero.reviews.value)
        assertEquals(3L, tokyo.totalReviews)
        val days = tokyo.hero.week.map { LocalDate.parse(it.date) }
        assertEquals(7, days.size)
        assertEquals((0L..6L).map { days.first().plusDays(it) }, days)
    }
}
