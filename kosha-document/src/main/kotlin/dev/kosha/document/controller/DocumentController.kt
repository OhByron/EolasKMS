package dev.kosha.document.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.document.dto.CreateDocumentRequest
import dev.kosha.document.dto.CreateVersionRequest
import dev.kosha.document.dto.UpdateDocumentRequest
import dev.kosha.document.service.DocumentService
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
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
@RequestMapping("/api/v1/documents")
class DocumentController(
    private val documentService: DocumentService,
    private val userProfileRepo: UserProfileRepository,
) {

    @GetMapping
    fun list(
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false) departmentId: UUID?,
    ) =
        (if (departmentId != null) {
            documentService.findByDepartment(departmentId, pageable)
        } else {
            documentService.findAll(pageable)
        }).let { page ->
            val deptSuffix = if (departmentId != null) "&departmentId=$departmentId" else ""
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
                links = Links(self = "/api/v1/documents?page=${page.number}&size=${page.size}$deptSuffix"),
            )
        }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = documentService.findById(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateDocumentRequest, @AuthenticationPrincipal jwt: Jwt) =
        ApiResponse(data = documentService.create(request, resolveUserId(jwt)))

    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateDocumentRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ) = ApiResponse(data = documentService.update(id, request, resolveUserId(jwt)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt) =
        documentService.softDelete(id, resolveUserId(jwt))

    @PostMapping("/{id}/checkout")
    fun checkout(@PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt) =
        ApiResponse(data = documentService.checkout(id, resolveUserId(jwt)))

    @PostMapping("/{id}/checkin")
    fun checkin(@PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt) =
        ApiResponse(data = documentService.checkin(id, resolveUserId(jwt)))

    // --- Versions ---

    @GetMapping("/{id}/versions")
    fun listVersions(@PathVariable id: UUID) =
        ApiResponse(data = documentService.listVersions(id))

    @PostMapping("/{id}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    fun createVersion(
        @PathVariable id: UUID,
        @RequestBody request: CreateVersionRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ) = ApiResponse(data = documentService.createVersion(id, request, resolveUserId(jwt)))

    private fun resolveUserId(jwt: Jwt): UUID {
        val keycloakId = UUID.fromString(jwt.subject)
        return userProfileRepo.findByKeycloakId(keycloakId)?.id
            ?: throw NoSuchElementException("User profile not found")
    }
}
