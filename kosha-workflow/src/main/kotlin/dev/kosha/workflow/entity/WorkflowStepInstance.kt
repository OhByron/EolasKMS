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
 * A single step in an executing workflow instance.
 *
 * ## Lifecycle
 *
 * - **WAITING**: step is not yet active (LINEAR workflows only — waiting for
 *   an earlier step to complete).
 * - **PENDING**: step is active and awaiting the assignee's decision. In
 *   LINEAR workflows only one step is PENDING at a time; in PARALLEL all
 *   steps become PENDING simultaneously at instance creation.
 * - **APPROVED**: assignee approved. In LINEAR the next step advances to
 *   PENDING; in PARALLEL we check whether all siblings are APPROVED.
 * - **REJECTED**: assignee rejected. The entire instance is marked REJECTED
 *   and the submitter must resubmit.
 * - **SKIPPED**: reserved for admin overrides; unused in v1.
 */
@Entity
@Table(name = "workflow_step_instance", schema = "wf")
class WorkflowStepInstance(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_inst_id", nullable = false)
    var workflowInstance: WorkflowInstance,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "step_def_id", nullable = false)
    var stepDefinition: WorkflowStepDefinition,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    var assignedTo: UserProfile? = null,

    @Column(nullable = false, length = 30)
    var status: String = "WAITING",

    @Column(columnDefinition = "TEXT")
    var comments: String? = null,

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null,

    @Column(name = "due_at")
    var dueAt: OffsetDateTime? = null,

    @Column(name = "escalated_at")
    var escalatedAt: OffsetDateTime? = null,

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
