package com.alirezaiyan.vokab.server.listening

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.requireId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/** Not @Transactional: the persister commits in REQUIRES_NEW, so assertions read committed rows. */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ListeningIntegrationTest {

    @Autowired lateinit var listeningService: ListeningService
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var jdbc: JdbcTemplate

    private lateinit var user: User

    @BeforeAll
    fun setupUser() {
        user = testUserHelper.saveAndCommit(User(email = "listening@test.com", name = "Listening User"))
    }

    @AfterAll
    fun teardownUser() {
        clearListening()
        testUserHelper.deleteByEmail("listening@test.com")
    }

    @BeforeEach
    fun setup() = clearListening()

    @Test
    fun `sync stores the session and every heard word`() {
        val response = listeningService.syncSessions(
            user.requireId(),
            SyncListeningRequest(listOf(sessionRequest("s-1", wordIds = listOf(10, 11, 12)))),
        )

        assertEquals(listOf("s-1"), response.syncedSessionIds)
        assertEquals(1, count("listening_sessions"))
        assertEquals(3, count("listening_session_words"))
        val row = jdbc.queryForMap(
            "SELECT source, source_detail, word_order, words_heard, listening_ms, completed_normally FROM listening_sessions WHERE user_id = ?",
            user.requireId(),
        )
        assertEquals("level", row["source"])
        assertEquals("NEW", row["source_detail"])
        assertEquals("translation_first", row["word_order"])
        assertEquals(3, row["words_heard"])
        assertEquals(18_000L, row["listening_ms"])
        assertEquals(false, row["completed_normally"])
    }

    @Test
    fun `sync is idempotent per client session id`() {
        val request = SyncListeningRequest(listOf(sessionRequest("dup", wordIds = listOf(1, 2))))

        listeningService.syncSessions(user.requireId(), request)
        val second = listeningService.syncSessions(user.requireId(), request)

        assertEquals(listOf("dup"), second.syncedSessionIds)
        assertEquals(1, count("listening_sessions"))
        assertEquals(2, count("listening_session_words"))
    }

    @Test
    fun `sync stores a session with no heard words`() {
        listeningService.syncSessions(
            user.requireId(),
            SyncListeningRequest(listOf(sessionRequest("empty", wordIds = emptyList()))),
        )

        assertEquals(1, count("listening_sessions"))
        assertEquals(0, count("listening_session_words"))
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE user_id = ?", Int::class.java, user.requireId()) ?: -1

    private fun clearListening() {
        jdbc.update("DELETE FROM listening_session_words WHERE user_id = ?", user.requireId())
        jdbc.update("DELETE FROM listening_sessions WHERE user_id = ?", user.requireId())
    }

    private fun sessionRequest(id: String, wordIds: List<Long>) = SyncListeningSessionRequest(
        clientSessionId = id,
        source = "level",
        sourceDetail = "NEW",
        order = "translation_first",
        repeatCount = 2,
        pauseMs = 3000,
        speechRate = 0.9f,
        plannedWords = 5,
        wordsHeard = wordIds.size,
        wordsSkipped = 1,
        pauseCount = 2,
        listeningMs = 18_000,
        durationMs = 25_000,
        completedNormally = false,
        startedAt = 1_700_000_000_000,
        endedAt = 1_700_000_025_000,
        words = wordIds.map {
            SyncListeningWordRequest(wordId = it, sourceLanguage = "en", targetLanguage = "de", heardAt = 1_700_000_001_000 + it)
        },
    )
}
