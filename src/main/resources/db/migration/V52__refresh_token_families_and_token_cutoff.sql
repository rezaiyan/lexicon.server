-- Refresh token rotation with reuse detection (V6 dropped the earlier, unused family columns).
--   family_id   : every token minted from one sign-in shares it; reuse of a retired token revokes the family
--   used_at     : first time the token was exchanged; the retry grace window counts from here and never moves
--   replaced_by : id of the latest token minted from this one (informational, no FK: cleanup deletes freely)
ALTER TABLE refresh_tokens ADD COLUMN family_id VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN used_at TIMESTAMP;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by BIGINT;

-- Existing tokens each become their own family (portable PostgreSQL / H2 syntax).
UPDATE refresh_tokens SET family_id = 'legacy-' || CAST(id AS VARCHAR(20)) WHERE family_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);

-- Access tokens issued before this instant are rejected (logout-all). Null = no cutoff.
ALTER TABLE users ADD COLUMN tokens_valid_after TIMESTAMP;
