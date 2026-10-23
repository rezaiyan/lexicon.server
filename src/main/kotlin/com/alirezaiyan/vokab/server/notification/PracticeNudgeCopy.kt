package com.alirezaiyan.vokab.server.notification

import com.alirezaiyan.vokab.server.analytics.PracticeHistory
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import java.time.Instant

/**
 * Copy for a Word Rush or Listening nudge: a challenge for a learner who already uses the mode,
 * an introduction otherwise. No deep link, the tap just opens the app. Neither mode counts toward
 * the streak, so the copy never promises it does. [history] is null when it couldn't be loaded.
 */
internal fun practiceNudgePayload(
    type: NotificationType,
    history: PracticeHistory?,
    now: Instant,
): NotificationPayload {
    val (title, body) = when (type) {
        NotificationType.WORD_RUSH -> {
            val bestScore = history?.bestWordRushScore ?: 0
            if (PracticeNudgePolicy.isRegular(history?.lastWordRushAt, now) && bestScore > 0) {
                "Beat your best: $bestScore ⚡" to "A quick Word Rush round with the words you're learning."
            } else {
                "Up for a quick game? ⚡" to "Word Rush turns your words into a fast round. How far can you get?"
            }
        }
        NotificationType.LISTENING ->
            if (PracticeNudgePolicy.isRegular(history?.lastListeningAt, now)) {
                "Practice by ear 🎧" to "Hands busy? Press play and listen through your words."
            } else {
                "Learn with your ears 🎧" to "Listening mode reads your words and translations aloud, hands-free."
            }
        else -> error("Not a practice nudge: $type")
    }
    return NotificationPayload(title, body, mapOf("type" to type.name.lowercase()), type)
}
