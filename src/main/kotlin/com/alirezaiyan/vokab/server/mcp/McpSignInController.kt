package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.RateLimitConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * Signs the learner in for the OAuth flow, in the browser the MCP client opened: Google via
 * Firebase, like the web app, and the server trusts only the verified ID token. The session this
 * creates belongs to the authorization server alone (a session cookie, never an API token).
 */
@RestController
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpSignInController(
    private val oauthService: McpOAuthService,
    private val rateLimits: RateLimitConfig,
    private val appProperties: AppProperties,
) {
    private val securityContextRepository = HttpSessionSecurityContextRepository()
    private val requestCache = HttpSessionRequestCache()

    @GetMapping(McpAuthorizationServerConfig.LOGIN_PATH, produces = ["text/html;charset=UTF-8"])
    fun loginPage(response: HttpServletResponse): String {
        response.setHeader("Content-Security-Policy", LOGIN_PAGE_CSP)
        response.setHeader("Referrer-Policy", "no-referrer")
        val mcp = appProperties.mcp
        return McpLoginPage.render(
            apiKey = mcp.firebaseWebApiKey,
            authDomain = mcp.firebaseAuthDomain,
            projectId = mcp.firebaseProjectId,
            sessionPath = McpAuthorizationServerConfig.SESSION_PATH,
        )
    }

    @PostMapping(McpAuthorizationServerConfig.SESSION_PATH)
    fun signIn(
        @RequestBody body: McpSignInRequest,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ResponseEntity<McpSignInResponse> = when {
        !isSameOrigin(request.getHeader(HttpHeaders.ORIGIN)) ->
            refuse(HttpStatus.FORBIDDEN, "Sign in from the Lexicon page")
        !rateLimits.getAuthBucket(clientIp(request)).tryConsume(1) ->
            refuse(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts, try again in a minute")
        else -> when (val result = oauthService.signIn(body.idToken)) {
            McpOAuthService.SignInResult.InvalidToken -> refuse(HttpStatus.UNAUTHORIZED, "Google sign-in failed")
            McpOAuthService.SignInResult.NoAccount ->
                refuse(HttpStatus.FORBIDDEN, "No Lexicon account uses this Google account. Sign up in the app first.")
            is McpOAuthService.SignInResult.SignedIn ->
                ResponseEntity.ok(startSession(result.userId, request, response))
        }
    }

    private fun startSession(
        userId: Long,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): McpSignInResponse {
        request.getSession(true)
        request.changeSessionId()
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            userId.toString(), null, listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
        logger.info { "MCP OAuth sign-in for userId=$userId" }

        // Back to the authorize request that sent the browser here (SAS saved it in the session)
        val savedAuthorize = requestCache.getRequest(request, response)?.redirectUrl
        return McpSignInResponse(redirectUrl = savedAuthorize?.let(::authorizePathOrNull))
    }

    /** Path and query only: behind the proxy the saved URL carries the internal host. */
    private fun authorizePathOrNull(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.rawPath != AUTHORIZE_PATH) return null
        return uri.rawQuery?.let { "$AUTHORIZE_PATH?$it" } ?: AUTHORIZE_PATH
    }

    private fun isSameOrigin(origin: String?): Boolean {
        val actual = origin?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val expected = URI(appProperties.mcp.issuer)
        return actual.scheme == expected.scheme && actual.host == expected.host && actual.port == expected.port
    }

    private fun refuse(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(McpSignInResponse(error = message))

    private companion object {
        const val AUTHORIZE_PATH = "/oauth2/authorize"
        const val LOGIN_PAGE_CSP = "default-src 'none'; " +
            "script-src 'self' 'unsafe-inline' https://www.gstatic.com https://apis.google.com; " +
            "connect-src 'self' https://*.googleapis.com https://securetoken.googleapis.com; " +
            "frame-src https://*.firebaseapp.com https://accounts.google.com; " +
            "style-src 'unsafe-inline'; img-src 'self' data:; " +
            "frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    }
}
