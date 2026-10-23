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
    @MockitoBean private lateinit var metadataFetcher: McpClientMetadataFetcher

    private lateinit var learner: User

    @BeforeEach
    fun setUp() {
        learner = userRepository.save(User(email = "oauth-learner@example.com", name = "Learner", googleId = "google-uid-1"))
        wordRepository.save(Word(user = learner, originalWord = "Apfel", translation = "apple", sourceLanguage = "en", targetLanguage = "de"))
        `when`(firebase.verify("good-id-token")).thenReturn(googleClaims("google-uid-1", learner.email, "Learner"))
        `when`(firebase.verify("stranger-id-token")).thenReturn(googleClaims("google-uid-404", "stranger@example.com", "Stranger"))
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
        assertTrue(metadata["authorization_response_iss_parameter_supported"].asBoolean())
        assertTrue(metadata["client_id_metadata_document_supported"].asBoolean())
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
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                org.hamcrest.Matchers.containsString("scope=\"progress:read words:delete words:read words:write\"")))
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
        val csp = requireNotNull(response.getHeader("Content-Security-Policy"))
        assertTrue(csp.contains("frame-ancestors 'none'"))
        // Firebase's auth iframe is served from our own domain (authDomain is the issuer host)
        assertTrue(csp.contains("frame-src 'self'"), csp)
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

    @Test
    fun `registration drops grant types we don't offer and needs authorization_code`() {
        fun registerWithGrants(grants: List<String>) = post("/oauth/register").withFreshIp()
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(mapOf(
                "redirect_uris" to listOf(REDIRECT),
                "token_endpoint_auth_method" to "none",
                "grant_types" to grants,
            )))

        val client = json(mockMvc.perform(
            registerWithGrants(listOf("authorization_code", "refresh_token", "urn:ietf:params:oauth:grant-type:jwt-bearer"))
        ).andExpect(status().isCreated))
        assertEquals(setOf("authorization_code", "refresh_token"), client["grant_types"].map { it.asText() }.toSet())

        mockMvc.perform(registerWithGrants(listOf("client_credentials"))).andExpect(status().isBadRequest)
    }

    // ── the whole flow ───────────────────────────────────────────────────────

    @Test
    fun `public client signs in, consents and calls mcp with the issued token`() {
        val clientId = registerClient("none")["client_id"].asText()
        val tokens = authorize(clientId, clientSecret = null)

        assertTrue(tokens["expires_in"].asLong() <= 3600, "access tokens are short-lived")
        val accessToken = tokens["access_token"].asText()

        val toolNames = json(mockMvc.perform(mcp(rpc("tools/list"), accessToken)).andExpect(status().isOk))["result"]["tools"]
            .map { it["name"].asText() }
        assertTrue("search_words" in toolNames)

        val call = json(mockMvc.perform(mcp(rpc("tools/call", mapOf("name" to "search_words", "arguments" to mapOf("query" to "apple"))), accessToken))
            .andExpect(status().isOk))
        val words = call["result"]["structuredContent"]["words"]
        assertEquals(listOf("Apfel"), words.map { it["word"].asText() })
    }

    @Test
    fun `confidential client also gets a refresh token`() {
        val client = registerClient("client_secret_basic")
        val tokens = authorize(client["client_id"].asText(), client["client_secret"].asText())

        assertNotNull(tokens["refresh_token"]?.asText())
    }

    @Test
    fun `public client refreshes with a rotating refresh token`() {
        val clientId = registerClient("none")["client_id"].asText()
        val first = authorize(clientId, clientSecret = null)
        val firstRefresh = requireNotNull(first["refresh_token"]?.asText()) { "public clients get a refresh token" }

        val second = refresh(clientId, firstRefresh)
        assertEquals(200, second.status, second.contentAsString)
        val rotated = objectMapper.readTree(second.contentAsString)
        val secondRefresh = rotated["refresh_token"].asText()
        assertTrue(secondRefresh != firstRefresh, "refresh tokens rotate")
        mockMvc.perform(mcp(rpc("tools/list"), rotated["access_token"].asText())).andExpect(status().isOk)

        // The replaced refresh token is spent
        assertEquals(400, refresh(clientId, firstRefresh).status)
    }

    @Test
    fun `refreshing as another client fails`() {
        val owner = registerClient("none")["client_id"].asText()
        val other = registerClient("none")["client_id"].asText()
        val refreshToken = authorize(owner, clientSecret = null)["refresh_token"].asText()

        assertEquals(400, refresh(other, refreshToken).status)
    }

    @Test
    fun `the authorization response names the issuer`() {
        authorize(registerClient("none")["client_id"].asText(), clientSecret = null)

        assertEquals(ISSUER, lastCallback.getFirst("iss"))
    }

    @Test
    fun `authorize refuses a token for another resource and says so to the client`() {
        val clientId = registerClient("none")["client_id"].asText()
        val session = signedInSession()

        val response = mockMvc.perform(authorizeRequest(clientId, resource = "https://other.example.com/mcp").session(session))
            .andReturn().response

        assertEquals(302, response.status, response.contentAsString)
        val callback = callbackParams(requireNotNull(response.redirectedUrl))
        assertEquals("invalid_target", callback.getFirst("error"))
        assertEquals(ISSUER, callback.getFirst("iss"))
    }

    @Test
    fun `token endpoint refuses another resource`() {
        val response = mockMvc.perform(
            post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", "authorization_code")
                .param("code", "whatever")
                .param("client_id", registerClient("none")["client_id"].asText())
                .param("resource", "https://other.example.com/mcp")
        ).andReturn().response

        assertEquals(400, response.status)
        assertEquals("invalid_target", objectMapper.readTree(response.contentAsString)["error"].asText())
    }

    @Test
    fun `consent page names the app and where it sends the learner back`() {
        val clientId = registerClient("none")["client_id"].asText()
        val session = signedInSession()

        val toConsent = mockMvc.perform(authorizeRequest(clientId).session(session).accept(MediaType.TEXT_HTML))
            .andReturn().response
        val page = mockMvc.perform(get(java.net.URI(requireNotNull(toConsent.redirectedUrl) { "no consent redirect: ${toConsent.status}" })).session(session))
            .andExpect(status().isOk).andReturn().response

        assertTrue("Test MCP client" in page.contentAsString)
        assertTrue("client.example.com" in page.contentAsString)
        assertTrue(requireNotNull(page.getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'"))
    }

    @Test
    fun `deleting words is offered unticked, everything else ticked`() {
        val clientId = registerClient("none")["client_id"].asText()
        val session = signedInSession()

        val toConsent = mockMvc.perform(
            authorizeRequest(clientId, scope = "words:read words:delete").session(session).accept(MediaType.TEXT_HTML)
        ).andReturn().response
        val page = mockMvc.perform(get(java.net.URI(requireNotNull(toConsent.redirectedUrl))).session(session))
            .andReturn().response.contentAsString

        assertTrue("""value="words:read" checked>""" in page, page)
        assertTrue("""value="words:delete">""" in page, page)
        assertTrue("Delete words" in page)
    }

    @Test
    fun `consent page escapes a hostile app name`() {
        val clientId = json(mockMvc.perform(
            post("/oauth/register").withFreshIp().contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(mapOf(
                "client_name" to "<script>alert(1)</script>",
                "redirect_uris" to listOf(REDIRECT),
                "token_endpoint_auth_method" to "none",
            )))
        ).andExpect(status().isCreated))["client_id"].asText()
        val session = signedInSession()

        val toConsent = mockMvc.perform(authorizeRequest(clientId).session(session).accept(MediaType.TEXT_HTML)).andReturn().response
        val page = mockMvc.perform(get(java.net.URI(requireNotNull(toConsent.redirectedUrl))).session(session)).andReturn().response.contentAsString

        assertTrue("<script>alert(1)</script>" !in page)
        assertTrue("&lt;script&gt;" in page)
    }

    // ── client ID metadata documents ────────────────────────────────────────

    @Test
    fun `a client identified by its metadata document URL connects without registering`() {
        `when`(metadataFetcher.fetch(java.net.URI(CIMD_CLIENT))).thenReturn(metadataDocument(CIMD_CLIENT))

        val tokens = authorize(CIMD_CLIENT, clientSecret = null)

        assertNotNull(tokens["refresh_token"]?.asText())
        mockMvc.perform(mcp(rpc("tools/list"), tokens["access_token"].asText())).andExpect(status().isOk)
    }

    @Test
    fun `consent for a metadata document client names the site that published it`() {
        `when`(metadataFetcher.fetch(java.net.URI(CIMD_CLIENT))).thenReturn(metadataDocument(CIMD_CLIENT))
        val session = signedInSession()

        val toConsent = mockMvc.perform(authorizeRequest(CIMD_CLIENT).session(session).accept(MediaType.TEXT_HTML)).andReturn().response
        val page = mockMvc.perform(get(java.net.URI(requireNotNull(toConsent.redirectedUrl))).session(session))
            .andReturn().response.contentAsString

        assertTrue("Published by <strong>apps.example.com</strong>" in page, page)
    }

    @Test
    fun `authorization requests without S256 PKCE or with unknown scopes go back as errors`() {
        val clientId = registerClient("none")["client_id"].asText()
        val session = signedInSession()
        fun errorFor(request: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder): String? {
            val redirect = mockMvc.perform(request.session(session)).andReturn().response.redirectedUrl
            return redirect?.takeIf { it.startsWith(REDIRECT) }?.let { callbackParams(it).getFirst("error") }
        }

        assertEquals("invalid_request", errorFor(authorizeRequest(clientId, challengeMethod = null)))
        assertEquals("invalid_request", errorFor(authorizeRequest(clientId, challengeMethod = "plain")))
        assertEquals("invalid_scope", errorFor(authorizeRequest(clientId, scope = "admin:all")))
    }

    @Test
    fun `a native client on a localhost redirect may use any port`() {
        // Claude Code's real document (2026-10-15): it listens on a random port on whichever loopback host works
        `when`(metadataFetcher.fetch(java.net.URI(CIMD_CLIENT))).thenReturn(objectMapper.writeValueAsString(mapOf(
            "client_id" to CIMD_CLIENT,
            "client_name" to "Claude Code",
            "redirect_uris" to listOf("http://localhost/callback", "http://127.0.0.1/callback"),
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "token_endpoint_auth_method" to "none",
        )))

        val tokens = authorize(CIMD_CLIENT, clientSecret = null, redirect = "http://localhost:57499/callback")

        assertTrue(lastCallbackUrl.startsWith("http://localhost:57499/callback?"), lastCallbackUrl)
        assertNotNull(tokens["access_token"]?.asText())
        listOf("http://localhost:57499/elsewhere", "http://example.com:57499/callback").forEach { redirect ->
            val response = mockMvc.perform(authorizeRequest(CIMD_CLIENT, redirect = redirect).session(signedInSession()))
                .andReturn().response
            assertEquals(400, response.status, redirect)
            assertNull(response.redirectedUrl, "never redirect to an unregistered URI: $redirect")
        }
    }

    @Test
    fun `a metadata document claiming another client id is refused`() {
        `when`(metadataFetcher.fetch(java.net.URI(CIMD_CLIENT))).thenReturn(metadataDocument("https://evil.example.com/client.json"))

        val response = mockMvc.perform(authorizeRequest(CIMD_CLIENT).session(signedInSession())).andReturn().response

        assertEquals(400, response.status)
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

    /** Query parameters the client got back on its redirect URI in the last [authorize]. */
    private lateinit var lastCallback: org.springframework.util.MultiValueMap<String, String>
    private lateinit var lastCallbackUrl: String

    private val verifier = "verifier-" + "x".repeat(50)

    private fun authorizeRequest(
        clientId: String,
        resource: String = "$ISSUER/mcp",
        redirect: String = REDIRECT,
        scope: String = "words:read",
        challengeMethod: String? = "S256",
    ) = get("/oauth2/authorize")
        .queryParam("response_type", "code")
        .queryParam("client_id", clientId)
        .queryParam("redirect_uri", redirect)
        .queryParam("scope", scope)
        .queryParam("state", "client-state")
        .apply {
            if (challengeMethod != null) {
                queryParam("code_challenge", Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())))
                queryParam("code_challenge_method", challengeMethod)
            }
        }
        .queryParam("resource", resource)

    private fun signedInSession(): MockHttpSession = MockHttpSession().also { session ->
        mockMvc.perform(signIn("good-id-token", session)).andExpect(status().isOk)
    }

    /** Runs authorize → login → consent → token and returns the token response. */
    private fun authorize(clientId: String, clientSecret: String?, redirect: String = REDIRECT): JsonNode {
        val session = MockHttpSession()
        val authorize = authorizeRequest(clientId, redirect = redirect)

        // Not signed in yet: off to our login page
        val toLogin = mockMvc.perform(authorize.session(session).accept(MediaType.TEXT_HTML)).andReturn().response
        assertEquals(302, toLogin.status, "authorize answered ${toLogin.status}: ${toLogin.errorMessage} ${toLogin.contentAsString}")
        assertTrue(requireNotNull(toLogin.redirectedUrl).contains("/oauth/login"), "redirect was ${toLogin.redirectedUrl}")

        mockMvc.perform(signIn("good-id-token", session)).andExpect(status().isOk)

        // Signed in: SAS sends the learner to our consent page
        val toConsent = mockMvc.perform(authorize.session(session).accept(MediaType.TEXT_HTML)).andReturn().response
        assertEquals(302, toConsent.status, "authorize answered ${toConsent.status}: ${toConsent.contentAsString}")
        val consentPage = mockMvc.perform(get(java.net.URI(requireNotNull(toConsent.redirectedUrl))).session(session))
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
        assertEquals(302, approved.status, "consent answered ${approved.status}: ${approved.errorMessage} ${approved.contentAsString}")
        lastCallbackUrl = requireNotNull(approved.redirectedUrl)
        lastCallback = callbackParams(lastCallbackUrl)
        assertEquals("client-state", lastCallback.getFirst("state"))
        val code = requireNotNull(lastCallback.getFirst("code"))

        val token = post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "authorization_code")
            .param("code", code)
            .param("redirect_uri", redirect)
            .param("code_verifier", verifier)
            .param("resource", "$ISSUER/mcp")
        if (clientSecret == null) token.param("client_id", clientId)
        else token.header(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray()))
        val response = mockMvc.perform(token).andReturn().response
        assertEquals(200, response.status, "token endpoint answered ${response.status}: ${response.contentAsString}")
        return objectMapper.readTree(response.contentAsString)
    }

    /** Decoded query parameters of a redirect back to the client. */
    private fun callbackParams(url: String): org.springframework.util.MultiValueMap<String, String> =
        UriComponentsBuilder.fromUriString(url).build(true).queryParams.let { raw ->
            org.springframework.util.LinkedMultiValueMap<String, String>().apply {
                raw.forEach { (name, values) -> put(name, values.map { org.springframework.web.util.UriUtils.decode(it, Charsets.UTF_8) }) }
            }
        }

    private fun refresh(clientId: String, refreshToken: String) = mockMvc.perform(
        post("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .param("grant_type", "refresh_token")
            .param("refresh_token", refreshToken)
            .param("client_id", clientId)
            .param("resource", "$ISSUER/mcp")
    ).andReturn().response

    private fun registerClient(authMethod: String): JsonNode =
        json(mockMvc.perform(register(listOf(REDIRECT), authMethod)).andExpect(status().isCreated))

    private fun register(redirects: List<String>, authMethod: String) = post("/oauth/register")
        .withFreshIp()
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(mapOf(
            "client_name" to "Test MCP client",
            "redirect_uris" to redirects,
            "grant_types" to listOf("authorization_code", "refresh_token"),
            "response_types" to listOf("code"),
            "token_endpoint_auth_method" to authMethod,
        )))

    private fun googleClaims(uid: String, email: String, name: String) =
        FirebaseIdClaims(uid, email, name, emailVerified = true, signInProvider = FirebaseIdClaims.GOOGLE_SIGN_IN_PROVIDER)

    private fun signIn(idToken: String, session: MockHttpSession) = post("/oauth/session")
        .withFreshIp()
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

    /** Registration is limited per IP; each test registration comes from its own address. */
    private fun org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder.withFreshIp() =
        with { it.apply { remoteAddr = "10.${registrations / 250}.${registrations++ % 250}.1" } }

    private fun metadataDocument(clientId: String) = objectMapper.writeValueAsString(mapOf(
        "client_id" to clientId,
        "client_name" to "Metadata client",
        "redirect_uris" to listOf(REDIRECT),
        "grant_types" to listOf("authorization_code", "refresh_token"),
        "token_endpoint_auth_method" to "none",
    ))

    private companion object {
        const val CIMD_CLIENT = "https://apps.example.com/lexicon/client.json"
        var registrations = 0
        const val REDIRECT = "https://client.example.com/callback"
    }
}

private const val ISSUER = "http://localhost"
