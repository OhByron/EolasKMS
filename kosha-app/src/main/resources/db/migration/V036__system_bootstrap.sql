-- V036: First-boot bootstrap tracking.
--
-- The BootstrapAdminInitializer runs exactly once per database lifetime:
--   * generates a strong random password for the seed admin (admin@kosha.dev)
--   * resets the password in Keycloak via the admin REST API
--   * prints the credentials in a single-shot startup banner
--
-- After it succeeds it stamps `bootstrap_completed_at` here so it never
-- runs again. To force a re-bootstrap, set the column back to NULL.

CREATE TABLE public.system_bootstrap (
    id                       VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    bootstrap_completed_at   TIMESTAMPTZ,
    bootstrap_admin_email    VARCHAR(255),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO public.system_bootstrap (id) VALUES ('default');
