package com.alirezaiyan.vokab.server.auth

import java.time.Duration
import com.alirezaiyan.vokab.server.MutableClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.JwtConfig
import com.alirezaiyan.vokab.server.shared.SecurityConfig
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserAccessView
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.admin.AppConfigService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import jakarta.servlet.http.HttpServletResponse
import com.alirezaiyan.vokab.server.shared.AuthUser

class JwtAuthenticationFilterTest {

    private val clock = MutableClock()

    private lateinit var jwtTokenProvider: RS256JwtTokenProvider
    private lateinit var userRepository: UserRepository
    private lateinit var appProperties: AppProperties
    private lateinit var appConfigService: AppConfigService
    private lateinit var filter: JwtAuthenticationFilter

    private lateinit var testKeyPair: KeyPair

    @BeforeEach
    fun setUp() {
        testKeyPair = generateTestKeyPair()
        appProperties = createAppProperties(testKeyPair)
        jwtTokenProvider = RS256JwtTokenProvider(appProperties, clock)
        userRepository = mockk()
        appConfigService = mockk()
        every { appConfigService.getTestEmails() } returns emptySet()
        filter = JwtAuthenticationFilter(jwtTokenProvider, appProperties, UserAccessCache(userRepository, appConfigService, clock))
        SecurityContextHolder.clearContext()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
    }

    // ── No Authorization header ───────────────────────────────────────────────

    @Test
    fun `should return 401 when no Authorization header is present`() {
        // Arrange
        val request = MockHttpServletRequest().apply { requestURI = "/api/v1/words" }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should not call filter chain when no Authorization header is present`() {
        // Arrange
        val request = MockHttpServletRequest().apply { requestURI = "/api/v1/words" }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    // ── Authorization header without "Bearer " prefix ─────────────────────────

    @Test
    fun `should return 401 when Authorization header does not start with Bearer`() {
        // Arrange
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Basic dXNlcjpwYXNz")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should not call filter chain when Authorization header lacks Bearer prefix`() {
        // Arrange
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Token sometoken")
        }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    // ── Valid JWT ─────────────────────────────────────────────────────────────

    @Test
    fun `should set SecurityContext authentication when valid JWT and active user found`() {
        // Arrange
        val user = createUser(id = 1L, active = true)
        val token = jwtTokenProvider.generateAccessToken(1L, user.email)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        every { userRepository.findAccessById(1L) } returns access(user)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertNotNull(SecurityContextHolder.getContext().authentication)
        assertEquals(AuthUser(1L), SecurityContextHolder.getContext().authentication.principal)
    }

    @Test
    fun `should proceed to filter chain when valid JWT and active user found`() {
        // Arrange
        val user = createUser(id = 2L, active = true)
        val token = jwtTokenProvider.generateAccessToken(2L, user.email)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)
        every { userRepository.findAccessById(2L) } returns access(user)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 1) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `should look up user by ID extracted from token`() {
        // Arrange
        val user = createUser(id = 42L, active = true)
        val token = jwtTokenProvider.generateAccessToken(42L, user.email)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        every { userRepository.findAccessById(42L) } returns access(user)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 1) { userRepository.findAccessById(42L) }
    }

    // ── Invalid or expired JWT ────────────────────────────────────────────────

    @Test
    fun `should return 401 when JWT token is invalid`() {
        // Arrange
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer this.is.not.valid")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should return 401 when JWT is expired`() {
        // Arrange — create a token that expires immediately
        val shortLivedProps = createAppProperties(testKeyPair, expirationMs = 1L)
        val shortLivedProvider = RS256JwtTokenProvider(shortLivedProps, clock)
        val token = shortLivedProvider.generateAccessToken(1L, "test@example.com")
        clock.advance(Duration.ofSeconds(1))

        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should not call filter chain when JWT is invalid`() {
        // Arrange
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer garbage.token.here")
        }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `should not query user repository when JWT validation fails`() {
        // Arrange
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer not.a.valid.jwt")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 0) { userRepository.findAccessById(any()) }
    }

    // ── Inactive user ─────────────────────────────────────────────────────────

    @Test
    fun `should return 403 when user is found but inactive`() {
        // Arrange
        val user = createUser(id = 5L, active = false)
        val token = jwtTokenProvider.generateAccessToken(5L, user.email)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        every { userRepository.findAccessById(5L) } returns access(user)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `should not call filter chain when user is inactive`() {
        // Arrange
        val user = createUser(id = 6L, active = false)
        val token = jwtTokenProvider.generateAccessToken(6L, user.email)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)
        every { userRepository.findAccessById(6L) } returns access(user)

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    // ── User not found in repository ──────────────────────────────────────────

    @Test
    fun `should return 403 when valid token references non-existent user`() {
        // Arrange
        val token = jwtTokenProvider.generateAccessToken(99L, "ghost@example.com")
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        every { userRepository.findAccessById(99L) } returns null

        // Act
        filter.doFilter(request, response, chain)

        // Assert
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    // ── Test email bypass (active check) ──────────────────────────────────────

    @Test
    fun `should allow inactive test email user through when email is in testEmails`() {
        // Arrange — configure a test email in appProperties
        val testEmail = "ci@test.example.com"
        val propsWithTestEmail = createAppProperties(testKeyPair, testEmails = testEmail)
        val providerForTest = RS256JwtTokenProvider(propsWithTestEmail, clock)
        val configServiceForTest = mockk<AppConfigService>()
        every { configServiceForTest.getTestEmails() } returns setOf(testEmail)
        val filterForTest = JwtAuthenticationFilter(
            providerForTest, propsWithTestEmail, UserAccessCache(userRepository, configServiceForTest, clock)
        )

        val user = createUser(id = 7L, email = testEmail, active = false)
        val token = providerForTest.generateAccessToken(7L, testEmail)
        val request = MockHttpServletRequest().apply {
            requestURI = "/api/v1/words"
            addHeader("Authorization", "Bearer $token")
        }
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        every { userRepository.findAccessById(7L) } returns access(user)

        // Act
        filterForTest.doFilter(request, response, chain)

        // Assert — chain was called, authentication was set
        assertNotNull(SecurityContextHolder.getContext().authentication)
    }

    // ── excluded paths ────────────────────────────────────────────────────────

    @Test
    fun `should skip filter for auth endpoint`() {
        // Arrange — excluded path, no Authorization header
        val request = MockHttpServletRequest().apply { requestURI = "/api/v1/auth/google" }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert — chain.doFilter was called, meaning doFilterInternal was skipped
        verify(exactly = 1) { chain.doFilter(request, response) }
    }

    @Test
    fun `should not skip filter for protected endpoint`() {
        // Arrange — protected path, no Authorization header
        val request = MockHttpServletRequest().apply { requestURI = "/api/v1/words" }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert — doFilterInternal ran and returned 401 (no token)
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status)
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `should skip filter for health check endpoint`() {
        // Arrange — excluded path
        val request = MockHttpServletRequest().apply { requestURI = "/api/v1/health" }
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>(relaxed = true)

        // Act
        filter.doFilter(request, response, chain)

        // Assert — chain.doFilter was called, meaning doFilterInternal was skipped
        verify(exactly = 1) { chain.doFilter(request, response) }
    }

    // ── factory functions ─────────────────────────────────────────────────────

    private fun createUser(
        id: Long = 1L,
        email: String = "test@example.com",
        active: Boolean = true,
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
    ): User = User(
        id = id,
        email = email,
        name = "Test User",
        active = active,
        subscriptionStatus = subscriptionStatus,
        currentStreak = 0,
        longestStreak = 0,
    )

    private fun access(user: User): UserAccessView = object : UserAccessView {
        override val active = user.active
        override val email = user.email
    }

    private fun generateTestKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.genKeyPair()

    private fun createAppProperties(
        keyPair: KeyPair,
        expirationMs: Long = 86400000L,
        testEmails: String = "",
    ): AppProperties {
        val privateKeyBase64 = Base64.getEncoder().encodeToString(keyPair.private.encoded)
        val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val props = AppProperties()
        props.jwt = JwtConfig(
            expirationMs = expirationMs,
            privateKey = privateKeyBase64,
            publicKey = publicKeyBase64,
            issuer = "vokab-server",
            audience = "vokab-client"
        )
        props.security = SecurityConfig(testEmails = testEmails)
        return props
    }
}
