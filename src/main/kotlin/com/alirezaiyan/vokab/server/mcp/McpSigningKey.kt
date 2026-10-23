package com.alirezaiyan.vokab.server.mcp

import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Loads the RSA key that signs MCP access tokens, creating it on first start. It lives next to the
 * app's JWT keys (a persisted volume in production) so tokens survive restarts, but it is a
 * separate key: an MCP token must never verify as an app token, or the reverse.
 *
 * The key is written to a temp file that is owner-only from the moment it exists (no window where
 * the umask leaves it readable), then published under its real name in one step that never
 * overwrites: if another instance got there first, its key is used.
 */
internal object McpSigningKey {

    fun loadOrCreate(path: String): RSAKey {
        val file = File(path)
        if (file.exists()) return load(file.toPath())

        val key = RSAKeyGenerator(KEY_SIZE).keyID(UUID.randomUUID().toString()).generate()
        val target = file.absoluteFile.toPath()
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".mcp-signing-", ".tmp", *ownerOnly(target.parent))
        try {
            Files.writeString(temp, key.toJSONString())
            if (!publish(temp, target)) {
                logger.info { "MCP signing key appeared at ${target} while creating one; using it" }
                return load(target)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        logger.info { "Created MCP signing key ${key.keyID} at ${file.path}" }
        return key
    }

    /** Makes [temp] visible as [target] without replacing an existing file. False when [target] exists. */
    private fun publish(temp: Path, target: Path): Boolean = try {
        Files.createLink(target, temp)
        true
    } catch (_: FileAlreadyExistsException) {
        false
    } catch (_: UnsupportedOperationException) {
        moveWithoutReplacing(temp, target)
    } catch (_: java.io.IOException) {
        // Hard links can be refused by some filesystems (e.g. certain volume mounts)
        if (Files.exists(target)) false else moveWithoutReplacing(temp, target)
    }

    private fun moveWithoutReplacing(temp: Path, target: Path): Boolean = try {
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        true
    } catch (_: FileAlreadyExistsException) {
        false
    }

    private fun load(path: Path): RSAKey {
        val permissions = runCatching { Files.getPosixFilePermissions(path) }.getOrNull()
        if (permissions != null && permissions.any { it !in OWNER_PERMISSIONS }) {
            logger.warn { "MCP signing key at $path is readable by other users; restrict it to the owner (chmod 600)" }
        }
        return RSAKey.parse(Files.readString(path))
    }

    private fun ownerOnly(dir: Path): Array<FileAttribute<*>> {
        val posix = runCatching { dir.fileSystem.supportedFileAttributeViews().contains("posix") }.getOrDefault(false)
        if (!posix) {
            logger.warn {
                "Filesystem at $dir has no POSIX permissions; the MCP signing key can't be restricted to its owner"
            }
            return emptyArray()
        }
        return arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    }

    private val OWNER_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    private const val KEY_SIZE = 2048
}
