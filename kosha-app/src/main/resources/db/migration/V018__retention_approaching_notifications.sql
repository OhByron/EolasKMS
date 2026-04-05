-- ============================================================================
-- V018 — Retention approaching notification tracking + email template
-- ============================================================================

-- Track which (review, threshold) pairs have been notified to prevent duplicates.
-- The scanner runs daily; this table ensures each warning is sent only once.
CREATE TABLE ret.review_notification_sent (
    review_id       UUID NOT NULL REFERENCES ret.retention_review(id) ON DELETE CASCADE,
    threshold_days  INTEGER NOT NULL,
    sent_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, threshold_days)
);

-- Email template for approaching reviews
INSERT INTO notif.notification_template (id, event_type, channel, subject_template, body_template, locale)
VALUES (
    gen_random_uuid(),
    'retention.review.approaching',
    'EMAIL',
    'Upcoming Review: {{documentTitle}} — due in {{daysUntilDue}} days',
    'Dear {{ownerName}},

Your document "{{documentTitle}}" ({{docNumber}}) has a retention review approaching.

Review details:
  - Due date: {{dueDate}}
  - Days remaining: {{daysUntilDue}}
  - Retention policy: {{policyName}}
  - Department: {{departmentName}}

Please plan to review this document before the due date. You will receive additional reminders at 60 and 30 days if the review remains outstanding.

Document: {{documentUrl}}

This is an automated notification from Kosha KMS.',
    'en'
);
