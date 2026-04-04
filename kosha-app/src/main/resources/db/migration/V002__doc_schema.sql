-- ============================================================
-- V002: Document module — registry, versions, metadata, access
-- ============================================================

CREATE SCHEMA IF NOT EXISTS doc;

-- MIME type lookup
CREATE TABLE doc.mime_type (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(100) NOT NULL,
    extension   VARCHAR(20)  NOT NULL,
    mime_type   VARCHAR(200) NOT NULL,
    icon        VARCHAR(100),
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

-- Document categories
CREATE TABLE doc.document_category (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    description     TEXT,
    department_id   UUID REFERENCES ident.department(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_doccat_dept ON doc.document_category(department_id);

-- Document status lookup
CREATE TABLE doc.document_status_lookup (
    code            VARCHAR(30) PRIMARY KEY,
    display_name    VARCHAR(100) NOT NULL,
    sort_order      INTEGER NOT NULL
);

INSERT INTO doc.document_status_lookup (code, display_name, sort_order) VALUES
    ('DRAFT',       'Draft',        10),
    ('IN_REVIEW',   'In Review',    20),
    ('PUBLISHED',   'Published',    30),
    ('ARCHIVED',    'Archived',     40),
    ('SUPERSEDED',  'Superseded',   50),
    ('LEGAL_HOLD',  'Legal Hold',   60);

-- Core document registry
CREATE TABLE doc.document (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_number          VARCHAR(50) UNIQUE,
    title               VARCHAR(500) NOT NULL,
    description         TEXT,
    department_id       UUID NOT NULL REFERENCES ident.department(id),
    category_id         UUID REFERENCES doc.document_category(id),
    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                        REFERENCES doc.document_status_lookup(code),
    storage_mode        VARCHAR(20) NOT NULL DEFAULT 'VAULT'
                        CHECK (storage_mode IN ('VAULT', 'CONNECTOR')),
    mime_type_id        UUID REFERENCES doc.mime_type(id),
    workflow_type       VARCHAR(20) NOT NULL DEFAULT 'NONE'
                        CHECK (workflow_type IN ('NONE', 'LINEAR', 'PARALLEL')),
    checked_out         BOOLEAN NOT NULL DEFAULT FALSE,
    locked_by           UUID REFERENCES ident.user_profile(id),
    locked_at           TIMESTAMPTZ,
    retention_policy_id UUID,   -- FK added in V006 after ret schema exists
    review_cycle        INTERVAL,
    next_review_at      TIMESTAMPTZ,
    superseded_by       UUID REFERENCES doc.document(id),
    created_by          UUID NOT NULL REFERENCES ident.user_profile(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_doc_dept       ON doc.document(department_id);
CREATE INDEX idx_doc_status     ON doc.document(status);
CREATE INDEX idx_doc_category   ON doc.document(category_id);
CREATE INDEX idx_doc_created_by ON doc.document(created_by);
CREATE INDEX idx_doc_locked_by  ON doc.document(locked_by) WHERE locked_by IS NOT NULL;
CREATE INDEX idx_doc_deleted    ON doc.document(deleted_at) WHERE deleted_at IS NOT NULL;

-- Document versions
CREATE TABLE doc.document_version (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id         UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    version_number      VARCHAR(20) NOT NULL,
    parent_version_id   UUID REFERENCES doc.document_version(id),
    file_name           VARCHAR(500) NOT NULL,
    file_size_bytes     BIGINT,
    content_hash        VARCHAR(128),
    storage_key         VARCHAR(1000),
    change_summary      TEXT,
    status              VARCHAR(30) NOT NULL DEFAULT 'DRAFT'
                        REFERENCES doc.document_status_lookup(code),
    created_by          UUID NOT NULL REFERENCES ident.user_profile(id),
    publish_at          TIMESTAMPTZ,
    unpublish_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_docver_doc     ON doc.document_version(document_id);
CREATE INDEX idx_docver_created ON doc.document_version(created_by);
CREATE UNIQUE INDEX idx_docver_unique ON doc.document_version(document_id, version_number);

-- AI-generated metadata per version
CREATE TABLE doc.version_metadata (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version_id      UUID NOT NULL REFERENCES doc.document_version(id) ON DELETE CASCADE,
    summary         TEXT,
    extracted_text  TEXT,
    ai_confidence   DECIMAL(3,2) CHECK (ai_confidence >= 0 AND ai_confidence <= 1),
    human_reviewed  BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by     UUID REFERENCES ident.user_profile(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_vermeta_version ON doc.version_metadata(version_id);

-- Document owners (junction, replaces Entrepot CSV docOwners)
CREATE TABLE doc.document_owner (
    document_id     UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    user_profile_id UUID NOT NULL REFERENCES ident.user_profile(id) ON DELETE CASCADE,
    PRIMARY KEY (document_id, user_profile_id)
);

-- Document access groups (junction, replaces Entrepot CSV docGroups)
CREATE TABLE doc.document_access (
    document_id     UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    access_group_id UUID NOT NULL REFERENCES ident.access_group(id) ON DELETE CASCADE,
    permission      VARCHAR(20) NOT NULL DEFAULT 'READ'
                    CHECK (permission IN ('READ', 'WRITE')),
    PRIMARY KEY (document_id, access_group_id)
);

-- Document relationships (AI-suggested or manual)
CREATE TABLE doc.document_relationship (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_doc_id   UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    target_doc_id   UUID NOT NULL REFERENCES doc.document(id) ON DELETE CASCADE,
    relationship    VARCHAR(30) NOT NULL
                    CHECK (relationship IN ('RELATED_TO', 'SUPERSEDES', 'REFERENCES')),
    confidence      DECIMAL(3,2) CHECK (confidence >= 0 AND confidence <= 1),
    created_by      UUID REFERENCES ident.user_profile(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (source_doc_id, target_doc_id, relationship),
    CHECK (source_doc_id != target_doc_id)
);

CREATE INDEX idx_docrel_source ON doc.document_relationship(source_doc_id);
CREATE INDEX idx_docrel_target ON doc.document_relationship(target_doc_id);

-- Triggers
CREATE OR REPLACE FUNCTION doc.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_updated
    BEFORE UPDATE ON doc.document
    FOR EACH ROW EXECUTE FUNCTION doc.set_updated_at();

CREATE TRIGGER trg_version_metadata_updated
    BEFORE UPDATE ON doc.version_metadata
    FOR EACH ROW EXECUTE FUNCTION doc.set_updated_at();

-- Seed common MIME types
INSERT INTO doc.mime_type (name, extension, mime_type, icon) VALUES
    ('PDF Document',        '.pdf',  'application/pdf',                                                     'file-pdf'),
    ('Microsoft Word',      '.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'file-word'),
    ('Microsoft Word (Legacy)', '.doc', 'application/msword',                                               'file-word'),
    ('Microsoft Excel',     '.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',    'file-excel'),
    ('Microsoft Excel (Legacy)', '.xls', 'application/vnd.ms-excel',                                        'file-excel'),
    ('Microsoft PowerPoint', '.pptx', 'application/vnd.openxmlformats-officedocument.presentationml.presentation', 'file-powerpoint'),
    ('Plain Text',          '.txt',  'text/plain',                                                          'file-text'),
    ('PNG Image',           '.png',  'image/png',                                                           'file-image'),
    ('JPEG Image',          '.jpg',  'image/jpeg',                                                          'file-image'),
    ('TIFF Image',          '.tiff', 'image/tiff',                                                          'file-image');
