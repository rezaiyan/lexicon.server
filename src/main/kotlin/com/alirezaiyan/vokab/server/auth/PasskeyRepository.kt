package com.alirezaiyan.vokab.server.auth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface PasskeyRepository : JpaRepository<Passkey, Long> {
    @Query("SELECT p FROM Passkey p JOIN FETCH p.user WHERE p.credentialId = :credentialId")
    fun findByCredentialIdWithUser(credentialId: String): Passkey?

    @Query("SELECT p FROM Passkey p WHERE p.user.id = :userId ORDER BY p.createdAt")
    fun findAllByUserId(userId: Long): List<Passkey>

    fun existsByCredentialId(credentialId: String): Boolean
}
