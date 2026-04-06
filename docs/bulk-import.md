# Bulk import

Eòlas ships with a command-line importer for loading pre-existing
documents and their metadata into the system from a directory + CSV
manifest. This is the v1 migration tool: CLI-first, fail-loud on
mismatched references, resumable on interruption.

## When to use it

- Initial migration from an existing DMS (SharePoint, M-Files, network
  shares) after exporting files and writing a CSV manifest of their
  metadata
- Loading test datasets into a non-production Eòlas instance
- Bulk re-import after a restore or environment rebuild

## When NOT to use it

- Ongoing automation: use the REST API directly instead
- Bulk user creation: a separate user-import command is planned in a
  follow-up release (see roadmap note on "4.2.1 User import CSV")

## Prerequisites

1. Eòlas is running and reachable (`curl -sf /actuator/health/liveness`
   returns OK from wherever the CLI runs)
2. A global admin account token can be obtained from Keycloak, either
   via password grant (dev) or a service-account token (prod)
3. All **users**, **departments**, and **document categories**
   referenced by the CSV already exist in Eòlas. Unknown references
   fail the row loudly — there is no auto-provisioning in v1

## CSV schema

Headers are required on the first line. Columns:

| Column                  | Required | Notes                                                                                         |
|-------------------------|:--------:|-----------------------------------------------------------------------------------------------|
| `file_path`             |    Yes   | Path to the file, relative to the root directory passed on the command line                  |
| `title`                 |    Yes   | Document title as it will appear in Eòlas                                                     |
| `description`           |    No    | Free text; blank allowed                                                                      |
| `department_name`       |    Yes   | Exact match against `ident.department.name` (case-insensitive)                                |
| `category_name`         |    No    | Exact match against `doc.document_category.name` (case-insensitive); blank means no category  |
| `owner_email`           |    Yes   | Exact match against `ident.user_profile.email`; unknown = row fails                           |
| `tags`                  |    No    | Semicolon-separated list of taxonomy term labels; unknown labels = row fails                  |
| `requires_legal_review` |    No    | `true`/`false` (case-insensitive); blank = false                                              |
| `legal_reviewer_email`  |    Cond. | Required when `requires_legal_review=true`; must be in a department flagged for legal review  |

### Example

```csv
file_path,title,description,department_name,category_name,owner_email,tags,requires_legal_review,legal_reviewer_email
./policies/travel.pdf,"Travel Expense Policy","Company travel reimbursement policy","Finance","Policy","maria@example.com","travel;expense;policy",false,
./contracts/acme-msa.docx,"Acme MSA","Master services agreement","Legal","Contract","anne@example.com","contract;msa",true,"legal-reviewer@example.com"
./forms/onboarding.docx,"New Hire Onboarding","HR onboarding checklist","Human Resources","Form","frank@example.com","hr;onboarding",false,
```

## Running the importer

The importer is a small Kotlin CLI that ships in the `kosha-import`
module. Build it once with gradle, then run it as a fat jar.

```bash
# Build
./gradlew :kosha-import:bootJar

# Dry-run — validate the CSV without writing anything
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8081 \
  --token "$KOSHA_ADMIN_TOKEN" \
  --dry-run

# Real run — actually creates documents, versions, and uploads bytes
java -jar kosha-import/build/libs/kosha-import.jar \
  --csv ./manifest.csv \
  --root ./documents \
  --api-url http://localhost:8081 \
  --token "$KOSHA_ADMIN_TOKEN"
```

### Token acquisition (dev)

```bash
KOSHA_ADMIN_TOKEN=$(curl -s -X POST \
  "http://localhost:8180/realms/kosha/protocol/openid-connect/token" \
  -d grant_type=password \
  -d client_id=kosha-web \
  -d username=admin@kosha.dev \
  -d password=admin | jq -r .access_token)
```

## What dry-run validates

The dry-run mode calls `POST /api/v1/admin/import/validate` on the
backend with the CSV content. The backend:

1. Parses every row and reports syntactic errors (missing required
   fields, malformed booleans, unknown columns)
2. Resolves every `department_name` against `ident.department`
3. Resolves every `category_name` against `doc.document_category`
4. Resolves every `owner_email` against `ident.user_profile`
5. Resolves every `legal_reviewer_email` against users in departments
   flagged `handles_legal_review=true`
6. Resolves every tag in `tags` against `tax.taxonomy_term`
7. Returns a per-row `ok/error` verdict

No files are touched during dry-run — only the CSV content is sent.
After a green dry-run, re-running without `--dry-run` executes the
real import.

## Resumability

On each successful row the CLI writes a state entry to
`.import-state.json` in the current directory. Re-running the same
CSV against the same state file skips rows that have already been
imported. This means:

- A crashed or interrupted import can be resumed by simply re-running
  the same command
- A partial failure (e.g. row 47 of 1000 fails because of a missing
  tag) is recoverable: fix row 47, re-run, and only rows ≥47 are
  attempted
- To force a fresh import, delete `.import-state.json` before running

## Error handling

- **Validation error** (dry-run catches it): the CLI exits with status
  2 and prints a per-row error summary. Nothing is written to Eòlas.
- **Row error during real run** (e.g. file missing from disk, API
  returns 4xx): the row is marked failed in the state file, the CLI
  continues with the next row. Final summary reports N succeeded, M
  failed. Failed rows can be re-run after fixing the underlying issue.
- **Network error** (API unreachable, timeout): the CLI aborts with
  exit status 3. Re-running resumes from the last completed row.

## Known limitations (v1)

- **Only the current version** of each document is imported. Version
  history from the source system is discarded.
- **Ownership is fail-loud**: unknown `owner_email` fails the row.
  Pre-provision users via the admin UI or a future user-import CSV
  before running the document import.
- **No workflow entry**: imported documents land as DRAFT. The admin
  decides whether and when to submit them for review.
- **No parallelism**: the CLI processes rows sequentially. Expect
  ~1-2 docs/sec depending on AI sidecar load. Batches of 10k+
  documents are practical but not fast.
