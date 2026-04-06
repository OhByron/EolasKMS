-- ============================================================================
-- V031 — Public share links with expiry (Pass 5.5)
--
-- Lets authenticated users generate time-limited, optionally password-
-- protected links that give anonymous viewers read-only access to a
-- specific document version's preview.
--
-- ## Security model
--
-- - **PUBLISHED-only gate.** Links can only be created when
--   document.status = 'PUBLISHED'. Resolution also checks the status
--   at access time — if the document leaves PUBLISHED (e.g. goes on
--   legal hold or gets archived), outstanding links return 410 Gone
--   without any explicit revoke step.
--
-- - **Token is hashed.** The raw 32-byte token is shown to the creator
--   exactly once. Only the SHA-256 hash is stored. An attacker who
--   compromises the DB cannot reconstruct a valid share URL.
--
-- - **Optional password.** Stored as bcrypt. The public page prompts
--   before rendering when the password hash is non-null. Brute-force
--   protection via rate limiting (not in this migration — enforced at
--   the controller layer).
--
-- - **Max access count.** Optional cap on how many times the link can
--   be resolved. Each resolution bumps `access_count`; when it hits
--   `max_access` the link is exhausted and returns 410.
--
-- - **Manual revocation.** Creator can set `revoked_at` via a PATCH
--   endpoint. The token continues to exist in the DB for audit but
--   resolution returns 410.
-- ============================================================================

CREATE TABLE doc.share_link (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES doc.document(id),
    version_id      UUID NOT NULL REFERENCES doc.document_version(id),

    -- SHA-256 hash of the raw token. The raw token is base64url-encoded
    -- 32 bytes of SecureRandom, shown to the creator once.
    token_hash      VARCHAR(128) NOT NULL UNIQUE,

    created_by      UUID NOT NULL REFERENCES ident.user_profile(id),
    expires_at      TIMESTAMPTZ NOT NULL,

    -- bcrypt hash of the optional password. NULL = no password required.
    password_hash   VARCHAR(200),

    -- NULL = unlimited access. When set, link becomes 410 once
    -- access_count reaches max_access.
    max_access      INTEGER,
    access_count    INTEGER NOT NULL DEFAULT 0,

    revoked_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_sharelink_document ON doc.share_link(document_id);
CREATE INDEX idx_sharelink_token    ON doc.share_link(token_hash);
CREATE INDEX idx_sharelink_creator  ON doc.share_link(created_by);

COMMENT ON TABLE doc.share_link
    IS 'Time-limited anonymous share links for published document versions.';
