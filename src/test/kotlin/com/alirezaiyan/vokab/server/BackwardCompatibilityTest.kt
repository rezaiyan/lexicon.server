package com.alirezaiyan.vokab.server

import com.alirezaiyan.vokab.server.user.requireId
import com.alirezaiyan.vokab.server.study.DailyActivity
import com.alirezaiyan.vokab.server.ai.DailyInsight
import com.alirezaiyan.vokab.server.user.Platform
import com.alirezaiyan.vokab.server.notification.PushToken
import com.alirezaiyan.vokab.server.auth.RefreshToken
import com.alirezaiyan.vokab.server.subscription.Subscription
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserSettings
import com.alirezaiyan.vokab.server.words.Word
import com.alirezaiyan.vokab.server.study.DailyActivityRepository
import com.alirezaiyan.vokab.server.ai.DailyInsightRepository
import com.alirezaiyan.vokab.server.notification.PushTokenRepository
import com.alirezaiyan.vokab.server.auth.RefreshTokenRepository
import com.alirezaiyan.vokab.server.subscription.SubscriptionRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.UserSettingsRepository
import com.alirezaiyan.vokab.server.words.WordRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BackwardCompatibilityTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var wordRepository: WordRepository
    @Autowired private lateinit var userSettingsRepository: UserSettingsRepository
    @Autowired private lateinit var dailyInsightRepository: DailyInsightRepository
    @Autowired private lateinit var dailyActivityRepository: DailyActivityRepository
    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired private lateinit var pushTokenRepository: PushTokenRepository
    @Autowired private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Test
    fun `existing user without refresh tokens should persist related entities`() {
        val user = userRepository.save(
            User(
                email = "legacy@example.com",
                name = "Legacy User",
                currentStreak = 5,
                longestStreak = 10
            )
        )
        assertNotNull(user.id)

        val word = wordRepository.save(
            Word(
                user = user,
                originalWord = "hello",
                translation = "hola",
                sourceLanguage = "en",
                targetLanguage = "es"
            )
        )
        assertNotNull(word.id)

        val settings = userSettingsRepository.save(
            UserSettings(
                user = user,
                languageCode = "en",
                themeMode = "AUTO",
                notificationsEnabled = true,
                dailyReminderTime = "18:00"
            )
        )
        assertNotNull(settings.id)

        val retrieved = userRepository.findById(user.requireId()).orElse(null)
        assertNotNull(retrieved)
        assertTrue(retrieved.email == "legacy@example.com")
    }

    @Test
    fun `refresh tokens with metadata should persist`() {
        val user = userRepository.save(
            User(
                email = "token@example.com",
                name = "Token User"
            )
        )

        val refreshToken = refreshTokenRepository.save(
            RefreshToken(
                tokenHash = "test_hash",
                user = user,
                expiresAt = Instant.now().plusSeconds(3600),
            )
        )

        assertNotNull(refreshToken.id)
        val loaded = refreshTokenRepository.findById(refreshToken.id!!).orElse(null)
        assertNotNull(loaded)
        assertTrue(loaded.tokenHash == "test_hash")
    }

    @Test
    fun `subscriptions and insight data persist`() {
        val user = userRepository.save(
            User(
                email = "compat@example.com",
                name = "Compat User",
                subscriptionStatus = SubscriptionStatus.ACTIVE
            )
        )

        val pushToken = pushTokenRepository.save(
            PushToken(
                user = user,
                token = "push-token",
                platform = Platform.ANDROID
            )
        )
        assertNotNull(pushToken.id)

        val insight = dailyInsightRepository.save(
            DailyInsight(
                user = user,
                insightText = "Stay motivated!",
                generatedAt = Instant.now(),
                date = "2025-01-01"
            )
        )
        assertNotNull(insight.id)

        val activity = dailyActivityRepository.save(
            DailyActivity(
                user = user,
                activityDate = LocalDate.now(),
                reviewCount = 3
            )
        )
        assertNotNull(activity.id)

        val subscription = subscriptionRepository.save(
            Subscription(
                user = user,
                productId = "premium_monthly",
                status = SubscriptionStatus.ACTIVE,
                startedAt = Instant.now(),
                expiresAt = Instant.now().plusSeconds(30 * 86400),
                isTrial = false,
                autoRenew = true
            )
        )
        assertNotNull(subscription.id)

        val reloaded = userRepository.findById(user.id!!).orElse(null)
        assertNotNull(reloaded)
        assertTrue(reloaded.subscriptionStatus == SubscriptionStatus.ACTIVE)
    }
}


