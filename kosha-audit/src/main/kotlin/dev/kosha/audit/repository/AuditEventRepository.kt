package dev.kosha.audit.repository

import dev.kosha.audit.entity.AuditEvent
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuditEventRepository : JpaRepository<AuditEvent, UUID> {

    fun findByAggregateTypeAndAggregateIdOrderByOccurredAtDesc(
        aggregateType: String,
        aggregateId: UUID,
    ): List<AuditEvent>

    fun findByActorIdOrderByOccurredAtDesc(actorId: UUID, pageable: Pageable): Page<AuditEvent>

    fun findByEventTypeOrderByOccurredAtDesc(eventType: String, pageable: Pageable): Page<AuditEvent>

    fun findAllByOrderByOccurredAtDesc(pageable: Pageable): Page<AuditEvent>
}
