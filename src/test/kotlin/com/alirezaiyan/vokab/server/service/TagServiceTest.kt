package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.answerReferences
import com.alirezaiyan.vokab.server.domain.entity.requireId
import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.domain.entity.Tag
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.TagRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TagServiceTest {

    private lateinit var tagRepository: TagRepository
    private lateinit var tagService: TagService

    @BeforeEach
    fun setUp() {
        tagRepository = mockk()
        tagService = TagService(tagRepository, clock = fixedClock(), userRepository = mockk<UserRepository>().answerReferences())
    }

    // --- list ---

    @Test
    fun `should return tags with word counts`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 1L, name = "Animals")
        every { tagRepository.findAllWithWordCountByUser(user) } returns listOf(
            arrayOf(tag as Any, 5L as Any)
        )

        // Act
        val result = tagService.list(user.requireId())

        // Assert
        assertEquals(1, result.size)
        assertEquals(1L, result[0].id)
        assertEquals("Animals", result[0].name)
        assertEquals(5L, result[0].wordCount)
        verify(exactly = 1) { tagRepository.findAllWithWordCountByUser(user) }
    }

    @Test
    fun `should return empty list when user has no tags`() {
        // Arrange
        val user = createUser()
        every { tagRepository.findAllWithWordCountByUser(user) } returns emptyList()

        // Act
        val result = tagService.list(user.requireId())

        // Assert
        assertEquals(0, result.size)
    }

    @Test
    fun `should map word count from Number type correctly`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 2L, name = "Food")
        // Repository returns Int-based Number (not Long) — coerce via (row[1] as Number).toLong()
        every { tagRepository.findAllWithWordCountByUser(user) } returns listOf(
            arrayOf(tag as Any, 3 as Any)
        )

        // Act
        val result = tagService.list(user.requireId())

        // Assert
        assertEquals(3L, result[0].wordCount)
    }

    // --- create ---

    @Test
    fun `should save new tag and return dto`() {
        // Arrange
        val user = createUser()
        val savedTag = createTag(id = 10L, name = "Travel")
        every { tagRepository.existsByUserAndName(user, "Travel") } returns false
        every { tagRepository.save(any()) } returns savedTag

        // Act
        val result = tagService.create(user.requireId(), "Travel")

        // Assert
        assertEquals(10L, result.id)
        assertEquals("Travel", result.name)
        assertEquals(0L, result.wordCount)
        verify(exactly = 1) { tagRepository.save(any()) }
    }

    @Test
    fun `should throw IllegalArgumentException when tag name already exists`() {
        // Arrange
        val user = createUser()
        every { tagRepository.existsByUserAndName(user, "Travel") } returns true

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            tagService.create(user.requireId(), "Travel")
        }
        verify(exactly = 0) { tagRepository.save(any()) }
    }

    @Test
    fun `should trim whitespace from tag name before saving`() {
        // Arrange
        val user = createUser()
        val savedTag = createTag(id = 11L, name = "Science")
        every { tagRepository.existsByUserAndName(user, "Science") } returns false
        every { tagRepository.save(any()) } returns savedTag

        // Act
        val result = tagService.create(user.requireId(), "  Science  ")

        // Assert
        assertEquals("Science", result.name)
        verify(exactly = 1) { tagRepository.existsByUserAndName(user, "Science") }
    }

    @Test
    fun `should throw IllegalArgumentException when trimmed tag name already exists`() {
        // Arrange
        val user = createUser()
        every { tagRepository.existsByUserAndName(user, "Science") } returns true

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            tagService.create(user.requireId(), "  Science  ")
        }
    }

    // --- rename ---

    @Test
    fun `should throw NoSuchElementException when tag not found on rename`() {
        // Arrange
        val user = createUser()
        every { tagRepository.findByIdAndUser(99L, user) } returns null

        // Act & Assert
        assertThrows(NoSuchElementException::class.java) {
            tagService.rename(user.requireId(), 99L, "New Name")
        }
    }

    @Test
    fun `should return same dto when name is unchanged on rename`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 5L, name = "History")
        every { tagRepository.findByIdAndUser(5L, user) } returns tag
        every { tagRepository.countWordsByTagId(5L) } returns 3L

        // Act
        val result = tagService.rename(user.requireId(), 5L, "History")

        // Assert
        assertEquals("History", result.name)
        assertEquals(3L, result.wordCount)
        verify(exactly = 0) { tagRepository.save(any()) }
        verify(exactly = 0) { tagRepository.existsByUserAndName(any(), any()) }
    }

    @Test
    fun `should return same dto when trimmed name matches existing name on rename`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 5L, name = "History")
        every { tagRepository.findByIdAndUser(5L, user) } returns tag
        every { tagRepository.countWordsByTagId(5L) } returns 2L

        // Act
        val result = tagService.rename(user.requireId(), 5L, "  History  ")

        // Assert
        assertEquals("History", result.name)
        verify(exactly = 0) { tagRepository.save(any()) }
    }

    @Test
    fun `should throw IllegalArgumentException when new name already exists for another tag`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 5L, name = "History")
        every { tagRepository.findByIdAndUser(5L, user) } returns tag
        every { tagRepository.existsByUserAndName(user, "Science") } returns true

        // Act & Assert
        assertThrows(IllegalArgumentException::class.java) {
            tagService.rename(user.requireId(), 5L, "Science")
        }
        verify(exactly = 0) { tagRepository.save(any()) }
    }

    @Test
    fun `should save renamed tag and return updated dto`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 5L, name = "History")
        every { tagRepository.findByIdAndUser(5L, user) } returns tag
        every { tagRepository.existsByUserAndName(user, "Modern History") } returns false
        every { tagRepository.save(tag) } returns tag
        every { tagRepository.countWordsByTagId(5L) } returns 7L

        // Act
        val result = tagService.rename(user.requireId(), 5L, "Modern History")

        // Assert
        assertEquals("Modern History", result.name)
        assertEquals(7L, result.wordCount)
        verify(exactly = 1) { tagRepository.save(tag) }
    }

    // --- delete ---

    @Test
    fun `should throw NoSuchElementException when tag not found on delete`() {
        // Arrange
        val user = createUser()
        every { tagRepository.findByIdAndUser(99L, user) } returns null

        // Act & Assert
        assertThrows(NoSuchElementException::class.java) {
            tagService.delete(user.requireId(), 99L)
        }
        verify(exactly = 0) { tagRepository.delete(any()) }
    }

    @Test
    fun `should delete tag when found`() {
        // Arrange
        val user = createUser()
        val tag = createTag(id = 3L, name = "Sports")
        every { tagRepository.findByIdAndUser(3L, user) } returns tag
        justRun { tagRepository.delete(tag) }

        // Act
        tagService.delete(user.requireId(), 3L)

        // Assert
        verify(exactly = 1) { tagRepository.delete(tag) }
    }

    // --- factory functions ---

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com"
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = TEST_NOW,
        updatedAt = TEST_NOW
    )

    private fun createTag(
        id: Long = 1L,
        name: String = "Tag",
        user: User? = null
    ): Tag {
        val tag = Tag(
            id = id,
            name = name,
            createdAt = TEST_NOW,
            updatedAt = TEST_NOW
        )
        tag.user = user
        return tag
    }
}
