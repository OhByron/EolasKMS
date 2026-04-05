package dev.kosha.workflow.repository

import dev.kosha.workflow.entity.WorkflowDefinition
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WorkflowDefinitionRepository : JpaRepository<WorkflowDefinition, UUID> {

    /**
     * Returns the workflow for a department. Since the v1 model enforces one
     * workflow per department, this returns at most one row. The method
     * name uses `First` so Spring Data generates a safe query even if the
     * DB somehow has multiple.
     */
    fun findFirstByDepartmentId(departmentId: UUID): WorkflowDefinition?

    /**
     * Fetches the workflow with its steps eagerly loaded, filtering out
     * soft-deleted steps. Returns only active steps in step_order sequence.
     */
    @Query("""
        SELECT DISTINCT wd FROM WorkflowDefinition wd
        LEFT JOIN FETCH wd.steps s
        WHERE wd.department.id = :departmentId
          AND (s IS NULL OR s.deletedAt IS NULL)
    """)
    fun findByDepartmentIdWithActiveSteps(departmentId: UUID): WorkflowDefinition?
}
