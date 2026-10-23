package com.alirezaiyan.vokab.server.shared

import com.google.firebase.FirebaseApp
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Whether Firebase (push + token verification) initialized. UNKNOWN rather than DOWN when it isn't
 * configured, so local dev and tests stay healthy; not part of readiness.
 */
@Component
class FirebaseHealthIndicator(private val firebaseApp: FirebaseApp?) : HealthIndicator {

    override fun health(): Health =
        if (firebaseApp == null) {
            Health.unknown().withDetail("reason", "Firebase credentials not configured").build()
        } else {
            Health.up().withDetail("app", firebaseApp.name).build()
        }
}
