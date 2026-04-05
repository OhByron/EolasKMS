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
 * A single step in a workflow definition. Identifies who approves the step
 * (the assignee), who takes over if they don't respond in time (the
 * escalation contact), what kind of action they must take, and the deadline.
 *
 * ## Soft delete
 *
 * When the admin edits a workflow, existing step rows are not removed. They
 * are marked with [deletedAt] so that in-flight [WorkflowStepInstance] rows
 * that still reference them keep their FK target intact. Queries for "the
 * current workflow definition" filter on `deletedAt IS NULL`.
 */
@Entity
@Table(name = "workflow_step_definition", schema = "wf")
class WorkflowStepDefinition(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workflow_def_id", nullable = false)
    var workflowDefinition: WorkflowDefinition,

    @Column(name = "step_order", nullable = false)
    var stepOrder: Int,

    @Column(nullable = false, length = 200)
    var name: String,

    /**
     * REVIEW = read/comment step (optional pre-approval check)
     * APPROVE = approval step (the standard action)
     * SIGN_OFF = final attestation (optional post-approval step)
     *
     * The distinction is cosmetic in v1 — all three behave identically in the
     * engine (they produce approve/reject outcomes with mandatory comments on
     * reject). The labels exist so the UI can render them differently and so
     * audit events carry meaningful semantics.
     */
    @Column(name = "action_type", nullable = false, length = 30)
    var actionType: String = "APPROVE",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_user_id")
    var assignee: UserProfile? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "escalation_user_id")
    var escalation: UserProfile? = null,

    @Column(name = "time_limit_days", nullable = false)
    var timeLimitDays: Int = 3,

    /**
     * Legacy role-based assignment from V003. Kept nullable for possible
     * future role-based assignment; not used by the v1 engine.
     */
    @Column(name = "assignee_role", length = 30)
    var assigneeRole: String? = null,

    @Column(name = "timeout_hours")
    var timeoutHours: Int? = null,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    var id: UUID? = null

    val isActive: Boolean get() = deletedAt == null
}
