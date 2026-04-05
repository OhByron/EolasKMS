package dev.kosha.identity.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.Links
import dev.kosha.common.api.PageMeta
import dev.kosha.identity.dto.CreateUserProfileRequest
import dev.kosha.identity.dto.ProvisionUserRequest
import dev.kosha.identity.dto.UpdateUserProfileRequest
import dev.kosha.identity.dto.UserProfileResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userProfileService: UserProfileService,
    private val userCreationService: UserCreationService,
) {

    @GetMapping
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')")
    fun list(
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false) departmentId: UUID?,
    ): ApiResponse<List<UserProfileResponse>> {
        val page = if (departmentId != null) {
            userProfileService.findByDepartment(departmentId, pageable)
        } else {
            userProfileService.findAll(pageable)
        }
        val deptSuffix = if (departmentId != null) "&departmentId=$departmentId" else ""
        return ApiResponse(
            data = page.content,
            meta = PageMeta(page = page.number, size = page.size, total = page.totalElements),
            links = Links(self = "/api/v1/users?page=${page.number}&size=${page.size}$deptSuffix"),
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

    /**
     * High-level user creation: provisions both the Keycloak account and the
     * local user_profile row in one atomic operation. Returns a temporary
     * password the admin can share with the new user.
     */
    @PostMapping("/provision")
    @ResponseStatus(HttpStatus.CREATED)
    // TODO: restore @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')") once Keycloak dev roles are wired up.
    fun provision(@RequestBody request: ProvisionUserRequest) =
        ApiResponse(data = userCreationService.provision(request))

    @PatchMapping("/{id}")
    // TODO: restore @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN')") once Keycloak dev roles are wired up.
    fun update(@PathVariable id: UUID, @RequestBody request: UpdateUserProfileRequest) =
        ApiResponse(data = userProfileService.update(id, request))

    /**
     * Admin-initiated password reset. Generates a new temporary password,
     * replaces it in Keycloak, and fires an event that the notification
     * listener picks up to email the new password to the user. The calling
     * admin also receives the password in the response as a fallback for
     * cases where email delivery fails.
     */
    @PostMapping("/{id}/reset-password")
    // TODO: restore @PreAuthorize("hasRole('GLOBAL_ADMIN')") once Keycloak dev roles are wired up.
    fun resetPassword(@PathVariable id: UUID) =
        ApiResponse(data = userCreationService.resetPassword(id, actorId = null))
}
