-- Tombstones for deleted words. The word sync only returns words changed since a timestamp, so
-- without these a word deleted on one device (or by a connected AI app) stays on the others.
-- Clients ask for ids deleted since their last sync; rows older than 180 days are purged nightly.
CREATE TABLE deleted_words (
    user_id    BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    word_id    BIGINT    NOT NULL,
    deleted_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, word_id)
);

CREATE INDEX idx_deleted_words_user_deleted_at ON deleted_words (user_id, deleted_at);
CREATE INDEX idx_deleted_words_deleted_at ON deleted_words (deleted_at);
