# Eòlas

**Eòlas** — *Scottish Gaelic for "knowledge"*

A modern, containerized Knowledge Management System for small-to-medium enterprises. Eòlas provides document lifecycle management, AI-powered classification and summarisation, full-text search, and retention policy enforcement — all self-hosted on an open-source stack.

Successor to [Entrepot (cf_qwikdoks2004)](https://github.com/OhByron), re-engineered for today's enterprise landscape. Production domain: **eolaskms.com** (reserved).

> **Note on internal naming**: the codebase and Gradle modules are still prefixed `kosha-*` from the project's early days. These internal identifiers will be renamed in a future pass; user-visible strings, icons, and emails are all Eòlas-branded.

---

## Features

- **Document management** — versioning with diff/rollback, check-in/check-out locking, explicit owner + proxy delegation, configurable approval workflows
- **Dual storage modes** — *Vault* (managed MinIO object storage) or *Connector* (index in-place from SharePoint, file shares, etc.)
- **AI-powered knowledge** — automatic parsing, summarisation, keyword extraction, taxonomy classification, and confidence scoring via a Python sidecar
- **Enterprise search** — full-text and faceted search powered by OpenSearch, with AI-suggested related documents
- **Retention & compliance** — policy-driven lifecycle management, scheduled reviews with per-department cadence, legal hold with owner notifications
- **Multi-department RBAC** — Global Admin, Dept Admin, Editor, Contributor roles with SSO (SAML 2.0 / OIDC) and LDAP/AD integration via Keycloak
- **Configurable mail gateway** — 12 provider presets (SMTP, SendGrid, Mailgun, Postmark, SparkPost, Mailjet, AWS SES, Google Workspace, Gmail, Microsoft 365) with hot-reload and test-send
- **Notifications** — retention review warnings at 90/60/30 days and critical overdue alerts to document owners and proxies
- **Reporting** — document aging, critical items, legal holds dashboards with bulk and targeted notification dispatch
- **Accessibility first** — WCAG 2.2 AA minimum, first-class screen reader support

## Architecture

Eòlas is a **modular monolith** — a single Spring Boot application composed of domain modules that communicate via events over NATS JetStream.

```
┌──────────────────────────────────────────────────────┐
│                  Eòlas API (single JVM)               │
│  ┌──────────┬──────────┬───────────┬───────────────┐  │
│  │ document │ identity │ taxonomy  │  workflow      │  │
│  │ storage  │ audit    │ search    │  retention     │  │
│  │ common   │ notify   │ reporting │  ...           │  │
│  └──────────┴──────────┴───────────┴───────────────┘  │
└────────────────────┬─────────────────────────────────┘
                     │ NATS JetStream
          ┌──────────┴──────────┐
          │   AI sidecar        │  (Python: LangChain, spaCy, Tesseract)
          └─────────────────────┘

Infrastructure: PostgreSQL · OpenSearch · MinIO · NATS · Keycloak · Mailpit (dev)
```

### Modules (internal names)

| Module | Purpose |
|---|---|
| `kosha-app` | Spring Boot host, REST API, security configuration |
| `kosha-common` | Shared domain primitives, base entities, API response types |
| `kosha-document` | Document CRUD, versioning, check-in/out, owner/proxy assignment |
| `kosha-storage` | MinIO integration, vault/connector abstraction |
| `kosha-identity` | User and department management, RBAC |
| `kosha-workflow` | Approval chains, review cycles (in development) |
| `kosha-taxonomy` | Taxonomy management, AI classification integration |
| `kosha-search` | OpenSearch indexing and query |
| `kosha-retention` | Retention policies, scheduled reviews, per-department scan intervals |
| `kosha-audit` | Audit trail capture and query |
| `kosha-notification` | Mail gateway, email templates, delivery logging |
| `kosha-reporting` | Dashboards, aging/critical/legal-hold reports, notification dispatch |
| `kosha-web` | Frontend (SvelteKit + TypeScript) |
| `kosha-ai-sidecar` | Python service for NLP, OCR, and LLM integration |

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Kotlin 2.1 + Spring Boot 3.4, Java 21 |
| AI sidecar | Python, LangChain, spaCy, Tesseract |
| Frontend | SvelteKit + TypeScript |
| Search | OpenSearch 2.12 |
| Database | PostgreSQL 16 |
| Object storage | MinIO (S3-compatible) |
| Message broker | NATS with JetStream |
| Auth | Keycloak 24 (SAML / OIDC / LDAP) |
| Document parsing | Apache Tika |
| Mail capture (dev) | Mailpit |
| Orchestration | Docker Compose (dev/SMB) / Kubernetes + Helm (production) |

## Prerequisites

- **Docker** and **Docker Compose** v2+
- **JDK 21** (for local development)
- **Python 3.11+** (for AI sidecar development)
- **Node.js 20+** (for frontend development)

## Getting Started

### 1. Clone the repository

```bash
git clone https://github.com/OhByron/Kosha.git
cd Kosha
```

### 2. Start the full stack with Docker Compose

```bash
docker compose up -d
```

This brings up:

| Service | URL |
|---|---|
| Eòlas API | http://localhost:8080 |
| Eòlas Web (dev) | http://localhost:5175 |
| Keycloak admin | http://localhost:8180 |
| MinIO console | http://localhost:9001 |
| OpenSearch | http://localhost:9200 |
| NATS monitoring | http://localhost:8222 |
| Mailpit (captured emails) | http://localhost:8025 |

### First login

On the very first start, the kosha-api process generates a strong random password
for the seed admin account and prints it once to its logs in a prominently formatted
banner. **Look for it now and save it (e.g. password manager) — it is shown only once:**

```bash
docker logs eolaskms-kosha-api-1 | grep -A 8 "FIRST-BOOT ADMIN ACCOUNT"
```

You should see something like:

```
============================================================================
  EOLAS KMS  -  FIRST-BOOT ADMIN ACCOUNT
  ------------------------------------------------------------------------
    email      : admin@kosha.dev
    password   : <a 24-character random string>
    sign-in URL: http://localhost:5173 (or your configured kosha-web origin)
  ------------------------------------------------------------------------
  This password is shown ONLY ONCE. Save it now (e.g. password manager).
  After signing in, create your own GLOBAL_ADMIN user and either change
  this seed account's password or delete the account before exposing this
  instance to anyone outside your local machine.
============================================================================
```

Two test accounts (Editor, Contributor) are also seeded with weak, well-known
passwords for local development:

| Email | Password | Role |
|---|---|---|
| `admin@kosha.dev` | *(see banner above)* | Global Admin |
| `editor@kosha.dev` | `editor` | Editor |
| `contributor@kosha.dev` | `contributor` | Contributor |

If the bootstrap step fails for any reason (e.g. Keycloak unreachable at startup),
the seed admin's password falls back to the realm-export default `admin` so you
can still sign in and recover. To re-run the bootstrap, set
`public.system_bootstrap.bootstrap_completed_at` back to `NULL` and restart
kosha-api.

**Before going to production:** create your own GLOBAL_ADMIN user via
Administration → Users → Add User, then delete or deactivate the seeded
dev accounts. Also change the Keycloak admin password (default: `admin` / `admin`)
at http://localhost:8180.

To include a local Ollama instance for AI:

```bash
docker compose --profile local-ai up -d
```

### 3. Build locally (without Docker)

```bash
# Backend
./gradlew build

# Frontend
cd kosha-web
npm install
npm run dev

# AI sidecar
cd kosha-ai-sidecar
pip install -e ".[dev]"
```

## Configuration

The application is configured via environment variables or `application.yml`. Key settings:

| Variable | Description | Default |
|---|---|---|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://postgres:5432/kosha` |
| `KOSHA_NATS_URL` | NATS server URL | `nats://nats:4222` |
| `KOSHA_OPENSEARCH_URL` | OpenSearch URL | `http://opensearch:9200` |
| `KOSHA_STORAGE_MINIO_ENDPOINT` | MinIO endpoint | `http://minio:9000` |
| `KOSHA_AI_LLM_PROVIDER` | LLM provider (`ollama`, `anthropic`, etc.) | `ollama` |
| `KOSHA_CRYPTO_PASSWORD` | Master key for encrypting SMTP credentials at rest | (dev fallback — **must set in production**) |
| `KOSHA_CRYPTO_SALT` | Hex-encoded salt for key derivation | (dev fallback — **must set in production**) |

The mail gateway, notification cadence, and retention policies are all configured through the admin UI after first login — no environment variables required.

## Project Status

Eòlas is in active development (`0.1.0-SNAPSHOT`). Completed and working:

- ✅ Modular monolith infrastructure, Docker Compose stack
- ✅ Document CRUD with versioning, soft delete, owner + proxy model
- ✅ Retention policies, scheduled reviews with 90/60/30/overdue notifications
- ✅ Reporting: document aging, critical items, legal holds
- ✅ Configurable mail gateway with 12 provider presets, hot-reload, test-send
- ✅ Per-department notification scan cadence with global defaults
- ✅ Mailpit integration for dev email capture

In progress:

- 🚧 User creation/invitation flow (manual and future SSO/LDAP)
- 🚧 Department admin tooling for team and workflow management
- 🚧 Workflow engine (linear and parallel approval chains with named participants)
- 🚧 Internal code rename from `kosha-*` to `eolas-*`

## Licence

All dependencies are open-source and permissively licensed. Eòlas itself is proprietary software.
