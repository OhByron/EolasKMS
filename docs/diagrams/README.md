# Architecture diagrams

PlantUML sources and rendered SVGs for Eòlas KMS. Sources are the source of truth — re-render after editing.

## Diagram set

| File | Type | Audience | What it shows |
|------|------|----------|---------------|
| [01-c4-container.puml](01-c4-container.puml) → [c4-container.svg](c4-container.svg) | C4 Container view | Architects, DevOps, on-call | The whole system on one page: kosha-app, sidecars, infrastructure, external systems. |
| [02-class-knowledge-core.puml](02-class-knowledge-core.puml) → [class-knowledge-core.svg](class-knowledge-core.svg) | UML class | Backend devs, DBA | `Document` ↔ `DocumentVersion` ↔ `VersionMetadata` ↔ `DocumentCategory`. |
| [03-class-identity.puml](03-class-identity.puml) → [class-identity.svg](class-identity.svg) | UML class | Backend devs, security reviewer | `UserProfile` ↔ `Department` ↔ `AccessGroup`. Keycloak mirroring rule. |
| [04-class-workflow.puml](04-class-workflow.puml) → [class-workflow.svg](class-workflow.svg) | UML class | Workflow / product | `WorkflowDefinition` ↔ `WorkflowStepDefinition` ↔ `WorkflowInstance` ↔ `WorkflowStepInstance`. Soft-delete invariant. |
| [05-class-taxonomy-retention.puml](05-class-taxonomy-retention.puml) → [class-taxonomy-retention.svg](class-taxonomy-retention.svg) | UML class | Taxonomy admins, compliance | `TaxonomyTerm` + alias / edge / classification + `RetentionPolicy` / review. |
| [10-seq-document-ingest.puml](10-seq-document-ingest.puml) → [seq-document-ingest.svg](seq-document-ingest.svg) | UML sequence | Full-stack devs, product | Upload → Tika → MinIO → NATS → sidecar → AI fan-in → OpenSearch. |
| [11-seq-approval-workflow.puml](11-seq-approval-workflow.puml) → [seq-approval-workflow.svg](seq-approval-workflow.svg) | UML sequence | Backend, QA | Submit → step approve/reject → publish or revert to DRAFT. |
| [12-seq-login-jwt.puml](12-seq-login-jwt.puml) → [seq-login-jwt.svg](seq-login-jwt.svg) | UML sequence | Backend, security reviewer | OIDC Auth Code + PKCE through Spring Security `KeycloakJwtConverter`. |

Numbering: `01-09` = structural views, `10-19` = sequences, `20+` reserved for future state machines / deployment views.

## Re-rendering

### Locally with PlantUML

PlantUML is a single jar plus a Java runtime. Java is already required to build the backend.

```sh
# Cache the jar once
mkdir -p ~/.plantuml
curl -sSLf -o ~/.plantuml/plantuml.jar \
  https://github.com/plantuml/plantuml/releases/download/v1.2024.7/plantuml-1.2024.7.jar

# Render everything
cd docs/diagrams
java -jar ~/.plantuml/plantuml.jar -tsvg *.puml
```

Or one diagram at a time:

```sh
java -jar ~/.plantuml/plantuml.jar -tsvg 11-seq-approval-workflow.puml
```

The C4 Container diagram (`01-c4-container.puml`) `!include`s `C4-PlantUML` from GitHub at render time. PlantUML caches that fetch. Air-gapped builds should vendor `C4_Container.puml` locally and change the include path.

### With Docker (no Java needed)

```sh
docker run --rm -v "$PWD/docs/diagrams:/work" -w /work \
  plantuml/plantuml:1.2024.7 -tsvg "*.puml"
```

### With VS Code

Install **PlantUML** (jebbs.plantuml). It renders previews and exports SVGs from the editor. Hit `Alt+D` on an open `.puml` file.

## Other output formats

| Need | How |
|------|-----|
| PNG | `-tpng` instead of `-tsvg` |
| PDF | `-tpdf` (requires extra deps) or render SVG and use a browser → "Print to PDF" |
| Visio (.vsdx) | Render to SVG → import into [draw.io / diagrams.net](https://app.diagrams.net), then File → Export As → VSDX. Or open the SVG directly in Visio (newer versions). |
| Confluence / wiki paste | Render to SVG and attach, or paste the `.puml` source into a [PlantUML server](https://www.plantuml.com/plantuml) URL. |
| Inline in GitHub READMEs | Convert sequences to Mermaid by hand, or commit the SVG and reference it. GitHub does not render PlantUML inline. |

## When to update

| Change | Re-render which diagram(s) |
|--------|--------------------------|
| Add/remove a Gradle module, sidecar, or external system | `01-c4-container` |
| Add a JPA entity or change a relationship | the relevant `class-*` diagram |
| Add or change a NATS subject / event class | `01-c4-container` (subject list), `10-seq-document-ingest` |
| Change workflow step lifecycle or events | `04-class-workflow`, `11-seq-approval-workflow` |
| Change Spring Security config or JWT claims | `12-seq-login-jwt` |

The diagrams are intentionally hand-curated rather than auto-generated. They lag behind the code by design — re-render them on architectural changes, not on every refactor.

## Conventions

- `<<schema.table>>` stereotypes show the Postgres table backing each entity.
- Italic strings on fields call out enum domains (`<i>DRAFT | IN_REVIEW | PUBLISHED</i>`).
- Sequence diagrams use solid arrows for synchronous calls and `-->>` for async events / NATS publishes.
- Boundary boxes (`==`) divide sequences into named phases.
