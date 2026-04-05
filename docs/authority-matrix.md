# Authority matrix

The single source of truth for who is allowed to do what in Eòlas.
Every mutating endpoint in the backend must match one of the rows below,
either via a `@PreAuthorize` expression on the controller method or via a
service-layer check that throws `AccessDeniedException`.

Updated: 2026-04-05 (Pass 4.3)

## Roles

Eòlas has four roles. The JWT from Keycloak carries them in the `roles`
claim. `KeycloakJwtConverter` maps each entry to a Spring authority
prefixed with `ROLE_` — so `roles: ["DEPT_ADMIN"]` becomes authority
`ROLE_DEPT_ADMIN`, usable with `hasRole('DEPT_ADMIN')` in SpEL.

| Role              | Canonical use                                                                             |
|-------------------|-------------------------------------------------------------------------------------------|
| `GLOBAL_ADMIN`    | System administrator — can do anything in any department                                  |
| `DEPT_ADMIN`      | Manages one department's users, workflow, and documents — scoped to their own department  |
| `EDITOR`          | Edits documents in any department they are a member of; can act on steps assigned to them |
| `CONTRIBUTOR`     | Uploads to their home department; can act on steps assigned to them; read-only elsewhere  |

## Rule of thumb

- **Role check alone is enough when the endpoint is system-global** (reports, settings, user listing). Use `@PreAuthorize("hasRole('GLOBAL_ADMIN')")` etc.
- **Role + target check is needed when the endpoint operates on something that has a department or an owner** (edit document, edit department workflow, act on step). Use `@PreAuthorize("@authorityService.canEditDepartment(authentication, #departmentId)")` etc.
- **Entity-level ownership (am I the assignee, am I the owner) stays in the service layer** and throws `AccessDeniedException`. Do not try to express these in SpEL — the annotation becomes unreadable and the check needs entity data that the controller doesn't have.

## Matrix

### Documents

| Action                                  | GLOBAL_ADMIN | DEPT_ADMIN          | EDITOR                  | CONTRIBUTOR              | Enforcement point          |
|-----------------------------------------|:------------:|---------------------|-------------------------|---------------------------|----------------------------|
| List / search documents                 | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (dept member)          | Service-level filter        |
| View a specific document                | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (dept member)          | Service-level check        |
| Create a document                       | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (home dept)            | Controller + service       |
| Update metadata                          | ✅            | ✅ (their dept)      | ✅ (dept member)         | ❌                        | `canEditDocument`          |
| Submit for review                       | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (own uploads only)     | Service-level check        |
| Delete                                  | ✅            | ✅ (their dept)      | ❌                       | ❌                        | `canDeleteDocument`        |
| Upload new version / check in           | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (own uploads only)     | Service-level check        |
| Check out for edit                      | ✅            | ✅ (their dept)      | ✅ (dept member)         | ✅ (own uploads only)     | Service-level check        |
| Place on legal hold                     | ✅            | ❌                   | ❌                       | ❌                        | `hasRole('GLOBAL_ADMIN')`  |
| Release from legal hold                 | ✅            | ❌                   | ❌                       | ❌                        | `hasRole('GLOBAL_ADMIN')`  |
| Transition status (publish / archive)   | via workflow | via workflow        | via workflow            | via workflow              | Workflow engine            |

### Workflow

| Action                                  | GLOBAL_ADMIN | DEPT_ADMIN       | EDITOR  | CONTRIBUTOR | Enforcement point                       |
|-----------------------------------------|:------------:|------------------|---------|-------------|-----------------------------------------|
| Read department workflow                | ✅            | ✅                | ✅       | ✅           | Any authenticated                       |
| Edit department workflow                | ✅            | ✅ (their dept)   | ❌       | ❌           | `canEditDepartment`                     |
| Approve/reject a step assigned to me    | ✅            | ✅                | ✅       | ✅           | Service-level assignee check            |
| Approve/reject a step not assigned to me| ❌            | ❌                | ❌       | ❌           | Service-level assignee check            |
| List my tasks                           | ✅            | ✅                | ✅       | ✅           | Filtered by JWT sub                     |

### Identity — users

| Action                                  | GLOBAL_ADMIN | DEPT_ADMIN       | EDITOR  | CONTRIBUTOR | Enforcement point                       |
|-----------------------------------------|:------------:|------------------|---------|-------------|-----------------------------------------|
| List all users                          | ✅            | ✅ (their dept)   | ❌       | ❌           | Controller + service filter             |
| Get any user                            | ✅            | ✅ (their dept)   | ❌       | ❌           | Controller method check                 |
| Get self                                | ✅            | ✅                | ✅       | ✅           | `/api/v1/me` (JWT subject)              |
| Provision (create) user                 | ✅            | ✅ (their dept)   | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` + dept check for DA |
| Update user profile                     | ✅            | ✅ (their dept)   | ❌       | ❌           | `canManageUser`                         |
| Promote user to GLOBAL_ADMIN or DEPT_ADMIN | ✅         | ❌                | ❌       | ❌           | Service-level role-escalation check     |
| Deactivate / reactivate user            | ✅            | ✅ (their dept)   | ❌       | ❌           | `canManageUser`                         |
| Transfer user between departments       | ✅            | ✅ (sending only) | ❌       | ❌           | Service-level: sending DA may transfer out, receiving DA has no veto |
| Reset another user's password           | ✅            | ✅ (their dept)   | ❌       | ❌           | `canManageUser`                         |
| Reset own password                      | ✅            | ✅                | ✅       | ✅           | `/api/v1/me/password` (JWT subject)     |

### Identity — departments

| Action                                  | GLOBAL_ADMIN | DEPT_ADMIN       | EDITOR  | CONTRIBUTOR | Enforcement point               |
|-----------------------------------------|:------------:|------------------|---------|-------------|---------------------------------|
| List departments                        | ✅            | ✅                | ✅       | ✅           | Any authenticated               |
| Get department                          | ✅            | ✅                | ✅       | ✅           | Any authenticated               |
| Create department                       | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`       |
| Update department                       | ✅            | ✅ (their dept)   | ❌       | ❌           | `canEditDepartment`             |
| Toggle handles_legal_review flag        | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`       |
| Read per-dept scan settings             | ✅            | ✅ (their dept)   | ❌       | ❌           | `canEditDepartment`             |
| Write per-dept scan settings            | ✅            | ✅ (their dept)   | ❌       | ❌           | `canEditDepartment`             |

### Taxonomy, categories, retention, reports, admin

| Action                                  | GLOBAL_ADMIN | DEPT_ADMIN       | EDITOR  | CONTRIBUTOR | Enforcement point                     |
|-----------------------------------------|:------------:|------------------|---------|-------------|---------------------------------------|
| Read taxonomy terms                     | ✅            | ✅                | ✅       | ✅           | Any authenticated                     |
| Edit taxonomy terms                     | ✅            | ✅                | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` |
| Read document categories                | ✅            | ✅                | ✅       | ✅           | Any authenticated                     |
| Edit document categories (incl. flags)  | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read retention policies                 | ✅            | ✅                | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` |
| Edit retention policies                 | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read reports (aging / critical / holds) | ✅            | ✅ (own dept)     | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` + dept filter |
| Trigger critical-item notifications     | ✅            | ✅ (own dept)     | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` |
| Read / edit mail gateway settings       | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read / edit global notification settings| ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read / edit legal review settings       | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read / edit escalation settings         | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| Read / edit AI config                   | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |
| AI-assist (define term, suggest children)| ✅           | ✅                | ❌       | ❌           | `hasAnyRole('GLOBAL_ADMIN','DEPT_ADMIN')` |
| Read audit log                          | ✅            | ❌                | ❌       | ❌           | `hasRole('GLOBAL_ADMIN')`             |

## Non-obvious rules and their rationale

1. **DEPT_ADMIN cannot promote anyone to DEPT_ADMIN or GLOBAL_ADMIN.** Role escalation is strictly global-admin-gated. A dept admin who could create another dept admin could silently broaden the admin set by creating a second dept, moving users in, promoting them, and transferring them back. Preventing self-amplification keeps the admin blast radius predictable.

2. **Department transfer is asymmetric.** A DEPT_ADMIN may transfer users *out* of their own department (relinquishing a team member) but has no role in the receiving department's admin approval. This is pragmatic: the sending admin knows they are losing a user, the receiving side inherits them, and making transfers require two-sided consent creates a workflow problem we don't want to solve today. Global admins can move anyone anywhere.

3. **Legal hold is GLOBAL_ADMIN only.** Placing a document on legal hold is a compliance-grade action that cannot be delegated. Typically the company's legal officer is also the Kosha global admin (or has the role in addition to another one). Dept admins can *see* documents under legal hold in their reports but cannot flip the flag.

4. **Reports are dept-scoped for DEPT_ADMIN.** A DEPT_ADMIN running an aging report sees only documents in their own department. This is enforced by the service layer adding an implicit `WHERE department_id = :caller.departmentId` filter, not by refusing the endpoint outright.

5. **Service-level assignee checks are not in this matrix.** Workflow step approve/reject is authorised by "the caller is the currently assigned user". That check lives in `WorkflowActionService.loadActiveStep` and throws `AccessDeniedException`. The `@PreAuthorize` annotation on the controller just requires authentication — the interesting check is closer to the entity.

6. **Contributors can act on their own submissions.** A CONTRIBUTOR who uploads a document can submit it for review, upload a new version, and check it out. They cannot touch other contributors' documents. This is owner-scoped and enforced in the service layer via `actorId == document.createdBy.id` checks, not as role annotations.

## How this maps to code

- **Role-only checks**: straight `@PreAuthorize("hasRole(...)")` or `hasAnyRole(...)` on the controller method. No SpEL function call.
- **Dept-scoped checks**: `@PreAuthorize("@authorityService.canEditDepartment(authentication, #departmentId)")`. The bean lookup syntax (`@authorityService`) is standard Spring Security SpEL.
- **Document-scoped checks**: `@PreAuthorize("@authorityService.canEditDocument(authentication, #id)")`.
- **Assignee / owner checks**: NOT in SpEL. Stays in the service method, throws `AccessDeniedException`, which `GlobalExceptionHandler` already maps to a 403 response.

## What was already correct before Pass 4

From the pre-Pass-4 inventory, these controllers had working `@PreAuthorize` annotations that just needed the dev bypass chain turned off to become real:

- `AuditController` (class-level `GLOBAL_ADMIN`)
- `RetentionController` (class-level `GLOBAL_ADMIN`)
- `AiAdminController` (class-level `GLOBAL_ADMIN`)
- `UserController.list` and `create` (method-level)
- `DepartmentController.create` and `update` (method-level)
- `TaxonomyController` mutations (method-level)
- `WorkflowTasksController.myTasks` (method-level)

Pass 4.3 adds annotations to the remaining controllers and flips the dev bypass chain to be gated on a property so production deployments don't accidentally ship with it open.
