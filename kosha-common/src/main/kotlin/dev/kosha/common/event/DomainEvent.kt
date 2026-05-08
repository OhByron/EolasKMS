package dev.kosha.common.event

import java.time.OffsetDateTime
import java.util.UUID

abstract class DomainEvent(
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: OffsetDateTime = OffsetDateTime.now(),
) {
    abstract val eventType: String
    abstract val aggregateType: String
    abstract val aggregateId: UUID
    abstract val actorId: UUID?
}

// --- Document events ---

data class DocumentCreated(
    override val aggregateId: UUID,
    val title: String,
    val departmentId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.created"
    override val aggregateType = "document"
}

data class DocumentVersionCreated(
    override val aggregateId: UUID,
    val documentId: UUID,
    val versionNumber: String,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.version.created"
    override val aggregateType = "document_version"
}

data class DocumentCheckedOut(
    override val aggregateId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.checked-out"
    override val aggregateType = "document"
}

data class DocumentCheckedIn(
    override val aggregateId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.checked-in"
    override val aggregateType = "document"
}

data class DocumentStatusChanged(
    override val aggregateId: UUID,
    val previousStatus: String,
    val newStatus: String,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.status.changed"
    override val aggregateType = "document"
}

data class DocumentLegalHoldApplied(
    override val aggregateId: UUID,
    val title: String,
    val primaryOwnerId: UUID,
    val proxyOwnerId: UUID?,
    val departmentId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.legal-hold.applied"
    override val aggregateType = "document"
}

/**
 * Fired when a document is created with `requiresLegalReview=true` and a
 * specific legal reviewer is selected. This is a pre-selection heads-up —
 * the document is still in DRAFT and no workflow step instance exists yet.
 * The actual review task is created when the workflow engine picks up the
 * document (Pass 3). This event triggers a courtesy email so the reviewer
 * knows they'll be on the hook soon.
 */
data class LegalReviewerPreSelected(
    override val aggregateId: UUID,      // document id
    val documentTitle: String,
    val submitterId: UUID,
    val submitterName: String,
    val departmentId: UUID,
    val departmentName: String,
    val legalReviewerId: UUID,
    val timeLimitDays: Int,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.legal-review.pre-selected"
    override val aggregateType = "document"
}

// --- User events ---

/**
 * Fired when an admin resets a user's password. The listener in the
 * notification module emails the new temporary password to the affected
 * user. The event is intentionally in-process only — it is not relayed to
 * NATS, so the `temporaryPassword` field never leaves the JVM that fires it.
 * If that changes, switch to an opaque token and have the listener fetch
 * the password from a short-lived store.
 */
data class UserPasswordReset(
    override val aggregateId: UUID,     // user_profile.id
    val userEmail: String,
    val userDisplayName: String,
    val departmentName: String,
    val temporaryPassword: String,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "user.password.reset"
    override val aggregateType = "user_profile"
}

/**
 * Fired when a document transitions from DRAFT to IN_REVIEW. This is the
 * primary trigger for the workflow engine: it consumes the event
 * synchronously, creates a `workflow_instance` with the right set of
 * `workflow_step_instance` rows, and injects a legal review step if
 * requested. If the engine throws (broken workflow, missing assignees,
 * etc.) the publisher's transaction rolls back and the document stays in
 * DRAFT, so the UI never sees a half-submitted state.
 *
 * Separate from [DocumentStatusChanged] because the engine needs richer
 * context (version, legal review flags) that the general-purpose status
 * event does not carry. `DocumentStatusChanged` still fires alongside this
 * event so notification/audit listeners can react to the state change.
 */
data class DocumentSubmittedForReview(
    override val aggregateId: UUID,      // document id
    val versionId: UUID,                 // version being reviewed (usually the latest)
    val departmentId: UUID,
    val submitterId: UUID,
    val requiresLegalReview: Boolean,
    val legalReviewerId: UUID?,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "doc.submitted-for-review"
    override val aggregateType = "document"
}

// --- Workflow events ---

/**
 * Fired when a workflow step instance transitions to IN_PROGRESS and the
 * assigned user needs to be notified. Published in three situations:
 *
 *   1. Initial creation of a PARALLEL workflow (all steps fire at once)
 *   2. Initial creation of a LINEAR workflow (only the first step)
 *   3. LINEAR advancement when an earlier step approves
 *   4. Legal-review synthetic step injection
 *
 * The listener in `kosha-notification` looks up document/submitter/dept
 * details via repositories rather than receiving them in the payload so
 * the event stays stable as display templates evolve.
 */
data class WorkflowStepAssigned(
    override val aggregateId: UUID,        // workflow_step_instance id
    val workflowInstanceId: UUID,
    val documentId: UUID,
    val assigneeId: UUID,
    val stepName: String,
    val dueAt: OffsetDateTime?,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "wf.step.assigned"
    override val aggregateType = "workflow_step_instance"
}

/**
 * Fired when an overdue workflow step is reassigned from the primary
 * assignee to the escalation contact. The notification listener emails
 * the new assignee and (optionally) the original assignee so both know
 * the handoff happened. [reason] distinguishes the common escalation
 * triggers so templates can word themselves appropriately:
 *
 *   - `DEADLINE_MISSED` — primary assignee failed to act by the due date
 *   - `NO_DEPT_ADMIN`   — synthetic fallback workflow could not find a
 *     dept admin at submission time; escalation was global admin from
 *     the start. This is fired for audit symmetry only; there is no
 *     actual reassignment because the step was already owned by global
 *     admin. May be omitted from v1 if not needed.
 */
data class WorkflowStepEscalated(
    override val aggregateId: UUID,      // workflow_step_instance id
    val workflowInstanceId: UUID,
    val documentId: UUID,
    val previousAssigneeId: UUID?,
    val newAssigneeId: UUID,
    val stepName: String,
    val reason: String,                  // DEADLINE_MISSED | NO_DEPT_ADMIN
    override val actorId: UUID? = null,  // null = system/scheduler
) : DomainEvent() {
    override val eventType = "wf.step.escalated"
    override val aggregateType = "workflow_step_instance"
}

/**
 * Fired when a workflow step with action_type=SIGN_OFF is approved.
 * The document module listens for this and auto-creates a signature
 * record so the approver doesn't need to sign separately.
 */
data class WorkflowSignOffApproved(
    override val aggregateId: UUID,     // step instance id
    val workflowInstanceId: UUID,
    val documentId: UUID,
    val versionId: UUID,
    val signerId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "wf.sign-off.approved"
    override val aggregateType = "workflow_step_instance"
}

data class WorkflowStepCompleted(
    override val aggregateId: UUID,
    val workflowInstanceId: UUID,
    val stepName: String,
    val outcome: String,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "wf.step.completed"
    override val aggregateType = "workflow_instance"
}

data class WorkflowCompleted(
    override val aggregateId: UUID,
    val documentId: UUID,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "wf.completed"
    override val aggregateType = "workflow_instance"
}

data class WorkflowRejected(
    override val aggregateId: UUID,
    val documentId: UUID,
    val reason: String?,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "wf.rejected"
    override val aggregateType = "workflow_instance"
}

// --- Taxonomy events ---

data class TaxonomyTermCreated(
    override val aggregateId: UUID,
    val label: String,
    val source: String,
    override val actorId: UUID?,
) : DomainEvent() {
    override val eventType = "tax.term.created"
    override val aggregateType = "taxonomy_term"
}

// --- Retention events ---

data class RetentionReviewDue(
    override val aggregateId: UUID,
    val documentId: UUID,
    val policyId: UUID,
    override val actorId: UUID? = null,
) : DomainEvent() {
    override val eventType = "retention.review.due"
    override val aggregateType = "retention_review"
}

data class RetentionReviewCritical(
    override val aggregateId: UUID,
    val documentId: UUID,
    val documentTitle: String,
    val policyId: UUID,
    val departmentId: UUID,
    val primaryOwnerId: UUID,
    val proxyOwnerId: UUID?,
    val daysOverdue: Long,
    override val actorId: UUID? = null,
) : DomainEvent() {
    override val eventType = "retention.review.critical"
    override val aggregateType = "retention_review"
}

data class RetentionReviewApproaching(
    override val aggregateId: UUID,
    val documentId: UUID,
    val documentTitle: String,
    val policyId: UUID,
    val policyName: String,
    val departmentId: UUID,
    val primaryOwnerId: UUID,
    val proxyOwnerId: UUID?,
    val daysUntilDue: Long,
    val dueAt: OffsetDateTime,
    override val actorId: UUID? = null,
) : DomainEvent() {
    override val eventType = "retention.review.approaching"
    override val aggregateType = "retention_review"
}

// --- AI events ---

data class AiTaskSubmitted(
    override val aggregateId: UUID,
    val documentId: UUID,
    val versionId: UUID,
    val taskType: String,
    val storageKey: String,
    override val actorId: UUID? = null,
) : DomainEvent() {
    override val eventType = "ai.task.submitted"
    override val aggregateType = "ai_task"
}
