package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.analytics.ReviewEvent
import com.alirezaiyan.vokab.server.analytics.ReviewEventRepository
import com.alirezaiyan.vokab.server.analytics.StudySession
import com.alirezaiyan.vokab.server.analytics.StudySessionRepository
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.words.Word
import com.alirezaiyan.vokab.server.words.WordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RescueCandidateQueriesTest {

    @Autowired lateinit var queries: RescueCandidateQueries
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var sessionRepository: StudySessionRepository
    @Autowired lateinit var reviewEventRepository: ReviewEventRepository

    private fun user(): User =
        userRepository.save(User(email = "cand-${System.nanoTime()}@example.com", name = "Cand User"))

    private fun word(user: User, text: String, level: Int, createdAt: Instant = Instant.parse("2026-03-02T10:00:00Z")): Word =
        wordRepository.saveAndFlush(
            Word(
                user = user, originalWord = text, translation = "$text-t",
                sourceLanguage = "en", targetLanguage = "de", level = level, nextReviewDate = 123L,
                createdAt = createdAt,
            )
        )

    /** Reviews in time order; [ratings] oldest first, 0 = wrong. */
    private fun reviews(user: User, word: Word, vararg ratings: Int) {
        val session = sessionRepository.saveAndFlush(
            StudySession(user = user, clientSessionId = "s-${System.nanoTime()}", startedAt = 0L)
        )
        ratings.forEachIndexed { i, rating ->
            reviewEventRepository.saveAndFlush(
                ReviewEvent(
                    session = session, user = user, wordId = requireNotNull(word.id), rating = rating,
                    previousLevel = word.level, newLevel = word.level, reviewedAt = 1_000L + i,
                )
            )
        }
    }

    @Test
    fun `returns reviewed unmastered words with counts and the last five results newest first`() {
        val user = user()
        val hund = word(user, "Hund", level = 3)
        reviews(user, hund, 0, 0, 1, 1, 0, 1, 1)   // 7 reviews, 3 wrong; last five oldest→newest 1,1,0,1,1

        val row = queries.candidates(requireNotNull(user.id)).single()

        assertEquals(hund.id, row.wordId)
        assertEquals("Hund", row.text)
        assertEquals(3, row.level)
        assertEquals(123L, row.nextReviewDate)
        assertEquals(7, row.totalReviews)
        assertEquals(3, row.errors)
        assertEquals("11011", row.recent)
    }

    @Test
    fun `skips mastered, new and never reviewed words`() {
        val user = user()
        reviews(user, word(user, "Katze", level = 6), 1, 1)
        reviews(user, word(user, "Maus", level = 0), 1)
        word(user, "Vogel", level = 3)

        assertEquals(emptyList<RescueCandidateRow>(), queries.candidates(requireNotNull(user.id)))
    }

    @Test
    fun `counts only the learner's own reviews`() {
        val owner = user()
        val other = user()
        val baum = word(owner, "Baum", level = 4)
        reviews(owner, baum, 1, 1)
        reviews(other, baum, 0, 0, 0)   // another user's events for the same word id must not count

        assertEquals(2, queries.candidates(requireNotNull(owner.id)).single().totalReviews)
    }

    @Test
    fun `first word is the oldest word in the language pair`() {
        val user = user()
        word(user, "Apfel", level = 2, createdAt = Instant.parse("2026-01-01T00:00:00Z"))
        word(user, "Birne", level = 2, createdAt = Instant.parse("2026-02-01T00:00:00Z"))

        val first = queries.firstWord(requireNotNull(user.id), "en", "de")

        assertEquals("Apfel", first?.text)
        assertNull(queries.firstWord(requireNotNull(user.id), "en", "fr"))
    }

    @Test
    fun `counts all words in the deck`() {
        val user = user()
        word(user, "Apfel", level = 0)
        word(user, "Birne", level = 6)

        assertEquals(2, queries.totalWords(requireNotNull(user.id)))
    }
}
