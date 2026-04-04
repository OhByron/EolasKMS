-- ============================================================
-- V006: Retention module — policies and reviews
-- ============================================================

CREATE SCHEMA IF NOT EXISTS ret;

-- Retention policies
CREATE TABLE ret.retention_policy (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(200) NOT NULL,
    description         TEXT,
    retention_period    INTERVAL NOT NULL,
    review_interval     INTERVAL,
    action_on_expiry    VARCHAR(30) NOT NULL
                        CHECK (action_on_expiry IN ('ARCHIVE', 'DELETE', 'REVIEW')),
    department_id       UUID REFERENCES ident.department(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retpol_dept ON ret.retention_policy(department_id);

-- Now add the deferred FK from doc.document → ret.retention_policy
ALTER TABLE doc.document
    ADD CONSTRAINT fk_doc_retention_policy
    FOREIGN KEY (retention_policy_id) REFERENCES ret.retention_policy(id);

-- Retention reviews (scheduled compliance checks)
CREATE TABLE ret.retention_review (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    policy_id       UUID NOT NULL REFERENCES ret.retention_policy(id),
    due_at          TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    reviewed_by     UUID REFERENCES ident.user_profile(id),
    outcome         VARCHAR(30)
                    CHECK (outcome IS NULL OR outcome IN ('RETAIN', 'ARCHIVE', 'DESTROY')),
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_retrev_doc     ON ret.retention_review(document_id);
CREATE INDEX idx_retrev_due     ON ret.retention_review(due_at) WHERE completed_at IS NULL;
CREATE INDEX idx_retrev_policy  ON ret.retention_review(policy_id);

-- Trigger
CREATE OR REPLACE FUNCTION ret.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_retpol_updated
    BEFORE UPDATE ON ret.retention_policy
    FOR EACH ROW EXECUTE FUNCTION ret.set_updated_at();
