-- ============================================================================
-- V023 — Replace workflow step unique constraint with a partial index
--
-- The original V003 constraint UNIQUE (workflow_def_id, step_order) prevents
-- soft-deleted rows from sharing a step_order with their replacements.
-- When the admin edits a workflow, V022's soft-delete approach leaves the
-- old rows in place, and inserting new steps at the same step_order values
-- violates the unique constraint.
--
-- The fix: drop the constraint and replace it with a partial unique index
-- that only applies to non-deleted rows.
-- ============================================================================

ALTER TABLE wf.workflow_step_definition
    DROP CONSTRAINT workflow_step_definition_workflow_def_id_step_order_key;

CREATE UNIQUE INDEX idx_wfstep_def_unique_active
    ON wf.workflow_step_definition (workflow_def_id, step_order)
    WHERE deleted_at IS NULL;

COMMENT ON INDEX wf.idx_wfstep_def_unique_active IS
    'Enforces step_order uniqueness only among active (non-deleted) rows. Soft-deleted rows can share step_order with their replacements, which is required by the workflow-edit flow that preserves old steps for in-flight instances.';
