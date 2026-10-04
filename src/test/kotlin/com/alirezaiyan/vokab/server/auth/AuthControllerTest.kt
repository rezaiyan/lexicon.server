package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.UserDto
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.shared.ControllerTestSecurityConfig
import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.shared.AuthUser
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ControllerTestSecurityConfig::class)
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var authService: AuthService

    @MockitoBean
    private lateinit var tokenService: TokenService

    @MockitoBean
    private lateinit var accountDeletionService: AccountDeletionService

    private val mockUser = User(
        id = 1L,
        email = "test@example.com",
        name = "Test User",
        currentStreak = 0,
        longestStreak = 0,
        active = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    private val auth = UsernamePasswordAuthenticationToken(AuthUser(mockUser.requireId()), null, emptyList())

    // ── POST /api/v1/auth/google ───────────────────────────────────────────────

    @Test
    fun `POST google should return 200 with auth response when credentials are valid`() {
        val authResponse = createAuthResponse()
        `when`(authService.authenticateWithGoogle("valid-google-token", null, null, "127.0.0.1")).thenReturn(authResponse)

        mockMvc.perform(
            post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"valid-google-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-tok"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-tok"))
    }

    @Test
    fun `POST google should return 401 when the token is rejected`() {
        `when`(authService.authenticateWithGoogle("bad-token", null, null, "127.0.0.1"))
            .thenThrow(AuthRejectedException("Invalid Firebase ID token"))

        mockMvc.perform(
            post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"bad-token"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Invalid Firebase ID token"))
    }

    @Test
    fun `POST google should return 500 without leaking details when the server fails`() {
        `when`(authService.authenticateWithGoogle("bad-token", null, null, "127.0.0.1"))
            .thenThrow(IllegalStateException("db password=secret"))

        mockMvc.perform(
            post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"bad-token"}""")
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
    }

    @Test
    fun `POST google should return 400 when request body is missing idToken`() {
        mockMvc.perform(
            post("/api/v1/auth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":""}""")
        )
            .andExpect(status().isBadRequest)
    }

    // ── POST /api/v1/auth/apple ────────────────────────────────────────────────

    @Test
    fun `POST apple should return 200 with auth response when credentials are valid`() {
        val authResponse = createAuthResponse()
        `when`(authService.authenticateWithApple("valid-apple-token", null, null, null, null, "127.0.0.1")).thenReturn(authResponse)

        mockMvc.perform(
            post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"valid-apple-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-tok"))
    }

    @Test
    fun `POST apple should return 200 with full name when provided`() {
        val authResponse = createAuthResponse()
        `when`(authService.authenticateWithApple("valid-apple-token", "Jane Doe", "apple-uid-123", null, null, "127.0.0.1"))
            .thenReturn(authResponse)

        mockMvc.perform(
            post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"idToken":"valid-apple-token","fullName":"Jane Doe","appleUserId":"apple-uid-123"}"""
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `POST apple should return 401 when the token is rejected`() {
        `when`(authService.authenticateWithApple("bad-apple-token", null, null, null, null, "127.0.0.1"))
            .thenThrow(AuthRejectedException("Invalid Apple ID token"))

        mockMvc.perform(
            post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"bad-apple-token"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Invalid Apple ID token"))
    }

    @Test
    fun `POST apple should return 500 without leaking details when the server fails`() {
        `when`(authService.authenticateWithApple("bad-apple-token", null, null, null, null, "127.0.0.1"))
            .thenThrow(IllegalStateException("db password=secret"))

        mockMvc.perform(
            post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":"bad-apple-token"}""")
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
    }

    @Test
    fun `POST apple should return 400 when idToken is blank`() {
        mockMvc.perform(
            post("/api/v1/auth/apple")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"idToken":""}""")
        )
            .andExpect(status().isBadRequest)
    }

    // ── POST /api/v1/auth/ci-token ─────────────────────────────────────────────

    @Test
    fun `POST ci-token should return 404 when CI auth is disabled`() {
        // ControllerTestSecurityConfig provides AppProperties with ciAuth.enabled = false by default
        mockMvc.perform(
            post("/api/v1/auth/ci-token")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.success").value(false))
    }

    // ── POST /api/v1/auth/refresh ──────────────────────────────────────────────

    @Test
    fun `POST refresh should return 200 with new auth response when token is valid`() {
        val authResponse = createAuthResponse()
        `when`(tokenService.rotate("valid-refresh-token")).thenReturn(authResponse)

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"valid-refresh-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-tok"))
    }

    @Test
    fun `POST refresh should return 401 when the token is rejected`() {
        `when`(tokenService.rotate("expired-refresh-token"))
            .thenThrow(AuthRejectedException("Refresh token has expired"))

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"expired-refresh-token"}""")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.message").value("Refresh token has expired"))
    }

    @Test
    fun `POST refresh should return 500 without leaking details when the server fails`() {
        `when`(tokenService.rotate("expired-refresh-token"))
            .thenThrow(IllegalStateException("db password=secret"))

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"expired-refresh-token"}""")
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
    }

    @Test
    fun `POST refresh should return 400 when refreshToken is blank`() {
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":""}""")
        )
            .andExpect(status().isBadRequest)
    }

    // ── POST /api/v1/auth/logout ───────────────────────────────────────────────

    @Test
    fun `POST logout should return 200 when authenticated and token is valid`() {
        doNothing().`when`(tokenService).logout(1L, "my-refresh-token")

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"my-refresh-token"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `POST logout should return 500 when service throws`() {
        doThrow(RuntimeException("Token not found")).`when`(tokenService).logout(1L, "unknown-token")

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"unknown-token"}""")
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST logout should return 4xx when not authenticated`() {
        mockMvc.perform(
            post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"refreshToken":"some-token"}""")
        )
            .andExpect(status().is4xxClientError)
    }

    // ── POST /api/v1/auth/logout-all ──────────────────────────────────────────

    @Test
    fun `POST logout-all should return 200 when authenticated`() {
        doNothing().`when`(tokenService).logoutAll(1L)

        mockMvc.perform(
            post("/api/v1/auth/logout-all")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `POST logout-all should return 500 when service throws`() {
        doThrow(RuntimeException("Logout all failed")).`when`(tokenService).logoutAll(1L)

        mockMvc.perform(
            post("/api/v1/auth/logout-all")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `POST logout-all should return 4xx when not authenticated`() {
        mockMvc.perform(
            post("/api/v1/auth/logout-all")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().is4xxClientError)
    }

    // ── DELETE /api/v1/auth/delete-account ────────────────────────────────────

    @Test
    fun `DELETE delete-account should return 200 when authenticated`() {
        doNothing().`when`(accountDeletionService).deleteAccount(1L)

        mockMvc.perform(
            delete("/api/v1/auth/delete-account")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
    }

    @Test
    fun `DELETE delete-account should return 500 when service throws`() {
        doThrow(RuntimeException("Deletion failed")).`when`(accountDeletionService).deleteAccount(1L)

        mockMvc.perform(
            delete("/api/v1/auth/delete-account")
                .with(authentication(auth))
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.success").value(false))
    }

    @Test
    fun `DELETE delete-account should return 4xx when not authenticated`() {
        mockMvc.perform(
            delete("/api/v1/auth/delete-account")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().is4xxClientError)
    }

    // ── factory functions ──────────────────────────────────────────────────────

    private fun createAuthResponse(
        accessToken: String = "access-tok",
        refreshToken: String = "refresh-tok",
        expiresIn: Long = 3600L,
    ): AuthResponse = AuthResponse(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresIn = expiresIn,
        user = UserDto(
            id = 1L,
            email = "test@example.com",
            name = "Test",
            subscriptionStatus = SubscriptionStatus.FREE,
            subscriptionExpiresAt = null,
        ),
    )
}
