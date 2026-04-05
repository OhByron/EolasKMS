-- ============================================================================
-- V017 — Email notification templates for legal hold and critical retention
-- ============================================================================

INSERT INTO notif.notification_template (id, event_type, channel, subject_template, body_template, locale)
VALUES
    -- Legal hold applied — sent to document owner (and proxy)
    (gen_random_uuid(),
     'doc.legal-hold.applied',
     'EMAIL',
     'Legal Hold: {{documentTitle}} ({{docNumber}})',
     'Dear {{ownerName}},

Your document "{{documentTitle}}" ({{docNumber}}) in the {{departmentName}} department has been placed on legal hold by {{actorName}} on {{occurredAt}}.

While under legal hold, this document:
  - Cannot be deleted or archived
  - Is exempt from retention policy enforcement
  - Must be preserved in its current state

If you have questions about this hold, please contact your department administrator or the legal team.

Document: {{documentUrl}}

This is an automated notification from Kosha KMS.',
     'en'),

    -- Critical retention review overdue — sent to document owner (and proxy)
    (gen_random_uuid(),
     'retention.review.critical',
     'EMAIL',
     'ACTION REQUIRED: Retention Review Overdue — {{documentTitle}} ({{daysOverdue}} days)',
     'Dear {{ownerName}},

The retention review for your document "{{documentTitle}}" ({{docNumber}}) is critically overdue by {{daysOverdue}} days.

Document details:
  - Department: {{departmentName}}
  - Retention policy: {{policyName}}
  - Review was due: {{reviewDueDate}}
  - Days overdue: {{daysOverdue}}

Please review this document and take the appropriate action (retain, archive, or destroy) as soon as possible.

Document: {{documentUrl}}

This is an automated notification from Kosha KMS.',
     'en');
