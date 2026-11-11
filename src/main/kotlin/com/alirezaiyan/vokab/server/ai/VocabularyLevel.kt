package com.alirezaiyan.vokab.server.ai

/**
 * The learner's level as the vocabulary prompt sees it. Each level carries its own CEFR band, what to
 * include, what to leave out and a matching example, so "intermediate" no longer gets A1 greetings.
 *
 * Clients send lowercase keys ("beginner", "intermediate", "advanced"); CEFR codes ("B1") work too.
 */
enum class VocabularyLevel(
    /** CEFR band, e.g. "B1-B2". */
    val cefr: String,
    /** What the words should be and what to leave out. */
    val guidance: String,
    /** Topics to spread the set over. */
    val topicMix: String,
    /** A few German lines that show the format at this level (German only to illustrate it). */
    val example: String,
) {
    BEGINNER(
        cefr = "A1-A2",
        guidance = "The learner is starting out. Choose high-frequency core vocabulary: greetings and " +
            "courtesy phrases, numbers, days and time, family, food and drink, basic verbs, simple " +
            "adjectives and survival phrases.",
        topicMix = "greetings, numbers, time, family, food, travel, work, emotions, actions, places and " +
            "common expressions",
        example = "Hallo,hello,a greeting\n" +
            "Guten Morgen,good morning,formal morning greeting\n" +
            "danke,thank you,\n" +
            "Brot,bread,common noun",
    ),
    INTERMEDIATE(
        cefr = "B1-B2",
        guidance = "The learner already knows the basics. Do NOT include A1-A2 vocabulary: no greetings or " +
            "courtesy phrases (hello, how are you, thank you, please, goodbye), no numbers, days, months " +
            "or colours, no family members, no basic food, and no very common verbs (to be, to have, to " +
            "go, to eat, to drink). Choose words a B1-B2 learner meets at work, in the news and in " +
            "discussions: abstract nouns, verbs with fixed prepositions, separable and reflexive verbs, " +
            "connectors (however, although, therefore), common collocations and everyday idioms.",
        topicMix = "work and career, society and news, health, technology, relationships, opinions and " +
            "arguments, travel problems, feelings and character, plus useful connectors and collocations",
        example = "die Verantwortung,responsibility,Sie übernimmt die Verantwortung.\n" +
            "sich beschweren über,to complain about,reflexive verb with über\n" +
            "allerdings,however,connector\n" +
            "eine Entscheidung treffen,to make a decision,common collocation",
    ),
    ADVANCED(
        cefr = "C1-C2",
        guidance = "The learner is fluent. Do NOT include anything at A1-B2 level: no greetings, courtesy " +
            "phrases, numbers, family, basic food or common everyday verbs. Choose precise, nuanced, " +
            "lower-frequency vocabulary: formal and academic register, idiomatic and figurative " +
            "expressions, nuanced synonyms of common words, and terms from the press, literature and " +
            "professional life.",
        topicMix = "politics and economics, science, law, culture and the arts, abstract ideas, rhetoric " +
            "and argumentation, idioms and set phrases, and formal correspondence",
        example = "die Nachhaltigkeit,sustainability,formal and press register\n" +
            "etwas in Kauf nehmen,to accept something as a downside,idiomatic expression\n" +
            "unumgänglich,unavoidable,formal adjective\n" +
            "die Gratwanderung,a balancing act,figurative",
    ),
    ;

    companion object {
        /** Reads a client level key or CEFR code; null when it is neither. */
        fun fromLabel(label: String): VocabularyLevel? {
            val key = label.trim().lowercase()
            return entries.firstOrNull { it.name.lowercase() == key }
                ?: when (key) {
                    "a1", "a2", "elementary" -> BEGINNER
                    "b1", "b2", "upper intermediate", "upper-intermediate" -> INTERMEDIATE
                    "c1", "c2", "proficient", "fluent" -> ADVANCED
                    else -> null
                }
        }
    }
}
