package dev.kosha.workflow.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.workflow.dto.UpdateWorkflowRequest
import dev.kosha.workflow.service.WorkflowDefinitionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Department-scoped workflow configuration endpoints.
 *
 * - `GET /api/v1/departments/{id}/workflow` returns the current workflow, or
 *   404 if the department has no workflow yet (new departments without an
 *   admin fall into this case).
 * - `PUT /api/v1/departments/{id}/workflow` replaces the workflow atomically.
 *   The request body is the full new definition — steps are not diffed.
 * - `GET /api/v1/departments/{id}/workflow/validation` returns whether the
 *   workflow is ready for document submission and a list of problems if not.
 *
 * Authorisation: GLOBAL_ADMIN can edit any department's workflow;
 * DEPT_ADMIN can edit their own department's workflow. Currently bypassed
 * for dev — see SecurityConfig. When restored, enforce on PUT only. Read
 * endpoints stay open to all authenticated users so non-admins can see
 * which assignees exist for documents they submit.
 */
@RestController
@RequestMapping("/api/v1/departments/{departmentId}/workflow")
// TODO: restore method-level @PreAuthorize once Keycloak dev roles are wired up
class WorkflowController(
    private val workflowService: WorkflowDefinitionService,
) {

    @GetMapping
    fun getDepartmentWorkflow(@PathVariable departmentId: UUID): ApiResponse<Any> {
        val workflow = workflowService.findByDepartment(departmentId)
            ?: throw NoSuchElementException(
                "Department $departmentId has no workflow configured"
            )
        return ApiResponse(data = workflow)
    }

    @PutMapping
    fun updateDepartmentWorkflow(
        @PathVariable departmentId: UUID,
        @RequestBody request: UpdateWorkflowRequest,
    ) = ApiResponse(
        data = workflowService.updateDepartmentWorkflow(departmentId, request)
    )

    @GetMapping("/validation")
    fun validateWorkflow(@PathVariable departmentId: UUID) =
        ApiResponse(data = workflowService.validateForSubmission(departmentId))
}
