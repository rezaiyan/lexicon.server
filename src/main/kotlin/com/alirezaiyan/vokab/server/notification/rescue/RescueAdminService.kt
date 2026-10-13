package com.alirezaiyan.vokab.server.notification.rescue

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/** Read side of the rescue admin page: previews with their ratings, nightly plans, funnel, eval set. */
@Service
class RescueAdminService(
    private val previewRepository: RescuePreviewRepository,
    private val ratingRepository: RescueCopyRatingRepository,
    private val rescueRepository: ComebackRescueRepository,
    private val previewService: RescuePreviewService,
    private val funnelQueries: RescueFunnelQueries,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional(readOnly = true)
    fun previews(limit: Int): List<RescuePreviewView> =
        previewViews(previewRepository.findAllByOrderByCreatedAtDescIdDesc(page(limit)))

    /** [previews] with every candidate's verdict, whether it was chosen, and its rating. */
    @Transactional(readOnly = true)
    fun previewViews(previews: List<RescuePreview>): List<RescuePreviewView> {
        val ratings = ratingRepository.findAllByPreviewIdIn(previews.mapNotNull { it.id })
            .associateBy { it.previewId to it.candidateIndex }
        return previews.map { preview ->
            val id = requireNotNull(preview.id)
            val chosen = previewService.chosenCandidateIndex(preview)
            RescuePreviewView(
                id = id,
                userId = preview.userId,
                createdAt = preview.createdAt,
                promptVersion = preview.promptVersion,
                source = preview.source,
                angle = preview.angle,
                title = preview.title,
                body = preview.body,
                outroWin = preview.outroWin,
                outroSoft = preview.outroSoft,
                wordIds = ComebackRescue.decodeWordIds(preview.wordIds),
                candidates = objectMapper.readStoredCandidates(preview.candidates).mapIndexed { index, stored ->
                    val rating = ratings[id to index]
                    RescueCandidateView(
                        index = index,
                        angle = stored.candidate.angle,
                        title = stored.candidate.title,
                        body = stored.candidate.body,
                        factIds = stored.candidate.factIds,
                        rank = stored.candidate.rank,
                        violations = stored.violations,
                        judgePass = stored.judge?.pass,
                        judgeReason = stored.judge?.reason,
                        chosen = index == chosen,
                        rating = rating?.rating,
                        note = rating?.note,
                    )
                },
            )
        }
    }

    @Transactional(readOnly = true)
    fun plans(limit: Int): List<RescuePlanView> =
        rescueRepository.findAllByOrderByPlannedForDescIdDesc(page(limit)).map {
            RescuePlanView(
                id = requireNotNull(it.id),
                userId = it.userId,
                plannedFor = it.plannedFor,
                status = it.status,
                source = it.source,
                angle = it.angle,
                title = it.title,
                body = it.body,
                outroWin = it.outroWin,
                outroSoft = it.outroSoft,
                wordCount = it.wordIdList.size,
                sentAt = it.sentAt,
                completedAt = it.completedAt,
            )
        }

    fun funnel(days: Int): List<RescueFunnelRow> =
        funnelQueries.funnel(days.coerceIn(1, MAX_FUNNEL_DAYS), LocalDate.now(clock))

    /** Every rated candidate, oldest preview first: the set future prompt versions are checked against. */
    @Transactional(readOnly = true)
    fun evalSet(): List<RescueEvalLine> {
        val ratings = ratingRepository.findAll()
        val previews = previewRepository.findAllById(ratings.map { it.previewId }.toSet()).associateBy { it.id }
        return ratings
            .sortedWith(compareBy({ it.previewId }, { it.candidateIndex }))
            .mapNotNull { rating ->
                val preview = previews[rating.previewId] ?: return@mapNotNull null
                val stored = objectMapper.readStoredCandidates(preview.candidates).getOrNull(rating.candidateIndex)
                    ?: return@mapNotNull null
                RescueEvalLine(
                    previewId = rating.previewId,
                    candidateIndex = rating.candidateIndex,
                    promptVersion = preview.promptVersion,
                    rating = rating.rating,
                    note = rating.note,
                    chosen = rating.candidateIndex == previewService.chosenCandidateIndex(preview),
                    angle = stored.candidate.angle,
                    title = stored.candidate.title,
                    body = stored.candidate.body,
                    factIds = stored.candidate.factIds,
                    violations = stored.violations,
                    judgePass = stored.judge?.pass,
                    judgeReason = stored.judge?.reason,
                )
            }
    }

    private fun page(limit: Int) = PageRequest.of(0, limit.coerceIn(1, MAX_LIMIT))

    companion object {
        const val MAX_LIMIT = 200
        const val MAX_FUNNEL_DAYS = 90
    }
}
