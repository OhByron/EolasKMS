# Backup & Restore

## What to back up

| Component       | Tool                    | Command                                                     | Notes                                    |
|-----------------|-------------------------|-------------------------------------------------------------|------------------------------------------|
| PostgreSQL      | `pg_dump`               | `pg_dump -U kosha -h localhost -p 5433 kosha > kosha.sql`   | Includes all schemas (doc, ident, wf, etc.) |
| MinIO           | `mc mirror`             | `mc mirror minio/kosha-vault ./backup/minio/`               | All document bytes, previews, OCR output |
| Keycloak        | realm export             | `docker exec kosha-keycloak-1 /opt/keycloak/bin/kc.sh export --dir /tmp/export --realm kosha` | Users, roles, clients, mappers |

## Backup script

```bash
#!/bin/bash
set -euo pipefail

BACKUP_DIR="./backup/$(date +%Y-%m-%d_%H%M)"
mkdir -p "$BACKUP_DIR"

echo "=== PostgreSQL ==="
docker exec kosha-postgres-1 pg_dump -U kosha kosha > "$BACKUP_DIR/kosha.sql"

echo "=== MinIO ==="
mc mirror --overwrite minio/kosha-vault "$BACKUP_DIR/minio/"

echo "=== Keycloak ==="
docker exec kosha-keycloak-1 /opt/keycloak/bin/kc.sh export \
  --dir /tmp/kc-export --realm kosha 2>/dev/null
docker cp kosha-keycloak-1:/tmp/kc-export "$BACKUP_DIR/keycloak/"

echo "=== Done: $BACKUP_DIR ==="
ls -lh "$BACKUP_DIR"
```

## Restore procedure

1. **Stop the application** — `docker compose down kosha-api`
2. **Restore PostgreSQL** — `docker exec -i kosha-postgres-1 psql -U kosha kosha < kosha.sql`
3. **Restore MinIO** — `mc mirror ./backup/minio/ minio/kosha-vault`
4. **Restore Keycloak** — `docker exec kosha-keycloak-1 /opt/keycloak/bin/kc.sh import --dir /tmp/kc-import --realm kosha`
5. **Start the application** — `docker compose up -d kosha-api`
6. **Verify** — hit `/actuator/health` and confirm all components report UP

## Recovery expectations

- **RPO (Recovery Point Objective)**: equal to backup frequency. With daily backups, worst case is 24 hours of data loss.
- **RTO (Recovery Time Objective)**: ~15 minutes for a small deployment (< 100GB MinIO). Dominated by MinIO mirror time for large vaults.

## Offsite strategy

Encrypt the backup directory with `gpg` or `age` before copying offsite:

```bash
tar czf - "$BACKUP_DIR" | age -r age1... > "$BACKUP_DIR.tar.gz.age"
```

Retention: keep 7 daily, 4 weekly, 12 monthly backups.
