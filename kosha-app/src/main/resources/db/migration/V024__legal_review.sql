-- ============================================================================
-- V024 — Legal review flags and settings
--
-- Adds flags on documents, categories, and departments to support a parallel
-- legal-review branch in workflows. This migration is Pass 1 of the legal
-- review feature — it establishes the data model and seeds sensible defaults.
-- The actual engine behaviour (creating a legal review step instance when a
-- document is submitted) lands with the workflow engine in a later pass.
--
-- Design summary:
--   * doc.document.requires_legal_review       — per-document flag, set at upload
--   * doc.document.legal_reviewer_id           — the chosen reviewer (FK)
--   * doc.document_category.suggests_legal_review — pre-tick hint on upload form
--   * ident.department.handles_legal_review    — marks departments as eligible
--                                                to provide legal reviewers
--   * notif.legal_review_settings              — singleton with global defaults
-- ============================================================================

-- ── doc.document columns ───────────────────────────────────────
ALTER TABLE doc.document
    ADD COLUMN requires_legal_review BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN legal_reviewer_id     UUID REFERENCES ident.user_profile(id);

-- If legal review is required, the reviewer must be set. Enforced at the
-- service layer too (clearer error messages), but the DB check guarantees
-- the invariant.
ALTER TABLE doc.document
    ADD CONSTRAINT document_legal_reviewer_required
    CHECK (
        requires_legal_review = FALSE
        OR legal_reviewer_id IS NOT NULL
    );

CREATE INDEX idx_document_legal_reviewer
    ON doc.document(legal_reviewer_id)
    WHERE legal_reviewer_id IS NOT NULL;

COMMENT ON COLUMN doc.document.requires_legal_review IS
    'Set by the submitter at upload. When true, the workflow engine adds a parallel legal review step assigned to legal_reviewer_id.';
COMMENT ON COLUMN doc.document.legal_reviewer_id IS
    'The specific user selected as legal reviewer. Must be a member of a department where handles_legal_review=true and must be ACTIVE.';

-- ── doc.document_category: category-level hint ─────────────────
ALTER TABLE doc.document_category
    ADD COLUMN suggests_legal_review BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN doc.document_category.suggests_legal_review IS
    'When true, the upload form pre-ticks the "requires legal review" checkbox when this category is selected. Advisory only — submitter can always override.';

-- Backfill: Policy, Contract, Procedure — documents in these categories
-- typically warrant a legal review. "Contract" does not exist in the seed
-- data but the clause will run anyway (affecting 0 rows) so fresh installs
-- that add it later inherit the default.
UPDATE doc.document_category
SET suggests_legal_review = TRUE
WHERE name IN ('Policy', 'Contract', 'Procedure');

-- ── ident.department: legal review provider flag ───────────────
ALTER TABLE ident.department
    ADD COLUMN handles_legal_review BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN ident.department.handles_legal_review IS
    'When true, members of this department appear in the "Legal reviewer" dropdown on the document upload form. Set by GLOBAL_ADMIN only.';

CREATE INDEX idx_department_legal_review
    ON ident.department(id)
    WHERE handles_legal_review = TRUE;

-- Backfill: flag "Legal & Compliance" (preferred) and "Legal" (fallback
-- name used in some test data) so the feature has reviewers available
-- on first run.
UPDATE ident.department
SET handles_legal_review = TRUE
WHERE name IN ('Legal & Compliance', 'Legal');

-- ── notif.legal_review_settings: singleton global config ────────
CREATE TABLE notif.legal_review_settings (
    id                      VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    default_time_limit_days INTEGER NOT NULL DEFAULT 5,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (default_time_limit_days BETWEEN 1 AND 90)
);

INSERT INTO notif.legal_review_settings (id, default_time_limit_days)
VALUES ('default', 5)
ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE notif.legal_review_settings IS
    'Singleton settings row for legal review defaults. Global admins edit this via /api/v1/admin/legal-review-settings.';

-- ── Email template for legal review pre-selection notification ──
INSERT INTO notif.notification_template (id, event_type, channel, subject_template, body_template, locale)
VALUES (
    gen_random_uuid(),
    'doc.legal-review.pre-selected',
    'EMAIL',
    'You have been chosen as the legal reviewer for {{documentTitle}}',
    'Dear {{reviewerName}},

You have been pre-selected as the legal reviewer for a document awaiting submission to review:

  Document: {{documentTitle}}
  Submitter: {{submitterName}}
  Department: {{departmentName}}

No action is required from you at this moment. When the submitter formally
submits the document for review, you will receive a follow-up email with a
link to approve or reject it. You will have {{timeLimitDays}} days to respond
at that time, after which the request will escalate to your department admin.

This is an automated notification from Eòlas.',
    'en'
);
