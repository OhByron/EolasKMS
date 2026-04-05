package dev.kosha.notification.service

import dev.kosha.notification.dto.UpdateWorkflowEscalationSettingsRequest
import dev.kosha.notification.dto.WorkflowEscalationSettingsResponse
import dev.kosha.notification.entity.WorkflowEscalationSettings
import dev.kosha.notification.repository.WorkflowEscalationSettingsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Get/update the singleton row controlling the workflow escalation scanner.
 *
 * The scanner itself also reads this row on every tick (via a native query
 * to avoid a cross-module dependency), so updates take effect on the next
 * scanner tick without a restart.
 */
@Service
class WorkflowEscalationSettingsService(
    private val repo: WorkflowEscalationSettingsRepository,
) {

    companion object {
        /**
         * Closed list of valid intervals. Anchored to the same set encoded
         * in the V027 CHECK constraint — if you add a value here, also add
         * it to the migration (or add a follow-up migration to relax the
         * constraint). The minimum is 5 minutes to protect the database
         * from an over-eager admin.
         */
        val VALID_INTERVALS: List<Pair<Int, String>> = listOf(
            5 to "Every 5 minutes",
            15 to "Every 15 minutes",
            30 to "Every 30 minutes",
            60 to "Every hour",
            180 to "Every 3 hours",
            360 to "Every 6 hours",
        )
    }

    @Transactional(readOnly = true)
    fun get(): WorkflowEscalationSettingsResponse {
        val settings = repo.findById("default")
            .orElseGet { repo.save(WorkflowEscalationSettings()) }
        return settings.toResponse()
    }

    @Transactional
    fun update(request: UpdateWorkflowEscalationSettingsRequest): WorkflowEscalationSettingsResponse {
        val allowedMinutes = VALID_INTERVALS.map { it.first }
        require(request.scanIntervalMinutes in allowedMinutes) {
            "scanIntervalMinutes must be one of $allowedMinutes"
        }

        val settings = repo.findById("default").orElseGet { WorkflowEscalationSettings() }
        settings.scanIntervalMinutes = request.scanIntervalMinutes
        settings.updatedAt = OffsetDateTime.now()
        return repo.save(settings).toResponse()
    }

    private fun WorkflowEscalationSettings.toResponse() = WorkflowEscalationSettingsResponse(
        scanIntervalMinutes = scanIntervalMinutes,
        lastScanAt = lastScanAt,
        updatedAt = updatedAt,
        validIntervals = VALID_INTERVALS.map {
            WorkflowEscalationSettingsResponse.IntervalOption(it.first, it.second)
        },
    )
}
