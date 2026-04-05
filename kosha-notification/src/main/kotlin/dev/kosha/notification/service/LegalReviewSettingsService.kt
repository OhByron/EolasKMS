package dev.kosha.notification.service

import dev.kosha.notification.dto.LegalReviewSettingsResponse
import dev.kosha.notification.dto.UpdateLegalReviewSettingsRequest
import dev.kosha.notification.entity.LegalReviewSettings
import dev.kosha.notification.repository.LegalReviewSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

@Service
class LegalReviewSettingsService(
    private val repo: LegalReviewSettingsRepository,
) {

    @Transactional(readOnly = true)
    fun get(): LegalReviewSettingsResponse {
        val settings = repo.findById("default")
            .orElseGet { repo.save(LegalReviewSettings()) }
        return LegalReviewSettingsResponse(
            defaultTimeLimitDays = settings.defaultTimeLimitDays,
            updatedAt = settings.updatedAt,
        )
    }

    @Transactional
    fun update(request: UpdateLegalReviewSettingsRequest): LegalReviewSettingsResponse {
        require(request.defaultTimeLimitDays in 1..90) {
            "defaultTimeLimitDays must be between 1 and 90"
        }

        val settings = repo.findById("default").orElseGet { LegalReviewSettings() }
        settings.defaultTimeLimitDays = request.defaultTimeLimitDays
        settings.updatedAt = OffsetDateTime.now()
        val saved = repo.save(settings)

        return LegalReviewSettingsResponse(
            defaultTimeLimitDays = saved.defaultTimeLimitDays,
            updatedAt = saved.updatedAt,
        )
    }
}
