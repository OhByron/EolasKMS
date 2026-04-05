package dev.kosha.workflow.service

import dev.kosha.common.event.WorkflowCompleted
import dev.kosha.common.event.WorkflowRejected
import dev.kosha.common.event.WorkflowStepAssigned
import dev.kosha.common.event.WorkflowStepCompleted
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.workflow.entity.WorkflowStepInstance
import dev.kosha.workflow.repository.WorkflowInstanceRepository
import dev.kosha.workflow.repository.WorkflowStepInstanceRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Approval and rejection handler for running workflow instances.
 *
 * ## Approval flow
 *
 * Approving a step marks it APPROVED and then advances the instance:
 *
 *   - **LINEAR**: find the next WAITING step (by step order) and promote it
 *     to IN_PROGRESS, setting its due date. If there are no more WAITING
 *     steps, the instance is complete and [WorkflowCompleted] fires so the
 *     document module can publish the document.
 *   - **PARALLEL**: check every sibling on the same instance. If all have
 *     reached a terminal state (APPROVED or SKIPPED), the instance is
 *     complete. If any are still IN_PROGRESS we do nothing — they finish
 *     independently.
 *
 * The legal review step (when present) is always parallel to the main
 * chain regardless of workflow type. It sits on the same instance as a
 * regular step, so the PARALLEL completion rule naturally covers it: the
 * instance only completes when the legal reviewer has approved along with
 * every LINEAR step.
 *
 * ## Rejection flow
 *
 * Rejecting a step requires non-blank comments (captured from the caller).
 * The whole instance is marked REJECTED, any sibling steps are left as-is
 * (their assignees no longer need to act), and [WorkflowRejected] fires so
 * the document module returns the document to DRAFT. The submitter must
 * revise and resubmit — which creates a brand new instance, matching the
 * resubmission rule decided during workflow planning (Option A).
 */
@Service
class WorkflowActionService(
    private val instanceRepo: WorkflowInstanceRepository,
    private val stepInstanceRepo: WorkflowStepInstanceRepository,
    private val userProfileRepo: UserProfileRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun approve(
        workflowInstanceId: UUID,
        stepInstanceId: UUID,
        actorId: UUID,
        comments: String?,
    ): ActionResult {
        val step = loadActiveStep(workflowInstanceId, stepInstanceId, actorId)

        step.status = "APPROVED"
        step.comments = comments?.takeIf { it.isNotBlank() }
        step.decidedAt = OffsetDateTime.now()
        step.updatedAt = OffsetDateTime.now()
        stepInstanceRepo.save(step)

        events.publishEvent(
            WorkflowStepCompleted(
                aggregateId = step.id!!,
                workflowInstanceId = workflowInstanceId,
                stepName = step.stepDefinition.name,
                outcome = "APPROVED",
                actorId = actorId,
            ),
        )

        val instance = step.workflowInstance
        val isLinear = instance.workflowDefinition.workflowType == "LINEAR"

        // LINEAR: promote the next WAITING step (if any). The legal review
        // step is parallel to the main chain, so LINEAR advancement only
        // walks the real step_order sequence — the synthetic legal step
        // has step_order = 9999 and is never "next".
        if (isLinear) {
            val next = stepInstanceRepo
                .findNextWaitingSteps(instance.id!!, step.stepDefinition.stepOrder)
                .firstOrNull { it.stepDefinition.stepOrder < 9999 } // skip synthetic legal
            if (next != null) {
                next.status = "IN_PROGRESS"
                next.dueAt = OffsetDateTime.now().plusDays(next.stepDefinition.timeLimitDays.toLong())
                next.updatedAt = OffsetDateTime.now()
                stepInstanceRepo.save(next)
                log.info(
                    "Advanced LINEAR workflow instance {} to step {} (assignee {})",
                    instance.id, next.stepDefinition.stepOrder, next.assignedTo?.displayName,
                )

                // Notify the newly-active assignee so they can act on the
                // step. Same event + template that fires on instance creation.
                val assignee = next.assignedTo
                if (assignee != null) {
                    events.publishEvent(
                        WorkflowStepAssigned(
                            aggregateId = next.id!!,
                            workflowInstanceId = instance.id!!,
                            documentId = instance.documentId,
                            assigneeId = assignee.id!!,
                            stepName = next.stepDefinition.name,
                            dueAt = next.dueAt,
                            actorId = actorId,
                        ),
                    )
                }
            }
        }

        // Check whether the whole instance is now complete. For both LINEAR
        // and PARALLEL the rule is the same: every step must be in a
        // terminal state. The legal review step (if present) is included
        // in this check, which is exactly what we want — the document is
        // not published until all approvers AND the legal reviewer approve.
        val allSteps = stepInstanceRepo.findByWorkflowInstanceId(instance.id!!)
        val terminalStates = setOf("APPROVED", "SKIPPED")
        val stillRunning = allSteps.any { it.status !in terminalStates }

        if (!stillRunning) {
            instance.status = "COMPLETED"
            instance.completedAt = OffsetDateTime.now()
            instance.updatedAt = OffsetDateTime.now()
            instanceRepo.save(instance)

            events.publishEvent(
                WorkflowCompleted(
                    aggregateId = instance.id!!,
                    documentId = instance.documentId,
                    actorId = actorId,
                ),
            )
            log.info(
                "Workflow instance {} completed — document {} ready to publish",
                instance.id, instance.documentId,
            )
            return ActionResult(
                stepInstanceId = step.id!!,
                stepStatus = step.status,
                workflowInstanceStatus = "COMPLETED",
                documentId = instance.documentId,
            )
        }

        return ActionResult(
            stepInstanceId = step.id!!,
            stepStatus = step.status,
            workflowInstanceStatus = instance.status,
            documentId = instance.documentId,
        )
    }

    @Transactional
    fun reject(
        workflowInstanceId: UUID,
        stepInstanceId: UUID,
        actorId: UUID,
        comments: String?,
    ): ActionResult {
        // Rejection comments are mandatory — the reviewer must tell the
        // submitter what needs to change. Enforced here rather than at the
        // DTO layer so the rule is co-located with the state machine.
        val trimmed = comments?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException(
                "Rejection requires comments explaining what needs to change",
            )
        }

        val step = loadActiveStep(workflowInstanceId, stepInstanceId, actorId)
        val instance = step.workflowInstance
        val rejector = userProfileRepo.findById(actorId)
            .orElseThrow { NoSuchElementException("Rejecting user not found: $actorId") }

        step.status = "REJECTED"
        step.comments = trimmed
        step.decidedAt = OffsetDateTime.now()
        step.updatedAt = OffsetDateTime.now()
        stepInstanceRepo.save(step)

        instance.status = "REJECTED"
        instance.completedAt = OffsetDateTime.now()
        instance.rejectionComments = trimmed
        // The FK on workflow_instance.rejected_at_step_id targets the step
        // *definition* row (V022 schema decision), not the step instance.
        // This gives audit reports "which step in the workflow template
        // blocked this submission" which survives instance cleanup.
        instance.rejectedAtStepId = step.stepDefinition.id
        instance.rejectedBy = rejector
        instance.updatedAt = OffsetDateTime.now()
        instanceRepo.save(instance)

        events.publishEvent(
            WorkflowStepCompleted(
                aggregateId = step.id!!,
                workflowInstanceId = workflowInstanceId,
                stepName = step.stepDefinition.name,
                outcome = "REJECTED",
                actorId = actorId,
            ),
        )
        events.publishEvent(
            WorkflowRejected(
                aggregateId = instance.id!!,
                documentId = instance.documentId,
                reason = trimmed,
                actorId = actorId,
            ),
        )

        log.info(
            "Workflow instance {} rejected at step {} by {} — document {} returned to submitter",
            instance.id, step.stepDefinition.name, rejector.displayName, instance.documentId,
        )

        return ActionResult(
            stepInstanceId = step.id!!,
            stepStatus = "REJECTED",
            workflowInstanceStatus = "REJECTED",
            documentId = instance.documentId,
        )
    }

    /**
     * Common preamble for approve/reject: find the step, verify it's on the
     * claimed instance, verify it's currently actionable, and verify the
     * caller is the assignee. Every branch that reaches the state change
     * goes through here so authority checks cannot be skipped.
     */
    private fun loadActiveStep(
        workflowInstanceId: UUID,
        stepInstanceId: UUID,
        actorId: UUID,
    ): WorkflowStepInstance {
        val step = stepInstanceRepo.findById(stepInstanceId)
            .orElseThrow { NoSuchElementException("Step instance not found: $stepInstanceId") }

        if (step.workflowInstance.id != workflowInstanceId) {
            throw NoSuchElementException(
                "Step $stepInstanceId does not belong to workflow instance $workflowInstanceId",
            )
        }

        if (step.status != "IN_PROGRESS") {
            throw IllegalStateException(
                "Step $stepInstanceId cannot be actioned: status is ${step.status}",
            )
        }

        // Escalation re-assigns `assigned_to` to the escalation contact, so
        // this single assignee check is sufficient to cover both the normal
        // case and the post-escalation case. Escalated steps carry an
        // `escalated_at` timestamp for audit but the authorisation logic
        // reads only `assigned_to`.
        if (step.assignedTo?.id != actorId) {
            throw AccessDeniedException(
                "User $actorId is not assigned to step $stepInstanceId",
            )
        }

        return step
    }
}

/**
 * Lightweight result payload for approve/reject endpoints. Just enough for
 * the frontend to know what the next screen should show — the detail page
 * re-fetches the document for the authoritative state.
 */
data class ActionResult(
    val stepInstanceId: UUID,
    val stepStatus: String,
    val workflowInstanceStatus: String,
    val documentId: UUID,
)
