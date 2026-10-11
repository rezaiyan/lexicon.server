-- In-app support chat. One thread per user; messages from the user (USER) and from the admin (ADMIN).
-- User messages are relayed to the admin Telegram chat; telegram_message_id maps an admin's
-- Telegram reply back to its thread and dedupes webhook retries.
CREATE TABLE support_threads (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT    NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    status          TEXT      NOT NULL DEFAULT 'OPEN',
    last_message_at TIMESTAMP NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_support_threads_last_message ON support_threads (last_message_at DESC);

CREATE TABLE support_messages (
    id                  BIGSERIAL PRIMARY KEY,
    thread_id           BIGINT    NOT NULL REFERENCES support_threads(id) ON DELETE CASCADE,
    sender              TEXT      NOT NULL,
    body                TEXT      NOT NULL,
    client_message_id   TEXT,
    telegram_message_id BIGINT    UNIQUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    read_at             TIMESTAMP,
    CONSTRAINT uq_support_client_message UNIQUE (thread_id, client_message_id)
);

CREATE INDEX idx_support_messages_thread ON support_messages (thread_id, id);
