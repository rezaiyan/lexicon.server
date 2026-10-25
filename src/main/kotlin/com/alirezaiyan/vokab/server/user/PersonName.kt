package com.alirezaiyan.vokab.server.user

/**
 * The account's own name, or `null` when there is none worth saying: blank, the email itself (a
 * Google account without a name), or the hidden Apple address's random part (accounts Apple shared
 * no name for used to be named after it).
 */
val User.ownName: String?
    get() = name.trim().takeUnless {
        it.isEmpty() ||
            it.equals(email, ignoreCase = true) ||
            (isHiddenAppleEmail(email) && it.equals(email.substringBefore("@"), ignoreCase = true))
    }

/** What to call the user: the alias they picked, else their own name; `null` when we know neither. */
val User.personName: String?
    get() = displayAlias?.trim()?.takeIf { it.isNotEmpty() } ?: ownName
