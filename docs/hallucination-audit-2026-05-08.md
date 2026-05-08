# Hallucination & Sanity-Check Audit — Eòlas KMS / Kosha

**Date:** 8 May 2026
**Scope:** Full repo at `E:\EolasKMS` — Kotlin services (`kosha-*`), Python sidecars (`kosha-ai-sidecar`, `kosha-preview-sidecar`), SvelteKit frontend (`kosha-web`), Docker Compose / Gradle / SQL migration glue.
**Method:** Two-pass review.

  1. **Smell test** — four parallel agents scanned for hallucination signals (broken imports, invented APIs, wiring drift, dead scaffolding).
  2. **Verification** — each finding re-checked against actual file contents, library source (OpenSearch RHLC 2.18.0, nats-py 2.x, langchain-community 0.3, pydantic 2), Spring Boot 3.4.4 binding rules, and reproduction tests where applicable.

**Note on confidence:** Spring Boot 3.4.4 confirms relaxed environment binding behaviour. Library APIs verified by direct source inspection (GitHub) or by installing the library and introspecting (`pip install` + `dir()` + reproduction).

---

## Summary table

| ID | Finding (one-liner) | Initial verdict | Verified verdict |
|---|---|---|---|
| A | Env var mismatch between `application.yml` placeholders and compose | Definite-broken | **Refuted** (smell remains) |
| B | OpenSearch HTTPS default vs HTTP server | Definite-broken | **Refuted** for compose; smell for fresh-local |
| C | `CreateIndexRequest.mapping(Map)` doesn't exist | Definite-broken | **Refuted** — overload is real in RHLC 2.18.0 |
| D | `ChatOpenAI` import path wrong + missing dep | Definite-broken | **Refuted** — `langchain_community.chat_models.ChatOpenAI` still exposed (deprecated) |
| E | Pydantic `corrected_at: datetime = datetime.now()` mutable default | Definite-broken | **Confirmed by reproduction** |
| F | README advertises `kosha-web` service that compose doesn't start | Definite-broken | **Confirmed** |
| G | No CI workflows in `.github/` | Definite-broken | **Confirmed** |
| H | Empty-body authorization checks in `kosha-identity` | Definite-broken | **Confirmed** |
| I | `nats-py` push-sub `.messages` async iterator doesn't exist | Probably-broken | **Refuted** — `Subscription.messages` is a real attribute |
| J | `formsnap`, `sveltekit-superforms`, `@tanstack/svelte-table` unused; schemas unwired | Probably-broken | **Confirmed** (0 imports of all four) |
| K | `WorkflowEditor.svelte` has hardcoded English, no paraglide | Probably-broken | **Confirmed** (no `m.` calls; ≥14 hardcoded strings) |
| L | `auth.ts` reads `oidcUser.profile.roles` — fragile Keycloak claim path | Probably-broken | **Confirmed as conditional** — depends on Keycloak client mapper config |
| M | `KeycloakAdminClient` wired to master-realm credentials | Probably-broken | **Confirmed** |
| N | `infra/grafana/`, `infra/prometheus/` unreferenced | Probably-broken | **Confirmed** |
| O | `kosha-ai` lacks `depends_on: kosha-api` | Probably-broken | **Confirmed** |
| P | `OwnershipNotificationListener` retention emails send empty `departmentName` | Probably-broken | **Confirmed** |
| Q | `handle_feedback` is a stub that ACKs the message | Suspicious | **Confirmed** |
| R | `corrections/` and `embeddings/` packages are empty | Suspicious | **Confirmed** |
| S | `os.unlink(tmp_path) if "tmp_path" in dir() else None` — wrong check | Suspicious | **Confirmed** |
| T | `LIMIT 1` in JPQL | Suspicious | **Confirmed** (works on Hibernate 6 + Postgres but non-standard) |
| U | `opensearch-java:2.18.0` declared but never imported | Suspicious | **Confirmed** |
| V | V010 retroactively adds `created_at`/`updated_at` columns | Suspicious | **Confirmed** (cosmetic) |
| W | `@types/dompurify` in `dependencies` not `devDependencies` | Suspicious | **Confirmed** |
| X | `kosha-retention` ↔ `kosha-notification` Spring-event coupling | Suspicious | **Confirmed** (not strictly a cycle) |
| Y | `import-docs` route is unreachable dead content | Suspicious | **Refuted** — linked from `/admin/import` page |

**Net:** 19 confirmed, 6 refuted. Roughly a quarter of the smell-test findings did not survive verification — important context when reading any AI-assisted audit, this one included.

---

## Confirmed findings (action required)

### Severity: high

**E. Pydantic mutable default — `models/messages.py:51`**
`corrected_at: datetime = datetime.now()` is evaluated once at class-definition time. Reproduction: two `FeedbackCorrection()` instances created 0.5s apart had identical timestamps. Every record gets the same frozen value.
**Fix:** `corrected_at: datetime = Field(default_factory=datetime.now)`.

**H. Authorization stubs that silently pass — `kosha-identity`**
- `service/DepartmentService.kt:103-106` — `checkGlobalAdminAuthority()` is empty body with TODO.
- `service/UserProfileService.kt:112-118` — `checkUpdateAuthority()` is empty body with TODO.
Both are called from real service paths (department creation/update, user-profile update). Documented behaviour says they enforce role; actual behaviour is no-op. This is a security finding. Either gate the endpoints behind Spring Security (`@PreAuthorize("hasRole('GLOBAL_ADMIN')")`) or implement the SecurityContextHolder check.

**M. Master-realm Keycloak credentials in production code path — `keycloak/KeycloakAdminClient.kt:23-29`**
`@Qualifier("keycloakMasterClient")` is wired into the production admin client because the service-account flow "hits 403 on admin API calls despite having realm-admin assigned" (per the inline TODO). All user provisioning runs as master admin. This is a known operational issue, but worth re-prioritising — either fix the service-account permissions properly or restrict the master client to bootstrap-only.

**F. `kosha-web` missing from `docker-compose.yml`**
README's service table advertises "Eòlas Web (dev) — http://localhost:5175" but the frontend has no service in `docker-compose.yml`, `docker-compose.override.yml`, or `docker-compose.prod.yml`. Following the README's "docker compose up" instruction does not start a frontend.
**Fix:** Add a `kosha-web` service to compose (or update the README to say "run `npm run dev` separately in `kosha-web/`").

**G. No CI**
`.github/` contains only `dependabot.yml`. No workflows. README implies a working build pipeline.
**Fix:** Add at minimum a build/test workflow that runs `./gradlew check`, `npm run check && npm run lint` in `kosha-web`, and `ruff check . && pytest` in each Python sidecar.

### Severity: medium

**J. Frontend dead scaffolding — `kosha-web`**
- `formsnap`, `sveltekit-superforms`, `@tanstack/svelte-table`, `@types/dompurify` — declared in `package.json` `dependencies`. All four have **0 imports** anywhere in `src/`.
- `src/lib/schemas/department.ts` and `src/lib/schemas/document.ts` define Zod schemas for forms. **0 imports** anywhere. Forms re-implement validation inline.
**Fix:** Either remove the deps and the schema files, or actually wire them up. Pick one.

**K. `WorkflowEditor.svelte` skipped i18n**
No `import * as m from '$paraglide/messages'`. ~14 hardcoded English strings (`"Review"`, `"Approve"`, `"Sign-off"`, `"Action"`, `"Time limit (days)"`, `"Failed to save workflow"`, etc.). The catalog already has `btn_save_workflow`, `btn_add_step`, `workflow_*` keys waiting.
**Fix:** Wire the same `m.*` pattern used in the rest of the app.

**L. Keycloak roles claim path — `auth.ts:107`**
`oidcUser.profile.roles ?? []`. Keycloak's default schema puts realm roles under `realm_access.roles`, not at the top-level `roles` claim. Will silently return `[]` on a vanilla Keycloak client unless someone adds a "User Realm Role" mapper that flattens roles into a top-level `roles` claim.
**Fix:** Either document the required mapper in the Keycloak setup runbook, or change the client to read `oidcUser.profile.realm_access?.roles ?? []`.

**N. Orphaned monitoring config**
`infra/grafana/eolas-dashboard.json` and `infra/prometheus/prometheus.yml` exist and reference `kosha-api:8080` correctly, but no `grafana` or `prometheus` service is in any compose file.
**Fix:** Either add the services or delete the configs.

**O. `kosha-ai` race against `kosha-api`**
`docker-compose.yml:55-57` — `kosha-ai` `depends_on: [nats, nats-init]` but not `kosha-api`. The sidecar calls `http://kosha-api:8080`. With the API in its 30s `start_period`, first calls fail unless the Python side has retry/backoff (worth verifying separately).
**Fix:** Add `kosha-api: { condition: service_healthy }` to `kosha-ai.depends_on`.

**P. Empty `departmentName` in retention emails — `OwnershipNotificationListener.kt`**
- Line ~65 (`onRetentionReviewCritical`): `"departmentName" to ""`
- Line ~92 (`onRetentionReviewApproaching`): `"departmentName" to ""`
- Line ~35 (`onLegalHoldApplied`): correctly uses `lookupDepartmentName(event.departmentId)`.
The retention handlers were copied from the legal-hold handler and never finished. Email template's `{{departmentName}}` placeholder will always render blank for retention notifications.

**Q. `handle_feedback` ACKs but is a stub — `kosha-ai-sidecar/src/handlers/feedback_handler.py`**
The function logs and returns. The NATS subscription in `main.py` then `await msg.ack()`s on success. Any feedback corrections sent to `ai.feedback.correction` are silently dropped. If the feedback feature isn't ready, either disable the subscription or `nak` until storage exists.

### Severity: low (cleanup)

**R. Empty `corrections/` and `embeddings/` packages** — `kosha-ai-sidecar/src/`. Both `__init__.py` are empty and unimported. Delete.

**S. `os.unlink(tmp_path) if "tmp_path" in dir() else None`** — `kosha-ai-sidecar/src/pipelines/ocr.py:92`. Use `if 'tmp_path' in locals()`. Only matters on the exception path.

**T. `LIMIT 1` in JPQL** — `kosha-document/.../DocumentRepositories.kt:37`. Non-standard JPQL. Works on Hibernate 6 + Postgres. Replace with `Pageable.ofSize(1)` or `@Query(... ) ; setMaxResults(1)` for portability.

**U. `opensearch-java:2.18.0` declared but unused** — `kosha-search/build.gradle.kts:5`. All imports use the RHLC. Remove the declaration to avoid pulling a conflicting transport into the runtime classpath.

**V. V010 retroactively adds `created_at`/`updated_at`** — `kosha-*/src/main/resources/db/migration/V010__fix_entity_schema_mismatches.sql`. Backfills columns omitted from V001-V009. Cosmetic — schema-first AI generation tell. Not worth touching now; just note when reading the migration history.

**W. `@types/dompurify` should be in `devDependencies`** — `kosha-web/package.json`. Type-only package, no runtime code.

**X. `kosha-retention` → `kosha-notification` plus events back the other way** — `kosha-retention/.../service/RetentionReviewScanner.kt:6`. Not a strict cycle (events are async via Spring's `ApplicationEventPublisher`) but a fragile coupling. Worth knowing about before refactoring either module.

---

## Refuted findings (kept here for the audit trail)

These were flagged by the smell-test agents but did not survive verification. They're listed so you have a record of the false positives, and so the same trap doesn't get re-set later.

**A. Env var mismatch between `application.yml` and `docker-compose.yml`**
The smell-test agent saw `application.yml` placeholders like `${NATS_URL:default}` and compose injecting `KOSHA_NATS_URL`, concluded none of them bind. **Wrong.** Spring Boot 3.4.4's relaxed environment binding maps `KOSHA_NATS_URL` to property path `kosha.nats.url` — and the YAML uses exactly that path. The placeholder `${NATS_URL:...}` is just a fallback name; the actual binding goes via the dotted property. `@Value("\${kosha.nats.url}")` annotations confirm this.
**Smell:** the placeholder names are misleadingly different from the env vars compose injects. Worth renaming for clarity but not a bug.

**B. OpenSearch HTTPS default vs HTTP server**
Same root cause as A. Compose sets `KOSHA_OPENSEARCH_URL=http://opensearch:9200` which wins via relaxed binding. The HTTPS default in YAML only applies when neither env var is set — i.e., a dev running locally without env vars. Smell, not a compose-stack bug.

**C. `CreateIndexRequest.mapping(Map<String, Object>)` is hallucinated**
**Reverse-hallucinated by the audit agent.** Direct inspection of OpenSearch core 2.18.0 source confirms four overloads:
```
public CreateIndexRequest mapping(BytesReference source, MediaType mediaType)
public CreateIndexRequest mapping(Map<String, ?> source)
public CreateIndexRequest mapping(String source, MediaType mediaType)
public CreateIndexRequest mapping(XContentBuilder source)
```
The Kotlin call `req.mapping(mapOf(...))` is valid. Code is fine.

**D. `ChatOpenAI` import path wrong + missing `langchain-openai` dep**
`from langchain_community.chat_models import ChatOpenAI` actually works in `langchain-community` 0.3 (verified by `pip install` + import). The class is deprecated (LangChain has been pushing partner packages) but functional. `langchain-openai` would be the recommended modern path but is not strictly required for current behaviour.
**Smell:** when LangChain finally removes the legacy re-export, this will break. Worth adding `langchain-openai>=0.3` and switching the import as a maintenance hygiene step.

**I. `nats-py` `.messages` doesn't exist**
**Reverse-hallucinated.** `nats.aio.subscription.Subscription` exposes `.messages` as a real attribute. `dir(Subscription)` shows: `delivered, drain, messages, next_msg, pending_bytes, pending_msgs, queue, subject, unsubscribe`. The `async for msg in task_sub.messages` pattern in `main.py:158, 172` is the documented JetStream push-subscribe iteration pattern. Code is fine.

**Y. `import-docs` route is unreachable dead content**
The `/admin/import` page links to `/admin/import-docs` (line 185). Reachable via the import wizard, just not from the sidebar. Not dead.

---

## Cross-cutting patterns

Three recurring fingerprints worth keeping in mind as you maintain this codebase.

### 1. Scaffolded-and-abandoned

Empty packages, declared deps that nothing imports, schema files nothing wires up, TODO-stub functions that ACK their input. Findings J, K, Q, R, U, and the empty subdirs share this shape. The pattern is: directory tree generated optimistically, content never finished. Cleaning these up is mostly mechanical and reduces a lot of "wait, what does this do?" friction for a future maintainer. A sweep with `npm prune`, `ruff` for unused imports, and a quick check for empty `__init__.py` files would catch most.

### 2. Wiring drift between code and infra

Findings F, N, O are all "code expects one thing, infra provides another." None of these would be caught by a compile or unit test; they only manifest at `docker compose up`. A smoke-test workflow that boots the stack and hits `/health` on each service would catch all of them — and would also catch finding A's relaxed-binding situation if it ever drifted.

### 3. Authorization-by-omission

Finding H (empty `checkGlobalAdminAuthority` / `checkUpdateAuthority` bodies) and finding M (master-realm credentials wired in until "service-account permissions are fully working") share an ominous pattern: the security model is documented in code comments, and the real enforcement is a TODO. This is the highest-priority cluster from a non-AI-maintainability standpoint — humans tend to read the comments and assume the code does what they say, especially when the comments are detailed and the surrounding code looks polished.

---

## Meta-finding: AI hallucinated about AI hallucinations

Six of the 25 smell-test findings (A, B, C, D, I, Y) did not survive verification — roughly 24%. Two of them (C and I) were the audit agent confidently asserting a real library API was a hallucination, when the API actually exists. Two more (A and B) misread Spring Boot's relaxed-binding rules. One (D) misread LangChain's deprecation/re-export status. One (Y) just missed an internal link.

This matters for your "human maintainability" concern: if you keep using AI-assisted review against an AI-assisted codebase, **the review needs the same verification step as the code itself**. An unverified AI audit is itself a source of bad-information risk — possibly a worse one than the original code, because reviewers tend to carry an air of authority that the code doesn't.

The pragmatic version for the future: smell-test agents are useful for spotting candidates, but every "definite-broken" claim should be confirmed by reproduction (reading the actual library source, running the import, building the offending file) before anyone acts on it. That's exactly the loop we just ran here.

---

## What this means for human maintainability — the headline

Setting aside the false positives, the verified findings cluster into three short stories.

**The wiring is mostly correct, but reads as if it isn't.** The relaxed-binding placeholder situation in `application.yml` is the clearest example: a maintainer reading the YAML without knowing Spring's binding rules in depth would conclude the env vars don't match. They do, but only by accident of property-path naming. A small renaming pass (make placeholders match the env vars compose injects) would let the file be read at face value.

**The skeleton is honest but the skin is incomplete.** The Kotlin domain model, the SQL migrations, the inter-module event flow, the API surface all spot-check cleanly. The unfinished bits are at the edges — empty Python subdirs, unwired Zod schemas, hardcoded English in one component, retention emails missing a department name. These read like the second-pass polish that didn't happen.

**Two real holes need closing before this goes to anyone outside your machine.** The empty `checkGlobalAdminAuthority`/`checkUpdateAuthority` stubs in `kosha-identity`, and the missing CI. Everything else is a maintainability tax — those two are correctness/safety risks.

---

*Report ends here. Mitigation plan to follow in a separate document.*
