package com.alirezaiyan.vokab.server.notification.rescue

import com.alirezaiyan.vokab.server.ai.Prompt
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.alirezaiyan.vokab.server.user.requireId
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/** How many rated candidates were liked: the chosen candidate per preview, and every candidate. */
data class TasteScore(val rated: Int, val up: Int, val allRated: Int, val allUp: Int) {
    val upRate: Double? get() = if (rated == 0) null else up.toDouble() / rated
    val allUpRate: Double? get() = if (allRated == 0) null else allUp.toDouble() / allRated
    val target: Double get() = TARGET_UP_RATE

    companion object {
        /** Sends stay off until the chosen copy is liked at least this often. */
        const val TARGET_UP_RATE = 0.8
    }
}

/**
 * The taste test behind the send switch: drafts rescue copy for real learners without sending or
 * planning anything, and keeps Ali's 👍/👎 on every candidate.
 */
@Service
class RescuePreviewService(
    private val planner: ComebackRescuePlanner,
    private val userRepository: UserRepository,
    private val previewRepository: RescuePreviewRepository,
    private val ratingRepository: RescueCopyRatingRepository,
    private val objectMapper: ObjectMapper,
    private val random: Random = Random.Default,
) {
    /** Up to [count] (max [MAX_COUNT]) previews from randomly chosen learners who have enough usable words. */
    fun generate(count: Int): List<RescuePreview> {
        val wanted = count.coerceIn(1, MAX_COUNT)
        return userRepository.findAllActiveUsersWithPushTokens()
            .shuffled(random)
            .asSequence()
            .take(wanted * TRIES_PER_PREVIEW)
            .mapNotNull { user -> draftFor(user)?.let { draft -> previewRepository.save(draft.toPreview(user)) } }
            .take(wanted)
            .toList()
    }

    private fun draftFor(user: User): RescueDraft? =
        runCatching { planner.draft(user, PREVIEW_DAYS_AWAY) }
            .onFailure { e -> logger.warn(e) { "Rescue preview draft failed for user=${user.id}" } }
            .getOrNull()

    private fun RescueDraft.toPreview(user: User) = RescuePreview(
        userId = user.requireId(),
        promptVersion = Prompt.COMEBACK_RESCUE.resource,
        source = source,
        angle = copy.angle,
        title = copy.title,
        body = copy.body,
        outroWin = copy.outroWin,
        outroSoft = copy.outroSoft,
        wordIds = ComebackRescue.encodeWordIds(wordIds),
        candidates = candidatesJson,
        picksReasoning = reasoningJson,
    )

    /** Rates one candidate of a preview, replacing any earlier rating of it. */
    fun rate(previewId: Long, candidateIndex: Int, rating: CopyRating, note: String?): RescueCopyRating {
        val preview = previewRepository.findById(previewId).orElseThrow { NoSuchElementException("Preview not found") }
        require(candidateIndex in 0 until candidateTitles(preview).size) { "No candidate $candidateIndex in this preview" }

        val existing = ratingRepository.findByPreviewIdAndCandidateIndex(previewId, candidateIndex)
        val saved = existing ?: RescueCopyRating(previewId = previewId, candidateIndex = candidateIndex, rating = rating)
        saved.rating = rating
        saved.note = note?.trim()?.takeIf { it.isNotEmpty() }
        saved.ratedAt = Instant.now()
        return ratingRepository.save(saved)
    }

    fun tasteScore(): TasteScore {
        val ratings = ratingRepository.findAll()
        val chosenIndex = previewRepository.findAllById(ratings.map { it.previewId }.toSet())
            .associate { requireNotNull(it.id) to chosenCandidateIndex(it) }
        val chosen = ratings.filter { it.candidateIndex == chosenIndex[it.previewId] }
        return TasteScore(
            rated = chosen.size,
            up = chosen.count { it.rating == CopyRating.UP },
            allRated = ratings.size,
            allUp = ratings.count { it.rating == CopyRating.UP },
        )
    }

    /** The candidate the linter picked and the learner would have seen, or null for template copy. */
    fun chosenCandidateIndex(preview: RescuePreview): Int? =
        candidateTitles(preview).indexOf(preview.title).takeIf { it >= 0 && preview.source == RescueSource.AI }

    /** Titles of the stored candidates, in the order the AI returned them. */
    fun candidateTitles(preview: RescuePreview): List<String> {
        val json = preview.candidates ?: return emptyList()
        return objectMapper.readTree(json).map { it.path("candidate").path("title").asText() }
    }

    companion object {
        const val MAX_COUNT = 50
        const val TRIES_PER_PREVIEW = 5

        /** Previews are drafted as if the learner had just missed two days, the earliest rescue. */
        const val PREVIEW_DAYS_AWAY = ComebackRescuePolicy.MIN_DAYS_AWAY
    }
}
