package dev.kosha.retention.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.retention.dto.CreateRetentionPolicyRequest
import dev.kosha.retention.dto.UpdateRetentionPolicyRequest
import dev.kosha.retention.service.RetentionPolicyService
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/retention-policies")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class RetentionController(
    private val retentionService: RetentionPolicyService,
) {

    @GetMapping
    fun list(@PageableDefault(size = 50) pageable: Pageable) =
        retentionService.findAll(pageable).let { page ->
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
                links = Links(self = "/api/v1/retention-policies?page=${page.number}&size=${page.size}"),
            )
        }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = retentionService.findById(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateRetentionPolicyRequest) =
        ApiResponse(data = retentionService.create(request))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateRetentionPolicyRequest) =
        ApiResponse(data = retentionService.update(id, request))
}
