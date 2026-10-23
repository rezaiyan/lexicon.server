-- AI credits. One wallet per user holds the current balances; credit_transactions is the
-- append-only ledger behind them (every grant, spend and refund), so balances can be audited.
--
-- Two buckets: the monthly allowance (reset each period for the user's tier, never rolls over)
-- and the bonus balance (signup bonus, admin grants; never expires). Spends drain the allowance
-- first. Periods are advanced lazily on access, so no scheduled job is needed.
CREATE TABLE credit_wallets (
    user_id             BIGINT      PRIMARY KEY REFERENCES users (id),
    tier                VARCHAR(16) NOT NULL,
    allowance_remaining INT         NOT NULL CHECK (allowance_remaining >= 0),
    period_start        TIMESTAMP   NOT NULL,
    period_end          TIMESTAMP   NOT NULL,
    bonus_balance       INT         NOT NULL CHECK (bonus_balance >= 0),
    created_at          TIMESTAMP   NOT NULL,
    updated_at          TIMESTAMP   NOT NULL
);

CREATE TABLE credit_transactions (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    type            VARCHAR(24) NOT NULL,
    action          VARCHAR(32) NULL,
    allowance_delta INT         NOT NULL,
    bonus_delta     INT         NOT NULL,
    -- For a REFUND: the SPEND it reverses. Unique, so a spend can be refunded at most once.
    reference_id    BIGINT      NULL UNIQUE REFERENCES credit_transactions (id),
    note            VARCHAR(128) NULL,
    created_at      TIMESTAMP   NOT NULL
);

CREATE INDEX idx_credit_transactions_user_created ON credit_transactions (user_id, created_at);
