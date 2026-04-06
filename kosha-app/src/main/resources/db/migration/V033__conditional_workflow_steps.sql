-- ============================================================================
-- V033 — Conditional workflow steps (Pass 5.3)
--
-- Each workflow step definition gains an optional JSON Logic expression
-- that the engine evaluates at promotion time. When the expression
-- evaluates to falsy, the step is auto-marked SKIPPED and the engine
-- moves to the next step. When null or empty, the step always fires
-- (backwards compatible with all existing workflows).
--
-- The expression is stored as raw JSON text because:
--   1. The DB doesn't need to interpret it — only the Java/Kotlin
--      JSON Logic evaluator does.
--   2. The workflow editor UI needs it as-is for the textarea value.
--   3. TEXT is simpler than JSONB for a column the DB never queries.
-- ============================================================================

ALTER TABLE wf.workflow_step_definition
    ADD COLUMN condition_json TEXT;

COMMENT ON COLUMN wf.workflow_step_definition.condition_json
    IS 'Optional JSON Logic expression. When present, the engine evaluates it against the document context at promotion time. Falsy result = step is auto-skipped. NULL or empty = step always fires.';
