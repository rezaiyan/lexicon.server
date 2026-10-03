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
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

private const val MAX_FILE_SIZE = 5L * 1024 * 1024 // 5MB
private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp")

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

        val contentType = file.contentType
        require(contentType in ALLOWED_CONTENT_TYPES) {
            "File type not allowed. Accepted: JPEG, PNG, WebP"
        }

        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

        val uploadPath = Paths.get(uploadDir)
        Files.createDirectories(uploadPath)

        // Delete old avatar file if exists
        deleteAvatarFile(userId, uploadPath)

        val filename = "avatar_${userId}_${clock.millis()}.${extension}"
        val filePath = uploadPath.resolve(filename)
        Files.copy(file.inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)

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
        Files.list(uploadPath)
            .filter { it.fileName.toString().startsWith("avatar_${userId}_") }
            .forEach { path ->
                Files.deleteIfExists(path)
                logger.debug { "Deleted old avatar file: ${path.fileName}" }
            }
    }
}
