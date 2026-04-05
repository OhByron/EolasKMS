package dev.kosha.notification.dto

import java.time.OffsetDateTime

/**
 * View shape returned to the admin UI. [validIntervals] is included so the
 * dropdown can render the same closed list the service layer enforces,
 * without the frontend having to hard-code it.
 */
data class WorkflowEscalationSettingsResponse(
    val scanIntervalMinutes: Int,
    val lastScanAt: OffsetDateTime?,
    val updatedAt: OffsetDateTime,
    val validIntervals: List<IntervalOption>,
) {
    data class IntervalOption(val minutes: Int, val label: String)
}

data class UpdateWorkflowEscalationSettingsRequest(
    val scanIntervalMinutes: Int,
)
