package com.alirezaiyan.vokab.server.ai

private const val MAX_LABEL_LENGTH = 40
private const val LABEL_PUNCTUATION = " ()'&,/-"
private val WHITESPACE = Regex("""\s+""")

/**
 * A short client-supplied label (language name, level, topic) as it may appear inside a prompt:
 * letters, digits, spaces and a little punctuation, single line, at most 40 characters.
 *
 * These labels reach generation prompts and the instruction judge unfenced, so nothing that could
 * start a new line, a role ("system:") or a fence survives. Real labels ("Chinese (Simplified)",
 * "Daily Life") pass through unchanged.
 */
fun String.asPromptLabel(): String =
    filter { it.isLetterOrDigit() || it in LABEL_PUNCTUATION || it.isWhitespace() }
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_LABEL_LENGTH)
