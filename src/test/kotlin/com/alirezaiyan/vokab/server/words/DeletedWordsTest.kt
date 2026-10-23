package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import kotlin.test.assertEquals

/** Devices learn about deleted words from tombstones, since the word sync only returns changed words. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeletedWordsTest {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private val clock = MutableClock().apply { set(TEST_NOW) }

    @Test
    fun `deleted words are reported to devices that synced before the deletion, and only the owner's`() {
        val (deletedWords, user, words) = setUp()
        val other = userRepository.save(User(email = "tombstone-other@example.com", name = "Other"))
        val syncedAt = TEST_NOW
        clock.set(TEST_NOW.plusSeconds(60))

        deletedWords.record(user.requireId(), listOf(words[0]))
        deletedWords.record(other.requireId(), listOf(words[1]))

        assertEquals(listOf(words[0]), deletedWords.since(user.requireId(), syncedAt))
        assertEquals(emptyList(), deletedWords.since(user.requireId(), TEST_NOW.plusSeconds(120)))
    }

    @Test
    fun `tombstones older than the retention period are purged`() {
        val (deletedWords, user, words) = setUp()
        deletedWords.record(user.requireId(), listOf(words[0]))
        clock.set(TEST_NOW.plus(DeletedWords.RETENTION).plus(Duration.ofDays(1)))
        deletedWords.record(user.requireId(), listOf(words[1]))

        deletedWords.purgeOld()

        assertEquals(listOf(words[1]), deletedWords.since(user.requireId(), TEST_NOW.minusSeconds(1)))
    }

    private fun setUp(): Triple<DeletedWords, User, List<Long>> {
        val user = userRepository.save(User(email = "tombstone@example.com", name = "Learner"))
        val ids = (1..2).map { n ->
            requireNotNull(
                wordRepository.save(
                    Word(user = user, originalWord = "w$n", translation = "t$n", sourceLanguage = "en", targetLanguage = "de"),
                ).id,
            )
        }
        return Triple(DeletedWords(jdbc, clock), user, ids)
    }
}
