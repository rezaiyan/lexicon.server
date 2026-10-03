package com.alirezaiyan.vokab.server

import com.alirezaiyan.vokab.server.domain.entity.User
import com.alirezaiyan.vokab.server.domain.repository.ReviewEventRepository
import com.alirezaiyan.vokab.server.domain.repository.StudySessionRepository
import com.alirezaiyan.vokab.server.domain.repository.UserRepository
import com.alirezaiyan.vokab.server.domain.repository.WordRushGameRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Helper that persists a User in its own committed transaction, so that
 * tests using @Transactional can call AnalyticsSessionPersister.saveSession()
 * (which uses REQUIRES_NEW) without hitting FK constraint violations from
 * uncommitted test-transaction data.
 *
 * clearUserSessions() also runs in REQUIRES_NEW so its deletions are committed
 * and visible to subsequent read-only service calls (which set Hibernate flush
 * mode to MANUAL and would not see uncommitted pending deletions).
 */
@Component
class TestUserHelper(
    private val userRepository: UserRepository,
    private val studySessionRepository: StudySessionRepository,
    private val reviewEventRepository: ReviewEventRepository,
    private val wordRushGameRepository: WordRushGameRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun saveAndCommit(user: User): User = userRepository.save(user)

    /** Deletes all study sessions and their review_events (no FK cascade) for the given user. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun clearUserSessions(userId: Long) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        reviewEventRepository.deleteAll(reviewEventRepository.findByUser(user))
        studySessionRepository.deleteAll(studySessionRepository.findByUser(user))
    }

    /** Deletes all Word Rush games for the given user. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun clearUserWordRushGames(userId: Long) {
        val user = userRepository.findById(userId).orElse(null) ?: return
        wordRushGameRepository.deleteAll(wordRushGameRepository.findByUser(user))
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun deleteByEmail(email: String) = userRepository.deleteAll(userRepository.findAll().filter { it.email == email })
}
