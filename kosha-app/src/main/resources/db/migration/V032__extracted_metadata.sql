-- ============================================================================
-- V032 — Extracted metadata JSONB on document versions (Pass 5.3.0)
--
-- Structured named-entity data extracted by the AI sidecar's spaCy
-- NER pipeline. This is the bridge between AI processing and the
-- conditional workflow engine (Pass 5.3): workflow step conditions
-- evaluate JSON Logic expressions against these fields to decide
-- whether a step should fire or be skipped.
--
-- The column stores a flat JSON object whose keys are defined by the
-- published metadata schema (see `metadata-schema.json` in the admin
-- UI). Example:
--
--   {
--     "amounts": [10000, 5000],
--     "currency": "GBP",
--     "effective_date": "2026-01-15",
--     "parties": ["Acme Corp", "Widget Ltd"],
--     "jurisdiction": "England and Wales",
--     "document_number": "DOC-00042"
--   }
--
-- The schema is a living contract: new fields are added when the AI
-- pipeline grows, and the workflow condition builder shows only fields
-- that exist in the schema. Old documents with fewer fields simply
-- have those keys absent — JSON Logic treats missing keys as null,
-- which conditions can test for explicitly.
-- ============================================================================

ALTER TABLE doc.document_version
    ADD COLUMN extracted_metadata JSONB;

COMMENT ON COLUMN doc.document_version.extracted_metadata
    IS 'Structured metadata extracted by the AI sidecar (spaCy NER). NULL until processing completes. Keys follow the published metadata schema.';
