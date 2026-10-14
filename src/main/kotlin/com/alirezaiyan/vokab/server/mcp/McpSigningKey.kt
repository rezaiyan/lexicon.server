package com.alirezaiyan.vokab.server.mcp

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Loads the RSA key that signs MCP access tokens, creating it on first start. It lives next to the
 * app's JWT keys (a persisted volume in production) so tokens survive restarts, but it is a
 * separate key: an MCP token must never verify as an app token, or the reverse.
 */
internal object McpSigningKey {

    fun loadOrCreate(path: String): RSAKey {
        val file = File(path)
        if (file.exists()) return RSAKey.parse(file.readText())

        val key = RSAKeyGenerator(KEY_SIZE).keyID(UUID.randomUUID().toString()).generate()
        file.absoluteFile.parentFile.mkdirs()
        file.writeText(key.toJSONString())
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        logger.info { "Created MCP signing key ${key.keyID} at ${file.path}" }
        return key
    }

    private const val KEY_SIZE = 2048
}
