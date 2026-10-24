-- Passkeys (WebAuthn) as an extra way to sign in to an existing account.
--   credential_id            : base64url credential id the authenticator returns on every sign-in
--   user_handle              : base64url WebAuthn user.id; one per user, shared by all their passkeys
--   attested_credential_data : webauthn4j-encoded AAGUID + credential id + COSE public key
--   sign_count               : last signature counter seen (synced passkeys always report 0)
CREATE TABLE passkeys (
    id                       BIGSERIAL     PRIMARY KEY,
    user_id                  BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential_id            VARCHAR(1366) NOT NULL,
    user_handle              VARCHAR(128)  NOT NULL,
    attested_credential_data BYTEA         NOT NULL,
    sign_count               BIGINT        NOT NULL DEFAULT 0,
    uv_initialized           BOOLEAN       NOT NULL,
    backup_eligible          BOOLEAN       NOT NULL,
    backup_state             BOOLEAN       NOT NULL,
    transports               VARCHAR(255),
    created_at               TIMESTAMP     NOT NULL,
    last_used_at             TIMESTAMP,
    CONSTRAINT uq_passkeys_credential_id UNIQUE (credential_id)
);

CREATE INDEX idx_passkeys_user_id ON passkeys (user_id);
