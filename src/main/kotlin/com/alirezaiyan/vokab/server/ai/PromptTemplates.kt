package com.alirezaiyan.vokab.server.ai

import org.springframework.stereotype.Component

/**
 * The versioned prompt templates under `resources/prompts/`. Changing a prompt's wording means a
 * new file version (`.v2.txt`) and pointing the entry at it, so the old wording stays reviewable.
 */
enum class Prompt(val resource: String) {
    IMAGE_EXTRACTION("image-extraction.v1"),
    IMAGE_EXTRACTION_V2("image-extraction.v2"),
    CELEBRATION_INSIGHT("celebration-insight.v1"),
    DAILY_INSIGHT("daily-insight.v1"),
    MILESTONE_MESSAGE("milestone-message.v1"),
    STREAK_REMINDER("streak-reminder.v1"),
    TRANSLATE("translate.v1"),
    SUGGEST_VOCABULARY("suggest-vocabulary.v1"),
    NOTIFICATION_ADVICE("notification-advice.v1"),
}

/**
 * Renders [Prompt] templates. `{{name}}` is replaced in a single pass, so values (user text
 * included) are never re-scanned. A line holding only `{{name}}` is a block: dropped when the
 * value is empty, otherwise replaced by the (possibly multi-line) value. Trailing whitespace is
 * trimmed.
 *
 * Every template is loaded at startup, so a missing file fails the boot, not a request.
 */
@Component
class PromptTemplates {

    private val templates: Map<Prompt, String> = Prompt.entries.associateWith(::load)

    /** @throws IllegalArgumentException if [values] misses a placeholder or has one the template doesn't use */
    fun render(prompt: Prompt, values: Map<String, Any>): String {
        val template = templates.getValue(prompt)
        val placeholders = PLACEHOLDER.findAll(template).map { it.groupValues[1] }.toSet()
        require(placeholders == values.keys) {
            "Prompt ${prompt.resource}: missing ${placeholders - values.keys}, unused ${values.keys - placeholders}"
        }
        return template.lines()
            .mapNotNull { line ->
                val block = PLACEHOLDER.matchEntire(line)?.groupValues?.get(1)
                if (block != null) {
                    values.getValue(block).toString().ifEmpty { null }
                } else {
                    PLACEHOLDER.replace(line) { values.getValue(it.groupValues[1]).toString() }
                }
            }
            .joinToString("\n")
            .trimEnd()
    }

    private fun load(prompt: Prompt): String {
        val path = "/prompts/${prompt.resource}.txt"
        val stream = checkNotNull(javaClass.getResourceAsStream(path)) { "Missing prompt template $path" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{(\w+)}}""")
    }
}
