package dev.kosha.app.controller

import dev.kosha.common.api.ApiResponse
import dev.kosha.common.api.PageMeta
import dev.kosha.document.repository.DocumentRepository
import dev.kosha.identity.repository.UserProfileRepository
import dev.kosha.workflow.repository.WorkflowStepInstanceRepository
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * "My tasks" endpoint powering the review inbox. Lives in `kosha-app`
 * rather than `kosha-workflow` because it joins workflow step instances
 * with documents and users — two modules the workflow module does not
 * depend on. The app module is the right place for cross-module glue.
 *
 * The query returns only IN_PROGRESS steps assigned to the caller. WAITING
 * steps (later in a LINEAR sequence) are intentionally excluded so users
 * don't see tasks they can't yet act on. REJECTED/APPROVED are omitted
 * because the inbox is for pending work.
 */
@RestController
@RequestMapping("/api/v1/my")
class WorkflowTasksController(
    private val stepInstanceRepo: WorkflowStepInstanceRepository,
    private val documentRepo: DocumentRepository,
    private val userProfileRepo: UserProfileRepository,
) {

    @GetMapping("/workflow-tasks")
    @PreAuthorize("hasAnyRole('GLOBAL_ADMIN', 'DEPT_ADMIN', 'EDITOR', 'CONTRIBUTOR')")
    fun myTasks(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ApiResponse<List<ReviewTaskDto>> {
        val keycloakId = UUID.fromString(jwt.subject)
        val me = userProfileRepo.findByKeycloakId(keycloakId)
            ?: return ApiResponse(
                data = emptyList(),
                meta = PageMeta(page = page, size = size, total = 0),
            )

        val steps = stepInstanceRepo.findActiveTasksForUser(me.id!!)

        val tasks = steps.mapNotNull { step ->
            val instance = step.workflowInstance
            // Pull the document for display info. Any soft-deleted document
            // is silently dropped from the inbox — the task is stale.
            val doc = documentRepo.findById(instance.documentId).orElse(null)
            if (doc == null || doc.isDeleted) return@mapNotNull null
            val submitter = instance.initiatedBy
            ReviewTaskDto(
                documentId = doc.id!!,
                documentTitle = doc.title,
                documentDepartment = doc.department.name,
                submittedBy = submitter.id!!,
                submittedByName = submitter.displayName,
                submittedAt = instance.startedAt.toString(),
                workflowInstanceId = instance.id!!,
                stepInstanceId = step.id!!,
                stepName = step.stepDefinition.name,
                status = step.status,
            )
        }

        // Manual pagination — the query is already bounded by "assigned to
        // me and IN_PROGRESS" which in practice is a handful of rows, so
        // paging in memory is fine for v1. A proper paginated query can
        // replace this if users accumulate hundreds of pending tasks.
        val from = (page * size).coerceAtMost(tasks.size)
        val to = (from + size).coerceAtMost(tasks.size)
        return ApiResponse(
            data = tasks.subList(from, to),
            meta = PageMeta(page = page, size = size, total = tasks.size.toLong()),
        )
    }
}

/**
 * Mirrors the frontend `ReviewTask` interface in `kosha-web/src/lib/types/api.ts`.
 * Kept inline to this controller because nothing else produces this shape.
 */
data class ReviewTaskDto(
    val documentId: UUID,
    val documentTitle: String,
    val documentDepartment: String,
    val submittedBy: UUID,
    val submittedByName: String,
    val submittedAt: String,
    val workflowInstanceId: UUID,
    val stepInstanceId: UUID,
    val stepName: String,
    val status: String,
)
