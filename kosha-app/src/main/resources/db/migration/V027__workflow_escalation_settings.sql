-- ============================================================================
-- V027 — Singleton settings for the workflow escalation scanner
--
-- Lets a GLOBAL_ADMIN tune how often overdue workflow steps are checked and
-- reassigned to their escalation contact. The scanner itself ticks every
-- minute and reads this row on each tick to decide whether to run.
-- last_scan_at is updated on every successful scan so the cadence survives
-- restarts and horizontal scaling (a second instance reading the same row
-- will skip a scan another just performed).
--
-- Valid intervals (enforced at the service layer): 5, 15, 30, 60, 180, 360
-- minutes. Minimum is 5 to protect the database from an admin setting the
-- value to 1 and hammering it.
-- ============================================================================

CREATE TABLE notif.workflow_escalation_settings (
    id                      VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    scan_interval_minutes   INTEGER     NOT NULL DEFAULT 15,
    last_scan_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (scan_interval_minutes IN (5, 15, 30, 60, 180, 360))
);

INSERT INTO notif.workflow_escalation_settings (id, scan_interval_minutes)
VALUES ('default', 15)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE notif.workflow_escalation_settings
    IS 'Singleton controlling cadence of the workflow escalation scanner.';
COMMENT ON COLUMN notif.workflow_escalation_settings.scan_interval_minutes
    IS 'Minutes between scans. Valid values enforced at service layer.';
COMMENT ON COLUMN notif.workflow_escalation_settings.last_scan_at
    IS 'Most recent successful scan completion. Set by the scanner itself.';
