-- Absolute session lifetime (app.jwt.refresh-family-max-age-ms, default 180 days).
--   family_expires_at : end of the sign-in session; every token rotated from it carries the same
--                       value and rotation caps each new token's expiry at it, so refreshing can't
--                       keep a session alive forever
ALTER TABLE refresh_tokens ADD COLUMN family_expires_at TIMESTAMP;

-- Existing sessions get the full max age from this migration on. Counting from their first token
-- would end the oldest (most active) sessions the moment this deploys.
UPDATE refresh_tokens SET family_expires_at = CURRENT_TIMESTAMP + INTERVAL '180' DAY;

ALTER TABLE refresh_tokens ALTER COLUMN family_expires_at SET NOT NULL;
