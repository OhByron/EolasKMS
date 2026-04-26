-- V038: Track whether each extracted_keyword row was produced by the AI
-- pipeline or added manually by a user.
--
-- Without this column, a future re-extraction run on a document would
-- have no way to tell user-added keywords from stale AI output, and
-- would clobber them. Recording provenance lets the AI worker scope its
-- INSERT/DELETE to source = 'AI_EXTRACTED' and leave manual entries alone.
--
-- The frontend can also use this to render manual keywords differently
-- (e.g. a small "added by you" hint) but the column primarily exists
-- for the backend re-extraction guard.
--
-- 'AI_EXTRACTED' chosen to match the parallel `source` enum on
-- tax.taxonomy_term_alias which uses 'AI_SUGGESTED' / 'MANUAL'.

ALTER TABLE tax.extracted_keyword
    ADD COLUMN source VARCHAR(30) NOT NULL DEFAULT 'AI_EXTRACTED'
    CHECK (source IN ('AI_EXTRACTED', 'MANUAL'));

-- Case-insensitive uniqueness per version so the manual-add endpoint
-- can use a simple ON CONFLICT idempotency without races, and so the
-- re-extraction worker doesn't accumulate duplicates over re-runs.
CREATE UNIQUE INDEX idx_extkw_version_keyword_ci
    ON tax.extracted_keyword (version_id, lower(keyword));
