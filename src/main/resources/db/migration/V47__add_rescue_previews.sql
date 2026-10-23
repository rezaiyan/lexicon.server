-- Comeback rescue taste test: copy generated for real learners from the admin page, never sent and
-- kept apart from comeback_rescue so a preview can't block or count as a real plan. Each AI copy
-- candidate in a preview can be rated once (re-rating replaces the rating).
CREATE TABLE rescue_preview (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    prompt_version  TEXT      NOT NULL,
    source          TEXT      NOT NULL,
    angle           TEXT      NOT NULL,
    title           TEXT      NOT NULL,
    body            TEXT      NOT NULL,
    outro_win       TEXT,
    outro_soft      TEXT,
    word_ids        JSONB     NOT NULL,
    candidates      JSONB,
    picks_reasoning JSONB
);

CREATE INDEX idx_rescue_preview_created ON rescue_preview (created_at DESC);

CREATE TABLE rescue_copy_rating (
    id              BIGSERIAL PRIMARY KEY,
    preview_id      BIGINT    NOT NULL REFERENCES rescue_preview(id) ON DELETE CASCADE,
    candidate_index INT       NOT NULL,
    rating          TEXT      NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    note            TEXT,
    rated_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_rescue_copy_rating_candidate UNIQUE (preview_id, candidate_index)
);
