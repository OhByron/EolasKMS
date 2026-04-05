package dev.kosha.notification.dto

import java.time.OffsetDateTime

/**
 * The set of scan interval values Kosha accepts.
 * Enforced at the service layer — cannot be bypassed from the frontend.
 *
 * Lower bound is 24h (daily) because retention compliance requires timely
 * notifications. Admins can slow the cadence but not disable it.
 */
object ScanIntervals {
    const val DAILY: Int = 24
    const val EVERY_TWO_DAYS: Int = 48
    const val EVERY_THREE_DAYS: Int = 72
    const val WEEKLY: Int = 168
    val VALID: Set<Int> = setOf(DAILY, EVERY_TWO_DAYS, EVERY_THREE_DAYS, WEEKLY)
}

data class NotificationSettingsResponse(
    val defaultScanIntervalHours: Int,
    val minScanIntervalHours: Int,
    val validIntervals: List<IntervalOption>,
    val updatedAt: OffsetDateTime,
)

data class IntervalOption(
    val hours: Int,
    val label: String,
)

val INTERVAL_OPTIONS = listOf(
    IntervalOption(ScanIntervals.DAILY, "Daily"),
    IntervalOption(ScanIntervals.EVERY_TWO_DAYS, "Every 2 days"),
    IntervalOption(ScanIntervals.EVERY_THREE_DAYS, "Every 3 days"),
    IntervalOption(ScanIntervals.WEEKLY, "Weekly"),
)

data class UpdateNotificationSettingsRequest(
    val defaultScanIntervalHours: Int,
)

data class DepartmentScanSettingsResponse(
    val departmentId: java.util.UUID,
    val departmentName: String,
    val scanIntervalHours: Int?,      // null = inherits global default
    val effectiveIntervalHours: Int,  // always the value currently in effect
    val inheritsDefault: Boolean,
    val lastScanAt: OffsetDateTime?,
    val validIntervals: List<IntervalOption>,
)

data class UpdateDepartmentScanSettingsRequest(
    /**
     * Null clears the override (falls back to global default).
     * Must be a member of [ScanIntervals.VALID] if non-null.
     */
    val scanIntervalHours: Int?,
)
