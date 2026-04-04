package dev.kosha.common.event

import java.time.OffsetDateTime
import java.util.UUID

abstract class DomainEvent(
    val eventId: UUID = UUID.randomUUID(),
    val occurredAt: OffsetDateTime = OffsetDateTime.now(),
    val actorId: UUID? = null,
) {
    abstract val eventType: String
    abstract val aggregateType: String
    abstract val aggregateId: UUID
}

// --- Document events ---

data class DocumentCreated(
    override val aggregateId: UUID,
    val title: String,
    val departmentId: UUID,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "doc.created"
    override val aggregateType = "document"
}

data class DocumentVersionCreated(
    override val aggregateId: UUID,
    val documentId: UUID,
    val versionNumber: String,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "doc.version.created"
    override val aggregateType = "document_version"
}

data class DocumentCheckedOut(
    override val aggregateId: UUID,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "doc.checked-out"
    override val aggregateType = "document"
}

data class DocumentCheckedIn(
    override val aggregateId: UUID,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "doc.checked-in"
    override val aggregateType = "document"
}

data class DocumentStatusChanged(
    override val aggregateId: UUID,
    val previousStatus: String,
    val newStatus: String,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "doc.status.changed"
    override val aggregateType = "document"
}

// --- Workflow events ---

data class WorkflowStepCompleted(
    override val aggregateId: UUID,
    val workflowInstanceId: UUID,
    val stepName: String,
    val outcome: String,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "wf.step.completed"
    override val aggregateType = "workflow_instance"
}

data class WorkflowCompleted(
    override val aggregateId: UUID,
    val documentId: UUID,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "wf.completed"
    override val aggregateType = "workflow_instance"
}

data class WorkflowRejected(
    override val aggregateId: UUID,
    val documentId: UUID,
    val reason: String?,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "wf.rejected"
    override val aggregateType = "workflow_instance"
}

// --- Taxonomy events ---

data class TaxonomyTermCreated(
    override val aggregateId: UUID,
    val label: String,
    val source: String,
    override val actorId: UUID?,
) : DomainEvent(actorId = actorId) {
    override val eventType = "tax.term.created"
    override val aggregateType = "taxonomy_term"
}

// --- Retention events ---

data class RetentionReviewDue(
    override val aggregateId: UUID,
    val documentId: UUID,
    val policyId: UUID,
) : DomainEvent() {
    override val eventType = "retention.review.due"
    override val aggregateType = "retention_review"
}

// --- AI events ---

data class AiTaskSubmitted(
    override val aggregateId: UUID,
    val documentId: UUID,
    val versionId: UUID,
    val taskType: String,
    val storageKey: String,
) : DomainEvent() {
    override val eventType = "ai.task.submitted"
    override val aggregateType = "ai_task"
}
