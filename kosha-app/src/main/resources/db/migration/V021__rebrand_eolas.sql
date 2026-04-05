-- ============================================================================
-- V021 — Rebrand Kosha → Eòlas
--
-- Updates user-visible strings in database seed data (notification templates,
-- mail gateway defaults). Only rewrites rows that still hold the original
-- Kosha defaults so any admin customisations are preserved.
--
-- Internal code identifiers (package names, module names, env vars) are NOT
-- touched by this migration — they will be renamed in a separate pass.
-- ============================================================================

-- ── Mail gateway column defaults (for future inserts) ───────
ALTER TABLE notif.mail_gateway_config
    ALTER COLUMN from_email SET DEFAULT 'notifications@eolaskms.com',
    ALTER COLUMN from_name  SET DEFAULT 'Eòlas';

-- ── Mail gateway default row ─────────────────────────────────
UPDATE notif.mail_gateway_config
SET from_email = 'notifications@eolaskms.com',
    from_name  = 'Eòlas',
    updated_at = now()
WHERE id = 'default'
  AND from_email = 'notifications@kosha.local'
  AND from_name  = 'Kosha KMS';

-- ── Notification templates ───────────────────────────────────
UPDATE notif.notification_template
SET body_template = REPLACE(body_template, 'Kosha KMS', 'Eòlas')
WHERE body_template LIKE '%Kosha KMS%';
