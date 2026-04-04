-- ============================================================
-- V005: Storage module — locations and connector sources
-- ============================================================

CREATE SCHEMA IF NOT EXISTS storage;

-- Storage locations (vault buckets or connector roots)
CREATE TABLE storage.storage_location (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mode            VARCHAR(20) NOT NULL
                    CHECK (mode IN ('VAULT', 'CONNECTOR')),
    name            VARCHAR(200) NOT NULL,
    config          JSONB NOT NULL DEFAULT '{}',
    department_id   UUID REFERENCES ident.department(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_storloc_dept ON storage.storage_location(department_id);
CREATE INDEX idx_storloc_mode ON storage.storage_location(mode);

-- Connector-mode external source definitions
CREATE TABLE storage.connector_source (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    location_id         UUID NOT NULL REFERENCES storage.storage_location(id) ON DELETE CASCADE,
    source_type         VARCHAR(30) NOT NULL
                        CHECK (source_type IN ('SHAREPOINT', 'SMB_SHARE', 'S3', 'AZURE_BLOB', 'GCS')),
    connection_config   JSONB NOT NULL DEFAULT '{}',
    scan_schedule       VARCHAR(50),
    last_scanned_at     TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connsrc_loc ON storage.connector_source(location_id);
