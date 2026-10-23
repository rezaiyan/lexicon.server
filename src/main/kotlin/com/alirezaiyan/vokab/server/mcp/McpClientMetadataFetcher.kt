package com.alirezaiyan.vokab.server.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.hc.client5.http.SystemDefaultDnsResolver
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.HttpHeaders
import org.apache.hc.core5.util.Timeout
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/** Fetches a client's metadata document. An interface so tests don't reach the network. */
fun interface McpClientMetadataFetcher {
    /** The document body, or null when it can't be fetched safely. */
    fun fetch(url: URI): String?
}

/**
 * Fetching a URL a stranger chose is an SSRF risk, so: https on the default port only, no
 * redirects, short timeouts, a small body cap, and a DNS resolver that refuses names resolving to
 * private, loopback, link-local (cloud metadata) or other non-public addresses. The connection uses
 * the addresses that resolver approved, so a name can't be rebound between the check and the request.
 *
 * It is also a DoS risk (a slow server could hold the pool), so every fetch has a hard total
 * deadline after which the request is aborted, and waiting for a pooled connection is short.
 */
@Component
@ConditionalOnProperty("spring.ai.mcp.server.enabled", havingValue = "true")
class HttpMcpClientMetadataFetcher : McpClientMetadataFetcher {

    private val client: CloseableHttpClient = HttpClients.custom()
        .setConnectionManager(
            PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(PublicOnlyDnsResolver)
                .setDefaultConnectionConfig(
                    ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(CONNECT_SECONDS))
                        .setSocketTimeout(Timeout.ofSeconds(READ_SECONDS))
                        .build()
                )
                .build()
        )
        .setDefaultRequestConfig(
            RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(READ_SECONDS))
                .setConnectionRequestTimeout(Timeout.ofSeconds(POOL_WAIT_SECONDS))
                .setRedirectsEnabled(false)
                .build()
        )
        .disableRedirectHandling()
        .disableCookieManagement()
        .disableAuthCaching()
        .build()

    override fun fetch(url: URI): String? {
        if (url.scheme != "https" || (url.port != -1 && url.port != HTTPS_PORT)) return null
        val request = HttpGet(url).apply { setHeader(HttpHeaders.ACCEPT, "application/json") }
        // Per-read timeouts don't bound a server that trickles one byte every few seconds
        val deadline = DEADLINES.schedule({ request.cancel() }, TOTAL_SECONDS, TimeUnit.SECONDS)
        return runCatching {
            client.execute(request) { response ->
                if (response.code != OK) {
                    null
                } else {
                    val bytes = response.entity?.content?.use { it.readNBytes(MAX_BYTES + 1) } ?: ByteArray(0)
                    if (bytes.size > MAX_BYTES) null else bytes.toString(Charsets.UTF_8)
                }
            }
        }.onFailure { logger.info { "Client metadata fetch failed for ${url.host}: ${it.javaClass.simpleName}" } }
            .also { deadline.cancel(false) }
            .getOrNull()
    }

    internal object PublicOnlyDnsResolver : org.apache.hc.client5.http.DnsResolver {
        override fun resolve(host: String): Array<InetAddress> {
            val addresses = SystemDefaultDnsResolver.INSTANCE.resolve(host)
            if (addresses.isEmpty() || !addresses.all(::isPublic)) throw UnknownHostException("$host is not public")
            return addresses
        }

        override fun resolveCanonicalHostname(host: String): String =
            SystemDefaultDnsResolver.INSTANCE.resolveCanonicalHostname(host)
    }

    internal companion object {
        const val MAX_BYTES = 5 * 1024
        const val OK = 200
        const val CONNECT_SECONDS = 3L
        const val READ_SECONDS = 5L
        const val TOTAL_SECONDS = 5L
        const val POOL_WAIT_SECONDS = 1L
        const val HTTPS_PORT = 443

        /** Aborts fetches that overrun [TOTAL_SECONDS]; one daemon thread is plenty. */
        private val DEADLINES = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mcp-metadata-deadline").apply { isDaemon = true }
        }

        /** True only for globally routable unicast addresses. Uses InetAddress's own classification. */
        fun isPublic(address: InetAddress): Boolean {
            val special = address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            if (special) return false
            val bytes = address.address
            return when (address) {
                is Inet4Address -> !isReservedIpv4(bytes)
                is Inet6Address -> !isReservedIpv6(bytes)
                else -> false
            }
        }

        private fun isReservedIpv4(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            return first == 0 || // "this network"
                (first == 100 && second in 64..127) || // carrier-grade NAT
                (first == 192 && second == 0 && (b[2].toInt() and 0xFF) == 0) || // IETF protocol assignments
                (first == 198 && second in 18..19) || // benchmarking
                first >= 240 // reserved and broadcast
        }

        private fun isReservedIpv6(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xFF
            val mappedOrCompatible = b.copyOfRange(0, 10).all { it.toInt() == 0 }
            val second = b[1].toInt() and 0xFF
            return (first and 0xFE) == 0xFC || // unique local fc00::/7
                mappedOrCompatible || // ::ffff:a.b.c.d and friends: judged as IPv4 only, never via IPv6
                // IPv4 embedded in IPv6 reaches an IPv4 address we never vetted:
                (startsWith(b, NAT64_WELL_KNOWN) && b.copyOfRange(4, 12).all { it.toInt() == 0 }) || // 64:ff9b::/96
                startsWith(b, NAT64_LOCAL) || // 64:ff9b:1::/48
                (first == 0x20 && second == 0x02) || // 6to4 2002::/16
                startsWith(b, TEREDO) // Teredo 2001::/32
        }

        private fun startsWith(bytes: ByteArray, prefix: IntArray): Boolean =
            prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }

        private val NAT64_WELL_KNOWN = intArrayOf(0x00, 0x64, 0xFF, 0x9B)
        private val NAT64_LOCAL = intArrayOf(0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01)
        private val TEREDO = intArrayOf(0x20, 0x01, 0x00, 0x00)
    }
}
