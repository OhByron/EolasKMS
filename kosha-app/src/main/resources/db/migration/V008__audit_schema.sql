-- ============================================================
-- V008: Audit module — append-only event store
-- ============================================================

CREATE SCHEMA IF NOT EXISTS audit;

-- Core event log (append-only, never updated or deleted)
CREATE TABLE audit.event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL,
    aggregate_type  VARCHAR(50)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    actor_id        UUID,
    department_id   UUID,
    payload         JSONB        NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Primary query patterns:
-- 1. "Show me everything that happened to document X" → aggregate index
-- 2. "Show me everything user Y did" → actor index
-- 3. "Show me all doc.created events" → event_type index
-- 4. "Audit log for department Z" → department + time index

CREATE INDEX idx_audit_aggregate    ON audit.event(aggregate_type, aggregate_id, occurred_at DESC);
CREATE INDEX idx_audit_actor        ON audit.event(actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX idx_audit_event_type   ON audit.event(event_type, occurred_at DESC);
CREATE INDEX idx_audit_dept_time    ON audit.event(department_id, occurred_at DESC) WHERE department_id IS NOT NULL;
CREATE INDEX idx_audit_occurred     ON audit.event(occurred_at DESC);

-- GIN index on payload for ad-hoc JSONB queries
CREATE INDEX idx_audit_payload ON audit.event USING gin (payload);

-- Note: For production with high write volumes, partition by month:
-- CREATE TABLE audit.event (...) PARTITION BY RANGE (occurred_at);
-- Partitions can be added via a scheduled migration or management script.

-- Protect against accidental mutations
CREATE OR REPLACE FUNCTION audit.prevent_mutation()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit.event is append-only: % operations are not allowed', TG_OP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_no_update
    BEFORE UPDATE ON audit.event
    FOR EACH ROW EXECUTE FUNCTION audit.prevent_mutation();

CREATE TRIGGER trg_audit_no_delete
    BEFORE DELETE ON audit.event
    FOR EACH ROW EXECUTE FUNCTION audit.prevent_mutation();
