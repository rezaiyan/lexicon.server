package com.alirezaiyan.vokab.server.mcp

/** What an MCP client may do with a learner's data; granted per client on the consent screen. */
object McpScopes {
    const val WORDS_READ = "words:read"
    const val WORDS_WRITE = "words:write"
    const val PROGRESS_READ = "progress:read"

    val ALL = setOf(WORDS_READ, WORDS_WRITE, PROGRESS_READ)

    /**
     * The scope each tool needs. `/mcp` checks it before the call reaches the tool, so a client
     * lacking it gets a 403 `insufficient_scope` it can act on; the tools check again themselves.
     */
    val BY_TOOL: Map<String, String> = mapOf(
        "search_words" to WORDS_READ,
        "list_tags" to WORDS_READ,
        "get_due_words" to WORDS_READ,
        "add_words" to WORDS_WRITE,
        "tag_words" to WORDS_WRITE,
        "get_difficult_words" to PROGRESS_READ,
        "get_weekly_report" to PROGRESS_READ,
    )

    /** Space-separated and sorted, as `scope` values in OAuth challenges are. */
    fun asParameter(scopes: Collection<String>): String = scopes.toSortedSet().joinToString(" ")
}
