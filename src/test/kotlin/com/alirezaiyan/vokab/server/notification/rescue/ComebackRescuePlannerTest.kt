package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.alirezaiyan.vokab.server.study.DailyActivity
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ComebackRescuePlannerTest {

    private val rescueRepository: ComebackRescueRepository = mockk()
    private val candidateQueries: RescueCandidateQueries = mockk()
    private val dailyActivityRepository: DailyActivityRepository = mockk()
    private val userSettingsRepository: UserSettingsRepository = mockk()
    private val aiClient: AiClient = mockk()

    private lateinit var planner: ComebackRescuePlanner

    private val near = (1L..7L).map { candidateRow(it) }
    private val weak = listOf(
        candidateRow(20, text = "gamma", level = 2, recent = "0101", total = 6, errors = 3),
        candidateRow(21, text = "delta", level = 2, recent = "0101", total = 6, errors = 3),
    )

    private val validAi = """
        {"wordIds":[1,2,20,3,21,4,5,6],"reasons":{"20":"weak, right last time"},
         "candidates":[
           {"angle":"rematch","title":"Ready to crush it?","body":"Let's go!","factIds":["F1"],"rank":1},
           {"angle":"curiosity","title":"Still know what gamma means?","body":"One way to find out. 8 words, about 3 min.","factIds":["F1"],"rank":2}
         ],
         "outroWin":"gamma: cracked. 8 for 8.","outroSoft":"8 words back in rotation."}
    """.trimIndent()

    private fun user(id: Long = 1L) = User(id = id, email = "u$id@example.com", name = "U$id", longestStreak = 0)

    @BeforeEach
    fun setUp() {
        planner = ComebackRescuePlanner(
            rescueRepository, candidateQueries, dailyActivityRepository, userSettingsRepository,
            aiClient, PromptTemplates(), ObjectMapper(), fixedClock(),
        )
        every { rescueRepository.findByUserIdAndPlannedFor(any(), TEST_TODAY) } returns null
        every { rescueRepository.existsByUserIdAndPlannedForAfterAndStatusIn(any(), any(), any()) } returns false
        every { rescueRepository.findTop3ByUserIdOrderByPlannedForDesc(any()) } returns emptyList()
        every { rescueRepository.save(any()) } answers { firstArg() }
        every { candidateQueries.candidates(any()) } returns near + weak
        every { candidateQueries.totalWords(any()) } returns 40
        every { candidateQueries.firstWord(any(), any(), any()) } returns null
        every { userSettingsRepository.findByUserId(any()) } returns null
        studiedDaysAgo(3, 4, 5)
    }

    private fun studiedDaysAgo(vararg days: Long) {
        every { dailyActivityRepository.findRecentActivities(any(), any()) } answers {
            days.map { DailyActivity(user = firstArg(), activityDate = TEST_TODAY.minusDays(it)) }
        }
    }

    @Test
    fun `plans with AI picks and the best copy that passes the linter`() {
        every { aiClient.complete(any(), AiOperation.COMEBACK_RESCUE) } returns validAi

        val rescue = requireNotNull(planner.planIfEligible(user()))

        assertEquals(RescueSource.AI, rescue.source)
        assertEquals(RescueStatus.PLANNED, rescue.status)
        assertEquals(listOf(1L, 2L, 20L, 3L, 21L, 4L, 5L, 6L), rescue.wordIdList)
        assertEquals("curiosity", rescue.angle)
        assertEquals("Still know what gamma means?", rescue.title)
        assertEquals("gamma: cracked. 8 for 8.", rescue.outroWin)
        assertEquals(TEST_TODAY, rescue.plannedFor)
    }

    @Test
    fun `falls back when the AI is unavailable`() {
        every { aiClient.complete(any(), any()) } throws UpstreamServiceException("down")

        val rescue = requireNotNull(planner.planIfEligible(user()))

        assertEquals(RescueSource.FALLBACK, rescue.source)
        assertEquals(RescueFallback.picks(requireNotNull(RescuePool.from(near + weak, fixedClock().millis()))), rescue.wordIdList)
    }

    @Test
    fun `falls back when the AI picks words outside the pool`() {
        every { aiClient.complete(any(), any()) } returns validAi.replace("[1,2,20,3,21,4,5,6]", "[1,2,20,3,999,4,5,6]")

        assertEquals(RescueSource.FALLBACK, planner.planIfEligible(user())?.source)
    }

    @Test
    fun `keeps AI picks with template copy when every candidate fails the linter`() {
        every { aiClient.complete(any(), any()) } returns validAi.replace("Still know what gamma means?", "Ready to crush gamma?")

        val rescue = requireNotNull(planner.planIfEligible(user()))

        assertEquals(RescueSource.AI_PICKS_TEMPLATE_COPY, rescue.source)
        assertEquals(listOf(1L, 2L, 20L, 3L, 21L, 4L, 5L, 6L), rescue.wordIdList)
        assertEquals("gamma wants a rematch", rescue.title)
    }

    @Test
    fun `drops an AI outro that fails the tone rules`() {
        every { aiClient.complete(any(), any()) } returns validAi.replace("gamma: cracked. 8 for 8.", "Don't lose gamma!")

        assertNull(planner.planIfEligible(user())?.outroWin)
    }

    @Test
    fun `holdout learners get a plan that is never sent`() {
        every { aiClient.complete(any(), any()) } returns validAi

        assertEquals(RescueStatus.HOLDOUT, planner.planIfEligible(user(id = ComebackRescuePlanner.HOLDOUT_BUCKET.toLong()))?.status)
    }

    @Test
    fun `does nothing for an ineligible learner`() {
        studiedDaysAgo(1, 2, 3)

        assertNull(planner.planIfEligible(user()))
        verify(exactly = 0) { aiClient.complete(any(), any()) }
    }

    @Test
    fun `does nothing when the pool is too thin`() {
        every { candidateQueries.candidates(any()) } returns near.take(4) + weak

        assertNull(planner.planIfEligible(user()))
        verify(exactly = 0) { aiClient.complete(any(), any()) }
    }

    @Test
    fun `does nothing when today's plan already exists`() {
        every { rescueRepository.findByUserIdAndPlannedFor(1L, TEST_TODAY) } returns mockk()

        assertNull(planner.planIfEligible(user()))
        verify(exactly = 0) { rescueRepository.save(any()) }
    }
}
