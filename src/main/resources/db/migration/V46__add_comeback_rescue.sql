-- AI comeback rescue: at most one plan per user per day for learners in an early lapse.
-- word_ids is the ordered session (JSON array of word ids). candidates keeps every AI copy
-- candidate with its lint verdict so the copy can be tuned. Opens are read from
-- notification_log through notification_log_id.
CREATE TABLE comeback_rescue (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    planned_for         DATE      NOT NULL,
    word_ids            JSONB     NOT NULL,
    title               TEXT      NOT NULL,
    body                TEXT      NOT NULL,
    angle               TEXT      NOT NULL,
    outro_win           TEXT,
    outro_soft          TEXT,
    candidates          JSONB,
    picks_reasoning     JSONB,
    source              TEXT      NOT NULL,
    status              TEXT      NOT NULL,
    notification_log_id BIGINT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    sent_at             TIMESTAMP,
    completed_at        TIMESTAMP,
    CONSTRAINT uq_comeback_rescue_user_day UNIQUE (user_id, planned_for)
);

CREATE INDEX idx_comeback_rescue_status_day ON comeback_rescue (status, planned_for);
