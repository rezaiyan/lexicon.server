package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.user.User
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    /** Row-locked lookup for rotation, so two concurrent exchanges of one token are serialized. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :tokenHash")
    fun findForRotationByTokenHash(tokenHash: String): Optional<RefreshToken>

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.user = :user")
    fun revokeAllByUser(user: User): Int

    /** Revokes the token only if it belongs to [userId], so one user can't revoke another's session. */
    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.tokenHash = :tokenHash AND r.user.id = :userId")
    fun revokeByTokenHash(tokenHash: String, userId: Long): Int

    @Modifying
    @Query("UPDATE RefreshToken r SET r.revoked = true WHERE r.familyId = :familyId")
    fun revokeFamily(familyId: String): Int

    @Modifying
    @Query("DELETE FROM RefreshToken r WHERE r.expiresAt < :now")
    fun deleteExpiredTokens(now: Instant): Int
}
