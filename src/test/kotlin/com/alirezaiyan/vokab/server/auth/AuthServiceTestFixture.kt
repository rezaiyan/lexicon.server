package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.TEST_NOW
import com.alirezaiyan.vokab.server.fixedClock
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.CiAuthConfig
import com.alirezaiyan.vokab.server.shared.JwtConfig
import com.alirezaiyan.vokab.server.shared.SecurityConfig
import com.alirezaiyan.vokab.server.shared.DomainEventPublisher
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserPlatformRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.notification.PushNotificationService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import java.util.Optional
import com.alirezaiyan.vokab.server.admin.AppConfigService
import com.alirezaiyan.vokab.server.user.GeoLocationService
import com.alirezaiyan.vokab.server.subscription.RevenueCatClient
import com.alirezaiyan.vokab.server.user.UserDataPurger

/** Mocks and real collaborators shared by the auth service tests. */
abstract class AuthServiceTestFixture {

    protected lateinit var userRepository: UserRepository
    protected lateinit var refreshTokenRepository: RefreshTokenRepository
    protected lateinit var jwtTokenProvider: RS256JwtTokenProvider
    protected lateinit var refreshTokenHashService: RefreshTokenHashService
    protected lateinit var appleIdTokenVerifier: AppleIdTokenVerifier
    protected lateinit var firebaseIdTokenVerifier: FirebaseIdTokenVerifier
    protected lateinit var revenueCatClient: RevenueCatClient
    protected lateinit var userPlatformRepository: UserPlatformRepository
    protected lateinit var userDataPurger: UserDataPurger
    protected lateinit var pushNotificationService: PushNotificationService
    protected lateinit var appProperties: AppProperties
    protected lateinit var auditLogService: AuditLogService
    protected lateinit var appConfigService: AppConfigService
    protected lateinit var domainEventPublisher: DomainEventPublisher
    protected lateinit var geoLocationService: GeoLocationService

    protected lateinit var authService: AuthService
    protected lateinit var tokenService: TokenService
    protected lateinit var accountDeletionService: AccountDeletionService

    @BeforeEach
    fun setUp() {
        userRepository = mockk()
        refreshTokenRepository = mockk()
        jwtTokenProvider = mockk()
        refreshTokenHashService = mockk()
        appleIdTokenVerifier = mockk()
        firebaseIdTokenVerifier = mockk()
        revenueCatClient = mockk(relaxed = true)
        userPlatformRepository = mockk(relaxed = true)
        userDataPurger = mockk()
        pushNotificationService = mockk()
        auditLogService = mockk(relaxed = true)
        appConfigService = mockk()
        domainEventPublisher = mockk(relaxed = true)
        every { appConfigService.getTestEmails() } returns emptySet()
        geoLocationService = mockk(relaxed = true)

        appProperties = AppProperties(
            jwt = JwtConfig(refreshExpirationMs = 7_776_000_000L, refreshTokenGracePeriodMs = 30_000L),
            ciAuth = CiAuthConfig(enabled = true, secret = "ci-secret", testEmail = "ci@test.vokab.dev"),
            security = SecurityConfig(testEmails = "")
        )

        authService = buildServiceWith(appProperties)
        tokenService = buildTokenService(appProperties)
        accountDeletionService = AccountDeletionService(
            userRepository = userRepository,
            userDataPurger = userDataPurger,
            revenueCatClient = revenueCatClient,
            deviceDataWipe = DeviceDataWipe(pushNotificationService),
            auditLogService = auditLogService,
            userAccessCache = mockk(relaxed = true),
        )
        every { userRepository.updateSubscription(any(), any(), any(), any()) } returns 1
        every { userRepository.updateGrant(any(), any(), any(), any()) } returns 1
    }


    protected fun stubTokenGeneration(user: User) {
        every { jwtTokenProvider.generateAccessToken(user.id!!, user.email, any()) } returns "access-token"
        every { refreshTokenHashService.generateSecureToken(32) } returns "refresh-token"
        every { refreshTokenHashService.createLookupHash("refresh-token") } returns "lookup-hash"
        every { refreshTokenRepository.save(any()) } returns mockk()
        every { jwtTokenProvider.getExpirationTime() } returns 86400L
    }

    protected fun stubDeleteAccount(
        userId: Long,
        user: User,
        pushThrows: Boolean = false
    ) {
        every { userRepository.findById(userId) } returns Optional.of(user)

        if (pushThrows) {
            every {
                pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
            } throws RuntimeException("push failed")
        } else {
            every {
                pushNotificationService.sendNotificationToUser(any(), any(), any(), any(), any())
            } returns emptyList()
        }

        every { userDataPurger.purge(userId) } returns mapOf("words" to 0)
        justRun { userRepository.delete(user) }
    }

    protected fun buildServiceWith(properties: AppProperties): AuthService = AuthService(
        firebaseIdTokenVerifier = firebaseIdTokenVerifier,
        appleIdTokenVerifier = appleIdTokenVerifier,
        userProvisioning = UserProvisioning(
            userRepository = userRepository,
            userPlatformRepository = userPlatformRepository,
            testAccessPolicy = TestAccessPolicy(userRepository, appConfigService, fixedClock()),
            clock = fixedClock(),
        ),
        tokenService = buildTokenService(properties),
        appProperties = properties,
        auditLogService = auditLogService,
        domainEventPublisher = domainEventPublisher,
        geoLocationService = geoLocationService,
    )

    private fun buildTokenService(properties: AppProperties) = TokenService(
        refreshTokenRepository = refreshTokenRepository,
        jwtTokenProvider = jwtTokenProvider,
        refreshTokenHashService = refreshTokenHashService,
        userRepository = userRepository,
        appProperties = properties,
        auditLogService = auditLogService,
        deviceDataWipe = DeviceDataWipe(pushNotificationService),
        clock = fixedClock(),
    )

    protected fun createUser(
        id: Long? = 1L,
        email: String = "test@example.com",
        name: String = "Test User",
        googleId: String? = null,
        appleId: String? = null,
        subscriptionStatus: SubscriptionStatus = SubscriptionStatus.FREE,
        currentStreak: Int = 0
    ): User = User(
        id = id,
        email = email,
        name = name,
        googleId = googleId,
        appleId = appleId,
        subscriptionStatus = subscriptionStatus,
        currentStreak = currentStreak,
        longestStreak = 0
    )

    protected fun createRefreshToken(
        id: Long? = 1L,
        user: User,
        tokenHash: String = "hash",
        expiresAt: Instant = TEST_NOW.plusSeconds(3600),
        revoked: Boolean = false
    ): RefreshToken = RefreshToken(
        id = id,
        tokenHash = tokenHash,
        user = user,
        expiresAt = expiresAt,
        revoked = revoked
    )
}
