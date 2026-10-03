package com.alirezaiyan.vokab.server.email

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Service
class EmailSubscriptionService(
    private val emailSubscriptionRepository: EmailSubscriptionRepository,
    private val clock: Clock
) {

    @Transactional(readOnly = true)
    fun getPreferences(userId: Long): List<EmailSubscription> {
        return emailSubscriptionRepository.findByUserId(userId)
    }

    @Transactional
    fun subscribe(userId: Long, category: String): EmailSubscription {
        val existing = emailSubscriptionRepository.findByUserIdAndCategory(userId, category)
        return if (existing != null) {
            existing.subscribed = true
            existing.updatedAt = Instant.now(clock)
            emailSubscriptionRepository.save(existing)
        } else {
            emailSubscriptionRepository.save(
                EmailSubscription(userId = userId, category = category, subscribed = true)
            )
        }.also { logger.info { "User $userId subscribed to $category" } }
    }

    @Transactional
    fun unsubscribe(userId: Long, category: String): EmailSubscription {
        val existing = emailSubscriptionRepository.findByUserIdAndCategory(userId, category)
        return if (existing != null) {
            existing.subscribed = false
            existing.updatedAt = Instant.now(clock)
            emailSubscriptionRepository.save(existing)
        } else {
            emailSubscriptionRepository.save(
                EmailSubscription(userId = userId, category = category, subscribed = false)
            )
        }.also { logger.info { "User $userId unsubscribed from $category" } }
    }

    /**
     * Bootstrap default subscriptions for a new user.
     * Call this during user registration.
     */
    @Transactional
    fun initDefaults(userId: Long, categories: List<String> = DEFAULT_CATEGORIES) {
        categories.forEach { category ->
            if (emailSubscriptionRepository.findByUserIdAndCategory(userId, category) == null) {
                emailSubscriptionRepository.save(
                    EmailSubscription(userId = userId, category = category, subscribed = true)
                )
            }
        }
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf("newsletter", "product_updates", "weekly_digest")
    }
}
