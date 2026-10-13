package com.alirezaiyan.vokab.server.notification.rescue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RescueCopyLinterTest {

    private val sheet = RescueFactSheet(
        listOf(
            RescueFact("F1", "session: 8 words, about 3 minutes"),
            RescueFact("F2", "words in deck: 212 (de)"),
            RescueFact("F3", "weak word \"Gewitter\" (thunderstorm): missed 3 of last 5", setOf("Gewitter")),
        )
    )
    private val ctx = RescueCopyContext(
        factSheet = sheet,
        sessionWords = setOf("Gewitter", "Hund"),
        lastAngle = null,
        recentCopies = emptyList(),
    )

    private fun candidate(
        title: String = "Gewitter, one more go",
        body: String = "You were close last time. 8 words, about 3 min.",
        angle: String = "rematch",
        factIds: List<String> = listOf("F1", "F3"),
        rank: Int = 1,
    ) = RescueCopyCandidate(angle, title, body, factIds, rank)

    @Test
    fun `good copy has no violations`() {
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(), ctx))
    }

    @Test
    fun `flags length by graphemes`() {
        val longTitle = "G".repeat(RescueCopyLinter.TITLE_MAX + 1)
        assertTrue(RescueCopyLinter.lint(candidate(title = "Gewitter $longTitle"), ctx).any { "title" in it })
        // 40 Persian letters are 40 graphemes even though they are multi-byte
        assertEquals(40, RescueCopyLinter.graphemes("س".repeat(40)))
    }

    @Test
    fun `must name a session word`() {
        assertTrue(RescueCopyLinter.lint(candidate(title = "One more go"), ctx).contains("names no session word"))
    }

    @Test
    fun `numbers must come from cited facts`() {
        val v = RescueCopyLinter.lint(candidate(body = "You know 500 words. 8 words, about 3 min."), ctx)
        assertTrue(v.contains("ungrounded number 500"))
        // 212 exists but F2 is not cited
        assertTrue(RescueCopyLinter.lint(candidate(body = "212 words! 8 words, about 3 min."), ctx).contains("ungrounded number 212"))
    }

    @Test
    fun `digits inside the learner's own words are not stats`() {
        val withDigitWord = ctx.copy(sessionWords = setOf("B2B", "Hund"))

        val v = RescueCopyLinter.lint(candidate(title = "B2B, one more go"), withDigitWord)

        assertEquals(emptyList<String>(), v)
        assertTrue(RescueCopyLinter.outroOk("Gewitter: cracked. 8 for 8.", sheet))
    }

    @Test
    fun `numbers in any script must come from cited facts`() {
        // Persian digits: 500 is invented, 8 and 3 are in the cited F1
        val v = RescueCopyLinter.lint(candidate(body = "Gewitter ۵۰۰ واژه. ۸ واژه، ۳ دقیقه."), ctx)

        assertEquals(listOf("ungrounded number 500"), v)
        assertTrue(!RescueCopyLinter.outroOk("Gewitter: ８ of ９９.", sheet))
    }

    @Test
    fun `a short session word inside another word does not count as naming it`() {
        val shortWord = ctx.copy(sessionWords = setOf("in"))

        assertTrue(RescueCopyLinter.lint(candidate(title = "Training time"), shortWord).contains("names no session word"))
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(title = "\"in\", one more go"), shortWord))
    }

    @Test
    fun `a session word in a script without spaces counts next to other letters`() {
        val japanese = ctx.copy(sessionWords = setOf("猫"))

        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(title = "猫をもう一度"), japanese))
    }

    @Test
    fun `quoted words must come from the fact sheet or the session`() {
        val v = RescueCopyLinter.lint(candidate(body = "Gewitter and \"Blitz\" wait. 8 words, about 3 min."), ctx)
        assertTrue(v.contains("ungrounded word Blitz"))

        val quotedSessionWord = candidate(body = "\"hund\" and Gewitter wait. 8 words, about 3 min.")
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(quotedSessionWord, ctx))
    }

    @Test
    fun `a target word inside copy written without spaces still counts`() {
        val japaneseUi = candidate(title = "Gewitterをもう一度", body = "8語、約3分。")

        assertEquals(emptyList<String>(), RescueCopyLinter.lint(japaneseUi, ctx))
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(title = "还记得Gewitter吗"), ctx))
    }

    @Test
    fun `banned words are caught in any form, but not inside other words`() {
        assertTrue(RescueCopyLinter.lint(candidate(body = "Gewitter unlocked. 8 words, about 3 min."), ctx).contains("banned phrase unlock"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "Boosting Gewitter. 8 words, about 3 min."), ctx).contains("banned phrase boost"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "Don’t lose Gewitter. 8 words, about 3 min."), ctx).contains("banned phrase don't lose"))
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(body = "Already today? 8 words, about 3 min."), ctx))
    }

    @Test
    fun `emoji are caught by the emoji property, not by code point ranges`() {
        assertTrue(RescueCopyLinter.lint(candidate(body = "8 words, about 3 min ⏰"), ctx).contains("emoji"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "8 words, about 3 min ⭐"), ctx).contains("emoji"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "Your 🇩🇪 Gewitter. 8 words, about 3 min."), ctx).contains("emoji"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "8 words, about 3️⃣ min."), ctx).contains("emoji"))
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(body = "𠀋 8 words, about 3 min."), ctx))
    }

    @Test
    fun `a session of another size grounds its own count, not the planned one`() {
        val sevenWords = ctx.copy(factSheet = sheet.withSessionSize(7))

        assertTrue(RescueCopyLinter.lint(candidate(), sevenWords).contains("ungrounded number 8"))
        assertEquals(emptyList<String>(), RescueCopyLinter.lint(candidate(body = "You were close. 7 words, about 3 min."), sevenWords))
    }

    @Test
    fun `cited fact ids must exist`() {
        assertTrue(RescueCopyLinter.lint(candidate(factIds = listOf("F1", "F9")), ctx).contains("cites unknown fact F9"))
    }

    @Test
    fun `bans hype, guilt and AI tells`() {
        assertTrue(RescueCopyLinter.lint(candidate(title = "Ready to crush Gewitter?"), ctx).any { it.startsWith("banned phrase") })
        assertTrue(RescueCopyLinter.lint(candidate(body = "Gewitter — 8 words, about 3 min."), ctx).contains("em dash"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "Go! Now! 8 words, about 3 min."), ctx).contains("too many exclamation marks"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "8 words, about 3 min 🚀"), ctx).contains("emoji"))
        assertTrue(RescueCopyLinter.lint(candidate(body = "We miss you. 8 words, about 3 min."), ctx).any { it.startsWith("banned phrase") })
    }

    @Test
    fun `angle must be known and differ from the last one`() {
        assertTrue(RescueCopyLinter.lint(candidate(angle = "hype"), ctx).contains("unknown angle hype"))
        assertTrue(RescueCopyLinter.lint(candidate(), ctx.copy(lastAngle = "rematch")).contains("repeats last angle"))
    }

    @Test
    fun `must not repeat a recent copy`() {
        val recent = ctx.copy(recentCopies = listOf("gewitter, ONE more go!! You were close last time. 8 words, about 3 min"))
        assertTrue(RescueCopyLinter.lint(candidate(), recent).contains("repeats a recent copy"))
    }

    @Test
    fun `lintAll marks each candidate ok or not, in order`() {
        val bad = candidate(title = "Ready to crush it?", rank = 1)
        val good = candidate(angle = "curiosity", title = "Still know what Gewitter means?", body = "8 words, about 3 min.", rank = 2)

        val linted = RescueCopyLinter.lintAll(listOf(bad, good), ctx)

        assertEquals(listOf(false, true), linted.map { it.ok })
        assertEquals(good, linted[1].candidate)
    }

    @Test
    fun `outros follow the same tone rules`() {
        assertTrue(RescueCopyLinter.outroOk("Gewitter: cracked. 8 for 8.", sheet))
        assertTrue(!RescueCopyLinter.outroOk("Don't lose your streak!", sheet))
        assertTrue(!RescueCopyLinter.outroOk("You beat 99 learners.", sheet))
        assertTrue(!RescueCopyLinter.outroOk(null, sheet))
        assertTrue(!RescueCopyLinter.outroOk("x".repeat(RescueCopyLinter.OUTRO_MAX + 1), sheet))
    }
}
