-- Comeback rescue autopilot: one row. Sends pause automatically while paused_until is in the future
-- (set by RescueGuard when rescued learners opt out) and resume on their own after it.
CREATE TABLE rescue_autopilot (
    id           SMALLINT  PRIMARY KEY CHECK (id = 1),
    paused_until DATE,
    reason       TEXT,
    changed_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

INSERT INTO rescue_autopilot (id) VALUES (1);
