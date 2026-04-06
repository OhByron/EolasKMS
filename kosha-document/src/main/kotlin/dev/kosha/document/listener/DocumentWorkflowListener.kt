package dev.kosha.document.listener

import dev.kosha.common.event.DocumentStatusChanged
import dev.kosha.common.event.WorkflowCompleted
import dev.kosha.common.event.WorkflowRejected
import dev.kosha.common.event.WorkflowSignOffApproved
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.document.service.SignatureService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Bridges the workflow module to document state.
 *
 * `kosha-workflow` knows nothing about documents beyond an opaque id. When
 * a workflow instance reaches a terminal state it publishes a domain event
 * and this listener — which lives in `kosha-document` — translates that
 * into the appropriate status transition on the document. The two modules
 * stay decoupled and their unit tests can be written against the events
 * rather than each other.
 *
 * Both listeners run synchronously in the publisher's transaction (the
 * approve/reject service call), so a document status update that fails
 * will also roll back the workflow state change. Approving the final step
 * is atomic with publishing the document; rejecting any step is atomic
 * with returning it to DRAFT.
 *
 * We re-fire [DocumentStatusChanged] here rather than going through
 * `DocumentService.update()` because:
 *   1. `update()` has its own DRAFT→IN_REVIEW trigger that would infinite-
 *      loop back into the engine if we routed through it
 *   2. The status change here has no "actor" in the normal sense — it is
 *      a system-driven transition whose actor is the approver or rejecter
 *      captured on the workflow instance, which we already know
 *   3. We want to do a direct save without going through the update DTO
 */
@Component
class DocumentWorkflowListener(
    private val documentRepo: DocumentRepository,
    private val events: ApplicationEventPublisher,
    private val signatureService: SignatureService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    @Transactional
    fun onWorkflowCompleted(event: WorkflowCompleted) {
        val doc = documentRepo.findById(event.documentId).orElse(null)
        if (doc == null || doc.isDeleted) {
            log.warn("Workflow {} completed but document {} not found", event.aggregateId, event.documentId)
            return
        }

        val previous = doc.status
        if (previous != "IN_REVIEW") {
            // This can only happen if someone bypassed the engine and
            // transitioned the document manually — log and bail rather
            // than stomp on whatever state it is in now.
            log.warn(
                "Workflow {} completed but document {} is in {} (expected IN_REVIEW) — not auto-publishing",
                event.aggregateId, event.documentId, previous,
            )
            return
        }

        doc.status = "PUBLISHED"
        doc.updatedAt = OffsetDateTime.now()
        documentRepo.save(doc)

        events.publishEvent(
            DocumentStatusChanged(
                aggregateId = doc.id!!,
                previousStatus = previous,
                newStatus = "PUBLISHED",
                actorId = event.actorId,
            ),
        )

        log.info("Published document {} after workflow {} completed", doc.id, event.aggregateId)
    }

    @EventListener
    @Transactional
    fun onWorkflowRejected(event: WorkflowRejected) {
        val doc = documentRepo.findById(event.documentId).orElse(null)
        if (doc == null || doc.isDeleted) {
            log.warn("Workflow {} rejected but document {} not found", event.aggregateId, event.documentId)
            return
        }

        val previous = doc.status
        if (previous != "IN_REVIEW") {
            log.warn(
                "Workflow {} rejected but document {} is in {} (expected IN_REVIEW) — not auto-reverting",
                event.aggregateId, event.documentId, previous,
            )
            return
        }

        doc.status = "DRAFT"
        doc.updatedAt = OffsetDateTime.now()
        documentRepo.save(doc)

        events.publishEvent(
            DocumentStatusChanged(
                aggregateId = doc.id!!,
                previousStatus = previous,
                newStatus = "DRAFT",
                actorId = event.actorId,
            ),
        )

        log.info(
            "Returned document {} to DRAFT after workflow {} rejected: {}",
            doc.id, event.aggregateId, event.reason,
        )
    }

    /**
     * Auto-create a signature when a SIGN_OFF workflow step is approved.
     * Runs synchronously in the approver's transaction so a signature
     * creation failure rolls back the approval — we don't want an
     * approved-but-unsigned step in the audit trail.
     */
    @EventListener
    @Transactional
    fun onSignOffApproved(event: WorkflowSignOffApproved) {
        try {
            signatureService.signFromWorkflow(
                documentId = event.documentId,
                versionId = event.versionId,
                signerId = event.signerId,
            )
            log.info(
                "Auto-signed document {} version {} by user {} (workflow step {})",
                event.documentId, event.versionId, event.signerId, event.aggregateId,
            )
        } catch (ex: IllegalStateException) {
            // "Already signed" is acceptable — the user may have manually
            // signed before the workflow step completed. Log and continue.
            log.info("Sign-off auto-sign skipped: {}", ex.message)
        }
    }
}
