package dev.kosha.notification.listener

import dev.kosha.common.event.DocumentLegalHoldApplied
import dev.kosha.common.event.LegalReviewerPreSelected
import dev.kosha.common.event.RetentionReviewApproaching
import dev.kosha.common.event.RetentionReviewCritical
import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.notification.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class OwnershipNotificationListener(
    private val notificationService: NotificationService,
    private val userProfileRepo: UserProfileRepository,
    private val departmentRepo: DepartmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onLegalHoldApplied(event: DocumentLegalHoldApplied) {
        log.info("Legal hold applied to document {}, notifying owner {}", event.aggregateId, event.primaryOwnerId)

        val recipients = resolveOwnerAndProxy(event.primaryOwnerId, event.proxyOwnerId)
        val actor = event.actorId?.let { userProfileRepo.findById(it).orElse(null) }

        val vars = mapOf(
            "documentTitle" to event.title,
            "docNumber" to event.aggregateId.toString().take(8),
            "departmentName" to lookupDepartmentName(event.departmentId),
            "actorName" to (actor?.displayName ?: "System"),
            "occurredAt" to event.occurredAt.toLocalDate().toString(),
            "documentUrl" to "/documents/${event.aggregateId}",
        )

        for ((userId, email, name) in recipients) {
            notificationService.sendEmail(
                eventType = "doc.legal-hold.applied",
                recipientEmail = email,
                recipientId = userId,
                vars = vars + ("ownerName" to name),
            )
        }
    }

    @Async
    @EventListener
    fun onRetentionReviewCritical(event: RetentionReviewCritical) {
        log.info("Critical retention review for document {}, {} days overdue", event.documentId, event.daysOverdue)

        val recipients = resolveOwnerAndProxy(event.primaryOwnerId, event.proxyOwnerId)
        val policy = event.policyId.toString().take(8)

        val vars = mapOf(
            "documentTitle" to event.documentTitle,
            "docNumber" to event.documentId.toString().take(8),
            "daysOverdue" to event.daysOverdue.toString(),
            "policyName" to policy,
            "reviewDueDate" to event.occurredAt.minusDays(event.daysOverdue).toLocalDate().toString(),
            "departmentName" to "",
            "documentUrl" to "/documents/${event.documentId}",
        )

        for ((userId, email, name) in recipients) {
            notificationService.sendEmail(
                eventType = "retention.review.critical",
                recipientEmail = email,
                recipientId = userId,
                vars = vars + ("ownerName" to name),
            )
        }
    }

    @Async
    @EventListener
    fun onRetentionReviewApproaching(event: RetentionReviewApproaching) {
        log.info("Retention review approaching for document {}, {} days until due", event.documentId, event.daysUntilDue)

        val recipients = resolveOwnerAndProxy(event.primaryOwnerId, event.proxyOwnerId)

        val vars = mapOf(
            "documentTitle" to event.documentTitle,
            "docNumber" to event.documentId.toString().take(8),
            "daysUntilDue" to event.daysUntilDue.toString(),
            "dueDate" to event.dueAt.toLocalDate().toString(),
            "policyName" to event.policyName,
            "departmentName" to "",
            "documentUrl" to "/documents/${event.documentId}",
        )

        for ((userId, email, name) in recipients) {
            notificationService.sendEmail(
                eventType = "retention.review.approaching",
                recipientEmail = email,
                recipientId = userId,
                vars = vars + ("ownerName" to name),
            )
        }
    }

    /**
     * Courtesy pre-selection email for legal reviewers. Fires when a submitter
     * uploads a document with `requiresLegalReview=true` and picks a specific
     * reviewer from the legal department dropdown. The reviewer does not need
     * to take any action at this point — the email is informational so they
     * know they'll be getting a formal task when the document is submitted
     * for review (Pass 3 of the workflow engine). No due date is tracked yet.
     */
    @Async
    @EventListener
    fun onLegalReviewerPreSelected(event: LegalReviewerPreSelected) {
        log.info(
            "Legal reviewer {} pre-selected for document '{}'",
            event.legalReviewerId, event.documentTitle
        )

        val reviewer = userProfileRepo.findById(event.legalReviewerId).orElse(null)
        if (reviewer == null) {
            log.warn("Legal reviewer {} not found — skipping pre-selection email", event.legalReviewerId)
            return
        }

        val vars = mapOf(
            "documentTitle" to event.documentTitle,
            "submitterName" to event.submitterName,
            "departmentName" to event.departmentName,
            "reviewerName" to reviewer.displayName,
            "timeLimitDays" to event.timeLimitDays.toString(),
        )

        notificationService.sendEmail(
            eventType = "doc.legal-review.pre-selected",
            recipientEmail = reviewer.email,
            recipientId = reviewer.id,
            vars = vars,
        )
    }

    private data class Recipient(val userId: UUID, val email: String, val name: String)

    private fun resolveOwnerAndProxy(primaryOwnerId: UUID, proxyOwnerId: UUID?): List<Recipient> {
        val recipients = mutableListOf<Recipient>()

        userProfileRepo.findById(primaryOwnerId).ifPresent {
            recipients.add(Recipient(it.id!!, it.email, it.displayName))
        }

        if (proxyOwnerId != null && proxyOwnerId != primaryOwnerId) {
            userProfileRepo.findById(proxyOwnerId).ifPresent {
                recipients.add(Recipient(it.id!!, it.email, it.displayName))
            }
        }

        return recipients
    }

    private fun lookupDepartmentName(departmentId: UUID): String =
        departmentRepo.findById(departmentId).map { it.name }.orElse("Unknown")
}
