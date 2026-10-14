package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.FirebaseIdClaims
import com.alirezaiyan.vokab.server.auth.FirebaseIdTokenVerifier
import com.alirezaiyan.vokab.server.auth.RS256JwtTokenProvider
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.words.Word
import com.alirezaiyan.vokab.server.words.WordRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The OAuth 2.1 flow an MCP client (Claude, ChatGPT, Claude Code) runs against us: discovery,
 * open client registration, sign-in with the learner's existing Google account, consent,
 * code exchange with PKCE, then calling /mcp with the issued token.
 */
@SpringBootTest(
    properties = [
        "spring.ai.mcp.server.enabled=true",
        "app.mcp.issuer=$ISSUER",
        "app.mcp.signing-key-path=build/tmp/mcp-test-signing-key.json",
    ]
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class McpOAuthFlowTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var wordRepository: WordRepository
    @Autowired private lateinit var authorizationService: OAuth2AuthorizationService
    @Autowired private lateinit var appTokens: RS256JwtTokenProvider
    @MockitoBean private lateinit var firebase: FirebaseIdTokenVerifier

    private lateinit var learner: User

    @BeforeEach
    fun setUp() {
        learner = userRepository.save(User(email = "oauth-learner@example.com", name = "Learner", googleId = "google-uid-1"))
        wordRepository.save(Word(user = learner, originalWord = "Apfel", translation = "apple", sourceLanguage = "de", targetLanguage = "en"))
        `when`(firebase.verify("good-id-token")).thenReturn(FirebaseIdClaims("google-uid-1", learner.email, "Learner"))
        `when`(firebase.verify("stranger-id-token")).thenReturn(FirebaseIdClaims("google-uid-404", "stranger@example.com", "Stranger"))
    }

    // ── discovery ────────────────────────────────────────────────────────────

    @Test
    fun `authorization server metadata advertises registration and PKCE`() {
        val metadata = json(mockMvc.perform(get("/.well-known/oauth-authorization-server")).andExpect(status().isOk))

        assertEquals(ISSUER, metadata["issuer"].asText())
        assertEquals("$ISSUER/oauth/register", metadata["registration_endpoint"].asText())
        assertTrue(metadata["code_challenge_methods_supported"].any { it.asText() == "S256" })
        assertTrue(metadata["token_endpoint_auth_methods_supported"].any { it.asText() == "none" })
        assertTrue(metadata["scopes_supported"].map { it.asText() }.containsAll(listOf("words:read", "words:write")))
    }

    @Test
    fun `protected resource metadata points at this authorization server`() {
        for (path in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            val metadata = json(mockMvc.perform(get(path)).andExpect(status().isOk))

            assertEquals("$ISSUER/mcp", metadata["resource"].asText())
            assertEquals(listOf(ISSUER), metadata["authorization_servers"].map { it.asText() })
        }
    }

    @Test
    fun `mcp without a token answers 401 pointing at the resource metadata`() {
        mockMvc.perform(mcp(rpc("tools/list"), token = null))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                org.hamcrest.Matchers.containsString("resource_metadata=\"$ISSUER/.well-known/oauth-protected-resource/mcp\"")))
    }

    @Test
    fun `mcp rejects the app's own access tokens`() {
        val appToken = appTokens.generateAccessToken(learner.requireId(), learner.email)

        mockMvc.perform(mcp(rpc("tools/list"), appToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `login page is utf-8 html under a strict content security policy`() {
        val response = mockMvc.perform(get("/oauth/login")).andExpect(status().isOk).andReturn().response

        assertEquals("text/html;charset=UTF-8", response.contentType)
        assertTrue(requireNotNull(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'"))
        assertTrue("Continue with Google" in response.contentAsString)
    }

    // ── registration ─────────────────────────────────────────────────────────

    @Test
    fun `registration rejects a plain http redirect that isn't loopback`() {
        mockMvc.perform(register(listOf("http://evil.example.com/cb"), "none"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `registration accepts https and loopback redirects`() {
        val client = json(mockMvc.perform(register(listOf("https://claude.ai/api/mcp/auth_callback", "http://127.0.0.1:33418/callback"), "none"))
            .andExpect(status().isCreated))

        assertNotNull(client["client_id"]?.asText())
        assertNull(client["client_secret"])
        assertEquals("none", client["token_endpoint_auth_method"].asText())
    }

    // ── the whole flow ───────────────────────────────────────────────────────

    @Test
    fun `public client signs in, consents and calls mcp with the issued token`() {
        val clientId = registerClient("none")["client_id"].asText()
        val tokens = authorize(clientId, clientSecret = null)

        assertNull(tokens["refresh_token"], "SAS 1.x issues no refresh token to public clients")
        val accessToken = tokens["access_token"].asText()

        val toolNames = json(mockMvc.perform(mcp(rpc("tools/list"), accessToken)).andExpect(status().isOk))["result"]["tools"]
            .map { it["name"].asText() }
        assertTrue("search_words" in toolNames)

        val call = json(mockMvc.perform(mcp(rpc("tools/call", mapOf("name" to "search_words", "arguments" to mapOf("query" to "apple"))), accessToken))
            .andExpect(status().isOk))
        val words = objectMapper.readTree(call["result"]["content"][0]["text"].asText())
        assertEquals(listOf("Apfel"), words.map { it["word"].asText() })
    }

    @Test
    fun `confidential client also gets a refresh token`() {
        val client = registerClient("client_secret_basic")
        val tokens = authorize(client["client_id"].asText(), client["client_secret"].asText())

        assertNotNull(tokens["refresh_token"]?.asText())
    }

    @Test
    fun `a revoked grant stops working immediately`() {
        val accessToken = authorize(registerClient("none")["client_id"].asText(), clientSecret = null)["access_token"].asText()
        val authorization = requireNotNull(authorizationService.findByToken(accessToken, OAuth2TokenType.ACCESS_TOKEN))

        authorizationService.remove(authorization)

        mockMvc.perform(mcp(rpc("tools/list"), accessToken)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `signing in with a Google account that has no Lexicon account is refused`() {
        val users = userRepository.count()

        mockMvc.perform(signIn("stranger-id-token", MockHttpSession())).andExpect(status().isForbidden)

        assertEquals(users, userRepository.count())
    }

    @Test
    fun `sign-in from another origin is refused`() {
        mockMvc.perform(
            post("/oauth/session").session(MockHttpSession())
                .header(HttpHeaders.ORIGIN, "https://evil.example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"good-id-token"}""")
        ).andExpect(status().isForbidden)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Runs authorize → login → consent → token and returns the token response. */
    private fun authorize(clientId: String, clientSecret: String?): JsonNode {
        val session = MockHttpSession()
        val verifier = "verifier-" + "x".repeat(50)
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val authorize = get("/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", REDIRECT)
            .queryParam("scope", "words:read")
            .queryParam("state", "client-state")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("resource", "$ISSUER/mcp")

        // Not signed in yet: off to our login page
        val toLogin = mockMvc.perform(authorize.session(session).accept(MediaType.TEXT_HTML)).andReturn().response
        assertEquals(302, toLogin.status, "authorize answered ${toLogin.status}: ${toLogin.errorMessage} ${toLogin.contentAsString}")
        assertTrue(requireNotNull(toLogin.redirectedUrl).contains("/oauth/login"), "redirect was ${toLogin.redirectedUrl}")

        mockMvc.perform(signIn("good-id-token", session)).andExpect(status().isOk)

        // Signed in: SAS shows the consent page
        val consentPage = mockMvc.perform(authorize.session(session).accept(MediaType.TEXT_HTML))
            .andExpect(status().isOk).andReturn().response.contentAsString
        val state = Regex("""name="state" value="([^"]+)"""").find(consentPage)?.groupValues?.get(1)
            ?: error("no state on consent page: $consentPage")

        val approved = mockMvc.perform(
            post("/oauth2/authorize").session(session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", clientId)
                .param("state", state)
                .param("scope", "words:read")
        ).andReturn().response
        assertEquals(302, approved.status, "consent answered ${approved.status}: ${approved.contentAsString}")
        val callback = UriComponentsBuilder.fromUriString(requireNotNull(approved.redirectedUrl)).build().queryParams
        assertEquals("client-state", callback.getFirst("state"))
        val code = requireNotNull(callback.getFirst("code"))

        val token = post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "authorization_code")
            .param("code", code)
            .param("redirect_uri", REDIRECT)
            .param("code_verifier", verifier)
            .param("resource", "$ISSUER/mcp")
        if (clientSecret == null) token.param("client_id", clientId)
        else token.header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray()))
        val response = mockMvc.perform(token).andReturn().response
        assertEquals(200, response.status, "token endpoint answered ${response.status}: ${response.contentAsString}")
        return objectMapper.readTree(response.contentAsString)
    }

    private fun registerClient(authMethod: String): JsonNode =
        json(mockMvc.perform(register(listOf(REDIRECT), authMethod)).andExpect(status().isCreated))

    private fun register(redirects: List<String>, authMethod: String) = post("/oauth/register")
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf(
            "client_name" to "Test MCP client",
            "redirect_uris" to redirects,
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to authMethod,
        )))

    private fun signIn(idToken: String, session: MockHttpSession) = post("/oauth/session")
        .session(session)
        .header(HttpHeaders.ORIGIN, ISSUER)
        .contentType(MediaType.APPLICATION_JSON)
        .content("""{"idToken":"$idToken"}""")

    private fun mcp(body: String, token: String?) = post("/mcp")
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
        .content(body)
        .apply { token?.let { header(HttpHeaders.AUTHORIZATION, "Bearer $it") } }

    private fun rpc(method: String, params: Map<String, Any> = emptyMap()): String =
        objectMapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to 1, "method" to method, "params" to params))

    private fun json(result: org.springframework.test.web.servlet.ResultActions): JsonNode =
        objectMapper.readTree(result.andReturn().response.contentAsString)

    private companion object {
        const val REDIRECT = "https://client.example.com/callback"
    }
}

private const val ISSUER = "http://localhost"
