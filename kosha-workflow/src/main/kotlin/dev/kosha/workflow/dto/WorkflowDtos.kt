package dev.kosha.workflow.dto

import java.time.OffsetDateTime
import java.util.UUID

// ── Definition DTOs ─────────────────────────────────────────────

/**
 * The current workflow for a department. Returned by
 * `GET /api/v1/departments/{id}/workflow`. Only active (non-deleted) steps
 * are included.
 */
data class WorkflowDefinitionResponse(
    val id: UUID,
    val departmentId: UUID,
    val departmentName: String,
    val name: String,
    val description: String?,
    val workflowType: String,
    val isDefault: Boolean,
    val steps: List<WorkflowStepResponse>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

data class WorkflowStepResponse(
    val id: UUID,
    val stepOrder: Int,
    val name: String,
    val actionType: String,
    val assigneeId: UUID?,
    val assigneeName: String?,
    val assigneeStatus: String?,  // lets the frontend flag inactive assignees
    val escalationId: UUID?,
    val escalationName: String?,
    val escalationStatus: String?,
    val timeLimitDays: Int,
)

/**
 * Request body for `PUT /api/v1/departments/{id}/workflow`. The server
 * replaces the department's workflow atomically — all existing active steps
 * are soft-deleted, and a new set is inserted from [steps]. In-flight
 * workflow instances continue to reference the now-soft-deleted rows.
 *
 * - [workflowType] must be LINEAR or PARALLEL
 * - [steps] must contain at least one step
 * - every step must have a valid assignee and escalation contact (both ACTIVE)
 * - `stepOrder` on each request is ignored; the server re-numbers from 1
 */
data class UpdateWorkflowRequest(
    val name: String? = null,
    val description: String? = null,
    val workflowType: String,
    val steps: List<UpdateWorkflowStepRequest>,
)

data class UpdateWorkflowStepRequest(
    val name: String,
    val actionType: String,
    val assigneeId: UUID,
    val escalationId: UUID,
    val timeLimitDays: Int,
)

// ── Validation DTO ──────────────────────────────────────────────

/**
 * Response from `GET /api/v1/departments/{id}/workflow/validation`.
 *
 * [ready] is true iff the department's workflow is valid for document
 * submission. When false, [problems] contains human-readable messages
 * suitable for display in the UI.
 */
data class WorkflowValidationResponse(
    val ready: Boolean,
    val problems: List<String>,
)
