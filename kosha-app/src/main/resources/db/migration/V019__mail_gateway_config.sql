-- ============================================================================
-- V019 — Mail gateway configuration
--
-- Singleton config row (id = 'default') storing the active outbound email
-- gateway settings. Password field holds AES-256 ciphertext encrypted by
-- Spring's TextEncryptor using a key supplied via env vars
-- (KOSHA_CRYPTO_PASSWORD, KOSHA_CRYPTO_SALT).
-- ============================================================================

CREATE TABLE notif.mail_gateway_config (
    id                      VARCHAR(50)  PRIMARY KEY DEFAULT 'default',
    provider                VARCHAR(50)  NOT NULL DEFAULT 'mailpit',
    transport               VARCHAR(20)  NOT NULL DEFAULT 'smtp',
    host                    VARCHAR(255) NOT NULL DEFAULT 'localhost',
    port                    INTEGER      NOT NULL DEFAULT 1025,
    encryption              VARCHAR(20)  NOT NULL DEFAULT 'none'
                            CHECK (encryption IN ('starttls', 'tls', 'none')),
    skip_tls_verify         BOOLEAN      NOT NULL DEFAULT FALSE,
    username                VARCHAR(255),
    encrypted_password      TEXT,
    from_email              VARCHAR(255) NOT NULL DEFAULT 'notifications@kosha.local',
    from_name               VARCHAR(255) NOT NULL DEFAULT 'Kosha KMS',
    reply_to_email          VARCHAR(255),
    region                  VARCHAR(50),
    sandbox_mode            BOOLEAN      NOT NULL DEFAULT FALSE,
    connection_timeout_ms   INTEGER      NOT NULL DEFAULT 10000,
    read_timeout_ms         INTEGER      NOT NULL DEFAULT 10000,
    last_tested_at          TIMESTAMPTZ,
    last_test_success       BOOLEAN,
    last_test_error         TEXT,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed the default row so the app has a working config on first boot.
-- Defaults point at Mailpit so dev flow continues working without env tweaks.
INSERT INTO notif.mail_gateway_config (id, provider)
VALUES ('default', 'mailpit')
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE notif.mail_gateway_config IS 'Singleton mail gateway configuration. Edited via /api/v1/admin/mail-gateway.';
COMMENT ON COLUMN notif.mail_gateway_config.encrypted_password IS 'AES-256 ciphertext via Spring TextEncryptor. Key from env: KOSHA_CRYPTO_PASSWORD + KOSHA_CRYPTO_SALT.';
