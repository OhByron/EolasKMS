package dev.kosha.app.licence

import dev.kosha.common.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Public (no authentication) licence endpoints, used by the landing
 * page to gate access behind licence key entry before login.
 *
 * GET  — check whether a licence key has been applied
 * POST — apply a licence key (first-time setup, before any user logs in)
 */
@RestController
@RequestMapping("/api/v1/public/licence")
class PublicLicenceController(
    private val licenceService: LicenceService,
) {

    @GetMapping
    fun getLicenceStatus(): ApiResponse<PublicLicenceStatus> {
        val hasKey = licenceService.licenceId != null
        return ApiResponse(
            data = PublicLicenceStatus(
                hasKey = hasKey,
                tier = if (hasKey) licenceService.effectiveTier else null,
                organisation = if (hasKey) licenceService.organisation else null,
            ),
        )
    }

    @PostMapping
    fun applyLicence(@RequestBody request: ApplyLicenceRequest): ApiResponse<PublicLicenceStatus> {
        licenceService.applyKey(request.key, actorId = null)
        return ApiResponse(
            data = PublicLicenceStatus(
                hasKey = true,
                tier = licenceService.effectiveTier,
                organisation = licenceService.organisation,
            ),
        )
    }
}

data class PublicLicenceStatus(
    val hasKey: Boolean,
    val tier: String?,
    val organisation: String?,
)
