package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.words.Word
import com.alirezaiyan.vokab.server.words.WordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DueWordQueriesTest {

    @Autowired lateinit var queries: DueWordQueries
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var wordRepository: WordRepository

    private fun user(): User =
        userRepository.save(User(email = "due-${System.nanoTime()}@example.com", name = "Due User"))

    private fun word(user: User, text: String, level: Int, nextReviewDate: Long): Word =
        wordRepository.saveAndFlush(
            Word(
                user = user, originalWord = text, translation = "$text-t",
                sourceLanguage = "en", targetLanguage = "de", level = level, nextReviewDate = nextReviewDate,
            )
        )

    @Test
    fun `returns only the user's due words, most overdue first`() {
        val user = user()
        val later = word(user, "Katze", level = 4, nextReviewDate = 900L)
        val oldest = word(user, "Hund", level = 1, nextReviewDate = 100L)
        word(user, "Maus", level = 2, nextReviewDate = 2_000L)        // not due yet
        word(user(), "Vogel", level = 3, nextReviewDate = 50L)        // someone else's

        val rows = queries.dueWords(requireNotNull(user.id), nowMs = 1_000L)

        assertEquals(
            listOf(DueWordRow(requireNotNull(oldest.id), 1, 100L), DueWordRow(requireNotNull(later.id), 4, 900L)),
            rows,
        )
    }
}
