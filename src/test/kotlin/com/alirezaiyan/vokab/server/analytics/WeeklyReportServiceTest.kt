package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/** TEST_NOW is Wednesday 2026-06-17: this week is Mon 06-15 – Sun 06-21, last week Mon 06-08 – Sun 06-14. */
class WeeklyReportServiceTest {

    private val user = User(id = 1L, email = "weekly@example.com", name = "Weekly")
    private val studySessionRepository: StudySessionRepository = mockk()
    private val reviewEventRepository: ReviewEventRepository = mockk()
    private val userRepository: UserRepository = mockk()
    private val service = WeeklyReportService(studySessionRepository, reviewEventRepository, userRepository, fixedClock())

    private var sessions = emptyList<StudySession>()

    @BeforeEach
    fun setUp() {
        every { userRepository.getReferenceById(1L) } returns user
        every { studySessionRepository.findByUserAndDateRange(user, any(), any()) } answers {
            sessions.filter { it.startedAt in secondArg<Long>()..thirdArg<Long>() }
        }
        every { reviewEventRepository.countWordsMasteredInRange(user, any(), any()) } returns 3
    }

    @Test
    fun `reports this calendar week against last week with the best day`() {
        sessions = listOf(
            session("2026-06-15T10:00:00Z", cards = 10, correct = 8, durationMs = 1_000),
            session("2026-06-16T18:00:00Z", cards = 20, correct = 10, durationMs = 2_000),
            session("2026-06-10T09:00:00Z", cards = 15, correct = 15, durationMs = 500),
        )

        val report = service.getWeeklyReport(1L)

        assertEquals(30, report.cardsReviewed)
        assertEquals(15, report.previousWeekCardsReviewed)
        assertEquals(100.0, report.changePercent)
        assertEquals(60.0, report.accuracyPercent)
        assertEquals(3, report.wordsMastered)
        assertEquals(3_000, report.totalStudyTimeMs)
        assertEquals(2, report.sessionsCount)
        assertEquals(BestDayResponse("Tuesday", 20, 50.0), report.bestDay)
        assertEquals("2026-06-15", report.weekStartDate)
        assertEquals("2026-06-21", report.weekEndDate)
    }

    @Test
    fun `no session in the last 7 days hides the card`() {
        sessions = listOf(session("2026-06-08T09:00:00Z", cards = 15, correct = 15, durationMs = 500))

        val report = service.getWeeklyReport(1L)

        assertEquals(0, report.sessionsCount)
        assertNull(report.bestDay)
        assertEquals("", report.weekStartDate)
    }

    @Test
    fun `no sessions last week leaves the change undefined`() {
        sessions = listOf(session("2026-06-17T08:00:00Z", cards = 5, correct = 5, durationMs = 100))

        assertNull(service.getWeeklyReport(1L).changePercent)
    }

    private fun session(startedAt: String, cards: Int, correct: Int, durationMs: Long) = StudySession(
        user = user,
        clientSessionId = startedAt,
        startedAt = Instant.parse(startedAt).toEpochMilli(),
        durationMs = durationMs,
        totalCards = cards,
        correctCount = correct,
        incorrectCount = cards - correct,
    )
}
