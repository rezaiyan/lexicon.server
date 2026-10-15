package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface ProgressRow {
    fun getLevel(): Int
    fun getWordCount(): Long
    fun getDueCount(): Long
}

interface WordRepository : JpaRepository<Word, Long> {
    fun findAllByUser(user: User): List<Word>

    fun findAllByUserAndIdIn(user: User, ids: List<Long>): List<Word>

    /**
     * Ids of a user's words matching an optional text pattern (word or translation, lower-cased LIKE),
     * learning language and tag. Empty [language] and 0 [tagId] mean "any".
     */
    @Query(
        """
        SELECT w.id FROM Word w
        WHERE w.user.id = :userId
          AND w.id > :afterId
          AND $SEARCH_FILTER
        ORDER BY w.id
        """
    )
    fun searchIds(
        userId: Long, pattern: String, language: String, tagId: Long, afterId: Long, page: Pageable,
    ): List<Long>

    /** [searchIds], newest first; [beforeId] 0 starts from the newest. */
    @Query(
        """
        SELECT w.id FROM Word w
        WHERE w.user.id = :userId
          AND (:beforeId = 0 OR w.id < :beforeId)
          AND $SEARCH_FILTER
        ORDER BY w.id DESC
        """
    )
    fun searchIdsNewestFirst(
        userId: Long, pattern: String, language: String, tagId: Long, beforeId: Long, page: Pageable,
    ): List<Long>

    @Query(
        "SELECT w.id FROM Word w WHERE w.user.id = :userId AND w.nextReviewDate <= :nowMs " +
            "ORDER BY w.nextReviewDate, w.id"
    )
    fun findDueIds(userId: Long, nowMs: Long, page: Pageable): List<Long>

    @Query("SELECT COUNT(w) FROM Word w WHERE w.user.id = :userId AND w.nextReviewDate <= :nowMs")
    fun countDue(userId: Long, nowMs: Long): Long

    @Query("SELECT DISTINCT w FROM Word w LEFT JOIN FETCH w.tags WHERE w.id IN :ids")
    fun findAllByIdInWithTags(ids: List<Long>): List<Word>

    @Query("SELECT DISTINCT w FROM Word w LEFT JOIN FETCH w.tags WHERE w.user = :user")
    fun findAllByUserWithTags(user: User): List<Word>

    @Query("""
        SELECT DISTINCT w FROM Word w
        LEFT JOIN FETCH w.tags
        WHERE w.user = :user AND w.updatedAt > :since
    """)
    fun findAllByUserAndUpdatedAtAfterWithTags(user: User, since: Instant): List<Word>

    @Query("""
        SELECT w.level AS level,
               COUNT(w) AS wordCount,
               SUM(CASE WHEN w.nextReviewDate <= :nowMs THEN 1L ELSE 0L END) AS dueCount
        FROM Word w
        WHERE w.user.id = :userId
        GROUP BY w.level
    """)
    fun findProgressRowsByUserId(userId: Long, nowMs: Long): List<ProgressRow>

    /** Terms (originalWord, in the learning language) the user already has in [targetLanguage]. */
    @Query("""
        SELECT w.originalWord FROM Word w
        WHERE w.user = :user AND LOWER(w.targetLanguage) = LOWER(:targetLanguage)
        AND TRIM(w.originalWord) <> ''
    """)
    fun findTermsByUserAndTargetLanguage(user: User, targetLanguage: String): List<String>

    @Query(
        "SELECT w.user.id, COUNT(w) FROM Word w " +
            "WHERE w.level = 6 AND w.user.id IN :userIds GROUP BY w.user.id"
    )
    fun countMasteredWordsByUserIds(userIds: List<Long>): List<Array<Any>>

    @Query("SELECT COUNT(w) FROM Word w WHERE w.level = 6 AND w.user.id = :userId")
    fun countMasteredWordsByUserId(userId: Long): Long

    @Query(
        "SELECT w.sourceLanguage, w.targetLanguage, COUNT(w) " +
            "FROM Word w WHERE w.user = :user " +
            "GROUP BY w.sourceLanguage, w.targetLanguage"
    )
    fun findLanguagePairsWithCount(user: User): List<Array<Any>>

    @Modifying
    @Query("DELETE FROM Word w WHERE w.id = :id AND w.user.id = :userId")
    fun deleteByIdAndUserId(id: Long, userId: Long): Int

    @Modifying
    @Query("DELETE FROM Word w WHERE w.id IN :ids AND w.user.id = :userId")
    fun deleteAllByIdInAndUserId(ids: List<Long>, userId: Long): Int

    @Query("SELECT w.id FROM Word w WHERE w.id IN :ids AND w.user.id = :userId")
    fun findOwnedIds(ids: Collection<Long>, userId: Long): List<Long>

    /** Marks the words changed for incremental pulls when an edit (e.g. tags) doesn't touch the row itself. */
    @Modifying
    @Query("UPDATE Word w SET w.updatedAt = :now WHERE w.id IN :ids AND w.user.id = :userId")
    fun touchByIdInAndUserId(ids: List<Long>, userId: Long, now: Instant): Int

    @Modifying
    @Query(
        "UPDATE Word w SET w.sourceLanguage = :sourceLanguage, w.targetLanguage = :targetLanguage, " +
            "w.updatedAt = :now WHERE w.id IN :ids AND w.user.id = :userId"
    )
    fun updateLanguagesByIdInAndUserId(
        ids: List<Long>,
        userId: Long,
        sourceLanguage: String,
        targetLanguage: String,
        now: Instant,
    ): Int

    @Modifying
    @Query(
        "UPDATE Word w SET w.sourceLanguage = :sourceLanguage, " +
            "w.updatedAt = :now WHERE w.id IN :ids AND w.user.id = :userId"
    )
    fun updateSourceLanguageByIdInAndUserId(
        ids: List<Long>,
        userId: Long,
        sourceLanguage: String,
        now: Instant,
    ): Int

    @Modifying
    @Query(
        "UPDATE Word w SET w.targetLanguage = :targetLanguage, " +
            "w.updatedAt = :now WHERE w.id IN :ids AND w.user.id = :userId"
    )
    fun updateTargetLanguageByIdInAndUserId(
        ids: List<Long>,
        userId: Long,
        targetLanguage: String,
        now: Instant,
    ): Int

    @Modifying
    @Query(
        "DELETE FROM word_tags WHERE word_id IN :wordIds " +
            "AND word_id IN (SELECT id FROM words WHERE user_id = :userId)",
        nativeQuery = true
    )
    fun deleteWordTagsByWordIdsAndUserId(wordIds: List<Long>, userId: Long)

    @Modifying
    @Query(
        """
        INSERT INTO word_tags (word_id, tag_id)
        SELECT w.id, t.id
        FROM words w
        CROSS JOIN tags t
        WHERE w.id IN :wordIds
          AND w.user_id = :userId
          AND t.id IN :tagIds
        ON CONFLICT DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertWordTagsBulkByWordIdsAndUserId(wordIds: List<Long>, tagIds: List<Long>, userId: Long)
}

/** Shared WHERE clause of the word searches (JPQL); empty pattern/language and tag 0 mean "any". */
private const val SEARCH_FILTER = """(:pattern = '%'
               OR LOWER(w.originalWord) LIKE :pattern ESCAPE '#'
               OR LOWER(w.translation) LIKE :pattern ESCAPE '#')
          AND (:language = '' OR LOWER(w.targetLanguage) = :language)
          AND (:tagId = 0 OR EXISTS (SELECT 1 FROM w.tags t WHERE t.id = :tagId))"""
