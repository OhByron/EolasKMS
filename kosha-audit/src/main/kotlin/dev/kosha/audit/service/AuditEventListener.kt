package dev.kosha.audit.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.kosha.audit.entity.AuditEvent
import dev.kosha.audit.repository.AuditEventRepository
import dev.kosha.common.event.DomainEvent
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class AuditEventListener(
    private val auditRepo: AuditEventRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun onDomainEvent(event: DomainEvent) {
        try {
            val payload = objectMapper.convertValue(event, Map::class.java)
                .filterKeys { it != "eventId" && it != "occurredAt" }
                .mapKeys { it.key.toString() }

            val auditEvent = AuditEvent(
                eventType = event.eventType,
                aggregateType = event.aggregateType,
                aggregateId = event.aggregateId,
                actorId = event.actorId,
                payload = payload,
                occurredAt = event.occurredAt,
            )
            auditRepo.save(auditEvent)
            log.debug("Recorded audit event: {} for {}/{}", event.eventType, event.aggregateType, event.aggregateId)
        } catch (ex: Exception) {
            log.error("Failed to record audit event: {}", event.eventType, ex)
        }
    }
}
