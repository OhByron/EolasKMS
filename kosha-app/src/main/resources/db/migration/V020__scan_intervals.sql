-- ============================================================================
-- V020 — Per-department scan intervals for retention notifications
--
-- Global default is held in notif.notification_settings (singleton row).
-- Per-department overrides live on ident.department as scan_interval_hours.
-- last_scan_at tracks when the scanner last processed each department so the
-- runtime can respect the configured cadence across restarts.
--
-- Valid intervals (enforced at the service layer): 24, 48, 72, 168.
-- Scanner cadence cannot be disabled — minimum is 24h (daily).
-- ============================================================================

-- ── Global notification settings ────────────────────────────────
CREATE TABLE notif.notification_settings (
    id                              VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    default_scan_interval_hours     INTEGER NOT NULL DEFAULT 24,
    min_scan_interval_hours         INTEGER NOT NULL DEFAULT 24,
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (default_scan_interval_hours >= min_scan_interval_hours),
    CHECK (default_scan_interval_hours IN (24, 48, 72, 168)),
    CHECK (min_scan_interval_hours IN (24, 48, 72, 168))
);

INSERT INTO notif.notification_settings (id, default_scan_interval_hours, min_scan_interval_hours)
VALUES ('default', 24, 24)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE notif.notification_settings IS 'Singleton settings row for notification scanner defaults.';

-- ── Per-department overrides and scan tracking ───────────────────
ALTER TABLE ident.department
    ADD COLUMN scan_interval_hours INTEGER,
    ADD COLUMN last_scan_at        TIMESTAMPTZ;

-- Constrain to the same valid values as the global settings.
-- NULL means "use global default".
ALTER TABLE ident.department
    ADD CONSTRAINT department_scan_interval_check
    CHECK (scan_interval_hours IS NULL OR scan_interval_hours IN (24, 48, 72, 168));

COMMENT ON COLUMN ident.department.scan_interval_hours
    IS 'Override for scan cadence in hours. NULL = use notification_settings.default_scan_interval_hours.';
COMMENT ON COLUMN ident.department.last_scan_at
    IS 'Timestamp of the most recent successful retention scan for this department.';
