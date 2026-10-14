package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.words.TagService
import com.alirezaiyan.vokab.server.words.Word
import com.alirezaiyan.vokab.server.words.WordRepository
import com.alirezaiyan.vokab.server.words.WordService
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
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = [MCP_ENABLED, MCP_TEST_ISSUER_PROPERTY, MCP_TEST_KEY_PROPERTY])
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpServerIntegrationTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var wordRepository: WordRepository
    @Autowired private lateinit var wordService: WordService
    @Autowired private lateinit var tagService: TagService
    @Autowired private lateinit var clients: RegisteredClientRepository
    @Autowired private lateinit var authorizations: OAuth2AuthorizationService
    @Autowired private lateinit var jwkSource: JWKSource<SecurityContext>

    private lateinit var learner: User
    private lateinit var token: String

    @BeforeEach
    fun setUp() {
        learner = userRepository.save(User(email = "mcp-learner@example.com", name = "Learner"))
        val other = userRepository.save(User(email = "mcp-other@example.com", name = "Other"))
        val apfel = wordRepository.save(word(learner, "Apfel", "apple", "de"))
        wordRepository.save(word(learner, "Birne", "pear", "de"))
        wordRepository.save(word(learner, "manzana", "apple", "es"))
        wordRepository.save(word(other, "Apfelsaft", "apple juice", "de"))
        val fruit = tagService.create(learner.requireId(), "fruit")
        // Entity path, not batchAssignTags: its native insert bypasses this test transaction's cached Word
        wordService.updateWordTags(learner.requireId(), requireNotNull(apfel.id), listOf(fruit.id))
        token = tokens().accessToken(learner.requireId())
    }

    @Test
    fun `mcp endpoint without a token is rejected`() {
        mockMvc.perform(mcpRequest(rpc("tools/list"), bearer = null))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a client without words read access gets a tool error, not the words`() {
        token = tokens().accessToken(learner.requireId(), scopes = setOf(McpScopes.PROGRESS_READ))

        val result = call(rpc("tools/call", mapOf("name" to "search_words", "arguments" to emptyMap<String, Any>())))["result"]

        assertTrue(result["isError"].asBoolean())
        assertTrue("words:read" in result["content"][0]["text"].asText())
    }

    @Test
    fun `tools list exposes the read-only word tools`() {
        val tools = call(rpc("tools/list"))["result"]["tools"]
        val byName = tools.associateBy { it["name"].asText() }

        assertEquals(setOf("search_words", "list_tags"), byName.keys)
        assertEquals(true, byName.getValue("search_words")["annotations"]["readOnlyHint"].asBoolean())
    }

    @Test
    fun `search_words finds only the caller's matching words`() {
        val words = toolResult("search_words", mapOf("query" to "apple"))

        assertEquals(setOf("Apfel", "manzana"), words.map { it["word"].asText() }.toSet())
    }

    @Test
    fun `search_words narrows by language and tag`() {
        val german = toolResult("search_words", mapOf("language" to "de"))
        val fruit = toolResult("search_words", mapOf("tag" to "Fruit"))
        val unknownTag = toolResult("search_words", mapOf("tag" to "nope"))

        assertEquals(setOf("Apfel", "Birne"), german.map { it["word"].asText() }.toSet())
        assertEquals(listOf("Apfel"), fruit.map { it["word"].asText() })
        assertEquals(0, unknownTag.size())
    }

    @Test
    fun `list_tags returns the caller's tags with word counts`() {
        val tags = toolResult("list_tags", emptyMap())

        assertEquals(1, tags.size())
        assertEquals("fruit", tags[0]["name"].asText())
        assertEquals(1, tags[0]["wordCount"].asLong())
    }

    private fun toolResult(name: String, arguments: Map<String, Any>): JsonNode {
        val result = call(rpc("tools/call", mapOf("name" to name, "arguments" to arguments)))["result"]
        assertFalse(result["isError"]?.asBoolean() ?: false, "tool error: $result")
        return objectMapper.readTree(result["content"][0]["text"].asText())
    }

    private fun call(body: String): JsonNode {
        val response = mockMvc.perform(mcpRequest(body, token))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        return objectMapper.readTree(response)
    }

    private fun mcpRequest(body: String, bearer: String?) = post("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .content(body)
        .apply { bearer?.let { header("Authorization", "Bearer $it") } }

    private fun rpc(method: String, params: Map<String, Any> = emptyMap()): String =
        objectMapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private fun word(user: User, original: String, translation: String, language: String) = Word(
        user = user,
        originalWord = original,
        translation = translation,
        sourceLanguage = language,
        targetLanguage = "en",
    )

    private fun tokens() = McpTestTokens(clients, authorizations, jwkSource)
}
