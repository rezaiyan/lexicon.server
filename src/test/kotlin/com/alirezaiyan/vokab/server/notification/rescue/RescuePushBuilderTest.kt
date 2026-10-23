package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.notification.NotificationTypeSelector.NotificationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RescuePushBuilderTest {

    private fun rescue(outroWin: String? = "Gewitter: cracked.", outroSoft: String? = "Soon.") = ComebackRescue(
        id = 42L,
        userId = 1L,
        plannedFor = LocalDate.of(2026, 6, 17),
        wordIds = "[5, 1, 20, 3]",
        title = "Gewitter, round 3",
        body = "8 words, about 3 min.",
        angle = "rematch",
        outroWin = outroWin,
        outroSoft = outroSoft,
        source = RescueSource.AI,
        status = RescueStatus.PLANNED,
    )

    @Test
    fun `carries the ordered words and rescue id in the deep link`() {
        val payload = RescuePushBuilder.build(rescue())

        assertEquals(NotificationType.COMEBACK_RESCUE, payload.type)
        assertEquals("Gewitter, round 3", payload.title)
        assertEquals("comeback_rescue", payload.data["type"])
        assertEquals("vokab://review/rescue?ids=5,1,20,3&rid=42", payload.data["deep_link"])
        assertEquals("42", payload.data["rid"])
        assertEquals("Gewitter: cracked.", payload.data["outro_win"])
        assertEquals("Soon.", payload.data["outro_soft"])
    }

    @Test
    fun `omits missing outros`() {
        val payload = RescuePushBuilder.build(rescue(outroWin = null, outroSoft = null))

        assertFalse("outro_win" in payload.data)
        assertFalse("outro_soft" in payload.data)
    }
}
