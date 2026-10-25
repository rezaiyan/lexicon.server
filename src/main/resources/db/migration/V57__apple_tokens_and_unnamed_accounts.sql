-- The user's Sign in with Apple refresh token, kept so account deletion can revoke it
ALTER TABLE users ADD COLUMN apple_refresh_token VARCHAR(1024);
ALTER TABLE users ADD COLUMN apple_token_client_id VARCHAR(255);

-- Apple accounts that shared no name were named after their hidden address's random part.
-- Blank it, so the app asks for a name instead of showing (and the AI greeting with) "525tbwzjhz".
UPDATE users
SET name = ''
WHERE apple_id IS NOT NULL
  AND (email LIKE '%@privaterelay.appleid.com' OR email LIKE '%@private.icloud.com' OR email LIKE '%@apple.hidden')
  AND lower(name) = lower(split_part(email, '@', 1));
