package com.alirezaiyan.vokab.server.study

import org.springframework.stereotype.Component
import kotlin.math.abs

@Component
class AliasGenerator {

    private val adjectives = listOf(
        "Swift", "Bright", "Bold", "Keen", "Calm",
        "Quick", "Sharp", "Wise", "Eager", "Brave",
        "Steady", "Clever", "Noble", "Vivid", "Lucky"
    )

    private val nouns = listOf(
        "Learner", "Owl", "Fox", "Scholar", "Voyager",
        "Falcon", "Phoenix", "Pioneer", "Sage", "Spark",
        "Rover", "Crest", "Petal", "Star", "Wave"
    )

    fun generate(userId: Long): String {
        val hash = abs(userId.hashCode())
        val adjective = adjectives[hash % adjectives.size]
        val noun = nouns[(hash / adjectives.size) % nouns.size]
        val suffix = (hash % 10000).toString().padStart(4, '0')
        return "$adjective$noun$suffix"
    }
}
