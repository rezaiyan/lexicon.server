package com.alirezaiyan.vokab.server.user

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional

class AvatarServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val userRepository: UserRepository = mockk()
    private lateinit var service: AvatarService
    private lateinit var user: User

    @BeforeEach
    fun setUp() {
        user = testUser()
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        service = AvatarService(userRepository, tempDir.resolve("avatars").toString(), "https://cdn", clock)
    }

    @Test
    fun `should reject a file whose bytes are not an image even when content type claims png`() {
        val file = upload("<html><script>alert(1)</script></html>".toByteArray(), "image/png")

        assertThrows(IllegalArgumentException::class.java) { service.uploadAvatar(1L, file) }
    }

    @Test
    fun `should store a png using the extension detected from its bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)

        val url = service.uploadAvatar(1L, upload(png, "image/jpeg"))

        assertTrue(url.endsWith(".png"))
        assertTrue(Files.exists(tempDir.resolve("avatars").resolve(url.substringAfterLast('/'))))
    }

    @Test
    fun `should delete avatar when the upload directory does not exist yet`() {
        user.profileImageUrl = "https://cdn/uploads/avatars/old.jpg"

        service.deleteAvatar(1L)

        assertNull(user.profileImageUrl)
    }

    @Test
    fun `detectImageExtension recognises jpeg and webp`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)

        assertEquals("jpg", detectImageExtension(jpeg))
        assertEquals("webp", detectImageExtension(webp))
    }

    // --- factories ---

    private fun upload(bytes: ByteArray, contentType: String) =
        MockMultipartFile("file", "avatar", contentType, bytes)

    private fun testUser() = User(id = 1L, email = "avatar@test.com", name = "Avatar User")
}
