package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.analytics.LearnerSignals
import com.alirezaiyan.vokab.server.analytics.PracticeHistory
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import com.alirezaiyan.vokab.server.shared.bestEffort
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.requireId
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Decides when a push should invite the learner to Word Rush or Listening.
 *
 * Only asked once nothing more useful is due (no streak at risk, milestone, recap, due cards or
 * struggling word) for a learner who hasn't studied today, so a nudge never displaces a review
 * reminder, and it goes out at the learner's usual study hour like every smart push. Within that:
 * - A mode the learner already uses (in the last [REGULAR_WINDOW]) can come back every
 *   [REGULAR_COOLDOWN], but not within [REST] of their last session: they just did it.
 * - A mode they never or no longer use is introduced at most every [INTRO_COOLDOWN], and not in
 *   the account's first [NEW_USER_GRACE], which belongs to the core review habit.
 * - A nudge that got no reaction (not tapped, mode not used since) doubles its wait.
 * - Never the same type as the previous push; a mode the learner already enjoys wins over an
 *   introduction, and among equals the one unused the longest.
 */
@Service
class PracticeNudgePolicy(
    private val learnerSignals: LearnerSignals,
    private val notificationLogRepository: NotificationLogRepository,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun pick(user: User, schedule: NotificationSchedule, totalWords: Int): NotificationType? {
        if (totalWords < MIN_WORDS) return null
        val history = bestEffort("Practice history for user=${user.id}") {
            learnerSignals.practiceHistory(user.requireId())
        } ?: return null
        val now = Instant.now(clock)

        return modes(history)
            .filter { it.type.name != schedule.lastSentType }
            .filter { isDue(it, user, now) }
            .sortedWith(
                compareByDescending<Mode> { isRegular(it.lastUsedAt, now) }
                    .thenBy { it.lastUsedAt ?: Instant.EPOCH }
            )
            .firstOrNull()
            ?.type
    }

    private fun isDue(mode: Mode, user: User, now: Instant): Boolean {
        val lastUsedAt = mode.lastUsedAt
        val regular = isRegular(lastUsedAt, now)
        if (regular && lastUsedAt != null && lastUsedAt.isAfter(now.minus(REST))) return false
        if (!regular && user.createdAt.isAfter(now.minus(NEW_USER_GRACE))) return false

        val lastNudge = notificationLogRepository
            .findTopByUserIdAndNotificationTypeOrderBySentAtDesc(user.requireId(), mode.type.name)
            ?: return true
        val usedSinceNudge = lastUsedAt != null && lastUsedAt.isAfter(lastNudge.sentAt)
        val unanswered = lastNudge.openedAt == null && !usedSinceNudge
        val cooldown = (if (regular) REGULAR_COOLDOWN else INTRO_COOLDOWN)
            .let { if (unanswered) it.multipliedBy(2) else it }
        return lastNudge.sentAt.plus(cooldown).isBefore(now)
    }

    private data class Mode(val type: NotificationType, val lastUsedAt: Instant?)

    private fun modes(history: PracticeHistory) = listOf(
        Mode(NotificationType.WORD_RUSH, history.lastWordRushAt),
        Mode(NotificationType.LISTENING, history.lastListeningAt),
    )

    companion object {
        /** Enough words for a varied game or listening session. */
        const val MIN_WORDS = 10

        val REGULAR_WINDOW: Duration = Duration.ofDays(30)
        val REST: Duration = Duration.ofDays(1)
        val REGULAR_COOLDOWN: Duration = Duration.ofDays(3)
        val INTRO_COOLDOWN: Duration = Duration.ofDays(14)
        val NEW_USER_GRACE: Duration = Duration.ofDays(3)

        /** Whether a mode last used at [lastUsedAt] is one the learner currently uses. */
        fun isRegular(lastUsedAt: Instant?, now: Instant): Boolean =
            lastUsedAt != null && lastUsedAt.isAfter(now.minus(REGULAR_WINDOW))
    }
}
