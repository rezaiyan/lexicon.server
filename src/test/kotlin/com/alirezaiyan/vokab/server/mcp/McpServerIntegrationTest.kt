package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.AuditEventType
import com.alirezaiyan.vokab.server.auth.AuditLogRepository
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
    @Autowired private lateinit var auditLogRepository: AuditLogRepository

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
    fun `a client without words read access gets 403 insufficient_scope naming what it needs`() {
        token = tokens().accessToken(learner.requireId(), scopes = setOf(McpScopes.PROGRESS_READ))

        val response = mockMvc.perform(mcpRequest(toolCall("search_words"), token))
            .andExpect(status().isForbidden)
            .andReturn().response

        val challenge = requireNotNull(response.getHeader("WWW-Authenticate"))
        assertTrue("error=\"insufficient_scope\"" in challenge, challenge)
        // Keeps what was granted, adds what this call needs, so re-consent doesn't drop access
        assertTrue("scope=\"progress:read words:read\"" in challenge, challenge)
        assertTrue("resource_metadata=" in challenge, challenge)
    }

    @Test
    fun `listing tools needs no particular scope`() {
        token = tokens().accessToken(learner.requireId(), scopes = setOf(McpScopes.PROGRESS_READ))

        mockMvc.perform(mcpRequest(rpc("tools/list"), token)).andExpect(status().isOk)
    }

    @Test
    fun `a browser call from another site is refused even with a valid token`() {
        mockMvc.perform(mcpRequest(rpc("tools/list"), token).header("Origin", "https://evil.example.com"))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `a browser call from our own site is allowed`() {
        mockMvc.perform(mcpRequest(rpc("tools/list"), token).header("Origin", MCP_TEST_ISSUER))
            .andExpect(status().isOk)
    }

    @Test
    fun `tools list marks which tools only read and which add`() {
        val tools = call(rpc("tools/list"))["result"]["tools"].associateBy { it["name"].asText() }

        assertEquals(READ_TOOLS + WRITE_TOOLS, tools.keys)
        READ_TOOLS.forEach { assertTrue(tools.getValue(it)["annotations"]["readOnlyHint"].asBoolean(), it) }
        WRITE_TOOLS.forEach {
            assertFalse(tools.getValue(it)["annotations"]["readOnlyHint"].asBoolean(), it)
            assertFalse(tools.getValue(it)["annotations"]["destructiveHint"].asBoolean(), it)
        }
    }

    @Test
    fun `every tool has a title, works on the learner's own data only and declares its output`() {
        val tools = call(rpc("tools/list"))["result"]["tools"]

        tools.forEach { tool ->
            val name = tool["name"].asText()
            assertTrue(tool["title"]?.asText().orEmpty().isNotBlank(), "$name has no title")
            assertFalse(tool["annotations"]["openWorldHint"].asBoolean(), "$name claims an open world")
            assertEquals("object", tool["outputSchema"]?.get("type")?.asText(), "$name has no object output schema")
        }
    }

    @Test
    fun `tool results come back as structured content with a text copy`() {
        val result = call(rpc("tools/call", mapOf("name" to "list_tags", "arguments" to emptyMap<String, Any>())))["result"]

        assertEquals("fruit", result["structuredContent"]["tags"][0]["name"].asText())
        assertEquals(result["structuredContent"], objectMapper.readTree(result["content"][0]["text"].asText()))
    }

    @Test
    fun `add_words tells the AI client exactly what a word needs`() {
        val tools = call(rpc("tools/list"))["result"]["tools"].associateBy { it["name"].asText() }
        val item = tools.getValue("add_words")["inputSchema"]["properties"]["words"]["items"]

        assertEquals(
            setOf("word", "translation", "language", "translationLanguage", "note", "tags"),
            item["properties"].fieldNames().asSequence().toSet(),
        )
        assertEquals(setOf("word", "translation", "language"), item["required"].map { it.asText() }.toSet())
    }

    @Test
    fun `search_words finds only the caller's matching words`() {
        val words = toolResult("search_words", mapOf("query" to "apple"))["words"]

        assertEquals(setOf("Apfel", "manzana"), words.map { it["word"].asText() }.toSet())
        val apfel = words.single { it["word"].asText() == "Apfel" }
        assertEquals("de", apfel["language"].asText())
        assertEquals("en", apfel["translationLanguage"].asText())
    }

    @Test
    fun `search_words narrows by language and tag`() {
        val german = toolResult("search_words", mapOf("language" to "de"))["words"]
        val fruit = toolResult("search_words", mapOf("tag" to "Fruit"))["words"]
        val unknownTag = toolResult("search_words", mapOf("tag" to "nope"))["words"]

        assertEquals(setOf("Apfel", "Birne"), german.map { it["word"].asText() }.toSet())
        assertEquals(listOf("Apfel"), fruit.map { it["word"].asText() })
        assertEquals(0, unknownTag.size())
    }

    @Test
    fun `search_words treats wildcards in the query literally and honours the limit`() {
        wordRepository.save(word(learner, "100% sicher", "100% sure", "de"))

        val percent = toolResult("search_words", mapOf("query" to "%"))["words"]
        val underscore = toolResult("search_words", mapOf("query" to "_"))["words"]
        val limited = toolResult("search_words", mapOf("limit" to 2))["words"]

        assertEquals(listOf("100% sicher"), percent.map { it["word"].asText() })
        assertEquals(0, underscore.size())
        assertEquals(2, limited.size())
    }

    @Test
    fun `get_due_words puts the most overdue first and counts beyond the limit`() {
        wordRepository.save(word(learner, "Gestern", "yesterday", "de").apply { nextReviewDate = -1 })

        val due = toolResult("get_due_words", mapOf("limit" to 1))

        assertEquals(4, due["dueCount"].asInt())
        assertEquals(listOf("Gestern"), due["words"].map { it["word"].asText() })
    }

    @Test
    fun `list_tags returns the caller's tags with word counts`() {
        val tags = toolResult("list_tags", emptyMap())["tags"]

        assertEquals(1, tags.size())
        assertEquals("fruit", tags[0]["name"].asText())
        assertEquals(1, tags[0]["wordCount"].asLong())
    }

    @Test
    fun `add_words adds new words, skips known ones and tags them`() {
        val result = toolResult("add_words", mapOf("words" to listOf(
            mapOf("word" to "Apfel", "translation" to "apple", "language" to "de"),
            mapOf("word" to "Kirsche", "translation" to "cherry", "language" to "de", "note" to "die Kirsche",
                "tags" to listOf("fruit", "news")),
        )))

        assertEquals(listOf("Kirsche"), result["added"].map { it["word"].asText() })
        assertEquals(listOf("Apfel"), result["skipped"].map { it.asText() })
        val kirsche = wordRepository.findAllByUserWithTags(learner).single { it.originalWord == "Kirsche" }
        assertEquals("de", kirsche.targetLanguage)
        assertEquals("en", kirsche.sourceLanguage, "translation language defaults to the learner's usual one")
        assertEquals("die Kirsche", kirsche.description)
        assertEquals(setOf("fruit", "news"), kirsche.tags.map { it.name }.toSet())
    }

    @Test
    fun `words added by an AI app are recorded in the audit log by id`() {
        val added = toolResult("add_words", mapOf("words" to listOf(
            mapOf("word" to "Pflaume", "translation" to "plum", "language" to "de"),
        )))["added"][0]["id"].asLong()

        val entry = auditLogRepository.findAll().single { it.userId == learner.id && it.eventType == AuditEventType.MCP_WRITE }
        val metadata = objectMapper.readTree(entry.metadata)
        assertEquals("add_words", metadata["tool"].asText())
        assertEquals(listOf(added), metadata["wordIds"].map { it.asLong() })
        assertTrue(metadata["client"].asText().startsWith("test_"))
        assertTrue("Pflaume" !in requireNotNull(entry.metadata), "no word text in the audit log")
    }

    @Test
    fun `add_words rejects a language the app doesn't support and stores nothing`() {
        val count = wordRepository.findAllByUser(learner).size

        val result = toolError("add_words", mapOf("words" to listOf(
            mapOf("word" to "Kirsche", "translation" to "cherry", "language" to "klingon"),
        )))

        assertTrue("klingon" in result)
        assertEquals(count, wordRepository.findAllByUser(learner).size)
    }

    @Test
    fun `add_words needs words write access`() {
        token = tokens().accessToken(learner.requireId(), scopes = setOf(McpScopes.WORDS_READ))

        val challenge = mockMvc.perform(mcpRequest(toolCall("add_words"), token))
            .andExpect(status().isForbidden)
            .andReturn().response.getHeader("WWW-Authenticate")

        assertTrue("scope=\"words:read words:write\"" in requireNotNull(challenge), challenge)
    }

    @Test
    fun `tag_words adds a tag to the caller's words`() {
        val birne = wordRepository.findAllByUser(learner).single { it.originalWord == "Birne" }

        val result = toolResult("tag_words", mapOf("wordIds" to listOf(birne.id), "tags" to listOf("fruit")))

        assertEquals(1, result["tagged"].asInt())
        assertEquals(setOf("Apfel", "Birne"), toolResult("search_words", mapOf("tag" to "fruit"))["words"].map { it["word"].asText() }.toSet())
    }

    @Test
    fun `get_due_words lists due words, most overdue first`() {
        wordRepository.save(word(learner, "Morgen", "tomorrow", "de").apply { nextReviewDate = Long.MAX_VALUE })

        val due = toolResult("get_due_words", emptyMap())

        assertEquals(3, due["dueCount"].asInt())
        assertTrue("Morgen" !in due["words"].map { it["word"].asText() })
    }

    @Test
    fun `progress tools answer for a learner without reviews`() {
        assertEquals(0, toolResult("get_difficult_words", emptyMap())["words"].size())
        assertEquals(0, toolResult("get_weekly_report", emptyMap())["cardsReviewed"].asInt())
    }

    private fun toolResult(name: String, arguments: Map<String, Any>): JsonNode {
        val result = call(rpc("tools/call", mapOf("name" to name, "arguments" to arguments)))["result"]
        assertFalse(result["isError"]?.asBoolean() ?: false, "tool error: $result")
        return result["structuredContent"]
    }

    /** Calls a tool that must fail and returns its error text. */
    private fun toolError(name: String, arguments: Map<String, Any>): String {
        val result = call(rpc("tools/call", mapOf("name" to name, "arguments" to arguments)))["result"]
        assertTrue(result["isError"]?.asBoolean() ?: false, "expected a tool error: $result")
        return result["content"][0]["text"].asText()
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

    private fun toolCall(name: String) = rpc("tools/call", mapOf("name" to name, "arguments" to emptyMap<String, Any>()))

    private fun rpc(method: String, params: Map<String, Any> = emptyMap()): String =
        objectMapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private fun word(user: User, original: String, translation: String, language: String) = Word(
        user = user,
        originalWord = original,
        translation = translation,
        sourceLanguage = "en",
        targetLanguage = language,
    )

    private fun tokens() = McpTestTokens(clients, authorizations, jwkSource)

    private companion object {
        val READ_TOOLS = setOf("search_words", "list_tags", "get_due_words", "get_difficult_words", "get_weekly_report")
        val WRITE_TOOLS = setOf("add_words", "tag_words")
    }
}
