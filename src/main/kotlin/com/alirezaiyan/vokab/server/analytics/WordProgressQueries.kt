package com.alirezaiyan.vokab.server.analytics

import com.alirezaiyan.vokab.server.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Per-word progress: difficult, most reviewed, mastered and comeback words, level transitions. Read-only; backs the analytics dashboards. */
@Service
class WordProgressQueries(
    private val reviewEventRepository: ReviewEventRepository,
    private val userRepository: UserRepository,
) {

    @Transactional(readOnly = true)
    fun getDifficultWords(userId: Long, minReviews: Int, limit: Int): List<DifficultWordResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.findDifficultWords(user, minReviews, PageRequest.of(0, limit))
            .map { p ->
                DifficultWordResponse(
                    wordId = p.wordId,
                    wordText = p.wordText,
                    wordTranslation = p.wordTranslation,
                    sourceLanguage = p.sourceLanguage,
                    targetLanguage = p.targetLanguage,
                    totalReviews = p.total.toInt(),
                    errorCount = p.errors.toInt(),
                    errorRate = if (p.total > 0) p.errors.toDouble() / p.total else 0.0
                )
            }
    }

    @Transactional(readOnly = true)
    fun getMostReviewedWords(userId: Long, limit: Int): List<MostReviewedWordResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.findMostReviewedWords(user, PageRequest.of(0, limit))
            .map { p ->
                MostReviewedWordResponse(
                    wordId = p.wordId,
                    wordText = p.wordText,
                    wordTranslation = p.wordTranslation,
                    totalReviews = p.total.toInt()
                )
            }
    }

    @Transactional(readOnly = true)
    fun getLevelTransitions(userId: Long): List<LevelTransitionResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.getLevelTransitions(user).map { p ->
            LevelTransitionResponse(
                fromLevel = p.fromLevel,
                toLevel = p.toLevel,
                count = p.count
            )
        }
    }

    @Transactional(readOnly = true)
    fun getWordsMastered(userId: Long, limit: Int): List<MasteredWordResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.findWordsMastered(user, PageRequest.of(0, limit)).map { p ->
            MasteredWordResponse(
                wordId = p.wordId,
                wordText = p.wordText,
                wordTranslation = p.wordTranslation,
                masteredAt = p.masteredAt
            )
        }
    }

    @Transactional(readOnly = true)
    fun getComebackWords(userId: Long): List<ComebackWordResponse> {
        val user = userRepository.getReferenceById(userId)
        return reviewEventRepository.findComebackWords(user).map { p ->
            ComebackWordResponse(
                wordId = p.wordId,
                wordText = p.wordText,
                wordTranslation = p.wordTranslation
            )
        }
    }
}
