-- ============================================================
-- V003: Workflow module — definitions, instances, step tracking
-- ============================================================

CREATE SCHEMA IF NOT EXISTS wf;

-- Workflow template definitions
CREATE TABLE wf.workflow_definition (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    workflow_type   VARCHAR(20) NOT NULL
                    CHECK (workflow_type IN ('LINEAR', 'PARALLEL')),
    department_id   UUID REFERENCES ident.department(id),
    is_default      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wfdef_dept ON wf.workflow_definition(department_id);

-- Steps within a workflow definition
CREATE TABLE wf.workflow_step_definition (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_def_id     UUID NOT NULL REFERENCES wf.workflow_definition(id) ON DELETE CASCADE,
    step_order          INTEGER NOT NULL,
    name                VARCHAR(200) NOT NULL,
    assignee_role       VARCHAR(30)
                        CHECK (assignee_role IS NULL OR assignee_role IN ('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR', 'CONTRIBUTOR')),
    assignee_group_id   UUID REFERENCES ident.access_group(id),
    action_type         VARCHAR(30) NOT NULL
                        CHECK (action_type IN ('REVIEW', 'APPROVE', 'SIGN_OFF')),
    timeout_hours       INTEGER,
    UNIQUE (workflow_def_id, step_order)
);

-- Active workflow instances
CREATE TABLE wf.workflow_instance (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_def_id UUID NOT NULL REFERENCES wf.workflow_definition(id),
    document_id     UUID NOT NULL REFERENCES doc.document(id),
    version_id      UUID NOT NULL REFERENCES doc.document_version(id),
    initiated_by    UUID NOT NULL REFERENCES ident.user_profile(id),
    status          VARCHAR(30) NOT NULL DEFAULT 'IN_PROGRESS'
                    CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE INDEX idx_wfinst_doc     ON wf.workflow_instance(document_id);
CREATE INDEX idx_wfinst_status  ON wf.workflow_instance(status);
CREATE INDEX idx_wfinst_init    ON wf.workflow_instance(initiated_by);

-- Individual step instances within a running workflow
CREATE TABLE wf.workflow_step_instance (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_inst_id    UUID NOT NULL REFERENCES wf.workflow_instance(id) ON DELETE CASCADE,
    step_def_id         UUID NOT NULL REFERENCES wf.workflow_step_definition(id),
    assigned_to         UUID REFERENCES ident.user_profile(id),
    status              VARCHAR(30) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'APPROVED', 'REJECTED', 'SKIPPED')),
    comments            TEXT,
    decided_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_wfstep_inst      ON wf.workflow_step_instance(workflow_inst_id);
CREATE INDEX idx_wfstep_assigned  ON wf.workflow_step_instance(assigned_to) WHERE assigned_to IS NOT NULL;
CREATE INDEX idx_wfstep_status    ON wf.workflow_step_instance(status);

-- Trigger
CREATE OR REPLACE FUNCTION wf.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_wfdef_updated
    BEFORE UPDATE ON wf.workflow_definition
    FOR EACH ROW EXECUTE FUNCTION wf.set_updated_at();
