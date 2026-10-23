package com.alirezaiyan.vokab.server.mcp

import com.alirezaiyan.vokab.server.auth.FirebaseIdClaims
import com.alirezaiyan.vokab.server.auth.FirebaseIdTokenVerifier
import com.alirezaiyan.vokab.server.auth.UserAccessCache
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class McpOAuthServiceSignInTest {

    private val firebase = mockk<FirebaseIdTokenVerifier>()
    private val userRepository = mockk<UserRepository>()
    private val userAccessCache = mockk<UserAccessCache> { every { isAllowed(any()) } returns true }
    private val service = McpOAuthService(
        clients = mockk(),
        firebase = firebase,
        userRepository = userRepository,
        userAccessCache = userAccessCache,
        appProperties = AppProperties(),
        clock = fixedClock(),
    )

    @Test
    fun `signIn rejects a Firebase token from another sign-in provider`() {
        every { firebase.verify("token") } returns claims(provider = "password")

        assertEquals(McpOAuthService.SignInResult.InvalidToken, service.signIn("token"))
        verify(exactly = 0) { userRepository.findByEmail(any()) }
    }

    @Test
    fun `signIn does not fall back to an unverified email`() {
        every { firebase.verify("token") } returns claims(emailVerified = false)
        every { userRepository.findByGoogleId("g-1") } returns Optional.empty()

        assertEquals(McpOAuthService.SignInResult.NoAccount, service.signIn("token"))
        verify(exactly = 0) { userRepository.findByEmail(any()) }
    }

    @Test
    fun `signIn falls back to a verified email`() {
        every { firebase.verify("token") } returns claims()
        every { userRepository.findByGoogleId("g-1") } returns Optional.empty()
        every { userRepository.findByEmail("learner@example.com") } returns Optional.of(user(id = 4L))

        assertEquals(McpOAuthService.SignInResult.SignedIn(4L), service.signIn("token"))
    }

    private fun claims(emailVerified: Boolean = true, provider: String = FirebaseIdClaims.GOOGLE_SIGN_IN_PROVIDER) =
        FirebaseIdClaims(
            uid = "g-1",
            email = "learner@example.com",
            name = "Learner",
            emailVerified = emailVerified,
            signInProvider = provider,
        )

    private fun user(id: Long) = User(id = id, email = "learner@example.com", name = "Learner")
}
