package com.alirezaiyan.vokab.server.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserPlatformRepository : JpaRepository<UserPlatform, Long> {
    fun findByUser(user: User): List<UserPlatform>

    /**
     * First half of an upsert: creates the (user, platform) row unless it exists. Always follow
     * with [touchPlatform]. Split from `ON CONFLICT ... DO UPDATE` so the SQL also runs on H2
     * (dev profile); `DO NOTHING` + UPDATE is equally race-safe in PostgreSQL.
     */
    @Modifying
    @Query(
        """
        INSERT INTO user_platforms (user_id, platform, first_seen_at, last_seen_at, app_version)
        VALUES (:userId, :platform, NOW(), NOW(), :appVersion)
        ON CONFLICT DO NOTHING
        """,
        nativeQuery = true
    )
    fun insertPlatformIfAbsent(
        @Param("userId") userId: Long,
        @Param("platform") platform: String,
        @Param("appVersion") appVersion: String?
    ): Int

    /** Second half of the upsert: records the latest sighting and app version. */
    @Modifying
    @Query(
        """
        UPDATE user_platforms SET last_seen_at = NOW(), app_version = :appVersion
        WHERE user_id = :userId AND platform = :platform
        """,
        nativeQuery = true
    )
    fun touchPlatform(
        @Param("userId") userId: Long,
        @Param("platform") platform: String,
        @Param("appVersion") appVersion: String?
    ): Int
}
