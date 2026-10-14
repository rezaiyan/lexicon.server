package com.alirezaiyan.vokab.server.mcp

/** What an MCP client may do with a learner's data; granted per client on the consent screen. */
object McpScopes {
    const val WORDS_READ = "words:read"
    const val WORDS_WRITE = "words:write"
    const val PROGRESS_READ = "progress:read"

    val ALL = setOf(WORDS_READ, WORDS_WRITE, PROGRESS_READ)
}
