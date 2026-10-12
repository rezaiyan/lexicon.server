package com.alirezaiyan.vokab.server.notification.rescue

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RescueAiResponseTest {

    private val objectMapper = ObjectMapper()

    private val json = """
        {"wordIds":[1,2,10,3],"reasons":{"10":"weak, right last time"},
         "candidates":[{"angle":"rematch","title":"Gewitter, round 3","body":"8 words, about 3 min.","factIds":["F1","F5"],"rank":1}],
         "outroWin":"Gewitter: cracked.","outroSoft":"Gewitter will come round again.","extra":"ignored"}
    """.trimIndent()

    @Test
    fun `parses a full response and ignores unknown fields`() {
        val response = requireNotNull(RescueAiResponse.parse(json, objectMapper))

        assertEquals(listOf(1L, 2L, 10L, 3L), response.wordIds)
        assertEquals("weak, right last time", response.reasons["10"])
        assertEquals(listOf("F1", "F5"), response.candidates.single().factIds)
        assertEquals("Gewitter: cracked.", response.outroWin)
    }

    @Test
    fun `tolerates a markdown code fence`() {
        val fenced = "```json\n$json\n```"

        assertEquals(listOf(1L, 2L, 10L, 3L), RescueAiResponse.parse(fenced, objectMapper)?.wordIds)
    }

    @Test
    fun `returns null for text that is not JSON`() {
        assertNull(RescueAiResponse.parse("Sure! Here is your plan.", objectMapper))
    }
}
