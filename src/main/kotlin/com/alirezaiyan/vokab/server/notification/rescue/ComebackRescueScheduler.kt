package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationScheduleRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

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
    /** The nightly run: every reachable learner. */
    fun planAll() = plan(MIN_SEND_HOUR)

    /**
     * An admin run during the day: only learners whose send hour is still ahead. A plan for an hour
     * that has passed would never be sent, yet would use up the learner's one rescue for the lapse.
     */
    fun planRemainingToday() = plan(maxOf(MIN_SEND_HOUR, clock.instant().atZone(ZoneOffset.UTC).hour + 1))

    private fun plan(minSendHour: Int) {
        val startedAt = clock.millis()
        val expired = repository.expireStale(LocalDate.now(clock), RescueStatus.PLANNED, RescueStatus.EXPIRED)
        val users = scheduleRepository.findRescuePlanningCandidates(minSendHour).map { it.user }
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

        // Scheduled jobs run one at a time: a batch that runs long delays the 01:00 dispatch
        logger.info {
            "Comeback rescue planning complete — users=${users.size}, planned=$planned, " +
                "failed=$failed, expired=$expired, tookMs=${clock.millis() - startedAt}"
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
