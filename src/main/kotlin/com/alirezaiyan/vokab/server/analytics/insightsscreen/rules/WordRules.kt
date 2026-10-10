package com.alirezaiyan.vokab.server.analytics.insightsscreen.rules

import com.alirezaiyan.vokab.server.analytics.insightsscreen.*
import org.springframework.stereotype.Component

private const val RECENT_DAYS = 7L
private const val MIN_WORDS = 3

private fun reviewWords(words: List<WordRef>) =
    CoachActionDto(CoachActionKind.REVIEW_WORDS, label = "Review ${plural(words.size, "word")}", wordIds = words.map { it.id })

@Component
class SlippingWordsRule : CoachRule {
    override val type = "SLIPPING_WORDS"
    override val priority = 90

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        val slipping = snapshot.reviewsIn(snapshot.lastDays(RECENT_DAYS))
            .groupBy { it.wordId }
            .mapNotNull { (_, facts) -> facts.maxBy { it.reviewedAt }.takeIf { it.newLevel < it.previousLevel } }
            .sortedBy { it.reviewedAt }
            .map { WordRef(it.wordId, it.wordText) }
        if (slipping.size < MIN_WORDS) return null
        return card(
            snapshot,
            title = "${plural(slipping.size, "word")} need a refresh",
            body = "They dropped a stage this week. A quick review locks them back in.",
            action = reviewWords(slipping),
            words = slipping,
        )
    }
}

@Component
class DifficultWordsRule : CoachRule {
    override val type = "DIFFICULT_WORDS"
    override val priority = 80

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        val hard = snapshot.difficultWords
            .filter { it.accuracyPct < MAX_ACCURACY_PCT && it.reviews >= MIN_REVIEWS }
            .sortedBy { it.accuracyPct }
            .map { WordRef(it.id, it.text) }
        if (hard.size < MIN_WORDS) return null
        return card(
            snapshot,
            title = "${plural(hard.size, "word")} keep tripping you up",
            body = "You get these right less than half the time. One focused session helps.",
            action = reviewWords(hard),
            words = hard,
        )
    }

    companion object {
        const val MAX_ACCURACY_PCT = 50
        const val MIN_REVIEWS = 3
    }
}

@Component
class LevelBottleneckRule : CoachRule {
    override val type = "LEVEL_BOTTLENECK"
    override val priority = 50

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        val rates = snapshot.stageBuckets()
            .filterKeys { it < MASTERED_LEVEL }
            .mapNotNull { (stage, facts) -> facts.gatedAccuracyPct()?.let { stage to it } }
            .toMap()
        if (rates.size < MIN_STAGES) return null
        val (stage, rate) = rates.minBy { it.value }
        val others = rates.filterKeys { it != stage }.values.average().toInt()
        if (others - rate < MIN_GAP_POINTS) return null
        val words = snapshot.stageBuckets().getValue(stage)
            .sortedByDescending { it.reviewedAt }
            .distinctBy { it.wordId }
            .map { WordRef(it.wordId, it.wordText) }
        return card(
            snapshot,
            title = "Stage $stage words are sticking less",
            body = "You pass $rate% of reviews at this stage, compared with $others% at other stages.",
            action = reviewWords(words),
            words = words,
        )
    }

    companion object {
        const val MIN_STAGES = 3
        const val MIN_GAP_POINTS = 15
    }
}

@Component
class ComebackWinRule : CoachRule {
    override val type = "COMEBACK_WIN"
    override val priority = 40

    override fun evaluate(snapshot: LearnerSnapshot): CoachCardDto? {
        val masteredRecently = snapshot.reviewsIn(snapshot.lastDays(RECENT_DAYS))
            .filter { it.newLevel == MASTERED_LEVEL && it.previousLevel < MASTERED_LEVEL }
            .map { it.wordId }
            .toSet()
        val wins = snapshot.comebackWords.filter { it.id in masteredRecently }
        if (wins.isEmpty()) return null
        return card(
            snapshot,
            title = plural(wins.size, "comeback word"),
            body = "Words you once struggled with are now mastered. That's real progress.",
            action = CoachActionDto(CoachActionKind.NONE),
            words = wins,
        )
    }
}
