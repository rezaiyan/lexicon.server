package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
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

    @Query("""
        SELECT w.translation FROM Word w
        WHERE w.user = :user AND LOWER(w.targetLanguage) = LOWER(:targetLanguage)
        AND TRIM(w.translation) <> ''
    """)
    fun findTranslationsByUserAndTargetLanguage(user: User, targetLanguage: String): List<String>

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
