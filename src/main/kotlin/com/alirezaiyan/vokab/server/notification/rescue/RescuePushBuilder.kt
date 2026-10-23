package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationPayload
import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType

/**
 * The push for a planned rescue. The deep link carries the words in session order and the rescue
 * id; clients that don't know the link fall back to the due review (`vokab://review/` prefix).
 */
object RescuePushBuilder {
    const val PUSH_TYPE = "comeback_rescue"

    fun build(rescue: ComebackRescue): NotificationPayload {
        val rid = requireNotNull(rescue.id) { "Rescue must be saved before it is sent" }
        return NotificationPayload(
            title = rescue.title,
            body = rescue.body,
            data = buildMap {
                put("type", PUSH_TYPE)
                put("deep_link", "vokab://review/rescue?ids=${rescue.wordIdList.joinToString(",")}&rid=$rid")
                put("rid", rid.toString())
                rescue.outroWin?.let { put("outro_win", it) }
                rescue.outroSoft?.let { put("outro_soft", it) }
            },
            type = NotificationType.COMEBACK_RESCUE,
        )
    }
}
