package com.alirezaiyan.vokab.server.email

import org.springframework.data.jpa.repository.JpaRepository

interface EmailSubscriptionRepository : JpaRepository<EmailSubscription, Long> {
    fun findByUserId(userId: Long): List<EmailSubscription>
    fun findByUserIdAndCategory(userId: Long, category: String): EmailSubscription?
    fun findByCategoryAndSubscribedTrue(category: String): List<EmailSubscription>
}
