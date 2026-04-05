-- ============================================================================
-- V026 — Email notification templates for workflow runtime events
-- ============================================================================

INSERT INTO notif.notification_template (id, event_type, channel, subject_template, body_template, locale)
VALUES
    -- Step assigned — sent to the assignee when a step becomes IN_PROGRESS
    (gen_random_uuid(),
     'wf.step.assigned',
     'EMAIL',
     'Review required: {{documentTitle}}',
     'Hello {{assigneeName}},

You have a new document awaiting your review in Eòlas.

Document: {{documentTitle}}
Department: {{departmentName}}
Submitted by: {{submitterName}}
Step: {{stepName}}
Due by: {{dueDate}}

Open your review inbox to approve or return the document to the submitter:

  {{inboxUrl}}

If you do not respond by the due date the step will be escalated to the configured escalation contact.

This is an automated notification from Eòlas KMS.',
     'en'),

    -- Workflow rejected — sent to the submitter when any step rejects the document
    (gen_random_uuid(),
     'wf.rejected-to-submitter',
     'EMAIL',
     'Changes requested: {{documentTitle}}',
     'Hello {{submitterName}},

Your document "{{documentTitle}}" has been returned to you for revision.

Rejected at step: {{stepName}}
Rejected by: {{rejectorName}}

Reviewer comments:
{{rejectionComments}}

The document has been moved back to DRAFT so you can revise it and resubmit. Resubmission creates a fresh review workflow — every step will review the revised document.

Open the document to make your changes:

  {{documentUrl}}

This is an automated notification from Eòlas KMS.',
     'en'),

    -- Step escalated — sent to the escalation contact when the primary assignee misses the deadline
    (gen_random_uuid(),
     'wf.step.escalated',
     'EMAIL',
     'Escalation: {{documentTitle}} review overdue',
     'Hello {{escalationName}},

A workflow step has been escalated to you because the primary reviewer did not respond within the configured time limit.

Document: {{documentTitle}}
Department: {{departmentName}}
Step: {{stepName}}
Previously assigned to: {{previousAssigneeName}}
New due date: {{newDueDate}}

You have been assigned as the escalation contact for this step. Please review and act on the document as soon as possible to keep the workflow moving.

Open your review inbox:

  {{inboxUrl}}

This is an automated notification from Eòlas KMS.',
     'en');
