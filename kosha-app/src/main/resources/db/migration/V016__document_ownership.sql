-- ============================================================================
-- V016 — Explicit document ownership with proxy delegation
--
-- Adds primary_owner_id and proxy_owner_id to doc.document.
-- primary_owner defaults to created_by for existing documents.
-- proxy_owner is the delegate who acts when the owner is unavailable.
-- ============================================================================

-- Add ownership columns
ALTER TABLE doc.document
    ADD COLUMN primary_owner_id UUID REFERENCES ident.user_profile(id),
    ADD COLUMN proxy_owner_id   UUID REFERENCES ident.user_profile(id);

-- Backfill: existing documents get created_by as primary owner
UPDATE doc.document SET primary_owner_id = created_by WHERE primary_owner_id IS NULL;

-- Make primary_owner non-null going forward
ALTER TABLE doc.document ALTER COLUMN primary_owner_id SET NOT NULL;

-- Indexes for lookups
CREATE INDEX idx_doc_primary_owner ON doc.document(primary_owner_id);
CREATE INDEX idx_doc_proxy_owner   ON doc.document(proxy_owner_id) WHERE proxy_owner_id IS NOT NULL;

-- Add domain event types for notifications
-- (These are recorded in the audit.event table, not in a separate table)
COMMENT ON COLUMN doc.document.primary_owner_id IS 'The accountable owner of this document. Defaults to created_by at upload time.';
COMMENT ON COLUMN doc.document.proxy_owner_id IS 'Delegate who acts on behalf of the owner. Auto-set to the uploader when owner differs from uploader.';
