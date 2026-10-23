package com.alirezaiyan.vokab.server.shared

import jakarta.servlet.http.HttpServletRequest

/**
 * The caller's IP address, for rate limits and logs.
 *
 * Forwarding headers are never read here: in production `server.forward-headers-strategy=native`
 * (docker-compose) makes Tomcat's RemoteIpValve resolve the client from the reverse proxy's
 * X-Forwarded-For, trusting only hops from internal proxy addresses, and put it in `remoteAddr`.
 * Reading X-Forwarded-For (or X-Real-IP, ...) directly would let any client pick its own address
 * and dodge every per-IP limit.
 */
fun HttpServletRequest.clientAddress(): String = remoteAddr?.takeIf { it.isNotBlank() } ?: "unknown"
