package dev.kosha.workflow.repository

import dev.kosha.workflow.entity.WorkflowInstance
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface WorkflowInstanceRepository : JpaRepository<WorkflowInstance, UUID> {
    fun findByDocumentIdOrderByStartedAtDesc(documentId: UUID): List<WorkflowInstance>
    fun existsByDocumentIdAndStatus(documentId: UUID, status: String): Boolean
}
