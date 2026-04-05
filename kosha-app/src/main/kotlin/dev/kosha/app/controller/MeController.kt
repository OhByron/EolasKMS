package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.dto.DepartmentResponse
import dev.kosha.identity.dto.UserProfileResponse
import dev.kosha.identity.service.DepartmentService
import dev.kosha.identity.service.UserProfileService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/me")
class MeController(
    private val userProfileService: UserProfileService,
    private val departmentService: DepartmentService,
) {

    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserProfileResponse> {
        val keycloakId = UUID.fromString(jwt.subject)
        val profile = userProfileService.findByKeycloakId(keycloakId)
            ?: throw NoSuchElementException("User profile not found")
        return ApiResponse(data = profile)
    }

    /**
     * Departments the current user is allowed to file documents into. Used
     * by the upload form to scope its department picker so contributors only
     * see their own department while GLOBAL_ADMIN sees all active departments.
     * The backing rule lives in DepartmentService.findUploadableFor so the
     * upload POST can enforce the same check server-side.
     */
    @GetMapping("/uploadable-departments")
    fun uploadableDepartments(
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<List<DepartmentResponse>> {
        val keycloakId = UUID.fromString(jwt.subject)
        return ApiResponse(data = departmentService.findUploadableFor(keycloakId))
    }
}
