# Monitoring Setup — Prometheus + Grafana

This guide walks through setting up Prometheus and Grafana to monitor
an Eòlas KMS deployment. By the end you'll have a dashboard showing
JVM metrics, HTTP traffic, workflow engine activity, document previews,
and the health of every external dependency.

## Prerequisites

- Docker Compose (already running Eòlas)
- The Eòlas backend exposes `/actuator/prometheus` (enabled by default)
- The Eòlas backend exposes `/actuator/health` (enabled by default)

## 1. Add Prometheus + Grafana to your stack

Add these services to your `docker-compose.yml` (or a separate
`docker-compose.monitoring.yml` overlay):

```yaml
services:
  prometheus:
    image: prom/prometheus:v2.53.0
    ports:
      - "9090:9090"
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    restart: unless-stopped

  grafana:
    image: grafana/grafana:11.1.0
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana-data:/var/lib/grafana
    restart: unless-stopped

volumes:
  prometheus-data:
  grafana-data:
```

## 2. Configure Prometheus to scrape Eòlas

Create `infra/prometheus/prometheus.yml`:

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'eolas-api'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['kosha-api:8080']   # or localhost:8081 if running outside Docker
    # If you're running the backend outside Docker on a different port:
    # - targets: ['host.docker.internal:8081']
```

## 3. Start the monitoring stack

```bash
docker compose up -d prometheus grafana
```

Verify Prometheus is scraping:
- Open http://localhost:9090/targets
- You should see `eolas-api` with State = UP

## 4. Import the Eòlas Grafana dashboard

1. Open Grafana at http://localhost:3000 (admin / admin)
2. Go to **Dashboards → Import**
3. Upload the file `infra/grafana/eolas-dashboard.json`
4. Select your Prometheus data source when prompted
5. Click **Import**

You'll see 12 panels covering:

| Panel                       | What it shows                                              |
|-----------------------------|------------------------------------------------------------|
| JVM Heap Usage              | Memory consumption by pool (eden, old gen, etc.)           |
| HTTP Request Rate           | Requests/sec by method, URI, and status code               |
| Workflow Instances          | Total workflow instances created                           |
| Uploads Stored              | Files stored in MinIO (success vs failure)                 |
| Document Previews           | Preview requests served vs not-found                       |
| Office Conversions          | LibreOffice conversion count + cache hits                  |
| Office Conversion Latency   | p95 conversion time (should be < 5s for normal docs)       |
| Signatures Created          | Electronic signatures applied                              |
| Share Links                 | Links created + anonymous accesses                         |
| Import Dry Runs / Rows      | Bulk import validation activity                            |
| DB Connection Pool          | HikariCP active/idle/pending connections                   |
| Scheduler Activity          | Background task execution (escalation scanner, retention)  |

## 5. Available Prometheus metrics

All Eòlas-specific metrics are prefixed with `eolas_`. Here's the
complete list as of Pass 6:

```
# Storage
eolas_uploads_stored_total{outcome="success|failure"}

# Document preview
eolas_preview_served_total{outcome="success|not_found|deleted"}
eolas_preview_office_total{outcome="converted|cache_hit|failed"}
eolas_preview_office_duration_seconds{quantile}

# Signatures
eolas_signatures_total

# Share links
eolas_share_links_total
eolas_share_links_accessed_total{outcome="allowed|expired|revoked|wrong_status"}

# Bulk import
eolas_import_dry_runs_total
eolas_import_rows_validated_total{outcome="ok|fail"}
eolas_import_users_dry_runs_total
eolas_import_users_rows_validated_total{outcome="ok|fail"}
```

Plus all standard Spring Boot Actuator metrics:
- `jvm_memory_*`, `jvm_gc_*`, `jvm_threads_*`
- `http_server_requests_seconds_*`
- `hikaricp_connections_*`
- `executor_*` (thread pools)

## 6. Setting up alerts (optional)

Example Alertmanager rules (add to Prometheus config):

```yaml
groups:
  - name: eolas-alerts
    rules:
      - alert: HighUploadFailureRate
        expr: rate(eolas_uploads_stored_total{outcome="failure"}[5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Upload failures exceeding threshold"

      - alert: EscalationScannerStalled
        expr: time() - max(eolas_share_links_accessed_total) > 3600
        for: 15m
        labels:
          severity: warning
        annotations:
          summary: "No scanner activity for over an hour"

      - alert: HealthCheckDown
        expr: up{job="eolas-api"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Eòlas API is unreachable"
```

---

# Health Indicators

## What they are

Eòlas includes custom Spring Boot Actuator health indicators for every
external dependency the application talks to. They're registered as
Spring beans and automatically included in the `/actuator/health`
composite endpoint.

## Accessing health status

```bash
# Full health with component details
curl -s http://localhost:8081/actuator/health | jq .

# Specific component
curl -s http://localhost:8081/actuator/health/minio | jq .
curl -s http://localhost:8081/actuator/health/keycloak | jq .
curl -s http://localhost:8081/actuator/health/nats | jq .
```

## Available health indicators

| Indicator          | Bean name         | What it probes                                     | Timeout |
|--------------------|-------------------|----------------------------------------------------|---------|
| PostgreSQL         | `db`              | Built-in Spring `DataSourceHealthIndicator`        | Default |
| MinIO              | `minio`           | `GET {endpoint}/minio/health/live`                 | 5s      |
| Keycloak           | `keycloak`        | `GET {serverUrl}/realms/{realm}/.well-known/openid-configuration` | 5s |
| NATS               | `nats`            | `GET http://{nats-host}:8222/healthz`              | 5s      |
| OpenSearch         | `opensearch`      | `GET {url}/_cluster/health` (checks for red status) | 5s     |
| Mail Gateway       | `mail-gateway`    | TCP socket connect to `{host}:{port}`              | 5s      |
| Preview Sidecar    | `preview-sidecar` | `GET {sidecarUrl}/health` (reports "not configured" if URL is blank) | 5s |
| Disk Space         | `diskSpace`       | Built-in Spring indicator                          | Default |

## Response format

```json
{
  "status": "UP",
  "components": {
    "db": { "status": "UP", "details": { "database": "PostgreSQL" } },
    "keycloak": { "status": "UP", "details": { "realm": "kosha" } },
    "minio": { "status": "UP", "details": { "endpoint": "http://localhost:9000" } },
    "nats": { "status": "UP" },
    "opensearch": { "status": "UP", "details": { "cluster": "green" } },
    "mail-gateway": { "status": "UP", "details": { "host": "localhost:1025" } },
    "preview-sidecar": { "status": "UP", "details": { "url": "http://localhost:9100" } }
  }
}
```

## How to use in production

1. **Kubernetes liveness probe**: point at `/actuator/health/liveness`
   (returns 200 if the JVM is alive, regardless of downstream deps)
2. **Kubernetes readiness probe**: point at `/actuator/health/readiness`
   (returns 200 only if all dependencies are healthy)
3. **External monitoring** (Uptime Robot, Pingdom, etc.): hit
   `/actuator/health` and alert on any non-200 response
4. **Grafana**: Prometheus can scrape the health endpoint via the
   `probe` exporter if you want per-component time series — but
   for most deployments the `/actuator/health` JSON endpoint polled
   by your monitoring tool is sufficient

## Adding a new health indicator

If you add a new external dependency:

1. Create a new `@Component` class implementing `HealthIndicator`
   in `kosha-app/src/main/kotlin/dev/kosha/app/health/`
2. Inject the connection config via `@Value`
3. Implement `health()` with a lightweight probe + 5s timeout
4. Return `Health.up()` with relevant details, or `Health.down(ex)`
5. The indicator auto-registers — no config changes needed

## The `ExternalServiceHealthIndicators.kt` file

Located at:
```
kosha-app/src/main/kotlin/dev/kosha/app/health/ExternalServiceHealthIndicators.kt
```

This single file contains all 6 custom indicators as separate classes.
They're grouped in one file because they follow the same pattern (HTTP
probe or TCP connect with timeout) and keeping them together makes the
ops surface easy to audit. If any indicator grows complex enough to
warrant its own file, split it out — Spring discovers beans by
annotation, not by file location.

Each indicator:
- Uses `java.net.http.HttpClient` (not RestTemplate) for zero Spring
  web dependency — the health check must work even if the web layer
  is partially broken
- Has a 5-second hard timeout so a hung dependency doesn't block the
  health endpoint for other consumers
- Reports the connection target in `details` so ops can see which
  endpoint is being checked without reading the code
