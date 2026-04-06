package dev.kosha.workflow.service

import dev.kosha.common.event.DocumentSubmittedForReview
import dev.kosha.common.event.WorkflowStepAssigned
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.workflow.entity.WorkflowDefinition
import dev.kosha.workflow.entity.WorkflowInstance
import dev.kosha.workflow.entity.WorkflowStepDefinition
import dev.kosha.workflow.entity.WorkflowStepInstance
import dev.kosha.workflow.repository.WorkflowDefinitionRepository
import dev.kosha.workflow.repository.WorkflowInstanceRepository
import dev.kosha.workflow.repository.WorkflowStepDefinitionRepository
import dev.kosha.workflow.repository.WorkflowStepInstanceRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Runtime orchestrator for the workflow engine.
 *
 * ## Trigger model
 *
 * The engine is driven by Spring application events. [DocumentSubmittedForReview]
 * is published by `DocumentService.update()` when a document transitions from
 * DRAFT to IN_REVIEW, and the listener here runs **synchronously in the
 * caller's transaction**. This gives us two important guarantees:
 *
 *   1. Atomicity — if the engine throws (broken workflow, missing assignees,
 *      schema violation, etc.) the document's status update rolls back. The
 *      user never sees "submitted for review but no one was notified".
 *   2. Ordering — listeners run after the status change is recorded in the
 *      current transaction but before commit, so queries that happen inside
 *      the engine see the document in IN_REVIEW state.
 *
 * The module boundary between `kosha-document` and `kosha-workflow` is
 * preserved — neither depends on the other. They communicate only through
 * the event contract defined in `kosha-common`.
 *
 * ## Instance creation
 *
 * For a LINEAR workflow the engine creates step instances in order and
 * marks only the first as IN_PROGRESS; the rest are WAITING and will be
 * advanced one at a time as earlier steps approve. For a PARALLEL workflow
 * every step is created IN_PROGRESS simultaneously and the instance
 * completes only when all have APPROVED.
 *
 * ## Legal review injection
 *
 * When [DocumentSubmittedForReview.requiresLegalReview] is true and a
 * reviewer was pre-selected on the upload form, the engine adds one extra
 * step instance assigned to that reviewer, running in parallel with the
 * main chain regardless of workflow type. To satisfy the FK constraint on
 * `workflow_step_instance.step_def_id` we persist a throwaway
 * [WorkflowStepDefinition] row that is marked soft-deleted from creation —
 * it never appears in admin UI queries (which filter `deleted_at IS NULL`)
 * but its PK stays valid for as long as the instance row exists.
 *
 * ## Advancement and completion
 *
 * This service is the creation half of the engine only (Phase 3a). The
 * approve/reject endpoints, LINEAR advancement, PARALLEL completion, and
 * rejection handling live in a separate service landing in Phase 3b so
 * each piece can be tested independently.
 */
@Service
class WorkflowEngineService(
    private val instanceRepo: WorkflowInstanceRepository,
    private val stepInstanceRepo: WorkflowStepInstanceRepository,
    private val stepDefRepo: WorkflowStepDefinitionRepository,
    private val definitionRepo: WorkflowDefinitionRepository,
    private val userProfileRepo: UserProfileRepository,
    private val workflowDefinitionService: WorkflowDefinitionService,
    private val entityManager: EntityManager,
    private val events: org.springframework.context.ApplicationEventPublisher,
    private val conditionEvaluator: ConditionEvaluator,
    private val conditionContext: WorkflowConditionContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Handle a document being submitted for review. Runs synchronously in
     * the publisher's transaction so any failure here rolls back the
     * submitting status update.
     */
    @EventListener
    @Transactional
    fun onDocumentSubmittedForReview(event: DocumentSubmittedForReview) {
        log.info(
            "Engine received submission for document {} version {} (dept {}, legal review={})",
            event.aggregateId, event.versionId, event.departmentId, event.requiresLegalReview,
        )

        // Validate the configured workflow. If it is missing or broken we
        // fall back to a synthetic single-step flow so the submission is
        // never silently dropped. The fallback is the safety net for
        // departments that somehow lost their seeded default workflow, and
        // for automatic resubmissions triggered by a new version upload
        // where we cannot pop an error dialog in the user's face.
        val validation = workflowDefinitionService.validateForSubmission(event.departmentId)
        val workflow = if (validation.ready) {
            definitionRepo.findFirstByDepartmentId(event.departmentId)
                ?: error("Validation passed but workflow not found — invariant violated")
        } else {
            log.warn(
                "Department {} workflow is broken ({}) — synthesising fallback flow",
                event.departmentId, validation.problems.joinToString("; "),
            )
            buildSyntheticFallbackWorkflow(event.departmentId)
                ?: throw IllegalStateException(
                    "Cannot submit document for review: department ${event.departmentId} has no " +
                        "workflow and no eligible approver (dept admin or global admin) is available. " +
                        "Original problems: ${validation.problems.joinToString("; ")}",
                )
        }

        val submitter = userProfileRepo.findById(event.submitterId)
            .orElseThrow { NoSuchElementException("Submitter not found: ${event.submitterId}") }

        val instance = WorkflowInstance(
            workflowDefinition = workflow,
            documentId = event.aggregateId,
            versionId = event.versionId,
            initiatedBy = submitter,
            status = "IN_PROGRESS",
        )
        val savedInstance = instanceRepo.save(instance)

        // Create one step instance per active step in the definition. The
        // LINEAR/PARALLEL distinction is purely which rows start life as
        // IN_PROGRESS vs WAITING.
        val activeSteps = stepDefRepo
            .findByWorkflowDefinitionIdAndDeletedAtIsNullOrderByStepOrderAsc(workflow.id!!)
        val isLinear = workflow.workflowType == "LINEAR"

        // Build the condition evaluation context once — shared across
        // all steps in this submission. The context contains document
        // metadata + extracted NER fields from the AI sidecar.
        val condContext = conditionContext.buildContext(event.aggregateId, event.versionId)

        val now = OffsetDateTime.now()
        activeSteps.forEachIndexed { idx, stepDef ->
            // Evaluate the step's condition (Pass 5.3). If the condition
            // is null/blank the step always fires (backwards compatible).
            // If the condition evaluates to falsy, the step is created as
            // SKIPPED — it never enters the assignee's inbox and doesn't
            // block workflow completion.
            val conditionPasses = conditionEvaluator.shouldFire(stepDef.conditionJson, condContext)

            val startStatus = if (!conditionPasses) {
                "SKIPPED"
            } else if (isLinear && idx > 0) {
                "WAITING"
            } else {
                "IN_PROGRESS"
            }

            val dueAt = if (startStatus == "IN_PROGRESS") {
                now.plusDays(stepDef.timeLimitDays.toLong())
            } else {
                null
            }

            val stepInst = WorkflowStepInstance(
                workflowInstance = savedInstance,
                stepDefinition = stepDef,
                assignedTo = if (startStatus == "SKIPPED") null else stepDef.assignee,
                status = startStatus,
                dueAt = dueAt,
            )
            val savedStep = stepInstanceRepo.save(stepInst)

            if (!conditionPasses) {
                log.info(
                    "Step '{}' (order {}) auto-skipped by condition: {}",
                    stepDef.name, stepDef.stepOrder, stepDef.conditionJson?.take(60),
                )
            }

            // Fire the assignment notification only for steps that are
            // actually IN_PROGRESS right now. WAITING and SKIPPED steps
            // don't need notifications.
            if (startStatus == "IN_PROGRESS" && stepDef.assignee != null) {
                events.publishEvent(
                    WorkflowStepAssigned(
                        aggregateId = savedStep.id!!,
                        workflowInstanceId = savedInstance.id!!,
                        documentId = event.aggregateId,
                        assigneeId = stepDef.assignee!!.id!!,
                        stepName = stepDef.name,
                        dueAt = dueAt,
                        actorId = event.submitterId,
                    ),
                )
            }
        }

        // Legal review runs as a parallel branch regardless of workflow type.
        // See class-level kdoc for the throwaway step-def trick that keeps
        // the FK valid without polluting admin UI queries.
        if (event.requiresLegalReview) {
            val reviewerId = event.legalReviewerId
                ?: throw IllegalStateException(
                    "Document marked requiresLegalReview but no reviewer was pre-selected",
                )
            val reviewer = userProfileRepo.findById(reviewerId)
                .orElseThrow { NoSuchElementException("Legal reviewer not found: $reviewerId") }

            // The legal reviewer's own department admin is the escalation
            // contact. This mirrors the rule captured in the project memory:
            // escalation on legal review timeout goes to the legal dept's
            // admin, NOT the document's department admin.
            val legalDept = reviewer.department
            val legalEscalation = resolveDeptAdmin(legalDept.id!!)

            val syntheticDef = WorkflowStepDefinition(
                workflowDefinition = workflow,
                stepOrder = 9999, // sentinel — never appears in active queries
                name = "Legal Review",
                actionType = "APPROVE",
                assignee = reviewer,
                escalation = legalEscalation,
                timeLimitDays = legalReviewTimeLimitDays(),
                deletedAt = now, // soft-deleted from birth so admin UI ignores it
            )
            val savedDef = stepDefRepo.save(syntheticDef)

            // Flush so the new step_def row is visible to the step_instance
            // FK check when Hibernate orders the insert. Same pattern used
            // by WorkflowDefinitionService.updateDepartmentWorkflow.
            entityManager.flush()

            val legalStepInst = WorkflowStepInstance(
                workflowInstance = savedInstance,
                stepDefinition = savedDef,
                assignedTo = reviewer,
                status = "IN_PROGRESS",
                dueAt = now.plusDays(syntheticDef.timeLimitDays.toLong()),
            )
            val savedLegalStep = stepInstanceRepo.save(legalStepInst)

            events.publishEvent(
                WorkflowStepAssigned(
                    aggregateId = savedLegalStep.id!!,
                    workflowInstanceId = savedInstance.id!!,
                    documentId = event.aggregateId,
                    assigneeId = reviewer.id!!,
                    stepName = syntheticDef.name,
                    dueAt = savedLegalStep.dueAt,
                    actorId = event.submitterId,
                ),
            )

            log.info(
                "Injected legal review step for instance {} (reviewer {}, dept {})",
                savedInstance.id, reviewer.displayName, legalDept.name,
            )
        }

        log.info(
            "Created workflow instance {} with {} step(s){} for document {}",
            savedInstance.id,
            activeSteps.size,
            if (event.requiresLegalReview) " + legal review" else "",
            event.aggregateId,
        )
    }

    /**
     * Read the global legal review time limit from `notif.legal_review_settings`.
     * Duplicated from `DocumentService.legalReviewTimeLimitDays()` — kept as a
     * native query to avoid coupling `kosha-workflow` to `kosha-notification`.
     * Defaults to 5 days if the row is somehow missing.
     */
    private fun legalReviewTimeLimitDays(): Int {
        return try {
            val result = entityManager
                .createNativeQuery(
                    "SELECT default_time_limit_days FROM notif.legal_review_settings WHERE id = 'default'",
                )
                .singleResult
            (result as Number).toInt()
        } catch (_: Exception) {
            5
        }
    }

    /**
     * Last-resort workflow constructor for submissions that hit a broken
     * department workflow. Creates a brand-new `WorkflowDefinition` with
     * exactly one step, assignee = department admin (or global admin if
     * none), escalation contact = global admin. The definition is marked
     * `isDefault = false` so it does not interfere with whatever the admin
     * eventually defines, and it is not linked to the department — it
     * belongs only to this one instance.
     *
     * Returns null if no eligible approver exists anywhere in the system.
     */
    private fun buildSyntheticFallbackWorkflow(departmentId: UUID): WorkflowDefinition? {
        val deptAdmin = resolveDeptAdmin(departmentId)
        val globalAdmin = resolveGlobalAdmin() ?: return null
        val assignee = deptAdmin ?: globalAdmin

        // Fetch the department so the definition has a name the UI can
        // render if it ever surfaces in audit — the synthetic def is not
        // linked via department_id so the admin workflow editor will
        // never see it, but it still needs a pretty label.
        val dept = entityManager.createQuery(
            "SELECT d FROM Department d WHERE d.id = :id",
            dev.kosha.identity.entity.Department::class.java,
        )
            .setParameter("id", departmentId)
            .resultList
            .firstOrNull()

        val syntheticDef = WorkflowDefinition(
            name = "Fallback Review (${dept?.name ?: "unknown department"})",
            description = "Auto-generated because the department workflow is missing or broken",
            workflowType = "LINEAR",
            department = null, // intentionally not attached — invisible to admin UI
            isDefault = false,
        )
        definitionRepo.save(syntheticDef)

        val step = WorkflowStepDefinition(
            workflowDefinition = syntheticDef,
            stepOrder = 1,
            name = if (deptAdmin != null) "Department Admin Approval" else "Global Admin Approval",
            actionType = "APPROVE",
            assignee = assignee,
            escalation = globalAdmin, // always escalate to global admin
            timeLimitDays = 3,
        )
        stepDefRepo.save(step)
        entityManager.flush()

        log.info(
            "Synthetic fallback workflow {} built for department {} (assignee {})",
            syntheticDef.id, departmentId, assignee.displayName,
        )
        return syntheticDef
    }

    /**
     * Find an active GLOBAL_ADMIN for the fallback escalation path. Returns
     * null if there isn't one, in which case the synthetic workflow cannot
     * be built and the caller must throw.
     */
    private fun resolveGlobalAdmin(): dev.kosha.identity.entity.UserProfile? {
        return try {
            entityManager
                .createQuery(
                    """
                    SELECT u FROM UserProfile u
                    WHERE u.role = 'GLOBAL_ADMIN'
                      AND u.status = 'ACTIVE'
                    ORDER BY u.createdAt ASC
                    """.trimIndent(),
                    dev.kosha.identity.entity.UserProfile::class.java,
                )
                .setMaxResults(1)
                .resultList
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Find an active DEPT_ADMIN for the given department, for use as an
     * escalation contact. Returns null if there is none — the 3d escalation
     * scanner is responsible for falling back to GLOBAL_ADMIN in that case.
     */
    private fun resolveDeptAdmin(departmentId: UUID): dev.kosha.identity.entity.UserProfile? {
        return try {
            entityManager
                .createQuery(
                    """
                    SELECT u FROM UserProfile u
                    WHERE u.department.id = :deptId
                      AND u.role = 'DEPT_ADMIN'
                      AND u.status = 'ACTIVE'
                    ORDER BY u.createdAt ASC
                    """.trimIndent(),
                    dev.kosha.identity.entity.UserProfile::class.java,
                )
                .setParameter("deptId", departmentId)
                .setMaxResults(1)
                .resultList
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
