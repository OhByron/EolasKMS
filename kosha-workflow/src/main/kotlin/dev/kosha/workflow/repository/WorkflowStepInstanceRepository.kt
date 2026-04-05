package dev.kosha.workflow.repository

import dev.kosha.workflow.entity.WorkflowStepInstance
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface WorkflowStepInstanceRepository : JpaRepository<WorkflowStepInstance, UUID> {
    fun findByWorkflowInstanceId(workflowInstanceId: UUID): List<WorkflowStepInstance>
    fun findByAssignedToIdAndStatus(assignedToId: UUID, status: String): List<WorkflowStepInstance>

    /**
     * Active tasks for a user's inbox — anything currently demanding their
     * attention. We exclude WAITING because those steps haven't started yet.
     */
    @Query(
        """
        SELECT s FROM WorkflowStepInstance s
        WHERE s.assignedTo.id = :userId
          AND s.status = 'IN_PROGRESS'
        ORDER BY s.createdAt ASC
        """,
    )
    fun findActiveTasksForUser(userId: UUID): List<WorkflowStepInstance>

    /**
     * Steps on the same workflow instance excluding the one provided. Used
     * by the action service to determine whether a PARALLEL workflow has
     * completed (all siblings APPROVED) after a given step approves.
     */
    @Query(
        """
        SELECT s FROM WorkflowStepInstance s
        WHERE s.workflowInstance.id = :instanceId
          AND s.id <> :excludeId
        """,
    )
    fun findSiblings(instanceId: UUID, excludeId: UUID): List<WorkflowStepInstance>

    /**
     * Steps scheduled to start after the given step order, for LINEAR
     * advancement. Returns steps in ascending order so the first WAITING
     * step is the next one to activate.
     */
    @Query(
        """
        SELECT s FROM WorkflowStepInstance s
        WHERE s.workflowInstance.id = :instanceId
          AND s.stepDefinition.stepOrder > :afterOrder
          AND s.status = 'WAITING'
        ORDER BY s.stepDefinition.stepOrder ASC
        """,
    )
    fun findNextWaitingSteps(instanceId: UUID, afterOrder: Int): List<WorkflowStepInstance>

    /**
     * Overdue steps that have not yet been escalated. Drives the escalation
     * scanner in Phase 3d.
     */
    @Query(
        """
        SELECT s FROM WorkflowStepInstance s
        WHERE s.status = 'IN_PROGRESS'
          AND s.dueAt IS NOT NULL
          AND s.dueAt < :now
          AND s.escalatedAt IS NULL
        """,
    )
    fun findOverdueUnescalated(now: OffsetDateTime): List<WorkflowStepInstance>
}
