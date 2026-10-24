package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.passki.getOrThrow
import com.alirezaiyan.passki.test.FakePasskeys
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import jakarta.persistence.EntityManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

/**
 * Real WebAuthn round trips: [FakePasskeys] signs genuine ES256 responses for the configured
 * relying party, and webauthn4j verifies them against the migrated schema.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PasskeyServiceTest {

    @Autowired lateinit var passkeyService: PasskeyService
    @Autowired lateinit var authService: AuthService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jsonMapper: JsonMapper
    @Autowired lateinit var em: EntityManager

    /** The device: holds the passkeys it created, for the iOS / web origin. */
    private val device = FakePasskeys(origin = "https://alirezaiyan.com")

    private fun newUser(): User =
        userRepository.save(User(email = "passkey-${System.nanoTime()}@example.com", name = "Pat"))

    private fun json(value: Any): String = jsonMapper.writeValueAsString(value)

    private fun createPasskey(userId: Long): PasskeyDto {
        val options = passkeyService.registrationOptions(userId)
        val response = runBlocking { device.register(json(options)).getOrThrow().json }
        return passkeyService.register(userId, response)
    }

    private fun signInResponse(): String =
        runBlocking { device.signIn(json(passkeyService.signInOptions())).getOrThrow().json }

    @Test
    fun `a created passkey signs its owner in and records the use`() {
        val user = newUser()
        val passkey = createPasskey(user.requireId())

        val auth = authService.authenticateWithPasskey(signInResponse(), platform = "ios")

        assertEquals(user.requireId(), auth.user.id)
        assertTrue(auth.accessToken.isNotBlank() && auth.refreshToken.isNotBlank())
        val listed = passkeyService.list(user.requireId()).single()
        assertEquals(passkey.id, listed.id)
        assertNotNull(listed.lastUsedAt)
    }

    @Test
    fun `a sign-in response can't be replayed`() {
        val user = newUser()
        createPasskey(user.requireId())
        val response = signInResponse()
        authService.authenticateWithPasskey(response)

        assertThrows<AuthRejectedException> { authService.authenticateWithPasskey(response) }
    }

    @Test
    fun `a creation challenge issued to one user can't register a passkey for another`() {
        val owner = newUser()
        val other = newUser()
        val response = runBlocking {
            device.register(json(passkeyService.registrationOptions(owner.requireId()))).getOrThrow().json
        }

        assertThrows<IllegalArgumentException> { passkeyService.register(other.requireId(), response) }
        assertTrue(passkeyService.list(other.requireId()).isEmpty())
    }

    @Test
    fun `creation options reuse the user handle and exclude passkeys the user already has`() {
        val user = newUser()
        val first = passkeyService.registrationOptions(user.requireId())
        val created = passkeyService.register(
            user.requireId(),
            runBlocking { device.register(json(first)).getOrThrow().json },
        )

        val next = passkeyService.registrationOptions(user.requireId())

        assertEquals(first.user.id, next.user.id)
        assertEquals(1, next.excludeCredentials.size)
        assertEquals(created.id, passkeyService.list(user.requireId()).single().id)
    }

    @Test
    fun `a deleted passkey no longer signs in`() {
        val user = newUser()
        val passkey = createPasskey(user.requireId())
        passkeyService.delete(user.requireId(), passkey.id)
        em.flush()

        assertThrows<AuthRejectedException> { authService.authenticateWithPasskey(signInResponse()) }
    }

    @Test
    fun `a user can't delete another user's passkey`() {
        val owner = newUser()
        val passkey = createPasskey(owner.requireId())

        assertThrows<NoSuchElementException> { passkeyService.delete(newUser().requireId(), passkey.id) }
        assertEquals(1, passkeyService.list(owner.requireId()).size)
    }

    @Test
    fun `a passkey doesn't sign in to a deactivated account`() {
        val user = newUser()
        createPasskey(user.requireId())
        user.active = false
        userRepository.save(user)

        assertThrows<AuthRejectedException> { authService.authenticateWithPasskey(signInResponse()) }
    }

    @Test
    fun `a malformed response is rejected`() {
        assertThrows<AuthRejectedException> { authService.authenticateWithPasskey("""{"id":"x"}""") }
        assertThrows<IllegalArgumentException> { passkeyService.register(newUser().requireId(), "not json") }
    }
}
