package com.alirezaiyan.vokab.server.analytics.insightsscreen

private const val MAX_WORD_CHIPS = 5

/** One coaching insight. Implementations are stateless Spring beans; add a rule = add a class. */
interface CoachRule {
    val type: String
    /** Higher shows first. */
    val priority: Int
    fun evaluate(snapshot: LearnerSnapshot): CoachCardDto?
}

fun CoachRule.card(
    snapshot: LearnerSnapshot,
    title: String,
    body: String,
    action: CoachActionDto,
    words: List<WordRef> = emptyList(),
) = CoachCardDto(
    id = "$type:${snapshot.today}",
    type = type,
    priority = priority,
    title = title,
    body = body,
    words = words.take(MAX_WORD_CHIPS).map { WordChipDto(it.id, it.text) },
    moreWordsCount = (words.size - MAX_WORD_CHIPS).coerceAtLeast(0),
    action = action,
)

fun plural(count: Int, one: String, many: String = "${one}s"): String = "$count ${if (count == 1) one else many}"
