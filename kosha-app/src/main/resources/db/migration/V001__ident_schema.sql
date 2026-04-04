-- ============================================================
-- V001: Identity module — departments, user profiles, groups
-- ============================================================

CREATE SCHEMA IF NOT EXISTS ident;

-- Departments (hierarchical via parent_dept_id)
CREATE TABLE ident.department (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    manager_user_id UUID,
    parent_dept_id  UUID REFERENCES ident.department(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_dept_parent ON ident.department(parent_dept_id);
CREATE INDEX idx_dept_status ON ident.department(status);

-- User profiles (auth lives in Keycloak; this holds Kosha-specific state)
CREATE TABLE ident.user_profile (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_id     UUID NOT NULL UNIQUE,
    display_name    VARCHAR(200) NOT NULL,
    email           VARCHAR(255) NOT NULL,
    department_id   UUID NOT NULL REFERENCES ident.department(id),
    role            VARCHAR(30) NOT NULL
                    CHECK (role IN ('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR', 'CONTRIBUTOR')),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_user_dept ON ident.user_profile(department_id);
CREATE INDEX idx_user_keycloak ON ident.user_profile(keycloak_id);
CREATE INDEX idx_user_email ON ident.user_profile(email);
CREATE INDEX idx_user_role ON ident.user_profile(role);

-- Back-reference: department manager FK now that user_profile exists
ALTER TABLE ident.department
    ADD CONSTRAINT fk_dept_manager
    FOREIGN KEY (manager_user_id) REFERENCES ident.user_profile(id);

-- Access groups (map to LDAP/AD groups via external_ref)
CREATE TABLE ident.access_group (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    external_ref    VARCHAR(500),
    department_id   UUID REFERENCES ident.department(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ag_dept ON ident.access_group(department_id);

-- User ↔ Group membership (junction)
CREATE TABLE ident.user_group_membership (
    user_profile_id UUID NOT NULL REFERENCES ident.user_profile(id) ON DELETE CASCADE,
    access_group_id UUID NOT NULL REFERENCES ident.access_group(id) ON DELETE CASCADE,
    PRIMARY KEY (user_profile_id, access_group_id)
);

-- Trigger: auto-update updated_at
CREATE OR REPLACE FUNCTION ident.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_department_updated
    BEFORE UPDATE ON ident.department
    FOR EACH ROW EXECUTE FUNCTION ident.set_updated_at();

CREATE TRIGGER trg_user_profile_updated
    BEFORE UPDATE ON ident.user_profile
    FOR EACH ROW EXECUTE FUNCTION ident.set_updated_at();
