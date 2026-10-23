package com.alirezaiyan.vokab.server.mcp

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Our consent screen (Spring Authorization Server's default can't show where the app redirects).
 * SAS sends the signed-in learner here with the request's client, scopes and state; the form posts
 * the choice back to SAS's authorize endpoint, which checks state and the session's principal.
 */
@RestController
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpConsentController(private val consentClients: McpConsentClients) {

    @GetMapping(McpAuthorizationServerConfig.CONSENT_PATH, produces = ["text/html;charset=UTF-8"])
    fun consentPage(
        @RequestParam("client_id") clientId: String,
        @RequestParam("state") state: String,
        @RequestParam("scope", defaultValue = "") scope: String,
        response: HttpServletResponse,
    ): ResponseEntity<String> {
        val client = consentClients.consentClient(clientId)
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Unknown app")
        response.setHeader("Content-Security-Policy", McpConsentPage.CSP)
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("X-Frame-Options", "DENY")
        return ResponseEntity.ok(
            McpConsentPage.render(
                client = client,
                clientId = clientId,
                state = state,
                requestedScopes = scope.split(' ').filter { it.isNotBlank() },
                authorizePath = AUTHORIZE_PATH,
            )
        )
    }

    private companion object {
        const val AUTHORIZE_PATH = "/oauth2/authorize"
    }
}
