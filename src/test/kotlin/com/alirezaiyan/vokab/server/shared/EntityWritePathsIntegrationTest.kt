package com.alirezaiyan.vokab.server.shared

import com.alirezaiyan.vokab.server.admin.AppConfigRepository
import com.alirezaiyan.vokab.server.ai.DailyInsightRepository
import com.alirezaiyan.vokab.server.email.EmailLogRepository
import com.alirezaiyan.vokab.server.email.EmailSubscriptionRepository
import com.alirezaiyan.vokab.server.subscription.ProcessedWebhookEventRepository
import com.alirezaiyan.vokab.server.auth.RefreshTokenRepository
import com.alirezaiyan.vokab.server.subscription.SubscriptionRepository
import com.alirezaiyan.vokab.server.words.TagRepository
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.words.WordRepository
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import com.alirezaiyan.vokab.server.admin.AppConfig
import com.alirezaiyan.vokab.server.ai.DailyInsight
import com.alirezaiyan.vokab.server.email.EmailLog
import com.alirezaiyan.vokab.server.email.EmailStatus
import com.alirezaiyan.vokab.server.email.EmailSubscription
import com.alirezaiyan.vokab.server.subscription.ProcessedWebhookEvent
import com.alirezaiyan.vokab.server.auth.RefreshToken
import com.alirezaiyan.vokab.server.subscription.Subscription
import com.alirezaiyan.vokab.server.user.SubscriptionStatus
import com.alirezaiyan.vokab.server.words.Tag
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.words.Word

/**
 * Entities are mutated in place and saved (no data-class copies). Each test writes, clears the
 * persistence context and reloads from the database, so it checks the column mapping itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EntityWritePathsIntegrationTest {

    @Autowired lateinit var em: EntityManager
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var refreshTokenRepository: RefreshTokenRepository
    @Autowired lateinit var appConfigRepository: AppConfigRepository
    @Autowired lateinit var emailSubscriptionRepository: EmailSubscriptionRepository
    @Autowired lateinit var emailLogRepository: EmailLogRepository
    @Autowired lateinit var dailyInsightRepository: DailyInsightRepository
    @Autowired lateinit var subscriptionRepository: SubscriptionRepository
    @Autowired lateinit var wordRepository: WordRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var processedWebhookEventRepository: ProcessedWebhookEventRepository

    private val now: Instant = Instant.parse("2026-06-17T10:00:00Z")

    private fun newUser(): User =
        userRepository.saveAndFlush(User(email = "write-${System.nanoTime()}@example.com", name = "Writer"))

    private fun flushAndClear() {
        em.flush()
        em.clear()
    }

    @Test
    fun `user profile, login and streak fields persist when mutated and saved`() {
        val user = newUser()
        val id = user.id!!

        user.name = "Renamed"
        user.displayAlias = "alias_1"
        user.profileImageUrl = "https://cdn.example.com/a.png"
        user.currentStreak = 4
        user.longestStreak = 9
        user.lastLoginAt = now
        user.updatedAt = now
        user.lastLoginCountry = "DE"
        user.signupCountry = "NL"
        user.firstWordAddedAt = now
        user.firstReviewAt = now
        user.googleId = "g-$id"
        user.appleId = "a-$id"
        userRepository.save(user)
        flushAndClear()

        val reloaded = userRepository.findById(id).orElseThrow()
        assertNotSame(user, reloaded)
        assertEquals(user, reloaded) // id equality across persistence contexts
        assertEquals("Renamed", reloaded.name)
        assertEquals("alias_1", reloaded.displayAlias)
        assertEquals("https://cdn.example.com/a.png", reloaded.profileImageUrl)
        assertEquals(4, reloaded.currentStreak)
        assertEquals(9, reloaded.longestStreak)
        assertEquals(now, reloaded.lastLoginAt)
        assertEquals("DE", reloaded.lastLoginCountry)
        assertEquals("NL", reloaded.signupCountry)
        assertEquals(now, reloaded.firstWordAddedAt)
        assertEquals(now, reloaded.firstReviewAt)
        assertEquals("g-$id", reloaded.googleId)
        assertEquals("a-$id", reloaded.appleId)
    }

    @Test
    fun `user deactivation and email change persist`() {
        val user = newUser()
        val newEmail = "relay-${System.nanoTime()}@privaterelay.appleid.com"

        user.active = false
        user.email = newEmail
        userRepository.save(user)
        flushAndClear()

        val reloaded = userRepository.findById(user.id!!).orElseThrow()
        assertFalse(reloaded.active)
        assertEquals(newEmail, reloaded.email)
    }

    @Test
    fun `mirrored store-owned columns are not written by a whole-user save`() {
        val user = newUser()

        user.mirrorSubscription(SubscriptionStatus.ACTIVE, now)
        user.mirrorGrant(now, "manual")
        user.mirrorRevenueCatUserId("rc-${user.id}")
        userRepository.save(user)
        flushAndClear()

        val reloaded = userRepository.findById(user.id!!).orElseThrow()
        assertEquals(SubscriptionStatus.FREE, reloaded.subscriptionStatus)
        assertEquals(null, reloaded.premiumGrantReason)
        assertEquals(null, reloaded.revenueCatUserId)
    }

    @Test
    fun `mirrored store-owned columns are written on the initial insert`() {
        val user = User(email = "insert-${System.nanoTime()}@example.com", name = "New")
        user.mirrorGrant(now, "test_email")
        user.mirrorSubscription(SubscriptionStatus.TRIAL, now)
        val saved = userRepository.save(user)
        flushAndClear()

        val reloaded = userRepository.findById(saved.id!!).orElseThrow()
        assertEquals("test_email", reloaded.premiumGrantReason)
        assertEquals(SubscriptionStatus.TRIAL, reloaded.subscriptionStatus)
    }

    @Test
    fun `refresh token grace expiry persists`() {
        val user = newUser()
        val token = refreshTokenRepository.save(
            RefreshToken(tokenHash = "hash-${System.nanoTime()}", user = user, expiresAt = now.plusSeconds(3600))
        )

        token.expiresAt = now.plusSeconds(30)
        refreshTokenRepository.save(token)
        flushAndClear()

        assertEquals(now.plusSeconds(30), refreshTokenRepository.findById(token.id!!).orElseThrow().expiresAt)
    }

    @Test
    fun `app config value update persists`() {
        val config = appConfigRepository.save(AppConfig(namespace = "it-${System.nanoTime()}", key = "k", value = "old"))

        config.value = "new"
        config.updatedAt = now
        appConfigRepository.save(config)
        flushAndClear()

        val reloaded = appConfigRepository.findById(config.id!!).orElseThrow()
        assertEquals("new", reloaded.value)
        assertEquals(now, reloaded.updatedAt)
    }

    @Test
    fun `email subscription toggle persists`() {
        val subscription = emailSubscriptionRepository.save(
            EmailSubscription(userId = newUser().id!!, category = "it-${System.nanoTime()}", subscribed = true)
        )

        subscription.subscribed = false
        subscription.updatedAt = now
        emailSubscriptionRepository.save(subscription)
        flushAndClear()

        assertFalse(emailSubscriptionRepository.findById(subscription.id!!).orElseThrow().subscribed)
    }

    @Test
    fun `email log delivery result persists`() {
        val log = emailLogRepository.save(
            EmailLog(recipientEmail = "r@example.com", category = "c", templateId = "t", subject = "s")
        )

        log.status = EmailStatus.FAILED
        log.provider = "resend"
        log.providerId = "msg_1"
        log.sentAt = now
        log.errorMessage = "bounced"
        emailLogRepository.save(log)
        flushAndClear()

        val reloaded = emailLogRepository.findById(log.id!!).orElseThrow()
        assertEquals(EmailStatus.FAILED, reloaded.status)
        assertEquals("resend", reloaded.provider)
        assertEquals("msg_1", reloaded.providerId)
        assertEquals(now, reloaded.sentAt)
        assertEquals("bounced", reloaded.errorMessage)
    }

    @Test
    fun `daily insight push flag persists`() {
        val insight = dailyInsightRepository.save(
            DailyInsight(user = newUser(), insightText = "Nice work", generatedAt = now, date = "2026-06-17")
        )

        insight.sentViaPush = true
        insight.pushSentAt = now
        dailyInsightRepository.save(insight)
        flushAndClear()

        val reloaded = dailyInsightRepository.findById(insight.id!!).orElseThrow()
        assertTrue(reloaded.sentViaPush)
        assertEquals(now, reloaded.pushSentAt)
    }

    @Test
    fun `subscription state changes persist`() {
        val subscription = subscriptionRepository.save(
            Subscription(
                user = newUser(),
                revenueCatSubscriptionId = "tx-${System.nanoTime()}",
                productId = "monthly",
                status = SubscriptionStatus.ACTIVE,
                startedAt = now,
            )
        )

        subscription.productId = "yearly"
        subscription.status = SubscriptionStatus.CANCELLED
        subscription.expiresAt = now.plus(30, ChronoUnit.DAYS)
        subscription.cancelledAt = now
        subscription.isTrial = true
        subscription.autoRenew = false
        subscription.updatedAt = now
        subscriptionRepository.save(subscription)
        flushAndClear()

        val reloaded = subscriptionRepository.findById(subscription.id!!).orElseThrow()
        assertEquals("yearly", reloaded.productId)
        assertEquals(SubscriptionStatus.CANCELLED, reloaded.status)
        assertEquals(now.plus(30, ChronoUnit.DAYS), reloaded.expiresAt)
        assertEquals(now, reloaded.cancelledAt)
        assertTrue(reloaded.isTrial)
        assertFalse(reloaded.autoRenew)
    }

    @Test
    fun `tag added to an unsaved word stays in the set after both are persisted`() {
        val user = newUser()
        val tag = tagRepository.save(Tag(user = user, name = "verbs-${System.nanoTime()}"))
        val word = Word(user = user, originalWord = "laufen", translation = "run")
        word.tags.add(tag)

        val saved = wordRepository.save(word)
        assertTrue(tag in saved.tags)
        flushAndClear()

        val reloaded = wordRepository.findById(saved.id!!).orElseThrow()
        assertEquals(setOf(tag), reloaded.tags.toSet())
    }

    @Test
    fun `processed webhook event exposes its event id as entity id`() {
        val eventId = "evt-${System.nanoTime()}"
        processedWebhookEventRepository.save(ProcessedWebhookEvent(eventId = eventId, eventType = "RENEWAL"))
        flushAndClear()

        val reloaded = processedWebhookEventRepository.findById(eventId).orElseThrow()
        assertEquals(eventId, reloaded.id)
    }
}
