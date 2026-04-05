package dev.kosha.notification.listener

import dev.kosha.common.event.WorkflowRejected
import dev.kosha.common.event.WorkflowStepAssigned
import dev.kosha.common.event.WorkflowStepEscalated
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.notification.service.NotificationService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Email notifications driven by workflow engine events.
 *
 * Both listeners look up document / user / instance metadata themselves
 * rather than receiving it in the event payload. This keeps the event
 * shapes stable as display templates evolve — add a new `{{variable}}`
 * to the template, update this listener to populate it, and you never
 * have to touch the event contract or the publishers.
 *
 * Queries go through `EntityManager` native SQL rather than injecting
 * `DocumentRepository` because the notification module does not depend on
 * `kosha-document`. Joining against another module's table via JPQL would
 * require the document entity on the classpath; native SQL sidesteps that.
 */
@Component
class WorkflowNotificationListener(
    private val notificationService: NotificationService,
    private val userProfileRepo: UserProfileRepository,
    private val entityManager: EntityManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, MMM d yyyy 'at' h:mm a")

    @Async
    @EventListener
    fun onStepAssigned(event: WorkflowStepAssigned) {
        val assignee = userProfileRepo.findById(event.assigneeId).orElse(null) ?: run {
            log.warn("Step assigned event fired but assignee {} not found", event.assigneeId)
            return
        }

        val docInfo = loadDocumentInfo(event.documentId)
        if (docInfo == null) {
            log.warn("Step assigned event fired but document {} not found", event.documentId)
            return
        }

        val submitter = loadSubmitterForInstance(event.workflowInstanceId)

        val vars = mapOf(
            "assigneeName" to assignee.displayName,
            "documentTitle" to docInfo.title,
            "departmentName" to docInfo.departmentName,
            "submitterName" to (submitter ?: "Unknown"),
            "stepName" to event.stepName,
            "dueDate" to (event.dueAt?.format(dateFormatter) ?: "not set"),
            "inboxUrl" to "/inbox",
        )

        notificationService.sendEmail(
            eventType = "wf.step.assigned",
            recipientEmail = assignee.email,
            recipientId = assignee.id,
            vars = vars,
        )
    }

    @Async
    @EventListener
    fun onWorkflowRejected(event: WorkflowRejected) {
        // Find the submitter and the rejecting step details from the
        // workflow_instance and workflow_step_instance rows. The instance
        // carries `initiated_by` (submitter) and `rejected_by` (rejector)
        // plus the rejected step id.
        val info = loadRejectionInfo(event.aggregateId)
        if (info == null) {
            log.warn("Workflow rejected event fired but instance {} not found", event.aggregateId)
            return
        }

        val docInfo = loadDocumentInfo(event.documentId) ?: return

        val vars = mapOf(
            "submitterName" to info.submitterName,
            "documentTitle" to docInfo.title,
            "stepName" to info.stepName,
            "rejectorName" to (info.rejectorName ?: "a reviewer"),
            "rejectionComments" to (event.reason ?: "(no comments provided)"),
            "documentUrl" to "/documents/${event.documentId}",
        )

        notificationService.sendEmail(
            eventType = "wf.rejected-to-submitter",
            recipientEmail = info.submitterEmail,
            recipientId = info.submitterId,
            vars = vars,
        )
    }

    @Async
    @EventListener
    fun onStepEscalated(event: WorkflowStepEscalated) {
        val newAssignee = userProfileRepo.findById(event.newAssigneeId).orElse(null) ?: run {
            log.warn("Step escalated event fired but new assignee {} not found", event.newAssigneeId)
            return
        }
        val previous = event.previousAssigneeId?.let {
            userProfileRepo.findById(it).orElse(null)
        }
        val docInfo = loadDocumentInfo(event.documentId) ?: return

        // Look up the step to get the fresh due_at set by the scanner.
        val newDueDate = loadStepDueDate(event.aggregateId)

        val vars = mapOf(
            "escalationName" to newAssignee.displayName,
            "documentTitle" to docInfo.title,
            "departmentName" to docInfo.departmentName,
            "stepName" to event.stepName,
            "previousAssigneeName" to (previous?.displayName ?: "(no prior assignee)"),
            "newDueDate" to (newDueDate?.format(dateFormatter) ?: "not set"),
            "inboxUrl" to "/inbox",
        )

        notificationService.sendEmail(
            eventType = "wf.step.escalated",
            recipientEmail = newAssignee.email,
            recipientId = newAssignee.id,
            vars = vars,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun loadStepDueDate(stepInstanceId: UUID): java.time.OffsetDateTime? {
        return try {
            entityManager.createNativeQuery(
                "SELECT due_at FROM wf.workflow_step_instance WHERE id = :id",
            )
                .setParameter("id", stepInstanceId)
                .singleResult as? java.time.OffsetDateTime
        } catch (_: Exception) {
            null
        }
    }


    private data class DocumentInfo(val title: String, val departmentName: String)

    @Suppress("UNCHECKED_CAST")
    private fun loadDocumentInfo(documentId: UUID): DocumentInfo? {
        return try {
            val row = entityManager.createNativeQuery(
                """
                SELECT d.title, dept.name
                FROM doc.document d
                JOIN ident.department dept ON dept.id = d.department_id
                WHERE d.id = :docId
                """.trimIndent(),
            )
                .setParameter("docId", documentId)
                .singleResult as Array<Any?>
            DocumentInfo(title = row[0] as String, departmentName = row[1] as String)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadSubmitterForInstance(instanceId: UUID): String? {
        return try {
            entityManager.createNativeQuery(
                """
                SELECT u.display_name
                FROM wf.workflow_instance wi
                JOIN ident.user_profile u ON u.id = wi.initiated_by
                WHERE wi.id = :instanceId
                """.trimIndent(),
            )
                .setParameter("instanceId", instanceId)
                .singleResult as String?
        } catch (_: Exception) {
            null
        }
    }

    private data class RejectionInfo(
        val submitterId: UUID,
        val submitterName: String,
        val submitterEmail: String,
        val stepName: String,
        val rejectorName: String?,
    )

    @Suppress("UNCHECKED_CAST")
    private fun loadRejectionInfo(instanceId: UUID): RejectionInfo? {
        return try {
            val row = entityManager.createNativeQuery(
                """
                SELECT
                    submitter.id,
                    submitter.display_name,
                    submitter.email,
                    step_def.name,
                    rejector.display_name
                FROM wf.workflow_instance wi
                JOIN ident.user_profile submitter ON submitter.id = wi.initiated_by
                LEFT JOIN wf.workflow_step_definition step_def ON step_def.id = wi.rejected_at_step_id
                LEFT JOIN ident.user_profile rejector ON rejector.id = wi.rejected_by
                WHERE wi.id = :instanceId
                """.trimIndent(),
            )
                .setParameter("instanceId", instanceId)
                .singleResult as Array<Any?>

            RejectionInfo(
                submitterId = row[0] as UUID,
                submitterName = row[1] as String,
                submitterEmail = row[2] as String,
                stepName = (row[3] as? String) ?: "Review step",
                rejectorName = row[4] as? String,
            )
        } catch (_: Exception) {
            null
        }
    }
}
