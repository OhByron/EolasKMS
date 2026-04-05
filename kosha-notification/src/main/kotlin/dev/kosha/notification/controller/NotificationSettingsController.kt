package dev.kosha.notification.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.notification.dto.UpdateDepartmentScanSettingsRequest
import dev.kosha.notification.dto.UpdateNotificationSettingsRequest
import dev.kosha.notification.service.NotificationSettingsService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
// TODO: restore @PreAuthorize("hasRole('GLOBAL_ADMIN')") on global + hasAnyRole
// on department endpoints once Keycloak dev roles are wired up.
class NotificationSettingsController(
    private val settingsService: NotificationSettingsService,
) {

    // ── Global notification settings (GLOBAL_ADMIN only) ─────────

    @GetMapping("/admin/notification-settings")
    fun getGlobalSettings() =
        ApiResponse(data = settingsService.getGlobalSettings())

    @PutMapping("/admin/notification-settings")
    fun updateGlobalSettings(@RequestBody request: UpdateNotificationSettingsRequest) =
        ApiResponse(data = settingsService.updateGlobalSettings(request))

    // ── Per-department scan settings (DEPT_ADMIN of that dept, or GLOBAL_ADMIN) ──

    @GetMapping("/departments/{id}/scan-settings")
    fun getDepartmentScanSettings(@PathVariable id: UUID) =
        ApiResponse(data = settingsService.getDepartmentScanSettings(id))

    @PutMapping("/departments/{id}/scan-settings")
    fun updateDepartmentScanSettings(
        @PathVariable id: UUID,
        @RequestBody request: UpdateDepartmentScanSettingsRequest,
    ) = ApiResponse(data = settingsService.updateDepartmentScanSettings(id, request))
}
