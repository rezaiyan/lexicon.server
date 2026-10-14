package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.RS256JwtTokenProvider
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals

@SpringBootTest(properties = [MCP_ENABLED, MCP_TEST_ISSUER_PROPERTY, MCP_TEST_KEY_PROPERTY])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpConnectionsTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var clients: RegisteredClientRepository
    @Autowired private lateinit var authorizations: OAuth2AuthorizationService
    @Autowired private lateinit var jwkSource: JWKSource<SecurityContext>
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var appTokens: RS256JwtTokenProvider

    private lateinit var learner: User
    private lateinit var other: User

    @BeforeEach
    fun setUp() {
        learner = userRepository.save(User(email = "connections-learner@example.com", name = "Learner"))
        other = userRepository.save(User(email = "connections-other@example.com", name = "Other"))
    }

    @Test
    fun `a learner without connected apps gets an empty list`() {
        assertEquals(0, connections(learner).size())
    }

    @Test
    fun `lists only the caller's live connections with their client name and access`() {
        tokens().accessToken(learner.requireId(), scopes = setOf(McpScopes.WORDS_READ))
        tokens().accessToken(other.requireId())
        val expired = tokens().accessToken(learner.requireId())
        jdbc.update(
            "UPDATE oauth2_authorization SET access_token_expires_at = now() - interval '1 hour' WHERE access_token_value = ?",
            expired,
        )

        val list = connections(learner)

        assertEquals(1, list.size())
        assertEquals("Test client", list[0]["clientName"].asText())
        assertEquals(listOf(McpScopes.WORDS_READ), list[0]["scopes"].map { it.asText() })
        assertEquals(true, list[0]["connectedAt"].asLong() > 0)
    }

    @Test
    fun `disconnecting an app removes it and its token stops working on mcp`() {
        val token = tokens().accessToken(learner.requireId())
        val id = connections(learner)[0]["id"].asText()
        mockMvc.perform(mcp(token)).andExpect(status().isOk)

        mockMvc.perform(delete("/api/v1/mcp/connections/$id").with(signedIn(learner)))
            .andExpect(status().isNoContent)

        assertEquals(0, connections(learner).size())
        mockMvc.perform(mcp(token)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `a learner can't disconnect someone else's app`() {
        tokens().accessToken(other.requireId())
        val id = connections(other)[0]["id"].asText()

        mockMvc.perform(delete("/api/v1/mcp/connections/$id").with(signedIn(learner)))
            .andExpect(status().isNotFound)

        assertEquals(1, connections(other).size())
    }

    @Test
    fun `connections need the app sign-in`() {
        mockMvc.perform(get("/api/v1/mcp/connections")).andExpect(status().isUnauthorized)
    }

    private fun connections(user: User): JsonNode {
        val body = mockMvc.perform(get("/api/v1/mcp/connections").with(signedIn(user)))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(body)["data"]
    }

    /** The app's own sign-in token, as the Settings screen sends it. */
    private fun signedIn(user: User) = RequestPostProcessor { request ->
        request.addHeader("Authorization", "Bearer " + appTokens.generateAccessToken(user.requireId(), user.email))
        request
    }

    private fun mcp(token: String) = post("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .header("Authorization", "Bearer $token")
        .content("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")

    private fun tokens() = McpTestTokens(clients, authorizations, jwkSource)
}
