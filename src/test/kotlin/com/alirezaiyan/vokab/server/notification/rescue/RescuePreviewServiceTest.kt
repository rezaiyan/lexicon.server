package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import kotlin.random.Random

class RescuePreviewServiceTest {

    private val planner: ComebackRescuePlanner = mockk()
    private val userRepository: UserRepository = mockk()
    private val previewRepository: RescuePreviewRepository = mockk()
    private val ratingRepository: RescueCopyRatingRepository = mockk()
    private val objectMapper = ObjectMapper()

    private val service = RescuePreviewService(
        planner, userRepository, previewRepository, ratingRepository, objectMapper, fixedClock(), Random(1),
    )

    private fun user(id: Long) = User(id = id, email = "u$id@example.com", name = "U$id")

    private val candidatesJson = objectMapper.writeValueAsString(
        listOf(
            // same title as the chosen copy, but the linter rejected it: must not be mistaken for it
            mapOf("candidate" to mapOf("title" to "Gewitter, one more try", "body" to "b"), "violations" to listOf("repeats last angle")),
            mapOf("candidate" to mapOf("title" to "Gewitter, one more try", "body" to "b"), "violations" to emptyList<String>()),
        )
    )

    private fun draft(source: RescueSource = RescueSource.AI) = RescueDraft(
        wordIds = listOf(1L, 2L, 3L),
        copy = RescueCopy("rematch", "Gewitter, one more try", "b", "win", "soft"),
        source = source,
        candidatesJson = candidatesJson,
    )

    private fun preview(id: Long, title: String = "Gewitter, one more try", candidates: String? = candidatesJson) =
        RescuePreview(
            id = id, userId = 1L, promptVersion = "comeback-rescue.v1", source = RescueSource.AI, angle = "rematch",
            title = title, body = "b", wordIds = "[1,2,3]", candidates = candidates,
        )

    private fun savesPreviews() {
        val saved = slot<RescuePreview>()
        every { previewRepository.save(capture(saved)) } answers { saved.captured }
    }

    @Test
    fun `generates up to count previews from learners with a usable draft`() {
        every { userRepository.findAllActiveUsersWithPushTokens() } returns (1L..6L).map(::user)
        every { planner.draft(any(), RescuePreviewService.PREVIEW_DAYS_AWAY) } answers {
            if (requireNotNull(firstArg<User>().id) % 2 == 0L) draft() else null
        }
        savesPreviews()

        val previews = service.generate(count = 2)

        assertEquals(2, previews.size)
        previews.forEach {
            assertEquals(0L, it.userId % 2)
            assertEquals("comeback-rescue.v1", it.promptVersion)
            assertEquals("[1,2,3]", it.wordIds)
            assertEquals("Gewitter, one more try", it.title)
            assertEquals(TEST_NOW, it.createdAt)
        }
    }

    @Test
    fun `skips template-copy drafts and stops once the AI looks down`() {
        every { userRepository.findAllActiveUsersWithPushTokens() } returns (1L..40L).map(::user)
        every { planner.draft(any(), any()) } returns draft(RescueSource.FALLBACK)

        assertEquals(emptyList<RescuePreview>(), service.generate(count = 5))

        verify(exactly = RescuePreviewService.MAX_FALLBACKS_IN_A_ROW) { planner.draft(any(), any()) }
    }

    @Test
    fun `an AI draft between template drafts resets the early stop`() {
        every { userRepository.findAllActiveUsersWithPushTokens() } returns (1L..10L).map(::user)
        // shuffled order is fixed by the seeded Random; alternate by call count instead of user id
        var calls = 0
        every { planner.draft(any(), any()) } answers {
            calls++
            if (calls == 3) draft() else draft(RescueSource.FALLBACK)
        }
        savesPreviews()

        val previews = service.generate(count = 5)

        // F, F, AI (resets), F, F, F (stops)
        assertEquals(1, previews.size)
        assertEquals(6, calls)
    }

    @Test
    fun `caps the count and the learners tried, and skips a learner whose draft fails`() {
        every { userRepository.findAllActiveUsersWithPushTokens() } returns (1L..400L).map(::user)
        every { planner.draft(any(), any()) } returns null
        every { planner.draft(match { it.id == 3L }, any()) } throws IllegalStateException("boom")

        assertEquals(emptyList<RescuePreview>(), service.generate(count = 1_000))

        verify(exactly = RescuePreviewService.MAX_COUNT * RescuePreviewService.TRIES_PER_PREVIEW) { planner.draft(any(), any()) }
    }

    @Test
    fun `rates a candidate and replaces an earlier rating of it`() {
        every { previewRepository.findById(7L) } returns Optional.of(preview(7L))
        val existing = RescueCopyRating(previewId = 7L, candidateIndex = 1, rating = CopyRating.DOWN)
        every { ratingRepository.findByPreviewIdAndCandidateIndex(7L, 1) } returns existing
        every { ratingRepository.save(existing) } returns existing

        service.rate(previewId = 7L, candidateIndex = 1, rating = CopyRating.UP, note = "warm")

        assertEquals(CopyRating.UP, existing.rating)
        assertEquals("warm", existing.note)
        assertEquals(TEST_NOW, existing.ratedAt)
    }

    @Test
    fun `rejects ratings for unknown previews and out-of-range candidates`() {
        every { previewRepository.findById(7L) } returns Optional.of(preview(7L))
        every { previewRepository.findById(8L) } returns Optional.empty()
        every { previewRepository.findById(9L) } returns Optional.of(preview(9L, candidates = null))

        assertThrows<NoSuchElementException> { service.rate(8L, 0, CopyRating.UP, null) }
        assertThrows<IllegalArgumentException> { service.rate(7L, 2, CopyRating.UP, null) }
        assertThrows<IllegalArgumentException> { service.rate(7L, -1, CopyRating.UP, null) }
        assertThrows<IllegalArgumentException> { service.rate(9L, 0, CopyRating.UP, null) }
    }

    @Test
    fun `taste score counts the chosen candidate separately from all candidates`() {
        every { ratingRepository.findAll() } returns listOf(
            RescueCopyRating(previewId = 7L, candidateIndex = 1, rating = CopyRating.UP),   // chosen
            RescueCopyRating(previewId = 7L, candidateIndex = 0, rating = CopyRating.DOWN),
            RescueCopyRating(previewId = 8L, candidateIndex = 1, rating = CopyRating.DOWN), // chosen
            RescueCopyRating(previewId = 9L, candidateIndex = 1, rating = CopyRating.UP),   // chosen
        )
        every { previewRepository.findAllById(setOf(7L, 8L, 9L)) } returns listOf(preview(7L), preview(8L), preview(9L))

        val score = service.tasteScore()

        assertEquals(TasteScore(rated = 3, up = 2, allRated = 4, allUp = 2), score)
        assertEquals(2.0 / 3, score.upRate)
        assertEquals(0.5, score.allUpRate)
    }

    @Test
    fun `taste score has no rate before anything is rated`() {
        every { ratingRepository.findAll() } returns emptyList()
        every { previewRepository.findAllById(emptySet()) } returns emptyList()

        assertNull(service.tasteScore().upRate)
    }
}
