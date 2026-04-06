-- ============================================================================
-- V030 — Document signatures (Pass 5.2)
--
-- An electronic attestation record binding a user to a specific version
-- of a document at a specific point in time. Not a cryptographic
-- signature in the PKI sense — it's an audit-grade "I, user X, affirm
-- that I reviewed version Y of document Z at time T, and the content
-- hash at signing was H."
--
-- Signatures are per-version, not per-document. A new version does not
-- inherit or invalidate prior signatures — they stay as a historical
-- record of who attested to what.
--
-- The SIGN_OFF workflow action type (existing since Pass 2) creates a
-- signature record automatically when the step is approved. Manual
-- signing via the document detail page is also supported.
-- ============================================================================

CREATE TABLE doc.document_signature (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES doc.document(id),
    version_id      UUID NOT NULL REFERENCES doc.document_version(id),
    signer_id       UUID NOT NULL REFERENCES ident.user_profile(id),

    -- The signer's typed name at the moment of signing. Captured
    -- separately from user_profile.display_name because the profile
    -- name can change after the fact; the typed name is the immutable
    -- attestation record.
    typed_name      VARCHAR(300) NOT NULL,

    -- SHA-256 hash of the version's bytes at signing time. Allows
    -- after-the-fact verification that the bytes haven't changed since
    -- the signature was applied. The preview/download endpoint serves
    -- the same bytes, so a verifier can re-hash and compare.
    content_hash    VARCHAR(128) NOT NULL,

    -- Optional IP address of the signer for audit trail depth. NULL
    -- when the signing happened via the workflow engine (server-side)
    -- rather than via a direct user click (client-side sends X-Forwarded-For).
    ip_address      VARCHAR(45),

    signed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Signatures are immutable — no updated_at, no soft delete, no
    -- revocation column. A signature cannot be withdrawn; it can only
    -- be superseded by a new version with new signatures. This is the
    -- correct model for audit-grade attestation: if you signed it,
    -- the record persists forever.
    CONSTRAINT document_signature_unique UNIQUE (document_id, version_id, signer_id)
);

CREATE INDEX idx_docsig_document ON doc.document_signature(document_id);
CREATE INDEX idx_docsig_version  ON doc.document_signature(version_id);
CREATE INDEX idx_docsig_signer   ON doc.document_signature(signer_id);

COMMENT ON TABLE doc.document_signature
    IS 'Immutable attestation records binding a user to a specific version of a document.';
