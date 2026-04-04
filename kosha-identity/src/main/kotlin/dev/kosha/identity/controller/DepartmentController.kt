package dev.kosha.identity.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.identity.dto.CreateDepartmentRequest
import dev.kosha.identity.dto.DepartmentResponse
import dev.kosha.identity.dto.UpdateDepartmentRequest
import dev.kosha.identity.service.DepartmentService
import dev.kosha.identity.service.UserProfileService
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
) {

    @GetMapping
    fun list(@PageableDefault(size = 20) pageable: Pageable): ApiResponse<List<DepartmentResponse>> {
        val page = departmentService.findAll(pageable)
        return ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/departments?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = departmentService.findById(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun create(@RequestBody request: CreateDepartmentRequest) =
        ApiResponse(data = departmentService.create(request))

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateDepartmentRequest) =
        ApiResponse(data = departmentService.update(id, request))

    @GetMapping("/{id}/users")
    fun listUsers(@PathVariable id: UUID, @PageableDefault(size = 20) pageable: Pageable) =
        userProfileService.findByDepartment(id, pageable).let { page ->
            ApiResponse(
                data = page.content,
                meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
                links = Links(self = "/api/v1/departments/$id/users?page=${page.number}&size=${page.size}"),
            )
        }
}
