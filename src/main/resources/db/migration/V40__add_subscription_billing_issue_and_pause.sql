-- Store states the app explains to the user: a failed renewal (access continues through the
-- store's grace period) and a Google Play pause (no access, resumes automatically).
-- Set by RevenueCat webhooks (BILLING_ISSUE, SUBSCRIPTION_PAUSED); cleared when the
-- subscription is active again.
ALTER TABLE users ADD COLUMN subscription_billing_issue_at TIMESTAMP NULL;
ALTER TABLE users ADD COLUMN subscription_pause_resumes_at TIMESTAMP NULL;
