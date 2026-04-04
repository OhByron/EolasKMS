-- ============================================================
-- V007: Notification module — templates, logs, webhooks
-- ============================================================

CREATE SCHEMA IF NOT EXISTS notif;

-- Notification templates (Mustache/Handlebars bodies)
CREATE TABLE notif.notification_template (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type          VARCHAR(100) NOT NULL,
    channel             VARCHAR(20) NOT NULL
                        CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    subject_template    TEXT,
    body_template       TEXT NOT NULL,
    locale              VARCHAR(10) NOT NULL DEFAULT 'en',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notiftpl_event ON notif.notification_template(event_type);
CREATE UNIQUE INDEX idx_notiftpl_unique ON notif.notification_template(event_type, channel, locale);

-- Notification delivery log
CREATE TABLE notif.notification_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id     UUID REFERENCES notif.notification_template(id),
    recipient_id    UUID REFERENCES ident.user_profile(id),
    channel         VARCHAR(20) NOT NULL
                    CHECK (channel IN ('EMAIL', 'WEBHOOK')),
    subject         TEXT,
    body            TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    error_detail    TEXT,
    sent_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notiflog_recipient ON notif.notification_log(recipient_id);
CREATE INDEX idx_notiflog_status    ON notif.notification_log(status) WHERE status = 'PENDING';
CREATE INDEX idx_notiflog_created   ON notif.notification_log(created_at);

-- Webhook endpoint configurations
CREATE TABLE notif.webhook_config (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    url             VARCHAR(2000) NOT NULL,
    secret          VARCHAR(500),
    events          TEXT[] NOT NULL,
    department_id   UUID REFERENCES ident.department(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhookcfg_dept ON notif.webhook_config(department_id);
