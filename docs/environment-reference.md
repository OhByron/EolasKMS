# Environment Variable Reference

All environment variables that Eòlas reads, grouped by concern.
Variables marked **required** must be set in production.
Variables with defaults are safe to omit in development.

## Database

| Variable                      | Required | Default                              | Notes                              |
|-------------------------------|:--------:|--------------------------------------|------------------------------------|
| `SPRING_DATASOURCE_URL`       | Yes      | `jdbc:postgresql://localhost:5432/kosha` | JDBC URL for PostgreSQL           |
| `SPRING_DATASOURCE_USERNAME`  | No       | `kosha`                              |                                    |
| `SPRING_DATASOURCE_PASSWORD`  | No       | `kosha`                              | Use a strong password in prod      |

## Keycloak

| Variable                      | Required | Default                              | Notes                              |
|-------------------------------|:--------:|--------------------------------------|------------------------------------|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Yes | `http://localhost:8180/realms/kosha` | Keycloak realm issuer URI |
| `KEYCLOAK_SERVER_URL`         | No       | `http://localhost:8180`              | Base URL (no /realms)              |
| `KEYCLOAK_REALM`              | No       | `kosha`                              |                                    |
| `KEYCLOAK_ADMIN_CLIENT_ID`    | No       | `kosha-backend`                      | Service account client             |
| `KEYCLOAK_ADMIN_CLIENT_SECRET`| No       | `kosha-backend-dev-secret`           | Set to real secret in prod         |
| `KEYCLOAK_MASTER_USERNAME`    | No       | `admin`                              | Only used at first bootstrap       |
| `KEYCLOAK_MASTER_PASSWORD`    | No       | `admin`                              | Only used at first bootstrap       |

## Storage (MinIO)

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `KOSHA_STORAGE_MINIO_ENDPOINT`| No       | `http://localhost:9000`              |
| `KOSHA_STORAGE_MINIO_ACCESS_KEY` | No    | `minioadmin`                         |
| `KOSHA_STORAGE_MINIO_SECRET_KEY` | No    | `minioadmin`                         |
| `KOSHA_STORAGE_MINIO_BUCKET`  | No       | `kosha-vault`                        |

## Messaging (NATS)

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `KOSHA_NATS_URL`              | No       | `nats://localhost:4222`              |

## Search (OpenSearch)

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `KOSHA_OPENSEARCH_URL`        | No       | `https://localhost:9200`             |

## Mail

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `SPRING_MAIL_HOST`            | No       | `localhost`                          |
| `SPRING_MAIL_PORT`            | No       | `1025`                               |

## Encryption (SMTP credentials at rest)

| Variable                      | Required | Default                              | Notes                              |
|-------------------------------|:--------:|--------------------------------------|------------------------------------|
| `KOSHA_CRYPTO_PASSWORD`       | **Yes (prod)** | dev default               | Strong random string               |
| `KOSHA_CRYPTO_SALT`           | **Yes (prod)** | dev default               | Hex-encoded, ≥ 16 chars            |

## Preview sidecar (optional)

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `KOSHA_PREVIEW_SIDECAR_URL`   | No       | _(blank = disabled)_                 |

## Security

| Variable                      | Required | Default                              | Notes                              |
|-------------------------------|:--------:|--------------------------------------|------------------------------------|
| `KOSHA_SECURITY_DEV_BYPASS_ENABLED` | No | `false`                         | **Never true in production**       |

## Server

| Variable                      | Required | Default                              |
|-------------------------------|:--------:|--------------------------------------|
| `SERVER_PORT`                 | No       | `8080`                               |
