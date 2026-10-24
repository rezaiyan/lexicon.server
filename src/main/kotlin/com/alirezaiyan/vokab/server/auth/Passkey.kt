package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.shared.JpaEntity
import com.alirezaiyan.vokab.server.user.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/** A passkey a user registered; signing in with it proves possession of its private key. */
@Entity
@Table(
    name = "passkeys",
    indexes = [Index(name = "idx_passkeys_user_id", columnList = "user_id")]
)
@Suppress("LongParameterList") // one constructor parameter per column
class Passkey(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    override val id: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,

    /** Base64url credential id, as the authenticator returns it on sign-in. */
    @Column(name = "credential_id", nullable = false, unique = true, length = 1366, updatable = false)
    val credentialId: String,

    /** Base64url WebAuthn user handle; the same for every passkey of [user]. */
    @Column(name = "user_handle", nullable = false, length = 128, updatable = false)
    val userHandle: String,

    /** webauthn4j-encoded AAGUID, credential id and public key. */
    @Column(name = "attested_credential_data", nullable = false, updatable = false)
    val attestedCredentialData: ByteArray,

    @Column(name = "sign_count", nullable = false)
    var signCount: Long,

    @Column(name = "uv_initialized", nullable = false)
    var uvInitialized: Boolean,

    @Column(name = "backup_eligible", nullable = false, updatable = false)
    val backupEligible: Boolean,

    @Column(name = "backup_state", nullable = false)
    var backupState: Boolean,

    /** Comma-separated WebAuthn transports (internal, hybrid, ...), if the client reported them. */
    @Column(name = "transports", length = 255, updatable = false)
    val transports: String?,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant,
) : JpaEntity<Long>() {
    @Column(name = "last_used_at")
    var lastUsedAt: Instant? = null
}
