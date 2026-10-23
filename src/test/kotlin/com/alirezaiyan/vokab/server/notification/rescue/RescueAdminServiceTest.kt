package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_TODAY
import com.alirezaiyan.vokab.server.fixedClock
import tools.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RescueAdminServiceTest {

    private val previewRepository: RescuePreviewRepository = mockk()
    private val ratingRepository: RescueCopyRatingRepository = mockk()
    private val rescueRepository: ComebackRescueRepository = mockk()
    private val funnelQueries: RescueFunnelQueries = mockk()
    private val objectMapper = ObjectMapper()
    private val previewService = RescuePreviewService(mockk(), mockk(), previewRepository, ratingRepository, objectMapper, fixedClock())

    private val service = RescueAdminService(
        previewRepository, ratingRepository, rescueRepository, previewService, funnelQueries, objectMapper, fixedClock(),
    )

    private val candidates = objectMapper.writeValueAsString(
        listOf(
            mapOf(
                "candidate" to mapOf("angle" to "curiosity", "title" to "Still know Gewitter?", "body" to "x", "factIds" to listOf("F3"), "rank" to 2),
                "violations" to listOf("repeats last angle"),
                "judge" to mapOf("pass" to false, "reason" to "generic"),
            ),
            mapOf(
                "candidate" to mapOf("angle" to "rematch", "title" to "Gewitter, one more try", "body" to "y", "factIds" to listOf("F1"), "rank" to 1),
                "violations" to emptyList<String>(),
                "judge" to mapOf("pass" to true, "reason" to "specific"),
            ),
        )
    )

    private val preview = RescuePreview(
        id = 11L, userId = 3L, promptVersion = "comeback-rescue.v1", source = RescueSource.AI, angle = "rematch",
        title = "Gewitter, one more try", body = "y", wordIds = "[4,5,6]", candidates = candidates,
    )

    @Test
    fun `previews show every candidate with its verdict, the chosen one and its rating`() {
        every { previewRepository.findAllByOrderByCreatedAtDescIdDesc(any()) } returns listOf(preview)
        every { ratingRepository.findAllByPreviewIdIn(listOf(11L)) } returns
            listOf(RescueCopyRating(previewId = 11L, candidateIndex = 0, rating = CopyRating.DOWN, note = "flat"))

        val view = service.previews(limit = 50).single()

        assertEquals(listOf(4L, 5L, 6L), view.wordIds)
        assertEquals(listOf(false, true), view.candidates.map { it.chosen })
        assertEquals(listOf("repeats last angle"), view.candidates[0].violations)
        assertEquals(CopyRating.DOWN, view.candidates[0].rating)
        assertEquals("flat", view.candidates[0].note)
        assertEquals(listOf("F3"), view.candidates[0].factIds)
        assertEquals(null, view.candidates[1].rating)
        assertEquals(listOf(false, true), view.candidates.map { it.judgePass })
        assertEquals("generic", view.candidates[0].judgeReason)
    }

    @Test
    fun `the eval set holds every rated candidate in preview and candidate order`() {
        every { ratingRepository.findAll() } returns listOf(
            RescueCopyRating(previewId = 11L, candidateIndex = 1, rating = CopyRating.UP),
            RescueCopyRating(previewId = 11L, candidateIndex = 0, rating = CopyRating.DOWN, note = "flat"),
        )
        every { previewRepository.findAllById(setOf(11L)) } returns listOf(preview)

        val lines = service.evalSet()

        assertEquals(listOf(0, 1), lines.map { it.candidateIndex })
        assertEquals(listOf(false, true), lines.map { it.chosen })
        assertEquals("Still know Gewitter?", lines[0].title)
        assertEquals("comeback-rescue.v1", lines[1].promptVersion)
    }

    @Test
    fun `the funnel window is clamped and ends today`() {
        every { funnelQueries.funnel(RescueAdminService.MAX_FUNNEL_DAYS, TEST_TODAY) } returns emptyList()

        assertEquals(emptyList<RescueFunnelRow>(), service.funnel(days = 10_000))
    }
}
