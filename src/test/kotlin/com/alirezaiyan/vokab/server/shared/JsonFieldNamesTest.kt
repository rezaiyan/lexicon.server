package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.study.LeaderboardEntryDto
import com.alirezaiyan.vokab.server.support.TelegramUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.json.JsonMapper

/**
 * Field names the clients read, through the application's own mapper. Kotlin `is`-prefixed
 * properties keep their prefix only while the Jackson Kotlin module is registered (lost silently
 * otherwise, as `isCurrentUser` -> `currentUser`).
 */
@SpringBootTest
@ActiveProfiles("test")
class JsonFieldNamesTest {

    @Autowired private lateinit var jsonMapper: JsonMapper

    @Test
    fun `is-prefixed properties keep their name`() {
        val entry = LeaderboardEntryDto(
            rank = 1, displayName = "A", currentStreak = 1, longestStreak = 1, masteredWords = 1, isCurrentUser = true,
        )

        val json = jsonMapper.readTree(jsonMapper.writeValueAsString(entry))

        assertTrue(json.has("isCurrentUser"))
        assertFalse(json.has("currentUser"))
    }

    @Test
    fun `snake case JsonProperty names are read`() {
        val user = jsonMapper.readValue("""{"is_bot": true}""", TelegramUser::class.java)

        assertEquals(true, user.isBot)
    }
}
