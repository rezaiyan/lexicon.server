package com.alirezaiyan.vokab.server.mcp

import tools.jackson.databind.ObjectMapper
import io.github.bucket4j.Bucket
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URI

/**
 * DNS-rebinding guard the MCP transport spec requires: a request carrying an `Origin` header
 * (i.e. sent by a browser) must come from a site we trust. Native clients send no Origin.
 */
internal class McpOriginFilter(issuer: String, extraOrigins: Collection<String>) : OncePerRequestFilter() {
    private val allowed: Set<String> = (extraOrigins + issuer).mapNotNull(::originOf).toSet()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val origin = request.getHeader(HttpHeaders.ORIGIN)
        if (origin != null && originOf(origin) !in allowed) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.contentType = "application/json"
            response.writer.write("""{"jsonrpc":"2.0","error":{"code":-32600,"message":"Origin not allowed"}}""")
            return
        }
        chain.doFilter(request, response)
    }

    private companion object {
        /** scheme://host[:port], lower-cased; null for anything that isn't an http(s) origin. */
        fun originOf(value: String): String? {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "$scheme://$host$port"
        }
    }
}

/**
 * Answers a `tools/call` the token's scopes don't cover with 403 `insufficient_scope` (RFC 6750),
 * naming the scopes to ask for, so the client can step up instead of failing. Runs after the
 * bearer token is authenticated; reads the JSON-RPC body once and hands a replayable copy on.
 */
internal class McpScopeFilter(
    private val objectMapper: ObjectMapper,
    private val resourceMetadataUrl: String,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        if (request.method != "POST" || token == null) {
            chain.doFilter(request, response)
            return
        }
        val body = request.inputStream.readNBytes(MAX_BODY_BYTES + 1)
        if (body.size > MAX_BODY_BYTES) {
            response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            return
        }
        val needed = requiredScope(body)
        val granted = token.authorities.mapNotNull { it.authority }
            .filter { it.startsWith(SCOPE_PREFIX) }
            .map { it.removePrefix(SCOPE_PREFIX) }
        if (needed != null && needed !in granted) {
            val scopes = McpScopes.asParameter(granted.filter { it in McpScopes.ALL } + needed)
            response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                """Bearer error="insufficient_scope", scope="$scopes", resource_metadata="$resourceMetadataUrl", """ +
                    """error_description="This call needs $needed access"""",
            )
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }
        chain.doFilter(ReplayableRequest(request, body), response)
    }

    /** The scope a `tools/call` needs; null for other methods, unknown tools and unparsable bodies. */
    private fun requiredScope(body: ByteArray): String? {
        val message = runCatching { objectMapper.readTree(body) }.getOrNull() ?: return null
        if (message.path("method").asString() != "tools/call") return null
        return McpScopes.BY_TOOL[message.path("params").path("name").asString()]
    }

    private class ReplayableRequest(request: HttpServletRequest, private val body: ByteArray) :
        HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = object : ServletInputStream() {
            private val stream = ByteArrayInputStream(body)
            override fun read(): Int = stream.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)
            override fun isFinished(): Boolean = stream.available() == 0
            override fun isReady(): Boolean = true
            override fun setReadListener(listener: ReadListener?) = Unit
        }

        override fun getReader(): BufferedReader =
            BufferedReader(InputStreamReader(ByteArrayInputStream(body), characterEncoding ?: Charsets.UTF_8.name()))

        override fun getContentLength(): Int = body.size
        override fun getContentLengthLong(): Long = body.size.toLong()
    }

    private companion object {
        const val SCOPE_PREFIX = "SCOPE_"
        /** add_words at its 100-word cap is far below this. */
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/**
 * Abuse guard, not a plan limit: tools cost no AI credits, so the cap is far above any real chat
 * (a runaway agent loop, not a learner, hits it). Counted per learner and app.
 */
internal class McpRateLimitFilter(private val bucketFor: (String) -> Bucket) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val token = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        if (token == null) {
            chain.doFilter(request, response)
            return
        }
        val clientId = token.token.getClaimAsString(McpAuthorizationServerConfig.CLIENT_ID_CLAIM).orEmpty()
        if (bucketFor("${token.name}:$clientId").tryConsume(1)) {
            chain.doFilter(request, response)
            return
        }
        response.status = TOO_MANY_REQUESTS
        response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
        response.contentType = "application/json"
        response.writer.write("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"Too many requests, slow down"}}""")
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
        const val RETRY_AFTER_SECONDS = "60"
    }
}
