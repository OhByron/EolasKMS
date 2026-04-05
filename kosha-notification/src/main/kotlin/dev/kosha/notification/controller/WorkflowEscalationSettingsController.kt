package dev.kosha.notification.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.notification.dto.UpdateWorkflowEscalationSettingsRequest
import dev.kosha.notification.dto.WorkflowEscalationSettingsResponse
import dev.kosha.notification.service.WorkflowEscalationSettingsService
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Singleton settings for the workflow escalation scanner.
 *
 *   GET  /api/v1/admin/workflow-escalation-settings
 *   PUT  /api/v1/admin/workflow-escalation-settings  { scanIntervalMinutes: Int }
 *
 * Authorisation: GLOBAL_ADMIN only. Enforced via class-level @PreAuthorize.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow-escalation-settings")
@PreAuthorize("hasRole('GLOBAL_ADMIN')")
class WorkflowEscalationSettingsController(
    private val service: WorkflowEscalationSettingsService,
) {

    @GetMapping
    fun get(): ApiResponse<WorkflowEscalationSettingsResponse> =
        ApiResponse(data = service.get())

    @PutMapping
    fun update(
        @RequestBody request: UpdateWorkflowEscalationSettingsRequest,
    ): ApiResponse<WorkflowEscalationSettingsResponse> =
        ApiResponse(data = service.update(request))
}
