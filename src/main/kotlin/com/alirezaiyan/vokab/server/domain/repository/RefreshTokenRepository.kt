package com.alirezaiyan.vokab.server.domain.repository

import com.alirezaiyan.vokab.server.domain.entity.RefreshToken
import com.alirezaiyan.vokab.server.domain.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface RefreshTokenRepository : JpaRepository<RefreshToken, Long> {
    fun findByTokenHash(tokenHash: String): Optional<RefreshToken>
    fun findByUser(user: User): List<RefreshToken>
    fun findByUserAndRevokedFalse(user: User): List<RefreshToken>

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user")
    fun revokeAllByUser(user: User): Int

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.tokenHash = :tokenHash")
    fun revokeByTokenHash(tokenHash: String): Int

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    fun deleteExpiredTokens(now: Instant): Int
}

