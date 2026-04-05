package dev.kosha.workflow.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.workflow.service.ActionResult
import dev.kosha.workflow.service.WorkflowActionService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Approve/reject endpoints for running workflow instances.
 *
 * Contract (matches the frontend `api.workflows` object defined months ago):
 *   POST /api/v1/workflows/{workflowInstanceId}/steps/{stepInstanceId}/approve
 *   POST /api/v1/workflows/{workflowInstanceId}/steps/{stepInstanceId}/reject
 *
 * Body: `{ "comments": string | null }`. Comments are mandatory on reject.
 *
 * The assignee check lives inside [WorkflowActionService.loadActiveStep] —
 * the controller's only job is to resolve the JWT into a user-profile id
 * and delegate.
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowActionController(
    private val actionService: WorkflowActionService,
    private val userProfileRepo: UserProfileRepository,
) {

    @PostMapping("/{workflowInstanceId}/steps/{stepInstanceId}/approve")
    @ResponseStatus(HttpStatus.OK)
    fun approve(
        @PathVariable workflowInstanceId: UUID,
        @PathVariable stepInstanceId: UUID,
        @RequestBody request: WorkflowActionRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<ActionResult> {
        val actorId = resolveUserId(jwt)
        return ApiResponse(
            data = actionService.approve(
                workflowInstanceId = workflowInstanceId,
                stepInstanceId = stepInstanceId,
                actorId = actorId,
                comments = request.comments,
            ),
        )
    }

    @PostMapping("/{workflowInstanceId}/steps/{stepInstanceId}/reject")
    @ResponseStatus(HttpStatus.OK)
    fun reject(
        @PathVariable workflowInstanceId: UUID,
        @PathVariable stepInstanceId: UUID,
        @RequestBody request: WorkflowActionRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ApiResponse<ActionResult> {
        val actorId = resolveUserId(jwt)
        return ApiResponse(
            data = actionService.reject(
                workflowInstanceId = workflowInstanceId,
                stepInstanceId = stepInstanceId,
                actorId = actorId,
                comments = request.comments,
            ),
        )
    }

    /**
     * Translate the Keycloak JWT subject into a user_profile row id. Kosha
     * stores its own user id as the canonical actor identifier everywhere
     * (audit log, ownership, workflow assignment) so every JWT-authenticated
     * endpoint goes through this indirection once per request.
     */
    private fun resolveUserId(jwt: Jwt): UUID {
        val keycloakId = UUID.fromString(jwt.subject)
        val profile = userProfileRepo.findByKeycloakId(keycloakId)
            ?: throw NoSuchElementException(
                "No user profile for Keycloak id $keycloakId — account was not provisioned via Eòlas",
            )
        return profile.id!!
    }
}

data class WorkflowActionRequest(
    val comments: String? = null,
)
