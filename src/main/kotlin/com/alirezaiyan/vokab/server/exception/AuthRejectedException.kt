package com.alirezaiyan.vokab.server.exception

/**
 * The caller's sign-in material is not acceptable: an ID token that fails verification, or a
 * refresh token that is unknown, revoked or expired. Mapped to 401.
 *
 * Only throw this when the client should discard what it sent. Infrastructure failures (database,
 * identity-provider outages) must surface as 5xx: the app signs the user out on a 401 from
 * `/auth/refresh` but keeps the session on a 5xx.
 */
class AuthRejectedException(message: String) : RuntimeException(message)
