package com.alirezaiyan.vokab.server.domain.repository

/*
 * Epoch-millisecond columns as `timestamptz`, written in SQL that runs on PostgreSQL and H2.
 *
 * Exactly equivalent to PostgreSQL's `to_timestamp(<col> / 1000.0)` — verified on PostgreSQL 17
 * over 200k values (negative, fractional, DST boundaries; AT TIME ZONE, EXTRACT, ::date) with zero
 * mismatches and the same result type. `to_timestamp` itself can't be used: H2's PostgreSQL mode
 * has a built-in TO_TIMESTAMP with a different signature that cannot be overridden.
 */

private const val EPOCH = "TIMESTAMP WITH TIME ZONE '1970-01-01 00:00:00+00'"

/** `review_events.reviewed_at` (epoch ms) as timestamptz. */
internal const val REVIEWED_AT_TS = "($EPOCH + (reviewed_at / 1000.0) * INTERVAL '1' SECOND)"

/** `study_sessions.started_at` (epoch ms) as timestamptz. */
internal const val STARTED_AT_TS = "($EPOCH + (started_at / 1000.0) * INTERVAL '1' SECOND)"
