package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertTrue

/**
 * Claude Code's connect sequence froze production on 2026-10-15: each `/mcp` call took a second pool
 * connection inside the first (SAS's JDBC services look up the client while reading the grant), and a
 * few concurrent calls deadlocked the pool. The test pool has 2 connections, so this replays the
 * sequence on a real Tomcat and fails fast if the nesting comes back.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [MCP_ENABLED, MCP_TEST_ISSUER_PROPERTY, MCP_TEST_KEY_PROPERTY],
)
@ActiveProfiles("test")
class McpConcurrentCallsTest {

    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var clients: RegisteredClientRepository
    @Autowired private lateinit var authorizations: OAuth2AuthorizationService
    @Autowired private lateinit var jwkSource: JWKSource<SecurityContext>
    @Autowired private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    /** Requests run on server threads, so there's no test transaction to roll back: clean up by hand. */
    private var learnerId: Long? = null

    @org.junit.jupiter.api.AfterEach
    fun cleanUp() {
        val id = learnerId ?: return
        val clientIds = jdbc.queryForList(
            "SELECT registered_client_id FROM oauth2_authorization WHERE principal_name = ?",
            String::class.java,
            id.toString(),
        )
        jdbc.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", id.toString())
        clientIds.forEach { jdbc.update("DELETE FROM oauth2_registered_client WHERE id = ?", it) }
        userRepository.deleteById(id)
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    @Test
    fun `claude code's connect sequence doesn't stall the server`() {
        val learner = userRepository.save(User(email = "vt-${UUID.randomUUID()}@example.com", name = "Learner"))
        learnerId = learner.requireId()
        val token = McpTestTokens(clients, authorizations, jwkSource).accessToken(learner.requireId())

        post(token, """{"jsonrpc":"2.0","id":0,"method":"server/discover","params":{}}""")
        post(
            token,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                """"capabilities":{},"clientInfo":{"name":"claude-code","version":"2.1.291"}}}""",
        )
        post(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        // Several list calls at once, as a client fetching its capabilities does
        val calls = (2..9).map { id ->
            val listTools = """{"jsonrpc":"2.0","id":$id,"method":"tools/list","params":{}}"""
            CompletableFuture.supplyAsync { post(token, listTools) }
        }
        val results = calls.map { runCatching { it.get() } }
        if (results.any { it.isFailure }) dumpThreads()
        results.forEach { assertTrue(it.getOrThrow().startsWith("200"), it.toString()) }

        assertTrue(get("/api/v1/health").startsWith("200"), "server still answers")
    }

    private fun post(token: String, body: String): String = send(
        HttpRequest.newBuilder(URI("http://localhost:$port/mcp"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body))
    )

    private fun dumpThreads() {
        val file = java.io.File("build/tmp/vt-dump.txt").apply { delete() }
        java.lang.management.ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean::class.java)
            .dumpThreads(file.absolutePath, com.sun.management.HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN)
    }

    private fun get(path: String): String = send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET())

    private fun send(request: HttpRequest.Builder): String {
        val response = http.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString())
        return "${response.statusCode()} ${response.body().take(300)}"
    }
}
