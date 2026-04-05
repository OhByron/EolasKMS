package dev.kosha.notification.dto

import java.time.OffsetDateTime

data class LegalReviewSettingsResponse(
    val defaultTimeLimitDays: Int,
    val updatedAt: OffsetDateTime,
)

/**
 * Update request for the singleton legal review settings row.
 * Only includes fields that are editable by administrators.
 */
data class UpdateLegalReviewSettingsRequest(
    val defaultTimeLimitDays: Int,
)
