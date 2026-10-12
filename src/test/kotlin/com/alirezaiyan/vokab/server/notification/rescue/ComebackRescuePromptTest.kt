package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComebackRescuePromptTest {

    @Test
    fun `renders with every rescue input`() {
        val prompt = PromptTemplates().render(
            Prompt.COMEBACK_RESCUE,
            mapOf(
                "uiLanguage" to "en",
                "targetLanguage" to "de",
                "sessionSize" to 8,
                "avoidAngle" to "rematch",
                "facts" to "F1 session: 8 words, about 3 minutes",
                "nearMastery" to "id=1 \"Hund\" (dog) level=4 recent=1111 errorRate=0.00",
                "weak" to "id=10 \"Gewitter\" (thunderstorm) level=2 recent=10100 errorRate=0.56",
            ),
        )

        assertTrue("F1 session: 8 words" in prompt)
        assertTrue("id=10 \"Gewitter\"" in prompt)
        assertTrue("Do not use the angle: rematch" in prompt)
    }
}
