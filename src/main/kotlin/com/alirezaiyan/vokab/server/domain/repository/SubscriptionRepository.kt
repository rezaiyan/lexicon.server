package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.Subscription
import com.alirezaiyan.vokab.server.domain.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface SubscriptionRepository : JpaRepository<Subscription, Long> {
    fun findByRevenueCatSubscriptionId(revenueCatSubscriptionId: String): Optional<Subscription>
    fun findByUser(user: User): List<Subscription>
}

