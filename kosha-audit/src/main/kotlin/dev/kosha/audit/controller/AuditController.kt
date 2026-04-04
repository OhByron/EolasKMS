package dev.kosha.audit.controller

import dev.kosha.audit.entity.AuditEvent
import dev.kosha.audit.repository.AuditEventRepository
import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class AuditController(
    private val auditRepo: AuditEventRepository,
) {

    @GetMapping("/events")
    fun listEvents(@PageableDefault(size = 50) pageable: Pageable) =
        auditRepo.findAllByOrderByOccurredAtDesc(pageable).let { page ->
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
                links = Links(self = "/api/v1/audit/events?page=${page.number}&size=${page.size}"),
            )
        }

    @GetMapping("/events/by-aggregate/{type}/{id}")
    fun byAggregate(@PathVariable type: String, @PathVariable id: UUID): ApiResponse<List<AuditEvent>> =
        ApiResponse(data = auditRepo.findByAggregateTypeAndAggregateIdOrderByOccurredAtDesc(type, id))

    @GetMapping("/events/by-actor/{actorId}")
    fun byActor(@PathVariable actorId: UUID, @PageableDefault(size = 50) pageable: Pageable) =
        auditRepo.findByActorIdOrderByOccurredAtDesc(actorId, pageable).let { page ->
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            )
        }
}
