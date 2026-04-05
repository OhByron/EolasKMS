-- ============================================================================
-- V022 — Workflow engine extensions
--
-- Adds fields needed to run actual workflows:
--   * Named assignees and escalation contacts on step definitions
--   * Per-step time limits in days (user-friendly unit)
--   * Soft-delete on step definitions so in-flight instances keep working
--     when the admin edits the workflow
--   * Rejection tracking on workflow instances (rejected_by, rejection_comments)
--   * Due date and escalation tracking on step instances
--   * WAITING status for LINEAR workflows where later steps haven't fired yet
--
-- Seeds a default workflow for every existing department: a single APPROVE
-- step assigned to the department's admin, escalating to a global admin.
-- Departments without an admin get no workflow and must create one before
-- documents can be submitted.
-- ============================================================================

-- ── workflow_step_definition: named assignees, time limits, soft delete ──
ALTER TABLE wf.workflow_step_definition
    ADD COLUMN assignee_user_id   UUID REFERENCES ident.user_profile(id),
    ADD COLUMN escalation_user_id UUID REFERENCES ident.user_profile(id),
    ADD COLUMN time_limit_days    INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN deleted_at         TIMESTAMPTZ;

-- Time limits: 1-90 days is a reasonable range. Below 1 is pointless,
-- above 90 means the admin should reconsider whether retention is the
-- better tool for the job.
ALTER TABLE wf.workflow_step_definition
    ADD CONSTRAINT wfstep_time_limit_range
    CHECK (time_limit_days BETWEEN 1 AND 90);

-- Soft-delete index — queries for active steps filter on deleted_at IS NULL
CREATE INDEX idx_wfstep_def_active
    ON wf.workflow_step_definition(workflow_def_id, step_order)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN wf.workflow_step_definition.assignee_user_id IS
    'The named user assigned to this step. Required for active workflows.';
COMMENT ON COLUMN wf.workflow_step_definition.escalation_user_id IS
    'User notified if the assignee does not act within time_limit_days. Required for active workflows.';
COMMENT ON COLUMN wf.workflow_step_definition.deleted_at IS
    'Soft delete. When a workflow is edited, old step rows are marked deleted_at but not removed, so in-flight workflow_step_instance rows keep their FK target.';

-- ── workflow_instance: rejection tracking ────────────────────────
ALTER TABLE wf.workflow_instance
    ADD COLUMN rejection_comments   TEXT,
    ADD COLUMN rejected_at_step_id  UUID REFERENCES wf.workflow_step_definition(id),
    ADD COLUMN rejected_by          UUID REFERENCES ident.user_profile(id);

-- ── workflow_step_instance: due date, escalation, WAITING state ──
ALTER TABLE wf.workflow_step_instance
    ADD COLUMN due_at        TIMESTAMPTZ,
    ADD COLUMN escalated_at  TIMESTAMPTZ;

-- Relax the status check to include WAITING (LINEAR workflows: steps that
-- aren't yet active because an earlier step hasn't completed).
ALTER TABLE wf.workflow_step_instance
    DROP CONSTRAINT workflow_step_instance_status_check;

ALTER TABLE wf.workflow_step_instance
    ADD CONSTRAINT workflow_step_instance_status_check
    CHECK (status IN ('WAITING', 'PENDING', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'SKIPPED'));

COMMENT ON COLUMN wf.workflow_step_instance.due_at IS
    'Deadline computed from the step definition''s time_limit_days when the step becomes PENDING.';
COMMENT ON COLUMN wf.workflow_step_instance.escalated_at IS
    'Timestamp when the escalation contact was notified because due_at passed. Null = not escalated. Set once, never reset.';

-- ── Seed default workflow for every existing department ─────────
--
-- Strategy:
--   1. For each department, find the first DEPT_ADMIN (fallback: first GLOBAL_ADMIN)
--   2. Find a GLOBAL_ADMIN anywhere to use as escalation contact
--   3. If no admin exists for the department, skip — the workflow must be
--      created later once an admin is assigned
--   4. Insert workflow_definition + single APPROVE step
--
-- Uses PL/pgSQL so we can iterate departments cleanly.

DO $$
DECLARE
    dept RECORD;
    admin_id UUID;
    global_admin_id UUID;
    wf_def_id UUID;
BEGIN
    -- Find any global admin for use as escalation contact
    SELECT id INTO global_admin_id
    FROM ident.user_profile
    WHERE role = 'GLOBAL_ADMIN' AND status = 'ACTIVE'
    ORDER BY created_at ASC
    LIMIT 1;

    FOR dept IN
        SELECT id, name FROM ident.department WHERE status = 'ACTIVE'
    LOOP
        -- Prefer a department admin; fall back to a global admin
        SELECT id INTO admin_id
        FROM ident.user_profile
        WHERE department_id = dept.id
          AND role = 'DEPT_ADMIN'
          AND status = 'ACTIVE'
        ORDER BY created_at ASC
        LIMIT 1;

        IF admin_id IS NULL THEN
            admin_id := global_admin_id;
        END IF;

        IF admin_id IS NULL THEN
            RAISE NOTICE 'Skipping default workflow for department % — no eligible approver', dept.name;
            CONTINUE;
        END IF;

        -- Don't clobber an existing workflow for this department
        IF EXISTS (
            SELECT 1 FROM wf.workflow_definition
            WHERE department_id = dept.id
        ) THEN
            CONTINUE;
        END IF;

        INSERT INTO wf.workflow_definition (name, description, workflow_type, department_id, is_default)
        VALUES (
            'Default Approval',
            'Default workflow seeded on department creation. The department admin approves all submissions.',
            'LINEAR',
            dept.id,
            TRUE
        )
        RETURNING id INTO wf_def_id;

        INSERT INTO wf.workflow_step_definition (
            workflow_def_id, step_order, name, action_type,
            assignee_user_id, escalation_user_id, time_limit_days
        ) VALUES (
            wf_def_id, 1, 'Department Admin Approval', 'APPROVE',
            admin_id,
            COALESCE(global_admin_id, admin_id),  -- fall back to self if no global admin exists
            3
        );

        RAISE NOTICE 'Seeded default workflow for department %', dept.name;
    END LOOP;
END $$;
