package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RescueCopyJudgeTest {

    private val aiClient: AiClient = mockk()
    private val judge = RescueCopyJudge(aiClient, PromptTemplates(), ObjectMapper())

    private val sheet = RescueFactSheet(listOf(RescueFact("F1", "session: 8 words, about 3 minutes")))
    private val candidates = listOf(
        RescueCopyCandidate("rematch", "Gewitter, one more try", "8 words, about 3 min.", listOf("F1"), 1),
        RescueCopyCandidate("curiosity", "Still know Gewitter?", "8 words, about 3 min.", listOf("F1"), 2),
    )

    @Test
    fun `asks the judge about every candidate and reads one verdict each`() {
        val prompt = slot<String>()
        every { aiClient.complete(capture(prompt), AiOperation.COMEBACK_RESCUE_JUDGE) } returns
            """```json
            {"verdicts":[{"index":0,"pass":true,"reason":"warm and specific"},{"index":1,"pass":false,"reason":"generic"}]}
            ```"""

        val verdicts = requireNotNull(judge.judge(candidates, sheet, uiLanguage = "en", targetLanguage = "de"))

        assertEquals(JudgeVerdict(true, "warm and specific"), verdicts[0])
        assertEquals(JudgeVerdict(false, "generic"), verdicts[1])
        assertTrue("[0] Gewitter, one more try | 8 words, about 3 min." in prompt.captured)
        assertTrue("F1 session: 8 words" in prompt.captured)
    }

    @Test
    fun `a candidate the judge skipped is not approved`() {
        every { aiClient.complete(any(), any()) } returns """{"verdicts":[{"index":0,"pass":true,"reason":"ok"}]}"""

        val verdicts = requireNotNull(judge.judge(candidates, sheet, "en", "de"))

        assertNull(verdicts[1])
    }

    @Test
    fun `an unavailable or unreadable judge approves nothing`() {
        every { aiClient.complete(any(), any()) } throws UpstreamServiceException("down")
        assertNull(judge.judge(candidates, sheet, "en", "de"))

        every { aiClient.complete(any(), any()) } returns "I like the first one."
        assertNull(judge.judge(candidates, sheet, "en", "de"))
    }

    @Test
    fun `nothing to judge makes no call`() {
        assertEquals(emptyMap<Int, JudgeVerdict>(), judge.judge(emptyList(), sheet, "en", "de"))
        verify(exactly = 0) { aiClient.complete(any(), any()) }
    }
}
