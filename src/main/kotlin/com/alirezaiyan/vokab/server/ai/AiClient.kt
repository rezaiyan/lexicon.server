package com.alirezaiyan.vokab.server.ai

import com.alirezaiyan.vokab.server.shared.UpstreamServiceException

/** What a model call is for; [tag] labels the `ai.requests` metric and log lines. */
enum class AiOperation(val tag: String) {
    IMAGE_EXTRACTION("image extraction"),
    CELEBRATION_INSIGHT("celebration insight"),
    STREAK_RESET_WARNING("streak reset warning"),
    DAILY_INSIGHT("daily insight"),
    MILESTONE_MESSAGE("milestone message"),
    STREAK_REMINDER("streak reminder"),
    TRANSLATION("translation"),
    SUGGEST_VOCABULARY("suggest vocabulary"),
    NOTIFICATION_ADVICE("notification advice"),
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
