package com.alirezaiyan.vokab.server.service

import com.alirezaiyan.vokab.server.domain.entity.SubscriptionStatus
import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional

class UserServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        userService = UserService(userRepository)
    }

    // ── getUserById ───────────────────────────────────────────────────────────

    @Test
    fun `should return UserDto when user exists by id`() {
        // Arrange
        val user = createUser(id = 1L, email = "alice@example.com", name = "Alice")
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act
        val result = userService.getUserById(1L)

        // Assert
        assertEquals(1L, result.id)
        assertEquals("alice@example.com", result.email)
        assertEquals("Alice", result.name)
    }

    @Test
    fun `should map subscription status to dto when getting user by id`() {
        // Arrange
        val user = createUser(id = 1L, subscriptionStatus = SubscriptionStatus.ACTIVE)
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act
        val result = userService.getUserById(1L)

        // Assert
        assertEquals(SubscriptionStatus.ACTIVE, result.subscriptionStatus)
    }

    @Test
    fun `should map currentStreak to dto when getting user by id`() {
        // Arrange
        val user = createUser(id = 1L, currentStreak = 7)
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act
        val result = userService.getUserById(1L)

        // Assert
        assertEquals(7, result.currentStreak)
    }

    @Test
    fun `should throw IllegalArgumentException when user not found by id`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.getUserById(99L)
        }
    }

    @Test
    fun `should query repository with the given user id`() {
        // Arrange
        val user = createUser(id = 42L)
        every { userRepository.findById(42L) } returns Optional.of(user)

        // Act
        userService.getUserById(42L)

        // Assert
        verify(exactly = 1) { userRepository.findById(42L) }
    }

    // ── getUserByEmail ────────────────────────────────────────────────────────

    @Test
    fun `should return UserDto when user exists by email`() {
        // Arrange
        val user = createUser(id = 2L, email = "bob@example.com", name = "Bob")
        every { userRepository.findByEmail("bob@example.com") } returns Optional.of(user)

        // Act
        val result = userService.getUserByEmail("bob@example.com")

        // Assert
        assertEquals(2L, result.id)
        assertEquals("bob@example.com", result.email)
        assertEquals("Bob", result.name)
    }

    @Test
    fun `should throw IllegalArgumentException when user not found by email`() {
        // Arrange
        every { userRepository.findByEmail("ghost@example.com") } returns Optional.empty()

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.getUserByEmail("ghost@example.com")
        }
    }

    @Test
    fun `should query repository with the given email`() {
        // Arrange
        val user = createUser(id = 1L, email = "test@example.com")
        every { userRepository.findByEmail("test@example.com") } returns Optional.of(user)

        // Act
        userService.getUserByEmail("test@example.com")

        // Assert
        verify(exactly = 1) { userRepository.findByEmail("test@example.com") }
    }

    // ── updateUser: name update ───────────────────────────────────────────────

    @Test
    fun `should update user name when new name is provided`() {
        // Arrange
        val user = createUser(id = 1L, name = "Old Name")
        val saved = createUser(id = 1L, name = "New Name")
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } returns saved

        // Act
        val result = userService.updateUser(1L, name = "New Name")

        // Assert
        assertEquals("New Name", result.name)
    }

    @Test
    fun `should keep existing name when null name is passed to updateUser`() {
        // Arrange
        val user = createUser(id = 1L, name = "Existing Name")
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = userService.updateUser(1L, name = null)

        // Assert
        assertEquals("Existing Name", result.name)
    }

    @Test
    fun `should save updated user to repository`() {
        // Arrange
        val user = createUser(id = 1L, name = "Old")
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        userService.updateUser(1L, name = "New")

        // Assert
        verify(exactly = 1) { userRepository.save(any()) }
    }

    @Test
    fun `should throw IllegalArgumentException when user not found in updateUser`() {
        // Arrange
        every { userRepository.findById(99L) } returns Optional.empty()

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.updateUser(99L, name = "New Name")
        }
    }

    // ── updateUser: displayAlias update ──────────────────────────────────────

    @Test
    fun `should update displayAlias when valid alias is provided`() {
        // Arrange
        val user = createUser(id = 1L, displayAlias = "old_alias")
        val saved = createUser(id = 1L, displayAlias = "new_alias")
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } returns saved

        // Act
        val result = userService.updateUser(1L, name = null, displayAlias = "new_alias")

        // Assert
        assertEquals("new_alias", result.displayAlias)
    }

    @Test
    fun `should keep existing displayAlias when null alias is passed`() {
        // Arrange
        val user = createUser(id = 1L, displayAlias = "keep_me")
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = userService.updateUser(1L, name = null, displayAlias = null)

        // Assert
        assertEquals("keep_me", result.displayAlias)
    }

    @Test
    fun `should throw IllegalArgumentException when displayAlias is too short`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.updateUser(1L, name = null, displayAlias = "x")
        }
    }

    @Test
    fun `should throw IllegalArgumentException when displayAlias contains invalid characters`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.updateUser(1L, name = null, displayAlias = "invalid@alias!")
        }
    }

    @Test
    fun `should throw IllegalArgumentException when displayAlias exceeds 30 characters`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)

        // Act & Assert
        assertThrows<IllegalArgumentException> {
            userService.updateUser(1L, name = null, displayAlias = "a".repeat(31))
        }
    }

    @Test
    fun `should accept displayAlias with letters numbers spaces underscores and hyphens`() {
        // Arrange
        val user = createUser(id = 1L)
        val validAlias = "my alias_1-2"
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act & Assert — no exception
        val result = userService.updateUser(1L, name = null, displayAlias = validAlias)
        assertEquals(validAlias, result.displayAlias)
    }

    @Test
    fun `should accept displayAlias of exactly 2 characters`() {
        // Arrange
        val user = createUser(id = 1L)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act & Assert — minimum valid length
        val result = userService.updateUser(1L, name = null, displayAlias = "ab")
        assertEquals("ab", result.displayAlias)
    }

    @Test
    fun `should accept displayAlias of exactly 30 characters`() {
        // Arrange
        val user = createUser(id = 1L)
        val maxAlias = "a".repeat(30)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act & Assert — maximum valid length
        val result = userService.updateUser(1L, name = null, displayAlias = maxAlias)
        assertEquals(maxAlias, result.displayAlias)
    }

    // ── updateUser: dto field mapping ─────────────────────────────────────────

    @Test
    fun `should return null displayAlias in dto when user has no displayAlias`() {
        // Arrange
        val user = createUser(id = 1L, displayAlias = null)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = userService.updateUser(1L, name = "Name", displayAlias = null)

        // Assert
        assertNull(result.displayAlias)
    }

    @Test
    fun `should return null profileImageUrl in dto when user has no profile image`() {
        // Arrange
        val user = createUser(id = 1L, profileImageUrl = null)
        every { userRepository.findById(1L) } returns Optional.of(user)
        every { userRepository.save(any()) } answers { firstArg() }

        // Act
        val result = userService.updateUser(1L, name = "Name")

        // Assert
        assertNull(result.profileImageUrl)
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        currentStreak: Int = 0,
        displayAlias: String? = null,
        profileImageUrl: String? = null,
        active: Boolean = true,
    ): User = User(
        id = id,
        email = email,
        name = name,
        subscriptionStatus = subscriptionStatus,
        currentStreak = currentStreak,
        longestStreak = 0,
        displayAlias = displayAlias,
        profileImageUrl = profileImageUrl,
        active = active,
    )
}
