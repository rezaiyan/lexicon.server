package com.alirezaiyan.vokab.server.auth

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** A passkey response from the device (the JSON the platform passkey API returns), passed on as is. */
data class PasskeyResponseRequest(
    @field:NotBlank(message = "Passkey response is required")
    @field:Size(max = 65_536, message = "Passkey response is too large")
    val responseJson: String,
)

/** WebAuthn `PublicKeyCredentialCreationOptions` (JSON form) for creating a passkey. */
data class PasskeyCreationOptions(
    val rp: RelyingParty,
    val user: PasskeyUser,
    val challenge: String,
    val pubKeyCredParams: List<CredentialParameter>,
    val timeout: Long,
    val excludeCredentials: List<CredentialDescriptor>,
    val authenticatorSelection: AuthenticatorSelection,
    val attestation: String = "none",
) {
    data class RelyingParty(val id: String, val name: String)

    /** [id] is the base64url user handle, never the account id or email. */
    data class PasskeyUser(val id: String, val name: String, val displayName: String)

    data class CredentialParameter(val alg: Long, val type: String = "public-key")

    data class AuthenticatorSelection(
        val residentKey: String = "required",
        val requireResidentKey: Boolean = true,
        val userVerification: String = "required",
    )
}

/** WebAuthn `PublicKeyCredentialRequestOptions` (JSON form) for signing in with any of the user's passkeys. */
data class PasskeyRequestOptions(
    val challenge: String,
    val rpId: String,
    val timeout: Long,
    val userVerification: String = "required",
    /** Empty: the user picks a discoverable passkey, so the server needs no username first. */
    val allowCredentials: List<CredentialDescriptor> = emptyList(),
)

data class CredentialDescriptor(val id: String, val transports: List<String>, val type: String = "public-key")

/**
 * The user's passkeys, with what the app needs to tell the device's credential provider which ones
 * still exist (WebAuthn signals), so deleted passkeys stop showing up in the sign-in sheet, and
 * which account label they show.
 */
data class PasskeyListDto(
    val rpId: String,
    /** Base64url user handle shared by all of the user's passkeys; null when they have none. */
    val userHandle: String?,
    /** The account label the device's passkey picker shows ([passkeyAccountName]). */
    val accountName: String,
    val accountDisplayName: String,
    val passkeys: List<PasskeyDto>,
)

/** A passkey as the account screen lists it. */
data class PasskeyDto(
    val id: Long,
    /** Base64url WebAuthn credential id, as the device knows the passkey. */
    val credentialId: String,
    /** Epoch millis. */
    val createdAt: Long,
    /** Epoch millis; null until the passkey is first used to sign in. */
    val lastUsedAt: Long?,
    /** Backed up by the credential provider (iCloud Keychain, Google Password Manager), so it survives a lost phone. */
    val synced: Boolean,
)

internal fun Passkey.toDto() = PasskeyDto(
    id = requireNotNull(id),
    credentialId = credentialId,
    createdAt = createdAt.toEpochMilli(),
    lastUsedAt = lastUsedAt?.toEpochMilli(),
    synced = backupState,
)
