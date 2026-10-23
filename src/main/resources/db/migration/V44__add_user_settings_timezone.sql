-- IANA zone id reported by the client (e.g. 'Europe/Berlin'). NULL = not reported yet;
-- notification timing then falls back to the offset derived from review history.
ALTER TABLE user_settings
    ADD COLUMN timezone VARCHAR(64);
