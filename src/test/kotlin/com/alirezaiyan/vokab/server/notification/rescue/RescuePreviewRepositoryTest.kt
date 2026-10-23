package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RescuePreviewRepositoryTest {

    @Autowired lateinit var previewRepository: RescuePreviewRepository
    @Autowired lateinit var ratingRepository: RescueCopyRatingRepository
    @Autowired lateinit var userRepository: UserRepository

    private fun preview(createdAt: Instant = Instant.parse("2026-10-13T10:00:00Z")): RescuePreview {
        val user = userRepository.save(User(email = "preview-${System.nanoTime()}@example.com", name = "Preview User"))
        return previewRepository.saveAndFlush(
            RescuePreview(
                userId = requireNotNull(user.id),
                createdAt = createdAt,
                promptVersion = "v1",
                source = RescueSource.AI,
                angle = "rematch",
                title = "Gewitter, one more try",
                body = "8 words, about 3 min.",
                wordIds = ComebackRescue.encodeWordIds(listOf(3L, 1L)),
                candidates = """[{"candidate":{"title":"Gewitter, one more try"},"violations":[]}]""",
            )
        )
    }

    @Test
    fun `stores previews and lists the newest first`() {
        val older = preview(Instant.parse("2026-10-12T10:00:00Z"))
        val newer = preview(Instant.parse("2026-10-13T10:00:00Z"))

        val listed = previewRepository.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(0, 50))
            .filter { it.id in setOf(older.id, newer.id) }

        assertEquals(listOf(newer.id, older.id), listed.map { it.id })
        assertTrue(listed.first().candidates.orEmpty().contains("Gewitter, one more try"))
    }

    @Test
    fun `a candidate has one rating that can be found and changed`() {
        val previewId = requireNotNull(preview().id)
        ratingRepository.saveAndFlush(RescueCopyRating(previewId = previewId, candidateIndex = 0, rating = CopyRating.UP))

        val rating = requireNotNull(ratingRepository.findByPreviewIdAndCandidateIndex(previewId, 0))
        rating.rating = CopyRating.DOWN
        ratingRepository.saveAndFlush(rating)

        assertEquals(CopyRating.DOWN, ratingRepository.findAllByPreviewIdIn(listOf(previewId)).single().rating)
        assertThrows<DataIntegrityViolationException> {
            ratingRepository.saveAndFlush(RescueCopyRating(previewId = previewId, candidateIndex = 0, rating = CopyRating.UP))
        }
    }
}
