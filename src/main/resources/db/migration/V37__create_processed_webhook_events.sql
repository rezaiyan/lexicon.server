-- Receipts for applied webhook events (RevenueCat redelivers until it gets a 2xx).
CREATE TABLE processed_webhook_events (
    event_id     VARCHAR(255) PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL
);
