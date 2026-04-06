-- ============================================================================
-- V034 -- Licence key storage (singleton row)
--
-- Allows admins to paste a licence key via the UI instead of requiring
-- an environment variable restart. The LicenceService reads from both
-- sources: DB row takes precedence over env var so that an admin-applied
-- key overrides the deployment default.
-- ============================================================================

CREATE TABLE public.licence_config (
    id          VARCHAR(50) PRIMARY KEY DEFAULT 'default',
    licence_key TEXT,
    applied_at  TIMESTAMPTZ,
    applied_by  UUID REFERENCES ident.user_profile(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO public.licence_config (id) VALUES ('default') ON CONFLICT DO NOTHING;

COMMENT ON TABLE public.licence_config
    IS 'Singleton row holding the active licence key. Applied via the admin UI.';
