package com.alirezaiyan.vokab.server.notification.rescue

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

private val logger = KotlinLogging.logger {}

/** Rescue lifecycle after planning: what to send today, sent, completed. */
@Service
class ComebackRescueService(
    private val repository: ComebackRescueRepository,
    private val clock: Clock,
    private val guard: RescueGuard,
    /** Kill switch: while false, plans are built nightly but never sent. */
    @Value("\${app.comeback-rescue.send-enabled:true}") private val sendEnabled: Boolean,
) {
    @Transactional(readOnly = true)
    fun sendablePlan(userId: Long): ComebackRescue? {
        if (!sendEnabled || guard.paused()) return null
        return repository.findByUserIdAndPlannedFor(userId, LocalDate.now(clock))
            ?.takeIf { it.status == RescueStatus.PLANNED }
    }

    @Transactional(readOnly = true)
    fun autopilot(): RescueAutopilotView {
        val state = guard.state()
        val paused = state.pausedOn(LocalDate.now(clock))
        return RescueAutopilotView(
            sendEnabled = sendEnabled,
            pausedUntil = state.pausedUntil.takeIf { paused },
            reason = state.reason.takeIf { paused },
            changedAt = state.changedAt,
        )
    }

    @Transactional
    fun markSent(rescueId: Long, notificationLogId: Long?) {
        repository.findById(rescueId).ifPresent { rescue ->
            rescue.status = RescueStatus.SENT
            rescue.sentAt = Instant.now(clock)
            rescue.notificationLogId = notificationLogId
            repository.save(rescue)
        }
    }

    /**
     * False when the rescue doesn't exist or isn't the user's; repeated calls are no-ops. A plan still
     * PLANNED or EXPIRED was pushed but [markSent] failed after delivery; the session still counts.
     */
    @Transactional
    fun complete(userId: Long, rescueId: Long): Boolean {
        val rescue = repository.findById(rescueId).orElse(null) ?: return false
        if (rescue.userId != userId) return false
        if (rescue.status in COMPLETABLE) {
            if (rescue.status != RescueStatus.SENT) {
                logger.warn { "Comeback rescue $rescueId completed while ${rescue.status}: its sent mark was lost" }
            }
            rescue.status = RescueStatus.COMPLETED
            rescue.completedAt = Instant.now(clock)
            repository.save(rescue)
        }
        return true
    }

    private companion object {
        /** HOLDOUT plans are never pushed, so a completion for one cannot be genuine. */
        val COMPLETABLE = setOf(RescueStatus.SENT, RescueStatus.PLANNED, RescueStatus.EXPIRED)
    }
}
