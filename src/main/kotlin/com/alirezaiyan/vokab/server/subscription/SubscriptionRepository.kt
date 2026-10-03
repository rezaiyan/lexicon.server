package com.alirezaiyan.vokab.server.subscription

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findByRevenueCatSubscriptionId(revenueCatSubscriptionId: String): Optional<Subscription>
    fun findByUser(user: User): List<Subscription>
}

