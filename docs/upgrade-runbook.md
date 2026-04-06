# Upgrade Runbook

## How upgrades work

Eòlas uses Flyway for database migrations. Every release ships with
numbered migration files (V001, V002, ..., V033, etc.) that run
automatically on application startup. Flyway tracks which migrations
have already been applied in the `flyway_schema_history` table and
only runs new ones.

**This means upgrading is: pull the new image, restart, done.**

The application will not start if a migration fails — it exits with
an error log pointing to the failing SQL statement. No partial state
is possible because each migration runs in a transaction.

## Pre-upgrade checklist

1. **Back up everything** — see `backup-restore.md`
2. **Read the release notes** — look for breaking changes, new
   environment variables, or new services
3. **Verify disk space** — Flyway migrations are small but MinIO
   data can grow; ensure the volume has headroom
4. **Notify users** — upgrades cause a brief downtime (~15–30 seconds
   for schema migrations, longer if Keycloak config changes)

## Upgrade steps

```bash
# 1. Pull new images
docker compose pull

# 2. Stop the API (infra stays up)
docker compose stop kosha-api

# 3. Start the API — Flyway runs on boot
docker compose up -d kosha-api

# 4. Watch the logs for migration output
docker compose logs -f kosha-api | head -50
# Look for: "Successfully applied N migration(s)"

# 5. Verify health
curl -sf http://localhost:8081/actuator/health | jq .
```

## Rollback strategy

Flyway does **not** support down-migrations. If a migration breaks:

1. Stop the application immediately
2. Restore from the backup taken in the pre-upgrade checklist
3. Start the old version of the application
4. Report the issue

**Never** manually edit `flyway_schema_history` unless directed by
the development team. The checksums are integrity-critical.

## Downtime expectations

| Upgrade class            | Expected downtime |
|--------------------------|-------------------|
| Code-only (no migration) | < 10 seconds      |
| Schema migration         | 15–60 seconds     |
| Keycloak config change   | 1–5 minutes       |
| Full stack rebuild       | 5–15 minutes      |
