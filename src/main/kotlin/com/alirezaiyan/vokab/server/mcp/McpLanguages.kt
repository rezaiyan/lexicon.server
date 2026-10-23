package com.alirezaiyan.vokab.server.mcp

/**
 * Language codes the app understands. The app maps any other code to English, so tools must reject
 * them instead of storing a word under the wrong language. Keep in sync with the app's
 * `utils.Language` enum.
 */
internal object McpLanguages {
    val SUPPORTED = setOf(
        "en", "fa", "de", "es", "fr", "it", "pt", "ru", "zh", "ja", "ko", "ar", "tr", "nl", "hi",
    )

    /** The normalized code, or a failure the AI client can act on. */
    fun require(code: String, field: String): String {
        val normalized = code.trim().lowercase()
        require(normalized in SUPPORTED) {
            "$field \"$code\" isn't supported. Use one of: ${SUPPORTED.joinToString(", ")}"
        }
        return normalized
    }
}
