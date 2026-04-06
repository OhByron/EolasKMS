# Eòlas vs the market
### How Eòlas compares to leading document & knowledge management systems

---

## At a glance

| Capability | Eòlas | SharePoint / M365 | M-Files | DocuWare | Paperless-ngx | Mayan EDMS | Alfresco |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Self-hosted / on-premises** | ✅ | ⚠️ On-prem costly | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Open source** | ✅ | ❌ | ❌ | ❌ | ✅ | ✅ | ⚠️ Community |
| **Cloud-agnostic** | ✅ | ❌ Azure-tied | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ |
| **Configurable workflows** | ✅ Linear + Parallel + Conditional | ⚠️ Power Automate | ✅ | ✅ | ❌ | ⚠️ Basic | ✅ |
| **Conditional workflow routing** | ✅ JSON Logic | ❌ Separate tool | ✅ | ✅ | ❌ | ❌ | ⚠️ |
| **Legal hold** | ✅ Built-in | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| **Retention policies + alerts** | ✅ 90/60/30-day + escalation | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| **Document preview (PDF/Office)** | ✅ pdf.js + LibreOffice | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **OCR for scanned documents** | ✅ Tesseract + OCRmyPDF | ⚠️ Add-on | ✅ | ✅ | ✅ Best-in-class | ✅ | ⚠️ |
| **AI-powered summaries & metadata** | ✅ spaCy + LLM | ⚠️ Copilot (paid) | ⚠️ Add-on | ❌ | ⚠️ ML classifier | ❌ | ❌ |
| **Electronic signatures** | ✅ Click-to-sign audit | ❌ DocuSign needed | ✅ | ✅ | ❌ | ❌ | ❌ |
| **Public share links** | ✅ Expiry + password + revoke | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ |
| **Bulk import with CSV** | ✅ CLI + browser validator | ⚠️ Migration tool | ✅ Consulting | ✅ Consulting | ✅ | ⚠️ | ⚠️ |
| **RBAC with dept scoping** | ✅ 4 roles + dept admin | ✅ Complex | ✅ | ✅ | ❌ | ⚠️ | ✅ |
| **Accessibility (WCAG 2.2 AA)** | ✅ Day-one priority | ⚠️ Partial | ⚠️ | ⚠️ | ❌ | ❌ | ⚠️ |
| **Internationalisation** | ✅ 23 languages | ✅ | ✅ | ✅ | ⚠️ Community | ⚠️ | ✅ |
| **Deployment complexity** | `docker compose up` | High (Azure AD, infra) | Medium | Medium | Low | Low | High |
| **Licence** | Open source | Per-user subscription | Per-user subscription | Per-user subscription | Open source | Open source | LGPL / Paid |
| **Target market** | SMB / mid-market | Enterprise | Mid-market / enterprise | Mid-market | Home / small office | Small office | Enterprise |

---

## Why Eòlas exists

Most document management systems fall into one of two camps:

**Enterprise behemoths** (SharePoint, Alfresco, Nuxeo) that require dedicated IT teams, complex infrastructure, and six-figure licencing. They do everything — but at a cost that excludes small and medium businesses.

**Lightweight scanners** (Paperless-ngx, Teedy) that excel at digitising paper but stop short of governance. No workflows, no retention, no legal hold, no compliance reporting. Great for a home office; inadequate for a business with regulatory obligations.

**Eòlas occupies the gap between these two worlds.** It delivers governance-grade document management — the kind of workflow, retention, and compliance tooling that regulated SMBs actually need — in an open-source, self-hosted package that deploys with a single `docker compose up` command.

---

## Where Eòlas leads

### 1. Governance without the enterprise price tag

Eòlas ships with per-department configurable workflows (linear or parallel), conditional step routing via JSON Logic, mandatory escalation contacts with configurable deadlines, legal hold that blocks deletion and archiving, and retention policies with approaching-review notifications at 90, 60, and 30 days.

This is the M-Files / DocuWare feature set — delivered as open source. No per-user licence fees. No annual renewal negotiations. No vendor lock-in.

**Who this matters to:** Any SMB that handles contracts, policies, HR documentation, financial records, or regulatory filings and needs an audit trail that satisfies ISO 9001, GDPR Article 30, or sector-specific compliance requirements.

### 2. AI assistance out of the box

Every document uploaded to Eòlas is automatically processed by the AI sidecar:

- **Summaries** generated via configurable LLM (Ollama for privacy-first on-prem, or Anthropic/OpenAI for quality)
- **Keyword extraction** via spaCy NER
- **Structured metadata** extraction (monetary amounts, dates, parties, jurisdictions, document numbers)
- **Taxonomy classification** against the organisation's own term hierarchy
- **OCR** for scanned documents via Tesseract + OCRmyPDF with 10 language packs

Competitors charge extra for AI features (M-Files Aino, Microsoft Copilot) or don't offer them at all. Eòlas includes them in the base deployment.

### 3. Accessibility as a first principle

Eòlas was designed for WCAG 2.2 AA compliance from the first line of code. Every interactive element has keyboard navigation, focus indicators, and ARIA attributes. Screen reader users (JAWS, NVDA, VoiceOver) can navigate the full workflow: upload a document, submit it for review, approve it, sign it.

This isn't a checkbox exercise — it's a procurement gate. Public-sector organisations in the EU, UK, Canada, and Australia are required to use accessible software. Most DMS vendors treat accessibility as a retrofit; Eòlas treats it as architecture.

### 4. 23 languages, zero configuration

The UI is fully translated into 23 languages spanning Latin, Cyrillic, Greek, Arabic, CJK, Nordic, Baltic, and Finnic scripts. Language switching is instant — users pick from a dropdown and the entire interface updates without a page reload.

Languages: English, German, French, Spanish, Italian, Portuguese, Dutch, Polish, Danish, Norwegian Bokmål, Icelandic, Swedish, Greek, Turkish, Ukrainian, Latvian, Lithuanian, Estonian, Finnish, Arabic, Simplified Chinese, Japanese, Korean.

**Who this matters to:** Any organisation operating across borders. An EU company with offices in Berlin, Paris, and Warsaw can give each team the UI in their native language against a single shared document repository.

### 5. Deploy in minutes, not months

The entire Eòlas stack — application, database, search, storage, messaging, authentication, email, AI processing — starts with one command:

```bash
docker compose up -d
```

No Azure Active Directory. No Oracle database. No proprietary message queue. Every dependency is open-source and containerised. The production deployment profile adds resource limits, TLS termination guidance, and a backup/restore runbook.

Compare this to SharePoint (requires Microsoft 365 tenancy, Azure AD, and typically a consulting engagement) or Alfresco (requires JBoss/Tomcat tuning, Solr configuration, and LDAP integration before the first document is uploaded).

---

## Detailed comparisons

### vs SharePoint / Microsoft 365

SharePoint is the default choice for organisations already invested in Microsoft 365. Its strength is integration with the Office ecosystem — co-authoring, Teams, Outlook.

**Choose Eòlas when:**
- You want to own your data on-premises or in your own cloud tenancy
- You don't want per-user Microsoft 365 licensing costs
- You need configurable approval workflows without learning Power Automate
- You need retention policies with proactive notifications (SharePoint's retention is label-based and passive)
- Your team is not already embedded in the Microsoft ecosystem
- You serve EU public-sector clients who require open-source or vendor-neutral solutions

**Choose SharePoint when:**
- Your organisation already has Microsoft 365 E3/E5 licences
- Co-authoring in Word/Excel is a daily workflow
- You need deep Teams/Outlook integration
- You have an IT department comfortable with Azure AD administration

### vs M-Files

M-Files is the closest commercial competitor to Eòlas in the governance space. It offers metadata-driven DMS with strong compliance features, intelligent classification, and workflow automation.

**Choose Eòlas when:**
- You want zero licensing cost (M-Files charges per named user, typically €40–80/user/month)
- You prefer to self-host rather than depend on M-Files Cloud
- You want to inspect, modify, and extend the source code
- You need a system that deploys in hours, not weeks
- Your AI requirements are served by the included sidecar (M-Files' AI features are add-on priced)

**Choose M-Files when:**
- You need enterprise-grade support contracts with SLAs
- You require integration with SAP, Salesforce, or other enterprise platforms
- You need M-Files' unique "metadata-only" architecture (views instead of folders)
- Your organisation has 500+ users and an established procurement process for commercial software

### vs Paperless-ngx

Paperless-ngx is the most popular open-source DMS for individuals and small offices. It excels at scanning, OCR, and tagging paper documents.

**Choose Eòlas when:**
- You need approval workflows (Paperless has none)
- You need retention policies and compliance reporting (Paperless has none)
- You need role-based access control beyond "can this user see this document"
- You need legal hold (Paperless has no concept of it)
- You need electronic signatures on documents
- You need to share documents externally via secure links
- You have more than one department and need per-department governance

**Choose Paperless-ngx when:**
- Your primary use case is digitising physical paper (scan → OCR → tag → search)
- You have a single user or a small household
- You don't need approval workflows or retention compliance
- You want the simplest possible setup for personal document archiving

### vs DocuWare

DocuWare is a mid-market commercial DMS with strong workflow automation and electronic signature integration.

**Choose Eòlas when:**
- You want open-source with zero licence fees
- You want to self-host with full control over your data
- You prefer a modern tech stack (Kotlin/Spring Boot, SvelteKit) to DocuWare's legacy architecture
- You want AI-powered summaries and metadata extraction included, not as a paid add-on
- You need 23-language support without purchasing a language pack

**Choose DocuWare when:**
- You need native DocuSign/Adobe Sign integration (Eòlas has click-to-sign but not commercial provider integration yet)
- You need deep ERP integration (SAP, Oracle)
- You require commercial support with guaranteed response times
- You need advanced electronic forms with conditional logic (Eòlas has workflow conditions but not a form builder)

---

## What Eòlas doesn't do (yet)

We believe in honest positioning. These are capabilities that some competitors offer and Eòlas does not — along with our roadmap status:

| Capability | Status | Notes |
|---|---|---|
| Real-time co-authoring | Not planned | Use Office Online or Google Docs for co-authoring; Eòlas manages the lifecycle |
| Native mobile apps | Not started | The web UI is responsive but not a PWA or native app |
| Commercial signature integration (DocuSign, Adobe Sign) | Roadmap | Click-to-sign audit records are shipped; commercial provider integration is a future pass |
| Advanced electronic form builder | Not started | Workflow conditions handle routing logic; a visual form builder is a future consideration |
| Multi-tenancy | Not started | Current architecture is single-tenant; hosting Eòlas-as-a-service for multiple customers requires one deployment per tenant |
| LDAP / Active Directory integration | Designed, not wired | Keycloak supports LDAP federation natively; the connection configuration is a deployment task, not a code task |
| Email template management UI | Roadmap (Pass 7) | Email notifications currently send in English; per-locale template editing is planned |

---

## Technology stack

| Layer | Technology | Why |
|---|---|---|
| Backend | Kotlin + Spring Boot 3.4 | Type-safe, mature ecosystem, excellent JPA and security support |
| Frontend | SvelteKit 5 + Tailwind CSS | Fast, accessible, runes-based reactivity, server-side rendering |
| Database | PostgreSQL 16 | Battle-tested, JSONB for flexible metadata, Flyway for migrations |
| Search | OpenSearch | Full-text search with relevance scoring, compatible with Elasticsearch clients |
| Storage | MinIO (S3-compatible) | Self-hosted object storage, API-compatible with AWS S3 |
| Auth | Keycloak 24 | OpenID Connect, LDAP federation, SSO, MFA — all configurable via admin console |
| Messaging | NATS JetStream | Lightweight pub/sub for AI task queue, event-driven architecture |
| AI | Python sidecar (spaCy, LangChain, Tesseract, OCRmyPDF) | Pluggable LLM provider, on-prem inference with Ollama or cloud with Anthropic/OpenAI |
| Email | SMTP via configurable gateway (12 provider presets) | Works with any provider; Mailpit for development |
| Preview | LibreOffice headless (optional sidecar) | Converts Office documents to PDF for in-browser rendering via pdf.js |
| Orchestration | Docker Compose / Kubernetes-ready | Single-command deployment for dev; production profile with resource limits and TLS guidance |
| i18n | Paraglide.js | Compile-time message extraction, tree-shaking per language, 23 languages |
| Observability | Micrometer + Prometheus + Grafana | Custom health indicators for every dependency, pre-built dashboard JSON |

---

## Deployment options

### Development (local)
```bash
git clone https://github.com/your-org/eolas.git
cd eolas
docker compose up -d
# Backend: http://localhost:8081
# Frontend: http://localhost:5173
# Keycloak: http://localhost:8180
# Mailpit: http://localhost:8025
```

### Production
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

Includes: resource limits, no exposed internal ports, restart policies, mandatory crypto environment variables, TLS termination via reverse proxy (nginx/Traefik/Caddy recipes provided).

### Kubernetes
Helm chart is on the roadmap. The Docker Compose services map 1:1 to Kubernetes Deployments + Services.

---

## Pricing

**Eòlas is open source. There is no per-user fee, no annual licence, no feature gating.**

The total cost of running Eòlas is the infrastructure it runs on:

| Deployment | Estimated monthly cost |
|---|---|
| DigitalOcean Droplet (4 vCPU, 8GB RAM) | ~$48/month |
| AWS EC2 t3.large + RDS + S3 | ~$120/month |
| On-premises (existing hardware) | $0 additional |
| Hetzner Cloud CX31 | ~€15/month |

Compare this to:
- **M-Files**: €40–80/user/month (20 users = €800–1,600/month)
- **DocuWare**: €30–60/user/month (20 users = €600–1,200/month)
- **SharePoint Online**: included in M365 E3 at €36/user/month, but requires the full M365 stack

For a 20-person SMB, Eòlas saves **€7,000–19,000 per year** in software licensing alone.

---

## Getting started

1. **Try it locally** — `docker compose up -d` and open `http://localhost:5173`
2. **Import your documents** — use the bulk import CLI with a CSV manifest
3. **Configure your departments** — set up teams, workflows, and retention policies
4. **Invite your users** — provision via the admin UI or bulk import CSV
5. **Go live** — switch to the production profile and point your domain at the deployment

Documentation: `docs/` directory in the repository  
Monitoring setup: `docs/monitoring-setup.md`  
Backup/restore: `docs/backup-restore.md`  
Environment reference: `docs/environment-reference.md`

---

*Eòlas (əʊləs) — Scottish Gaelic for "knowledge." Built for organisations that take their documents seriously.*
