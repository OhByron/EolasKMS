-- ============================================================================
-- V025 — Email notification template for admin-initiated password resets
-- ============================================================================

INSERT INTO notif.notification_template (id, event_type, channel, subject_template, body_template, locale)
VALUES
    (gen_random_uuid(),
     'user.password.reset',
     'EMAIL',
     'Your Eòlas password has been reset',
     'Hello {{userName}},

An administrator has reset the password for your Eòlas account in the {{departmentName}} department.

Your new temporary password is:

    {{temporaryPassword}}

Please sign in with this password and you will be prompted to choose a new one immediately. The temporary password can only be used once.

If you were not expecting this reset, contact your department administrator right away.

This is an automated notification from Eòlas KMS.',
     'en');
