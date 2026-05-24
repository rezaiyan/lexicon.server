-- Engagement segment + AI advisor fields on notification_schedule
-- engagement_segment: HOT | WARM | COOLING | COLD | DORMANT (computed nightly)
-- ai_action:          send | pause | motivate (Haiku decision for COLD/DORMANT)
-- ai_interval_days:   days until next notification attempt (AI-chosen)
-- ai_content_hint:    emotional angle for MOTIVATION type (AI-chosen)
-- ai_decided_at:      when AI last evaluated this user (cache TTL = 7 days)

ALTER TABLE notification_schedule
    ADD COLUMN engagement_segment VARCHAR(20) NOT NULL DEFAULT 'WARM',
    ADD COLUMN ai_action          VARCHAR(20),
    ADD COLUMN ai_interval_days   INT,
    ADD COLUMN ai_content_hint    VARCHAR(50),
    ADD COLUMN ai_decided_at      TIMESTAMP;
