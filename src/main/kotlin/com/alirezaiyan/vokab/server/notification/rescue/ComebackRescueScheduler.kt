package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.user.User
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Nightly batch: retires yesterday's unsent plans and plans today's rescues.
 * Invoked by NotificationScheduledTasks — not a scheduler itself.
 */
@Service
class ComebackRescueScheduler(
    private val planner: ComebackRescuePlanner,
    private val repository: ComebackRescueRepository,
    private val clock: Clock,
) {
    fun planAll(users: List<User>) {
        val expired = repository.expireStale(LocalDate.now(clock), RescueStatus.PLANNED, RescueStatus.EXPIRED)
        var planned = 0
        var failed = 0

        for (user in users) {
            runCatching { planner.planIfEligible(user) }
                .onSuccess { if (it != null) planned++ }
                .onFailure { e ->
                    failed++
                    logger.warn(e) { "Comeback rescue planning failed for user=${user.id}" }
                }
        }

        logger.info { "Comeback rescue planning complete — users=${users.size}, planned=$planned, failed=$failed, expired=$expired" }
    }
}
