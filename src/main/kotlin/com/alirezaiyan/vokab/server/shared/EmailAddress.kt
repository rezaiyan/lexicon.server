package com.alirezaiyan.vokab.server.shared

/**
 * How emails are stored and compared: trimmed and lowercased, so letter case or stray spaces never
 * split one person across two accounts or stop a sign-in from linking to theirs.
 */
fun normalizeEmail(email: String): String = email.trim().lowercase()
