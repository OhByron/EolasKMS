package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.dto.UserProfileResponse
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
) {

    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): ApiResponse<UserProfileResponse> {
        val keycloakId = UUID.fromString(jwt.subject)
        val profile = userProfileService.findByKeycloakId(keycloakId)
            ?: throw NoSuchElementException("User profile not found")
        return ApiResponse(data = profile)
    }
}
