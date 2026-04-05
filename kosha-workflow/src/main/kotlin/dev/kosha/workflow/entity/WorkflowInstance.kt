package dev.kosha.workflow.entity

import dev.kosha.identity.entity.UserProfile
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A running or completed workflow against a specific document version.
 *
 * Instances reference the [WorkflowDefinition] by id. Because step definitions
 * are soft-deleted rather than removed, an instance started against version
 * 1 of a workflow keeps executing against that version even if the admin
 * later edits the workflow — all the instance's step_def_id references
 * still resolve.
 */
@Entity
@Table(name = "workflow_instance", schema = "wf")
class WorkflowInstance(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_def_id", nullable = false)
    var workflowDefinition: WorkflowDefinition,

    @Column(name = "document_id", nullable = false)
    var documentId: UUID,

    @Column(name = "version_id", nullable = false)
    var versionId: UUID,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false)
    var initiatedBy: UserProfile,

    /**
     * IN_PROGRESS = workflow is running, steps are pending or in progress
     * COMPLETED = all steps approved, document can be published
     * REJECTED = at least one step rejected; submitter must revise and resubmit as a new instance
     * CANCELLED = manually cancelled by an admin before completion
     */
    @Column(nullable = false, length = 30)
    var status: String = "IN_PROGRESS",

    @Column(name = "started_at", nullable = false)
    var startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "completed_at")
    var completedAt: OffsetDateTime? = null,

    // ── Rejection tracking ─────────────────────────────────────
    @Column(name = "rejection_comments", columnDefinition = "TEXT")
    var rejectionComments: String? = null,

    @Column(name = "rejected_at_step_id")
    var rejectedAtStepId: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by")
    var rejectedBy: UserProfile? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    var id: UUID? = null
}
