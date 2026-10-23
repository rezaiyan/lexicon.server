package com.alirezaiyan.vokab.server.mcp

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository
import org.springframework.stereotype.Service
import java.net.URI

/**
 * What the consent screen may say about a client. Only facts the client can't choose freely are
 * presented as trustworthy: a dynamically registered client picked its own name (anyone can register
 * as "Claude"), so it is labelled unverified and its full redirect URIs are shown; a metadata-document
 * client is named by the host it publishes from, with a warning when it sends learners back to
 * a different site.
 */
@Service
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class McpConsentClients(private val clients: RegisteredClientRepository) {

    internal fun consentClient(clientId: String): McpConsentPage.Client? =
        clients.findByClientId(clientId)?.let(::describe)

    internal companion object {
        fun describe(client: RegisteredClient): McpConsentPage.Client {
            val publisher = McpRegisteredClientRepository.publisherOf(client.clientId)
            val redirectUris = client.redirectUris.sorted()
            return McpConsentPage.Client(
                name = client.clientName,
                returnHosts = McpClientHosts.of(client),
                publisher = publisher,
                redirectUris = redirectUris,
                unverified = publisher == null,
                redirectsElsewhere = publisher != null && redirectsAwayFrom(publisher, redirectUris),
            )
        }

        /**
         * True when an https redirect goes to a site other than [publisher] (or its subdomains).
         * Loopback http redirects are a native app on this computer, not another site.
         */
        fun redirectsAwayFrom(publisher: String, redirectUris: Collection<String>): Boolean {
            val home = publisher.lowercase()
            return redirectUris.mapNotNull { runCatching { URI(it) }.getOrNull() }
                .filter { it.scheme == "https" }
                .mapNotNull { it.host?.lowercase() }
                .any { host -> host != home && !host.endsWith(".$home") }
        }
    }
}
