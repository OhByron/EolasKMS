package dev.kosha.notification.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.notification.dto.UpdateLegalReviewSettingsRequest
import dev.kosha.notification.service.LegalReviewSettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Global admin endpoints for tuning legal review defaults. Singleton row
 * lives in `notif.legal_review_settings`. Currently only holds the default
 * time limit — the time window a chosen legal reviewer has before their
 * step instance escalates to the legal department admin.
 */
@RestController
@RequestMapping("/api/v1/admin/legal-review-settings")
// TODO: restore @PreAuthorize("hasRole('GLOBAL_ADMIN')") once JWT roles are wired up.
class LegalReviewSettingsController(
    private val service: LegalReviewSettingsService,
) {

    @GetMapping
    fun get() = ApiResponse(data = service.get())

    @PutMapping
    fun update(@RequestBody request: UpdateLegalReviewSettingsRequest) =
        ApiResponse(data = service.update(request))
}
