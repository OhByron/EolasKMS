# Mitigation Plan — Eòlas KMS / Kosha

**Companion to:** `hallucination-audit-2026-05-08.md`
**Date:** 8 May 2026
**Author tone:** terse and surgical. Read this top-to-bottom; each item has Why / What / Diff (or commands) / Verify.

---

## How to use this doc

Three tracks, sequenced by priority. Track 1 should land before this codebase is shown to anyone outside your machine. Track 2 is the maintainability tax — work through it in batches at your own pace. Track 3 is a small ongoing-quality investment that pays for itself the next time anything in compose drifts.

Each item lists:

- **Why** — one-line problem statement, mapped to the audit finding ID.
- **What** — the change in plain language.
- **Diff** — the smallest patch that does the job, or the exact commands to run.
- **Verify** — how you confirm the change worked.

You can act on items in any order within a track. Tracks 1 and 2 are independent; Track 3 is a one-shot setup.

---

## Important context discovered during planning

While drafting this plan I cross-checked the controllers and Spring Security config. **`@PreAuthorize` is already wired at the controller layer** for the endpoints that call the empty service stubs:

- `DepartmentController.update` is gated by `@authorityService.canEditDepartment(authentication, #id)` (`DepartmentController.kt:65`).
- `UserController.update` is gated by `@authorityService.canManageUser(authentication, #id)` (`UserController.kt:87`).

So the service-layer stubs (`checkGlobalAdminAuthority`, `checkUpdateAuthority`) are **defense-in-depth secondary checks**, not the only line of defence. They specifically guard against:

1. A DEPT_ADMIN with edit rights on a department flipping `handlesLegalReview=true` to elevate their own department's privilege. The controller-level check passes (they can edit the dept), but the field is global-admin-only — and `@PreAuthorize` can't inspect request body fields, so this has to live in the service.
2. A DEPT_ADMIN with edit rights on a user escalating that user's role to GLOBAL_ADMIN or transferring them to a department they don't administer.

The audit's "high severity" framing of finding H still stands (these are real privilege-escalation paths), but the fix is smaller and more targeted than implementing JWT inspection from scratch — both stubs can read `SecurityContextHolder.getContext().authentication.authorities` directly, since `KeycloakJwtConverter` already maps roles into Spring authorities.

---

## Track 1 — Correctness holes (urgent)

### 1.1 Lock down the `handlesLegalReview` field

**Why** Finding H, part 1. `DepartmentService.checkGlobalAdminAuthority` is a no-op. A DEPT_ADMIN can flip `handlesLegalReview=true` on their own department.

**What** Replace the empty body with a SecurityContextHolder check that throws `AccessDeniedException` for non-GLOBAL_ADMINs.

**Diff** — `kosha-identity/src/main/kotlin/dev/kosha/identity/service/DepartmentService.kt`

```kotlin
// Add to imports
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder

// Replace the empty stub with:
private fun checkGlobalAdminAuthority() {
    val auth = SecurityContextHolder.getContext().authentication
        ?: throw AccessDeniedException("No authentication context")
    val isGlobalAdmin = auth.authorities.any { it.authority == "ROLE_GLOBAL_ADMIN" }
    if (!isGlobalAdmin) {
        throw AccessDeniedException("Only GLOBAL_ADMIN can change handlesLegalReview")
    }
}
```

**Verify** Add an integration test (or hit it manually with a DEPT_ADMIN bearer token) that PATCHes `/api/v1/departments/{id}` with `{"handlesLegalReview": true}` and confirm a 403 response. A GLOBAL_ADMIN's request should still succeed.

---

### 1.2 Lock down user role escalation and cross-department transfers

**Why** Finding H, part 2. `UserProfileService.checkUpdateAuthority` is a no-op. A DEPT_ADMIN with edit rights on a user can escalate that user to GLOBAL_ADMIN or transfer them to a department they don't administer.

**What** Implement the rules described in the existing comment: GLOBAL_ADMIN can do anything, DEPT_ADMIN can edit role/status/dept-transfer only within their own scope, no self-edit through this endpoint.

**Diff** — `kosha-identity/src/main/kotlin/dev/kosha/identity/service/UserProfileService.kt`

```kotlin
// Add to imports
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder

// Replace the empty stub with:
@Suppress("UNUSED_PARAMETER")
private fun checkUpdateAuthority(
    targetUser: UserProfile,
    request: UpdateUserProfileRequest,
) {
    val auth = SecurityContextHolder.getContext().authentication
        ?: throw AccessDeniedException("No authentication context")
    val isGlobalAdmin = auth.authorities.any { it.authority == "ROLE_GLOBAL_ADMIN" }
    val isDeptAdmin = auth.authorities.any { it.authority == "ROLE_DEPT_ADMIN" }

    if (isGlobalAdmin) return  // global admin can do anything

    if (!isDeptAdmin) {
        throw AccessDeniedException("Editing user profiles requires DEPT_ADMIN or higher")
    }

    // Role escalation to GLOBAL_ADMIN or DEPT_ADMIN is global-admin-only.
    request.role?.let { newRole ->
        if (newRole in setOf("GLOBAL_ADMIN", "DEPT_ADMIN")) {
            throw AccessDeniedException("Promoting users to $newRole is GLOBAL_ADMIN-only")
        }
    }

    // Block self-edit through this endpoint.
    val callerKeycloakId = auth.name?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: (auth.principal as? org.springframework.security.oauth2.jwt.Jwt)?.subject
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (callerKeycloakId != null && callerKeycloakId == targetUser.keycloakId) {
        throw AccessDeniedException("Self-edit goes through /api/v1/me, not this endpoint")
    }
}
```

Note: the `auth.name` extraction is a little defensive because `JwtAuthenticationToken` can resolve `name` from either `preferred_username` or the subject claim. Confirm against your Keycloak user store which one carries the UUID.

**Verify** Three test cases: DEPT_ADMIN promoting a user to GLOBAL_ADMIN → 403. DEPT_ADMIN editing themselves → 403. DEPT_ADMIN editing another user's display name within scope → 200.

---

### 1.3 Pydantic mutable default

**Why** Finding E. Every `FeedbackCorrection` shares the same frozen timestamp.

**What** Use `default_factory`.

**Diff** — `kosha-ai-sidecar/src/models/messages.py`

```python
# Add to imports
from pydantic import BaseModel, ConfigDict, Field

# Replace the field:
class FeedbackCorrection(BaseModel):
    correction_type: str
    document_id: UUID
    version_id: UUID
    original: dict
    corrected: dict
    corrected_by: UUID
    corrected_at: datetime = Field(default_factory=datetime.now)
```

**Verify** The reproduction test from the audit will now show two distinct timestamps for two instances created milliseconds apart.

---

### 1.4 Minimum CI workflow

**Why** Finding G. No automated build gate. With the relaxed-binding subtleties already discovered, you want at least one signal that confirms each component compiles.

**What** A single workflow that runs the three component checks. Don't try to be clever — just call the existing tools.

**File to create** — `.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

jobs:
  jvm:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew check --no-daemon

  web:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: kosha-web } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: npm, cache-dependency-path: kosha-web/package-lock.json }
      - run: npm ci
      - run: npx svelte-check --threshold error

  python:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        sidecar: [kosha-ai-sidecar, kosha-preview-sidecar]
    defaults: { run: { working-directory: ${{ matrix.sidecar }} } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.12' }
      - run: pip install -e '.[dev]' || pip install -r requirements.txt
      - run: ruff check .
      - run: pytest -q || true   # tests may not exist yet
```

**Verify** Push the file. The workflow runs on the next push to main or PR.

**Note** Confirm `kosha-preview-sidecar` actually has a `pyproject.toml` with a `[dev]` extra; if not, simplify that job to just install runtime deps.

---

### 1.5 Resolve the `kosha-web` README contradiction

**Why** Finding F. README claims a frontend service that compose doesn't start. New contributors will follow the README, run `docker compose up`, and have no frontend.

**What** Pick one of two paths.

**Path A — quick & honest (recommended).** Update README to clarify how the frontend is started.

```diff
- | Eòlas Web (dev)         | http://localhost:5175           |
+ | Eòlas Web (dev)         | http://localhost:5173 — run `cd kosha-web && npm run dev` separately |
```

Plus a one-line note in the "Quick start" section saying compose runs the backend; the frontend runs from `kosha-web/`.

**Path B — wire the frontend into compose.** Add a service. Worth doing only if you want a single command to spin up the entire system.

```yaml
# Append to docker-compose.yml services:
  kosha-web:
    build:
      context: ./kosha-web
    depends_on:
      kosha-api:
        condition: service_healthy
    environment:
      VITE_API_BASE_URL: http://kosha-api:8080
    ports:
      - "5173:5173"
```

This requires a `Dockerfile` in `kosha-web/` that runs `npm install && npm run dev -- --host 0.0.0.0` (or a multi-stage build for production).

**Verify** Path A: README matches reality. Path B: `docker compose up` boots both backend and frontend, and the frontend reaches the API.

---

### 1.6 Plan to retire the master Keycloak client

**Why** Finding M. `KeycloakAdminClient` uses `@Qualifier("keycloakMasterClient")` because the service-account flow returned 403. This is operationally bad in production.

**What** This is investigation, not a code change. Schedule a half-day to:

1. Reproduce the original 403 — note the exact admin API endpoint that failed.
2. In Keycloak admin console: open the `kosha-backend` client → "Service Account Roles" tab → assign `realm-admin` from the `realm-management` client (not the realm-level role that was probably tried before).
3. Restart the backend, repeat the failing operation, confirm 200.
4. Switch the qualifier:

```diff
- @Qualifier("keycloakMasterClient") private val adminClient: Keycloak,
+ @Qualifier("keycloakServiceAccountClient") private val adminClient: Keycloak,
```

5. Once confirmed working, remove the `keycloakMasterClient` bean from the Keycloak config and drop `KEYCLOAK_MASTER_USERNAME`/`KEYCLOAK_MASTER_PASSWORD` from `application.yml` and compose.

**Verify** All admin operations (user provision, password reset, etc.) succeed without master-realm credentials. Master env vars no longer in any config file.

---

## Track 2 — Maintainability cleanup (batched)

These are independent of each other. Pick batches in any order based on what you feel like fixing.

### Batch A — Frontend dead deps and dead schemas (Finding J)

**What** Remove four unused dependencies. Decide whether the unused Zod schemas should be wired up or deleted.

**Commands** — run from `kosha-web/`

```bash
npm uninstall formsnap sveltekit-superforms @tanstack/svelte-table
npm uninstall @types/dompurify && npm install --save-dev @types/dompurify
```

**Decision needed** The schemas in `src/lib/schemas/department.ts` and `src/lib/schemas/document.ts` define real validation rules but no form imports them. Two options:

- **Delete** if the inline form validation in `kosha-web/src/routes/(admin)/admin/departments/.../+page.svelte` and the upload form is doing the same work. Saves dead-code maintenance cost.
- **Wire them up** by having the existing forms `parse()` the schema before submitting. More work but you get type-safe validation in one place.

Pick one. If unsure, delete and revisit if a third form needs the same rules.

**Verify** `npm run check` passes. Open the affected forms in the browser and confirm validation still works.

---

### Batch B — Python sidecar cleanup (Findings Q, R, S)

**What** Three small fixes in `kosha-ai-sidecar`.

**B.1 — Either implement `handle_feedback` or stop ACKing**

If feedback storage isn't ready, either disable the subscription in `main.py` or change the handler to `nak` so messages stay in JetStream until a real handler exists:

```diff
# kosha-ai-sidecar/src/handlers/feedback_handler.py
async def handle_feedback(payload: dict) -> None:
    """Process editor corrections to improve future AI suggestions."""
    correction = FeedbackCorrection(**payload)
-   logger.info(...)
-   # TODO: store correction and update few-shot prompt examples
+   logger.warning(
+       "Feedback handling not implemented; correction for %s not stored",
+       correction.document_id,
+   )
+   raise NotImplementedError("feedback_handler not implemented")
```

Then in `main.py`'s `process_feedback` loop, the existing `except` clause already calls `await msg.nak()` — so the message will redeliver instead of being silently dropped.

**B.2 — Delete empty subdirs**

```bash
cd kosha-ai-sidecar/src
rm -rf corrections/ embeddings/
```

**B.3 — `dir()` → `locals()` in ocr.py:92**

```diff
-    os.unlink(tmp_path) if "tmp_path" in dir() else None
+    if "tmp_path" in locals():
+        os.unlink(tmp_path)
```

**Verify** `ruff check .` passes. The sidecar still starts and processes a sample task end-to-end.

---

### Batch C — Kotlin nits (Findings P, T, U)

**C.1 — Look up department name in retention notification handlers** (Finding P)

In `kosha-notification/src/main/kotlin/dev/kosha/notification/listener/OwnershipNotificationListener.kt`, replace the two `"departmentName" to ""` lines with `lookupDepartmentName(event.departmentId)` — using whatever ID field the retention events carry (check `RetentionReviewCritical` and `RetentionReviewApproaching` event classes for the field name).

If those events don't carry a department ID, that's a bigger fix — the events need to grow the field. Either way, the empty string is wrong.

**C.2 — Replace `LIMIT 1` JPQL with `Pageable`** (Finding T)

`kosha-document/src/main/kotlin/dev/kosha/document/repository/DocumentRepositories.kt:37`

```diff
-    @Query("SELECT v FROM DocumentVersion v WHERE v.document.id = :docId ORDER BY v.createdAt DESC LIMIT 1")
-    fun findLatestVersion(@Param("docId") docId: UUID): DocumentVersion?
+    @Query("SELECT v FROM DocumentVersion v WHERE v.document.id = :docId ORDER BY v.createdAt DESC")
+    fun findLatestVersion(@Param("docId") docId: UUID, pageable: Pageable): List<DocumentVersion>
```

Then call sites adapt to `findLatestVersion(docId, PageRequest.of(0, 1)).firstOrNull()`. Or, cleaner: use Spring Data's "Top1" naming convention:

```kotlin
fun findFirstByDocumentIdOrderByCreatedAtDesc(docId: UUID): DocumentVersion?
```

Spring Data generates the query automatically. No JPQL needed.

**C.3 — Drop `opensearch-java:2.18.0`** (Finding U)

`kosha-search/build.gradle.kts`

```diff
 dependencies {
     implementation(project(":kosha-common"))
     implementation("org.springframework.boot:spring-boot-starter-web")
     implementation("org.opensearch.client:opensearch-rest-high-level-client:2.18.0")
-    implementation("org.opensearch.client:opensearch-java:2.18.0")
 }
```

**Verify** `./gradlew :kosha-search:compileKotlin :kosha-document:compileKotlin :kosha-notification:compileKotlin` succeeds. The retention email rendering shows the department name in a manual/integration test.

---

### Batch D — Frontend i18n & misc (Findings K, L, W)

**D.1 — Wire `WorkflowEditor.svelte` to paraglide** (Finding K)

Replace the 14 hardcoded strings with `m.*` calls. Catalog already has `btn_save_workflow`, `btn_add_step`, `workflow_linear`, `workflow_none`, `workflow_parallel` — add the rest (`workflow_step_review`, `workflow_step_approve`, `workflow_step_signoff`, `workflow_action_label`, `workflow_time_limit_label`, `workflow_assignee_label`, `workflow_escalation_label`, `workflow_select_placeholder`, `workflow_save_failed`) to `messages/en.json` and reference from the component:

```svelte
<script>
  import * as m from '$lib/paraglide/messages.js';
</script>

- <button>Save</button>
+ <button>{m.btn_save_workflow()}</button>
```

**D.2 — Document the Keycloak roles claim mapper** (Finding L)

The `KeycloakJwtConverter` reads `jwt.getClaimAsStringList("roles")` (`SecurityConfig.kt:141`). For this to work, the Keycloak `kosha-backend` client must have a "User Realm Role" mapper that adds a top-level `roles` claim to access tokens. This isn't documented anywhere. Two ways to fix:

- **Option 1 — code-side fallback (more robust):** Update both backend and frontend to also check `realm_access.roles`:
  ```kotlin
  // SecurityConfig.kt:141
  val roles = jwt.getClaimAsStringList("roles")
      ?: jwt.getClaim<Map<String, Any>>("realm_access")?.get("roles") as? List<String>
      ?: emptyList()
  ```
  ```typescript
  // kosha-web/src/lib/auth.ts:107
  const roles = oidcUser.profile.roles ?? oidcUser.profile.realm_access?.roles ?? [];
  ```

- **Option 2 — document the required mapper:** Add a `docs/keycloak-setup.md` describing exactly the mapper config needed: client → Client scopes → `roles` mapper → "Add to access token" enabled, "Token claim name" = `roles`, type = "User Realm Role".

Recommend Option 1 — code is self-documenting and survives Keycloak admins changing config.

**D.3 — Move `@types/dompurify`** — already covered in Batch A above.

**Verify** `npm run check` in `kosha-web/` passes. Keycloak login still produces a `roles` array on the user object (test by logging `oidcUser.profile` in the browser console).

---

### Batch E — Misleading placeholder names in `application.yml`

**Why** The audit's refuted finding A — placeholder names in `application.yml` (`${MINIO_ENDPOINT:...}`, `${NATS_URL:...}`, etc.) don't match what compose injects. They work today only because of Spring's relaxed property-path binding. A future maintainer reading the YAML at face value will conclude the wiring is broken.

**What** Rename the placeholders to match the compose env vars. The relaxed binding still works; the file just becomes self-documenting.

**Diff** — `kosha-app/src/main/resources/application.yml`

```diff
-    host: ${MAIL_HOST:localhost}
-    port: ${MAIL_PORT:1025}
+    host: ${SPRING_MAIL_HOST:localhost}
+    port: ${SPRING_MAIL_PORT:1025}
...
-      endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
-      access-key: ${MINIO_ACCESS_KEY:minioadmin}
-      secret-key: ${MINIO_SECRET_KEY:minioadmin}
-      bucket: ${MINIO_BUCKET:kosha-vault}
+      endpoint: ${KOSHA_STORAGE_MINIO_ENDPOINT:http://localhost:9000}
+      access-key: ${KOSHA_STORAGE_MINIO_ACCESS_KEY:minioadmin}
+      secret-key: ${KOSHA_STORAGE_MINIO_SECRET_KEY:minioadmin}
+      bucket: ${KOSHA_STORAGE_MINIO_BUCKET:kosha-vault}
   nats:
-    url: ${NATS_URL:nats://localhost:4222}
+    url: ${KOSHA_NATS_URL:nats://localhost:4222}
   opensearch:
-    url: ${OPENSEARCH_URL:https://localhost:9200}
+    url: ${KOSHA_OPENSEARCH_URL:http://localhost:9200}
```

Note the second change to OpenSearch: also flip the local default from `https` to `http` so a developer running compose without env vars set doesn't trip over the SSL handshake against an HTTP server (that was the half-real version of finding B).

Also worth checking the same files in `kosha-app/src/main/kotlin/dev/kosha/app/nats/NatsService.kt:27` and `AiResultListener.kt:89`, which have inline placeholders for the NATS URL — same renaming applies.

**Verify** `docker compose up` still works. A manual `./gradlew :kosha-app:bootRun` against an empty environment uses the localhost defaults.

---

### Batch F — Compose hygiene (Findings N, O)

**F.1 — Decide on monitoring** (Finding N)

`infra/grafana/eolas-dashboard.json` and `infra/prometheus/prometheus.yml` exist but no compose file uses them. Two paths:

- **Add the services** if observability is on the roadmap:
  ```yaml
  prometheus:
    image: prom/prometheus:v2.55.0
    volumes: ["./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]
    ports: ["9090:9090"]
  grafana:
    image: grafana/grafana:11.3.0
    depends_on: [prometheus]
    volumes: ["./infra/grafana:/etc/grafana/provisioning/dashboards:ro"]
    ports: ["3000:3000"]
  ```
- **Delete the configs** if monitoring isn't a priority right now — `infra/grafana/`, `infra/prometheus/`. They can be regenerated when needed.

Pick the second if you're on a near-term ship deadline; pick the first if anyone is going to look at metrics this quarter.

**F.2 — Add `kosha-api` to `kosha-ai`'s `depends_on`** (Finding O)

```diff
   kosha-ai:
     build:
       context: ./kosha-ai-sidecar
     depends_on:
       nats:
         condition: service_healthy
       nats-init:
         condition: service_completed_successfully
+      kosha-api:
+        condition: service_healthy
```

**Verify** `docker compose up` from a cold start: kosha-ai logs should show no early HTTP errors against kosha-api.

---

## Track 3 — Smoke-test workflow (the single best ongoing investment)

**Why** Three of the verified findings (F, N, O) are wiring drift between code and infra — exactly the kind of bug a unit test won't catch. A workflow that boots compose and pings each service's `/health` endpoint catches all of them, and will catch the next one too.

**What** Add a `compose-smoke` job to the CI workflow defined in 1.4. It boots the full stack, waits for healthchecks, hits each service's `/health`, then tears down.

**File to extend** — `.github/workflows/ci.yml` (append a new job)

```yaml
  compose-smoke:
    runs-on: ubuntu-latest
    needs: [jvm, python]
    steps:
      - uses: actions/checkout@v4

      - name: Boot stack
        run: docker compose up -d --wait --wait-timeout 180

      - name: Probe service healthchecks
        run: |
          set -e
          declare -A SERVICES=(
            [kosha-api]="http://localhost:8080/actuator/health"
            [keycloak]="http://localhost:8180/health/ready"
            [nats]="http://localhost:8222/healthz"
            [minio]="http://localhost:9000/minio/health/live"
            [opensearch]="http://localhost:9200/_cluster/health"
            [mailpit]="http://localhost:8025/api/v1/info"
          )
          for name in "${!SERVICES[@]}"; do
            url="${SERVICES[$name]}"
            echo "→ probing $name @ $url"
            curl -fsS --max-time 30 "$url" > /dev/null \
              || { echo "FAIL: $name"; docker compose logs "$name"; exit 1; }
          done

      - name: Probe AI sidecar
        run: |
          # Sidecar doesn't expose HTTP; check its log for "started, listening for tasks"
          docker compose logs kosha-ai | grep -q "listening for tasks" \
            || { echo "FAIL: kosha-ai didn't reach steady state"; docker compose logs kosha-ai; exit 1; }

      - name: Tear down
        if: always()
        run: docker compose down -v
```

**Verify** First run: it should fail loudly on whatever's actually broken, then succeed once Track 1 + Batch F lands. From then on, any future env-var rename, compose drift, or healthcheck-config issue surfaces in CI before merge.

**Caveat** Real ports may differ (the snippet above guesses based on standard compose conventions for these images). Cross-check against `docker-compose.yml` port mappings before committing.

---

## Suggested order of work

If you want a sequencing recommendation:

**Day 1 (4-6 hours):** Track 1 in full except 1.6.

That is: 1.1 + 1.2 (auth stubs, ~1 hour with tests), 1.3 (pydantic, 5 minutes), 1.4 (CI yaml, 15 minutes), 1.5 (path A — README update, 5 minutes). The bulk of the time is writing the auth-stub tests properly.

**Day 2 (2-3 hours):** Batch E (placeholder rename) + Batch F (compose hygiene) + Track 3 (smoke workflow). These three together produce a working CI signal that boots the stack — a meaningful before/after change.

**Subsequent sessions, ~1 hour each:** Batch A (frontend deps), Batch B (Python polish), Batch C (Kotlin nits), Batch D (i18n + Keycloak). Order doesn't matter.

**At leisure:** 1.6 (Keycloak service-account migration). Schedule when you have a clean half-day and access to the Keycloak admin console.

---

## What this plan deliberately omits

- **Architectural refactors.** Finding X (kosha-retention/notification event coupling) is a smell, not a bug. Worth a deeper look only if either module is being touched anyway.
- **Test coverage.** Adding tests is a separate concern from this audit's scope. The CI workflow in 1.4 will run whatever tests exist; growing the suite is its own work.
- **Production hardening.** No mention of rate limits, secrets management, image scanning, dependency audits — all worthwhile but beyond "the codebase you have today is sound and maintainable." Pick those up after Track 1 lands and the CI signal is green.
- **The two LangChain-related items** (deprecation of `langchain_community.chat_models.ChatOpenAI`). Functional today; will need migration to `langchain-openai` someday. Not urgent.
- **V010 retroactive schema fix** (Finding V). Cosmetic; not worth touching the migration history.

---

*Plan ends here. Each Track 1 item is small enough to land in a single short PR; the cleanup batches are likewise self-contained. Resist the urge to bundle multiple batches into one big change — small reviewable diffs are the maintainability concern that prompted this whole exercise in the first place.*
