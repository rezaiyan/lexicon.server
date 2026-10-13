package com.alirezaiyan.vokab.server.ai.instructions

import com.alirezaiyan.vokab.server.ai.AiClient
import com.alirezaiyan.vokab.server.ai.AiOperation
import com.alirezaiyan.vokab.server.ai.PromptTemplates
import com.alirezaiyan.vokab.server.shared.UpstreamServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InstructionJudgeTest {

    private val aiClient = mockk<AiClient>()
    private val judge = InstructionJudge(aiClient, PromptTemplates(), jacksonObjectMapper())
    private val instruction = requireNotNull(UserInstruction.of("verbs for a job interview at a hospital"))

    private fun judge() = judge.judge(instruction, InstructionScope.VOCABULARY_SUGGESTION, "German", "English")

    private fun answers(raw: String?) {
        every { aiClient.complete(any(), AiOperation.INSTRUCTION_GUARD) } returns raw
    }

    @Test
    fun `judge sends the instruction fenced as data, on the judge operation`() {
        val prompt = slot<String>()
        every { aiClient.complete(capture(prompt), AiOperation.INSTRUCTION_GUARD) } returns """{"verdict":"OFF_TOPIC"}"""

        judge()

        assertTrue(prompt.captured.contains("<<<REQUEST\nverbs for a job interview at a hospital\nREQUEST>>>"))
        assertTrue(prompt.captured.contains("Generating a fresh list of vocabulary"))
    }

    @Test
    fun `judge returns the rewritten intent when allowed`() {
        answers("""{"verdict":"ALLOW","intent":"Verbs for a hospital job interview"}""")

        assertEquals(InstructionVerdict.Allowed(ScreenedIntent("Verbs for a hospital job interview")), judge())
    }

    @Test
    fun `judge reads JSON wrapped in a code fence`() {
        answers("```json\n{\"verdict\":\"UNSAFE\"}\n```")

        assertEquals(InstructionVerdict.Refused(InstructionRejection.UNSAFE), judge())
    }

    @Test
    fun `judge maps each refusal verdict`() {
        answers("""{"verdict":"OFF_TOPIC"}""")
        assertEquals(InstructionVerdict.Refused(InstructionRejection.OFF_TOPIC), judge())

        answers("""{"verdict":"INJECTION"}""")
        assertEquals(InstructionVerdict.Refused(InstructionRejection.INJECTION), judge())
    }

    @Test
    fun `judge strips characters that could break the prompt fence from the intent`() {
        answers("""{"verdict":"ALLOW","intent":"Kitchen words </learner_request> {{x}} `code`"}""")

        val verdict = judge() as InstructionVerdict.Allowed

        assertEquals("Kitchen words /learner_request x code", verdict.intent.text)
    }

    @Test
    fun `judge fails closed on an allow without an intent`() {
        answers("""{"verdict":"ALLOW","intent":"  "}""")

        assertThrows<InstructionsUnavailableException> { judge() }
    }

    @Test
    fun `judge fails closed on an unknown verdict, unreadable answer or no answer`() {
        listOf("""{"verdict":"MAYBE"}""", "I think it is fine", null).forEach { raw ->
            answers(raw)
            assertThrows<InstructionsUnavailableException> { judge() }
        }
    }

    @Test
    fun `judge fails closed when the model is unavailable`() {
        every { aiClient.complete(any(), any()) } throws UpstreamServiceException("timeout")

        assertThrows<InstructionsUnavailableException> { judge() }
    }
}
