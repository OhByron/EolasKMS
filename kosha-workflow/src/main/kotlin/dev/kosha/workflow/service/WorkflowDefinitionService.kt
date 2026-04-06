package dev.kosha.workflow.service

import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.workflow.dto.UpdateWorkflowRequest
import dev.kosha.workflow.dto.UpdateWorkflowStepRequest
import dev.kosha.workflow.dto.WorkflowDefinitionResponse
import dev.kosha.workflow.dto.WorkflowStepResponse
import dev.kosha.workflow.dto.WorkflowValidationResponse
import dev.kosha.workflow.entity.WorkflowDefinition
import dev.kosha.workflow.entity.WorkflowStepDefinition
import dev.kosha.workflow.repository.WorkflowDefinitionRepository
import dev.kosha.workflow.repository.WorkflowStepDefinitionRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Manages a department's workflow definition.
 *
 * Core operations:
 * - [findByDepartment] — read the current workflow
 * - [updateDepartmentWorkflow] — replace the definition atomically (soft-deletes
 *   old step rows to preserve in-flight instance FKs)
 * - [validateForSubmission] — pre-submission check used by the document
 *   submission flow and by the UI to show warnings
 *
 * Notes on the mutation model:
 *
 * Rather than trying to diff-and-update individual step rows, [updateDepartmentWorkflow]
 * marks every existing active step as deleted and inserts a fresh set. This is
 * simpler and correct: in-flight WorkflowStepInstance rows still reference the
 * old step_def ids (now soft-deleted), so their FKs remain valid. New instances
 * created after the update will reference the new step rows.
 */
@Service
class WorkflowDefinitionService(
    private val workflowRepo: WorkflowDefinitionRepository,
    private val stepRepo: WorkflowStepDefinitionRepository,
    private val departmentRepo: DepartmentRepository,
    private val userProfileRepo: UserProfileRepository,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        val VALID_WORKFLOW_TYPES = setOf("LINEAR", "PARALLEL")
        val VALID_ACTION_TYPES = setOf("REVIEW", "APPROVE", "SIGN_OFF")
        const val MIN_TIME_LIMIT_DAYS = 1
        const val MAX_TIME_LIMIT_DAYS = 90
        const val MAX_STEPS_PER_WORKFLOW = 15
    }

    // ── Read ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun findByDepartment(departmentId: UUID): WorkflowDefinitionResponse? {
        val workflow = workflowRepo.findFirstByDepartmentId(departmentId) ?: return null
        return workflow.toResponse()
    }

    @Transactional(readOnly = true)
    fun validateForSubmission(departmentId: UUID): WorkflowValidationResponse {
        val problems = mutableListOf<String>()

        val workflow = workflowRepo.findFirstByDepartmentId(departmentId)
        if (workflow == null) {
            problems.add("Department has no workflow configured. A department admin must create one before documents can be submitted.")
            return WorkflowValidationResponse(ready = false, problems = problems)
        }

        val activeSteps = stepRepo
            .findByWorkflowDefinitionIdAndDeletedAtIsNullOrderByStepOrderAsc(workflow.id!!)
        if (activeSteps.isEmpty()) {
            problems.add("Workflow has no steps. Add at least one step to the workflow.")
        }

        activeSteps.forEachIndexed { idx, step ->
            val label = "Step ${idx + 1} (${step.name})"
            val assignee = step.assignee
            val escalation = step.escalation

            if (assignee == null) {
                problems.add("$label has no assignee.")
            } else if (assignee.status != "ACTIVE") {
                problems.add("$label is assigned to ${assignee.displayName} who is not active.")
            }

            if (escalation == null) {
                problems.add("$label has no escalation contact.")
            } else if (escalation.status != "ACTIVE") {
                problems.add("$label has ${escalation.displayName} as escalation contact who is not active.")
            }
        }

        return WorkflowValidationResponse(
            ready = problems.isEmpty(),
            problems = problems,
        )
    }

    // ── Write ─────────────────────────────────────────────────────

    /**
     * Replace the workflow definition for a department. If no workflow exists
     * yet, one is created. Existing active step rows are soft-deleted; new
     * step rows are inserted from the request.
     *
     * Validation happens up-front — nothing is persisted unless the whole
     * request is valid.
     */
    @Transactional
    fun updateDepartmentWorkflow(
        departmentId: UUID,
        request: UpdateWorkflowRequest,
    ): WorkflowDefinitionResponse {
        validateRequest(request)

        val department = departmentRepo.findById(departmentId)
            .orElseThrow { NoSuchElementException("Department not found: $departmentId") }

        // Resolve assignee and escalation users up front so we fail fast
        // before mutating anything.
        val resolvedSteps = request.steps.mapIndexed { idx, stepRequest ->
            val assignee = userProfileRepo.findById(stepRequest.assigneeId).orElseThrow {
                IllegalArgumentException(
                    "Step ${idx + 1} assignee not found: ${stepRequest.assigneeId}"
                )
            }
            val escalation = userProfileRepo.findById(stepRequest.escalationId).orElseThrow {
                IllegalArgumentException(
                    "Step ${idx + 1} escalation contact not found: ${stepRequest.escalationId}"
                )
            }
            require(assignee.status == "ACTIVE") {
                "Step ${idx + 1} assignee '${assignee.displayName}' is not active"
            }
            require(escalation.status == "ACTIVE") {
                "Step ${idx + 1} escalation contact '${escalation.displayName}' is not active"
            }
            Triple(stepRequest, assignee, escalation)
        }

        val now = OffsetDateTime.now()

        // Find or create the workflow definition
        val workflow = workflowRepo.findFirstByDepartmentId(departmentId)
            ?: WorkflowDefinition(
                name = request.name ?: "${department.name} Workflow",
                description = request.description,
                workflowType = request.workflowType,
                department = department,
                isDefault = false,
            ).also { workflowRepo.save(it) }

        // Update definition fields
        request.name?.let { workflow.name = it }
        request.description?.let { workflow.description = it }
        workflow.workflowType = request.workflowType
        // Once edited by an admin, it's no longer the seeded default
        workflow.isDefault = false
        workflow.updatedAt = now

        // Soft-delete existing active steps — they stay referenced by any
        // in-flight workflow_step_instance rows, but new lookups filter them out.
        //
        // We must flush to the DB before inserting the new step rows. Hibernate
        // would otherwise reorder inserts before updates within the transaction,
        // and the new inserts would collide with the still-active (in-memory
        // un-flushed) step rows on the partial unique index
        // (workflow_def_id, step_order) WHERE deleted_at IS NULL.
        val existing = stepRepo
            .findByWorkflowDefinitionIdAndDeletedAtIsNullOrderByStepOrderAsc(workflow.id!!)
        existing.forEach {
            it.deletedAt = now
            it.updatedAt = now
            stepRepo.save(it)
        }
        entityManager.flush()

        // Insert the new step set. stepOrder is re-numbered from 1 regardless
        // of what the client sent, so drag-reorder in the UI is just "send the
        // new order".
        resolvedSteps.forEachIndexed { idx, (stepRequest, assignee, escalation) ->
            val step = WorkflowStepDefinition(
                workflowDefinition = workflow,
                stepOrder = idx + 1,
                name = stepRequest.name,
                actionType = stepRequest.actionType,
                assignee = assignee,
                escalation = escalation,
                timeLimitDays = stepRequest.timeLimitDays,
                conditionJson = stepRequest.conditionJson?.takeIf { it.isNotBlank() },
            )
            stepRepo.save(step)
        }

        log.info(
            "Updated workflow for department '{}': {} {} steps",
            department.name, resolvedSteps.size, request.workflowType
        )

        // Re-fetch to get a fresh view with the new active steps
        return findByDepartment(departmentId)
            ?: error("Workflow disappeared after update — this should never happen")
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun validateRequest(request: UpdateWorkflowRequest) {
        require(request.workflowType in VALID_WORKFLOW_TYPES) {
            "Invalid workflow type '${request.workflowType}'. Must be one of: $VALID_WORKFLOW_TYPES"
        }
        require(request.steps.isNotEmpty()) {
            "Workflow must have at least one step"
        }
        require(request.steps.size <= MAX_STEPS_PER_WORKFLOW) {
            "Workflow cannot have more than $MAX_STEPS_PER_WORKFLOW steps"
        }
        request.steps.forEachIndexed { idx, step ->
            val label = "Step ${idx + 1}"
            require(step.name.isNotBlank()) { "$label name is required" }
            require(step.actionType in VALID_ACTION_TYPES) {
                "$label has invalid action type '${step.actionType}'. Must be one of: $VALID_ACTION_TYPES"
            }
            require(step.timeLimitDays in MIN_TIME_LIMIT_DAYS..MAX_TIME_LIMIT_DAYS) {
                "$label time limit must be between $MIN_TIME_LIMIT_DAYS and $MAX_TIME_LIMIT_DAYS days"
            }
        }
    }

    private fun WorkflowDefinition.toResponse(): WorkflowDefinitionResponse {
        val dept = department
            ?: error("Workflow ${id} has no department — invariant violated")
        val activeSteps = stepRepo
            .findByWorkflowDefinitionIdAndDeletedAtIsNullOrderByStepOrderAsc(id!!)
        return WorkflowDefinitionResponse(
            id = id!!,
            departmentId = dept.id!!,
            departmentName = dept.name,
            name = name,
            description = description,
            workflowType = workflowType,
            isDefault = isDefault,
            steps = activeSteps.map { it.toResponse() },
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun WorkflowStepDefinition.toResponse() = WorkflowStepResponse(
        id = id!!,
        stepOrder = stepOrder,
        name = name,
        actionType = actionType,
        assigneeId = assignee?.id,
        assigneeName = assignee?.displayName,
        assigneeStatus = assignee?.status,
        escalationId = escalation?.id,
        escalationName = escalation?.displayName,
        escalationStatus = escalation?.status,
        timeLimitDays = timeLimitDays,
        conditionJson = conditionJson,
    )
}
