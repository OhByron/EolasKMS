# Kosha KMS

**Kosha** (कोश) — *Treasury of Knowledge*

A modern, containerized Knowledge Management System for small-to-medium enterprises. Kosha provides document lifecycle management, AI-powered classification and summarisation, full-text search, and retention policy enforcement — all self-hosted on an open-source stack.

Successor to [Entrepot (cf_qwikdoks2004)](https://github.com/OhByron), re-engineered for today's enterprise landscape.

---

## Features

- **Document management** — versioning with diff/rollback, check-in/check-out locking, configurable approval workflows
- **Dual storage modes** — *Vault* (managed MinIO object storage) or *Connector* (index in-place from SharePoint, file shares, etc.)
- **AI-powered knowledge** — automatic parsing, summarisation, keyword extraction, taxonomy classification, and confidence scoring via a Python sidecar
- **Enterprise search** — full-text and faceted search powered by OpenSearch, with AI-suggested related documents
- **Retention & compliance** — policy-driven lifecycle management, scheduled reviews, legal hold export
- **Multi-department RBAC** — Global Admin, Dept Admin, Editor, Contributor roles with SSO (SAML 2.0 / OIDC) and LDAP/AD integration via Keycloak
- **Notifications** — email, Teams/Slack webhooks, scheduled review reminders
- **Reporting** — compliance dashboards, usage analytics, taxonomy gap analysis, audit log viewer
- **Accessibility first** — WCAG 2.2 AA minimum, first-class screen reader support

## Architecture

Kosha is a **modular monolith** — a single Spring Boot application composed of domain modules that communicate via events over NATS JetStream.

```
┌──────────────────────────────────────────────────────┐
│                   kosha-app (API)                     │
│  ┌──────────┬──────────┬───────────┬───────────────┐  │
│  │ document │ identity │ taxonomy  │  workflow      │  │
│  │ storage  │ audit    │ search    │  retention     │  │
│  │ common   │ notify   │ reporting │  ...           │  │
│  └──────────┴──────────┴───────────┴───────────────┘  │
└────────────────────┬─────────────────────────────────┘
                     │ NATS JetStream
          ┌──────────┴──────────┐
          │   kosha-ai-sidecar  │  (Python: LangChain, spaCy, Tesseract)
          └─────────────────────┘

Infrastructure: PostgreSQL · OpenSearch · MinIO · NATS · Keycloak
```

### Modules

| Module | Purpose |
|---|---|
| `kosha-app` | Spring Boot host, REST API, security configuration |
| `kosha-common` | Shared domain primitives, base entities, API response types |
| `kosha-document` | Document CRUD, versioning, check-in/out |
| `kosha-storage` | MinIO integration, vault/connector abstraction |
| `kosha-identity` | User and department management, RBAC |
| `kosha-workflow` | Approval chains, review cycles |
| `kosha-taxonomy` | Taxonomy management, AI classification integration |
| `kosha-search` | OpenSearch indexing and query |
| `kosha-retention` | Retention policies, scheduled enforcement |
| `kosha-audit` | Audit trail capture and query |
| `kosha-notification` | Email/webhook dispatch |
| `kosha-reporting` | Dashboards and analytics |
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
| Kosha API | http://localhost:8080 |
| Keycloak admin | http://localhost:8180 |
| MinIO console | http://localhost:9001 |
| OpenSearch | http://localhost:9200 |
| NATS monitoring | http://localhost:8222 |

To include a local Ollama instance for AI:

```bash
docker compose --profile local-ai up -d
```

### 3. Build locally (without Docker)

```bash
# Backend
./gradlew build

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

## Project Status

Kosha is in early development (`0.1.0-SNAPSHOT`). The module structure, infrastructure, and core domain models are in place. Active development is underway.

## Licence

All dependencies are open-source and permissively licensed. Kosha itself is proprietary software.
