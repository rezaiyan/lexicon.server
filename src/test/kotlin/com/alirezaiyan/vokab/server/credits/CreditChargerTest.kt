package com.alirezaiyan.vokab.server.credits

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CreditChargerTest {

    private val spend = CreditSpend(transactionId = 1, userId = 9, cost = 3)
    private val creditService = mockk<CreditService> {
        every { spend(9, CreditAction.PHOTO_EXTRACTION) } returns spend
        every { refund(any()) } just runs
    }
    private val charger = CreditCharger(creditService)

    @Test
    fun `charge returns the work's result and keeps the spend`() {
        val result = charger.charge(9, CreditAction.PHOTO_EXTRACTION) { "words" }

        assertEquals("words", result)
        verify(exactly = 0) { creditService.refund(any()) }
    }

    @Test
    fun `charge refunds and rethrows when the work fails`() {
        val error = assertThrows<IllegalStateException> {
            charger.charge(9, CreditAction.PHOTO_EXTRACTION) { error("AI down") }
        }

        assertEquals("AI down", error.message)
        verify(exactly = 1) { creditService.refund(spend) }
    }

    @Test
    fun `charge refunds when the result is worthless`() {
        val result = charger.charge(9, CreditAction.PHOTO_EXTRACTION, refundIf = { it.isEmpty() }) { emptyList<String>() }

        assertEquals(emptyList<String>(), result)
        verify(exactly = 1) { creditService.refund(spend) }
    }

    @Test
    fun `a failing refund never masks the work's own error`() {
        every { creditService.refund(any()) } throws RuntimeException("db down")

        val error = assertThrows<IllegalStateException> {
            charger.charge(9, CreditAction.PHOTO_EXTRACTION) { error("AI down") }
        }

        assertEquals("AI down", error.message)
    }

    @Test
    fun `the work never runs when the spend is refused`() {
        every { creditService.spend(9, CreditAction.PHOTO_EXTRACTION) } throws IllegalStateException("short")
        var ran = false

        assertThrows<IllegalStateException> { charger.charge(9, CreditAction.PHOTO_EXTRACTION) { ran = true } }

        assertEquals(false, ran)
    }

    @Test
    fun `a free action runs without any refund bookkeeping`() {
        every { creditService.spend(9, CreditAction.TEXT_TRANSLATION) } returns null

        charger.charge(9, CreditAction.TEXT_TRANSLATION, refundIf = { true }) { "ok" }

        verify(exactly = 0) { creditService.refund(any()) }
    }
}
