package dev.kosha.identity.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.identity.dto.CreateDepartmentRequest
import dev.kosha.identity.dto.DepartmentResponse
import dev.kosha.identity.dto.ProvisionUserRequest
import dev.kosha.identity.dto.UpdateDepartmentRequest
import dev.kosha.identity.service.DepartmentService
import dev.kosha.identity.service.UserProfileService
import dev.kosha.identity.service.UserCreationService
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
@RequestMapping("/api/v1/departments")
class DepartmentController(
    private val departmentService: DepartmentService,
    private val userProfileService: UserProfileService,
    private val userCreationService: UserCreationService,
) {

    // Department listing and read-by-id are open to any authenticated user
    // (the upload form, admin hub, and every dropdown need them).
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(@PageableDefault(size = 20) pageable: Pageable): ApiResponse<List<DepartmentResponse>> {
        val page = departmentService.findAll(pageable)
        return ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/departments?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = departmentService.findById(id))

    // Creating a department is global-admin-only. No department-level scoping
    // possible because there is no pre-existing department to scope against.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun create(@RequestBody request: CreateDepartmentRequest) =
        ApiResponse(data = departmentService.create(request))

    // Updating is dept-scoped. Note that the `handlesLegalReview` flag in
    // the request must additionally be global-admin-only — enforced in the
    // service layer since @PreAuthorize can't inspect request body fields.
    @PatchMapping("/{id}")
    @PreAuthorize("@authorityService.canEditDepartment(authentication, #id)")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateDepartmentRequest) =
        ApiResponse(data = departmentService.update(id, request))

    // Listing department members is dept-scoped so dept admins don't get a
    // peek at other departments' rosters.
    @GetMapping("/{id}/users")
    @PreAuthorize("@authorityService.canEditDepartment(authentication, #id)")
    fun listUsers(@PathVariable id: UUID, @PageableDefault(size = 20) pageable: Pageable) =
        userProfileService.findByDepartment(id, pageable).let { page ->
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
                links = Links(self = "/api/v1/departments/$id/users?page=${page.number}&size=${page.size}"),
            )
        }

    /**
     * Provision a new user directly into this department. Convenience endpoint
     * that pre-fills the departmentId — the frontend uses this from the
     * department detail page's "Add member" button.
     */
    @PostMapping("/{id}/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@authorityService.canEditDepartment(authentication, #id)")
    fun provisionUserInDepartment(
        @PathVariable id: UUID,
        @RequestBody request: ProvisionUserRequest,
    ) = ApiResponse(
        // Force the departmentId from the path so the frontend can't override
        // it. DEPT_ADMIN authority is scoped to their own department and we
        // don't trust the body.
        data = userCreationService.provision(request.copy(departmentId = id))
    )
}
