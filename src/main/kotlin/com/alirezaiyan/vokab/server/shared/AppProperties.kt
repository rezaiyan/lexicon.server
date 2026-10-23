package com.alirezaiyan.vokab.server.shared

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "app")
data class AppProperties(
    var jwt: JwtConfig = JwtConfig(),
    var firebase: FirebaseAppConfig = FirebaseAppConfig(),
    var openrouter: OpenRouterConfig = OpenRouterConfig(),
    var apple: AppleConfig = AppleConfig(),
    var revenuecat: RevenueCatConfig = RevenueCatConfig(),
    var cors: CorsConfig = CorsConfig(),
    var features: FeatureFlagsConfig = FeatureFlagsConfig(),
    var security: SecurityConfig = SecurityConfig(),
    var logging: LoggingConfig = LoggingConfig(),
    var vocabulary: VocabularyConfig = VocabularyConfig(),
    var ciAuth: CiAuthConfig = CiAuthConfig(),
    var notifications: NotificationsConfig = NotificationsConfig(),
    var geolocation: GeoLocationConfig = GeoLocationConfig(),
    var rateLimit: RateLimitProperties = RateLimitProperties(),
    var aiInstructions: AiInstructionsConfig = AiInstructionsConfig(),
    var mcp: McpConfig = McpConfig(),
)

/** OAuth for MCP clients; only read while `spring.ai.mcp.server.enabled` is true. */
data class McpConfig(
    /** Public origin clients reach us at; the issuer of MCP tokens and base of every OAuth URL. */
    var issuer: String = "http://localhost:8080",
    /** RSA JWK (with private part) signing MCP tokens; created on first start. Never the app's JWT key. */
    var signingKeyPath: String = "./keys/mcp-signing-key.json",
    /** Short-lived access tokens; every client also gets a rotating refresh token. */
    var accessTokenMinutes: Long = 60,
    var refreshTokenDays: Long = 30,
    /**
     * Browser origins allowed to call `/mcp` besides our own (DNS-rebinding guard). Requests
     * without an Origin header (native and server-side clients) are unaffected.
     */
    var allowedOrigins: List<String> = emptyList(),
    /** Firebase web config for the sign-in page (the same public values the web app ships). */
    var firebaseWebApiKey: String = "",
    var firebaseAuthDomain: String = "",
    var firebaseProjectId: String = "",
)

data class JwtConfig(
    var expirationMs: Long = 900_000, // 15 minutes
    var refreshExpirationMs: Long = 7_776_000_000, // 90 days
    var refreshTokenGracePeriodMs: Long = 30_000, // 30 seconds grace period for rotated tokens
    var refreshFamilyMaxAgeMs: Long = 15_552_000_000, // 180 days: a session ends then, however often it refreshes
    var privateKeyPath: String = "",
    var publicKeyPath: String = "",
    var privateKey: String = "", // Base64-encoded RSA private key (for ephemeral deployments)
    var publicKey: String = "",  // Base64-encoded RSA public key (for ephemeral deployments)
    var issuer: String = "vokab-server",
    var audience: String = "vokab-client"
)

data class FirebaseAppConfig(
    var serviceAccountPath: String = ""
)

data class OpenRouterConfig(
    var apiKey: String = "",
    var baseUrl: String = "https://openrouter.ai/api/v1",
    var model: String = "anthropic/claude-haiku-4.5",
    /** Model for second-opinion checks (the rescue copy judge); blank = [model]. */
    var judgeModel: String = "",
    var timeoutSeconds: Long = 60,
    /**
     * `max_tokens` for calls whose answer is a few sentences or a small JSON object. Long answers
     * (photo extraction, vocabulary lists, rescue copy) have their own, larger cap in `AiOperation`.
     */
    var maxTokens: Int = 1024,
)

data class AppleConfig(
    /** Bundle / Services IDs accepted as the `aud` of Apple ID tokens (comma-separated). */
    var clientIds: String = "com.alirezaiyan.vokab"
) {
    val clientIdSet: Set<String>
        get() = clientIds.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

data class RevenueCatConfig(
    /** Static `Authorization` header value configured on the RevenueCat webhook. Required. */
    var webhookSecret: String = "",
    /** HMAC signing secret; when set, every delivery must carry a valid `X-RevenueCat-Webhook-Signature`. */
    var webhookSigningSecret: String = "",
    /** Max age/skew of the signature timestamp (`t=`), in seconds. */
    var webhookSignatureToleranceSeconds: Long = 300,
    var apiKey: String = ""
)

data class CorsConfig(
    var allowedOrigins: String = "http://localhost:3000"
)

data class FeatureFlagsConfig(
    var pushNotificationsEnabled: Boolean = true
)

data class SecurityConfig(
    var testEmails: String = "",  // Unused: deactivated users are always rejected
    var adminApiKey: String = ""  // Static key for X-Admin-Key header (CLI access to /admin/**)
)

data class LoggingConfig(
    var enabled: Boolean = true,
    // The Docker healthcheck hits /api/v1/health every 30s
    var excludePatterns: String = "/api/v1/health,/actuator/**,/h2-console/**",
)

/**
 * Vocabulary-related configuration.
 *
 * The suggestionCount value controls how many vocabulary items the AI
 * should generate for onboarding and suggest-vocabulary flows.
 * It can be overridden via environment variable:
 *
 *   APP_VOCABULARY_SUGGESTIONCOUNT=150
 */
data class VocabularyConfig(
    var suggestionCount: Int = 50
)

/**
 * Per-user call limits for the paid AI endpoints. Each limit resets in full every [windowMinutes].
 * Photo extraction has its own, smaller limit because each call costs more.
 */
data class RateLimitProperties(
    var windowMinutes: Long = 5,
    var photoCalls: Long = 20,
    var aiCalls: Long = 50,
    /** Sign-in attempts (Google, Apple, CI token) per IP per minute. */
    var authPerMinute: Long = 20,
    /** Token refreshes per IP per minute. */
    var refreshPerMinute: Long = 60,
    /** Unauthenticated onboarding AI calls across all IPs: a ceiling on that bill, not a per-user limit. */
    var onboardingGlobalPerHour: Long = 200,
    var onboardingGlobalPerDay: Long = 2000,
    /** Failed admin key attempts across all IPs per hour before admin key auth pauses for everyone. */
    var adminFailuresGlobalPerHour: Long = 50,
)

/** The learner-instruction guard (see `InstructionGuard`). */
data class AiInstructionsConfig(
    /** Refused instructions a user may have per [rejectionWindowMinutes] before instructions pause. */
    var maxRejections: Long = 5,
    var rejectionWindowMinutes: Long = 60,
    /** How long the guard waits for the judge before refusing as unavailable (fails closed). */
    var judgeTimeoutSeconds: Long = 8,
)

data class CiAuthConfig(
    var enabled: Boolean = false,
    var secret: String = "",
    var testEmail: String = "ci-maestro@test.vokab.dev"
)

data class NotificationsConfig(
    var admin: AdminConfig = AdminConfig()
) {
    data class AdminConfig(
        var enabled: Boolean = true,
        var telegram: TelegramConfig = TelegramConfig()
    )
}

data class TelegramConfig(
    var botToken: String = "",
    var chatId: String = "",
    /** Telegram echoes it in `X-Telegram-Bot-Api-Secret-Token`; blank rejects every webhook call. */
    var webhookSecret: String = ""
)

data class GeoLocationConfig(
    var databasePath: String = ""
)
