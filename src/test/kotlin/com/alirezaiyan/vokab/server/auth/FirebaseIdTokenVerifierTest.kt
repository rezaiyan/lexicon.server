package com.alirezaiyan.vokab.server.auth

import com.google.firebase.ErrorCode
import com.google.firebase.auth.AuthErrorCode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseToken
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FirebaseIdTokenVerifierTest {

    private val firebaseAuth = mockk<FirebaseAuth>()
    private val verifier = FirebaseIdTokenVerifier()

    @BeforeEach
    fun setUp() {
        mockkStatic(FirebaseAuth::class)
        every { FirebaseAuth.getInstance() } returns firebaseAuth
    }

    @AfterEach
    fun tearDown() = unmockkStatic(FirebaseAuth::class)

    private fun authException(authErrorCode: AuthErrorCode?, errorCode: ErrorCode): FirebaseAuthException =
        mockk(relaxed = true) {
            every { this@mockk.authErrorCode } returns authErrorCode
            every { this@mockk.errorCode } returns errorCode
        }

    @Test
    fun `verify returns claims for a valid token`() {
        val token = mockk<FirebaseToken> {
            every { uid } returns "uid-1"
            every { email } returns "a@example.com"
            every { name } returns "A"
        }
        every { firebaseAuth.verifyIdToken("good") } returns token

        assertEquals(FirebaseIdClaims(uid = "uid-1", email = "a@example.com", name = "A"), verifier.verify("good"))
    }

    @Test
    fun `verify returns null for an expired token`() {
        every { firebaseAuth.verifyIdToken("expired") } throws
            authException(AuthErrorCode.EXPIRED_ID_TOKEN, ErrorCode.INVALID_ARGUMENT)

        assertNull(verifier.verify("expired"))
    }

    @Test
    fun `verify returns null for a malformed token`() {
        every { firebaseAuth.verifyIdToken("") } throws IllegalArgumentException("ID token must not be null or empty")

        assertNull(verifier.verify(""))
    }

    @Test
    fun `verify throws when Firebase signing certificates cannot be fetched`() {
        val outage = authException(AuthErrorCode.CERTIFICATE_FETCH_FAILED, ErrorCode.UNKNOWN)
        every { firebaseAuth.verifyIdToken("token") } throws outage

        assertThrows<FirebaseAuthException> { verifier.verify("token") }
    }

    @Test
    fun `verify throws when Firebase is unavailable`() {
        every { firebaseAuth.verifyIdToken("token") } throws authException(null, ErrorCode.UNAVAILABLE)

        assertThrows<FirebaseAuthException> { verifier.verify("token") }
    }

    @Test
    fun `verify throws when Firebase is not initialised`() {
        every { FirebaseAuth.getInstance() } throws IllegalStateException("FirebaseApp not initialized")

        assertThrows<IllegalStateException> { verifier.verify("token") }
    }
}
