package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/**
 * Nightly batch: retires yesterday's unsent plans and plans today's rescues for every learner the
 * dispatcher could reach later today. Invoked by NotificationScheduledTasks — not a scheduler itself.
 */
@Service
class ComebackRescueScheduler(
    private val planner: ComebackRescuePlanner,
    private val repository: ComebackRescueRepository,
    private val scheduleRepository: NotificationScheduleRepository,
    private val clock: Clock,
) {
    fun planAll() {
        val expired = repository.expireStale(LocalDate.now(clock), RescueStatus.PLANNED, RescueStatus.EXPIRED)
        val users = scheduleRepository.findRescuePlanningCandidates(MIN_SEND_HOUR).map { it.user }
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

        logger.info {
            "Comeback rescue planning complete — users=${users.size}, planned=$planned, " +
                "failed=$failed, expired=$expired"
        }
    }

    companion object {
        /**
         * Planning runs at 00:45 UTC, after the hour-0 dispatch runs; a plan for an hour-0 learner
         * could never be sent and would only cost an AI call.
         */
        const val MIN_SEND_HOUR = 1
    }
}
