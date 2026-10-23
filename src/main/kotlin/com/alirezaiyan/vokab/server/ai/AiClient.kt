package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.UpstreamServiceException

/**
 * Output cap for list answers: a dense page photo or a 150-item suggestion list (plus headroom) at
 * ~40 tokens per item in non-Latin scripts still fits.
 */
private const val LIST_MAX_TOKENS = 8192

/** Comeback rescue: word picks with reasons, several candidate pushes and two outros, as JSON. */
private const val RESCUE_MAX_TOKENS = 2048

/**
 * What a model call is for; [tag] labels the `ai.requests` metric and log lines. A [judge] call
 * checks another call's output and runs on `app.openrouter.judge-model` when that is set.
 * [maxTokens] caps the answer; null means `app.openrouter.max-tokens` (short answers).
 */
enum class AiOperation(val tag: String, val judge: Boolean = false, val maxTokens: Int? = null) {
    IMAGE_EXTRACTION("image extraction", maxTokens = LIST_MAX_TOKENS),
    CELEBRATION_INSIGHT("celebration insight"),
    STREAK_RESET_WARNING("streak reset warning"),
    DAILY_INSIGHT("daily insight"),
    MILESTONE_MESSAGE("milestone message"),
    STREAK_REMINDER("streak reminder"),
    TRANSLATION("translation"),
    SUGGEST_VOCABULARY("suggest vocabulary", maxTokens = LIST_MAX_TOKENS),
    NOTIFICATION_ADVICE("notification advice"),
    COMEBACK_RESCUE("comeback rescue", maxTokens = RESCUE_MAX_TOKENS),
    COMEBACK_RESCUE_JUDGE("comeback rescue judge", judge = true),
    INSTRUCTION_GUARD("instruction guard", judge = true),
}

/** One-shot chat completion with the configured model. */
interface AiClient {

    /**
     * Sends [prompt] as a single user message; the trimmed answer, or `null` when the model
     * returned no text.
     *
     * @throws UpstreamServiceException on HTTP, transport or API errors
     */
    fun complete(prompt: String, operation: AiOperation): String?

    /** As [complete], with a JPEG image (base64) placed before the prompt. */
    fun completeWithImage(jpegBase64: String, prompt: String, operation: AiOperation): String?
}
