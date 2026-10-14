package com.alirezaiyan.vokab.server.mcp

import org.springframework.security.oauth2.server.authorization.client.RegisteredClient
import java.net.URI

/** Where a client really lives, as opposed to the name it gave itself: the hosts of its redirect URIs. */
internal object McpClientHosts {

    fun of(client: RegisteredClient): List<String> = of(client.redirectUris)

    fun of(redirectUris: Collection<String>): List<String> =
        redirectUris.mapNotNull { runCatching { URI(it).host }.getOrNull() }.distinct().sorted()
}
