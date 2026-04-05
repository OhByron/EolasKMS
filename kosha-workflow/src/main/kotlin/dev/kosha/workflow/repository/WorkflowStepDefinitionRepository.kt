package dev.kosha.workflow.repository

import dev.kosha.workflow.entity.WorkflowStepDefinition
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WorkflowStepDefinitionRepository : JpaRepository<WorkflowStepDefinition, UUID> {

    /**
     * Active (non-deleted) steps for a workflow, in step_order sequence.
     */
    fun findByWorkflowDefinitionIdAndDeletedAtIsNullOrderByStepOrderAsc(
        workflowDefinitionId: UUID,
    ): List<WorkflowStepDefinition>
}
