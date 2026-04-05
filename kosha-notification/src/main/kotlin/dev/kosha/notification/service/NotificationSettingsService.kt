package dev.kosha.notification.service

import dev.kosha.identity.repository.DepartmentRepository
import dev.kosha.notification.dto.DepartmentScanSettingsResponse
import dev.kosha.notification.dto.INTERVAL_OPTIONS
import dev.kosha.notification.dto.NotificationSettingsResponse
import dev.kosha.notification.dto.ScanIntervals
import dev.kosha.notification.dto.UpdateDepartmentScanSettingsRequest
import dev.kosha.notification.dto.UpdateNotificationSettingsRequest
import dev.kosha.notification.entity.NotificationSettings
import dev.kosha.notification.repository.NotificationSettingsRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
@Transactional(readOnly = true)
class NotificationSettingsService(
    private val settingsRepo: NotificationSettingsRepository,
    private val departmentRepo: DepartmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── Global settings ──────────────────────────────────────────

    fun getGlobalSettings(): NotificationSettingsResponse {
        val settings = settingsRepo.findById("default")
            .orElseGet { settingsRepo.save(NotificationSettings()) }
        return settings.toResponse()
    }

    @Transactional
    fun updateGlobalSettings(request: UpdateNotificationSettingsRequest): NotificationSettingsResponse {
        require(request.defaultScanIntervalHours in ScanIntervals.VALID) {
            "Invalid scan interval: must be one of ${ScanIntervals.VALID}"
        }

        val settings = settingsRepo.findById("default").orElseGet { NotificationSettings() }
        require(request.defaultScanIntervalHours >= settings.minScanIntervalHours) {
            "Default scan interval must be at least ${settings.minScanIntervalHours}h"
        }
        settings.defaultScanIntervalHours = request.defaultScanIntervalHours
        settings.updatedAt = OffsetDateTime.now()

        val saved = settingsRepo.save(settings)
        log.info("Global scan interval default updated to {}h", saved.defaultScanIntervalHours)
        return saved.toResponse()
    }

    /**
     * Returns the effective interval in hours for a specific department:
     * the department's override if set, otherwise the global default.
     */
    fun effectiveIntervalFor(departmentId: UUID): Int {
        val dept = departmentRepo.findById(departmentId).orElse(null)
        val override = dept?.scanIntervalHours
        if (override != null) return override
        return getGlobalSettings().defaultScanIntervalHours
    }

    // ── Per-department settings ──────────────────────────────────

    fun getDepartmentScanSettings(departmentId: UUID): DepartmentScanSettingsResponse {
        val dept = departmentRepo.findById(departmentId)
            .orElseThrow { NoSuchElementException("Department not found: $departmentId") }
        val globalDefault = getGlobalSettings().defaultScanIntervalHours
        val effective = dept.scanIntervalHours ?: globalDefault

        return DepartmentScanSettingsResponse(
            departmentId = dept.id!!,
            departmentName = dept.name,
            scanIntervalHours = dept.scanIntervalHours,
            effectiveIntervalHours = effective,
            inheritsDefault = dept.scanIntervalHours == null,
            lastScanAt = dept.lastScanAt,
            validIntervals = INTERVAL_OPTIONS,
        )
    }

    @Transactional
    fun updateDepartmentScanSettings(
        departmentId: UUID,
        request: UpdateDepartmentScanSettingsRequest,
    ): DepartmentScanSettingsResponse {
        val dept = departmentRepo.findById(departmentId)
            .orElseThrow { NoSuchElementException("Department not found: $departmentId") }

        val settings = getGlobalSettings()
        val newValue = request.scanIntervalHours
        if (newValue != null) {
            require(newValue in ScanIntervals.VALID) {
                "Invalid scan interval: must be one of ${ScanIntervals.VALID}"
            }
            require(newValue >= settings.minScanIntervalHours) {
                "Scan interval must be at least ${settings.minScanIntervalHours}h"
            }
        }

        dept.scanIntervalHours = newValue
        departmentRepo.save(dept)
        log.info(
            "Department {} scan interval set to {}",
            dept.name,
            newValue?.toString() ?: "inherit (${settings.defaultScanIntervalHours}h)"
        )
        return getDepartmentScanSettings(departmentId)
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun NotificationSettings.toResponse() = NotificationSettingsResponse(
        defaultScanIntervalHours = defaultScanIntervalHours,
        minScanIntervalHours = minScanIntervalHours,
        validIntervals = INTERVAL_OPTIONS,
        updatedAt = updatedAt,
    )
}
