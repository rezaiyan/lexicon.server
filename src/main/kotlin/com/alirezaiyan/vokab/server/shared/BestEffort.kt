package com.alirezaiyan.vokab.server.shared

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Runs an optional lookup whose failure must not fail the caller (extra context for a
 * notification or an AI prompt). A failure is logged as "[what] unavailable" and treated as no
 * value, so it is visible instead of silently looking like missing data.
 */
fun <T> bestEffort(what: String, block: () -> T): T? =
    runCatching(block)
        .onFailure { logger.warn(it) { "$what unavailable, continuing without it" } }
        .getOrNull()
