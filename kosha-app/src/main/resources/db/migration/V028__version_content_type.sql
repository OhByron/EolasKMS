-- ============================================================================
-- V028 — Persist MIME type on document versions
--
-- Added as part of Pass 4.1 (document preview). The upload endpoint was
-- already receiving `file.contentType` from the multipart request, but
-- was only forwarding it to the AI task queue and not persisting it.
-- Preview needs a reliable content-type to set on the HTTP response, and
-- re-inferring it from filename extension is fragile — especially for
-- Office docs where the same extension can map to multiple MIME types.
--
-- Backfill strategy: existing rows stay NULL. The preview endpoint
-- handles null by falling back to MinIO object metadata, which existed
-- on upload time via the PutObjectArgs contentType. Docs uploaded before
-- this migration have neither — preview degrades to a download button
-- with a note explaining the gap. Users can fix by re-uploading a new
-- version.
-- ============================================================================

ALTER TABLE doc.document_version
    ADD COLUMN content_type VARCHAR(200);

COMMENT ON COLUMN doc.document_version.content_type
    IS 'MIME type as reported by the uploader. NULL for versions uploaded before Pass 4.1.';
