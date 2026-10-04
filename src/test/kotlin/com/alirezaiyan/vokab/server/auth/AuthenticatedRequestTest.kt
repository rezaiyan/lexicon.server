package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TestUserHelper
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.words.TagRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Real JWT filter → controller → service → PostgreSQL, not transactional, like production: the
 * principal is only the token's user id and services resolve the user themselves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticatedRequestTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtTokenProvider: RS256JwtTokenProvider
    @Autowired lateinit var testUserHelper: TestUserHelper
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var accountDeletionService: AccountDeletionService
    @Autowired lateinit var entityManagerFactory: EntityManagerFactory

    private val created = mutableListOf<Long>()

    private fun user(active: Boolean = true): User {
        val email = "auth-${System.nanoTime()}@example.com"
        return testUserHelper.saveAndCommit(User(email = email, name = "Auth User", active = active))
            .also { created += it.requireId() }
    }

    private fun bearer(user: User) = "Bearer " + jwtTokenProvider.generateAccessToken(user.requireId(), user.email)

    @AfterEach
    fun cleanUp() {
        created.filter { userRepository.existsById(it) }.forEach(accountDeletionService::deleteAccount)
    }

    @Test
    fun `an authenticated write reaches the database as the token's user`() {
        val user = user()

        mockMvc.perform(
            post("/api/v1/tags").header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"verbs"}""")
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/tags").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].name").value("verbs"))
        assertEquals(listOf("verbs"), tagRepository.findAll().filter { it.user?.id == user.id }.map { it.name })
    }

    @Test
    fun `a service that reads user fields loads them on demand`() {
        val user = user()

        mockMvc.perform(get("/api/v1/leaderboard").header("Authorization", bearer(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `with a warm access cache a request issues no users-table query`() {
        val user = user()
        val token = bearer(user)
        mockMvc.perform(get("/api/v1/tags").header("Authorization", token)).andExpect(status().isOk)
        val stats = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        stats.clear()

        mockMvc.perform(get("/api/v1/tags").header("Authorization", token)).andExpect(status().isOk)

        assertEquals(1, stats.prepareStatementCount, "only the tags query; no user lookup per request")
        assertEquals(0, stats.getEntityStatistics(User::class.java.name).loadCount)
    }

    @Test
    fun `an inactive account is rejected`() {
        val user = user(active = false)

        mockMvc.perform(get("/api/v1/tags").header("Authorization", bearer(user)))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a deleted account is rejected at once despite the cache`() {
        val user = user()
        val token = bearer(user)
        mockMvc.perform(get("/api/v1/tags").header("Authorization", token)).andExpect(status().isOk)

        accountDeletionService.deleteAccount(user.requireId())

        mockMvc.perform(get("/api/v1/tags").header("Authorization", token)).andExpect(status().isForbidden)
    }
}
