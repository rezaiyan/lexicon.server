-- One row per OpenRouter call: tokens and the provider's own cost, so AI credit prices are set from
-- real usage (e.g. average cost per operation in Metabase). No user id: prices are per operation.
CREATE TABLE ai_usage (
    id                BIGSERIAL     PRIMARY KEY,
    operation         VARCHAR(32)   NOT NULL,
    model             VARCHAR(100)  NOT NULL,
    prompt_tokens     INT           NULL,
    completion_tokens INT           NULL,
    -- USD as reported by OpenRouter; NULL when the response carried no cost
    cost_usd          NUMERIC(12, 8) NULL,
    created_at        TIMESTAMP     NOT NULL
);

CREATE INDEX idx_ai_usage_operation_created ON ai_usage (operation, created_at);
