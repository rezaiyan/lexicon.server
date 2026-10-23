package com.alirezaiyan.vokab.server.user

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger = KotlinLogging.logger {}

private const val MAX_FILE_SIZE = 5L * 1024 * 1024 // 5MB

private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)

/**
 * File extension for an accepted image, read from its leading bytes (JPEG, PNG or WebP); null for
 * anything else. The client's Content-Type header is not trusted.
 */
internal fun detectImageExtension(bytes: ByteArray): String? = when {
    bytes.startsWith(JPEG_MAGIC) -> "jpg"
    bytes.startsWith(PNG_MAGIC) -> "png"
    bytes.startsWith(RIFF_MAGIC) && bytes.startsWith(WEBP_MAGIC, offset = 8) -> "webp"
    else -> null
}

private fun ByteArray.startsWith(prefix: ByteArray, offset: Int = 0): Boolean =
    size >= offset + prefix.size && prefix.indices.all { this[offset + it] == prefix[it] }

@Service
class AvatarService(
    private val userRepository: UserRepository,
    @Value("\${app.avatar.upload-dir:/var/www/uploads/avatars}")
    private val uploadDir: String,
    @Value("\${app.avatar.base-url:}")
    private val baseUrl: String,
    private val clock: Clock
) {

    @Transactional
    fun uploadAvatar(userId: Long, file: MultipartFile): String {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        require(file.size <= MAX_FILE_SIZE) { "File size exceeds 5MB limit" }

        val bytes = file.bytes
        val extension = requireNotNull(detectImageExtension(bytes)) {
            "File type not allowed. Accepted: JPEG, PNG, WebP"
        }

        val uploadPath = Paths.get(uploadDir)
        Files.createDirectories(uploadPath)

        // Delete old avatar file if exists
        deleteAvatarFile(userId, uploadPath)

        val filename = "avatar_${userId}_${clock.millis()}.${extension}"
        val filePath = uploadPath.resolve(filename)
        Files.write(filePath, bytes)

        val imageUrl = "${baseUrl}/uploads/avatars/${filename}"

        user.profileImageUrl = imageUrl
        userRepository.save(user)

        logger.info { "Avatar uploaded for user $userId: $filename" }
        return imageUrl
    }

    @Transactional
    fun deleteAvatar(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { IllegalArgumentException("User not found") }

        val uploadPath = Paths.get(uploadDir)
        deleteAvatarFile(userId, uploadPath)

        user.profileImageUrl = null
        userRepository.save(user)

        logger.info { "Avatar deleted for user $userId" }
    }

    private fun deleteAvatarFile(userId: Long, uploadPath: Path) {
        if (!Files.isDirectory(uploadPath)) return
        Files.list(uploadPath).use { files ->
            files
                .filter { it.fileName.toString().startsWith("avatar_${userId}_") }
                .forEach { path ->
                    Files.deleteIfExists(path)
                    logger.debug { "Deleted old avatar file: ${path.fileName}" }
                }
        }
    }
}
