package com.alirezaiyan.vokab.server.words

import com.alirezaiyan.vokab.server.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant

interface TagRepository : JpaRepository<Tag, Long> {

    fun findAllByUser(user: User): List<Tag>

    fun findByIdAndUser(id: Long, user: User): Tag?

    fun existsByUserAndName(user: User, name: String): Boolean

    /** Returns all tags belonging to the given user filtered by the provided IDs. Ownership-safe single query. */
    fun findAllByUserAndIdIn(user: User, ids: List<Long>): List<Tag>

    /** Returns the number of words associated with the given tag. */
    @Query("SELECT COUNT(w) FROM Tag t JOIN t.words w WHERE t.id = :tagId")
    fun countWordsByTagId(tagId: Long): Long

    /** Returns tag with word count for each tag owned by the user. */
    @Query("""
        SELECT t, COUNT(w)
        FROM Tag t
        LEFT JOIN t.words w
        WHERE t.user = :user
        GROUP BY t.id, t.name, t.createdAt, t.updatedAt, t.user
    """)
    fun findAllWithWordCountByUser(user: User): List<Array<Any>>

    /** Bumps `updated_at` on the user's words carrying [tagId], so incremental pulls see the tag change. */
    @Modifying
    @Query(
        "UPDATE words SET updated_at = :now WHERE user_id = :userId " +
            "AND id IN (SELECT word_id FROM word_tags WHERE tag_id = :tagId)",
        nativeQuery = true
    )
    fun touchWordsByTagIdAndUserId(tagId: Long, userId: Long, now: Instant): Int
}
