package com.alirezaiyan.vokab.server.auth

import com.alirezaiyan.vokab.server.auth.PasskeyChallenges.Ceremony
import com.alirezaiyan.vokab.server.shared.AppProperties
import com.alirezaiyan.vokab.server.shared.AuthRejectedException
import com.alirezaiyan.vokab.server.user.User
import com.alirezaiyan.vokab.server.user.UserRepository
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.AuthenticationParameters
import com.webauthn4j.data.AuthenticatorTransport
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.RegistrationParameters
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.server.ServerProperty
import com.webauthn4j.verifier.exception.VerificationException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant

private val logger = KotlinLogging.logger {}

private const val USER_HANDLE_BYTES = 32

/** ES256 first (every platform authenticator), RS256 for Windows Hello and older security keys. */
private val SUPPORTED_ALGORITHMS = listOf(COSEAlgorithmIdentifier.ES256, COSEAlgorithmIdentifier.RS256)

/**
 * Passkeys as an extra sign-in for an existing account: a signed-in user creates one, then signs in
 * with it later without Google or Apple. The device shows the passkey sheet; this service creates
 * the WebAuthn options and verifies what comes back.
 *
 * Every challenge is single use and bound to its ceremony (and, for creation, to the user), so a
 * response can't be replayed or used for another account. Attestation is not requested: Lexicon
 * doesn't restrict which credential provider holds the passkey.
 */
@Service
class PasskeyService(
    private val passkeyRepository: PasskeyRepository,
    private val userRepository: UserRepository,
    private val challenges: PasskeyChallenges,
    private val appProperties: AppProperties,
    private val clock: Clock,
) {
    private val webAuthn = WebAuthnManager.createNonStrictWebAuthnManager()
    private val attestedDataConverter = AttestedCredentialDataConverter(ObjectConverter())
    private val random = SecureRandom()

    private val config get() = appProperties.passkey
    private val timeoutMs get() = config.challengeTtlSeconds * 1000

    /** Options for creating a passkey for [userId]; lists their passkeys so a device doesn't get a second one. */
    @Transactional(readOnly = true)
    fun registrationOptions(userId: Long): PasskeyCreationOptions {
        val user = userRepository.findById(userId).orElseThrow { NoSuchElementException("User not found") }
        val existing = passkeyRepository.findAllByUserId(userId)
        // One handle per user: credential providers then treat a new passkey as the same account
        val userHandle = existing.firstOrNull()?.userHandle
            ?: base64Url(ByteArray(USER_HANDLE_BYTES).also(random::nextBytes))
        return PasskeyCreationOptions(
            rp = PasskeyCreationOptions.RelyingParty(id = config.rpId, name = config.rpName),
            user = PasskeyCreationOptions.PasskeyUser(id = userHandle, name = user.email, displayName = user.name),
            challenge = challenges.issue(Ceremony.REGISTRATION, userId, userHandle),
            pubKeyCredParams = SUPPORTED_ALGORITHMS.map { PasskeyCreationOptions.CredentialParameter(alg = it.value) },
            timeout = timeoutMs,
            excludeCredentials = existing.map { it.descriptor() },
            authenticatorSelection = PasskeyCreationOptions.AuthenticatorSelection(),
        )
    }

    /**
     * Verifies a passkey creation response for [userId] and stores the passkey.
     *
     * @throws IllegalArgumentException if the response is malformed, its challenge is unknown, used,
     *   expired or not [userId]'s, verification fails, or the passkey is already registered
     */
    @Transactional
    fun register(userId: Long, responseJson: String): PasskeyDto {
        val parsed = parse("registration") { webAuthn.parseRegistrationResponseJSON(responseJson) }
        val registration = requireNotNull(parsed) { "Invalid passkey response" }
        val challenge = registration.collectedClientData?.challenge?.value
        val pending = requireNotNull(challenges.consume(challenge, Ceremony.REGISTRATION, userId)) {
            "Passkey request expired, please try again"
        }
        val parameters = RegistrationParameters(
            serverProperty(requireNotNull(challenge)), credentialParameters(), true, true,
        )
        require(verifies("registration for userId=$userId") { webAuthn.verify(registration, parameters) }) {
            "Passkey could not be verified"
        }

        val authenticatorData = requireNotNull(registration.attestationObject).authenticatorData
        val attested = requireNotNull(authenticatorData.attestedCredentialData)
        val credentialId = base64Url(attested.credentialId)
        require(!passkeyRepository.existsByCredentialId(credentialId)) { "This passkey is already registered" }

        val passkey = passkeyRepository.save(
            Passkey(
                user = userRepository.getReferenceById(userId),
                credentialId = credentialId,
                userHandle = requireNotNull(pending.userHandle),
                attestedCredentialData = attestedDataConverter.convert(attested),
                signCount = authenticatorData.signCount,
                uvInitialized = authenticatorData.isFlagUV,
                backupEligible = authenticatorData.isFlagBE,
                backupState = authenticatorData.isFlagBS,
                transports = registration.transports?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.value },
                createdAt = Instant.now(clock),
            )
        )
        logger.info { "Passkey registered for userId=$userId (synced=${passkey.backupState})" }
        return passkey.toDto()
    }

    /** Options for signing in with any passkey of this relying party; the device lets the user pick one. */
    fun signInOptions(): PasskeyRequestOptions = PasskeyRequestOptions(
        challenge = challenges.issue(Ceremony.SIGN_IN),
        rpId = config.rpId,
        timeout = timeoutMs,
    )

    /**
     * Verifies a passkey sign-in response and records the use. Runs inside the caller's sign-in
     * transaction.
     *
     * @return the account the passkey belongs to
     * @throws AuthRejectedException if the response is malformed, its challenge is unknown, used or
     *   expired, the passkey is unknown (deleted on another device) or the signature doesn't verify
     */
    fun verifySignIn(responseJson: String): User {
        val assertion = parse("sign-in") { webAuthn.parseAuthenticationResponseJSON(responseJson) }
            .orReject("Invalid passkey response")
        val challenge = assertion.collectedClientData?.challenge?.value
        challenges.consume(challenge, Ceremony.SIGN_IN).orReject("Passkey sign-in expired, please try again")

        val credentialId = assertion.credentialId.orReject("Invalid passkey response")
        val passkey = passkeyRepository.findByCredentialIdWithUser(base64Url(credentialId))
            .orReject("This passkey is no longer linked to an account")
        val userHandle = assertion.userHandle
        rejectUnless(userHandle == null || base64Url(userHandle) == passkey.userHandle, "Invalid passkey response") {
            "Passkey sign-in rejected: user handle does not match passkey ${passkey.id}"
        }
        val parameters = AuthenticationParameters(
            serverProperty(requireNotNull(challenge)), passkey.toRecord(), null, true, true,
        )
        rejectUnless(
            verifies("sign-in with passkey ${passkey.id}") { webAuthn.verify(assertion, parameters) },
            "Passkey could not be verified",
        )

        val authenticatorData = requireNotNull(assertion.authenticatorData)
        passkey.signCount = authenticatorData.signCount
        passkey.backupState = authenticatorData.isFlagBS
        passkey.uvInitialized = passkey.uvInitialized || authenticatorData.isFlagUV
        passkey.lastUsedAt = Instant.now(clock)
        passkeyRepository.save(passkey)
        return passkey.user
    }

    @Transactional(readOnly = true)
    fun list(userId: Long): List<PasskeyDto> = passkeyRepository.findAllByUserId(userId).map { it.toDto() }

    /**
     * Deletes one of [userId]'s passkeys. The copy in the credential provider stays until the user
     * removes it there; signing in with it then fails as unknown.
     *
     * @throws NoSuchElementException if [userId] has no passkey [passkeyId]
     */
    @Transactional
    fun delete(userId: Long, passkeyId: Long) {
        val passkey = passkeyRepository.findById(passkeyId).orElse(null)
            ?.takeIf { it.user.id == userId }
            ?: throw NoSuchElementException("Passkey not found")
        passkeyRepository.delete(passkey)
        logger.info { "Passkey $passkeyId deleted by userId=$userId" }
    }

    private fun serverProperty(challenge: ByteArray): ServerProperty = ServerProperty.builder()
        .origins(config.originSet.map(Origin::create).toSet())
        .rpId(config.rpId)
        .challenge(DefaultChallenge(challenge))
        .build()

    private fun credentialParameters() =
        SUPPORTED_ALGORITHMS.map { PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, it) }

    private fun Passkey.transportSet(): Set<AuthenticatorTransport>? =
        transports?.split(",")?.map(AuthenticatorTransport::create)?.toSet()

    private fun Passkey.descriptor() = CredentialDescriptor(
        id = credentialId,
        transports = transportSet().orEmpty().map { it.value },
    )

    private fun Passkey.toRecord() = CredentialRecordImpl(
        NoneAttestationStatement(),
        uvInitialized,
        backupEligible,
        backupState,
        signCount,
        attestedDataConverter.convert(attestedCredentialData),
        null,
        null,
        null,
        transportSet(),
    )
}

/**
 * Parses a device response, or null when it is unreadable. Besides DataConversionException,
 * webauthn4j throws NullPointerException / IllegalArgumentException for missing fields.
 */
@Suppress("TooGenericExceptionCaught") // see above: the parser has no single failure type
private fun <T> parse(ceremony: String, parser: () -> T): T? = try {
    parser()
} catch (e: RuntimeException) {
    logger.warn { "Passkey $ceremony rejected: unreadable response (${e.javaClass.simpleName})" }
    null
}

/** Runs a webauthn4j verification; false (logged) when the response fails it. */
private fun verifies(what: String, verification: () -> Unit): Boolean = try {
    verification()
    true
} catch (e: VerificationException) {
    logger.warn { "Passkey $what rejected: ${e.javaClass.simpleName}" }
    false
}

private fun <T : Any> T?.orReject(message: String): T = this ?: throw AuthRejectedException(message)

private fun rejectUnless(condition: Boolean, message: String, logLine: (() -> String)? = null) {
    if (condition) return
    logLine?.let { logger.warn(it) }
    throw AuthRejectedException(message)
}
