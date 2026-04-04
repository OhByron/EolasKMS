-- ============================================================
-- V009: Reporting module — materialized views for dashboards
-- ============================================================

CREATE SCHEMA IF NOT EXISTS report;

-- Retention compliance summary per department
CREATE MATERIALIZED VIEW report.retention_compliance AS
SELECT
    d.department_id,
    dept.name AS department_name,
    COUNT(*)                                                    AS total_documents,
    COUNT(*) FILTER (WHERE rr.id IS NOT NULL AND rr.completed_at IS NOT NULL)  AS reviews_completed,
    COUNT(*) FILTER (WHERE rr.id IS NOT NULL AND rr.completed_at IS NULL AND rr.due_at < now()) AS reviews_overdue,
    COUNT(*) FILTER (WHERE rr.id IS NOT NULL AND rr.completed_at IS NULL AND rr.due_at >= now()) AS reviews_pending
FROM doc.document d
JOIN ident.department dept ON dept.id = d.department_id
LEFT JOIN ret.retention_review rr ON rr.document_id = d.id
WHERE d.deleted_at IS NULL
GROUP BY d.department_id, dept.name
WITH NO DATA;

CREATE UNIQUE INDEX idx_retcomp_dept ON report.retention_compliance(department_id);

-- Document activity summary per department (last 30 days)
CREATE MATERIALIZED VIEW report.department_activity AS
SELECT
    ae.department_id,
    dept.name AS department_name,
    COUNT(*) FILTER (WHERE ae.event_type = 'doc.created')           AS documents_created,
    COUNT(*) FILTER (WHERE ae.event_type = 'doc.version.created')   AS versions_created,
    COUNT(*) FILTER (WHERE ae.event_type = 'wf.completed')          AS workflows_completed,
    COUNT(*) FILTER (WHERE ae.event_type = 'wf.rejected')           AS workflows_rejected
FROM audit.event ae
JOIN ident.department dept ON dept.id = ae.department_id
WHERE ae.occurred_at >= now() - INTERVAL '30 days'
  AND ae.department_id IS NOT NULL
GROUP BY ae.department_id, dept.name
WITH NO DATA;

CREATE UNIQUE INDEX idx_deptact_dept ON report.department_activity(department_id);

-- Taxonomy coverage: terms with document counts
CREATE MATERIALIZED VIEW report.taxonomy_coverage AS
SELECT
    tt.id           AS term_id,
    tt.label        AS term_label,
    tt.status       AS term_status,
    tt.source       AS term_source,
    COUNT(dc.id)    AS document_count
FROM tax.taxonomy_term tt
LEFT JOIN tax.document_classification dc ON dc.term_id = tt.id
WHERE tt.status IN ('ACTIVE', 'CANDIDATE')
GROUP BY tt.id, tt.label, tt.status, tt.source
WITH NO DATA;

CREATE UNIQUE INDEX idx_taxcov_term ON report.taxonomy_coverage(term_id);

-- Note: These materialized views should be refreshed periodically.
-- In production, use a scheduled job (Spring @Scheduled or pg_cron):
--   REFRESH MATERIALIZED VIEW CONCURRENTLY report.retention_compliance;
--   REFRESH MATERIALIZED VIEW CONCURRENTLY report.department_activity;
--   REFRESH MATERIALIZED VIEW CONCURRENTLY report.taxonomy_coverage;
