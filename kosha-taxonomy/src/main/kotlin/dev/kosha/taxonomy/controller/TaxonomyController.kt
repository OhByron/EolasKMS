package dev.kosha.taxonomy.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.taxonomy.dto.CreateTermRequest
import dev.kosha.taxonomy.dto.UpdateTermRequest
import dev.kosha.taxonomy.service.TaxonomyService
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/taxonomy")
class TaxonomyController(
    private val taxonomyService: TaxonomyService,
) {

    @GetMapping("/terms")
    @PreAuthorize("isAuthenticated()")
    fun listTerms(@RequestParam(required = false) format: String?): ApiResponse<Any> {
        return if (format == "tree") {
            ApiResponse(data = taxonomyService.buildTree())
        } else {
            ApiResponse(data = taxonomyService.findAll())
        }
    }

    @GetMapping("/terms/{id}")
    @PreAuthorize("isAuthenticated()")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = taxonomyService.findById(id))

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun create(@RequestBody request: CreateTermRequest) =
        ApiResponse(data = taxonomyService.create(request))

    @PatchMapping("/terms/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateTermRequest) =
        ApiResponse(data = taxonomyService.update(id, request))

    @GetMapping("/terms/check-duplicate")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun checkDuplicate(@RequestParam label: String) =
        ApiResponse(data = taxonomyService.checkDuplicate(label))

    @GetMapping("/candidates")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun listCandidates() =
        ApiResponse(data = taxonomyService.findCandidates())

    @PostMapping("/candidates/{id}/approve")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun approveCandidate(@PathVariable id: UUID) =
        ApiResponse(data = taxonomyService.updateStatus(id, "ACTIVE"))

    @PostMapping("/candidates/{id}/reject")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun rejectCandidate(@PathVariable id: UUID) =
        ApiResponse(data = taxonomyService.updateStatus(id, "DEPRECATED"))

    @GetMapping("/terms/{id}/documents")
    @PreAuthorize("isAuthenticated()")
    fun termDocuments(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
    ) = ApiResponse(data = taxonomyService.findDocumentsByTerm(id, page, size))

    @org.springframework.web.bind.annotation.DeleteMapping("/terms/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun deleteTerm(@PathVariable id: UUID) {
        taxonomyService.deleteTerm(id)
    }
}
