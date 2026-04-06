package dev.kosha.app.licence

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.repository.UserProfileRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin/licence")
class LicenceController(
    private val licenceService: LicenceService,
    private val userProfileRepo: UserProfileRepository,
) {

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun getLicence(): ApiResponse<LicenceSummary> =
        ApiResponse(data = licenceService.getSummary())

    @PatchMapping
    @PreAuthorize("hasRole('GLOBAL_ADMIN')")
    fun applyLicence(
        @RequestBody request: ApplyLicenceRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<LicenceSummary> {
        val actorId = resolveUserId(jwt)
        return ApiResponse(data = licenceService.applyKey(request.key, actorId))
    }

    private fun resolveUserId(jwt: Jwt): UUID? {
        return try {
            val keycloakId = UUID.fromString(jwt.subject)
            userProfileRepo.findByKeycloakId(keycloakId)?.id
        } catch (_: Exception) { null }
    }
}

data class ApplyLicenceRequest(val key: String)
