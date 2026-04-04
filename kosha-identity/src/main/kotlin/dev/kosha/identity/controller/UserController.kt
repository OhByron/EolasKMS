package dev.kosha.identity.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.identity.dto.CreateUserProfileRequest
import dev.kosha.identity.dto.UpdateUserProfileRequest
import dev.kosha.identity.dto.UserProfileResponse
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
@RequestMapping("/api/v1/users")
class UserController(
    private val userProfileService: UserProfileService,
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun list(@PageableDefault(size = 20) pageable: Pageable): ApiResponse<List<UserProfileResponse>> {
        val page = userProfileService.findAll(pageable)
        return ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/users?page=${page.number}&size=${page.size}"),
        )
    }

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID) =
        ApiResponse(data = userProfileService.findById(id))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun create(@RequestBody request: CreateUserProfileRequest) =
        ApiResponse(data = userProfileService.create(request))

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateUserProfileRequest) =
        ApiResponse(data = userProfileService.update(id, request))
}
