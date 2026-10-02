-- Premium grants (test users, manual comps) get their own columns instead of posing as
-- store subscriptions, so store webhooks/sync and grants can no longer overwrite each other.
ALTER TABLE users ADD COLUMN premium_grant_until TIMESTAMP NULL;
ALTER TABLE users ADD COLUMN premium_grant_reason VARCHAR(64) NULL;

-- Existing grants were stored as ACTIVE with a ~100-year expiry (test emails) or no expiry
-- (manually granted). Move them to the grant columns and reset the store status.
-- 'legacy_grant' is never auto-revoked; test users still on the list are re-tagged
-- 'test_email' on their next login, and only that reason is revoked when removed from the list.
UPDATE users
SET premium_grant_until  = COALESCE(subscription_expires_at, TIMESTAMP '2126-01-01 00:00:00'),
    premium_grant_reason = CASE WHEN subscription_expires_at IS NULL THEN 'manual' ELSE 'legacy_grant' END,
    subscription_status  = 'FREE',
    subscription_expires_at = NULL
WHERE subscription_status = 'ACTIVE'
  AND (subscription_expires_at IS NULL OR subscription_expires_at > TIMESTAMP '2070-01-01 00:00:00')
  AND revenuecat_user_id IS NULL;
