-- Listening mode (hands-free audio review) sessions. Passive exposure only: never graded,
-- never part of study_sessions, so accuracy, streaks and the heatmap are unaffected.
CREATE TABLE listening_sessions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    client_session_id  TEXT      NOT NULL,
    source             TEXT      NOT NULL,
    source_detail      TEXT,
    word_order         TEXT      NOT NULL,
    repeat_count       INT       NOT NULL DEFAULT 1,
    pause_ms           BIGINT    NOT NULL DEFAULT 0,
    speech_rate        REAL      NOT NULL DEFAULT 1.0,
    planned_words      INT       NOT NULL DEFAULT 0,
    words_heard        INT       NOT NULL DEFAULT 0,
    words_skipped      INT       NOT NULL DEFAULT 0,
    pause_count        INT       NOT NULL DEFAULT 0,
    listening_ms       BIGINT    NOT NULL DEFAULT 0,
    duration_ms        BIGINT    NOT NULL DEFAULT 0,
    completed_normally BOOLEAN   NOT NULL DEFAULT FALSE,
    started_at         BIGINT    NOT NULL,
    ended_at           BIGINT    NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_listening_user_session UNIQUE (user_id, client_session_id)
);

CREATE INDEX idx_listening_sessions_user_started ON listening_sessions (user_id, started_at DESC);

-- One row per word whose answer was spoken in a session. word_id has no FK, like review_events:
-- the history outlives a deleted word.
CREATE TABLE listening_session_words (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT  NOT NULL REFERENCES listening_sessions(id) ON DELETE CASCADE,
    user_id          BIGINT  NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word_id          BIGINT  NOT NULL,
    source_language  TEXT    NOT NULL,
    target_language  TEXT    NOT NULL,
    heard_at         BIGINT  NOT NULL
);

CREATE INDEX idx_listening_words_session ON listening_session_words (session_id);
CREATE INDEX idx_listening_words_user_word ON listening_session_words (user_id, word_id);
