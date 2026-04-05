package dev.kosha.workflow.entity

import dev.kosha.identity.entity.Department
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "workflow_definition", schema = "wf")
class WorkflowDefinition(
    @Column(nullable = false, length = 200)
    var name: String,

    @Column(columnDefinition = "TEXT")
    var description: String? = null,

    /**
     * LINEAR = steps run in sequence, each must complete before the next becomes PENDING.
     * PARALLEL = all steps become PENDING simultaneously when the workflow starts; all must complete.
     */
    @Column(name = "workflow_type", nullable = false, length = 20)
    var workflowType: String = "LINEAR",

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    var department: Department? = null,

    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,

    @OneToMany(
        mappedBy = "workflowDefinition",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = false, // soft-delete preserves rows for in-flight instances
    )
    @OrderBy("stepOrder ASC")
    var steps: MutableList<WorkflowStepDefinition> = mutableListOf(),

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
